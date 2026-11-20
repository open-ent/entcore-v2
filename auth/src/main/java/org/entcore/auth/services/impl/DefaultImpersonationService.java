/*
 * Copyright © "Open ENT", 2026
 *
 * This program is free software, licensed under the terms of the GNU Affero General Public License
 * as published by the Free Software Foundation, version 3 of the License.
 */

package org.entcore.auth.services.impl;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.entcore.auth.services.ImpersonationService;
import org.entcore.common.neo4j.Neo4j;
import org.entcore.common.neo4j.Neo4jResult;
import org.entcore.common.user.DefaultFunctions;
import org.entcore.common.user.UserInfos;

import java.util.ArrayList;
import java.util.List;

/**
 * Règles d'habilitation du mode assistance.
 *
 * <p>Un super administrateur assiste n'importe quel usager ; un administrateur local, les seuls
 * usagers de son périmètre. Dans les deux cas un compte qui porte lui-même une fonction
 * d'administration est hors de portée : l'assistance sert à voir l'ENT d'un usager, pas à entrer
 * dans celui d'un pair.</p>
 */
public class DefaultImpersonationService implements ImpersonationService {

	private static final String QUERY =
			"MATCH (u:User {id: $userId}) " +
			"OPTIONAL MATCH (u)-[:IN]->(:ProfileGroup)-[:DEPENDS]->(s:Structure) " +
			"OPTIONAL MATCH (u)-[:IN]->(:ProfileGroup)-[:HAS_PROFILE]->(p:Profile) " +
			"OPTIONAL MATCH (u)-[:HAS_FUNCTION]->(f:Function) " +
			"RETURN u.id as id, u.displayName as displayName, u.blocked as blocked, " +
			"collect(distinct s.id) as structures, collect(distinct p.name) as profiles, " +
			"collect(distinct f.externalId) as functions";

	private final Neo4j neo4j = Neo4j.getInstance();

	@Override
	public Future<JsonObject> checkTarget(final UserInfos admin, final String targetUserId) {
		if (admin == null || targetUserId == null || targetUserId.trim().isEmpty()) {
			return Future.failedFuture("impersonation.error.invalid.target");
		}
		if (targetUserId.equals(admin.getUserId())) {
			return Future.failedFuture("impersonation.error.self");
		}

		final Promise<JsonObject> promise = Promise.promise();
		neo4j.execute(QUERY, new JsonObject().put("userId", targetUserId),
				Neo4jResult.validUniqueResultHandler(result -> {
			if (result.isLeft()) {
				promise.fail("impersonation.error.target.unreachable");
				return;
			}
			final JsonObject target = result.right().getValue();
			if (target == null || target.getString("id") == null) {
				promise.fail("impersonation.error.target.notfound");
				return;
			}
			if (Boolean.TRUE.equals(target.getBoolean("blocked"))) {
				promise.fail("impersonation.error.target.blocked");
				return;
			}

			final JsonArray targetFunctions = target.getJsonArray("functions", new JsonArray());
			if (targetFunctions.contains(DefaultFunctions.SUPER_ADMIN)
					|| targetFunctions.contains(DefaultFunctions.ADMIN_LOCAL)
					|| targetFunctions.contains(DefaultFunctions.ADMIN_COLLECTIVITE)) {
				promise.fail("impersonation.error.target.admin");
				return;
			}

			if (!isInScope(admin, target.getJsonArray("structures", new JsonArray()))) {
				promise.fail("impersonation.error.out.of.scope");
				return;
			}

			final JsonArray profiles = target.getJsonArray("profiles", new JsonArray());
			promise.complete(new JsonObject()
					.put("id", target.getString("id"))
					.put("displayName", target.getString("displayName"))
					.put("profile", profiles.isEmpty() ? null : profiles.getString(0))
					.put("structures", target.getJsonArray("structures", new JsonArray())));
		}));
		return promise.future();
	}

	/**
	 * Un super administrateur voit tout le monde. Un administrateur local ne voit que les usagers
	 * rattachés à une structure de son périmètre — périmètre déjà résolu avec l'héritage par
	 * AuthManager au moment de la session.
	 */
	private boolean isInScope(final UserInfos admin, final JsonArray targetStructures) {
		if (admin.getFunctions() == null) {
			return false;
		}
		if (admin.getFunctions().containsKey(DefaultFunctions.SUPER_ADMIN)) {
			return true;
		}
		final UserInfos.Function adminLocal = admin.getFunctions().get(DefaultFunctions.ADMIN_LOCAL);
		if (adminLocal == null || adminLocal.getScope() == null) {
			return false;
		}
		final List<String> scope = new ArrayList<>(adminLocal.getScope());
		for (int i = 0; i < targetStructures.size(); i++) {
			if (scope.contains(targetStructures.getString(i))) {
				return true;
			}
		}
		return false;
	}
}
