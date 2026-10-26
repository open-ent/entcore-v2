/*
 * Retrait automatique des accès privilégiés arrivés à échéance (HAS_FUNCTION.validUntil).
 *
 * Toutes les N minutes ("pim-expiry-check-minutes", 5 par défaut, 0 pour désactiver) :
 *  1. retire la fonction et l'appartenance aux FunctionGroups correspondants ;
 *  2. ferme toutes les sessions du compte, pour que ses droits soient recalculés à la reconnexion ;
 *  3. écrit l'expiration dans le journal d'audit (PIM_FUNCTION_EXPIRED, source JOB).
 */
package org.entcore.feeder.pim;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import org.entcore.common.audit.AuditEvent;
import org.entcore.common.audit.AuditHelper;
import org.entcore.common.neo4j.Neo4j;
import org.entcore.common.user.UserUtils;
import org.entcore.feeder.utils.StatementsBuilder;

public class PimExpiryJob {

    private static final Logger log = LoggerFactory.getLogger(PimExpiryJob.class);

    static final String FIND_EXPIRED =
            "MATCH (u:User)-[rf:HAS_FUNCTION]->(f:Function) " +
            "WHERE rf.validUntil IS NOT NULL AND rf.validUntil <= {now} " +
            "RETURN u.id AS userId, u.login AS login, f.externalId AS function, rf.scope AS scope, " +
            "rf.validUntil AS validUntil, rf.grantedBy AS grantedBy, rf.reason AS reason " +
            "LIMIT 500";

    static final String REMOVE =
            "MATCH (u:User {id: {userId}})-[rf:HAS_FUNCTION]->(f:Function {externalId: {function}}) " +
            "WHERE rf.validUntil IS NOT NULL AND rf.validUntil <= {now} " +
            "OPTIONAL MATCH (u)-[r:IN|COMMUNIQUE]-(fg:FunctionGroup) WHERE fg.externalId ENDS WITH {function} " +
            "DELETE r, rf " +
            "RETURN count(DISTINCT f) AS removed";

    private final Vertx vertx;
    private final Neo4j neo4j;
    private boolean running = false;

    public PimExpiryJob(Vertx vertx, Neo4j neo4j) {
        this.vertx = vertx;
        this.neo4j = neo4j;
    }

    public void start(JsonObject config) {
        final long minutes = config.getLong("pim-expiry-check-minutes", 5L);
        if (minutes <= 0) return;
        vertx.setPeriodic(minutes * 60_000L, id -> run());
    }

    public Future<Integer> run() {
        if (running) return Future.succeededFuture(0);
        running = true;
        final long now = System.currentTimeMillis();
        final Promise<Integer> promise = Promise.promise();
        neo4j.execute(FIND_EXPIRED, new JsonObject().put("now", now), event -> {
            final JsonArray rows = event.body().getJsonArray("result");
            if (!"ok".equals(event.body().getString("status")) || rows == null || rows.isEmpty()) {
                running = false;
                promise.complete(0);
                return;
            }
            Future<Integer> chain = Future.succeededFuture(0);
            for (Object o : rows) {
                final JsonObject row = (JsonObject) o;
                chain = chain.compose(count -> expire(row, now).map(ok -> ok ? count + 1 : count));
            }
            chain.onComplete(ar -> {
                running = false;
                if (ar.succeeded() && ar.result() > 0) log.info("[PIM] " + ar.result() + " expired function(s) removed");
                promise.handle(ar);
            });
        });
        return promise.future();
    }

    private Future<Boolean> expire(JsonObject row, long now) {
        final Promise<Boolean> promise = Promise.promise();
        final StatementsBuilder statements = new StatementsBuilder()
                .add(REMOVE, new JsonObject().put("userId", row.getString("userId"))
                        .put("function", row.getString("function")).put("now", now));
        neo4j.executeTransaction(statements.build(), null, true, event -> {
            if (!"ok".equals(event.body().getString("status"))) {
                log.error("[PIM] unable to remove expired function " + row.encode() + " : " + event.body().getString("message"));
                promise.complete(false);
                return;
            }
            UserUtils.deleteSessionsByUserId(vertx.eventBus(), row.getString("userId"), dropped -> {
                if (dropped.failed()) log.warn("[PIM] sessions not closed for " + row.getString("userId"), dropped.cause());
            });
            AuditHelper.publish(vertx.eventBus(), AuditEvent.of("PIM_FUNCTION_EXPIRED")
                    .target("User", row.getString("userId"))
                    .source(AuditEvent.Source.JOB)
                    .application("feeder")
                    .before(row)
                    .status(200));
            promise.complete(true);
        });
        return promise.future();
    }
}
