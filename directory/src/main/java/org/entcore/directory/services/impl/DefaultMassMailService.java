package org.entcore.directory.services.impl;

import fr.wseduc.webutils.Either;
import fr.wseduc.webutils.I18n;
import fr.wseduc.webutils.data.FileResolver;
import fr.wseduc.webutils.email.EmailSender;
import fr.wseduc.webutils.http.Renders;
import io.vertx.core.*;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.eventbus.DeliveryOptions;
import io.vertx.core.eventbus.EventBus;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import org.entcore.common.neo4j.Neo4j;
import org.entcore.common.user.UserInfos;
import org.entcore.directory.services.MassMailService;

import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static fr.wseduc.webutils.Utils.getOrElse;
import static fr.wseduc.webutils.Utils.handlerToAsyncHandler;
import static org.entcore.common.neo4j.Neo4jResult.validResultHandler;
import static org.entcore.common.user.DefaultFunctions.*;

public class DefaultMassMailService extends Renders implements MassMailService {
    protected static final Logger log = LoggerFactory.getLogger(DefaultMassMailService.class);
    static final Set<String> SORTABLE_FIELDS = new HashSet<>(Arrays.asList(
            "profile", "classname", "firstClass", "lastName", "firstName", "displayName", "login", "email", "creationDate"));
    private final Vertx vertx;
    private final EventBus eb;
    private final EmailSender notifHelper;
    private final String node;
    private final Neo4j neo = Neo4j.getInstance();
    /** Envois simultanés d'un publipostage par courriel. */
    private static final int MAIL_CONCURRENCY = 4;
    /** Durée de conservation d'un bilan après la fin de son lot. */
    private static final long REPORT_TTL_MS = 24L * 3600 * 1000;
    /** Lots de publipostage par courriel, en cours ou terminés depuis moins de {@link #REPORT_TTL_MS}. */
    private static final Map<String, JsonObject> MAIL_JOBS = new ConcurrentHashMap<>();

    public DefaultMassMailService(Vertx vertx, EventBus eb, EmailSender notifHelper, JsonObject config, String node) {
        super(vertx, config);
        this.notifHelper = notifHelper;
        this.vertx = vertx;
        this.eb = eb;
        this.node = node == null ? "" : node;
    }

    public void massMailTypePdf(UserInfos userInfos, final HttpServerRequest request, final String templatePath, final String baseUrl, final String filename, final String type, final JsonArray users) {
        massMailTypePdf(userInfos, request, templatePath, baseUrl, filename, type, users, null);
    }

    public void massMailTypePdf(UserInfos userInfos, final HttpServerRequest request, final String templatePath, final String baseUrl, final String filename, final String type, final JsonArray users, final JsonObject branding) {

        final JsonObject templateProps = new JsonObject().put("hostname", Renders.getHost(request)).put("host",Renders.getScheme(request));

        // Branding de l'établissement (logo, entête, cachet, signature, signataire). Les gabarits
        // le testent avant de l'utiliser ({{#branding.logoUrl}}) et retombent sinon sur les images
        // du thème via baseUrl : un établissement sans branding rend donc exactement comme avant.
        if (branding != null && !branding.isEmpty()) {
            templateProps.put("branding", branding);
        }

        // Try to extend each user data.
        try {
            for (int i = 0; i < users.size(); i++) {
                final JsonObject user = users.getJsonObject(i);
                final String profile = user.getString("profile");
                user.put("isGuestOrPersonnel", "Guest".equals(profile) || "Personnel".equals(profile));
            }
        } catch (Exception e) {
            // Users list is not what we were waiting for. Let's proceed with actual data but emit a warn.
            log.warn("MassMailService : cannot extend users data before generation of PDF from template", e);
        }

        final String templateNamePrefix;
        if ("pdf".equals(type)) {
            templateNamePrefix = "massmail.pdf";
            if (!users.isEmpty()) {
                users.getJsonObject(users.size() - 1).put("end", true);
            }
            templateProps.put("users", users);
        } else if ("newPdf".equals(type)) {
            templateNamePrefix = "massmail_new" + File.separator +"massmail_new.pdf";
            templateProps.put("users", users).put("A5",true);
        } else if ("simplePdf".equals(type)) {
            templateNamePrefix = "massmail_simple.pdf";
            List list = users.getList();
            JsonArray blocks = new JsonArray();
            for (int i = 0; i < list.size(); i += 8) {
                blocks.add(new JsonObject().put("users", new JsonArray(list.subList(i, Math.min((i + 8), list.size())))));
            }
            if (!blocks.isEmpty()) {
                blocks.getJsonObject(blocks.size() - 1).put("end", true);
            }
            templateProps.put("blocks", blocks);
        } else {
            badRequest(request);
            return;
        }
        getTemplateName(userInfos, request, templatePath, templateNamePrefix, "xhtml").onComplete(templateNameRes -> {
            if (templateNameRes.failed()) {
                badRequest(request, templateNameRes.cause().getMessage());
                return;
            }
            String templateName = templateNameRes.result();
            vertx.fileSystem().readFile(templatePath + templateName, result -> {
                if (!result.succeeded()) {
                    badRequest(request);
                    return;
                }

                StringReader reader = new StringReader(result.result().toString("UTF-8"));
                DefaultMassMailService.this.processTemplate(request, templateProps, templateName, reader, writer -> {
                    String processedTemplate = ((StringWriter) writer).getBuffer().toString();

                    if (processedTemplate == null) {
                        badRequest(request);
                        return;
                    }

                    JsonObject actionObject = new JsonObject();
                    actionObject
                            .put("content", processedTemplate.getBytes())
                            .put("baseUrl", baseUrl);

                    eb.request(node + "entcore.pdf.generator", actionObject, new DeliveryOptions()
                            .setSendTimeout(600000l), handlerToAsyncHandler(reply -> {
                        JsonObject pdfResponse = reply.body();
                        if (!"ok".equals(pdfResponse.getString("status"))) {
                            badRequest(request, pdfResponse.getString("message"));
                            return;
                        }

                        byte[] pdf = pdfResponse.getBinary("content");
                        request.response().putHeader("Content-Type", "application/pdf");
                        request.response().putHeader("Content-Disposition",
                                "attachment; filename=" + filename + ".pdf");
                        request.response().end(Buffer.buffer(pdf));
                    }));
                });
            });
        });
    }

