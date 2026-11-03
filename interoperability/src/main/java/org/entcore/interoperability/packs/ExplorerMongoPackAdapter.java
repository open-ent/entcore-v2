package org.entcore.interoperability.packs;

import fr.wseduc.mongodb.MongoDb;
import fr.wseduc.mongodb.MongoQueryBuilder;
import com.mongodb.client.model.Filters;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.entcore.common.explorer.IExplorerPluginClient;
import org.entcore.common.explorer.impl.ExplorerPluginClient;
import org.entcore.common.explorer.to.ExplorerReindexResourcesRequest;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Set;

/**
 * Adaptateur des applications dont les ressources sont des documents Mongo indexés par
 * l'explorateur : wiki, cartes mentales, frises, murs collaboratifs…
 *
 * <p>La création passe par le plugin d'explorateur de l'application ({@code createAll}) : c'est lui
 * qui écrit le document et l'indexe, exactement comme une création faite dans l'application. Les
 * mises à jour et les droits, eux, sont écrits ici puis l'application est priée de réindexer —
 * le service de partage standard refuserait le compte technique, qui ne voit aucun groupe.</p>
 *
 * <p>Chaque écriture avance le champ {@code version} : l'explorateur écarte un message dont la
 * version n'a pas progressé, et une réindexation resterait alors sans effet.</p>
 */
public class ExplorerMongoPackAdapter implements PackResourceAdapter {

    /** Champs que le paquet ne doit jamais imposer : identité, auteur et droits sont locaux. */
    private static final String[] LOCAL_FIELDS = {"_id", "owner", "shared", "version", "ingest_job_state"};

    private final String serviceId;
    private final String collection;
    private final String readRight;
    private final String editRight;
    private final ExplorerPluginClient client;
    private final MongoDb mongo = MongoDb.getInstance();

    public ExplorerMongoPackAdapter(Vertx vertx, String serviceId, String application, String resourceType,
                                    String collection, String readRight, String editRight) {
        this.serviceId = serviceId;
        this.collection = collection;
        this.readRight = readRight;
        this.editRight = editRight;
        this.client = IExplorerPluginClient.withBus(vertx, application, resourceType);
    }

    @Override
    public String serviceId() { return serviceId; }

    @Override
    public String readRight() { return readRight; }

    @Override
    public String editRight() { return editRight; }

    /** Copie faite par le plugin de l'application (mode copie), comme une duplication dans l'appli. */
    @Override
    public Future<String> fork(PackOwner owner, String localId) {
        final Promise<JsonObject> found = Promise.promise();
        mongo.findOne(collection, MongoQueryBuilder.build(Filters.eq("_id", localId)), res -> {
            final JsonObject result = res.body().getJsonObject("result");
            if (result == null) {
                found.fail("[packs] " + serviceId + " : ressource " + localId + " introuvable");
            } else {
                found.complete(result);
            }
        });
        return found.future().compose(original -> client.createAll(owner.asUser(),
                Collections.singletonList(withoutLocalFields(original).put("shared", new JsonArray())), true))
                .compose(ids -> ids.isEmpty()
                        ? Future.<String>failedFuture("[packs] " + serviceId + " : copie refusée par l'application")
                        : Future.succeededFuture(ids.get(0)));
    }

    @Override
    public Future<Void> delete(PackOwner owner, Set<String> localIds) {
        if (localIds.isEmpty()) {
            return Future.succeededFuture();
        }
        return client.deleteById(owner.asUser(), localIds).mapEmpty();
    }

    @Override
    public Future<String> create(PackOwner owner, JsonObject resource) {
        final JsonObject json = withoutLocalFields(resource.getJsonObject("document")).put("shared", new JsonArray());
        return client.createAll(owner.asUser(), Collections.singletonList(json), false)
                .compose(ids -> ids.isEmpty()
                        ? Future.<String>failedFuture("[packs] " + serviceId + " : création refusée par l'application")
                        : Future.succeededFuture(ids.get(0)));
    }

    @Override
    public Future<Void> update(PackOwner owner, String localId, JsonObject resource) {
        final JsonObject set = withoutLocalFields(resource.getJsonObject("document"))
                .put("version", System.currentTimeMillis());
        return write(Filters.eq("_id", localId), set)
                .compose(v -> reindex(owner, Collections.singleton(localId)));
    }

    @Override
    public Future<Void> setAccess(Set<String> localIds, PackAccess access) {
        if (localIds.isEmpty()) {
            return Future.succeededFuture();
        }
        final JsonObject set = new JsonObject().put("shared", access.shared())
                .put("version", System.currentTimeMillis());
        return write(Filters.in("_id", new ArrayList<String>(localIds)), set);
    }

    @Override
    public Future<Void> reindex(PackOwner owner, Set<String> localIds) {
        if (localIds.isEmpty()) {
            return Future.succeededFuture();
        }
        // Quelques essais espacés : l'application peut ne pas encore voir l'écriture (réplication).
        return client.tryReindex(owner.asUser(), new ExplorerReindexResourcesRequest(localIds), 3, 1000)
                .mapEmpty();
    }

    private Future<Void> write(org.bson.conversions.Bson filter, JsonObject set) {
        final Promise<Void> promise = Promise.promise();
        mongo.update(collection, MongoQueryBuilder.build(filter), new JsonObject().put("$set", set), false, true,
                res -> {
                    if ("ok".equals(res.body().getString("status"))) {
                        promise.complete();
                    } else {
                        promise.fail("[packs] " + serviceId + " : écriture impossible : "
                                + res.body().getString("message"));
                    }
                });
        return promise.future();
    }

    static JsonObject withoutLocalFields(JsonObject document) {
        final JsonObject json = document == null ? new JsonObject() : document.copy();
        for (String field : LOCAL_FIELDS) {
            json.remove(field);
        }
        return json;
    }
}
