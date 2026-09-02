/* Copyright © "Open Digital Education", 2014
 *
 * This program is published by "Open Digital Education".
 * You must indicate the name of the software and the company in any production /contribution
 * using the software and indicate on the home page of the software industry in question,
 * "powered by Open Digital Education" with a reference to the website: https://opendigitaleducation.com/.
 *
 * This program is free software, licensed under the terms of the GNU Affero General Public License
 * as published by the Free Software Foundation, version 3 of the License.
 *
 * You can redistribute this application and/or modify it since you respect the terms of the GNU Affero General Public License.
 * If you modify the source code and then use this modified source code in your creation, you must make available the source code of your modifications.
 *
 * You should have received a copy of the GNU Affero General Public License along with the software.
 * If not, please see : <http://www.gnu.org/licenses/>. Full compliance requires reading the terms of this license and following its directives.

 *
 */

package org.entcore.archive.filters;

import fr.wseduc.webutils.http.Binding;
import fr.wseduc.webutils.request.RequestUtils;
import io.vertx.core.Handler;
import io.vertx.core.http.HttpServerRequest;
import org.entcore.archive.services.StructureExportService;
import org.entcore.common.http.filter.ResourcesProvider;
import org.entcore.common.user.DefaultFunctions;
import org.entcore.common.user.UserInfos;

import java.util.List;
import java.util.Map;

/**
 * Autorise les routes /export/structure/* (lancement, statut, téléchargement, suppression d'un
 * lot) aux super-administrateurs, ainsi qu'aux comptes ADMIN_COLLECTIVITE dont le périmètre de
 * fonction (rf.scope, déjà étendu par entcore aux établissements rattachés quand la fonction est
 * octroyée avec inherit="s") couvre l'établissement concerné. La MFA reste exigée par ailleurs
 * (@MfaProtected sur le contrôleur).
 *
 * /export/structure/admin/list (vue plateforme, tous lots confondus, sans notion d'établissement)
 * n'est PAS couvert ici : il reste sur SuperAdminFilter dans ArchiveController.
 */
public class StructureExportFilter implements ResourcesProvider {

	/** Renseigné par ArchiveController.init() — nécessaire pour résoudre structureId à partir d'un batchId. */
	private static StructureExportService structureExportService;

	public static void setStructureExportService(StructureExportService service) {
		structureExportService = service;
	}

	@Override
	public void authorize(HttpServerRequest resourceRequest, Binding binding, UserInfos user, Handler<Boolean> handler) {
		Map<String, UserInfos.Function> functions = user.getFunctions();
		if (functions == null || functions.isEmpty()) {
			handler.handle(false);
			return;
		}
		if (functions.containsKey(DefaultFunctions.SUPER_ADMIN)) {
			handler.handle(true);
			return;
		}
		final UserInfos.Function collectivite = functions.get(DefaultFunctions.ADMIN_COLLECTIVITE);
		final List<String> scope = collectivite != null ? collectivite.getScope() : null;
		if (scope == null || scope.isEmpty()) {
			handler.handle(false);
			return;
		}

		final String batchId = resourceRequest.params().get("batchId");
		if (batchId != null) {
			// Statut / téléchargement / suppression d'un lot déjà lancé : résoudre son structureId.
			if (structureExportService == null) {
				handler.handle(false);
				return;
			}
			resourceRequest.pause();
			structureExportService.status(batchId).onComplete(res -> {
				resourceRequest.resume();
				final String structureId = res.succeeded() && res.result() != null
						? res.result().getString("structureId") : null;
				handler.handle(structureId != null && scope.contains(structureId));
			});
			return;
		}

		// Lancement d'un nouvel export : structureId porté par le corps de la requête.
		RequestUtils.bodyToJson(resourceRequest, body -> {
			String structureId = body != null ? body.getString("structureId") : null;
			handler.handle(structureId != null && scope.contains(structureId));
		});
	}

}
