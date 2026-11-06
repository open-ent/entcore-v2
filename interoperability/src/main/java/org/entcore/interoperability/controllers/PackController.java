package org.entcore.interoperability.controllers;

import fr.wseduc.rs.Delete;
import fr.wseduc.rs.Get;
import fr.wseduc.rs.Post;
import fr.wseduc.rs.Put;
import fr.wseduc.security.ActionType;
import fr.wseduc.security.SecuredAction;
import fr.wseduc.webutils.http.BaseController;
import fr.wseduc.webutils.request.RequestUtils;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.entcore.common.http.filter.AdmlOfStructure;
import org.entcore.common.http.filter.ResourceFilter;
import org.entcore.common.http.filter.SuperAdminFilter;
import org.entcore.common.storage.Storage;
import org.entcore.common.user.UserUtils;
import io.vertx.core.file.OpenOptions;
import org.entcore.interoperability.OeipFormat;
import org.entcore.interoperability.packaging.OeipPackageReader;
import org.entcore.interoperability.packs.PackPackage;
import org.entcore.interoperability.packs.PackService;
import org.entcore.interoperability.schema.OeipValidator;

import java.nio.file.Path;
import org.entcore.interoperability.packs.PackShares;
import org.entcore.interoperability.packs.PackStore;

/**
 * Packs pédagogiques : installation (super administrateur), abonnements (ADML de l'établissement).
 *
 * <p>L'abonnement offert à tous ({@code structureId = *}) n'est accessible qu'au super
 * administrateur : aucun périmètre d'ADML ne contient {@code *}.</p>
 */
public class PackController extends BaseController {

    private final PackService packs;
    private final OeipValidator validator;
    private final Path workDir;
    private final Storage storage;

    public PackController(PackService packs, OeipValidator validator, Path workDir, Storage storage) {
        this.packs = packs;
        this.validator = validator;
        this.workDir = workDir;
        this.storage = storage;
    }

    /**
     * Installe un pack reçu comme fichier {@code .oeip} (OEIP 1.1, profil « pack »).
     * Le paquet est refusé en entier s'il est altéré ou non conforme ; rien n'est alors installé.
     */
    @Post("/packs/import")
    @SecuredAction(value = "", type = ActionType.RESOURCE)
    @ResourceFilter(SuperAdminFilter.class)
    public void importPackage(final HttpServerRequest request) {
        UserUtils.getUserInfos(eb, request, user -> {
            final Path dir = workDir.resolve("packs").resolve(java.util.UUID.randomUUID().toString());
            final Path file = dir.resolve("pack" + OeipFormat.EXTENSION);
            request.pause();
            request.setExpectMultipart(true);
            vertx.fileSystem().mkdirs(dir.toString(), mk -> {
                if (mk.failed()) {
                    renderError(request, new JsonObject().put("error", String.valueOf(mk.cause())));
                    return;
                }
                vertx.fileSystem().open(file.toString(), new OpenOptions(), opened -> {
                    if (opened.failed()) {
                        renderError(request, new JsonObject().put("error", String.valueOf(opened.cause())));
                        return;
                    }
                    request.uploadHandler(upload -> upload.pipeTo(opened.result()));
                    request.endHandler(v -> vertx.<JsonObject>executeBlocking(blocking -> {
                        try {
                            blocking.complete(PackPackage.read(
                                    OeipPackageReader.open(file, dir.resolve("unzipped"), MAX_UNCOMPRESSED), validator));
                        } catch (Exception e) {
                            blocking.fail(e);
                        }
                    }, false).compose(descriptor -> packs.install(descriptor, user.getUserId())
                            .map(report -> report.put("warnings", descriptor.getJsonArray("warnings"))))
                            // Le pack est installé : ne pas pouvoir le retélécharger n'en fait pas un échec.
                            .compose(report -> keepPackage(report.getString("pack"), file)
                                    .recover(error -> {
                                        report.getJsonArray("warnings").add("paquet d'origine non conservé, "
                                                + "il ne pourra pas être téléchargé : " + error.getMessage());
                                        return Future.succeededFuture();
                                    })
                                    .map(kept -> report))
                            .onComplete(done -> {
                                vertx.fileSystem().deleteRecursive(dir.toString(), true, cleaned -> {});
                                if (done.succeeded()) {
                                    renderJson(request, done.result(), 201);
                                } else {
                                    badRequest(request, done.cause().getMessage());
                                }
                            }));
                    request.resume();
                });
            });
        });
    }

    /**
     * Conserve le paquet tel qu'il a été reçu, signature comprise, pour qu'il puisse être
     * retéléchargé (et réinstallé ailleurs) ; celui de la version précédente est supprimé.
     */
    private Future<Void> keepPackage(String packId, Path file) {
        final String fileId = java.util.UUID.randomUUID().toString();
        final Promise<JsonObject> written = Promise.promise();
        storage.writeFsFile(fileId, file.toString(), written::complete);
        return written.future().compose(res -> "ok".equals(res.getString("status"))
                        ? vertx.fileSystem().props(file.toString())
                        : Future.failedFuture(String.valueOf(res.getString("message"))))
                .compose(props -> packs.store().pack(packId).compose(previous -> packs.store().savePack(
                        new JsonObject().put("_id", packId).put("package", new JsonObject()
                                .put("file", fileId)
                                .put("size", props.size())
                                .put("storedAt", System.currentTimeMillis())))
                        .map(v -> {
                            final String old = previous == null ? null
                                    : previous.getJsonObject("package", new JsonObject()).getString("file");
                            if (old != null) {
                                storage.removeFile(old, removed -> {});
                            }
                            return (Void) null;
                        })));
    }

