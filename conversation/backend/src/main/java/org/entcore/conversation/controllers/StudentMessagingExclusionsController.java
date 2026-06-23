/*
 * API d'administration des exclusions temporaires d'élèves de la messagerie.
 *
 * - GET    /conversation/messaging-exclusions                      -> statut de l'utilisateur courant (bandeau)
 * - GET    /conversation/messaging-exclusions/:structureId         -> liste des exclusions de l'établissement
 * - POST   /conversation/messaging-exclusions/:structureId         -> exclure un élève {userId, blockedUntil, reason}
 * - DELETE /conversation/messaging-exclusions/:structureId/:userId -> lever l'exclusion
 *
 * Sécurité sans nouveau workflow obligatoire : les routes de gestion sont AUTHENTICATED puis on
 * autorise manuellement SUPER_ADMIN, l'ADML de la structure, OU le personnel à qui le chef a
 * délégué l'action {@code communication.restriction.manage} (vie-scolaire).
 */
package org.entcore.conversation.controllers;

import fr.wseduc.mongodb.MongoDb;
import fr.wseduc.rs.Delete;
import fr.wseduc.rs.Get;
import fr.wseduc.rs.Post;
import fr.wseduc.security.ActionType;
import fr.wseduc.security.SecuredAction;
import fr.wseduc.webutils.http.BaseController;
import fr.wseduc.webutils.request.RequestUtils;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.JsonObject;
import org.entcore.common.user.DefaultFunctions;
import org.entcore.common.user.UserInfos;
import org.entcore.common.user.UserUtils;
import org.entcore.conversation.util.StudentMessagingExclusions;

public class StudentMessagingExclusionsController extends BaseController {

    /** Action workflow que le chef peut déléguer au personnel vie-scolaire. */
    public static final String DELEGATION_ACTION = "communication.restriction.manage";

    private final MongoDb mongo = MongoDb.getInstance();
    private final StudentMessagingExclusions exclusions = StudentMessagingExclusions.getInstance();

    /**
     * Action WORKFLOW de délégation. Son seul rôle est d'EXISTER comme action nommée
     * « communication.restriction.manage » dans l'app-registry, pour que le chef puisse la déléguer
     * au personnel vie-scolaire via l'écran de gestion des rôles. Les contrôleurs (exclusion élève
     * ET coupure de communication) reconnaissent cette action dans getAuthorizedActions().
     * L'endpoint lui-même se contente de répondre que l'utilisateur courant possède la délégation.
     */
    @Get("messaging-restriction/can-manage")
    @SecuredAction(value = DELEGATION_ACTION, type = ActionType.WORKFLOW)
    public void canManageDelegation(final HttpServerRequest request) {
        renderJson(request, new JsonObject().put("canManage", true));
    }

    /** Statut de l'utilisateur courant — alimente le bandeau « messagerie suspendue ». */
    @Get("messaging-exclusions")
    @SecuredAction(value = "", type = ActionType.AUTHENTICATED)
    public void status(final HttpServerRequest request) {
        UserUtils.getUserInfos(eb, request, user -> {
            if (user == null) { unauthorized(request); return; }
            renderJson(request, exclusions.statusFor(user.getUserId()));
        });
    }

    /** Liste des exclusions d'un établissement (admin de la structure ou délégué). */
    @Get("messaging-exclusions/:structureId")
    @SecuredAction(value = "", type = ActionType.AUTHENTICATED)
    public void list(final HttpServerRequest request) {
        final String structureId = request.params().get("structureId");
        UserUtils.getUserInfos(eb, request, user -> {
            if (!canManage(user, structureId)) { unauthorized(request); return; }
            mongo.find(StudentMessagingExclusions.COLLECTION,
                    new JsonObject().put("structureId", structureId), res -> {
                if ("ok".equals(res.body().getString("status"))) {
                    renderJson(request, new JsonObject().put("results", res.body().getJsonArray("results")));
                } else {
                    renderError(request, new JsonObject().put("error", res.body().getString("message")));
                }
            });
        });
    }

