package org.entcore.interoperability.packs;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.entcore.common.neo4j.Neo4j;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lectures d'annuaire du moteur des packs. Requêtes écrites pour les deux moteurs de graphe
 * (Neo4j et ArcadeDB) : motifs simples, paramètres nommés, aucune fonction propre à l'un d'eux.
 */
public class PackDirectory {

    private final Neo4j neo4j;
    /** Les actions d'une application ne changent qu'au déploiement d'une nouvelle version. */
    private final Map<String, List<String>> actionsByRight = new ConcurrentHashMap<String, List<String>>();

    public PackDirectory(Neo4j neo4j) {
        this.neo4j = neo4j;
    }

    /** Groupes de profil Enseignants et Élèves de chaque établissement : établissement → profil → groupe. */
    public Future<Map<String, Map<String, String>>> profileGroups(Collection<String> structureIds) {
        if (structureIds.isEmpty()) {
            return Future.succeededFuture(new HashMap<String, Map<String, String>>());
        }
        final String cypher = "MATCH (s:Structure)<-[:DEPENDS]-(g:ProfileGroup)-[:HAS_PROFILE]->(p:Profile) "
                + "WHERE s.id IN $structures AND p.name IN ['Teacher', 'Student'] "
                + "RETURN s.id AS structure, p.name AS profile, g.id AS groupId";
        return query(cypher, new JsonObject().put("structures", new JsonArray(new ArrayList<String>(structureIds))))
                .map(rows -> {
                    final Map<String, Map<String, String>> groups = new LinkedHashMap<String, Map<String, String>>();
                    for (Object row : rows) {
                        final JsonObject r = (JsonObject) row;
                        Map<String, String> profiles = groups.get(r.getString("structure"));
                        if (profiles == null) {
                            profiles = new HashMap<String, String>();
                            groups.put(r.getString("structure"), profiles);
                        }
                        profiles.put(r.getString("profile"), r.getString("groupId"));
                    }
                    return groups;
                });
    }

    /** Actions de ressource regroupées sous un libellé de droit ({@code wiki.read}). */
    public Future<List<String>> actions(String right) {
        final List<String> cached = actionsByRight.get(right);
        if (cached != null) {
            return Future.succeededFuture(cached);
        }
        final String cypher = "MATCH (a:Action) WHERE a.displayName = $right AND a.type = 'SECURED_ACTION_RESOURCE' "
                + "RETURN a.name AS name";
        return query(cypher, new JsonObject().put("right", right)).compose(rows -> {
            final List<String> names = new ArrayList<String>();
            for (Object row : rows) {
                names.add(((JsonObject) row).getString("name"));
            }
            if (names.isEmpty()) {
                // Un libellé inconnu partagerait des ressources sans aucun droit : mieux vaut échouer.
                return Future.failedFuture("[packs] aucune action « " + right + " » dans l'annuaire");
            }
            actionsByRight.put(right, names);
            return Future.succeededFuture(names);
        });
    }

    /**
     * Établissements d'enseignement peuplés, destinataires d'un abonnement offert à tous.
     *
     * <p>L'annuaire national verse aussi circonscriptions, inspections et services : sans ce filtre,
     * une ressource offerte serait partagée avec des structures qui n'ont ni élève ni enseignant.</p>
     */
    public Future<List<String>> schools() {
        final String cypher = "MATCH (s:Structure)<-[:DEPENDS]-(g:ProfileGroup)-[:HAS_PROFILE]->(p:Profile) "
                + "WHERE p.name = 'Teacher' AND exists((g)<-[:IN]-(:User)) "
                + "RETURN DISTINCT s.id AS id";
        return query(cypher, new JsonObject()).map(rows -> {
            final List<String> ids = new ArrayList<String>();
            for (Object row : rows) {
                ids.add(((JsonObject) row).getString("id"));
            }
            return ids;
        });
    }

    private Future<JsonArray> query(String cypher, JsonObject params) {
        final Promise<JsonArray> promise = Promise.promise();
        neo4j.execute(cypher, params, res -> {
            final JsonObject body = res.body();
            if ("ok".equals(body.getString("status"))) {
                promise.complete(body.getJsonArray("result", new JsonArray()));
            } else {
                promise.fail("[packs] annuaire : " + body.getString("message"));
            }
        });
        return promise.future();
    }
}
