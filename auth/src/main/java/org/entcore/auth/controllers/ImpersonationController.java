/*
 * Copyright © "Open ENT", 2026
 *
 * This program is free software, licensed under the terms of the GNU Affero General Public License
 * as published by the Free Software Foundation, version 3 of the License.
 */

package org.entcore.auth.controllers;

import fr.wseduc.rs.ApiDoc;
import fr.wseduc.rs.Get;
import fr.wseduc.rs.Post;
import fr.wseduc.security.ActionType;
import fr.wseduc.security.SecuredAction;
import fr.wseduc.webutils.http.BaseController;
import fr.wseduc.webutils.request.CookieHelper;
import fr.wseduc.webutils.request.RequestUtils;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.JsonObject;
import org.entcore.auth.security.ClientDevice;
import org.entcore.auth.services.ImpersonationService;
import org.entcore.common.events.EventStore;
import org.entcore.common.impersonation.Impersonation;
import org.entcore.common.user.UserInfos;
import org.entcore.common.user.UserUtils;

/**
 * Mode assistance : un administrateur habilité ouvre une session de l'usager qu'il aide, pour
 * voir l'ENT tel que celui-ci le voit, sans pouvoir rien y écrire.
 *
 * <p>La session ouverte est une session ordinaire de l'usager — ses droits à lui, jamais ceux de
 * l'administrateur. Elle porte un identifiant marqué et daté ({@link Impersonation}) ; c'est lui
 * qui impose la lecture seule et l'expiration, dans le filtre d'action commun à tous les modules.</p>
 *
 * <p>La session de l'administrateur n'est pas détruite : elle est mise de côté dans un cookie
 * signé, et restituée à la sortie. Un prototype s'en contente ; en cible, l'assistance s'ouvre
 * sur un hôte dédié, dont le bocal à cookies est séparé de celui de l'administration.</p>
 */
public class ImpersonationController extends BaseController {

	public enum ImpersonationEvent {
		IMPERSONATION_START, IMPERSONATION_STOP
	}

	private static final int MIN_REASON_LENGTH = 5;

	private EventStore eventStore;
	private ImpersonationService impersonationService;

	@Post("/impersonate")
	@SecuredAction(value = "", type = ActionType.AUTHENTICATED)
	@ApiDoc("Ouvre une session d'assistance, en lecture seule, sur le compte d'un usager")
	public void start(final HttpServerRequest request) {
		RequestUtils.bodyToJson(request, body -> UserUtils.getAuthenticatedUserInfos(eb, request)
			.onFailure(th -> unauthorized(request))
			.onSuccess(admin -> {
				if (admin == null) {
					unauthorized(request);
					return;
				}
				final String adminSessionId = CookieHelper.getInstance().getSigned("oneSessionId", request);
				// Pas de chaînage : on n'ouvre pas une assistance depuis une assistance, sans quoi
				// la session mise de côté serait perdue et la trace deviendrait illisible.
				if (adminSessionId == null || Impersonation.isImpersonationSessionId(adminSessionId)) {
					forbidden(request, "impersonation.error.chaining");
					return;
				}

				final String reason = body.getString("reason", "").trim();
				if (reason.length() < MIN_REASON_LENGTH) {
					badRequest(request, "impersonation.error.reason.required");
					return;
				}
				// Une durée absente, nulle ou négative vaut la durée par défaut ; au-delà du
				// plafond, c'est le plafond qui s'applique.
				final long asked = body.getLong("durationMinutes", 0L) * 60000L;
				final long duration = Math.min(
						asked > 0L ? asked : Impersonation.DEFAULT_DURATION_MS,
						Impersonation.MAX_DURATION_MS);

				impersonationService.checkTarget(admin, body.getString("userId"))
					.onFailure(th -> forbidden(request, th.getMessage()))
					.onSuccess(target -> openSession(request, admin, target, reason, duration));
			}));
	}

