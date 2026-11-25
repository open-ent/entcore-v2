package org.entcore.common.geo;

import fr.wseduc.mongodb.MongoDb;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Lecture des politiques de zones géographiques actives, avec un cache court : elles sont
 * consultées à chaque création de session et ne changent qu'à la main, depuis le dashboard.
 *
 * <p>Une erreur Mongo n'empêche jamais une connexion : on garde le dernier état connu, ou
 * aucune politique.</p>
 */
public final class GeoAccessPolicies {

    private static final Logger log = LoggerFactory.getLogger(GeoAccessPolicies.class);
    private static final long TTL_MS = 60_000L;

    /** Adresse event-bus où sont publiées les connexions hors zone (alertes). */
    public static final String OUT_OF_ZONE_ADDRESS = "openent.geo-access.out-of-zone";
    /** Journal des connexions hors zone (carte, tableau de bord). */
    public static final String EVENTS_COLLECTION = "geo_access_events";

    private static volatile List<JsonObject> cached = Collections.emptyList();
    private static volatile long cachedAt = 0L;

    private GeoAccessPolicies() {
    }

    /** Politiques actives ({@code enabled: true}). */
    public static Future<List<JsonObject>> active() {
        if (System.currentTimeMillis() - cachedAt < TTL_MS) {
            return Future.succeededFuture(cached);
        }
        final Promise<List<JsonObject>> promise = Promise.promise();
        try {
            MongoDb.getInstance().find(GeoAccessPolicy.COLLECTION, new JsonObject().put("enabled", true), res -> {
                if ("ok".equals(res.body().getString("status"))) {
                    final List<JsonObject> policies = new ArrayList<>();
                    for (Object o : res.body().getJsonArray("results", new JsonArray())) {
                        if (o instanceof JsonObject) {
                            policies.add((JsonObject) o);
                        }
                    }
                    cached = Collections.unmodifiableList(policies);
                } else {
                    log.warn("Unable to load geo access policies : " + res.body().getString("message"));
                }
                cachedAt = System.currentTimeMillis();
                promise.complete(cached);
            });
        } catch (Exception e) {
            log.warn("Unable to load geo access policies", e);
            promise.complete(cached);
        }
        return promise.future();
    }

    /** Force la relecture au prochain appel (après une modification sur ce nœud). */
    public static void invalidate() {
        cachedAt = 0L;
    }
}
