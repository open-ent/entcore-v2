/*
 * Exclusion temporaire d'un élève de la messagerie.
 *
 * Complément des horaires d'utilisation ({@link MessagingHours}) : ici on cible UN élève
 * précis pour une DURÉE déterminée (décision pédagogique/disciplinaire), sans toucher à ses
 * autres applications. Pendant l'exclusion l'élève peut toujours LIRE ses messages mais ne
 * peut plus en ENVOYER (même règle « lecture seule » que les horaires).
 *
 * Source de vérité partagée entre la messagerie classique (ce module) et la messagerie
 * instantanée (module chat-nats) : une collection Mongo « messaging.exclusions » lue par les
 * deux backends. Un document par exclusion :
 *   { _id, userId, structureId, blockedUntil (epoch ms), reason, createdBy, createdAt }
 *
 * Lecture mise en cache (rafraîchie périodiquement) pour ne pas toucher Mongo sur le chemin
 * d'envoi. Le cache retient, par élève, la date de fin la plus tardive (un élève peut être
 * exclu dans plusieurs établissements). Singleton initialisé au démarrage du module.
 */
package org.entcore.conversation.util;

import fr.wseduc.mongodb.MongoDb;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class StudentMessagingExclusions {

    public static final String COLLECTION = "messaging.exclusions";

    private static final StudentMessagingExclusions INSTANCE = new StudentMessagingExclusions();

    private final MongoDb mongo = MongoDb.getInstance();
    // userId -> { blockedUntil, reason } (la fin d'exclusion la plus tardive pour cet élève)
    private final Map<String, JsonObject> byUser = new ConcurrentHashMap<>();
    private boolean featureEnabled = true;

    public static StudentMessagingExclusions getInstance() { return INSTANCE; }

    /** À appeler une fois au démarrage du module : charge le cache + planifie son rafraîchissement. */
    public void init(Vertx vertx, JsonObject conf) {
        long refreshMs = 60000L;
        if (conf != null) {
            this.featureEnabled = conf.getBoolean("messaging-exclusions-enabled", true);
            refreshMs = conf.getLong("messaging-exclusions-refresh-ms", 60000L);
        }
        // Premier chargement différé (MongoDb peut ne pas avoir encore son EventBus) + timer filet.
        final long refresh = refreshMs;
        vertx.setTimer(1, id -> reload());
        vertx.setPeriodic(refresh, id -> reload());
    }

    /** Recharge le cache depuis Mongo. Défensif : si MongoDb n'est pas prêt, on réessaiera. */
    public void reload() {
        try {
            doReload();
        } catch (RuntimeException e) {
            // MongoDb pas encore initialisé : on réessaiera au prochain rafraîchissement.
        }
    }

    private void doReload() {
        mongo.find(COLLECTION, new JsonObject(), msg -> {
            final JsonObject body = msg.body();
            if (body == null || !"ok".equals(body.getString("status"))) return;
            final JsonArray results = body.getJsonArray("results", new JsonArray());
            final Map<String, JsonObject> map = new HashMap<>();
            for (Object o : results) {
                if (!(o instanceof JsonObject)) continue;
                final JsonObject doc = (JsonObject) o;
                final String userId = doc.getString("userId");
                if (userId == null) continue;
                final long until = doc.getLong("blockedUntil", 0L);
                final JsonObject prev = map.get(userId);
                if (prev == null || until > prev.getLong("blockedUntil", 0L)) {
                    map.put(userId, new JsonObject()
                            .put("blockedUntil", until)
                            .put("reason", doc.getString("reason")));
                }
            }
            this.byUser.clear();
            this.byUser.putAll(map);
        });
    }

    public boolean isFeatureEnabled() { return featureEnabled; }

    /** L'élève est-il exclu de la messagerie à l'instant {@code now} (epoch ms) ? */
    public boolean isExcluded(String userId, long now) {
        if (!featureEnabled || userId == null) return false;
        final JsonObject e = byUser.get(userId);
        return e != null && e.getLong("blockedUntil", 0L) > now;
    }

    public boolean isExcluded(String userId) {
        return isExcluded(userId, System.currentTimeMillis());
    }

    /** Statut destiné au bandeau client : exclu ou non, jusqu'à quand, et pourquoi. */
    public JsonObject statusFor(String userId) {
        final long now = System.currentTimeMillis();
        final boolean excluded = isExcluded(userId, now);
        final JsonObject e = excluded ? byUser.get(userId) : null;
        return new JsonObject()
                .put("excluded", excluded)
                .put("blockedUntil", e != null ? e.getLong("blockedUntil") : null)
                .put("reason", e != null ? e.getString("reason") : null);
    }
}
