/*
 * Revue des accès (recertification), réservée au super administrateur :
 *
 *   GET /directory/pim/privileged-access?expiringWithinDays=<n>
 *       Fonctions d'administration attribuées : titulaire, périmètre, qui l'a accordée, quand,
 *       pourquoi, échéance, dernière connexion. expiringWithinDays filtre les échéances proches ;
 *       "permanent=true" ne garde que les attributions sans échéance.
 *   GET /directory/pim/dormant-accounts?days=<n>&adminsOnly=<bool>
 *       Comptes actifs sans connexion depuis n jours (180 par défaut), jamais connectés compris.
 *
 * Fonctions concernées : "pim.privileged-functions" (défaut : SUPER_ADMIN, ADMIN_LOCAL).
 */
package org.entcore.directory.controllers;

import fr.wseduc.rs.Get;
import fr.wseduc.security.ActionType;
import fr.wseduc.security.MfaProtected;
import fr.wseduc.security.SecuredAction;
import fr.wseduc.webutils.http.BaseController;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.entcore.common.http.filter.ResourceFilter;
import org.entcore.common.http.filter.SuperAdminFilter;
import org.entcore.common.neo4j.Neo4j;
import org.joda.time.DateTime;

import java.util.HashSet;
import java.util.Set;

import static org.entcore.common.http.response.DefaultResponseHandler.arrayResponseHandler;
import static org.entcore.common.neo4j.Neo4jResult.validResultHandler;

public class PimController extends BaseController {

    private static final int MAX_ROWS = 5000;

    private JsonArray privilegedFunctions() {
        return config.getJsonObject("pim", new JsonObject())
                .getJsonArray("privileged-functions", new JsonArray().add("SUPER_ADMIN").add("ADMIN_LOCAL"));
    }

    @Get("/pim/privileged-access")
    @SecuredAction(value = "", type = ActionType.RESOURCE)
    @ResourceFilter(SuperAdminFilter.class)
    @MfaProtected()
    public void privilegedAccess(final HttpServerRequest request) {
        final JsonObject params = new JsonObject().put("functions", privilegedFunctions()).put("limit", MAX_ROWS);
        String filter = "";
        final Integer within = intParam(request, "expiringWithinDays");
        if (within != null) {
            filter = "AND rf.validUntil IS NOT NULL AND rf.validUntil <= {horizon} ";
            params.put("horizon", System.currentTimeMillis() + within * 24L * 3600 * 1000);
        } else if ("true".equals(request.params().get("permanent"))) {
            filter = "AND rf.validUntil IS NULL ";
        }
        final String query =
                "MATCH (u:User)-[rf:HAS_FUNCTION]->(f:Function) " +
                "WHERE f.externalId IN {functions} " + filter +
                "OPTIONAL MATCH (s:Structure) WHERE s.id IN coalesce(rf.scope, []) " +
                "OPTIONAL MATCH (g:User {id: rf.grantedBy}) " +
                // Agrégation dans un WITH : après un RETURN agrégé, ORDER BY ne voit plus u.
                "WITH u, rf, f, g, collect(DISTINCT {id: s.id, name: s.name}) AS scope " +
                "RETURN u.id AS userId, u.login AS login, u.displayName AS displayName, u.blocked AS blocked, " +
                "u.lastLogin AS lastLogin, f.externalId AS function, scope, " +
                "rf.grantedBy AS grantedBy, g.displayName AS grantedByName, rf.grantedAt AS grantedAt, " +
                "rf.reason AS reason, rf.validUntil AS validUntil " +
                "ORDER BY coalesce(rf.validUntil, 9223372036854775807), u.lastName, u.firstName " +
                "LIMIT {limit}";
        Neo4j.getInstance().execute(query, params, validResultHandler(arrayResponseHandler(request)));
    }

