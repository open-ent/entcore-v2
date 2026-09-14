package org.entcore.interoperability.providers;

import fr.wseduc.mongodb.MongoDb;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.entcore.common.folders.impl.DocumentHelper;
import org.entcore.common.storage.Storage;
import org.entcore.interoperability.spi.OeipImportContext;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Reprend des dossiers et des fichiers décrits dans le modèle commun.
 *
 * Cet importeur passe AVANT ceux qui reprennent des contenus : un billet de blog peut citer un
 * document, et il ne pourra rétablir son lien que si le fichier a déjà été recréé ici. La table
 * des correspondances portée par le contexte est le point de rendez-vous des deux.
 *
 * <p>La forme des documents n'est pas reconstruite à la main : les fabriques du socle
 * ({@link DocumentHelper}) l'établissent, ce qui évite de diverger du reste du produit à chaque
 * évolution du modèle.
 *
 * <p>Comme ailleurs, un partage n'est jamais rétabli : les groupes de la plateforme d'origine
 * n'existent pas ici. Tout revient privé, à repartager sciemment.
 */
public class WorkspaceOeipImporter {

    private static final String DOCUMENTS = "documents";

    /** Même application que la reprise d'archive, pour que les fichiers apparaissent au bon endroit. */
    private static final String APPLICATION = "media-library";

    private final MongoDb mongo;
    private final Storage storage;

    public WorkspaceOeipImporter(MongoDb mongo, Storage storage) {
        this.mongo = mongo;
        this.storage = storage;
    }

    public Future<JsonObject> importCore(OeipImportContext context) {
        final JsonArray folders = readItems(context, "resources/workspace/folders.json");
        final JsonArray attachments = readItems(context, "resources/workspace/attachments.json");

        if (folders.isEmpty() && attachments.isEmpty()) {
            return Future.succeededFuture(new JsonObject()
                    .put("service", WorkspaceOeipMapper.SERVICE_ID)
                    .put("dryRun", context.isDryRun())
                    .put("created", 0)
                    .put("notice", "Aucun contenu d'espace documentaire décrit dans ce paquet."));
        }

        final Map<String, String> newIdByGlobalId = new LinkedHashMap<String, String>();
        final JsonArray skipped = new JsonArray();

        // 1. Les dossiers, du plus haut au plus profond : un dossier ne peut être rattaché qu'à
        //    un parent déjà créé.
        final List<JsonObject> folderDocs = new ArrayList<JsonObject>();
        for (JsonObject f : orderByDepth(folders)) {
            String newId = UUID.randomUUID().toString();
            newIdByGlobalId.put(f.getString("globalId"), newId);
            folderDocs.add(toFolderDocument(f, newId, newIdByGlobalId, context));
        }

        // 2. Les fichiers : seuls ceux réellement présents dans le paquet sont repris.
        final List<JsonObject> pending = new ArrayList<JsonObject>();
        for (int i = 0; i < attachments.size(); i++) {
            JsonObject a = attachments.getJsonObject(i);
            Path binary = context.getPackageRoot().resolve(a.getString("path", ""));
            if (a.getString("path") == null || !Files.isRegularFile(binary)) {
                // Le paquet le décrit sans le contenir : on ne fabrique pas un document vide.
                skipped.add(new JsonObject()
                        .put("globalId", a.getString("globalId"))
                        .put("fileName", a.getString("fileName"))
                        .put("reason", "le fichier décrit est absent du paquet"));
                continue;
            }
            pending.add(a);
        }

        final JsonObject report = new JsonObject()
                .put("service", WorkspaceOeipMapper.SERVICE_ID)
                .put("dryRun", context.isDryRun())
                .put("folders", folderDocs.size())
                .put("files", pending.size())
                .put("created", folderDocs.size() + pending.size())
                .put("notice", "Les fichiers reviennent privés : les groupes de la plateforme "
                        + "d'origine n'existent pas ici, un partage devra être refait.");
        if (!skipped.isEmpty()) {
            report.put("skipped", skipped);
        }

        if (context.isDryRun()) {
            return Future.succeededFuture(report
                    .put("created", 0)
                    .put("wouldCreate", folderDocs.size() + pending.size()));
        }

        Future<Void> chain = insertAll(folderDocs);
        for (final JsonObject a : pending) {
            chain = chain.compose(v -> storeFile(a, context, newIdByGlobalId));
        }
        return chain.map(v -> {
            // Les contenus repris ensuite pourront rétablir leurs liens vers ces fichiers.
            context.getLocalByGlobalId().putAll(newIdByGlobalId);
            return report;
        });
    }

    // ------------------------------------------------------------------ dossiers