    public void massMailTypeMail(UserInfos userInfos, final HttpServerRequest request, final String templatePath, final JsonArray users) {
        massMailTypeMail(userInfos, request, templatePath, users, null);
    }

    public void massMailTypeMail(UserInfos userInfos, final HttpServerRequest request, final String templatePath, final JsonArray users, final JsonObject branding) {
        massMailTypeMail(userInfos, request, templatePath, users, branding, null);
    }

    /**
     * Bandeau logo posé en tête du mail. Largeur ET hauteur sont fixées, en attributs et en style :
     * les messageries ignorent souvent les feuilles de style, et une image sans dimensions s'y
     * affiche à sa taille native. L'image servie a toujours le format du cadre (2,5:1), elle n'est
     * donc jamais déformée.
     */
    private static String mailHeaderHtml(final JsonObject mailHeader) {
        if (mailHeader == null || mailHeader.getString("url", "").isEmpty()) {
            return "";
        }
        final String alt = mailHeader.getString("alt", "")
                .replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;");
        final String url = mailHeader.getString("url").replace("&", "&amp;").replace("\"", "&quot;");
        return "<p style=\"margin:0 0 16px 0\"><img src=\"" + url + "\" width=\"150\" height=\"60\" alt=\"" + alt
                + "\" style=\"display:block;border:0;outline:none;width:150px;height:60px\"/></p>\n";
    }

    public void massMailTypeMail(UserInfos userInfos, final HttpServerRequest request, final String templatePath, final JsonArray users, final JsonObject branding, final JsonObject mailHeader) {
        massMailTypeMail(userInfos, request, templatePath, users, branding, mailHeader, null);
    }

    /**
     * Envoi en arrière-plan : la requête reçoit aussitôt l'identifiant du lot ({@code jobId}) et
     * ses effectifs ; l'avancement puis le bilan se lisent par {@link #massMailReport}. À la fin du
     * lot, le bilan part aussi par courriel à l'administrateur qui l'a lancé.
     * <p>
     * Les envois sont faits {@value #MAIL_CONCURRENCY} par {@value #MAIL_CONCURRENCY} : les lancer
     * tous d'un coup saturait le serveur SMTP sur un gros établissement, et chaque réponse doit
     * être comptée.
     */
    public void massMailTypeMail(UserInfos userInfos, final HttpServerRequest request, final String templatePath, final JsonArray users, final JsonObject branding, final JsonObject mailHeader, final String structureId) {
        final String header = mailHeaderHtml(mailHeader);
        getTemplateName(userInfos, request, templatePath, "massmail.mail", "txt").onComplete(templateNameRes -> {
            if (templateNameRes.failed()) {
                badRequest(request, templateNameRes.cause().getMessage());
                return;
            }
            String templateName = templateNameRes.result();
            vertx.fileSystem().readFile(templatePath + templateName, result -> {
                if (!result.succeeded()) {
                    badRequest(request);
                    return;
                }
                final String template = result.result().toString("UTF-8");
                final JsonArray mailHeaders = new JsonArray().add(
                        new JsonObject().put("name", "Content-Type").put("value", "text/html; charset=\"UTF-8\""));

                final List<JsonObject> recipients = new ArrayList<>();
                final JsonArray withoutEmail = new JsonArray();
                for (Object userObj : users) {
                    final JsonObject user = (JsonObject) userObj;
                    final String userMail = user.getString("email");
                    if (userMail == null || userMail.trim().isEmpty()) {
                        withoutEmail.add(reportUser(user));
                    } else {
                        recipients.add(user);
                    }
                }
                final JsonObject job = startMailJob(userInfos, structureId, users.size(), recipients.size(), withoutEmail);
                renderJson(request, jobSummary(job));

                final AtomicInteger next = new AtomicInteger();
                final AtomicInteger workers = new AtomicInteger(Math.min(MAIL_CONCURRENCY, recipients.size()));
                final Runnable onDone = () -> finishMailJob(request, userInfos, job);
                if (recipients.isEmpty()) {
                    onDone.run();
                    return;
                }
                for (int w = 0; w < MAIL_CONCURRENCY && w < recipients.size(); w++) {
                    sendNextMail(request, template, templateName, header, mailHeaders, branding, recipients, next, workers, job, onDone);
                }
            });
        });
    }

