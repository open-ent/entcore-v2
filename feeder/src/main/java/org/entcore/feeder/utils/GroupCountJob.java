/*
 * Recalcul du compteur dénormalisé g.nbUsers des groupes.
 *
 * Seuls l'import AAF et quelques opérations du feeder le tiennent à jour : un compte créé ou
 * rattaché à la main (ManualFeeder, dashboard) laisse le compteur de ses groupes en retard. Or
 * DefaultCommunicationService écarte les groupes à nbUsers = 0 : un groupe qui a des membres mais
 * un compteur à 0 disparaît du sélecteur de la messagerie et du partage.
 *
 * Deux déclencheurs :
 *  1. toutes les N minutes ("group-count-check-minutes", 15 par défaut, 0 pour désactiver), ce qui
 *     couvre aussi les écritures faites hors du feeder ;
 *  2. quelques secondes après chaque action manuelle du feeder (requestRecount), une fois sa
 *     transaction validée, les demandes rapprochées étant regroupées en un seul passage.
 *
 * Seuls les groupes en écart sont réécrits. Un groupe sans compteur et sans membre est laissé tel
 * quel : lui poser 0 le masquerait du sélecteur, ce que l'absence de compteur ne fait pas.
 */
package org.entcore.feeder.utils;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import org.entcore.common.neo4j.Neo4j;

public class GroupCountJob {

    private static final Logger log = LoggerFactory.getLogger(GroupCountJob.class);

    static final String RECOUNT =
            "MATCH (g:Group) " +
            "OPTIONAL MATCH (g)<-[:IN]-(u:User) " +
            "WITH g, count(DISTINCT u) AS n " +
            "WHERE (g.nbUsers IS NULL AND n > 0) OR g.nbUsers <> n " +
            "SET g.nbUsers = n " +
            "RETURN count(g) AS fixed";

    private static final long REQUEST_DELAY_MS = 5_000L;

    private final Vertx vertx;
    private final Neo4j neo4j;
    private boolean running = false;
    private boolean requestedWhileRunning = false;
    private long pendingTimer = -1L;

    public GroupCountJob(Vertx vertx, Neo4j neo4j) {
        this.vertx = vertx;
        this.neo4j = neo4j;
    }

    public void start(JsonObject config) {
        final long minutes = config.getLong("group-count-check-minutes", 15L);
        if (minutes <= 0) return;
        vertx.setPeriodic(minutes * 60_000L, id -> run());
    }

    public void requestRecount() {
        if (pendingTimer >= 0) return;
        pendingTimer = vertx.setTimer(REQUEST_DELAY_MS, id -> {
            pendingTimer = -1L;
            run();
        });
    }

    public Future<Integer> run() {
        if (running) {
            requestedWhileRunning = true;
            return Future.succeededFuture(0);
        }
        running = true;
        final Promise<Integer> promise = Promise.promise();
        neo4j.execute(RECOUNT, new JsonObject(), event -> {
            running = false;
            final JsonArray rows = event.body().getJsonArray("result");
            if (!"ok".equals(event.body().getString("status")) || rows == null || rows.isEmpty()) {
                log.error("[GroupCount] recount failed : " + event.body().getString("message"));
                promise.complete(0);
            } else {
                final int fixed = rows.getJsonObject(0).getInteger("fixed", 0);
                if (fixed > 0) log.info("[GroupCount] " + fixed + " group counter(s) fixed");
                promise.complete(fixed);
            }
            if (requestedWhileRunning) {
                requestedWhileRunning = false;
                requestRecount();
            }
        });
        return promise.future();
    }
}
