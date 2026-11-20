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
import fr.wseduc.mongodb.MongoDb;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.JsonObject;
import org.entcore.auth.security.ClientDevice;
import org.entcore.auth.services.ImpersonationService;
import org.entcore.common.events.EventStore;
import org.entcore.common.impersonation.Impersonation;
import org.entcore.common.user.UserInfos;
import org.entcore.common.user.UserUtils;
import org.entcore.common.utils.MapFactory;
import org.vertx.java.core.http.RouteMatcher;

import java.util.Map;
import java.util.UUID;

/**
 * Mode assistance : un administrateur habilité ouvre une session de l'usager qu'il aide, pour
 * voir l'ENT tel que celui-ci le voit, sans pouvoir rien y écrire.
 *
 * <p>La session ouverte est une session ordinaire de l'usager — ses droits à lui, jamais ceux de
 * l'administrateur. Elle porte un identifiant marqué et daté ({@link Impersonation}) ; c'est lui
 * qui impose la lecture seule et l'expiration, dans le filtre d'action commun à tous les modules.</p>
 *
 * <p>Deux façons d'ouvrir la séance, selon la configuration :</p>
 * <ul>
 * <li><b>avec un hôte dédié</b> ({@code impersonation-host}) : la séance s'ouvre sur cet hôte, par
 * un jeton de passage à usage unique. Les cookies du socle étant posés sans {@code Domain}, la
 * console garde la session de l'administrateur et l'onglet d'assistance a celle de l'usager ;</li>
 * <li><b>sans</b> : la session de ce navigateur bascule. Celle de l'administrateur n'est pas
 * détruite : elle est mise de côté dans un cookie signé, et restituée à la sortie.</li>
 * </ul>
 */
public class ImpersonationController extends BaseController {

	public enum ImpersonationEvent {
		IMPERSONATION_START, IMPERSONATION_STOP
	}

	private static final int MIN_REASON_LENGTH = 5;

	/** Un jeton de passage ne sert qu'une fois, et pas longtemps. */
	private static final long HANDOFF_TTL_MS = 120_000L;

	private EventStore eventStore;
	private ImpersonationService impersonationService;

	/**
	 * Jetons de passage vers l'hôte dédié : <code>jeton → identifiant de session + échéance</code>.
	 *
	 * <p>Un cookie ne se pose que sur l'hôte qui répond. Pour ouvrir l'assistance sur un autre
	 * hôte que celui de la console, il faut donc que le navigateur s'y rende lui-même, et y
	 * présente de quoi récupérer la session. Ce jeton est cette preuve : à usage unique, valable
	 * deux minutes, et accepté par le seul hôte d'assistance.</p>
	 */
	private Map<String, String> handoffTokens;

	@Override
	public void init(Vertx vertx, JsonObject config, RouteMatcher rm,
			Map<String, fr.wseduc.webutils.security.SecuredAction> securedActions) {
		super.init(vertx, config, rm, securedActions);
		this.handoffTokens = MapFactory.getSyncClusterMap("impersonationHandoff", vertx);
	}

	/**
	 * Hôte réellement demandé par le navigateur.
	 *
	 * <p>{@code X-Forwarded-Host} d'abord : le tableau de bord parle au socle par son adresse
	 * interne, et c'est par cet en-tête qu'il transmet l'hôte de la console. Puis {@code Host}
	 * brut. On ne passe pas par {@code getHost()}, qui ramène toute requête à un hôte connu de la
	 * liste des thèmes : un hôte d'assistance absent de cette liste serait rabattu sur l'hôte
	 * principal, et la table ne s'y retrouverait plus.</p>
	 *
	 * <p>Cet en-tête n'est pas un contrôle d'accès : il choisit seulement où ouvrir la séance. Ce
	 * qui sépare les sessions, c'est le navigateur — un cookie ne vaut que pour l'hôte auquel il a
	 * réellement parlé —, et ce qui ouvre la séance sur l'hôte dédié, c'est le jeton.</p>
	 */
	private String requestHost(final HttpServerRequest request) {
		final String forwarded = request.headers().get("X-Forwarded-Host");
		if (forwarded != null && !forwarded.trim().isEmpty()) {
			// Plusieurs mandataires en chaîne : le premier est celui que le navigateur a demandé.
			return forwarded.split(",")[0].trim();
		}
		final String raw = request.headers().get("Host");
		return raw != null && !raw.trim().isEmpty() ? raw.trim() : getHost(request);
	}

