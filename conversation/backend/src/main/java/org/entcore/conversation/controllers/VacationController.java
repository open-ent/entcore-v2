/*
 * API du message d'absence automatique.
 *
 * - GET    /conversation/vacation -> réglages de l'utilisateur courant (+ « active » pour le bandeau)
 * - PUT    /conversation/vacation -> enregistre {enabled, body, startDate, endDate}
 * - DELETE /conversation/vacation -> désactive l'absence (les réglages sont conservés)
 *
 * Chacun gère sa propre absence : les routes sont AUTHENTICATED et n'agissent que sur le
 * document de l'utilisateur connecté (l'identifiant vient de la session, jamais du corps).
 *
 * Le texte riche est ASSAINI à l'enregistrement par le transformateur de contenu — le même
 * que celui des messages — et stocké nettoyé : la réponse automatique est insérée hors
 * requête HTTP et ne peut plus être filtrée à ce moment-là.
 */
package org.entcore.conversation.controllers;

import fr.wseduc.mongodb.MongoDb;
import fr.wseduc.rs.Delete;
import fr.wseduc.rs.Get;
import fr.wseduc.rs.Put;
import fr.wseduc.security.ActionType;
import fr.wseduc.security.SecuredAction;
import fr.wseduc.webutils.I18n;
import fr.wseduc.webutils.http.BaseController;
import fr.wseduc.webutils.request.RequestUtils;
import io.vertx.core.Future;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.JsonObject;
import org.entcore.common.user.UserUtils;
import org.entcore.conversation.service.ConversationService;
import org.entcore.conversation.util.VacationMessages;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

public class VacationController extends BaseController {

    /** Garde-fou de taille : le message d'absence est un court texte, pas une pièce jointe. */
    private static final int BODY_MAX_LENGTH = 10000;

    private final MongoDb mongo = MongoDb.getInstance();
    private final VacationMessages vacations = VacationMessages.getInstance();
    private final ConversationService conversationService;

    public VacationController(ConversationService conversationService) {
        this.conversationService = conversationService;
    }

    /** Réglages de l'utilisateur courant — alimente l'écran de paramétrage et le bandeau. */
    @Get("vacation")
    @SecuredAction(value = "", type = ActionType.AUTHENTICATED)
    public void get(final HttpServerRequest request) {
        UserUtils.getUserInfos(eb, request, user -> {
            if (user == null) { unauthorized(request); return; }
            mongo.findOne(VacationMessages.COLLECTION, new JsonObject().put("_id", user.getUserId()), res -> {
                final JsonObject body = res.body();
                final JsonObject stored = "ok".equals(body.getString("status"))
                        ? body.getJsonObject("result")
                        : null;
                renderJson(request, vacations.statusFor(user.getUserId(),
                        stored != null && !stored.isEmpty() ? stored : null));
            });
        });
    }

