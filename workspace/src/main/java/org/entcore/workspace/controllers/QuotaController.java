/*
 * Copyright © "Open Digital Education", 2014
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

 */

package org.entcore.workspace.controllers;

import static org.entcore.common.http.response.DefaultResponseHandler.arrayResponseHandler;
import static org.entcore.common.http.response.DefaultResponseHandler.defaultResponseHandler;
import static org.entcore.common.http.response.DefaultResponseHandler.notEmptyResponseHandler;

import org.entcore.common.folders.QuotaService;
import org.entcore.common.user.UserUtils;

import fr.wseduc.bus.BusAddress;
import fr.wseduc.rs.Get;
import fr.wseduc.rs.Post;
import fr.wseduc.rs.Put;
import fr.wseduc.rs.Delete;
import org.entcore.common.user.DefaultFunctions;
import org.entcore.common.user.UserInfos;
import fr.wseduc.security.ActionType;
import fr.wseduc.security.MfaProtected;
import fr.wseduc.security.SecuredAction;
import fr.wseduc.webutils.Either;
import fr.wseduc.webutils.http.BaseController;
import fr.wseduc.webutils.request.RequestUtils;
import io.vertx.core.Handler;
import io.vertx.core.eventbus.Message;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.JsonObject;

public class QuotaController extends BaseController {

	private QuotaService quotaService;

	/**
	 * Seuil d'alerte de la plate-forme (`alertStorage`), servi aux établissements qui n'en
	 * fixent pas. Renseigné au démarrage par le verticle, avec la même valeur que celle
	 * transmise aux services de quota — les deux ne doivent pas diverger.
	 */
	private int defaultAlertThreshold = 80;

	@Get("/quota/user/:userId")
	@SecuredAction(value = "", type = ActionType.RESOURCE)
	public void getQuota(final HttpServerRequest request) {
		String userId = request.params().get("userId");
		quotaService.quotaAndUsage(userId, res -> {
			if (res.isRight() && (res.right().getValue() == null || res.right().getValue().size() == 0)) {
				// UserBook absent (utilisateur jamais activé) — on l'initialise et on renvoie les valeurs par défaut
				quotaService.init(userId);
				renderJson(request, new JsonObject().put("quota", 0L).put("storage", 0L));
			} else {
				notEmptyResponseHandler(request).handle(res);
			}
		});
	}

	@Get("/quota/structure/:structureId")
	@SecuredAction(value = "", type = ActionType.RESOURCE)
	@MfaProtected()
	public void getQuotaStructure(final HttpServerRequest request) {
		String structureId = request.params().get("structureId");
		quotaService.quotaAndUsageStructure(structureId, notEmptyResponseHandler(request));
	}

	@Get("/quota/global")
	@SecuredAction(value = "", type = ActionType.RESOURCE)
	@MfaProtected()
	public void getQuotaGlobal(final HttpServerRequest request) {
		quotaService.quotaAndUsageGlobal(notEmptyResponseHandler(request));
	}

	@Put("/quota")
	@SecuredAction(value = "", type = ActionType.RESOURCE)
	@MfaProtected()
	public void update(final HttpServerRequest request) {
		RequestUtils.bodyToJson(request, pathPrefix + "updateQuota", new Handler<JsonObject>() {
			@Override
			public void handle(JsonObject object) {
				quotaService.update(object.getJsonArray("users"), object.getLong("quota"), arrayResponseHandler(request));
			}
		});
	}

	@Put("/quota/default/:profile")
	@SecuredAction(value = "", type = ActionType.RESOURCE)
	@MfaProtected()
	public void updateDefault(final HttpServerRequest request) {
		RequestUtils.bodyToJson(request, pathPrefix + "updateDefaultQuota", new Handler<JsonObject>() {
			@Override
			public void handle(JsonObject object) {
				String profile = request.params().get("profile");
				quotaService.updateQuotaDefaultMax(profile, object.getLong("defaultQuota"), object.getLong("maxQuota"),
						notEmptyResponseHandler(request));
			}
		});
	}

	/**
	 * Met à jour le quota de tous les utilisateurs d'un profil, pour toutes les structures
	 * d'un même département (modification multi-établissements).
	 */
	@Put("/quota/department/:departmentCode/profile/:profile")
	@SecuredAction(value = "", type = ActionType.RESOURCE)
	@MfaProtected()
	public void updateByDepartmentAndProfile(final HttpServerRequest request) {
		RequestUtils.bodyToJson(request, pathPrefix + "updateQuotaByDepartment", new Handler<JsonObject>() {
			@Override
			public void handle(JsonObject object) {
				String departmentCode = request.params().get("departmentCode");
				String profile = request.params().get("profile");
				quotaService.updateByProfileAndDepartment(profile, departmentCode, object.getLong("quota"),
						arrayResponseHandler(request));
			}
		});
	}

	/**
	 * Départements sur lesquels l'utilisateur connecté a le droit d'appliquer un quota
	 * (SuperAdmin : tous ; ADML : uniquement ceux où son périmètre couvre 100% des
	 * établissements). Alimente le sélecteur du dashboard pour n'y proposer que des
	 * départements réellement actionnables.
	 */
	@Get("/quota/department/allowed")
	@SecuredAction(value = "", type = ActionType.RESOURCE)
	public void getAllowedDepartments(final HttpServerRequest request) {
		UserUtils.getUserInfos(eb, request, user -> {
			if (user == null) {
				unauthorized(request);
				return;
			}
			quotaService.getAllowedDepartments(user, arrayResponseHandler(request));
		});
	}

