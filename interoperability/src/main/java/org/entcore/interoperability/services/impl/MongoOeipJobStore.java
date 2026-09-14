package org.entcore.interoperability.services.impl;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.json.JsonObject;
import org.entcore.interoperability.services.OeipJob;

import fr.wseduc.mongodb.MongoDb;
import fr.wseduc.mongodb.MongoQueryBuilder;
import com.mongodb.client.model.Filters;

/**
 * Persistance des travaux OEIP.
 *
 * Aucun accès à la base n'est fait au démarrage du verticle : MongoDb n'est câblé qu'après
 * super.start(), et un appel prématuré échoue par un « eb null » difficile à diagnostiquer.
 */
public class MongoOeipJobStore {

    private final MongoDb mongo = MongoDb.getInstance();

    public Future<Void> save(JsonObject job) {
        final Promise<Void> promise = Promise.promise();
        mongo.save(OeipJob.COLLECTION, job, res -> {
            if ("ok".equals(res.body().getString("status"))) {
                promise.complete();
            } else {
                promise.fail("[OEIP] enregistrement du travail impossible : "
                        + res.body().getString("message"));
            }
        });
        return promise.future();
    }

    public Future<JsonObject> get(String jobId) {
        final Promise<JsonObject> promise = Promise.promise();
        mongo.findOne(OeipJob.COLLECTION, MongoQueryBuilder.build(Filters.eq("_id", jobId)), res -> {
            if ("ok".equals(res.body().getString("status"))) {
                promise.complete(res.body().getJsonObject("result"));
            } else {
                promise.fail("[OEIP] travail introuvable : " + jobId);
            }
        });
        return promise.future();
    }

    /** Fusionne des champs dans le travail, en rafraîchissant toujours updatedAt. */
    public Future<Void> update(String jobId, JsonObject fields) {
        final Promise<Void> promise = Promise.promise();
        JsonObject set = fields.copy().put("updatedAt", System.currentTimeMillis());
        mongo.update(OeipJob.COLLECTION, MongoQueryBuilder.build(Filters.eq("_id", jobId)),
                new JsonObject().put("$set", set), res -> {
            if ("ok".equals(res.body().getString("status"))) {
                promise.complete();
            } else {
                promise.fail("[OEIP] mise à jour du travail impossible : "
                        + res.body().getString("message"));
            }
        });
        return promise.future();
    }

    public Future<Void> fail(String jobId, String message) {
        return update(jobId, new JsonObject()
                .put("state", OeipJob.ERROR)
                .put("error", message));
    }

    /**
     * Travaux dont la durée de conservation est écoulée.
     *
     * Chaque paquet annonce sa durée de conservation dans {@code META/rgpd.json} et demande au
     * destinataire de le détruire : la plateforme émettrice ne peut pas exiger moins d'elle-même.
     */
    public Future<io.vertx.core.json.JsonArray> expired(long now) {
        final Promise<io.vertx.core.json.JsonArray> promise = Promise.promise();
        // Requête écrite à la main, comme partout ailleurs dans le produit : MongoQueryBuilder
        // encode un entier long sous une forme que le persistor refuse ensuite de relire
        // (« String cannot be cast to Number »), alors qu'il accepte très bien une égalité de
        // chaîne. Le défaut ne se voit qu'à l'exécution, jamais à la compilation.
        mongo.find(OeipJob.COLLECTION,
                new JsonObject().put("expiresAt", new JsonObject().put("$lte", now)), res -> {
            if ("ok".equals(res.body().getString("status"))) {
                promise.complete(res.body().getJsonArray("results",
                        new io.vertx.core.json.JsonArray()));
            } else {
                promise.fail("[OEIP] recherche des travaux expirés impossible : "
                        + res.body().getString("message"));
            }
        });
        return promise.future();
    }

    /** Identifiants de tous les travaux connus — sert à repérer les répertoires orphelins. */
    public Future<java.util.Set<String>> allIds() {
        final Promise<java.util.Set<String>> promise = Promise.promise();
        mongo.find(OeipJob.COLLECTION, new JsonObject(), res -> {
            if (!"ok".equals(res.body().getString("status"))) {
                promise.fail("[OEIP] inventaire des travaux impossible : "
                        + res.body().getString("message"));
                return;
            }
            java.util.Set<String> ids = new java.util.LinkedHashSet<String>();
            io.vertx.core.json.JsonArray results = res.body().getJsonArray("results",
                    new io.vertx.core.json.JsonArray());
            for (int i = 0; i < results.size(); i++) {
                ids.add(results.getJsonObject(i).getString("_id"));
            }
            promise.complete(ids);
        });
        return promise.future();
    }

    public Future<Void> delete(String jobId) {
        final Promise<Void> promise = Promise.promise();
        mongo.delete(OeipJob.COLLECTION, MongoQueryBuilder.build(Filters.eq("_id", jobId)),
                res -> promise.complete());
        return promise.future();
    }
}
