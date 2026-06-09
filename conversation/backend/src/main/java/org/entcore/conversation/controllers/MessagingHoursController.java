/*
 * API d'administration des horaires d'utilisation de la messagerie.
 *
 * - GET  /conversation/messaging-hours                     -> statut pour l'utilisateur courant (bandeau)
 * - GET  /conversation/messaging-hours/config              -> défaut global (super-admin)
 * - PUT  /conversation/messaging-hours/config              -> régler le défaut global (super-admin)
 * - GET  /conversation/messaging-hours/config/:structureId -> surcharge d'un établissement (ADML)
 * - PUT  /conversation/messaging-hours/config/:structureId -> régler la surcharge (ADML)
 * - DEL  /conversation/messaging-hours/config/:structureId -> revenir au défaut global (ADML)
 *
 * Sécurité sans nouveau workflow (pas de churn app-registry) : les routes de config sont
 * AUTHENTICATED puis on vérifie manuellement SUPER_ADMIN (global) ou ADMIN_LOCAL de la
 * structure (surcharge).
 */
package org.entcore.conversation.controllers;

import fr.wseduc.mongodb.MongoDb;
import fr.wseduc.rs.Delete;
import fr.wseduc.rs.Get;
import fr.wseduc.rs.Put;
import fr.wseduc.security.ActionType;
import fr.wseduc.security.SecuredAction;
import fr.wseduc.webutils.http.BaseController;
import fr.wseduc.webutils.request.RequestUtils;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.entcore.common.user.DefaultFunctions;
import org.entcore.common.user.UserInfos;
import org.entcore.common.user.UserUtils;
import org.entcore.conversation.util.MessagingHours;

public class MessagingHoursController extends BaseController {

    private final MongoDb mongo = MongoDb.getInstance();
    private final MessagingHours hours = MessagingHours.getInstance();

    /** Statut (ouvert/fermé + horaire applicable) pour l'utilisateur courant — alimente le bandeau. */
    @Get("messaging-hours")
    @SecuredAction(value = "", type = ActionType.AUTHENTICATED)
    public void status(final HttpServerRequest request) {
        UserUtils.getUserInfos(eb, request, user -> {
            if (user == null) { unauthorized(request); return; }
            renderJson(request, hours.statusFor(user.getType(), user.getStructures()));
        });
    }

    /** Défaut global (super-admin). */
    @Get("messaging-hours/config")
    @SecuredAction(value = "", type = ActionType.AUTHENTICATED)
    public void getGlobalConfig(final HttpServerRequest request) {
        UserUtils.getUserInfos(eb, request, user -> {
            if (!isSuperAdmin(user)) { unauthorized(request); return; }
            renderJson(request, withDefaults(hours.getGlobal(), MessagingHours.GLOBAL_ID));
        });
    }

    /** Régler le défaut global (super-admin). */
    @Put("messaging-hours/config")
    @SecuredAction(value = "", type = ActionType.AUTHENTICATED)
    public void setGlobalConfig(final HttpServerRequest request) {
        UserUtils.getUserInfos(eb, request, user -> {
            if (!isSuperAdmin(user)) { unauthorized(request); return; }
            RequestUtils.bodyToJson(request, body -> save(MessagingHours.GLOBAL_ID, body, user, request));
        });
    }

    /** Surcharge d'un établissement (ADML de la structure) ; hérite du global si non définie. */
    @Get("messaging-hours/config/:structureId")
    @SecuredAction(value = "", type = ActionType.AUTHENTICATED)
    public void getStructureConfig(final HttpServerRequest request) {
        final String structureId = request.params().get("structureId");
        UserUtils.getUserInfos(eb, request, user -> {
            if (!canAdminStructure(user, structureId)) { unauthorized(request); return; }
            final JsonObject own = hours.getStructureOverride(structureId);
            if (own != null) {
                renderJson(request, own);
            } else {
                renderJson(request, withDefaults(hours.getGlobal(), structureId).put("inherited", true));
            }
        });
    }

    /** Régler la surcharge d'un établissement (ADML de la structure). */
    @Put("messaging-hours/config/:structureId")
    @SecuredAction(value = "", type = ActionType.AUTHENTICATED)
    public void setStructureConfig(final HttpServerRequest request) {
        final String structureId = request.params().get("structureId");
        UserUtils.getUserInfos(eb, request, user -> {
            if (!canAdminStructure(user, structureId)) { unauthorized(request); return; }
            RequestUtils.bodyToJson(request, body -> save(structureId, body, user, request));
        });
    }

    /** Supprimer la surcharge : l'établissement revient au défaut global (ADML de la structure). */
    @Delete("messaging-hours/config/:structureId")
    @SecuredAction(value = "", type = ActionType.AUTHENTICATED)
    public void deleteStructureConfig(final HttpServerRequest request) {
        final String structureId = request.params().get("structureId");
        UserUtils.getUserInfos(eb, request, user -> {
            if (!canAdminStructure(user, structureId)) { unauthorized(request); return; }
            mongo.delete(MessagingHours.COLLECTION, new JsonObject().put("_id", structureId), res -> {
                if ("ok".equals(res.body().getString("status"))) {
                    hours.reload();
                    renderJson(request, new JsonObject().put("status", "ok").put("reverted", true));
                } else {
                    renderError(request, new JsonObject().put("error", res.body().getString("message")));
                }
            });
        });
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private void save(final String id, final JsonObject body, final UserInfos user, final HttpServerRequest request) {
        final JsonObject fields = new JsonObject()
                .put("enabled", body.getBoolean("enabled", false))
                .put("days", body.getJsonArray("days", new JsonArray().add(1).add(2).add(3).add(4).add(5)))
                .put("start", body.getString("start", "08:00"))
                .put("end", body.getString("end", "18:00"))
                .put("updatedAt", System.currentTimeMillis())
                .put("updatedBy", user.getUserId());
        final JsonObject modifier = new JsonObject().put("$set", fields);
        mongo.update(MessagingHours.COLLECTION, new JsonObject().put("_id", id), modifier, true, false, res -> {
            if ("ok".equals(res.body().getString("status"))) {
                hours.reload();
                renderJson(request, fields.put("_id", id));
            } else {
                renderError(request, new JsonObject().put("error", res.body().getString("message")));
            }
        });
    }

    /** Renvoie le document existant, ou un gabarit par défaut désactivé si aucun n'est défini. */
    private JsonObject withDefaults(final JsonObject doc, final String id) {
        if (doc != null) return doc.copy();
        return new JsonObject()
                .put("_id", id)
                .put("enabled", false)
                .put("days", new JsonArray().add(1).add(2).add(3).add(4).add(5))
                .put("start", "08:00")
                .put("end", "18:00");
    }

    private boolean isSuperAdmin(final UserInfos user) {
        return user != null && user.getFunctions() != null
                && user.getFunctions().containsKey(DefaultFunctions.SUPER_ADMIN);
    }

    private boolean canAdminStructure(final UserInfos user, final String structureId) {
        if (user == null || structureId == null || user.getFunctions() == null) return false;
        if (isSuperAdmin(user)) return true;
        final UserInfos.Function adml = user.getFunctions().get(DefaultFunctions.ADMIN_LOCAL);
        return adml != null && adml.getScope() != null && adml.getScope().contains(structureId);
    }
}