	/**
	 * Hôte dédié à l'assistance pour l'hôte courant, s'il y en a un.
	 *
	 * <p>{@code impersonation-host} vaut soit un hôte unique, soit une table
	 * <code>hôte de la console → hôte d'assistance</code> pour une plateforme multi-domaines.</p>
	 */
	private String assistanceHost(final HttpServerRequest request) {
		final Object configured = config.getValue("impersonation-host");
		if (configured instanceof String) {
			return (String) configured;
		}
		if (configured instanceof JsonObject) {
			return ((JsonObject) configured).getString(requestHost(request));
		}
		return null;
	}

	/**
	 * Hôte de la console dont dépend l'hôte d'assistance courant, s'il y en a un — c'est là que
	 * l'opérateur doit revenir en quittant.
	 */
	private String consoleHost(final HttpServerRequest request) {
		final Object configured = config.getValue("impersonation-host");
		if (!(configured instanceof JsonObject)) {
			return null;
		}
		final String host = requestHost(request);
		for (final Map.Entry<String, Object> entry : ((JsonObject) configured).getMap().entrySet()) {
			if (host.equals(entry.getValue())) {
				return entry.getKey();
			}
		}
		return null;
	}

	/** Vrai si l'hôte courant est un hôte d'assistance déclaré. */
	private boolean onAssistanceHost(final HttpServerRequest request) {
		final Object configured = config.getValue("impersonation-host");
		final String host = requestHost(request);
		if (configured instanceof String) {
			return configured.equals(host);
		}
		if (configured instanceof JsonObject) {
			return ((JsonObject) configured).getMap().containsValue(host);
		}
		return false;
	}

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