	private void openSession(final HttpServerRequest request, final UserInfos admin,
			final JsonObject target, final String reason, final long duration) {
		final long startedAt = System.currentTimeMillis();
		final long expiresAt = startedAt + duration;
		final String sessionId = Impersonation.newSessionId(expiresAt);
		final String adminSessionId = CookieHelper.getInstance().getSigned("oneSessionId", request);

		UserUtils.createSessionWithId(eb, target.getString("id"), sessionId, false, ClientDevice.infos(request))
			.onFailure(th -> renderError(request))
			.onSuccess(created -> {
				if (created == null) {
					renderError(request);
					return;
				}
				final JsonObject infos = new JsonObject()
						.put("impersonatorId", admin.getUserId())
						.put("impersonatorName", admin.getUsername())
						.put("targetId", target.getString("id"))
						.put("targetName", target.getString("displayName"))
						.put("reason", reason)
						.put("startedAt", startedAt)
						.put("expiresAt", expiresAt)
						.put("readOnly", true);

				// Description de l'assistance, pour l'affichage et le diagnostic. Sa perte
				// éventuelle n'ouvre aucun droit : la lecture seule tient à l'identifiant.
				UserUtils.addSessionAttributeOnId(eb, sessionId, Impersonation.SESSION_FIELD,
						infos.encode(), ignored -> {
					// Les cookies vivent plus longtemps que l'assistance elle-même, et c'est
					// voulu : si le cookie expirait avec elle, l'opérateur serait renvoyé à la
					// page de connexion sans explication, et la session qu'il avait mise de côté
					// disparaîtrait avec son cookie — il devrait se reconnecter. En les gardant,
					// l'échéance est prononcée par le filtre, qui dit pourquoi, et /stop reste
					// joignable pour rendre à l'administrateur sa propre session.
					final long timeout = config.getLong("cookie_timeout", Long.MIN_VALUE);
					CookieHelper.getInstance().setSigned(Impersonation.IMPERSONATOR_COOKIE,
							adminSessionId, timeout, request);
					CookieHelper.getInstance().setSigned("oneSessionId", sessionId, timeout, request);
					CookieHelper.set("authenticated", "true", timeout, request);

					eventStore.createAndStoreEvent(ImpersonationEvent.IMPERSONATION_START.name(), admin,
							new JsonObject()
									.put("targetUserId", target.getString("id"))
									.put("targetDisplayName", target.getString("displayName"))
									.put("targetProfile", target.getString("profile"))
									.put("reason", reason)
									.put("expiresAt", expiresAt));

					renderJson(request, new JsonObject()
							.put("user", target)
							.put("readOnly", true)
							.put("startedAt", startedAt)
							.put("expiresAt", expiresAt));
				});
			});
	}

	@Get("/impersonate/stop")
	@SecuredAction(value = "", type = ActionType.AUTHENTICATED)
	@ApiDoc("Ferme la session d'assistance et rend la main à l'administrateur")
	public void stop(final HttpServerRequest request) {
		final String sessionId = CookieHelper.getInstance().getSigned("oneSessionId", request);
		if (!Impersonation.isImpersonationSessionId(sessionId)) {
			badRequest(request, "impersonation.error.not.impersonating");
			return;
		}
		final String adminSessionId = CookieHelper.getInstance()
				.getSigned(Impersonation.IMPERSONATOR_COOKIE, request);

		UserUtils.getSession(eb, request, session -> {
			final JsonObject infos = Impersonation.infos(session);
			eventStore.createAndStoreEvent(ImpersonationEvent.IMPERSONATION_STOP.name(), request,
					new JsonObject()
							.put("impersonatorId", infos != null ? infos.getString("impersonatorId") : null)
							.put("reason", infos != null ? infos.getString("reason") : null)
							.put("startedAt", infos != null ? infos.getLong("startedAt") : null)
							.put("endedAt", System.currentTimeMillis()));

			UserUtils.deleteSession(eb, sessionId, deleted -> {
				if (adminSessionId != null && !adminSessionId.trim().isEmpty()) {
					// On rend à l'administrateur la session qu'il avait avant l'assistance : elle
					// n'a jamais été détruite, seulement mise de côté.
					final long timeout = config.getLong("cookie_timeout", Long.MIN_VALUE);
					CookieHelper.getInstance().setSigned("oneSessionId", adminSessionId, timeout, request);
					CookieHelper.set("authenticated", "true", timeout, request);
				} else {
					CookieHelper.set("oneSessionId", "", 0L, request);
					CookieHelper.set("authenticated", "", 0L, request);
				}
				CookieHelper.set(Impersonation.IMPERSONATOR_COOKIE, "", 0L, request);
				renderJson(request, new JsonObject().put("stopped", Boolean.TRUE.equals(deleted)));
			});
		});
	}

	@Get("/impersonate/status")
	@SecuredAction(value = "", type = ActionType.AUTHENTICATED)
	@ApiDoc("Décrit l'assistance en cours, pour le bandeau des interfaces")
	public void status(final HttpServerRequest request) {
		final String sessionId = CookieHelper.getInstance().getSigned("oneSessionId", request);
		if (!Impersonation.isImpersonationSessionId(sessionId)) {
			renderJson(request, new JsonObject().put("impersonated", false));
			return;
		}
		UserUtils.getSession(eb, request, session -> {
			final JsonObject infos = Impersonation.infos(session);
			final JsonObject answer = new JsonObject()
					.put("impersonated", true)
					.put("readOnly", true)
					.put("expiresAt", Impersonation.expiresAt(sessionId));
			if (infos != null) {
				answer.put("impersonatorName", infos.getString("impersonatorName"))
						.put("targetName", infos.getString("targetName"))
						.put("reason", infos.getString("reason"))
						.put("startedAt", infos.getLong("startedAt"));
			}
			renderJson(request, answer);
		});
	}

	public void setEventStore(final EventStore eventStore) {
		this.eventStore = eventStore;
	}

	public void setImpersonationService(final ImpersonationService impersonationService) {
		this.impersonationService = impersonationService;
	}
}
