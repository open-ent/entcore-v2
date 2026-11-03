package org.entcore.interoperability.packs;

import com.mongodb.client.model.Filters;
import fr.wseduc.mongodb.MongoDb;
import fr.wseduc.mongodb.MongoQueryBuilder;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.bson.conversions.Bson;

/**
 * Registre des packs : ce qui est installé, où, et qui y est abonné.
 *
 * <ul>
 *   <li>{@code oeip.packs} — un document par pack ({@code _id} = identifiant global du pack) ;</li>
 *   <li>{@code oeip.packResources} — une ressource du pack et son identifiant LOCAL sur cette
 *       plateforme ({@code _id} = pack|identifiant global de la ressource) ;</li>
 *   <li>{@code oeip.packSubscriptions} — un abonnement d'établissement ({@code _id} =
 *       pack|établissement ; l'établissement {@code *} désigne l'abonnement offert à tous).</li>
 * </ul>
 */
public class PackStore {

    public static final String PACKS = "oeip.packs";
    public static final String RESOURCES = "oeip.packResources";
    public static final String SUBSCRIPTIONS = "oeip.packSubscriptions";
    /** Variante d'un établissement : {@code _id} = pack|ressource|établissement. */
    public static final String VARIANTS = "oeip.packVariants";
    /** Établissement fictif d'un abonnement offert à tous les établissements. */
    public static final String EVERY_STRUCTURE = "*";

    private final MongoDb mongo = MongoDb.getInstance();

    public static String key(String packId, String other) {
        return packId + "|" + other;
    }

    public Future<Void> savePack(JsonObject pack) {
        return upsert(PACKS, Filters.eq("_id", pack.getString("_id")), pack);
    }

    public Future<JsonObject> pack(String packId) {
        return findOne(PACKS, Filters.eq("_id", packId));
    }

    public Future<JsonArray> packs() {
        return find(PACKS, Filters.exists("_id"));
    }

    public Future<JsonArray> resources(String packId) {
        return find(RESOURCES, Filters.eq("pack", packId));
    }

    public Future<Void> saveResource(JsonObject resource) {
        return upsert(RESOURCES, Filters.eq("_id", resource.getString("_id")), resource);
    }

    public Future<JsonObject> resource(String id) {
        return findOne(RESOURCES, Filters.eq("_id", id));
    }

    public Future<JsonArray> variants(String packId) {
        return find(VARIANTS, Filters.eq("pack", packId));
    }

    public Future<JsonArray> variantsOf(String structureId) {
        return find(VARIANTS, Filters.eq("structure", structureId));
    }

    public Future<JsonObject> variant(String id) {
        return findOne(VARIANTS, Filters.eq("_id", id));
    }

    public Future<Void> saveVariant(JsonObject variant) {
        return upsert(VARIANTS, Filters.eq("_id", variant.getString("_id")), variant);
    }

    public Future<Void> deleteVariant(String id) {
        final Promise<Void> promise = Promise.promise();
        mongo.delete(VARIANTS, MongoQueryBuilder.build(Filters.eq("_id", id)),
                res -> complete(promise, res.body(), "suppression de variante"));
        return promise.future();
    }

    public Future<JsonArray> subscriptions(String packId) {
        return find(SUBSCRIPTIONS, Filters.eq("pack", packId));
    }

    public Future<JsonArray> subscriptionsOf(String structureId) {
        return find(SUBSCRIPTIONS, Filters.in("structure", structureId, EVERY_STRUCTURE));
    }

    public Future<Void> saveSubscription(JsonObject subscription) {
        return upsert(SUBSCRIPTIONS, Filters.eq("_id", subscription.getString("_id")), subscription);
    }

    public Future<Void> deleteSubscription(String packId, String structureId) {
        final Promise<Void> promise = Promise.promise();
        mongo.delete(SUBSCRIPTIONS, MongoQueryBuilder.build(Filters.eq("_id", key(packId, structureId))),
                res -> complete(promise, res.body(), "désabonnement"));
        return promise.future();
    }

    private Future<Void> upsert(String collection, Bson filter, JsonObject document) {
        final Promise<Void> promise = Promise.promise();
        final JsonObject set = document.copy();
        set.remove("_id");
        mongo.update(collection, MongoQueryBuilder.build(filter), new JsonObject().put("$set", set), true, false,
                res -> complete(promise, res.body(), "enregistrement dans " + collection));
        return promise.future();
    }

    private Future<JsonObject> findOne(String collection, Bson filter) {
        final Promise<JsonObject> promise = Promise.promise();
        mongo.findOne(collection, MongoQueryBuilder.build(filter), res -> {
            if ("ok".equals(res.body().getString("status"))) {
                promise.complete(res.body().getJsonObject("result"));
            } else {
                promise.fail("[packs] lecture de " + collection + " : " + res.body().getString("message"));
            }
        });
        return promise.future();
    }

    private Future<JsonArray> find(String collection, Bson filter) {
        final Promise<JsonArray> promise = Promise.promise();
        mongo.find(collection, MongoQueryBuilder.build(filter), res -> {
            if ("ok".equals(res.body().getString("status"))) {
                promise.complete(res.body().getJsonArray("results", new JsonArray()));
            } else {
                promise.fail("[packs] lecture de " + collection + " : " + res.body().getString("message"));
            }
        });
        return promise.future();
    }

    private static void complete(Promise<Void> promise, JsonObject body, String what) {
        if ("ok".equals(body.getString("status"))) {
            promise.complete();
        } else {
            promise.fail("[packs] " + what + " : " + body.getString("message"));
        }
    }
}