    /** Enregistre (ou met à jour) le message d'absence de l'utilisateur courant. */
    @Put("vacation")
    @SecuredAction(value = "", type = ActionType.AUTHENTICATED)
    public void save(final HttpServerRequest request) {
        UserUtils.getUserInfos(eb, request, user -> {
            if (user == null) { unauthorized(request); return; }
            RequestUtils.bodyToJson(request, payload -> {
                final boolean enabled = payload.getBoolean("enabled", false);
                final String rawBody = payload.getString("body", "");
                final String startDate = normalizeDate(payload.getString("startDate"));
                final String endDate = normalizeDate(payload.getString("endDate"));

                if (rawBody.length() > BODY_MAX_LENGTH) {
                    badRequest(request, "conversation.error.vacation.body.too.long");
                    return;
                }
                if (isInvalidDate(payload.getString("startDate"), startDate)
                        || isInvalidDate(payload.getString("endDate"), endDate)) {
                    badRequest(request, "conversation.error.vacation.invalid.date");
                    return;
                }
                if (startDate != null && endDate != null && startDate.compareTo(endDate) > 0) {
                    badRequest(request, "conversation.error.vacation.invalid.range");
                    return;
                }
                // Une absence active sans texte n'aurait rien à répondre.
                if (enabled && rawBody.trim().isEmpty()) {
                    badRequest(request, "conversation.error.vacation.body.required");
                    return;
                }

                cleanBody(rawBody, request).onSuccess(cleaned -> {
                    final JsonObject fields = new JsonObject()
                            .put("enabled", enabled)
                            .put("body", cleaned.getString("body", ""))
                            .put("startDate", startDate)
                            .put("endDate", endDate)
                            .put("displayName", user.getUsername())
                            // Langue et domaine figés : la réponse est construite hors requête HTTP.
                            .put("lang", I18n.acceptLanguage(request))
                            .put("host", getHost(request))
                            .put("updatedAt", System.currentTimeMillis());
                    final Integer contentVersion = cleaned.getInteger("contentVersion");
                    if (contentVersion != null) fields.put("contentVersion", contentVersion);

                    mongo.update(VacationMessages.COLLECTION,
                            new JsonObject().put("_id", user.getUserId()),
                            new JsonObject().put("$set", fields), true, false, res -> {
                        if (!"ok".equals(res.body().getString("status"))) {
                            renderError(request, new JsonObject().put("error", res.body().getString("message")));
                            return;
                        }
                        vacations.reload();
                        renderJson(request, vacations.statusFor(user.getUserId(), fields));
                    });
                }).onFailure(th -> {
                    log.error("[Vacation] assainissement du message d'absence échoué", th);
                    renderError(request, new JsonObject().put("error", "conversation.error.vacation.save"));
                });
            });
        });
    }

    /** Désactive l'absence sans perdre le texte déjà saisi. */
    @Delete("vacation")
    @SecuredAction(value = "", type = ActionType.AUTHENTICATED)
    public void disable(final HttpServerRequest request) {
        UserUtils.getUserInfos(eb, request, user -> {
            if (user == null) { unauthorized(request); return; }
            mongo.update(VacationMessages.COLLECTION,
                    new JsonObject().put("_id", user.getUserId()),
                    new JsonObject().put("$set", new JsonObject()
                            .put("enabled", false)
                            .put("updatedAt", System.currentTimeMillis())),
                    false, false, res -> {
                if (!"ok".equals(res.body().getString("status"))) {
                    renderError(request, new JsonObject().put("error", res.body().getString("message")));
                    return;
                }
                vacations.reload();
                renderJson(request, new JsonObject().put("status", "ok").put("enabled", false));
            });
        });
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    /**
     * Assainit le texte riche avec le transformateur de contenu des messages.
     * Un texte vide n'a rien à transformer.
     */
    private Future<JsonObject> cleanBody(final String rawBody, final HttpServerRequest request) {
        if (rawBody.trim().isEmpty()) {
            return Future.succeededFuture(new JsonObject().put("body", ""));
        }
        return conversationService
                .transformMessageContent(rawBody, null, false, request)
                .map(response -> {
                    if (response == null) return new JsonObject().put("body", rawBody);
                    return new JsonObject()
                            .put("body", response.getCleanHtml())
                            .put("contentVersion", response.getContentVersion());
                });
    }

    /** Ramène une date à la forme YYYY-MM-DD ; {@code null} si absente ou illisible. */
    private static String normalizeDate(final String value) {
        if (value == null || value.trim().isEmpty()) return null;
        try {
            return LocalDate.parse(value.trim().length() > 10 ? value.trim().substring(0, 10) : value.trim())
                    .toString();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** Une date fournie mais illisible est une erreur ; une date absente n'en est pas une. */
    private static boolean isInvalidDate(final String raw, final String normalized) {
        return raw != null && !raw.trim().isEmpty() && normalized == null;
    }
}
