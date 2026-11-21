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
 * Tickets d'enrôlement d'appareil remis de la main à la main par un administrateur.
 *
 * <p>C'est la réponse aux comptes qui n'ont ni adresse e-mail ni numéro — les élèves de
 * primaire au premier chef. Pour eux, les deux preuves habituelles sont hors de portée : la
 * session n'a jamais franchi de second facteur, et aucun code ne peut leur être envoyé. Sans ce
 * ticket, leur appareil ne pourrait jamais dépasser le niveau « enrôlé avec le mot de passe »,
 * donc jamais tenir lieu de second facteur.</p>
 *
 * <p>Le canal est celui, déjà en usage dans les écoles, par lequel circulent les codes
 * d'activation et les réinitialisations de mot de passe : l'adulte remet un code à l'élève, qui
 * s'en sert devant lui. L'administrateur n'enrôle jamais à la place de quelqu'un ; il ouvre une
 * fenêtre que la personne doit franchir avec son téléphone en main.</p>
 */
public interface DeviceTicketService {

    /**
     * Délivre un ticket pour un compte et rend le code en clair — la seule et unique fois où il
     * sera lisible.
     *
     * @param issuedBy administrateur qui en fait la demande ; un contournement
     *                 d'authentification doit pouvoir être attribué nommément
     * @return {@code {code, expiresAt, expiresIn}}
     */
    Future<JsonObject> issue(String userId, String issuedBy);

    /**
     * Vérifie et consomme le ticket d'un compte. Le ticket est retiré même lorsque le code est
     * faux, ce qui interdit de le deviner par essais successifs.
     *
     * @return {@code true} si le code était le bon et le ticket encore valable
     */
    Future<Boolean> consume(String userId, String code);

}
