/*
 * Revue des accès (recertification), réservée au super administrateur :
 *
 *   GET /directory/pim/privileged-access?expiringWithinDays=<n>
 *       Fonctions d'administration attribuées : titulaire, périmètre, qui l'a accordée, quand,
 *       pourquoi, échéance, dernière connexion. expiringWithinDays filtre les échéances proches ;
 *       "permanent=true" ne garde que les attributions sans échéance.
 *   GET /directory/pim/dormant-accounts?days=<n>&connection=inactive|never&profile=<p>
 *         &structureId=<id>&adminsOnly=<bool>&skip=<n>&limit=<n>
 *       Comptes actifs sans connexion depuis n jours (180 par défaut), une page à la fois :
 *       "inactive" (défaut) = déjà connectés, mais plus depuis n jours, les plus anciens d'abord ;
 *       "never" = jamais connectés, créés depuis plus de n jours. profile : Student, Teacher,
 *       Relative, Personnel ou Guest. limit : 50 par défaut, 200 au plus ; skip : 10 000 au plus.
 *       Le client demande limit + 1 lignes pour savoir s'il existe une page suivante.
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

import java.util.Arrays;
import java.util.List;

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
        final boolean never = "never".equals(request.params().get("connection"));
        final String profile = request.params().get("profile");
        final String structureId = request.params().get("structureId");
        final boolean adminsOnly = "true".equals(request.params().get("adminsOnly"));
        final Integer skip = intParam(request, "skip");
        final Integer limit = intParam(request, "limit");
        final JsonObject params = new JsonObject().put("cutoff", cutoff).put("functions", privilegedFunctions())
                .put("skip", skip == null ? 0 : Math.max(0, Math.min(skip, MAX_DORMANT_SKIP)))
                .put("limit", limit == null ? 50 : Math.max(1, Math.min(limit, MAX_DORMANT_PAGE)));

        // Presque tous les comptes sont dormants (élèves importés jamais connectés) : jamais
        // d'agrégat sur l'ensemble des User, il a fait tomber Neo4j en OutOfMemoryError (prod,
        // 27/10/2026). On part du plus petit ensemble disponible (établissement, titulaires d'une
        // fonction, index lastLogin), on trie en top-k (ORDER BY + LIMIT) et l'on ne lit les
        // fonctions que des lignes de la page.
        final StringBuilder query = new StringBuilder();
        String where = "WHERE ";
        if (structureId != null && !structureId.isEmpty()) {
            query.append("MATCH (:Structure {id: {structureId}})<-[:DEPENDS]-(:ProfileGroup)<-[:IN]-(u:User) WITH DISTINCT u ");
            params.put("structureId", structureId);
            if (adminsOnly) where += "(u)-[:HAS_FUNCTION]->(:Function) AND ";
        } else if (adminsOnly) {
            query.append("MATCH (:Function)<-[:HAS_FUNCTION]-(u:User) WITH DISTINCT u ");
        } else {
            query.append("MATCH (u:User) ");
        }
        query.append(where).append(never
                ? "u.lastLogin IS NULL AND u.created < {cutoff} "
                // Comparaison seule sur lastLogin : Neo4j la sert par l'index (neo4j/001-user-lastlogin-index.cypher).
                : "u.lastLogin < {cutoff} ");
        query.append("AND NOT u.deleteDate IS NOT NULL AND coalesce(u.blocked, false) = false ");
        if (PROFILES.contains(profile)) {
            query.append("AND {profile} IN u.profiles ");
            params.put("profile", profile);
        }
        final String order = never ? "u.created" : "u.lastLogin";
        query.append("WITH u ORDER BY ").append(order).append(" SKIP {skip} LIMIT {limit} ")
                .append("OPTIONAL MATCH (u)-[:HAS_FUNCTION]->(f:Function) WHERE f.externalId IN {functions} ")
                .append("WITH u, collect(DISTINCT f.externalId) AS privilegedFunctions ")
                .append("RETURN ").append(DORMANT_COLUMNS).append(", privilegedFunctions ")
                .append("ORDER BY ").append(order);
        Neo4j.getInstance().execute(query.toString(), params, validResultHandler(arrayResponseHandler(request)));
    }

    private static final int MAX_DORMANT_PAGE = 201;
    private static final int MAX_DORMANT_SKIP = 10000;
    private static final List<String> PROFILES = Arrays.asList("Student", "Teacher", "Relative", "Personnel", "Guest");

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
