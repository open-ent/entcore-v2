package org.entcore.workspace.controllers;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Optional;

import org.entcore.common.folders.ElementQuery;
import org.entcore.common.folders.impl.DocumentHelper;
import org.entcore.common.user.DefaultFunctions;
import org.entcore.common.user.UserInfos;
import org.entcore.common.user.UserUtils;
import org.entcore.common.utils.StringUtils;
import org.entcore.workspace.service.WorkspaceService;

import fr.wseduc.rs.Delete;
import fr.wseduc.rs.Get;
import fr.wseduc.security.ActionType;
import fr.wseduc.security.SecuredAction;
import fr.wseduc.webutils.http.BaseController;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * Documents de l'espace documentaire possédés par un compte, vus par la plate-forme : la liste
 * de ce qui occupe son quota, et la purge complète.
 *
 * Sécurité sans nouveau workflow (cf. QuotaController) : routes AUTHENTICATED, puis vérification
 * manuelle de SUPER_ADMIN. Volontairement pas d'ouverture à l'ADML : la liste expose les noms des
 * fichiers personnels d'un compte, et la purge est irréversible.
 */
public class UserDocumentsAdminController extends BaseController {

	private final WorkspaceService workspaceService;

	public UserDocumentsAdminController(WorkspaceService workspaceService) {
		this.workspaceService = workspaceService;
	}

	/**
	 * Tout ce que possède le compte, corbeille et documents déposés depuis les applications
	 * (`protected`, `public`) compris : ce sont eux qui comptent dans `UserBook.storage`.
	 *
	 * Un identifiant vide est refusé ici plutôt qu'à l'appelant : sans propriétaire, le filtre
	 * disparaît de la requête et la purge porterait sur l'espace documentaire de TOUT LE MONDE.
	 */
	static ElementQuery ownedBy(final String userId) {
		if (StringUtils.isEmpty(userId) || userId.trim().isEmpty()) {
			throw new IllegalArgumentException("user.id.required");
		}
		final ElementQuery query = new ElementQuery(false);
		query.setOwnerIds(new HashSet<>(Collections.singletonList(userId)));
		return query;
	}

	@Get("/admin/user/:userId/documents")
	@SecuredAction(value = "", type = ActionType.AUTHENTICATED)
	public void listUserDocuments(final HttpServerRequest request) {
		final String userId = request.params().get("userId");
		UserUtils.getUserInfos(eb, request, user -> {
			if (!isSuperAdmin(user)) { unauthorized(request); return; }
			final ElementQuery query;
			try {
				query = ownedBy(userId);
			} catch (IllegalArgumentException e) {
				badRequest(request, e.getMessage());
				return;
			}
			query.setProjection(new HashSet<>(Arrays.asList("_id", "name", "eType", "eParent", "application",
					"protected", "public", "deleted", "created", "modified", "metadata", "isShared")));
			workspaceService.findByQuery(query, null, res -> {
				if (res.failed()) {
					renderError(request, new JsonObject().put("error", res.cause().getMessage()));
					return;
				}
				final JsonArray documents = res.result();
				long size = 0L;
				int files = 0;
				for (Object o : documents) {
					final JsonObject doc = (JsonObject) o;
					if (DocumentHelper.isFile(doc)) {
						files++;
						size += DocumentHelper.getFileSize(doc);
					}
				}
				renderJson(request, new JsonObject().put("userId", userId).put("files", files)
						.put("size", size).put("documents", documents));
			});
		});
	}

	@Delete("/admin/user/:userId/documents")
	@SecuredAction(value = "", type = ActionType.AUTHENTICATED)
	public void deleteUserDocuments(final HttpServerRequest request) {
		final String userId = request.params().get("userId");
		UserUtils.getUserInfos(eb, request, user -> {
			if (!isSuperAdmin(user)) { unauthorized(request); return; }
			final ElementQuery query;
			try {
				query = ownedBy(userId);
			} catch (IllegalArgumentException e) {
				badRequest(request, e.getMessage());
				return;
			}
			// Sans utilisateur courant : aucun filtre de partage, et le quota est rendu au
			// propriétaire de chaque fichier supprimé (cf. FolderManagerWithQuota). Les révisions
			// suivent via DefaultWorkspaceService.afterDelete.
			workspaceService.deleteByQuery(query, Optional.empty(), res -> {
				if (res.failed()) {
					log.error("[Workspace] Purge des documents de " + userId + " par " + user.getUserId()
							+ " en échec", res.cause());
					renderError(request, new JsonObject().put("error", res.cause().getMessage()));
					return;
				}
				final JsonArray deleted = res.result();
				long size = 0L;
				int files = 0;
				for (Object o : deleted) {
					final JsonObject doc = (JsonObject) o;
					if (DocumentHelper.isFile(doc)) {
						files++;
						size += DocumentHelper.getFileSize(doc);
					}
				}
				log.info("[Workspace] Purge des documents de " + userId + " par " + user.getUserId() + " : "
						+ files + " fichier(s), " + size + " octet(s)");
				renderJson(request, new JsonObject().put("userId", userId).put("files", files)
						.put("size", size).put("number", deleted.size()));
			});
		});
	}

	private boolean isSuperAdmin(final UserInfos user) {
		return user != null && user.getFunctions() != null
				&& user.getFunctions().containsKey(DefaultFunctions.SUPER_ADMIN);
	}

}
