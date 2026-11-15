package org.entcore.common.email;

import fr.wseduc.webutils.Either;
import fr.wseduc.webutils.email.Bounce;
import fr.wseduc.webutils.email.EmailSender;
import io.vertx.core.AsyncResult;
import io.vertx.core.Handler;
import io.vertx.core.eventbus.Message;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.Date;
import java.util.List;

/**
 * Wraps a plain {@link EmailSender} (e.g. the SMTP / SendInBlue / GoMail senders from
 * web-utils, which cannot implement {@link MassEmailSender} directly) so that it exposes
 * {@link #sendEmails(List)} through the naive default implementation.
 */
public class SimpleMassEmailSender implements MassEmailSender {

    private final EmailSender delegate;

    public SimpleMassEmailSender(EmailSender delegate) {
        this.delegate = delegate;
    }

    @Override
    public String getSenderEmail() {
        return delegate.getSenderEmail();
    }

    @Override
    public String getHost(HttpServerRequest request) {
        return delegate.getHost(request);
    }

    @Override
    public void hardBounces(Date date, Handler<Either<String, List<Bounce>>> handler) {
        delegate.hardBounces(date, handler);
    }

    @Override
    public void hardBounces(Date startDate, Date endDate, Handler<Either<String, List<Bounce>>> handler) {
        delegate.hardBounces(startDate, endDate, handler);
    }

    @Override
    public void sendEmail(HttpServerRequest request, String to, String cc, String bcc,
                          String subject, String templateBody, JsonObject templateParams,
                          boolean translateSubject, Handler<AsyncResult<Message<JsonObject>>> handler) {
        delegate.sendEmail(request, to, cc, bcc, subject, templateBody, templateParams, translateSubject, handler);
    }

    @Override
    public void sendEmail(HttpServerRequest request, String to, String cc, String bcc,
                          String subject, JsonArray attachments, String templateBody, JsonObject templateParams,
                          boolean translateSubject, Handler<AsyncResult<Message<JsonObject>>> handler) {
        delegate.sendEmail(request, to, cc, bcc, subject, attachments, templateBody, templateParams, translateSubject, handler);
    }

    @Override
    public void sendEmail(HttpServerRequest request, String to, String cc, String bcc,
                          String subject, String templateBody, JsonObject templateParams,
                          boolean translateSubject, JsonArray headers, Handler<AsyncResult<Message<JsonObject>>> handler) {
        delegate.sendEmail(request, to, cc, bcc, subject, templateBody, templateParams, translateSubject, headers, handler);
    }

    @Override
    public void sendEmail(HttpServerRequest request, String to, String from, String cc, String bcc,
                          String subject, String templateBody, JsonObject templateParams,
                          boolean translateSubject, Handler<AsyncResult<Message<JsonObject>>> handler) {
        delegate.sendEmail(request, to, from, cc, bcc, subject, templateBody, templateParams, translateSubject, handler);
    }

    @Override
    public void sendEmail(HttpServerRequest request, String to, String from, String cc, String bcc,
                          String subject, String templateBody, JsonObject templateParams,
                          boolean translateSubject, JsonArray headers, Handler<AsyncResult<Message<JsonObject>>> handler) {
        delegate.sendEmail(request, to, from, cc, bcc, subject, templateBody, templateParams, translateSubject, headers, handler);
    }

    @Override
    public void sendEmail(HttpServerRequest request, String to, String from, String cc, String bcc,
                          String subject, JsonArray attachments, String templateBody, JsonObject templateParams,
                          boolean translateSubject, JsonArray headers, Handler<AsyncResult<Message<JsonObject>>> handler) {
        delegate.sendEmail(request, to, from, cc, bcc, subject, attachments, templateBody, templateParams, translateSubject, headers, handler);
    }

    @Override
    public void sendEmail(HttpServerRequest request, List<Object> to, List<Object> cc, List<Object> bcc,
                          String subject, String templateBody, JsonObject templateParams,
                          boolean translateSubject, Handler<AsyncResult<Message<JsonObject>>> handler) {
        delegate.sendEmail(request, to, cc, bcc, subject, templateBody, templateParams, translateSubject, handler);
    }

    @Override
    public void sendEmail(HttpServerRequest request, List<Object> to, String from, List<Object> cc, List<Object> bcc,
                          String subject, String templateBody, JsonObject templateParams,
                          boolean translateSubject, JsonArray headers, Handler<AsyncResult<Message<JsonObject>>> handler) {
        delegate.sendEmail(request, to, from, cc, bcc, subject, templateBody, templateParams, translateSubject, headers, handler);
    }

    @Override
    public void sendEmail(HttpServerRequest request, List<Object> to, String from, List<Object> cc, List<Object> bcc,
                          String subject, JsonArray attachments, String templateBody, JsonObject templateParams,
                          boolean translateSubject, JsonArray headers, Handler<AsyncResult<Message<JsonObject>>> handler) {
        delegate.sendEmail(request, to, from, cc, bcc, subject, attachments, templateBody, templateParams, translateSubject, headers, handler);
    }

}