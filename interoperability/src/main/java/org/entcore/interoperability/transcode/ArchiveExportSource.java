package org.entcore.interoperability.transcode;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.eventbus.DeliveryOptions;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.entcore.common.storage.Storage;

import java.nio.file.Path;
import java.util.List;

/**
 * Pilote l'export d'archive existant et en rend une archive dézippée, exploitable.
 *
 * C'est cette classe qui donne à OEIP la couverture des modules déjà gréés : elle ne parle pas
 * au bus « user.repository », elle demande au module archive de faire son travail habituel. Le
 * procédé est déjà en service ailleurs dans le produit (DefaultLibraryService s'en sert pour
 * publier une ressource dans la bibliothèque).
 */
public class ArchiveExportSource {

    private static final io.vertx.core.impl.logging.Logger log =
            io.vertx.core.impl.logging.LoggerFactory.getLogger(ArchiveExportSource.class);

    public static final String EXPORT_ADDRESS = "entcore.export";

    private final Vertx vertx;
    private final Storage storage;
    private final Path workDir;
    private final long exportTimeoutMs;
    private final long maxUncompressedBytes;

    public ArchiveExportSource(Vertx vertx, Storage storage, Path workDir,
                               long exportTimeoutMs, long maxUncompressedBytes) {
        this.vertx = vertx;
        this.storage = storage;
        this.workDir = workDir;
        this.exportTimeoutMs = exportTimeoutMs;
        this.maxUncompressedBytes = maxUncompressedBytes;
    }

    /**
     * Demande un export d'archive et rend l'arborescence dézippée.
     *
     * @param serviceIds préfixes de route des services demandés
     */
    public Future<ArchiveBundle> export(final String userId, final String locale, List<String> serviceIds,
                                        boolean exportDocuments, boolean exportSharedResources) {
        JsonArray apps = new JsonArray();
        for (String s : serviceIds) {
            apps.add(s);
        }
        JsonObject message = new JsonObject()
                .put("action", "start")
                .put("userId", userId)
                .put("locale", locale)
                .put("apps", apps)
                .put("exportDocuments", exportDocuments)
                .put("exportSharedResources", exportSharedResources)
                // force : un export resté « en cours » après un incident bloquerait sinon tout
                // nouvel export du même compte (400 « export.exists »).
                .put("force", true)
                // synchroniseReply : on veut la réponse quand l'archive est réellement prête,
                // pas quand elle est simplement lancée.
                .put("synchroniseReply", true);

        final Promise<ArchiveBundle> promise = Promise.promise();

        vertx.eventBus().request(EXPORT_ADDRESS, message,
                new DeliveryOptions().setSendTimeout(exportTimeoutMs), reply -> {
            if (reply.failed()) {
                // Un module qui ne répond jamais ferait pendre le travail indéfiniment : le
                // délai est borné, et l'échec nomme les services demandés.
                promise.fail("[OEIP] export d'archive sans réponse pour " + serviceIds
                        + " : " + reply.cause().getMessage());
                return;
            }
            JsonObject body = (JsonObject) reply.result().body();
            if (!"ok".equals(body.getString("status"))) {
                promise.fail("[OEIP] export d'archive en échec : " + body.getString("message"));
                return;
            }
            final String exportId = body.getString("exportId");
            if (exportId == null) {
                promise.fail("[OEIP] export d'archive sans identifiant");
                return;
            }
            readAndUnzip(exportId, promise);
        });
        return promise.future();
    }

    private void readAndUnzip(final String exportId, final Promise<ArchiveBundle> promise) {
        // La clé de stockage est l'exportId NU. Le champ « exportPath » rendu par le bus vaut
        // exportId + ".zip", alors que FileSystemExportService écrit sous la clé exportId :
        // s'y fier ferait chercher une clé qui n'existe pas.
        storage.readFile(exportId, new io.vertx.core.Handler<Buffer>() {
            @Override
            public void handle(Buffer buffer) {
                // readFile signale l'échec par un null, sans canal d'erreur : sans ce contrôle,
                // un export absent se manifesterait par un NPE illisible.
                if (buffer == null) {
                    promise.fail("[OEIP] archive introuvable dans le stockage : " + exportId);
                    return;
                }
                final byte[] bytes = buffer.getBytes();
                vertx.executeBlocking(new io.vertx.core.Handler<Promise<ArchiveBundle>>() {
                    @Override
                    public void handle(Promise<ArchiveBundle> blocking) {
                        try {
                            Path target = workDir.resolve(exportId);
                            blocking.complete(ArchiveBundle.unzip(bytes, target, maxUncompressedBytes));
                        } catch (Exception e) {
                            blocking.fail(e);
                        }
                    }
                }, false, res -> {
                    if (res.succeeded()) {
                        promise.complete(res.result());
                    } else {
                        promise.fail(res.cause());
                    }
                });
            }
        });
    }

    /**
     * Supprime l'archive intermédiaire du stockage.
     *
     * À appeler systématiquement : une archive personnelle contient des données à caractère
     * personnel et n'a aucune raison de s'attarder une fois le paquet produit.
     */
    public Future<Void> discard(String exportId) {
        final Promise<Void> promise = Promise.promise();
        vertx.eventBus().request(EXPORT_ADDRESS,
                new JsonObject().put("action", "delete").put("exportId", exportId),
                new DeliveryOptions().setSendTimeout(30000L), reply -> {
            if (reply.failed()) {
                // Non bloquant : la purge périodique du module archive rattrapera.
                log.warn("[OEIP] suppression de l'archive " + exportId + " impossible : "
                        + reply.cause().getMessage());
            }
            promise.complete();
        });
        return promise.future();
    }
}
