package org.entcore.interoperability.controllers;

import fr.wseduc.rs.Delete;
import fr.wseduc.rs.Get;
import fr.wseduc.rs.Post;
import fr.wseduc.security.SecuredAction;
import fr.wseduc.webutils.I18n;
import fr.wseduc.webutils.http.BaseController;
import fr.wseduc.webutils.request.RequestUtils;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.entcore.common.events.EventStore;
import org.entcore.common.user.UserInfos;
import org.entcore.common.user.UserUtils;
import org.entcore.interoperability.services.OeipJob;
import org.entcore.interoperability.services.impl.DefaultOeipExportService;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Production d'un paquet d'échange. Périmètre personnel : l'appelant exporte son propre compte. */
public class OeipExportController extends BaseController {

    private final DefaultOeipExportService exportService;
    private final EventStore eventStore;

    public OeipExportController(DefaultOeipExportService exportService, EventStore eventStore) {
        this.exportService = exportService;
        this.eventStore = eventStore;
    }

    @Post("/export")
    @SecuredAction("interoperability.export.personal")
    public void export(final HttpServerRequest request) {
        UserUtils.getUserInfos(eb, request, user -> {
            if (user == null) {
                unauthorized(request);
                return;
            }
            RequestUtils.bodyToJson(request, body -> {
                List<String> services = new ArrayList<String>();
                JsonArray requested = body.getJsonArray("services", new JsonArray());
                for (int i = 0; i < requested.size(); i++) {
                    services.add(requested.getString(i));
                }
                if (services.isEmpty()) {
                    services = exportService.defaultServices();
                }
                if (services.isEmpty()) {
                    // Plutôt qu'un paquet vide, un refus explicite : c'est exactement le
                    // silence qu'OEIP cherche à supprimer.
                    badRequest(request, "interoperability.error.service.not.exportable");
                    return;
                }
                final String locale = I18n.acceptLanguage(request);
                final String host = getHost(request);

                exportService.start(user, locale, host, services,
                                body.getBoolean("includeBinaries", true),
                                body.getBoolean("includeSharedResources", true))
                        .onSuccess(jobId -> {
                            eventStore.createAndStoreEvent("OEIP_EXPORT", request,
                                    new JsonObject().put("jobId", jobId));
                            request.response().setStatusCode(202);
                            renderJson(request, new JsonObject().put("jobId", jobId)
                                    .put("state", OeipJob.QUEUED), 202);
                        })
                        .onFailure(err -> renderError(request,
                                new JsonObject().put("error", String.valueOf(err.getMessage()))));
            });
        });
    }

    @Get("/export/:jobId")
    @SecuredAction("interoperability.export.personal")
    public void status(final HttpServerRequest request) {
        withOwnedJob(request, job -> renderJson(request, OeipJob.toPublic(job)));
    }

    @Get("/export/:jobId/package")
    @SecuredAction("interoperability.export.personal")
    public void download(final HttpServerRequest request) {
        withOwnedJob(request, job -> {
            if (!OeipJob.READY.equals(job.getString("state"))) {
                badRequest(request, "interoperability.error.package.invalid");
                return;
            }
            Path path = exportService.packagePath(job);
            if (path == null || !Files.isRegularFile(path)) {
                notFound(request);
                return;
            }
            String fileName = job.getString("_id")
                    + org.entcore.interoperability.OeipFormat.EXTENSION;
            request.response()
                    .putHeader("Content-Type", org.entcore.interoperability.OeipFormat.MEDIA_TYPE)
                    .putHeader("Content-Disposition", "attachment; filename=\"" + fileName + "\"");
            request.response().sendFile(path.toAbsolutePath().toString());
        });
    }

    @Delete("/export/:jobId")
    @SecuredAction("interoperability.export.personal")
    public void discard(final HttpServerRequest request) {
        withOwnedJob(request, job -> {
            Path path = exportService.packagePath(job);
            if (path != null) {
                vertx.fileSystem().deleteRecursive(path.getParent().toString(), true, ignored -> { });
            }
            renderJson(request, new JsonObject().put("status", "ok"));
        });
    }

    /**
     * Un travail n'est lisible que par son propriétaire.
     *
     * Le contrôle est fait ici plutôt que par un filtre de ressource : un identifiant de travail
     * est un UUID opaque, mais l'opacité n'est pas une autorisation.
     */
    private void withOwnedJob(final HttpServerRequest request,
                              final io.vertx.core.Handler<JsonObject> handler) {
        UserUtils.getUserInfos(eb, request, user -> {
            if (user == null) {
                unauthorized(request);
                return;
            }
            String jobId = request.params().get("jobId");
            exportService.get(jobId)
                    .onSuccess(job -> {
                        if (job == null) {
                            notFound(request);
                        } else if (!user.getUserId().equals(job.getString("userId"))) {
                            unauthorized(request);
                        } else {
                            handler.handle(job);
                        }
                    })
                    .onFailure(err -> notFound(request));
        });
    }
}
