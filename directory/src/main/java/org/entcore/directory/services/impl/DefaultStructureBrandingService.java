/*
 * Copyright © "Open Digital Education", 2014
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
 */

package org.entcore.directory.services.impl;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.entcore.common.neo4j.Neo4j;
import org.entcore.directory.services.StructureBrandingService;

import java.util.Arrays;
import java.util.List;

/**
 * Neo4j-backed {@link StructureBrandingService}.
 *
 * @see StructureBrandingService for why this lives in one place rather than in each caller.
 */
public class DefaultStructureBrandingService implements StructureBrandingService {

	/**
	 * API name -> Structure property. Mirrors {@code BRANDING_FIELDS} of
	 * {@code StructureController}, which is the writing side of the same data.
	 */
	private static final List<String> FIELDS = Arrays.asList(
			"primaryColor", "accentColor",
			"logo", "entete", "cachet", "signature",
			"cachetPourtour", "cachetMention",
			"signataireCivilite", "signataireNom", "signatairePrenom",
			"signataireFonction", "signataireLibelle");

	/** Fields holding a workspace document id, which the callers turn into a URL. */
	private static final List<String> DOCUMENT_FIELDS = Arrays.asList("logo", "entete", "cachet", "signature");

	private final Neo4j neo4j;

	public DefaultStructureBrandingService(Neo4j neo4j) {
		this.neo4j = neo4j;
	}

	private static String returnClause(String alias) {
		final StringBuilder sb = new StringBuilder();
		for (String field : FIELDS) {
			if (sb.length() > 0) sb.append(", ");
			sb.append(alias).append('.').append(PROPERTY_PREFIX)
					.append(Character.toUpperCase(field.charAt(0))).append(field.substring(1))
					.append(" as ").append(field);
		}
		sb.append(", ").append(alias).append(".id as structureId")
				.append(", ").append(alias).append(".name as structureName")
				.append(", ").append(alias).append(".type as structureType")
				.append(", ").append(alias).append(".city as structureCity");
		return sb.toString();
	}

	@Override
	public Future<JsonObject> getForStructure(String structureId) {
		if (structureId == null || structureId.isEmpty()) {
			return Future.succeededFuture(new JsonObject());
		}
		final String query = "MATCH (s:Structure {id: {id}}) RETURN " + returnClause("s");
		return querySingle(query, new JsonObject().put("id", structureId));
	}

	@Override
	public Future<JsonObject> getForUser(String userId) {
		if (userId == null || userId.isEmpty()) {
			return Future.succeededFuture(new JsonObject());
		}
		// Le tri place d'abord les structures qui définissent effectivement un branding, puis
		// départage par nom. Sans le second critère, un enseignant partagé entre deux collèges
		// pourrait recevoir un en-tête différent d'une génération à l'autre.
		final String query =
				"MATCH (u:User {id: {id}})-[:IN]->(:ProfileGroup)-[:DEPENDS]->(s:Structure) " +
				"WITH DISTINCT s, " +
				"  CASE WHEN s." + PROPERTY_PREFIX + "Logo IS NOT NULL " +
				"         OR s." + PROPERTY_PREFIX + "Cachet IS NOT NULL " +
				"         OR s." + PROPERTY_PREFIX + "Entete IS NOT NULL " +
				"         OR s." + PROPERTY_PREFIX + "PrimaryColor IS NOT NULL " +
				"       THEN 0 ELSE 1 END as rank " +
				"ORDER BY rank, s.name " +
				"LIMIT 1 " +
				"RETURN " + returnClause("s");
		return querySingle(query, new JsonObject().put("id", userId));
	}

	/**
	 * Runs a query expected to return at most one row.
	 * <p>
	 * Any failure — unknown structure, Neo4j hiccup — resolves to an empty object rather than a
	 * failed future. Branding is decoration layered on top of a theme that already works: losing
	 * it must never be what stops a mass-mailing or a page from rendering.
	 */
	private Future<JsonObject> querySingle(String query, JsonObject params) {
		final Promise<JsonObject> promise = Promise.promise();
		neo4j.execute(query, params, message -> {
			final JsonObject body = message.body();
			if (!"ok".equals(body.getString("status"))) {
				promise.complete(new JsonObject());
				return;
			}
			final JsonArray result = body.getJsonArray("result", new JsonArray());
			if (result.isEmpty()) {
				promise.complete(new JsonObject());
				return;
			}
			promise.complete(clean(result.getJsonObject(0)));
		});
		return promise.future();
	}

	/**
	 * Drops null and empty entries.
	 * <p>
	 * Callers layer branding over the theme by testing presence ({@code {{#branding.logo}}} in a
	 * template, {@code containsKey} in Java). An empty string left in the object would read as
	 * "defined" and would blank out the theme's logo instead of falling back to it.
	 */
	private static JsonObject clean(JsonObject row) {
		final JsonObject out = new JsonObject();
		for (String field : row.fieldNames()) {
			final Object value = row.getValue(field);
			if (value instanceof String && ((String) value).trim().isEmpty()) continue;
			if (value == null) continue;
			out.put(field, value);
		}
		return out;
	}

	/** Whether a field carries a workspace document id (so a caller can build its URL). */
	public static boolean isDocumentField(String field) {
		return DOCUMENT_FIELDS.contains(field);
	}
}