    @Get("/pim/dormant-accounts")
    @SecuredAction(value = "", type = ActionType.RESOURCE)
    @ResourceFilter(SuperAdminFilter.class)
    @MfaProtected()
    public void dormantAccounts(final HttpServerRequest request) {
        final Integer days = intParam(request, "days");
        final String cutoff = DateTime.now().minusDays(days == null || days < 1 ? 180 : days).toString();
        final boolean adminsOnly = "true".equals(request.params().get("adminsOnly"));
        final JsonObject params = new JsonObject().put("cutoff", cutoff)
                .put("functions", privilegedFunctions()).put("limit", MAX_ROWS);
        // Presque tous les comptes sont « dormants » (élèves importés jamais connectés) : agréger
        // sur l'ensemble des User matérialise des centaines de milliers de lignes et fait tomber
        // Neo4j en OutOfMemoryError (prod, 27/10/2026). D'où deux requêtes bornées : les titulaires
        // d'une fonction, atteints par les quelques nœuds Function, puis les autres comptes en
        // top-k (ORDER BY + LIMIT sans agrégat, mémoire limitée à {limit} lignes).
        final String functionHolders =
                "MATCH (:Function)<-[:HAS_FUNCTION]-(u:User) WHERE " + DORMANT +
                "WITH DISTINCT u " +
                "OPTIONAL MATCH (u)-[:HAS_FUNCTION]->(f:Function) WHERE f.externalId IN {functions} " +
                "WITH u, collect(DISTINCT f.externalId) AS privilegedFunctions " +
                "RETURN " + DORMANT_COLUMNS + ", privilegedFunctions " +
                "ORDER BY size(privilegedFunctions) DESC, coalesce(u.lastLogin, '') " +
                "LIMIT {limit}";
        Neo4j.getInstance().execute(functionHolders, params, validResultHandler(holders -> {
            if (holders.isLeft()) {
                renderError(request, new JsonObject().put("error", holders.left().getValue()));
                return;
            }
            final JsonArray rows = new JsonArray();
            final Set<String> seen = new HashSet<>();
            for (Object o : holders.right().getValue()) {
                final JsonObject row = (JsonObject) o;
                if (adminsOnly || !row.getJsonArray("privilegedFunctions", new JsonArray()).isEmpty()) {
                    rows.add(row);
                    seen.add(row.getString("userId"));
                }
            }
            if (adminsOnly || rows.size() >= MAX_ROWS) {
                renderJson(request, rows);
                return;
            }
            final String others =
                    "MATCH (u:User) WHERE " + DORMANT +
                    "RETURN " + DORMANT_COLUMNS + ", [] AS privilegedFunctions " +
                    "ORDER BY coalesce(u.lastLogin, '') " +
                    "LIMIT {limit}";
            Neo4j.getInstance().execute(others, params, validResultHandler(rest -> {
                if (rest.isLeft()) {
                    renderError(request, new JsonObject().put("error", rest.left().getValue()));
                    return;
                }
                for (Object o : rest.right().getValue()) {
                    if (rows.size() >= MAX_ROWS) break;
                    final JsonObject row = (JsonObject) o;
                    if (seen.add(row.getString("userId"))) rows.add(row);
                }
                renderJson(request, rows);
            }));
        }));
    }

    private static final String DORMANT =
            "NOT HAS(u.deleteDate) AND coalesce(u.blocked, false) = false " +
            "AND ((u.lastLogin IS NOT NULL AND u.lastLogin < {cutoff}) " +
            "  OR (u.lastLogin IS NULL AND u.created IS NOT NULL AND u.created < {cutoff})) ";

    private static final String DORMANT_COLUMNS =
            "u.id AS userId, u.login AS login, u.displayName AS displayName, head(u.profiles) AS profile, " +
            "u.source AS source, u.lastLogin AS lastLogin, u.created AS created, " +
            "u.activationCode IS NULL AS activated";

    private static Integer intParam(HttpServerRequest request, String name) {
        try {
            final String v = request.params().get(name);
            return v == null ? null : Integer.valueOf(v);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
