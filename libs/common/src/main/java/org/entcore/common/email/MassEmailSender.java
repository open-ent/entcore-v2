package org.entcore.common.email;

import fr.wseduc.webutils.email.EmailSender;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.entcore.common.email.impl.PostgresEmailDto;

import java.util.ArrayList;
import java.util.List;

/**
 * An {@link EmailSender} able to send a batch of e-mails in one call.
 * <p>
 * This interface exists so that callers can rely on {@link #sendEmails(List)} without
 * downcasting to a concrete sender.
 */
public interface MassEmailSender extends EmailSender {

    /**
     * Send a batch of e-mails.
     * <p>
     * The default implementation is "naive": it loops over the mails and calls
     * {@link #sendEmail} once per mail. It is meant for senders that have no native
     * bulk API (SMTP, SendInBlue, GoMail...).
     * <p>
     * Note: the Postgres-specific metadata carried by {@link PostgresEmailDto}
     * (priority, scheduleAt, module, platformId) is ignored on this path, since the
     * underlying transport cannot honour it.
     * {@link org.entcore.common.email.impl.PostgresEmailSender} overrides this method
     * with an efficient single bulk insert that preserves that metadata.
     */
    default Future<MassCreateResults> sendEmails(List<PostgresEmailDto> mails) {
        final MassCreateResults results = new MassCreateResults();
        final List<Future<Void>> futures = new ArrayList<>();
        for (final PostgresEmailDto mail : mails) {
            final Promise<Void> promise = Promise.promise();
            futures.add(promise.future());
            try {
                final String from = mail.getFrom() != null ? mail.getFrom().getMail() : getSenderEmail();
                sendEmail(null, toAddresses(mail.getTo()), from, toAddresses(mail.getCc()),
                        toAddresses(mail.getBcc()), mail.getSubject(), toAttachments(mail),
                        mail.getBody(), null, false, mail.getHeadersJson(), res -> {
                            if (res.succeeded()) {
                                results.getSuccess().incrementAndGet();
                            } else {
                                results.getFailure().incrementAndGet();
                            }
                            promise.complete();
                        });
            } catch (Exception e) {
                results.getFailure().incrementAndGet();
                promise.complete();
            }
        }
        return Future.all(futures).map(results);
    }

    static List<Object> toAddresses(List<PostgresEmailDto.User> users) {
        final List<Object> addresses = new ArrayList<>();
        if (users != null) {
            for (final PostgresEmailDto.User user : users) {
                addresses.add(user.getMail());
            }
        }
        return addresses;
    }

    static JsonArray toAttachments(PostgresEmailDto mail) {
        final JsonArray attachments = new JsonArray();
        if (mail.getAttachments() != null) {
            for (final PostgresEmailDto.Attachment att : mail.getAttachments()) {
                attachments.add(new JsonObject()
                        .put("name", att.getName())
                        .put("content", att.getContent()));
            }
        }
        return attachments;
    }

}