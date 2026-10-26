package org.entcore.infra.services.impl;

import fr.wseduc.mongodb.MongoDb;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.eventbus.Message;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import org.entcore.common.audit.AuditChain;
import org.entcore.common.audit.AuditEvent;
import org.entcore.common.audit.AuditHelper;

import java.util.ArrayDeque;
import java.util.Deque;

import static org.entcore.common.utils.DateUtils.formatUtcDateTime;

/**
 * Écrivain unique du journal d'audit (collection {@code audit}), à l'écoute de
 * {@link AuditHelper#ADDRESS}.
 *
 * Actions :
 *  - append : ajoute une entrée en fin de chaîne (seq, prevHash, hash) ; jamais de mise à jour.
 *  - verify : recalcule la chaîne et renvoie les anomalies (entrée modifiée, retirée ou insérée).
 *
 * Les écritures sont sérialisées localement ; entre nœuds, l'index unique sur seq fait échouer
 * l'écriture concurrente, qui relit la fin de chaîne et réessaie. La purge par TTL (expireAt) retire
 * les entrées les plus anciennes : la vérification part donc de la première entrée restante.
 */
public class MongoDbAuditStore {

    public static final String COLLECTION = "audit";
    private static final Logger log = LoggerFactory.getLogger(MongoDbAuditStore.class);
    private static final int MAX_RETRIES = 3;
    private static final int VERIFY_BATCH = 500;
    private static final int MAX_ANOMALIES = 100;

    private final MongoDb mongo = MongoDb.getInstance();
    private final Deque<Message<JsonObject>> queue = new ArrayDeque<>();
    private boolean writing = false;
    private Long lastSeq;
    private String lastHash;

    public MongoDbAuditStore(Vertx vertx) {
        vertx.eventBus().<JsonObject>consumer(AuditHelper.ADDRESS, this::handle);
    }

    private void handle(Message<JsonObject> message) {
        final String action = message.body().getString("action", "");
        if ("append".equals(action)) {
            queue.add(message);
            drain();
        } else if ("verify".equals(action)) {
            verify(message.body().getLong("fromSeq", 0L)).onComplete(ar -> {
                if (ar.succeeded()) message.reply(ar.result().put("status", "ok"));
                else message.reply(error(ar.cause().getMessage()));
            });
        } else {
            message.reply(error("audit.invalid.action"));
        }
    }

    // ------------------------------------------------------------------------------- append

    private void drain() {
        if (writing || queue.isEmpty()) return;
        writing = true;
        final Message<JsonObject> message = queue.poll();
        append(message.body().getJsonObject("event", new JsonObject()), 0).onComplete(ar -> {
            if (ar.succeeded()) {
                message.reply(new JsonObject().put("status", "ok").put("seq", ar.result()));
            } else {
                log.error("[Audit] append failed : " + ar.cause().getMessage());
                message.reply(error(ar.cause().getMessage()));
            }
            writing = false;
            drain();
        });
    }

    private Future<Long> append(JsonObject event, int attempt) {
        return loadTail().compose(v -> {
            final long seq = lastSeq + 1;
            final JsonObject entry = event.copy().put("seq", seq).put("prevHash", lastHash);
            final String hash = AuditChain.hash(lastHash, entry);
            final int retention = entry.getInteger("retention-days", AuditEvent.DEFAULT_RETENTION_DAYS);
            entry.put("hash", hash).put("expireAt", new JsonObject().put("$date",
                    formatUtcDateTime(entry.getLong("date", System.currentTimeMillis()) + retention * 24L * 3600 * 1000)));
            final Promise<Long> promise = Promise.promise();
            mongo.insert(COLLECTION, entry, res -> {
                if ("ok".equals(res.body().getString("status"))) {
                    lastSeq = seq;
                    lastHash = hash;
                    promise.complete(seq);
                } else if (attempt < MAX_RETRIES && String.valueOf(res.body().getString("message")).contains("E11000")) {
                    // Un autre nœud a écrit ce seq : on relit la fin de chaîne.
                    lastSeq = null;
                    append(event, attempt + 1).onComplete(promise);
                } else {
                    promise.fail(res.body().getString("message", "audit.insert.error"));
                }
            });
            return promise.future();
        });
    }

    private Future<Void> loadTail() {
        if (lastSeq != null) return Future.succeededFuture();
        final Promise<Void> promise = Promise.promise();
        mongo.find(COLLECTION, new JsonObject(), new JsonObject().put("seq", -1),
                new JsonObject().put("seq", 1).put("hash", 1), 0, 1, 1, res -> {
            if (!"ok".equals(res.body().getString("status"))) {
                promise.fail(res.body().getString("message", "audit.tail.error"));
                return;
            }
            final JsonArray results = res.body().getJsonArray("results", new JsonArray());
            if (results.isEmpty()) {
                lastSeq = 0L;
                lastHash = AuditChain.GENESIS;
            } else {
                lastSeq = results.getJsonObject(0).getLong("seq");
                lastHash = results.getJsonObject(0).getString("hash");
            }
            promise.complete();
        });
        return promise.future();
    }

    // ------------------------------------------------------------------------------- verify

    public Future<JsonObject> verify(long fromSeq) {
        final JsonObject state = new JsonObject().put("checked", 0).put("anomalies", new JsonArray());
        return verifyBatch(fromSeq, null, state);
    }

    private Future<JsonObject> verifyBatch(long fromSeq, JsonObject previous, JsonObject state) {
        final Promise<JsonObject> promise = Promise.promise();
        final JsonObject query = new JsonObject().put("seq", new JsonObject().put("$gte", fromSeq));
        mongo.find(COLLECTION, query, new JsonObject().put("seq", 1), null, 0, VERIFY_BATCH, VERIFY_BATCH, res -> {
            if (!"ok".equals(res.body().getString("status"))) {
                promise.fail(res.body().getString("message", "audit.verify.error"));
                return;
            }
            final JsonArray results = res.body().getJsonArray("results", new JsonArray());
            final JsonArray anomalies = state.getJsonArray("anomalies");
            JsonObject prev = previous;
            for (Object o : results) {
                final JsonObject e = (JsonObject) o;
                final long seq = e.getLong("seq");
                final String prevHash = e.getString("prevHash");
                if (!AuditChain.hash(prevHash, e).equals(e.getString("hash"))) {
                    anomalies.add(anomaly(seq, "content.modified"));
                }
                // La première entrée restante sert d'ancre (les précédentes ont pu être purgées).
                if (prev != null) {
                    if (seq != prev.getLong("seq") + 1) anomalies.add(anomaly(seq, "entries.missing.before"));
                    if (!prev.getString("hash").equals(prevHash)) anomalies.add(anomaly(seq, "chain.broken"));
                }
                prev = e;
                state.put("checked", state.getInteger("checked") + 1);
                if (anomalies.size() >= MAX_ANOMALIES) break;
            }
            if (results.size() < VERIFY_BATCH || anomalies.size() >= MAX_ANOMALIES) {
                promise.complete(state.put("valid", anomalies.isEmpty()));
            } else {
                verifyBatch(prev.getLong("seq") + 1, prev, state).onComplete(promise);
            }
        });
        return promise.future();
    }

    private static JsonObject anomaly(long seq, String reason) {
        return new JsonObject().put("seq", seq).put("reason", reason);
    }

    private static JsonObject error(String message) {
        return new JsonObject().put("status", "error").put("message", message);
    }
}
