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

    public Future<Void> delete(String jobId) {
        final Promise<Void> promise = Promise.promise();
        mongo.delete(OeipJob.COLLECTION, MongoQueryBuilder.build(Filters.eq("_id", jobId)),
                res -> promise.complete());
        return promise.future();
    }
}
