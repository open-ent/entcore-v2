/* Copyright © "Open Digital Education", 2025
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

package org.entcore.auth.security;

import fr.wseduc.webutils.request.CookieHelper;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.JsonObject;

import java.util.UUID;

/**
 * Cookies posés à l'ouverture d'une session.
 *
 * <p>Extrait de {@code AuthController} pour que les connexions par appareil ouvrent une session
 * <b>exactement</b> comme celles par mot de passe : même cookie signé, même durée, même jeton
 * anti-rejeu de formulaire. Une seconde implémentation finirait par diverger, et c'est en
 * divergeant qu'un chemin d'authentification devient le maillon faible — durée de vie plus
 * longue ici, déconnexion qui ne nettoie pas tout là.</p>
 */
public final class SessionCookies {

    /** Durée d'un cookie « se souvenir de moi » : un an. */
    private static final long REMEMBER_ME_TIMEOUT = 3600L * 24 * 365;

    private SessionCookies() {
    }

    /**
     * Pose les cookies de session sur la réponse en cours.
     *
     * @param rememberMe session longue demandée par l'utilisateur
     * @param config     configuration du module, pour {@code cookie_timeout} et {@code xsrfOnAuth}
     */
    public static void open(final HttpServerRequest request, final String sessionId,
                            final boolean rememberMe, final JsonObject config) {
        final long timeout = rememberMe
                ? REMEMBER_ME_TIMEOUT
                : config.getLong("cookie_timeout", Long.MIN_VALUE);
        CookieHelper.getInstance().setSigned("oneSessionId", sessionId, timeout, request);
        CookieHelper.set("authenticated", "true", timeout, request);
        // Jeton anti-rejeu renouvelé dès l'ouverture, pour qu'une page mise en cache ne le fige pas.
        if (config.getBoolean("xsrfOnAuth", true)) {
            CookieHelper.set("XSRF-TOKEN", UUID.randomUUID().toString(), timeout, request);
        }
    }

}
