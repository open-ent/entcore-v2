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
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.entcore.common.user.UserInfos;

import java.util.List;

/**
 * Sauvegarde d'un établissement : entcore n'exporte que par compte (chaque service d'export
 * filtre sur `owner`/`author` = l'utilisateur exporté), il n'existe donc aucune notion d'export
 * « application entière » ni « établissement ». Ce service simule un tel export en lançant, pour
 * chaque compte d'un groupe, l'export personnel standard (même mécanisme que "Mes données"), et
 * assemble les archives individuelles produites en un seul lot téléchargeable.
 *
 * Le lot n'est PAS une archive personnelle : il contient un sous-dossier par compte du groupe.
 * Chaque sous-dossier reste toutefois un export personnel complet et autonome (son propre
 * `Manifest.json`, sa propre signature) : il peut être re-zippé seul et réimporté via
 * `/archive/import` exactement comme un export "Mes données" ordinaire.
 */
public interface StructureExportService {

	/** Préfixe distinguant un export de lot d'un export personnel sur le bus `entcore.export`. */
	String STRUCT_PREFIX = "struct-";

	static boolean isBatchExportId(String exportId) {
		return exportId != null && exportId.startsWith(STRUCT_PREFIX);
	}

	/**
	 * Lance l'export de tous les comptes du groupe {@code groupId}, un par un.
	 *
	 * @return l'identifiant du lot (batchId), à interroger via {@link #status(String)}. La
	 * future échoue synchroniquement si le groupe est introuvable, vide, ou dépasse la taille
	 * maximale d'un lot (`max-users-per-batch`) — avant que quoi que ce soit ne soit écrit sur
	 * disque.
	 */
	Future<String> launch(UserInfos requester, String structureId, String groupId, JsonArray apps,
						  boolean exportDocuments, boolean exportSharedResources, String locale, String host);

	/**
	 * État courant du lot : statut global (`running`/`completed`/`error`), effectif attendu et
	 * traité, comptes en erreur. {@code null} si {@code batchId} est inconnu.
	 */
	Future<JsonObject> status(String batchId);

	/** Traite la fin d'export d'UNE application pour UN compte du lot (réponse du bus `entcore.export`). */
	void onAppExportDone(String exportId, String status, String app);

	/** Supprime l'archive du lot (storage + état de suivi). N'affecte aucun export personnel. */
	Future<Void> deleteBatch(String batchId);

	/** Supervision : tous les lots suivis, tous super-administrateurs confondus. */
	Future<List<JsonObject>> getAllBatchesStatus();

	/** Purge les lots dont le démarrage remonte à plus de {@code maxAgeMs} et jamais terminés. */
	Future<Void> purgeStuckBatches(long maxAgeMs);
}
