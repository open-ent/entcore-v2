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

import fr.wseduc.webutils.http.Renders;
import fr.wseduc.webutils.request.CookieHelper;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;

import java.util.UUID;

import static fr.wseduc.webutils.Utils.isEmpty;
import static fr.wseduc.webutils.Utils.isNotEmpty;

/**
 * Décrit l'appareil à l'origine d'une connexion : adresse IP, navigateur et identifiant
 * d'appareil. Ces informations sont recopiées dans les métadonnées de session pour que
 * l'utilisateur puisse reconnaître, depuis son profil, les appareils connectés à son compte
 * et fermer ceux qu'il ne reconnaît pas.
 */
public final class ClientDevice {

    private static final Logger log = LoggerFactory.getLogger(ClientDevice.class);

    /** Cookie signé portant l'identifiant d'appareil. */
    public static final String DEVICE_ID_COOKIE = "oneDeviceId";

    /**
     * Durée de vie du cookie d'appareil, en secondes : un an. Il doit survivre à la session,
     * sans quoi un appareil de confiance redeviendrait inconnu à chaque déconnexion.
     */
    private static final long DEVICE_ID_COOKIE_TIMEOUT = 3600L * 24 * 365;

    private ClientDevice() {
    }

    /**
     * Construit la description de l'appareil à joindre à la création de session.
     * Aucun champ n'est obligatoire : une connexion reste possible même si l'IP, le
     * User-Agent ou le cookie sont indisponibles.
     *
     * @param request requête de connexion, ou {@code null} pour un appelant sans requête HTTP
     */
    public static JsonObject infos(final HttpServerRequest request) {
        final JsonObject infos = new JsonObject();
        if (request == null) {
            return infos;
        }
        final String ip = ip(request);
        if (isNotEmpty(ip)) {
            infos.put("ip", ip);
        }
        final String ua = request.headers().get("User-Agent");
        if (isNotEmpty(ua)) {
            infos.put("ua", ua);
        }
        final String deviceId = issueDeviceId(request);
        if (isNotEmpty(deviceId)) {
            infos.put("deviceId", deviceId);
        }
        return infos;
    }

    /**
     * Description de l'appareil sans identifiant persistant, pour les clients qui ne portent
     * pas de cookie — l'application mobile s'authentifie par jeton. L'IP et le navigateur
     * restent affichables, seul le regroupement par appareil est perdu.
     */
    public static JsonObject infosWithoutCookie(final HttpServerRequest request) {
        final JsonObject infos = infos(request);
        infos.remove("deviceId");
        return infos;
    }

    /**
     * Identifiant d'appareil déjà connu, sans en créer ni poser de cookie. Sert à reconnaître
     * « cet appareil » sur une requête qui n'ouvre pas de session.
     *
     * @return l'identifiant, ou {@code null} si l'appareil n'en porte pas
     */
    public static String currentDeviceId(final HttpServerRequest request) {
        if (request == null) {
            return null;
        }
        return CookieHelper.getInstance().getSigned(DEVICE_ID_COOKIE, request);
    }

    /**
     * Identifiant stable de l'appareil : lu depuis le cookie signé, ou créé s'il n'existe pas
     * encore. Le cookie est reposé à chaque connexion pour repousser son expiration.
     *
     * <p>On ne dérive délibérément pas cet identifiant de l'IP ni du User-Agent : la première
     * change en changeant de réseau, le second à chaque montée de version du navigateur, et
     * l'appareil ne serait alors jamais reconnu deux fois de suite.</p>
     */
    private static String issueDeviceId(final HttpServerRequest request) {
        String deviceId = currentDeviceId(request);
        if (isEmpty(deviceId)) {
            deviceId = UUID.randomUUID().toString();
        }
        try {
            CookieHelper.getInstance().setSigned(
                    DEVICE_ID_COOKIE, deviceId, DEVICE_ID_COOKIE_TIMEOUT, request, true);
        } catch (Exception e) {
            // Réponse déjà envoyée ou cookies indisponibles : on renonce au suivi d'appareil
            // plutôt qu'à la connexion.
            log.debug("Unable to set device id cookie", e);
        }
        return deviceId;
    }

    /**
     * Adresse IP de l'appelant. {@link Renders#getIp(HttpServerRequest)} lève une
     * {@code NullPointerException} quand {@code remoteAddress()} est null (connexion déjà
     * fermée) : l'absence d'IP ne doit pas faire échouer la connexion.
     */
    private static String ip(final HttpServerRequest request) {
        try {
            return Renders.getIp(request);
        } catch (Exception e) {
            log.debug("Unable to resolve client ip", e);
            return null;
        }
    }

}