				// Sans hôte dédié, la lecture seule ne repose que sur le filtre d'action, chargé
				// par chaque module depuis son propre fat-jar : elle ne tient que dans les modules
				// reconstruits avec ce socle. Constaté en local — une écriture dans la messagerie
				// allait jusqu'à la base. Sur l'hôte dédié, c'est l'ingress qui refuse toute
				// écriture, pour tous les modules. Le mode sans hôte dédié reste possible, mais
				// seulement si on le demande, en connaissance de cause.
				if (assistanceHost(request) == null && !config.getBoolean("impersonation-same-host", false)) {
					forbidden(request, "impersonation.error.no.dedicated.host");
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

		final JsonObject device = ClientDevice.infos(request);
		UserUtils.createSessionWithId(eb, target.getString("id"), sessionId, false, device)
			.compose(created -> created == null
					? Future.<String>failedFuture("impersonation.session.not.created")
					: persistSession(sessionId, target.getString("id"), device))
			.onFailure(th -> renderError(request))
			.onSuccess(created -> {
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
					final JsonObject answer = new JsonObject()
							.put("user", target)
							.put("readOnly", true)
							.put("startedAt", startedAt)
							.put("expiresAt", expiresAt);
					final String assistanceHost = assistanceHost(request);
					if (assistanceHost != null) {
						// L'assistance s'ouvre sur un hôte dédié : on ne touche pas aux cookies de
						// celui-ci. La console reste la session de l'administrateur, l'onglet
						// d'assistance sera celle de l'usager, et les deux ne se voient pas — les
						// cookies du socle sont posés sans Domain, donc propres à leur hôte.
						answer.put("enterUrl", handoffUrl(request, assistanceHost, sessionId));
					} else {
						// Pas d'hôte dédié : la session de ce navigateur bascule. Les cookies
						// vivent plus longtemps que l'assistance elle-même, et c'est voulu : si le
						// cookie expirait avec elle, l'opérateur serait renvoyé à la page de
						// connexion sans explication, et la session mise de côté disparaîtrait avec
						// son cookie. En les gardant, l'échéance est prononcée par le filtre, qui
						// dit pourquoi, et /stop reste joignable.
						final long timeout = config.getLong("cookie_timeout", Long.MIN_VALUE);
						CookieHelper.getInstance().setSigned(Impersonation.IMPERSONATOR_COOKIE,
								adminSessionId, timeout, request);
						CookieHelper.getInstance().setSigned("oneSessionId", sessionId, timeout, request);
						CookieHelper.set("authenticated", "true", timeout, request);
						// Lisible par le navigateur : c'est ce qui permet aux interfaces d'afficher
						// le bandeau sans interroger le serveur à chaque page.
						CookieHelper.set(Impersonation.MARKER_COOKIE, "1", timeout, request);
						answer.put("enterUrl", "/");
					}

					eventStore.createAndStoreEvent(ImpersonationEvent.IMPERSONATION_START.name(), admin,
							new JsonObject()
									.put("targetUserId", target.getString("id"))
									.put("targetDisplayName", target.getString("displayName"))
									.put("targetProfile", target.getString("profile"))
									.put("reason", reason)
									.put("expiresAt", expiresAt)
									.put("assistanceHost", assistanceHost));

					renderJson(request, answer);
				});
			});
	}

	/**
	 * Inscrit la session d'assistance dans la collection {@code sessions}, comme le module
	 * {@code session} le fait pour une connexion ordinaire.
	 *
	 * <p>Le module {@code session} traite un identifiant imposé comme le rafraîchissement d'une
	 * session existante : il range la session en mémoire, mais dans Mongo il met à jour un
	 * document qu'il ne crée pas. Or quand une session sort de la mémoire partagée (éviction,
	 * redémarrage de la grille), le socle la reconstruit depuis ce document. Sans lui, la séance
	 * d'assistance mourait à la première éviction : le portail ne trouvait plus d'usager et
	 * affichait la vitrine publique. Constaté en production le 20/11/2026, reproduit en local en
	 * redémarrant le module {@code session}.</p>
	 *
	 * <p>Le document porte ce que la reconstruction relit : l'usager, et la description de
	 * l'appareil. La fermeture de la séance le supprime avec la session.</p>
	 */
	private Future<String> persistSession(final String sessionId, final String userId,
			final JsonObject device) {
		final Promise<String> promise = Promise.promise();
		final JsonObject now = MongoDb.now();
		final JsonObject doc = new JsonObject()
				.put("_id", sessionId)
				.put("userId", userId)
				.put("created", now)
				.put("lastUsed", now);
		if (device != null) {
			for (final String key : new String[] { "ip", "ua", "deviceId" }) {
				if (device.getValue(key) != null) {
					doc.put(key, device.getValue(key));
				}
			}
		}
		MongoDb.getInstance().save("sessions", doc, res -> {
			if ("ok".equals(res.body().getString("status"))) {
				promise.complete(sessionId);
			} else {
				promise.fail("impersonation.session.not.persisted: " + res.body().getString("message"));
			}
		});
		return promise.future();
	}

	/**
	 * Fabrique l'adresse par laquelle le navigateur ira prendre sa session sur l'hôte dédié.
	 * Le jeton ne voyage qu'une fois, dans une barre d'adresse, et ne vaut rien passé deux
	 * minutes ni une seconde utilisation.
	 */
	private String handoffUrl(final HttpServerRequest request, final String assistanceHost,
			final String sessionId) {
		final String token = UUID.randomUUID().toString();
		handoffTokens.put(token, sessionId + "|" + (System.currentTimeMillis() + HANDOFF_TTL_MS));
		// Un jeton jamais présenté ne doit pas rester dans la table partagée : l'échéance qu'il
		// porte le rend inutilisable, ce minuteur le fait disparaître.
		vertx.setTimer(HANDOFF_TTL_MS, id -> handoffTokens.remove(token));
		return getScheme(request) + "://" + assistanceHost + "/auth/impersonate/enter?t=" + token;
	}

	/**
	 * Prise de session sur l'hôte dédié.
	 *
	 * <p>Volontairement sans {@code @SecuredAction} : le navigateur n'a par construction aucune
	 * session sur cet hôte quand il arrive ici. C'est le jeton qui fait foi, et lui seul.</p>
	 */
	@Get("/impersonate/enter")
	@ApiDoc("Prend la session d'assistance sur l'hôte dédié, à partir d'un jeton de passage")
	public void enter(final HttpServerRequest request) {
		// Un jeton présenté ailleurs que sur l'hôte d'assistance n'a rien à faire là : sans ce
		// contrôle, il suffirait de le rejouer sur l'hôte de la console pour y basculer la session.
		if (!onAssistanceHost(request)) {
			refuseEnter(request, "impersonation.error.host");
			return;
		}
		final String token = request.params().get("t");
		final String stored = token != null ? handoffTokens.remove(token) : null;
		if (stored == null) {
			refuseEnter(request, "impersonation.error.handoff");
			return;
		}
		final int separator = stored.lastIndexOf('|');
		final String sessionId = separator > 0 ? stored.substring(0, separator) : null;
		long until = 0L;
		try {
			until = separator > 0 ? Long.parseLong(stored.substring(separator + 1)) : 0L;
		} catch (NumberFormatException e) {
			until = 0L;
		}
		if (!Impersonation.isImpersonationSessionId(sessionId) || System.currentTimeMillis() > until) {
			refuseEnter(request, "impersonation.error.handoff");
			return;
		}

		final long timeout = config.getLong("cookie_timeout", Long.MIN_VALUE);
		CookieHelper.getInstance().setSigned("oneSessionId", sessionId, timeout, request);
		CookieHelper.set("authenticated", "true", timeout, request);
		CookieHelper.set(Impersonation.MARKER_COOKIE, "1", timeout, request);
		// Redirection *relative*, posée à la main : redirect() reconstruit une adresse absolue à
		// partir de getHost(), qui rabat tout hôte inconnu de la liste des thèmes sur l'hôte
		// principal — l'opérateur serait renvoyé sur la console, c'est-à-dire exactement ce que
		// l'hôte dédié cherche à éviter. Une adresse relative reste sur l'hôte courant, quelle
		// que soit la configuration.
		request.response().setStatusCode(302).putHeader("Location", "/").end();
	}

	/**
	 * Refus de la prise de session. On y arrive en suivant un lien, pas par un appel d'API : une
	 * réponse JSON s'afficherait brute dans l'onglet. Pas de lien vers la sortie ici — il n'y a
	 * pas de séance à quitter —, seulement de quoi comprendre et recommencer.
	 */
	private void refuseEnter(final HttpServerRequest request, final String code) {
		final String label = "impersonation.error.host".equals(code)
				? "Ce lien ne s'ouvre que sur l'hôte réservé à l'assistance."
				: "Ce lien d'assistance n'est plus valable : il ne sert qu'une fois, dans les deux "
						+ "minutes. Relancez l'assistance depuis la fiche de l'usager.";
		request.response().setStatusCode(403).setStatusMessage("Forbidden")
				.putHeader("content-type", "text/html; charset=utf-8")
				.putHeader("Cache-Control", "no-store")
				.end("<!doctype html><html lang=\"fr\"><head><meta charset=\"utf-8\">"
						+ "<title>Mode assistance</title></head><body style=\"font-family:sans-serif;"
						+ "max-width:34rem;margin:4rem auto;line-height:1.5\"><h1>Mode assistance</h1>"
						+ "<p>" + label + "</p><!-- " + code + " --></body></html>");
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

			// Le document inscrit à l'ouverture (persistSession) n'est pas toujours retiré par le
			// module session : on le retire ici, sans quoi la séance resterait reconstructible
			// jusqu'à ce que son cookie disparaisse.
			MongoDb.getInstance().delete("sessions", new JsonObject().put("_id", sessionId));
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
				CookieHelper.set(Impersonation.MARKER_COOKIE, "", 0L, request);
				final JsonObject answer = new JsonObject().put("stopped", Boolean.TRUE.equals(deleted));
				// Sur l'hôte dédié, il n'y a rien à rendre : la session de l'administrateur n'a
				// jamais quitté l'hôte de la console. On dit seulement où revenir, car cet onglet
				// n'a plus de session du tout et sa page d'accueil renverrait à la connexion.
				final String consoleHost = consoleHost(request);
				if (consoleHost != null) {
					answer.put("redirectUrl", getScheme(request) + "://" + consoleHost + "/");
				}
				// Suivi comme un lien (la page de refus en propose un) : on emmène le navigateur
				// là où il doit aller plutôt que de lui afficher du JSON.
				final String accept = request.headers().get("Accept");
				if (accept != null && accept.contains("text/html")) {
					request.response().setStatusCode(302)
							.putHeader("Location", consoleHost != null ? answer.getString("redirectUrl") : "/")
							.end();
					return;
				}
				renderJson(request, answer);
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
