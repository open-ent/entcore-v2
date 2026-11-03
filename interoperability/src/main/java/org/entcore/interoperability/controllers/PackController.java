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
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.entcore.common.http.filter.AdmlOfStructure;
import org.entcore.common.http.filter.ResourceFilter;
import org.entcore.common.http.filter.SuperAdminFilter;
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

    public PackController(PackService packs, OeipValidator validator, Path workDir) {
        this.packs = packs;
        this.validator = validator;
        this.workDir = workDir;
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
        packs.store().packs().compose(all -> packs.store().subscriptionsOf(structureId).map(subscriptions -> {
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
                result.add(pack);
            }
            return result;
        })).onSuccess(result -> renderJson(request, new JsonObject().put("packs", result)))
                .onFailure(error -> renderError(request, new JsonObject().put("error", error.getMessage())));
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

    @Delete("/packs/:packId/subscriptions/:structureId")
    @SecuredAction(value = "", type = ActionType.RESOURCE)
    @ResourceFilter(AdmlOfStructure.class)
    public void unsubscribe(final HttpServerRequest request) {
        packs.unsubscribe(param(request, "packId"), param(request, "structureId"))
                .onSuccess(v -> noContent(request))
                .onFailure(error -> renderError(request, new JsonObject().put("error", error.getMessage())));
    }
}