    /** Exclure un élève d'un établissement jusqu'à une date (admin de la structure ou délégué). */
    @Post("messaging-exclusions/:structureId")
    @SecuredAction(value = "", type = ActionType.AUTHENTICATED)
    public void create(final HttpServerRequest request) {
        final String structureId = request.params().get("structureId");
        UserUtils.getUserInfos(eb, request, user -> {
            if (!canManage(user, structureId)) { unauthorized(request); return; }
            RequestUtils.bodyToJson(request, body -> {
                final String userId = body.getString("userId");
                final Long blockedUntil = body.getLong("blockedUntil");
                if (userId == null || blockedUntil == null) {
                    badRequest(request, "conversation.error.exclusion.invalid");
                    return;
                }
                final String id = structureId + ":" + userId;
                final JsonObject fields = new JsonObject()
                        .put("userId", userId)
                        .put("userName", body.getString("userName", ""))
                        .put("structureId", structureId)
                        .put("blockedUntil", blockedUntil)
                        .put("reason", body.getString("reason", ""))
                        .put("createdBy", user.getUserId())
                        .put("createdByName", user.getUsername())
                        .put("createdAt", System.currentTimeMillis());
                final JsonObject modifier = new JsonObject().put("$set", fields);
                mongo.update(StudentMessagingExclusions.COLLECTION, new JsonObject().put("_id", id),
                        modifier, true, false, res -> {
                    if ("ok".equals(res.body().getString("status"))) {
                        exclusions.reload();
                        renderJson(request, fields.put("_id", id));
                    } else {
                        renderError(request, new JsonObject().put("error", res.body().getString("message")));
                    }
                });
            });
        });
    }

    /** Lever l'exclusion d'un élève (admin de la structure ou délégué). */
    @Delete("messaging-exclusions/:structureId/:userId")
    @SecuredAction(value = "", type = ActionType.AUTHENTICATED)
    public void remove(final HttpServerRequest request) {
        final String structureId = request.params().get("structureId");
        final String userId = request.params().get("userId");
        UserUtils.getUserInfos(eb, request, user -> {
            if (!canManage(user, structureId)) { unauthorized(request); return; }
            mongo.delete(StudentMessagingExclusions.COLLECTION,
                    new JsonObject().put("_id", structureId + ":" + userId), res -> {
                if ("ok".equals(res.body().getString("status"))) {
                    exclusions.reload();
                    renderJson(request, new JsonObject().put("status", "ok").put("deleted", true));
                } else {
                    renderError(request, new JsonObject().put("error", res.body().getString("message")));
                }
            });
        });
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private boolean isSuperAdmin(final UserInfos user) {
        return user != null && user.getFunctions() != null
                && user.getFunctions().containsKey(DefaultFunctions.SUPER_ADMIN);
    }

    /** Autorise SUPER_ADMIN, l'ADML de la structure, ou le délégué (action workflow). */
    private boolean canManage(final UserInfos user, final String structureId) {
        if (user == null || structureId == null || user.getFunctions() == null) return false;
        if (isSuperAdmin(user)) return true;
        final UserInfos.Function adml = user.getFunctions().get(DefaultFunctions.ADMIN_LOCAL);
        if (adml != null && adml.getScope() != null && adml.getScope().contains(structureId)) return true;
        return hasDelegation(user, structureId);
    }

    /** Le personnel doit avoir l'action déléguée ET appartenir à la structure visée. */
    private boolean hasDelegation(final UserInfos user, final String structureId) {
        if (user.getAuthorizedActions() == null) return false;
        if (user.getStructures() == null || !user.getStructures().contains(structureId)) return false;
        // Pour une action WORKFLOW, le nom métier (« communication.restriction.manage ») est porté
        // par displayName ; getName() renvoie le FQN « Controller|methode ».
        for (UserInfos.Action a : user.getAuthorizedActions()) {
            if (DELEGATION_ACTION.equals(a.getDisplayName())) return true;
        }
        return false;
    }
}