    /**
     * Télécharge le paquet {@code .oeip} d'origine du pack. Un pack installé avant que les paquets
     * soient conservés n'en a pas : le réinstaller le rend téléchargeable.
     */
    @Get("/packs/:packId/package")
    @SecuredAction(value = "", type = ActionType.RESOURCE)
    @ResourceFilter(SuperAdminFilter.class)
    public void download(final HttpServerRequest request) {
        final String packId = param(request, "packId");
        packs.store().pack(packId)
                .onSuccess(pack -> {
                    final JsonObject kept = pack == null ? null : pack.getJsonObject("package");
                    if (kept == null || kept.getString("file") == null) {
                        notFound(request, pack == null ? "pack inconnu"
                                : "paquet d'origine non conservé : réinstaller le pack pour pouvoir le télécharger");
                        return;
                    }
                    storage.sendFile(kept.getString("file"), downloadName(pack), request, false,
                            new JsonObject().put("content-type", OeipFormat.MEDIA_TYPE).put("size", kept.getLong("size")));
                })
                .onFailure(error -> renderError(request, new JsonObject().put("error", error.getMessage())));
    }

    /** {@code urn:openent:pack:ce1-soustraction-posee} en 1.2.0 → {@code ce1-soustraction-posee-1.2.0.oeip}. */
    private static String downloadName(JsonObject pack) {
        final String id = pack.getString("_id");
        final String version = pack.getString("version");
        final String base = (id.substring(id.lastIndexOf(':') + 1)
                + (version == null ? "" : "-" + version)).replaceAll("[^A-Za-z0-9._-]", "_");
        return base + OeipFormat.EXTENSION;
    }

    /** Plafond de décompression d'un pack : bien au-delà d'une séquence avec ses fiches PDF. */
    private static final long MAX_UNCOMPRESSED = 2L * 1024 * 1024 * 1024;

