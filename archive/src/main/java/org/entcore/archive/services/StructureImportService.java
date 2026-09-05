/* Copyright © "Open Digital Education", 2014
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

package org.entcore.archive.services;

import io.vertx.core.Future;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.JsonObject;
import org.entcore.common.user.UserInfos;

import java.util.List;

/**
 * Restauration groupée : réimporte, en une opération, le lot produit par
 * {@link StructureExportService}.
 *
 * <p>Un lot n'est pas une archive personnelle : c'est un {@code .zip} contenant un sous-dossier
 * par compte, chacun étant un export personnel autonome, plus un {@code Batch-Manifest.json} qui
 * dit quel dossier appartient à quel compte. Restaurer le lot revient donc à rejouer, compte par
 * compte, l'import personnel standard — mais **au nom de chaque compte**, et non de la personne
 * qui dépose le fichier. C'est toute la différence avec {@code /archive/import}, qui restaure
 * dans le compte de l'utilisateur connecté.
 *
 * <p><b>Cette dissociation est la seule chose dangereuse ici</b> : se tromper de destinataire
 * verserait les documents d'une personne dans le compte d'une autre. Trois vérifications
 * indépendantes encadrent donc chaque dossier avant le moindre import, et une seule qui échoue
 * fait renoncer au lot <b>entier</b> — voir {@link #analyze(String)} :
 *
 * <ol>
 *   <li>le {@code Batch-Manifest.json} déclare un identifiant de compte pour le dossier ;</li>
 *   <li>le <b>nom du dossier</b> se termine par ce même identifiant (l'export le suffixe ainsi,
 *       pour lever les homonymies) — deux sources qu'il faudrait falsifier de concert ;</li>
 *   <li>le compte existe encore dans l'annuaire, et son identifiant de connexion est celui
 *       attendu.</li>
 * </ol>
 *
 * <p>L'import lui-même est <b>additif</b>, comme celui d'une archive personnelle : il ajoute des
 * ressources, il n'efface pas l'existant. Restaurer deux fois duplique, cela ne détruit rien.
 */
public interface StructureImportService {

	/**
	 * Reçoit le {@code .zip} d'un lot et le décompresse dans un espace de travail.
	 *
	 * @return l'identifiant de restauration, à passer à {@link #analyze(String)}
	 */
	void uploadBatch(HttpServerRequest request, UserInfos requester,
					 io.vertx.core.Handler<fr.wseduc.webutils.Either<String, String>> handler);

	/**
	 * Inventorie le lot sans rien restaurer : un état par compte, et le verdict global.
	 *
	 * <p>Le lot n'est déclaré restaurable que si <b>tous</b> ses comptes le sont. Restaurer
	 * partiellement un lot laisserait un établissement dans un état que personne ne saurait
	 * décrire : mieux vaut refuser, dire quels comptes posent problème, et laisser corriger.
	 */
	Future<JsonObject> analyze(String restoreId);

	/**
	 * Lance la restauration des comptes du lot, un par un.
	 *
	 * <p>Échoue immédiatement si l'analyse n'a pas conclu que le lot est restaurable, ou si une
	 * restauration est déjà en cours pour ce lot.
	 */
	Future<Void> launch(String restoreId, UserInfos requester, String locale, String host);

	/** État courant : comptes attendus, traités, en erreur, statut global. */
	Future<JsonObject> status(String restoreId);

	/** Supervision : toutes les restaurations suivies. */
	Future<List<JsonObject>> getAllRestoresStatus();

	/** Supprime l'espace de travail d'une restauration (fichiers + état de suivi). */
	Future<Void> delete(String restoreId);

	/** Purge les restaurations démarrées il y a plus de {@code maxAgeMs} et jamais terminées. */
	Future<Void> purgeStuckRestores(long maxAgeMs);
}
