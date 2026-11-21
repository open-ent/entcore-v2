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
 * Approbations de connexion : une session ouverte sur le web attend qu'on la confirme depuis le
 * téléphone déjà enrôlé.
 *
 * <p>Évite de taper un code à six chiffres sur un clavier quand on a son téléphone en main, et
 * surtout évite d'avoir à le recevoir : rien n'est envoyé, ni par SMS ni par courriel.</p>
 *
 * <h3>Le choix du bon nombre</h3>
 * <p>L'écran web affiche trois nombres, le téléphone demande lequel est le bon. Sans cela,
 * approuver se réduirait à appuyer sur « oui » — et quelqu'un qui détient le mot de passe n'aurait
 * qu'à réessayer jusqu'à ce que la personne, lassée de voir des demandes, finisse par accepter.
 * C'est une fatigue qui se provoque, et c'est ainsi que des comptes tombent. Devoir lire un nombre
 * sur l'écran de la connexion impose de regarder ce que l'on approuve.</p>
 *
 * <h3>Le code prime, la notification n'est qu'un raccourci</h3>
 * <p>Le parcours nominal fonctionne sans aucune notification : l'écran web affiche un code, la
 * personne ouvre son application et le saisit. Une notification poussée ne fait que raccourcir ce
 * chemin quand elle arrive. Un facteur d'authentification ne peut pas dépendre d'un service de
 * notification tiers, que le téléphone peut mettre en veille et que l'instance n'a pas forcément
 * configuré.</p>
 */
public interface DeviceApprovalService {

    String STATE_PENDING = "pending";
    String STATE_APPROVED = "approved";
    String STATE_DENIED = "denied";

    /**
     * Ouvre une demande d'approbation pour la session qui vient de se connecter sur le web.
     *
     * @param sessionId session à marquer une fois l'approbation donnée — figée ici, pour qu'une
     *                  approbation ne puisse jamais valider une autre session que celle affichée
     * @return {@code {approvalId, code, numbers, expiresIn}} ; {@code numbers} est à montrer à
     *         l'écran, et le bon n'y est pas distingué
     */
    Future<JsonObject> open(String userId, String sessionId, String ip, String ua);

    /** État d'une demande, pour que l'écran web sache quand poursuivre. */
    Future<JsonObject> state(String approvalId);

    /**
     * Retrouve une demande en attente à partir du code affiché sur l'écran web.
     *
     * <p>Le code n'identifie qu'une demande déjà ouverte par le compte lui-même : il ne donne
     * aucun pouvoir à qui le connaît sans détenir aussi la clé de l'appareil.</p>
     *
     * @return la demande, ou {@code null} si le code est inconnu, périmé ou déjà tranché
     */
    Future<JsonObject> findPendingByCode(String userId, String code);

    /**
     * Tranche une demande.
     *
     * <p>Un mauvais nombre ne donne pas droit à un nouvel essai : la demande est refusée et
     * détruite. Réessayer reviendrait à offrir une chance sur trois, puis une sur deux.</p>
     *
     * @param chosenNumber nombre choisi par la personne sur son téléphone
     * @return la demande telle qu'elle a été tranchée, avec son {@code state} et son
     *         {@code sessionId} ; échoue si elle est inconnue ou périmée
     */
    Future<JsonObject> decide(String approvalId, String userId, int chosenNumber);

}