    /** Prend le compte suivant de la file et l'envoie ; le dernier ouvrier à s'arrêter clôt le lot. */
    private void sendNextMail(final HttpServerRequest request, final String template, final String templateName,
            final String header, final JsonArray mailHeaders, final JsonObject branding, final List<JsonObject> recipients,
            final AtomicInteger next, final AtomicInteger workers, final JsonObject job, final Runnable onDone) {
        final int i = next.getAndIncrement();
        if (i >= recipients.size()) {
            if (workers.decrementAndGet() == 0) {
                onDone.run();
            }
            return;
        }
        final JsonObject user = recipients.get(i);
        final Handler<String> recordAndContinue = error -> {
            recordMailResult(job, user, error);
            sendNextMail(request, template, templateName, header, mailHeaders, branding, recipients, next, workers, job, onDone);
        };
        try {
            final String mailTitle = !user.containsKey("activationCode") ||
                    user.getString("activationCode") == null ||
                    user.getString("activationCode").trim().isEmpty() ?
                    "directory.massmail.mail.subject.activated" :
                    "directory.massmail.mail.subject.not.activated";
            final JsonObject mailProps = (branding == null || branding.isEmpty())
                    ? user
                    : user.copy().put("branding", branding);

            // Un lecteur par compte : plusieurs rendus sont en cours en même temps.
            processTemplate(request, mailProps, templateName, new StringReader(template), writer -> {
                final String processedTemplate = writer == null ? null : ((StringWriter) writer).getBuffer().toString();
                if (processedTemplate == null) {
                    recordAndContinue.handle("template");
                    return;
                }
                notifHelper.sendEmail(
                        request,
                        user.getString("email"), null, null,
                        mailTitle,
                        header + processedTemplate, null, true, mailHeaders,
                        handlerToAsyncHandler(event -> {
                            if ("error".equals(event.body().getString("status"))) {
                                final String message = event.body().getString("message", "");
                                log.error("[MassMail] Error while sending mail to " + user.getString("id") + " (" + message + ")");
                                recordAndContinue.handle(message.isEmpty() ? "error" : message);
                            } else {
                                recordAndContinue.handle(null);
                            }
                        }));
            });
        } catch (Exception e) {
            log.error("[MassMail] Error while preparing mail for " + user.getString("id"), e);
            recordAndContinue.handle(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    /** Ce que le bilan retient d'un compte : de quoi le retrouver, rien de son code d'activation. */
    private static JsonObject reportUser(final JsonObject user) {
        final String displayName = user.getString("displayName");
        return new JsonObject()
                .put("id", user.getString("id"))
                .put("login", user.getString("login"))
                .put("displayName", displayName != null ? displayName
                        : (getOrElse(user.getString("firstName"), "") + " " + getOrElse(user.getString("lastName"), "")).trim())
                .put("profile", user.getString("profile"))
                .put("email", user.getString("email"));
    }

    JsonObject startMailJob(final UserInfos userInfos, final String structureId, final int total,
            final int recipients, final JsonArray withoutEmail) {
        final long now = System.currentTimeMillis();
        MAIL_JOBS.values().removeIf(j -> j.getLong("finishedAt", now) < now - REPORT_TTL_MS);
        final JsonObject job = new JsonObject()
                .put("jobId", UUID.randomUUID().toString())
                .put("ownerId", userInfos == null ? null : userInfos.getUserId())
                .put("structureId", structureId)
                .put("status", "running")
                .put("startedAt", now)
                .put("total", total)
                .put("recipients", recipients)
                .put("sent", 0)
                .put("failed", 0)
                .put("withoutEmail", withoutEmail.size())
                .put("failures", new JsonArray())
                .put("withoutEmailUsers", withoutEmail);
        MAIL_JOBS.put(job.getString("jobId"), job);
        return job;
    }

    static synchronized void recordMailResult(final JsonObject job, final JsonObject user, final String error) {
        if (error == null) {
            job.put("sent", job.getInteger("sent") + 1);
        } else {
            job.put("failed", job.getInteger("failed") + 1);
            job.getJsonArray("failures").add(reportUser(user).put("error", error));
        }
    }

    private void finishMailJob(final HttpServerRequest request, final UserInfos userInfos, final JsonObject job) {
        final long now = System.currentTimeMillis();
        job.put("finishedAt", now).put("durationMs", now - job.getLong("startedAt")).put("status", "done");
        log.info("[MassMail] Job " + job.getString("jobId") + " done: " + job.getInteger("sent") + " sent, "
                + job.getInteger("failed") + " failed, " + job.getInteger("withoutEmail") + " without e-mail, "
                + job.getLong("durationMs") + " ms");
        sendReportEmail(request, userInfos, job);
    }

    /** Effectifs et état du lot, sans les listes de comptes. */
    static JsonObject jobSummary(final JsonObject job) {
        final JsonObject summary = job.copy();
        summary.remove("failures");
        summary.remove("withoutEmailUsers");
        summary.remove("ownerId");
        return summary;
    }

    @Override
    public JsonObject massMailReport(final String jobId, final UserInfos user) {
        final JsonObject job = jobId == null ? null : MAIL_JOBS.get(jobId);
        if (job == null || user == null) {
            return null;
        }
        final boolean owner = user.getUserId() != null && user.getUserId().equals(job.getString("ownerId"));
        final boolean superAdmin = user.getFunctions() != null && user.getFunctions().containsKey(SUPER_ADMIN);
        if (!owner && !superAdmin) {
            return null;
        }
        synchronized (DefaultMassMailService.class) {
            final JsonObject copy = job.copy();
            copy.remove("ownerId");
            return copy;
        }
    }

    /**
     * Bilan envoyé à l'administrateur qui a lancé le lot, à l'adresse de son compte. L'issue est
     * notée dans le bilan ({@code reportEmail} : sent, failed ou none faute d'adresse).
     */
    private void sendReportEmail(final HttpServerRequest request, final UserInfos userInfos, final JsonObject job) {
        if (userInfos == null || userInfos.getUserId() == null) {
            job.put("reportEmail", "none");
            return;
        }
        neo.execute("MATCH (u:User {id: {userId}}) OPTIONAL MATCH (s:Structure {id: {structureId}}) " +
                        "RETURN u.email as email, s.name as structureName",
                new JsonObject().put("userId", userInfos.getUserId()).put("structureId", job.getString("structureId", "")),
                validResultHandler(res -> {
                    final JsonObject row = res.isRight() && !res.right().getValue().isEmpty()
                            ? res.right().getValue().getJsonObject(0) : new JsonObject();
                    final String email = row.getString("email");
                    if (email == null || email.trim().isEmpty()) {
                        job.put("reportEmail", "none");
                        return;
                    }
                    final String lang = I18n.acceptLanguage(request);
                    final String host = Renders.getHost(request);
                    final String body = reportEmailHtml(job, row.getString("structureName"), host, lang);
                    final JsonArray headers = new JsonArray().add(
                            new JsonObject().put("name", "Content-Type").put("value", "text/html; charset=\"UTF-8\""));
                    notifHelper.sendEmail(request, email, null, null,
                            I18n.getInstance().translate("directory.massmail.report.subject", host, lang),
                            body, null, false, headers,
                            handlerToAsyncHandler(event -> {
                                final boolean ok = !"error".equals(event.body().getString("status"));
                                if (!ok) {
                                    log.error("[MassMail] Error while sending report of job " + job.getString("jobId")
                                            + " (" + event.body().getString("message", "") + ")");
                                }
                                job.put("reportEmail", ok ? "sent" : "failed");
                            }));
                }));
    }

    /** Nombre maximal de comptes listés par tableau dans le courriel de bilan. */
    private static final int REPORT_EMAIL_MAX_ROWS = 200;

    private static String html(final String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    static String formatDuration(final long ms) {
        final long seconds = Math.round(ms / 1000d);
        if (seconds < 60) {
            return seconds + " s";
        }
        final long minutes = seconds / 60;
        if (minutes < 60) {
            return minutes + " min " + (seconds % 60) + " s";
        }
        return (minutes / 60) + " h " + (minutes % 60) + " min";
    }

    String reportEmailHtml(final JsonObject job, final String structureName, final String host, final String lang) {
        final I18n i18n = I18n.getInstance();
        final java.util.function.Function<String, String> t = key -> html(i18n.translate("directory.massmail.report." + key, host, lang));
        final SimpleDateFormat date = new SimpleDateFormat("dd/MM/yyyy HH:mm");
        date.setTimeZone(TimeZone.getTimeZone("Europe/Paris"));
        final String cell = "style=\"padding:4px 8px;border:1px solid #ddd;text-align:left\"";
        final StringBuilder b = new StringBuilder();
        b.append("<p>").append(t.apply("intro")).append("</p>\n<table style=\"border-collapse:collapse\">\n");
        final String[][] rows = {
                {"structure", html(structureName)},
                {"started", date.format(new Date(job.getLong("startedAt")))},
                {"duration", formatDuration(job.getLong("durationMs", 0L))},
                {"total", String.valueOf(job.getInteger("total"))},
                {"sent", String.valueOf(job.getInteger("sent"))},
                {"failed", String.valueOf(job.getInteger("failed"))},
                {"withoutEmail", String.valueOf(job.getInteger("withoutEmail"))}};
        for (final String[] row : rows) {
            if (row[1] == null || row[1].isEmpty()) continue;
            b.append("<tr><th ").append(cell).append(">").append(t.apply(row[0])).append("</th><td ").append(cell)
                    .append(">").append(row[1]).append("</td></tr>\n");
        }
        b.append("</table>\n");
        // Profils traduits par les libellés existants du module (Teacher → Enseignant…).
        final java.util.function.Function<String, String> profile = p -> p == null ? "" : html(i18n.translate(p, host, lang));
        appendUserTable(b, t.apply("failures"), job.getJsonArray("failures"), true, cell, t, profile);
        appendUserTable(b, t.apply("withoutEmailList"), job.getJsonArray("withoutEmailUsers"), false, cell, t, profile);
        return b.toString();
    }

    private static void appendUserTable(final StringBuilder b, final String title, final JsonArray users, final boolean failures,
            final String cell, final java.util.function.Function<String, String> t,
            final java.util.function.Function<String, String> profile) {
        if (users == null || users.isEmpty()) {
            return;
        }
        b.append("<h3>").append(title).append(" (").append(users.size()).append(")</h3>\n<table style=\"border-collapse:collapse\">\n<tr>");
        for (final String col : failures ? new String[]{"name", "login", "email", "error"} : new String[]{"name", "login", "profile"}) {
            b.append("<th ").append(cell).append(">").append(t.apply("col." + col)).append("</th>");
        }
        b.append("</tr>\n");
        for (int i = 0; i < users.size() && i < REPORT_EMAIL_MAX_ROWS; i++) {
            final JsonObject u = users.getJsonObject(i);
            b.append("<tr><td ").append(cell).append(">").append(html(u.getString("displayName")))
                    .append("</td><td ").append(cell).append(">").append(html(u.getString("login")));
            if (failures) {
                b.append("</td><td ").append(cell).append(">").append(html(u.getString("email")))
                        .append("</td><td ").append(cell).append(">").append(html(u.getString("error")));
            } else {
                b.append("</td><td ").append(cell).append(">").append(profile.apply(u.getString("profile")));
            }
            b.append("</td></tr>\n");
        }
        b.append("</table>\n");
        if (users.size() > REPORT_EMAIL_MAX_ROWS) {
            b.append("<p>").append(t.apply("truncated")).append("</p>\n");
        }
    }

    public void massMailTypeCSV(final HttpServerRequest request, JsonArray users) {
        String path = FileResolver.absolutePath("view/text/export.txt");

        vertx.fileSystem().readFile(path, result -> {
            if (!result.succeeded()) {
                badRequest(request);
                return;
            }
            processTemplate(request, "text/export.txt", new JsonObject().put("list", users), new Handler<String>() {
                @Override
                public void handle(final String export) {
                    if (export != null) {
                        request.response().putHeader("Content-Type", "application/csv");
                        request.response().putHeader("Content-Disposition", "attachment; filename.csv");
                        request.response().end('\ufeff' + export);
                    } else {
                        renderError(request);
                    }
                }
            });
        });


    }

    @Override
    public void massmailNoCheck(String structureId, JsonObject filterObj, UserInfos userInfos, Handler<Either<String, JsonArray>> results) {
        this.massmailUsers(structureId, filterObj, null, false, userInfos, results);
    }

    @Override
    public void massmailUsers(String structureId, JsonObject filterObj, UserInfos userInfos, Handler<Either<String, JsonArray>> results) {
        this.massmailUsers(structureId, filterObj, null, true, userInfos, results);
    }

    /**
     * @return an array of neo4j Users, each having the following fields :
     *   "profile": profile of the User = Relative | Student | Teacher | Personnel | Guest 
     *   "id": NEO4J ID.
     *   "firstName" 
     *   "lastName" 
     *   "displayName": fullname, intended for displaying.
     *   "email": email address, may be null.
     *   "login": actual login needed to connect to the system.
     *   "originalLogin": default login generated by the system.
     *   "activationCode": default "password" generated by the system.
     *   "resetCode"
     *   "creationDate"
     *   "classes": array of classnames the User belongs to.
     *   "firstClass": first classname found in the "classes" array. May be null.
     *   "otherClasses": array of other classnames found in the "classes" array, different from "firstClass". May be null 
     *   "children": array of child objects defined as {firstName: string, lastName: string, classname: string}. Null for non-Relative Users.
     *   "firstChild": first child object found in the "children" array. May be null.
     *   "otherChildren": array of other child objects found in the "children" array, different from "firstChild". May be null.
     *   "hasFederatedIdentity: boolean true if has an idp or structure default auth is federated for his profile"
     *
     * // Below are the DEPRECATED FIELDS, kept for backward compability only. Do not use anymore.
     *   "classname": name of the first found class the User belongs to. Use firstClass instead.
     *   "isInClass": true if "classname" is not null, false otherwise.
     *   "child": use firstChild instead.
     * }
     * 
     * /!\ WARNING for developers /!\
     * This method must always return objects having the above "fields".
     * - You can filter out objects from the array (use the filterObj parameter)
     * - You can add new fields, but think twice before doing it.
     * - You can *NOT* remove any field from the returned objects !
     *   Otherwise, your changes won't be backward-compatible with existing templates.
     *
     * //TODO the resulting JsonArray should be validated through a JSON schema validator.
     */
    @Override
    public void massmailUsers(String structureId, JsonObject filterObj, 
                              Boolean hasMail, boolean performCheck, UserInfos userInfos, Handler<Either<String, JsonArray>> results) {

        String filter =
                " MATCH (s:Structure {id: {structureId}})<-[:DEPENDS]-(g:ProfileGroup)<-[:IN]-(u:User), " +
                        "(g)-[:HAS_PROFILE]-(p: Profile) ";
        String condition = "";
        // Optional match on classes, dropped when one or more classes are explicitly filtered:
        // `c` is then bound by a mandatory MATCH (see the //Classes section below).
        String classOptional =
                " OPTIONAL MATCH (s)<-[:BELONGS]-(c:Class)<-[:DEPENDS]-(:ProfileGroup)<-[:IN]-(u) ";
        final String optional =
                " OPTIONAL MATCH (u)<-[:RELATED]-(child: User)-[:IN]->(:ProfileGroup)-[:DEPENDS]->(c) " +
                " OPTIONAL MATCH (s)-[:HAS_AUTH_DEFAULT]->(auths:AuthDefault { profile: HEAD(u.profiles), auth: 'FEDERATED' }) ";

        JsonObject params = new JsonObject().put("structureId", structureId);

        //Activation
        if (filterObj.containsKey("activated")) {
            String activated = filterObj.getString("activated", "false");
            if ("false".equals(activated.toLowerCase())) {
                condition = "WHERE NOT(u.activationCode IS NULL) ";
            } else if ("true".equals(activated.toLowerCase())) {
                condition = "WHERE (u.activationCode IS NULL) ";
            } else {
                condition = "WHERE 1 = 1 ";
            }
        } else {
            condition = "WHERE NOT(u.activationCode IS NULL) ";
        }

        //Profiles
        if (filterObj.containsKey("profiles") && filterObj.getJsonArray("profiles").size() > 0) {
            condition += "AND p.name IN {profilesArray} ";
            params.put("profilesArray", filterObj.getJsonArray("profiles"));
        }

        //Levels
        if (filterObj.containsKey("levels") && filterObj.getJsonArray("levels").size() > 0) {
            condition += " AND u.level IN {levelsArray} ";
            params.put("levelsArray", filterObj.getJsonArray("levels"));
        }

        //Classes
        if (filterObj.containsKey("classes") && filterObj.getJsonArray("classes").size() > 0) {
            filter += ", (c:Class)<-[:DEPENDS]-(:ProfileGroup)<-[:IN]-(u) ";
            classOptional = "";
            condition += " AND c.id IN {classesArray} ";
            params.put("classesArray", filterObj.getJsonArray("classes"));
        }

        //Adml
        if (filterObj.containsKey("adml")) {
            String adml = filterObj.getString("adml", "false");
            if ("false".equals(adml.toLowerCase())) {
                condition += " AND NOT (u)-[:HAS_FUNCTION]->({ externalId: 'ADMIN_LOCAL' }) ";
            } else if ("true".equals(adml.toLowerCase())) {
                condition += " AND (u)-[:HAS_FUNCTION]->({ externalId: 'ADMIN_LOCAL' }) ";
            }
        }

        //Email
        if (hasMail != null) {
            if (hasMail) {
                condition += " AND COALESCE(u.email, \"\") <> \"\" ";
            } else {
                condition += " AND COALESCE(u.email, \"\") = \"\" ";
            }
        }

        //Date
        if (filterObj.containsKey("dateFilter") && filterObj.containsKey("date")) {
            String dateFilter = filterObj.getString("dateFilter", "error");
            String date = filterObj.getString("date", "error");

            if ((dateFilter.equals("before") || (dateFilter.equals("after"))) && date.matches("\\d+")) {
                String formattedDate = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ").format(new Date(Long.parseLong(date)));
                String operator = dateFilter.equals("before") ? "<=" : ">=";
                condition += " AND u.created " + operator + " \"" + formattedDate + "\" ";
            }
        }

        // List of users (from class-admin)
        if (filterObj.containsKey("userIds")) {
            condition += " AND u.id IN {userIds} ";
            params.put("userIds", filterObj.getJsonArray("userIds"));
        }

        //Blocked status
        if (filterObj.containsKey("blocked")) {
            Boolean blocked = filterObj.getBoolean("blocked", false);
            if(blocked) {
                condition += " AND u.blocked = true ";
            } else {
                condition += " AND (NOT EXISTS(u.blocked) OR u.blocked = false) ";
            }
        }

        //Admin check
        if(performCheck){
            if (!userInfos.getFunctions().containsKey(SUPER_ADMIN) &&
                    !userInfos.getFunctions().containsKey(ADMIN_LOCAL) &&
                    !userInfos.getFunctions().containsKey(CLASS_ADMIN)) {
                results.handle(new Either.Left<String, JsonArray>("forbidden"));
                return;
            } else if (userInfos.getFunctions().containsKey(ADMIN_LOCAL)) {
                UserInfos.Function f = userInfos.getFunctions().get(ADMIN_LOCAL);
                List<String> scope = f.getScope();
                if (scope != null && !scope.isEmpty()) {
                    condition += "AND s.id IN {scope} ";
                    params.put("scope", new JsonArray(scope));
                }
            } else if (userInfos.getFunctions().containsKey(CLASS_ADMIN)) {
                if (filterObj.getJsonArray("classes").size() < 1) {
                    results.handle(new Either.Left<String, JsonArray>("forbidden"));
                    return;
                }

                UserInfos.Function f = userInfos.getFunctions().get(CLASS_ADMIN);
                List<String> scope = f.getScope();
                if (scope != null && !scope.isEmpty()) {
                    condition = "AND c.id IN {scope} ";
                    params.put("scope", new JsonArray(scope));
                }
            }
        }

        //With clause
        String withStr =
            " WITH u, p, collect(auths) as auths "
           +", collect(distinct c.name) as classes, min(c.name) as classname, CASE count(c) WHEN 0 THEN false ELSE true END as isInClass "
           +", CASE count(child) WHEN 0 THEN null ELSE filter("
              +"c IN (collect(distinct {firstName: child.firstName, lastName: child.lastName, classname: c.name})) WHERE not(c.firstName is null)"
           +") END as children ";

        if (!filterObj.containsKey("includeFederated")) {
            withStr += " WHERE NOT ((u.federatedIDP IS NOT NULL AND NOT(u.federatedIDP IS NULL) AND u.federated IS NOT NULL AND u.federated = true) OR size(auths) > 0 AND (u.source in ['AAF', 'AAF1D']) AND u.activationCode IS NOT NULL) ";
        }

        //Return clause
        String returnStr =
            " RETURN distinct collect(p.name)[0] as profile"
            +", u.id as id, u.firstName as firstName, u.lastName as lastName, u.displayName as displayName"
            +", u.email as email, CASE WHEN u.loginAlias IS NOT NULL THEN u.loginAlias ELSE u.login END as login"
            +", u.login as originalLogin, u.activationCode as activationCode, u.created as creationDate"
            +", classes"
            +", CASE WHEN size(classes) = 0 THEN null ELSE head(classes) END as firstClass"
            +", CASE WHEN size(classes) < 2 THEN null ELSE tail(classes) END as otherClasses"
            +", children"
            +", CASE WHEN size(children) = 0 THEN null ELSE head(children) END as firstChild"
            +", CASE WHEN size(children) < 2 THEN null ELSE tail(children) END as otherChildren"
            // Deprecated fields below
            +", classname, isInClass"
            +", CASE WHEN size(children) = 0 THEN null ELSE head(children) END as child"
            +", (u.federatedIDP IS NOT NULL AND NOT(u.federatedIDP IS NULL) AND u.federated IS NOT NULL AND u.federated = true) OR " +
            " (size(auths) > 0 AND (u.source in ['AAF', 'AAF1D']) AND u.activationCode IS NOT NULL) as hasFederatedIdentity ";

        //Order by
        String sort = " ORDER BY ";
        if (filterObj.containsKey("sort")) {
            for (Object sortObj : filterObj.getJsonArray("sort")) {
                // Le tri est concaténé dans la requête : seuls les alias du RETURN sont admis (injection Cypher).
                if (!(sortObj instanceof String) || !SORTABLE_FIELDS.contains(sortObj)) {
                    continue;
                }
                sort += "TOLOWER(TOSTRING(" + sortObj + ")), ";
            }
        }
        sort += "TOLOWER(lastName) ";

        String query = filter + condition + classOptional + optional + withStr + returnStr + sort;

        neo.execute(query.toString(), params, validResultHandler(results));
    }

    public void massMailUser(String userId, UserInfos userInfos, Handler<Either<String, JsonArray>> results) {
        String filter =
                "MATCH (s:Structure)<-[:DEPENDS]-(g:ProfileGroup)<-[:IN]-(u:User {id: {userId}}), " +
                        "(g)-[:HAS_PROFILE]-(p: Profile) ";
        String condition = "";
        if (!userInfos.getFunctions().containsKey(SUPER_ADMIN)) {
            condition = "WHERE " + DefaultSchoolService.EXCLUDE_ADMC_QUERY_FILTER;
        }

        String optional =
                "OPTIONAL MATCH (s)<-[:BELONGS]-(c:Class)<-[:DEPENDS]-(:ProfileGroup)<-[:IN]-(u) " +
                        "OPTIONAL MATCH (u)<-[:RELATED]-(child: User)-[:IN]->(:ProfileGroup)-[:DEPENDS]->(c) ";

        JsonObject params = new JsonObject().put("userId", userId);

        //With clause
        String withStr =
                "WITH u, p ";


        //Return clause
        String returnStr =
                "RETURN distinct collect(p.name)[0] as profile, " +
                        "u.id as id, u.firstName as firstName, u.lastName as lastName, " +
                        "u.email as email, CASE WHEN u.loginAlias IS NOT NULL THEN u.loginAlias ELSE u.login END as login, u.activationCode as activationCode ";

        withStr += ", collect(distinct c.name) as classes, min(c.name) as classname, CASE count(c) WHEN 0 THEN false ELSE true END as isInClass ";
        returnStr += ", classes, classname, isInClass ";

        withStr += ", CASE count(child) WHEN 0 THEN null ELSE {firstName: child.firstName, lastName: child.lastName } END as child ";
        returnStr += ", child ";

        String query = filter + condition + optional + withStr + returnStr;

        neo.execute(query.toString(), params, validResultHandler(results));
    }

    @Override
    public void massMailAllUsersByStructure(String structureId, UserInfos userInfos, Handler<Either<String, JsonArray>> results) {
        String filter =
                "MATCH (s:Structure {id: {structureId}})<-[:DEPENDS]-(g:ProfileGroup)<-[:IN]-(u:User), " +
                        "(g)-[:HAS_PROFILE]-(p: Profile) ";
        String condition = "";
        String optional =
                "OPTIONAL MATCH (s)<-[:BELONGS]-(c:Class)<-[:DEPENDS]-(:ProfileGroup)<-[:IN]-(u) " +
                        "OPTIONAL MATCH (u)<-[:RELATED]-(child: User)-[:IN]->(:ProfileGroup)-[:DEPENDS]->(c) " +
                        "OPTIONAL MATCH (u:User)-[rf:HAS_FUNCTION]->(f:Function) "
                +" OPTIONAL MATCH (s)-[:HAS_AUTH_DEFAULT]->(auths:AuthDefault { profile: HEAD(u.profiles), auth: 'FEDERATED' }) ";

        JsonObject params = new JsonObject().put("structureId", structureId);

        //Admin check
        if (!userInfos.getFunctions().containsKey(SUPER_ADMIN) &&
                !userInfos.getFunctions().containsKey(ADMIN_LOCAL)) {
            results.handle(new Either.Left<String, JsonArray>("forbidden"));
            return;
        } else if (userInfos.getFunctions().containsKey(ADMIN_LOCAL)) {
            UserInfos.Function f = userInfos.getFunctions().get(ADMIN_LOCAL);
            List<String> scope = f.getScope();
            if (scope != null && !scope.isEmpty()) {
                condition += "WHERE s.id IN {scope} ";
                params.put("scope", new JsonArray(scope));
            }
        }

        if (!userInfos.getFunctions().containsKey(SUPER_ADMIN)) {
            condition += (condition.isEmpty() ? "WHERE " : "AND ") + DefaultSchoolService.EXCLUDE_ADMC_QUERY_FILTER;
        }

        //With clause
        String withStr =
                "WITH u, p, rf, f, COLLECT(auths) as auths ";

        //Return clause
        String returnStr =
                "RETURN distinct collect(p.name)[0] as type, " +
                        "u.id as id, u.firstName as firstName, u.lastName as lastName, " +
                        "u.email as email, CASE WHEN u.loginAlias IS NOT NULL THEN u.loginAlias ELSE u.login END as login, u.activationCode as code, " +
                        "u.created as creationDate, COALESCE(u.blocked, false) as blocked ";

        withStr += ", collect(distinct {id: c.id, name: c.name}) as classes, min(c.name) as classname, CASE count(c) WHEN 0 THEN false ELSE true END as isInClass ";
        returnStr += ", classes, classname, isInClass ";

        withStr += ", CASE count(child) WHEN 0 THEN null ELSE collect(distinct {firstName: child.firstName, lastName: child.lastName, classname: c.name}) END as children ";
        returnStr += ", filter(c IN children WHERE not(c.firstName is null)) as children, (u.federatedIDP IS NOT NULL AND NOT(u.federatedIDP IS NULL) AND u.federated IS NOT NULL AND u.federated = true) OR " +
                " (size(auths) > 0 AND (u.source in ['AAF', 'AAF1D'] AND u.activationCode IS NOT NULL ))  as hasFederatedIdentity ";

        String sort = "ORDER BY lastName";

        String query = filter + condition + optional + withStr + returnStr + sort;

        neo.execute(query.toString(), params, validResultHandler(results));
    }

    private Future<String> getTemplateName(UserInfos user, HttpServerRequest request, String basePath, String prefix, String suffix) {
        Future<String> defaultName = getDefaultFileName(prefix, suffix);
        Future<String> i18Name = getI18FileName(user, request, prefix, suffix);
        return Future.all(defaultName, i18Name).compose(all -> {
            Promise<String> fullPath = Promise.promise();
            String i18Path = basePath + i18Name.result();
            vertx.fileSystem().exists(i18Path, exists -> {
                if (exists.succeeded() && exists.result()) {
                    fullPath.complete(i18Name.result());
                } else {
                    fullPath.complete(defaultName.result());
                }
            });
            return fullPath.future();
        });
    }

    private Future<String> getDefaultFileName(String prefix, String suffix) {
        return Future.succeededFuture(String.format("%s.%s", prefix, suffix));
    }

    private Future<String> getI18FileName(UserInfos user, HttpServerRequest request, String prefix, String suffix) {
        return getLanguage(user, request).map(lang -> {
            return String.format("%s.%s.%s", prefix, lang, suffix);
        });
    }

    private Future<String> getLanguage(UserInfos user, HttpServerRequest request) {
        Promise<String> future = Promise.promise();
        String navLang = getOrElse(I18n.acceptLanguage(request), "fr");
        future.complete(navLang);
        return future.future();
    }

}
