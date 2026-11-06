package org.entcore.interoperability.packs;

import com.mongodb.client.model.Filters;
import fr.wseduc.mongodb.MongoDb;
import fr.wseduc.mongodb.MongoQueryBuilder;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.entcore.common.folders.impl.DocumentHelper;
import org.entcore.common.storage.Storage;

import java.util.ArrayList;
import java.util.Set;
import java.util.UUID;

/**
 * Fichiers d'un pack dans l'espace documentaire : fiches à imprimer, documents à déposer dans les
 * casiers des élèves. Un enseignant d'un établissement abonné les trouve dans « Partagés avec moi »,
 * d'où il peut les copier ou les envoyer.
 *
 * <p>L'espace documentaire ne passe pas par l'explorateur : ses lectures filtrent sur
 * {@code inheritedShares} (le partage effectif, héritage des dossiers compris). Un fichier de pack
 * n'ayant pas de dossier parent, {@code inheritedShares} y est égal à {@code shared}, et les deux
 * sont écrits ensemble.</p>
 *
 * <p>Ressource attendue : {@code {"file": <chemin du binaire dans le paquet décompressé>,
 * "document": {"name", "contentType"}}}.</p>
 */
public class WorkspacePackAdapter implements PackResourceAdapter {

    private static final String DOCUMENTS = "documents";
    private static final String APPLICATION = "media-library";

    private final Vertx vertx;
    private final Storage storage;
    private final MongoDb mongo = MongoDb.getInstance();

    public WorkspacePackAdapter(Vertx vertx, Storage storage) {
        this.vertx = vertx;
        this.storage = storage;
    }

    @Override
    public String serviceId() { return "workspace"; }

    @Override
    public String readRight() { return "workspace.read"; }

    @Override
    public Future<String> create(PackOwner owner, JsonObject resource) {
        return store(resource).compose(stored -> {
            final String id = UUID.randomUUID().toString();
            final JsonObject doc = DocumentHelper.initFile(new JsonObject(), owner.getId(), owner.getName(),
                    stored.getString("name"), APPLICATION)
                    .put("_id", id)
                    .put("file", stored.getString("file"))
                    .put("metadata", stored.getJsonObject("metadata"))
                    .put("packGlobalId", resource.getString("globalId"))
                    .put("shared", new JsonArray()).put("inheritedShares", new JsonArray())
                    .put("isShared", false);
            final Promise<String> promise = Promise.promise();
            mongo.insert(DOCUMENTS, doc, res -> {
                if ("ok".equals(res.body().getString("status"))) {
                    promise.complete(id);
                } else {
                    promise.fail("[packs] workspace : insertion impossible : " + res.body().getString("message"));
                }
            });
            return promise.future();
        });
    }

    /** Nouveau binaire, nouvelles métadonnées ; l'ancien binaire est supprimé une fois remplacé. */
    @Override
    public Future<Void> update(PackOwner owner, String localId, JsonObject resource) {
        return findFile(localId).compose(previous -> store(resource).compose(stored ->
                write(Filters.eq("_id", localId), new JsonObject()
                        .put("name", stored.getString("name"))
                        .put("file", stored.getString("file"))
                        .put("metadata", stored.getJsonObject("metadata"))
                        .put("modified", MongoDb.formatDate(new java.util.Date()))
                        .put("packGlobalId", resource.getString("globalId")))
                        .compose(v -> {
                            if (previous != null && !previous.equals(stored.getString("file"))) {
                                storage.removeFile(previous, removed -> {});
                            }
                            return Future.<Void>succeededFuture();
                        })));
    }

    @Override
    public Future<Void> setAccess(Set<String> localIds, PackAccess access) {
        if (localIds.isEmpty()) {
            return Future.succeededFuture();
        }
        return write(Filters.in("_id", new ArrayList<String>(localIds)), new JsonObject()
                .put("shared", access.shared())
                .put("inheritedShares", access.shared())
                .put("isShared", !access.shared().isEmpty()));
    }

    @Override
    public Future<Void> reindex(PackOwner owner, Set<String> localIds) {
        return Future.succeededFuture();
    }

    @Override
    public String editRight() { return "workspace.contrib"; }

    /** L'adresse que l'éditeur écrit lui-même pour un enregistrement : {@code <audio src="…">}. */
    @Override
    public String href(String localId) { return "/workspace/document/" + localId; }