    /**
     * Paramètre de chemin décodé : un identifiant de pack est une URN ({@code urn:openent:pack:…}),
     * qu'un client peut envoyer encodée ; le routeur, lui, ne décode pas.
     */
    private static String param(HttpServerRequest request, String name) {
        final String raw = request.params().get(name);
        try {
            return raw == null ? null : java.net.URLDecoder.decode(raw, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            return raw;
        }
    }

    @Post("/packs/install")
    @SecuredAction(value = "", type = ActionType.RESOURCE)
    @ResourceFilter(SuperAdminFilter.class)
    public void install(final HttpServerRequest request) {
        UserUtils.getUserInfos(eb, request, user -> RequestUtils.bodyToJson(request, descriptor ->
                packs.install(descriptor, user.getUserId())
                        .onSuccess(report -> renderJson(request, report, 201))
                        .onFailure(error -> badRequest(request, error.getMessage()))));
    }

    @Get("/packs")
    @SecuredAction(value = "", type = ActionType.RESOURCE)
    @ResourceFilter(SuperAdminFilter.class)
    public void list(final HttpServerRequest request) {
        packs.store().packs()
                .onSuccess(result -> renderJson(request, new JsonObject().put("packs", result)))
                .onFailure(error -> renderError(request, new JsonObject().put("error", error.getMessage())));
    }

    @Get("/packs/:packId")
    @SecuredAction(value = "", type = ActionType.RESOURCE)
    @ResourceFilter(SuperAdminFilter.class)
    public void get(final HttpServerRequest request) {
        final String packId = param(request, "packId");
        packs.store().pack(packId).compose(pack -> pack == null
                ? Future.<JsonObject>succeededFuture(null)
                : packs.store().resources(packId).compose(resources -> packs.store().subscriptions(packId)
                        .map(subscriptions -> pack.copy().put("resources", resources)
                                .put("subscriptions", subscriptions))))
                .onSuccess(result -> {
                    if (result == null) {
                        notFound(request, "pack inconnu");
                    } else {
                        renderJson(request, result);
                    }
                })
                .onFailure(error -> renderError(request, new JsonObject().put("error", error.getMessage())));
    }

    /** Packs installés et état de l'abonnement de l'établissement (écran ADML). */
    @Get("/packs/structures/:structureId")
    @SecuredAction(value = "", type = ActionType.RESOURCE)
    @ResourceFilter(AdmlOfStructure.class)
    public void ofStructure(final HttpServerRequest request) {
        final String structureId = param(request, "structureId");
        packs.store().packs().compose(all -> packs.store().subscriptionsOf(structureId).compose(subscriptions ->
                packs.store().variantsOf(structureId).map(variants -> {
            final JsonArray result = new JsonArray();
            for (Object o : all) {
                final JsonObject pack = ((JsonObject) o).copy();
                for (Object s : subscriptions) {
                    final JsonObject sub = (JsonObject) s;
                    if (!pack.getString("_id").equals(sub.getString("pack"))) {
                        continue;
                    }
                    // L'abonnement propre à l'établissement l'emporte sur l'offre faite à tous.
                    if (structureId.equals(sub.getString("structure")) || !pack.containsKey("subscription")) {
                        pack.put("subscription", sub);
                    }
                }
                // Variantes de l'établissement, et celles dont le pack a changé de version depuis.
                final JsonArray own = new JsonArray();
                for (Object v : variants) {
                    final JsonObject variant = ((JsonObject) v).copy();
                    if (pack.getString("_id").equals(variant.getString("pack"))) {
                        own.add(variant.put("updateAvailable",
                                !String.valueOf(pack.getString("version")).equals(variant.getString("basedOnVersion"))));
                    }
                }
                result.add(pack.put("variants", own));
            }
            return result;
        }))).compose(this::withResources)
                .onSuccess(result -> renderJson(request, new JsonObject().put("packs", result)))
                .onFailure(error -> renderError(request, new JsonObject().put("error", error.getMessage())));
    }

    /** Ajoute à chaque pack ses ressources (titre, service, identifiant global, énoncés). */
    private Future<JsonArray> withResources(JsonArray packList) {
        Future<Void> chain = Future.succeededFuture();
        for (Object o : packList) {
            final JsonObject pack = (JsonObject) o;
            chain = chain.compose(v -> packs.store().resources(pack.getString("_id")).map(resources -> {
                final JsonArray summary = new JsonArray();
                for (Object r : resources) {
                    final JsonObject resource = (JsonObject) r;
                    summary.add(new JsonObject()
                            .put("globalId", resource.getString("globalId"))
                            .put("service", resource.getString("service"))
                            .put("title", resource.getString("title"))
                            .put("localId", resource.getString("localId"))
                            .put("alignments", resource.getJsonArray("alignments", new JsonArray())));
                }
                pack.put("resources", summary);
                return (Void) null;
            }));
        }
        return chain.map(v -> packList);
    }

    /** Corps : {@code {"audience": "teachers" | "teachers+students"}}. */
    @Put("/packs/:packId/subscriptions/:structureId")
    @SecuredAction(value = "", type = ActionType.RESOURCE)
    @ResourceFilter(AdmlOfStructure.class)
    public void subscribe(final HttpServerRequest request) {
        final String packId = param(request, "packId");
        final String structureId = param(request, "structureId");
        UserUtils.getUserInfos(eb, request, user -> RequestUtils.bodyToJson(request, body ->
                packs.subscribe(packId, structureId, body.getString("audience", PackShares.TEACHERS),
                                PackStore.EVERY_STRUCTURE.equals(structureId), user.getUserId())
                        .onSuccess(v -> renderJson(request, new JsonObject().put("pack", packId)
                                .put("structure", structureId)))
                        .onFailure(error -> badRequest(request, error.getMessage()))));
    }

    /**
     * Variante de l'établissement pour une ressource du pack. Corps : {@code {"globalId": …}}.
     * L'ADML qui la demande en devient l'éditeur.
     */
    @Post("/packs/:packId/variants/:structureId")
    @SecuredAction(value = "", type = ActionType.RESOURCE)
    @ResourceFilter(AdmlOfStructure.class)
    public void createVariant(final HttpServerRequest request) {
        final String packId = param(request, "packId");
        final String structureId = param(request, "structureId");
        UserUtils.getUserInfos(eb, request, user -> RequestUtils.bodyToJson(request, body ->
                packs.createVariant(packId, structureId, body.getString("globalId"), user.getUserId())
                        .onSuccess(variant -> renderJson(request, variant, 201))
                        .onFailure(error -> badRequest(request, error.getMessage()))));
    }

    /** Abandonne la variante ({@code ?globalId=…}) : l'établissement retrouve la ressource commune. */
    @Delete("/packs/:packId/variants/:structureId")
    @SecuredAction(value = "", type = ActionType.RESOURCE)
    @ResourceFilter(AdmlOfStructure.class)
    public void deleteVariant(final HttpServerRequest request) {
        packs.deleteVariant(param(request, "packId"), param(request, "structureId"), request.params().get("globalId"))
                .onSuccess(v -> noContent(request))
                .onFailure(error -> renderError(request, new JsonObject().put("error", error.getMessage())));
    }

    @Delete("/packs/:packId/subscriptions/:structureId")
    @SecuredAction(value = "", type = ActionType.RESOURCE)
    @ResourceFilter(AdmlOfStructure.class)
    public void unsubscribe(final HttpServerRequest request) {
        packs.unsubscribe(param(request, "packId"), param(request, "structureId"))
                .onSuccess(v -> noContent(request))
                .onFailure(error -> renderError(request, new JsonObject().put("error", error.getMessage())));
    }
}
