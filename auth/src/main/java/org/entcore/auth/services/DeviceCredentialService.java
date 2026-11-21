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
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * Clés d'appareil enrôlées sur un compte : la partie publique d'une paire dont la privée ne
 * quitte jamais le coffre matériel du téléphone.
 *
 * <p>Volontairement distinct de {@link DeviceService}, qui mémorise les <i>passages</i> d'un
 * appareil. Cette mémoire-là est écrite à chaque connexion par un chemin tolérant aux pannes ;
 * un magasin de clés d'authentification ne doit être écrit qu'à l'enrôlement et à la
 * révocation. Les deux restent reliés par {@code deviceId}.</p>
 *
 * <p>Niveau d'assurance porté par chaque clé :</p>
 * <ul>
 *   <li>{@value #ASSURANCE_PROVEN} — enrôlée avec la preuve d'un facteur déjà possédé
 *       (session ayant franchi le second facteur, code e-mail, ticket d'administrateur) ;</li>
 *   <li>{@value #ASSURANCE_SELF} — enrôlée avec le seul mot de passe. Utilisable pour ouvrir
 *       une session, <b>jamais</b> comme second facteur : une clé posée avec un mot de passe
 *       dérobé transformerait ce vol en deux facteurs.</li>
 * </ul>
 */
public interface DeviceCredentialService {

    String ASSURANCE_PROVEN = "proven";
    String ASSURANCE_SELF = "self";

    /**
     * Enregistre une clé.
     *
     * @param credential document complet, {@code _id} en tête (l'identité dérivée de la clé)
     * @return le document enregistré
     *         ; échoue en {@code device.key.already.enrolled} si cette clé exacte est déjà
     *         connue — ce qui interdit d'enrôler la même clé sur deux comptes
     */
    Future<JsonObject> create(JsonObject credential);

    /**
     * Clé active portant cet identifiant, tous comptes confondus : la connexion par appareil
     * présente une clé avant que l'on sache de quel compte il s'agit.
     *
     * @return le document, ou {@code null} si la clé est inconnue ou révoquée
     */
    Future<JsonObject> activeById(String credentialId);

    /** Clés actives d'un compte, de la plus récemment enrôlée à la plus ancienne. */
    Future<JsonArray> listActiveFor(String userId);

    /**
     * Révoque une clé du compte indiqué. Le document est conservé, marqué révoqué : l'historique
     * d'un retrait de clé a autant de valeur que celui d'un ajout.
     *
     * @param revokedBy identifiant de qui révoque — le titulaire lui-même, ou un administrateur
     * @return {@code true} si une clé active a bien été révoquée
     */
    Future<Boolean> revoke(String userId, String credentialId, String revokedBy);

    /**
     * Révoque toutes les clés actives d'un compte sur un appareil donné. Appelé au moment
     * d'enrôler une nouvelle clé sur cet appareil : l'ancienne n'existe déjà plus côté
     * téléphone, la laisser active en base en ferait un facteur fantôme — comptabilisé, mais
     * impossible à utiliser.
     *
     * @return le nombre de clés révoquées
     */
    Future<Integer> revokeForDevice(String userId, String deviceId, String revokedBy);

    /**
     * Note qu'une clé vient de servir, pour afficher sa dernière utilisation et repérer les
     * dormantes. Remet aussi le compteur d'échecs à zéro : un verrou ne doit pas s'accumuler
     * sur des échecs anciens séparés par des usages normaux.
     */
    Future<Void> markUsed(String credentialId);

    /**
     * Note un échec de présentation et verrouille la clé quand ils s'enchaînent.
     *
     * <p>Le défi à usage unique interdit déjà le rejeu, mais pas l'acharnement : sans compteur,
     * une clé volée mais inutilisable servirait de cible à des tentatives sans fin. Le verrou
     * est temporaire, et porte sur la clé — pas sur le compte, qu'un tiers pourrait sinon
     * bloquer en présentant n'importe quoi.</p>
     *
     * @return l'instant jusqu'auquel la clé est verrouillée, ou {@code null} si elle ne l'est pas
     */
    Future<Long> recordFailure(String credentialId);

    /** Vrai si la clé est actuellement verrouillée à la suite d'échecs répétés. */
    static boolean isLocked(JsonObject credential) {
        if (credential == null) {
            return false;
        }
        final Long lockedUntil = credential.getLong("lockedUntil");
        return lockedUntil != null && lockedUntil > System.currentTimeMillis();
    }

}
