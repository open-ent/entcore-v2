/*
 * API d'administration des pièces jointes de messagerie d'un compte : ce qui occupe son quota
 * côté messagerie, et leur retrait en bloc.
 *
 * - GET    /conversation/admin/user/:userId/attachments -> pièces jointes rattachées au compte
 * - DELETE /conversation/admin/user/:userId/attachments -> les détache toutes et rend le quota
 *
 * Sécurité sans nouveau workflow : routes AUTHENTICATED puis contrôle manuel de SUPER_ADMIN
 * (même parti pris que le pendant côté espace documentaire, UserDocumentsAdminController).
 */
package org.entcore.conversation.controllers;

import fr.wseduc.rs.Delete;
import fr.wseduc.rs.Get;
import fr.wseduc.security.ActionType;
import fr.wseduc.security.SecuredAction;
import fr.wseduc.webutils.Either;
import fr.wseduc.webutils.http.BaseController;
import io.vertx.core.Handler;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.entcore.common.sql.Sql;
import org.entcore.common.sql.SqlResult;
import org.entcore.common.sql.SqlStatementsBuilder;
import org.entcore.common.storage.Storage;
import org.entcore.common.user.DefaultFunctions;
import org.entcore.common.user.UserInfos;
import org.entcore.common.user.UserUtils;

import static fr.wseduc.webutils.Utils.handlerToAsyncHandler;

public class UserAttachmentsAdminController extends BaseController {

    private static final String QUOTA_BUS_ADDRESS = "org.entcore.workspace.quota";

    /** Une ligne par (message, pièce jointe) : un même fichier transféré compte à chaque message. */
    static final String LIST_ATTACHMENTS =
        "SELECT a.id AS id, a.filename AS filename, a.\"contentType\" AS contenttype, a.size AS size, " +
        "m.id AS messageid, m.subject AS subject, m.date AS date " +
        "FROM conversation.usermessagesattachments uma " +
        "JOIN conversation.attachments a ON a.id = uma.attachment_id " +
        "JOIN conversation.messages m ON m.id = uma.message_id " +
        "WHERE uma.user_id = ? ORDER BY a.size DESC";

    /** Ce que la messagerie a imputé au quota du compte : la somme de ses `total_quota`. */
    static final String TOTAL_QUOTA =
        "SELECT coalesce(sum(um.total_quota), 0)::bigint AS totalquota " +
        "FROM conversation.usermessages um WHERE um.user_id = ?";

    static final String DETACH_ATTACHMENTS =
        "DELETE FROM conversation.usermessagesattachments WHERE user_id = ?";

    static final String RESET_MESSAGES_QUOTA =
        "UPDATE conversation.usermessages SET total_quota = 0 WHERE user_id = ? AND total_quota <> 0";

    private final Storage storage;
    private final Sql sql = Sql.getInstance();
    private int threshold;

    public UserAttachmentsAdminController(Storage storage) {
        this.storage = storage;
    }

    @Override
    public void init(io.vertx.core.Vertx vertx, JsonObject config, org.vertx.java.core.http.RouteMatcher rm,
            java.util.Map<String, fr.wseduc.webutils.security.SecuredAction> securedActions) {
        super.init(vertx, config, rm, securedActions);
        this.threshold = config.getInteger("alertStorage", 80);
    }

    @Get("admin/user/:userId/attachments")
    @SecuredAction(value = "", type = ActionType.AUTHENTICATED)
    public void listUserAttachments(final HttpServerRequest request) {
        final String userId = request.params().get("userId");
        UserUtils.getUserInfos(eb, request, user -> {
            if (!isSuperAdmin(user)) { unauthorized(request); return; }
            if (isBlank(userId)) { badRequest(request, "user.id.required"); return; }
            sql.prepared(LIST_ATTACHMENTS, new JsonArray().add(userId), SqlResult.validResultHandler(res -> {
                if (res.isLeft()) {
                    renderError(request, new JsonObject().put("error", res.left().getValue()));
                    return;
                }
                final JsonArray documents = new JsonArray();
                long size = 0L;
                for (Object o : res.right().getValue()) {
                    final JsonObject row = (JsonObject) o;
                    final long rowSize = row.getLong("size", 0L);
                    size += rowSize;
                    documents.add(new JsonObject()
                            .put("_id", row.getString("id"))
                            .put("name", row.getString("filename"))
                            .put("contentType", row.getString("contenttype"))
                            .put("size", rowSize)
                            .put("messageId", row.getString("messageid"))
                            .put("subject", row.getString("subject"))
                            .put("date", row.getLong("date")));
                }
                renderJson(request, new JsonObject().put("userId", userId).put("files", documents.size())
                        .put("size", size).put("documents", documents));
            }));
        });
    }

