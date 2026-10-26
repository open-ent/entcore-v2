/*
 * Accès annuaire de l'API SCIM 2.0 (/scim/v2).
 *
 * Lectures : directement dans Neo4j (tous les comptes, quelle que soit leur source, pour que l'IAM
 * puisse les rapprocher). Écritures : envoyées au feeder (bus entcore.feeder, action "scim-rest")
 * qui applique le cycle de vie commun (ScimFeeder) et écrit l'audit.
 */
package org.entcore.directory.scim;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.eventbus.DeliveryOptions;
import io.vertx.core.eventbus.EventBus;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.entcore.common.neo4j.Neo4j;

public class ScimV2Service {

    private static final String GROUP_LABELS = "(g:Class OR g:FunctionalGroup OR g:ManualGroup)";

    private static final String USER_PROJECTION =
            "OPTIONAL MATCH (u)-[:IN]->(:ProfileGroup)-[:DEPENDS]->(s:Structure) " +
            "WITH u, collect(DISTINCT {id: s.id, name: s.name}) AS structures " +
            "OPTIONAL MATCH (u)-[:IN]->(fg:Group) WHERE fg:FunctionalGroup OR fg:ManualGroup " +
            "WITH u, structures, collect(DISTINCT {id: fg.id, name: fg.name}) AS directGroups " +
            "OPTIONAL MATCH (u)-[:IN]->(:ProfileGroup)-[:DEPENDS]->(c:Class) " +
            "RETURN u.id AS id, u.login AS login, u.externalId AS externalId, u.scimExternalId AS scimExternalId, " +
            "u.firstName AS firstName, u.lastName AS lastName, u.displayName AS displayName, u.email AS email, " +
            "u.blocked AS blocked, u.deleteDate AS deleteDate, u.activationCode AS activationCode, " +
            "u.profiles AS profiles, u.source AS source, u.birthDate AS birthDate, " +
            "u.created AS created, u.modified AS modified, structures, " +
            "directGroups + collect(DISTINCT {id: c.id, name: c.name}) AS groups ";

    private static final String GROUP_PROJECTION =
            "OPTIONAL MATCH (g)-[:BELONGS|DEPENDS]->(s:Structure) " +
            "WITH g, head(collect(s)) AS s ";
    private static final String GROUP_MEMBERS =
            "OPTIONAL MATCH (m:User)-[:IN]->(g) " +
            "WITH g, s, collect(DISTINCT {id: m.id, displayName: m.displayName}) AS direct " +
            "OPTIONAL MATCH (cm:User)-[:IN]->(:ProfileGroup)-[:DEPENDS]->(g) " +
            "WITH g, s, direct + collect(DISTINCT {id: cm.id, displayName: cm.displayName}) AS members ";
    private static final String GROUP_RETURN =
            "RETURN g.id AS id, g.name AS name, g.externalId AS externalId, g.scimExternalId AS scimExternalId, " +
            "g.source AS source, g.created AS created, g.modified AS modified, " +
            "CASE WHEN g:Class THEN 'class' WHEN g:FunctionalGroup THEN 'functional' ELSE 'manual' END AS type, " +
            "s.id AS structureId, s.name AS structureName";

    private final Neo4j neo4j = Neo4j.getInstance();
    private final EventBus eb;

    public ScimV2Service(EventBus eb) {
        this.eb = eb;
    }

    // ------------------------------------------------------------------------------- Users

    public Future<JsonObject> listUsers(ScimFilter.Clause clause, int startIndex, int count) {
        final JsonObject params = clause.params.copy().put("skip", startIndex - 1).put("limit", count);
        final String page = "MATCH (u:User) WHERE " + clause.where + " " +
                "WITH u ORDER BY u.lastName, u.firstName, u.id SKIP {skip} LIMIT {limit} " + USER_PROJECTION;
        final String total = "MATCH (u:User) WHERE " + clause.where + " RETURN count(u) AS total";
        return page(page, total, params);
    }

    public Future<JsonObject> getUser(String id) {
        return first("MATCH (u:User {id: {id}}) WITH u " + USER_PROJECTION, new JsonObject().put("id", id));
    }

    // ------------------------------------------------------------------------------ Groups

    public Future<JsonObject> listGroups(ScimFilter.Clause clause, int startIndex, int count, boolean withMembers) {
        final JsonObject params = clause.params.copy().put("skip", startIndex - 1).put("limit", count);
        final String page = "MATCH (g:Group) WHERE " + GROUP_LABELS + " AND " + clause.where + " " +
                "WITH g ORDER BY g.name, g.id SKIP {skip} LIMIT {limit} " + GROUP_PROJECTION +
                (withMembers ? GROUP_MEMBERS + GROUP_RETURN + ", members" : GROUP_RETURN);
        final String total = "MATCH (g:Group) WHERE " + GROUP_LABELS + " AND " + clause.where + " RETURN count(g) AS total";
        return page(page, total, params);
    }

    public Future<JsonObject> getGroup(String id, boolean withMembers) {
        return first("MATCH (g:Group {id: {id}}) WHERE " + GROUP_LABELS + " WITH g " + GROUP_PROJECTION +
                (withMembers ? GROUP_MEMBERS + GROUP_RETURN + ", members" : GROUP_RETURN), new JsonObject().put("id", id));
    }

    // ------------------------------------------------------------------------------ écritures

    /**
     * Envoie une opération d'écriture au feeder. Réponse : {status: ok, id} ou
     * {status: error, message} ; les messages "scim.conflict…", "scim.notfound…" et
     * "scim.readonly…" sont traduits en 409, 404 et 403 par le contrôleur.
     */
    public Future<JsonObject> write(String op, JsonObject payload, String client) {
        final Promise<JsonObject> promise = Promise.promise();
        final JsonObject message = new JsonObject().put("action", "scim-rest")
                .put("op", op).put("payload", payload).put("client", client);
        eb.request("entcore.feeder", message, new DeliveryOptions().setSendTimeout(30000L), ar -> {
            if (ar.failed()) {
                promise.fail("feeder.unavailable");
                return;
            }
            final JsonObject body = (JsonObject) ar.result().body();
            if ("ok".equals(body.getString("status"))) promise.complete(body);
            else promise.fail(body.getString("message", "scim.write.error"));
        });
        return promise.future();
    }

    // ------------------------------------------------------------------------------ utilitaires

    private Future<JsonObject> page(String pageQuery, String totalQuery, JsonObject params) {
        return execute(totalQuery, params).compose(totalRows -> {
            final long total = totalRows.isEmpty() ? 0 : totalRows.getJsonObject(0).getLong("total", 0L);
            return execute(pageQuery, params).map(rows -> new JsonObject().put("total", total).put("rows", rows));
        });
    }

    private Future<JsonObject> first(String query, JsonObject params) {
        return execute(query, params).map(rows -> rows.isEmpty() ? null : rows.getJsonObject(0));
    }

    private Future<JsonArray> execute(String query, JsonObject params) {
        final Promise<JsonArray> promise = Promise.promise();
        neo4j.execute(query, params, event -> {
            if ("ok".equals(event.body().getString("status"))) {
                promise.complete(event.body().getJsonArray("result", new JsonArray()));
            } else {
                promise.fail(event.body().getString("message", "neo4j.error"));
            }
        });
        return promise.future();
    }
}