    /**
     * Ordonne les dossiers parent avant enfant.
     *
     * Un paquet n'est pas tenu de les présenter dans cet ordre, et un dossier rattaché à un
     * parent pas encore créé se retrouverait à la racine sans qu'on le remarque.
     */
    static List<JsonObject> orderByDepth(JsonArray folders) {
        Map<String, JsonObject> byGlobalId = new LinkedHashMap<String, JsonObject>();
        for (int i = 0; i < folders.size(); i++) {
            JsonObject f = folders.getJsonObject(i);
            byGlobalId.put(f.getString("globalId"), f);
        }
        List<JsonObject> ordered = new ArrayList<JsonObject>();
        java.util.Set<String> placed = new java.util.LinkedHashSet<String>();
        int guard = 0;
        while (ordered.size() < byGlobalId.size() && guard++ <= byGlobalId.size() + 1) {
            for (Map.Entry<String, JsonObject> e : byGlobalId.entrySet()) {
                if (placed.contains(e.getKey())) {
                    continue;
                }
                String parent = e.getValue().getString("parentFolderRef");
                if (parent == null || placed.contains(parent) || !byGlobalId.containsKey(parent)) {
                    ordered.add(e.getValue());
                    placed.add(e.getKey());
                }
            }
        }
        // Un cycle de parenté — donnée corrompue à la source — ne doit pas faire boucler l'import.
        for (Map.Entry<String, JsonObject> e : byGlobalId.entrySet()) {
            if (!placed.contains(e.getKey())) {
                ordered.add(e.getValue());
            }
        }
        return ordered;
    }

    private JsonObject toFolderDocument(JsonObject f, String newId, Map<String, String> newIds,
                                        OeipImportContext context) {
        JsonObject doc = DocumentHelper.initFolder(new JsonObject(), context.getTargetUserId(),
                context.getTargetUserName(),
                nonEmpty(f.getString("name"), "Dossier importé"), APPLICATION);
        doc.put("_id", newId);
        String parent = f.getString("parentFolderRef") == null
                ? null : newIds.get(f.getString("parentFolderRef"));
        if (parent != null) {
            doc.put("eParent", parent);
        }
        // Rien n'est partagé : les groupes d'origine n'existent pas ici.
        doc.put("shared", new JsonArray()).put("inheritedShares", new JsonArray())
           .put("isShared", false);
        return doc;
    }

    // ------------------------------------------------------------------ fichiers

    private Future<Void> storeFile(JsonObject attachment, OeipImportContext context,
                                   Map<String, String> newIds) {
        final Promise<Void> promise = Promise.promise();
        final Path binary = context.getPackageRoot().resolve(attachment.getString("path"));
        final byte[] bytes;
        try {
            bytes = Files.readAllBytes(binary);
        } catch (Exception e) {
            promise.fail("[OEIP] fichier illisible : " + attachment.getString("path"));
            return promise.future();
        }
        final String name = nonEmpty(attachment.getString("fileName"),
                binary.getFileName().toString());
        final String contentType = nonEmpty(attachment.getString("mediaType"),
                "application/octet-stream");

        storage.writeBuffer(Buffer.buffer(bytes), contentType, name, written -> {
            if (!"ok".equals(written.getString("status"))) {
                promise.fail("[OEIP] écriture du fichier impossible : "
                        + written.getString("message"));
                return;
            }
            String storageId = written.getString("_id");
            String docId = UUID.randomUUID().toString();

            JsonObject doc = DocumentHelper.initFile(new JsonObject(), context.getTargetUserId(),
                    context.getTargetUserName(), name, APPLICATION);
            doc.put("_id", docId)
               .put("file", storageId)
               .put("metadata", new JsonObject()
                       .put("name", "file")
                       .put("filename", name)
                       .put("content-type", contentType)
                       .put("size", bytes.length))
               .put("shared", new JsonArray()).put("inheritedShares", new JsonArray())
               .put("isShared", false);
            String parent = attachment.getString("folderRef") == null
                    ? null : newIds.get(attachment.getString("folderRef"));
            if (parent != null) {
                doc.put("eParent", parent);
            }

            mongo.insert(DOCUMENTS, doc, res -> {
                if ("ok".equals(res.body().getString("status"))) {
                    // C'est cet identifiant que les contenus repris ensuite citeront.
                    newIds.put(attachment.getString("globalId"), docId);
                    promise.complete();
                } else {
                    promise.fail("[OEIP] insertion du document impossible : "
                            + res.body().getString("message"));
                }
            });
        });
        return promise.future();
    }

    // ------------------------------------------------------------------ utilitaires

    private Future<Void> insertAll(List<JsonObject> documents) {
        Future<Void> chain = Future.succeededFuture();
        for (final JsonObject doc : documents) {
            chain = chain.compose(v -> {
                Promise<Void> p = Promise.promise();
                mongo.insert(DOCUMENTS, doc, res -> {
                    if ("ok".equals(res.body().getString("status"))) {
                        p.complete();
                    } else {
                        p.fail("[OEIP] insertion du dossier impossible : "
                                + res.body().getString("message"));
                    }
                });
                return p.future();
            });
        }
        return chain;
    }

    private static String nonEmpty(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value;
    }

    private static JsonArray readItems(OeipImportContext context, String path) {
        try {
            Path p = context.getPackageRoot().resolve(path);
            if (!Files.isRegularFile(p)) {
                return new JsonArray();
            }
            return new JsonObject(new String(Files.readAllBytes(p), StandardCharsets.UTF_8))
                    .getJsonArray("items", new JsonArray());
        } catch (Exception e) {
            return new JsonArray();
        }
    }
}
