package org.entcore.interoperability.packs;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.eventbus.DeliveryOptions;
import io.vertx.core.eventbus.EventBus;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.ArrayList;
import java.util.Set;

/**
 * Adaptateur des applications qui gèrent elles-mêmes leurs ressources de pack, derrière une
 * adresse du bus (exercizer : {@code exercizer.packs}). Utile quand les ressources vivent dans un
 * schéma SQL propre à l'application ou ne passent pas par l'explorateur : c'est elle qui sait
 * écrire ses tables, le moteur ne fait que lui transmettre la ressource et l'accès.
 *
 * <p>Protocole (réponse {@code {"status": "ok", …}} ou {@code {"status": "error", "message"}}) :
 * <ul>
 *   <li>{@code install} — {@code {globalId, localId?, owner: {id, name}, title, alignments, document}}
 *       → {@code {id}} ; crée, ou met à jour la ressource déjà liée à ce {@code globalId} ;</li>
 *   <li>{@code access} — {@code {ids: […], access: PackAccess.toJson()}} ; remplace l'accès.</li>
 * </ul></p>
 */
public class BusPackAdapter implements PackResourceAdapter {

    private static final DeliveryOptions OPTIONS = new DeliveryOptions().setSendTimeout(120000L);

    private final EventBus eb;
    private final String serviceId;
    private final String address;

    public BusPackAdapter(EventBus eb, String serviceId, String address) {
        this.eb = eb;
        this.serviceId = serviceId;
        this.address = address;
    }

    @Override
    public String serviceId() { return serviceId; }

    @Override
    public String readRight() { return null; }

    @Override
    public Future<String> create(PackOwner owner, JsonObject resource) {
        return install(owner, null, resource);
    }

    @Override
    public Future<Void> update(PackOwner owner, String localId, JsonObject resource) {
        return install(owner, localId, resource).mapEmpty();
    }

    @Override
    public Future<Void> setAccess(Set<String> localIds, PackAccess access) {
        if (localIds.isEmpty()) {
            return Future.succeededFuture();
        }
        return send(new JsonObject().put("action", "access")
                .put("ids", new JsonArray(new ArrayList<Object>(localIds)))
                .put("access", access.toJson())).mapEmpty();
    }

    @Override
    public Future<Void> reindex(PackOwner owner, Set<String> localIds) {
        return Future.succeededFuture();
    }

    private Future<String> install(PackOwner owner, String localId, JsonObject resource) {
        return send(new JsonObject().put("action", "install")
                .put("globalId", resource.getString("globalId"))
                .put("localId", localId)
                .put("owner", new JsonObject().put("id", owner.getId()).put("name", owner.getName()))
                .put("title", resource.getString("title"))
                .put("alignments", resource.getJsonArray("alignments", new JsonArray()))
                .put("document", resource.getJsonObject("document", new JsonObject())))
                .map(body -> String.valueOf(body.getValue("id")));
    }

    private Future<JsonObject> send(JsonObject message) {
        final Promise<JsonObject> promise = Promise.promise();
        eb.<JsonObject>request(address, message, OPTIONS, reply -> {
            if (reply.failed()) {
                promise.fail("[packs] " + serviceId + " injoignable sur " + address + " : " + reply.cause().getMessage());
            } else if (!"ok".equals(reply.result().body().getString("status"))) {
                promise.fail("[packs] " + serviceId + " : " + reply.result().body().getString("message"));
            } else {
                promise.complete(reply.result().body());
            }
        });
        return promise.future();
    }
}
