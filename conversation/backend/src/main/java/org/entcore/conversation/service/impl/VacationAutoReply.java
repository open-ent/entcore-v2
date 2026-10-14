/*
 * Copyright © Open ENT, 2026 — AGPL v3.
 * Message d'absence automatique : émission de la réponse aux expéditeurs.
 */
package org.entcore.conversation.service.impl;

import fr.wseduc.webutils.I18n;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.impl.logging.Logger;
import io.vertx.core.impl.logging.LoggerFactory;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.entcore.common.sql.Sql;
import org.entcore.common.sql.SqlStatementsBuilder;
import org.entcore.common.user.UserInfos;
import org.entcore.common.utils.StringUtils;
import org.entcore.conversation.service.ConversationService;
import org.entcore.conversation.service.ConversationService.State;
import org.entcore.conversation.util.VacationMessages;

import java.util.UUID;

/**
 * Déclenché après la délivrance d'un message : pour chaque destinataire dont l'absence est
 * active, une réponse automatique est adressée à l'expéditeur.
 *
 * <p>La réponse est INSÉRÉE DIRECTEMENT (ligne {@code messages} + lignes {@code usermessages}
 * pour l'expéditeur et pour l'absent), sans repasser par {@link ConversationService#send} :
 * c'est ce qui rend l'ANTI-BOUCLE structurel — une réponse automatique ne peut pas en
 * déclencher une autre. La colonne {@code auto_reply} en garde la trace.</p>
 *
 * <p>Deuxième garde-fou : une seule réponse par jour et par expéditeur, réservée de façon
 * atomique par {@link VacationMessages#reserveDailyReply(String, String)}.</p>
 *
 * <p>Le traitement est volontairement « au mieux » : un échec n'affecte jamais l'envoi du
 * message d'origine, déjà délivré quand on arrive ici.</p>
 */
public class VacationAutoReply {

    private static final Logger log = LoggerFactory.getLogger(VacationAutoReply.class);

    /** Taille de la colonne « subject » de conversation.messages. */
    private static final int SUBJECT_MAX_LENGTH = 1024;

    private static final VacationAutoReply INSTANCE = new VacationAutoReply();

    private final Sql sql = Sql.getInstance();
    private String messageTable = "conversation.messages";
    private String userMessageTable = "conversation.usermessages";

    public static VacationAutoReply getInstance() { return INSTANCE; }

    /** À appeler une fois au démarrage du module, avec le schéma SQL de la messagerie. */
    public void init(String schema) {
        this.messageTable = schema + ".messages";
        this.userMessageTable = schema + ".usermessages";
    }

    /**
     * À appeler après la délivrance effective d'un message.
     *
     * @param sender       expéditeur du message d'origine (destinataire des réponses automatiques)
     * @param recipientIds destinataires résolus du message d'origine ({@code allUsers})
     * @param subject      objet du message d'origine, rappelé dans l'objet de la réponse
     * @param isAutoReply  vrai si le message d'origine est lui-même une réponse automatique
     */
    public void onMessageDelivered(final UserInfos sender, final JsonArray recipientIds,
            final String subject, final boolean isAutoReply) {
        final VacationMessages vacations = VacationMessages.getInstance();
        if (isAutoReply || !vacations.isFeatureEnabled() || sender == null
                || sender.getUserId() == null || recipientIds == null) {
            return;
        }
        final String senderId = sender.getUserId();

        int replied = 0;
        for (Object o : recipientIds) {
            if (!(o instanceof String)) continue;
            final String recipientId = (String) o;
            if (senderId.equals(recipientId)) continue;

            final JsonObject settings = vacations.activeFor(recipientId);
            if (settings == null) continue;

            if (++replied > vacations.getMaxRepliesPerMessage()) {
                log.info("[Vacation] plafond de " + vacations.getMaxRepliesPerMessage()
                        + " réponses automatiques atteint pour un message de " + senderId);
                break;
            }

            vacations.reserveDailyReply(recipientId, senderId)
                .compose(allowed -> Boolean.TRUE.equals(allowed)
                        ? reply(recipientId, settings, sender, subject)
                        : Future.<Void>succeededFuture())
                .onFailure(th -> log.error("[Vacation] réponse automatique de " + recipientId
                        + " à " + senderId + " échouée : " + th.getMessage()));
        }
    }

    /** Insère la réponse automatique dans la boîte de réception de l'expéditeur. */
    private Future<Void> reply(final String absentUserId, final JsonObject settings,
            final UserInfos sender, final String originalSubject) {
        final String id = UUID.randomUUID().toString();
        final String absentName = settings.getString("displayName", "");
        final String senderName = getOrEmpty(sender.getUsername());

        final JsonObject message = new JsonObject()
                .put("id", id)
                .put("subject", buildSubject(settings, originalSubject))
                .put("body", settings.getString("body", ""))
                .put("from", absentUserId)
                .put("to", new JsonArray().add(sender.getUserId()))
                .put("cc", new JsonArray())
                // Format attendu par DecodedDisplayName : « id$displayName$name$groupDisplayName ».
                // Pour un usager les deux derniers champs valent un espace (cf. le coalesce du Cypher).
                .put("displayNames", new JsonArray()
                        .add(absentUserId + "$" + absentName + "$ $ ")
                        .add(sender.getUserId() + "$" + senderName + "$ $ "))
                .put("state", State.SENT.name())
                .put("date", System.currentTimeMillis())
                // Fil autonome : la réponse automatique ne s'insère pas dans la conversation d'origine.
                .put("thread_id", id)
                .put("auto_reply", true);

        final Integer contentVersion = settings.getInteger("contentVersion");
        if (contentVersion != null) message.put("content_version", contentVersion);

        final SqlStatementsBuilder builder = new SqlStatementsBuilder();
        builder.insert(messageTable, message, "id");
        // Boîte de réception de l'expéditeur : non lu.
        builder.insert(userMessageTable, new JsonObject()
                .put("user_id", sender.getUserId())
                .put("message_id", id)
                .put("unread", true));
        // Boîte d'envoi de l'absent : il voit ce qui a été envoyé en son nom, déjà lu.
        builder.insert(userMessageTable, new JsonObject()
                .put("user_id", absentUserId)
                .put("message_id", id)
                .put("unread", false));

        final Promise<Void> promise = Promise.promise();
        sql.transaction(builder.build(), res -> {
            if ("ok".equalsIgnoreCase(res.body().getString("status"))) {
                log.info("[Vacation] réponse automatique de " + absentUserId
                        + " envoyée à " + sender.getUserId());
                promise.complete();
            } else {
                promise.fail(res.body().getString("message", "unknown error"));
            }
        });
        return promise.future();
    }

    /**
     * « Message d'absence – &lt;objet d'origine&gt; », dans la langue de l'absent (figée à
     * l'enregistrement de ses réglages), tronqué à la taille de la colonne.
     */
    private static String buildSubject(final JsonObject settings, final String originalSubject) {
        final String prefix = I18n.getInstance().translate("conversation.vacation.reply.subject",
                settings.getString("host"), settings.getString("lang"));
        final String base = StringUtils.isEmpty(originalSubject) ? "" : originalSubject.trim();
        final String subject = base.isEmpty() ? prefix : (prefix + " – " + base);
        return subject.length() > SUBJECT_MAX_LENGTH ? subject.substring(0, SUBJECT_MAX_LENGTH) : subject;
    }

    private static String getOrEmpty(String s) { return s == null ? "" : s; }
}
