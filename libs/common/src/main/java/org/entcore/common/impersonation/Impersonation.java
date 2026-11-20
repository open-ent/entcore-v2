/*
 * Copyright © "Open ENT", 2026
 *
 * This program is free software, licensed under the terms of the GNU Affero General Public License
 * as published by the Free Software Foundation, version 3 of the License.
 */

package org.entcore.common.impersonation;

import fr.wseduc.webutils.http.Renders;
import fr.wseduc.webutils.request.CookieHelper;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.JsonObject;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Mode assistance : un administrateur habilité ouvre une session de l'utilisateur qu'il aide,
 * pour voir l'ENT tel que celui-ci le voit, en lecture seule.
 *
 * <p>La session ouverte est une session ordinaire de l'utilisateur cible : elle ne porte que
 * les droits de celui-ci, jamais ceux de l'administrateur. Deux marqueurs la distinguent :</p>
 * <ul>
 *   <li>son <strong>identifiant</strong>, de la forme {@code imp-&lt;expiration&gt;-&lt;uuid&gt;}. C'est
 *   lui qui fait foi : il est signé dans le cookie, il survit à une recréation de session et à
 *   une purge du cache, et il porte sa propre date d'expiration. Une session dont on ne sait
 *   plus rien reste donc en lecture seule, et expire à l'heure dite ;</li>
 *   <li>l'attribut de session {@code impersonation} (cache), qui décrit qui assiste qui et
 *   pourquoi. Il sert à l'affichage et au diagnostic ; sa disparition n'ouvre aucun droit.</li>
 * </ul>
 *
 * <p>La trace faisant autorité n'est ni l'un ni l'autre : ce sont les événements
 * {@code IMPERSONATION_START} / {@code IMPERSONATION_STOP} de l'event store.</p>
 */
public final class Impersonation {

	/** Préfixe des identifiants de session d'assistance. */
	public static final String SESSION_ID_PREFIX = "imp-";

	/** Clé de l'attribut de session décrivant l'assistance en cours. */
	public static final String SESSION_FIELD = "impersonation";

	/** Cookie signé où l'on met de côté la session de l'administrateur pendant l'assistance. */
	public static final String IMPERSONATOR_COOKIE = "oneImpersonatorSessionId";

	/** Posé sur chaque réponse servie à une session d'assistance. */
	public static final String HEADER = "X-ENT-Impersonation";

	/** Durée par défaut d'une session d'assistance. */
	public static final long DEFAULT_DURATION_MS = 30 * 60 * 1000L;

	/** Durée maximale acceptée, quelle que soit la demande. */
	public static final long MAX_DURATION_MS = 2 * 60 * 60 * 1000L;

	/** Routes toujours joignables : sans elles on ne pourrait plus sortir du mode assistance. */
	private static final List<String> ALWAYS_ALLOWED = Arrays.asList(
			"/auth/impersonate/stop",
			"/auth/impersonate/status");

	/**
	 * Routes refusées même en GET. Trois familles :
	 * <ul>
	 *   <li>la délivrance de jetons et le rebond SSO : une session d'assistance ne doit pas
	 *   pouvoir devenir une identité utilisable hors de l'ENT (GAR, Pronote, connecteurs) ;</li>
	 *   <li>les parcours de compte (activation, mot de passe oublié, validation) ;</li>
	 *   <li>l'export de données personnelles, et l'ouverture d'une assistance depuis une
	 *   assistance (pas de chaînage).</li>
	 * </ul>
	 */
	private static final List<Pattern> FORBIDDEN_PATHS = Arrays.asList(
			Pattern.compile("^/auth/oauth2/auth.*"),
			Pattern.compile("^/auth/oauth2/token.*"),
			Pattern.compile("^/auth/saml/.*"),
			Pattern.compile("^/auth/openid/.*"),
			Pattern.compile("^/cas/.*"),
			Pattern.compile("^/auth/reset.*"),
			Pattern.compile("^/auth/activation.*"),
			Pattern.compile("^/auth/forgot.*"),
			Pattern.compile("^/auth/mfa/.*"),
			Pattern.compile("^/auth/logout.*"),
			Pattern.compile("^/archive/.*"),
			Pattern.compile("^/auth/impersonate$"));

	private Impersonation() {
	}

