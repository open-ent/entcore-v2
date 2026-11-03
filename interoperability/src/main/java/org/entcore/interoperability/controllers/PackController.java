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
import org.entcore.interoperability.packs.PackService;
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

    public PackController(PackService packs) {
        this.packs = packs;
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
        final String packId = request.params().get("packId");
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
        final String structureId = request.params().get("structureId");
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
        final String packId = request.params().get("packId");
        final String structureId = request.params().get("structureId");
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
        packs.unsubscribe(request.params().get("packId"), request.params().get("structureId"))
                .onSuccess(v -> noContent(request))
                .onFailure(error -> renderError(request, new JsonObject().put("error", error.getMessage())));
    }
}
