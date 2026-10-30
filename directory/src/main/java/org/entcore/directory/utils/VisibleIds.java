package org.entcore.directory.utils;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.entcore.common.neo4j.Neo4j;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/**
 * Parmi des identifiants (personnes ou groupes), ceux que l'utilisateur peut voir : ses groupes communiquent avec un
 * groupe qui contient la personne, ou avec le groupe lui-même, ou il a une relation directe avec la personne.
 *
 * <p>Remplace {@code UserUtils.filterFewOrGetAllVisibles} (bibliothèque commune), dont la requête est refusée par ArcadeDB
 * (erreur de syntaxe) : elle rendait toujours « personne n'est visible ».
 */
public final class VisibleIds {

	private static final String QUERY =
			"MATCH (u:User {id:$userId}) " +
			"OPTIONAL MATCH (u)-[:IN]->(:Group)-[:COMMUNIQUE]->(g:Group) " +
			"OPTIONAL MATCH (g)<-[:IN]-(v:User) WHERE v.id IN $ids " +
			"OPTIONAL MATCH (u)-[:COMMUNIQUE_DIRECT]->(d:User) WHERE d.id IN $ids " +
			"WITH COLLECT(DISTINCT v.id) + COLLECT(DISTINCT CASE WHEN g.id IN $ids THEN g.id END) + COLLECT(DISTINCT d.id) AS vs " +
			"UNWIND vs AS id WITH id WHERE id IS NOT NULL RETURN DISTINCT id";

	private VisibleIds() {}

	/** Identifiants visibles par {@code userId} parmi {@code ids} ; l'échec de la requête est propagé. */
	public static Future<Set<String>> among(final String userId, final Collection<String> ids) {
		final Promise<Set<String>> promise = Promise.promise();
		final JsonObject params = new JsonObject().put("userId", userId).put("ids", new JsonArray(new ArrayList<>(ids)));
		Neo4j.getInstance().execute(QUERY, params, message -> {
			final JsonObject body = message.body();
			if (body != null && "ok".equals(body.getString("status"))) {
				final Set<String> visible = new HashSet<>();
				body.getJsonArray("result", new JsonArray()).forEach(o -> visible.add(((JsonObject) o).getString("id")));
				promise.complete(visible);
			} else {
				promise.fail(body == null ? "no.response" : String.valueOf(body.getString("message")));
			}
		});
		return promise.future();
	}
}