    /** Copie du document ET de son binaire : la variante doit pouvoir être remplacée sans toucher l'original. */
    @Override
    public Future<String> fork(PackOwner owner, String localId) {
        final Promise<JsonObject> found = Promise.promise();
        mongo.findOne(DOCUMENTS, MongoQueryBuilder.build(Filters.eq("_id", localId)), res -> {
            final JsonObject result = res.body().getJsonObject("result");
            if (result == null) {
                found.fail("[packs] workspace : document " + localId + " introuvable");
            } else {
                found.complete(result);
            }
        });
        return found.future().compose(original -> {
            final Promise<String> promise = Promise.promise();
            storage.copyFile(original.getString("file"), copied -> {
                if (!"ok".equals(copied.getString("status"))) {
                    promise.fail("[packs] workspace : copie du fichier impossible : " + copied.getString("message"));
                    return;
                }
                final String id = UUID.randomUUID().toString();
                final JsonObject doc = original.copy()
                        .put("_id", id)
                        .put("file", copied.getString("_id"))
                        .put("shared", new JsonArray()).put("inheritedShares", new JsonArray()).put("isShared", false)
                        .put("created", MongoDb.formatDate(new java.util.Date()))
                        .put("modified", MongoDb.formatDate(new java.util.Date()));
                doc.remove("packGlobalId");
                mongo.insert(DOCUMENTS, doc, res -> {
                    if ("ok".equals(res.body().getString("status"))) {
                        promise.complete(id);
                    } else {
                        promise.fail("[packs] workspace : insertion de la copie impossible : "
                                + res.body().getString("message"));
                    }
                });
            });
            return promise.future();
        });
    }

    @Override
    public Future<Void> delete(PackOwner owner, Set<String> localIds) {
        if (localIds.isEmpty()) {
            return Future.succeededFuture();
        }
        final Promise<Void> promise = Promise.promise();
        mongo.find(DOCUMENTS, MongoQueryBuilder.build(Filters.in("_id", new ArrayList<String>(localIds))), found -> {
            for (Object o : found.body().getJsonArray("results", new JsonArray())) {
                storage.removeFile(((JsonObject) o).getString("file"), removed -> {});
            }
            mongo.delete(DOCUMENTS, MongoQueryBuilder.build(Filters.in("_id", new ArrayList<String>(localIds))),
                    res -> promise.complete());
        });
        return promise.future();
    }

    // ------------------------------------------------------------------ utilitaires

    /** Écrit le binaire de la ressource dans le stockage ; rend {name, file, metadata}. */
    private Future<JsonObject> store(JsonObject resource) {
        final String path = resource.getString("file");
        final JsonObject document = resource.getJsonObject("document", new JsonObject());
        if (path == null) {
            return Future.failedFuture("[packs] workspace : ressource sans fichier (body.href)");
        }
        final String name = document.getString("name", resource.getString("title", "document"));
        final String contentType = document.getString("contentType", "application/octet-stream");
        return vertx.fileSystem().readFile(path).compose(bytes -> {
            final Promise<JsonObject> promise = Promise.promise();
            storage.writeBuffer(bytes, contentType, name, written -> {
                if (!"ok".equals(written.getString("status"))) {
                    promise.fail("[packs] workspace : écriture du fichier impossible : " + written.getString("message"));
                    return;
                }
                promise.complete(new JsonObject()
                        .put("name", name)
                        .put("file", written.getString("_id"))
                        .put("metadata", new JsonObject().put("name", "file").put("filename", name)
                                .put("content-type", contentType).put("size", bytes.length())));
            });
            return promise.future();
        });
    }

    private Future<String> findFile(String localId) {
        final Promise<String> promise = Promise.promise();
        mongo.findOne(DOCUMENTS, MongoQueryBuilder.build(Filters.eq("_id", localId)), res -> {
            final JsonObject result = res.body().getJsonObject("result");
            promise.complete(result == null ? null : result.getString("file"));
        });
        return promise.future();
    }

    private Future<Void> write(org.bson.conversions.Bson filter, JsonObject set) {
        final Promise<Void> promise = Promise.promise();
        mongo.update(DOCUMENTS, MongoQueryBuilder.build(filter), new JsonObject().put("$set", set), false, true, res -> {
            if ("ok".equals(res.body().getString("status"))) {
                promise.complete();
            } else {
                promise.fail("[packs] workspace : écriture impossible : " + res.body().getString("message"));
            }
        });
        return promise.future();
    }
}