	@Get("/quota/default")
	@SecuredAction(value = "", type = ActionType.RESOURCE)
	@MfaProtected()
	public void getDefault(final HttpServerRequest request) {
		quotaService.getDefaultMaxQuota(arrayResponseHandler(request));
	}

	/**
	 * POST /quota/user/:userId/init
	 * Crée le UserBook (quota) si absent (idempotent via MERGE).
	 * Utile pour les comptes non encore activés via activation.ack.
	 */
	@Post("/quota/user/:userId/init")
	@SecuredAction(value = "", type = ActionType.RESOURCE)
	public void initUserQuota(final HttpServerRequest request) {
		String userId = request.params().get("userId");
		quotaService.init(userId);
		request.response().setStatusCode(204).end();
	}

	@BusAddress("activation.ack")
	public void initQuota(final Message<JsonObject> message){
		String userId = message.body().getString("userId");
		if (userId != null && !userId.trim().isEmpty()) {
			quotaService.init(userId);
		}
	}

	@BusAddress("org.entcore.workspace.quota")
	public void quotaEventBusHandler(final Message<JsonObject> message){
		Handler<Either<String, JsonObject>> responseHandler = new Handler<Either<String, JsonObject>>() {
			@Override
			public void handle(Either<String, JsonObject> res) {
				if (res.isRight()) {
					message.reply(res.right().getValue());
				} else {
					message.reply(new JsonObject().put("status", "error")
							.put("message", res.left().getValue()));
				}
			}
		};

		String userId = message.body().getString("userId");

		switch (message.body().getString("action", "")) {
			case "getUserQuota" :
				quotaService.quotaAndUsage(userId, responseHandler);
				break;
			case "updateUserQuota" :
				long size = message.body().getLong("size");
				int threshold = message.body().getInteger("threshold");
				quotaService.incrementStorage(userId, size, threshold, responseHandler);
				break;
			default:
				message.reply(new JsonObject().put("status", "error").put("message", "invalid.action"));
		}
	}


	public void setDefaultAlertThreshold(int defaultAlertThreshold) {
		this.defaultAlertThreshold = defaultAlertThreshold;
	}

	public void setQuotaService(QuotaService quotaService) {
		this.quotaService = quotaService;
	}

	// ── Seuil d'alerte d'occupation du stockage ────────────────────────────────
	// Le seuil au-delà duquel l'utilisateur est prévenu que son espace se remplit
	// (« Votre espace de stockage est bientôt plein »). Défaut de plate-forme `alertStorage`,
	// surchargeable par établissement.
	//
	// Sécurité sans nouveau workflow (pas de churn app-registry, cf. MessagingHoursController) :
	// routes AUTHENTICATED, puis vérification manuelle de SUPER_ADMIN ou de l'ADML de la
	// structure visée.

	/** Seuil applicable à un établissement : le sien, ou celui de la plate-forme dont il hérite. */
	@Get("/quota/alert-threshold/:structureId")
	@SecuredAction(value = "", type = ActionType.AUTHENTICATED)
	public void getAlertThreshold(final HttpServerRequest request) {
		final String structureId = request.params().get("structureId");
		UserUtils.getUserInfos(eb, request, user -> {
			if (!canAdminStructure(user, structureId)) { unauthorized(request); return; }
			quotaService.getStorageAlertThreshold(structureId, res -> {
				if (res.isRight()) {
					final JsonObject found = res.right().getValue();
					final Integer own = found.getInteger("threshold");
					renderJson(request, found.copy()
							.put("threshold", own != null ? own : defaultAlertThreshold)
							.put("defaultThreshold", defaultAlertThreshold)
							.put("inherited", own == null));
				} else {
					renderError(request, new JsonObject().put("error", res.left().getValue()));
				}
			});
		});
	}

	/** Fixer le seuil d'un établissement. */
	@Put("/quota/alert-threshold/:structureId")
	@SecuredAction(value = "", type = ActionType.AUTHENTICATED)
	public void setAlertThreshold(final HttpServerRequest request) {
		final String structureId = request.params().get("structureId");
		UserUtils.getUserInfos(eb, request, user -> {
			if (!canAdminStructure(user, structureId)) { unauthorized(request); return; }
			RequestUtils.bodyToJson(request, body -> {
				final Integer threshold = body.getInteger("threshold");
				// Un seuil hors de ]0,100[ n'a pas de sens : à 0 tout le monde est en alerte en
				// permanence, à 100 l'alerte n'arrive jamais avant le refus d'écriture.
				if (threshold == null || threshold < 1 || threshold > 99) {
					badRequest(request, "quota.alert.threshold.invalid");
					return;
				}
				quotaService.setStorageAlertThreshold(structureId, threshold,
						defaultResponseHandler(request));
			});
		});
	}

	/** Retirer la surcharge : l'établissement revient au seuil de la plate-forme. */
	@Delete("/quota/alert-threshold/:structureId")
	@SecuredAction(value = "", type = ActionType.AUTHENTICATED)
	public void deleteAlertThreshold(final HttpServerRequest request) {
		final String structureId = request.params().get("structureId");
		UserUtils.getUserInfos(eb, request, user -> {
			if (!canAdminStructure(user, structureId)) { unauthorized(request); return; }
			quotaService.setStorageAlertThreshold(structureId, null, defaultResponseHandler(request));
		});
	}

	private boolean canAdminStructure(final UserInfos user, final String structureId) {
		if (user == null || structureId == null || user.getFunctions() == null) return false;
		if (user.getFunctions().containsKey(DefaultFunctions.SUPER_ADMIN)) return true;
		final UserInfos.Function adml = user.getFunctions().get(DefaultFunctions.ADMIN_LOCAL);
		return adml != null && adml.getScope() != null && adml.getScope().contains(structureId);
	}

}
