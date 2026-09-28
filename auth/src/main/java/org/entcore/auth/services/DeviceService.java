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

package org.entcore.auth.services;

import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;

/**
 * Mémoire des appareils depuis lesquels un utilisateur se connecte.
 *
 * <p>Complète les sessions, qui ne disent que « qui est connecté maintenant » : cette mémoire
 * survit à la déconnexion, ce qui permet de reconnaître un appareil déjà vu, de le marquer
 * de confiance, et d'alerter sur une connexion depuis un appareil ou un réseau inhabituels.</p>
 */
public interface DeviceService {

    /**
     * Enregistre le passage d'un appareil sur le compte et qualifie la connexion.
     *
     * @param userId      utilisateur qui vient de se connecter
     * @param clientInfos description de l'appareil ({@code ip}, {@code ua}, {@code deviceId})
     * @return {@code {deviceId, known, trusted, newIp, firstEver, shouldNotify}} —
     *         {@code shouldNotify} vaut {@code true} quand la connexion mérite une alerte.
     *         Échoue seulement sur erreur de la base : un appareil non identifiable
     *         (mobile, cookies bloqués) renvoie simplement {@code shouldNotify = false}.
     */
    Future<JsonObject> recordSighting(String userId, JsonObject clientInfos);

    /**
     * Appareils connus de l'utilisateur, indexés par {@code deviceId}. Sert à enrichir la
     * liste des sessions ouvertes (appareil de confiance, première fois qu'on l'a vu).
     */
    Future<JsonObject> devicesByIdFor(String userId);

    /**
     * Marque ou démarque un appareil comme étant de confiance.
     *
     * @return {@code true} si l'appareil existait et appartenait bien à cet utilisateur
     */
    Future<Boolean> setTrusted(String userId, String deviceId, boolean trusted);

    /** Mémorise qu'une alerte vient d'être envoyée, pour ne pas la répéter à chaque connexion. */
    Future<Void> markNotified(String userId, String deviceId);

}