    /**
     * Les messages restent dans la boîte du compte, sans leurs pièces jointes : seul le lien
     * (compte, message, fichier) est retiré, comme le fait le retrait d'une pièce jointe d'un
     * brouillon. Les autres destinataires gardent les leurs ; un fichier que plus personne ne
     * référence est retiré du stockage dans la foulée.
     */
    @Delete("admin/user/:userId/attachments")
    @SecuredAction(value = "", type = ActionType.AUTHENTICATED)
    public void deleteUserAttachments(final HttpServerRequest request) {
        final String userId = request.params().get("userId");
        UserUtils.getUserInfos(eb, request, user -> {
            if (!isSuperAdmin(user)) { unauthorized(request); return; }
            if (isBlank(userId)) { badRequest(request, "user.id.required"); return; }
            final JsonArray params = new JsonArray().add(userId);
            final SqlStatementsBuilder builder = new SqlStatementsBuilder();
            builder.prepared(LIST_ATTACHMENTS, params);
            builder.prepared(TOTAL_QUOTA, params);
            builder.prepared(DETACH_ATTACHMENTS, params);
            builder.prepared(RESET_MESSAGES_QUOTA, params);
            sql.transaction(builder.build(), SqlResult.validResultsHandler(res -> {
                if (res.isLeft()) {
                    log.error("[Conversation] Purge des pièces jointes de " + userId + " par "
                            + user.getUserId() + " en échec : " + res.left().getValue());
                    renderError(request, new JsonObject().put("error", res.left().getValue()));
                    return;
                }
                final JsonArray results = res.right().getValue();
                final JsonArray detached = results.getJsonArray(0);
                final long freed = results.getJsonArray(1).getJsonObject(0).getLong("totalquota", 0L);
                final JsonArray attachmentIds = new JsonArray();
                for (Object o : detached) {
                    final String id = ((JsonObject) o).getString("id");
                    if (!attachmentIds.contains(id)) attachmentIds.add(id);
                }
                log.info("[Conversation] Purge des pièces jointes de " + userId + " par " + user.getUserId()
                        + " : " + detached.size() + " pièce(s) jointe(s), " + freed + " octet(s)");
                removeOrphans(attachmentIds);
                final JsonObject body = new JsonObject().put("userId", userId).put("files", detached.size())
                        .put("size", freed);
                if (freed == 0L) { renderJson(request, body); return; }
                updateUserQuota(userId, -freed, v -> renderJson(request, body));
            }));
        });
    }

    /** Parmi les fichiers détachés, ceux que plus aucun compte ne référence. */
    private void removeOrphans(final JsonArray attachmentIds) {
        if (attachmentIds.isEmpty()) return;
        final String in = Sql.listPrepared(attachmentIds);
        final String selectOrphans =
            "SELECT a.id AS id FROM conversation.attachments a WHERE a.id IN " + in + " AND NOT EXISTS " +
            "(SELECT 1 FROM conversation.usermessagesattachments uma WHERE uma.attachment_id = a.id)";
        sql.prepared(selectOrphans, attachmentIds, SqlResult.validResultHandler(res -> {
            if (res.isLeft()) {
                log.error("[Conversation] Recherche des pièces jointes orphelines en échec : " + res.left().getValue());
                return;
            }
            final JsonArray orphans = new JsonArray();
            for (Object o : res.right().getValue()) orphans.add(((JsonObject) o).getString("id"));
            if (orphans.isEmpty()) return;
            storage.removeFiles(orphans, removed -> {
                if (removed == null || !"ok".equals(removed.getString("status"))) {
                    log.error("[Conversation] Retrait du stockage en échec : "
                            + (removed == null ? "pas de réponse" : removed.encode()));
                }
            });
            sql.prepared("DELETE FROM conversation.attachments WHERE id IN " + Sql.listPrepared(orphans), orphans,
                    SqlResult.validRowsResultHandler(deleted -> {
                if (deleted.isLeft()) {
                    log.error("[Conversation] Suppression des pièces jointes orphelines en échec : "
                            + deleted.left().getValue());
                }
            }));
        }));
    }

    private void updateUserQuota(final String userId, final long size, final Handler<Void> continuation) {
        final JsonObject message = new JsonObject().put("action", "updateUserQuota").put("userId", userId)
                .put("size", size).put("threshold", threshold);
        eb.request(QUOTA_BUS_ADDRESS, message, handlerToAsyncHandler((io.vertx.core.eventbus.Message<JsonObject> reply) -> {
            UserUtils.addSessionAttribute(eb, userId, "storage", reply.body().getLong("storage"), null);
            continuation.handle(null);
        }));
    }

    static boolean isBlank(final String s) {
        return s == null || s.trim().isEmpty();
    }

    private boolean isSuperAdmin(final UserInfos user) {
        return user != null && user.getFunctions() != null
                && user.getFunctions().containsKey(DefaultFunctions.SUPER_ADMIN);
    }

}