	/**
	 * Fabrique un identifiant de session d'assistance portant sa date d'expiration.
	 * L'identifiant voyage signé dans le cookie {@code oneSessionId} : il n'est pas falsifiable
	 * côté client.
	 */
	public static String newSessionId(final long expiresAt) {
		return SESSION_ID_PREFIX + expiresAt + "-" + UUID.randomUUID().toString();
	}

	public static boolean isImpersonationSessionId(final String sessionId) {
		return sessionId != null && sessionId.startsWith(SESSION_ID_PREFIX);
	}

	/**
	 * @return l'instant d'expiration porté par l'identifiant, ou {@code -1} si l'identifiant est
	 * illisible — auquel cas la session est tenue pour expirée.
	 */
	public static long expiresAt(final String sessionId) {
		if (!isImpersonationSessionId(sessionId)) {
			return -1L;
		}
		final int end = sessionId.indexOf('-', SESSION_ID_PREFIX.length());
		if (end < 0) {
			return -1L;
		}
		try {
			return Long.parseLong(sessionId.substring(SESSION_ID_PREFIX.length(), end));
		} catch (NumberFormatException e) {
			return -1L;
		}
	}

	/**
	 * Identifiant de la session courante, lu d'abord dans les métadonnées de session, puis dans
	 * le cookie signé pour les cas où la session nous parvient filtrée (intégration HTTP).
	 */
	public static String sessionId(final HttpServerRequest request, final JsonObject session) {
		if (session != null) {
			final JsonObject metadata = session.getJsonObject("sessionMetadata");
			if (metadata != null && metadata.getString("_id") != null) {
				return metadata.getString("_id");
			}
		}
		if (request != null) {
			try {
				return CookieHelper.getInstance().getSigned("oneSessionId", request);
			} catch (RuntimeException e) {
				return null;
			}
		}
		return null;
	}

	/**
	 * Description de l'assistance en cours, ou {@code null}. Jamais utilisée pour autoriser.
	 *
	 * <p>Les attributs de session ne se posent qu'en texte : la description est donc stockée
	 * encodée, et relue ici. On accepte aussi la forme objet, qu'une session régénérée pourrait
	 * porter.</p>
	 */
	public static JsonObject infos(final JsonObject session) {
		if (session == null) {
			return null;
		}
		final JsonObject cache = session.getJsonObject("cache");
		final Object raw = cache != null && cache.getValue(SESSION_FIELD) != null
				? cache.getValue(SESSION_FIELD)
				: session.getValue(SESSION_FIELD);
		if (raw instanceof JsonObject) {
			return (JsonObject) raw;
		}
		if (raw instanceof String) {
			try {
				return new JsonObject((String) raw);
			} catch (RuntimeException e) {
				return null;
			}
		}
		return null;
	}

	public static boolean isImpersonated(final HttpServerRequest request, final JsonObject session) {
		return isImpersonationSessionId(sessionId(request, session)) || infos(session) != null;
	}

	/**
	 * Applique les règles du mode assistance et répond lui-même en cas de refus.
	 *
	 * @return {@code true} si la requête a été refusée et la réponse déjà écrite — l'appelant
	 * doit alors s'arrêter là, comme pour un changement de mot de passe forcé.
	 */
	public static boolean checkAndDeny(final HttpServerRequest request, final JsonObject session) {
		final String sessionId = sessionId(request, session);
		final boolean marked = isImpersonationSessionId(sessionId);
		if (!marked && infos(session) == null) {
			return false;
		}

		request.response().putHeader(HEADER, marked ? sessionId : "unknown");

		final String path = request.path();
		if (ALWAYS_ALLOWED.contains(path)) {
			return false;
		}

		// Une session marquée dont l'identifiant est illisible est traitée comme expirée :
		// le doute profite à l'utilisateur assisté, pas à l'administrateur.
		final long expiresAt = marked ? expiresAt(sessionId) : -1L;
		if (expiresAt < 0L || System.currentTimeMillis() > expiresAt) {
			Renders.forbidden(request, "impersonation.expired");
			return true;
		}

		final HttpMethod method = request.method();
		if (!HttpMethod.GET.equals(method) && !HttpMethod.HEAD.equals(method)
				&& !HttpMethod.OPTIONS.equals(method)) {
			Renders.forbidden(request, "impersonation.readonly");
			return true;
		}

		for (final Pattern forbidden : FORBIDDEN_PATHS) {
			if (forbidden.matcher(path).matches()) {
				Renders.forbidden(request, "impersonation.forbidden.route");
				return true;
			}
		}
		return false;
	}
}
