/*
 * Copyright © "Open ENT", 2026
 *
 * This program is free software, licensed under the terms of the GNU Affero General Public License
 * as published by the Free Software Foundation, version 3 of the License.
 */

package org.entcore.auth.services;

import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import org.entcore.common.user.UserInfos;

/**
 * Habilitation du mode assistance : qui a le droit d'ouvrir une session sur le compte de qui.
 */
public interface ImpersonationService {

	/**
	 * Vérifie que {@code admin} peut assister {@code targetUserId}, et décrit la cible.
	 *
	 * @return une description {@code {id, displayName, profile, structures}} si l'assistance est
	 * permise ; un échec portant une clé i18n sinon.
	 */
	Future<JsonObject> checkTarget(UserInfos admin, String targetUserId);
}
