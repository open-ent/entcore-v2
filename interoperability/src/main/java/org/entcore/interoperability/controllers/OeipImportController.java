package org.entcore.interoperability.controllers;

import fr.wseduc.rs.Get;
import fr.wseduc.rs.Post;
import fr.wseduc.security.ActionType;
import fr.wseduc.security.MfaProtected;
import fr.wseduc.security.SecuredAction;
import fr.wseduc.webutils.I18n;
import fr.wseduc.webutils.http.BaseController;
import fr.wseduc.webutils.request.RequestUtils;
import io.vertx.core.file.OpenOptions;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.entcore.common.events.EventStore;
import org.entcore.common.http.filter.ResourceFilter;
import org.entcore.common.http.filter.SuperAdminFilter;
import org.entcore.common.user.UserUtils;
import org.entcore.interoperability.services.OeipJob;
import org.entcore.interoperability.services.impl.DefaultOeipImportService;

import java.util.ArrayList;
import java.util.List;

/**
 * Consommation d'un paquet d'échange.
 *
 * Toutes les routes sont réservées au super-administrateur, avec authentification renforcée :
 * un paquet peut transporter les données d'annuaire d'une autre plateforme, et rien en dessous
 * de ce niveau ne doit pouvoir les appliquer ici.
 */
public class OeipImportController extends BaseController {

    private final DefaultOeipImportService importService;
    private final EventStore eventStore;

    public OeipImportController(DefaultOeipImportService importService, EventStore eventStore) {
        this.importService = importService;
        this.eventStore = eventStore;
    }

    @Post("/import")
    @SecuredAction(value = "", type = ActionType.RESOURCE)
    @ResourceFilter(SuperAdminFilter.class)
    @MfaProtected()
    public void upload(final HttpServerRequest request) {
        UserUtils.getUserInfos(eb, request, user -> {
            if (user == null) {
                unauthorized(request);
                return;
            }
            final String jobId = importService.newJobId();
            final String locale = I18n.acceptLanguage(request);
            final String host = getHost(request);
            final String path = importService.uploadTarget(jobId).toAbsolutePath().toString();

            request.pause();
            request.setExpectMultipart(true);
            vertx.fileSystem().mkdirs(importService.uploadTarget(jobId).getParent()
                    .toAbsolutePath().toString(), mk -> {
                if (mk.failed()) {
                    renderError(request, new JsonObject().put("error", String.valueOf(mk.cause())));
                    return;
                }
                vertx.fileSystem().open(path, new OpenOptions(), file -> {
                    if (file.failed()) {
                        renderError(request, new JsonObject().put("error", String.valueOf(file.cause())));
                        return;
                    }
                    request.uploadHandler(upload -> upload.pipeTo(file.result()));
                    request.endHandler(v -> importService.register(jobId, user, locale, host)
                            .onSuccess(saved -> {
                                eventStore.createAndStoreEvent("OEIP_IMPORT_UPLOAD", request,
                                        new JsonObject().put("jobId", jobId));
                                renderJson(request, new JsonObject()
                                        .put("jobId", jobId).put("state", OeipJob.QUEUED), 202);
                            })
                            .onFailure(err -> renderError(request,
                                    new JsonObject().put("error", String.valueOf(err.getMessage())))));
                    request.resume();
                });
            });
        });
    }

    @Get("/import/:jobId/analysis")
    @SecuredAction(value = "", type = ActionType.RESOURCE)
    @ResourceFilter(SuperAdminFilter.class)
    @MfaProtected()
    public void analyze(final HttpServerRequest request) {
        final String jobId = request.params().get("jobId");
        importService.analyze(jobId)
                .onSuccess(analysis -> renderJson(request, analysis))
                .onFailure(err -> renderError(request, new JsonObject()
                        .put("error", String.valueOf(err.getMessage()))));
    }

    @Post("/import/:jobId/apply")
    @SecuredAction(value = "", type = ActionType.RESOURCE)
    @ResourceFilter(SuperAdminFilter.class)
    @MfaProtected()
    public void apply(final HttpServerRequest request) {
        UserUtils.getUserInfos(eb, request, user -> {
            if (user == null) {
                unauthorized(request);
                return;
            }
            RequestUtils.bodyToJson(request, body -> {
                final String jobId = request.params().get("jobId");
                List<String> services = new ArrayList<String>();
                JsonArray requested = body.getJsonArray("services", new JsonArray());
                for (int i = 0; i < requested.size(); i++) {
                    services.add(requested.getString(i));
                }
                // Le mode d'essai est le DÉFAUT : appliquer des données venues d'ailleurs doit
                // être un acte délibéré, pas la conséquence d'un paramètre oublié.
                final boolean dryRun = !"apply".equals(body.getString("mode", "dry-run"));

                // Compte destinataire. Sans ce paramètre, l'import atterrirait dans le compte de
                // l'opérateur — ce qui n'a aucun sens pour une migration : les données d'une
                // personne doivent revenir DANS SON compte. Le module archive sait déjà importer
                // « pour quelqu'un d'autre » : c'est par là que passent la restauration groupée
                // et la reprise de plate-forme. Réservé au super-administrateur par la route.
                final String targetUserId = body.getString("targetUserId");
                if (targetUserId == null || targetUserId.equals(user.getUserId())) {
                    runApply(request, jobId, user, services, dryRun);
                    return;
                }
                UserUtils.getUserInfos(eb, targetUserId, target -> {
                    if (target == null) {
                        badRequest(request, "interoperability.error.target.unknown");
                        return;
                    }
                    runApply(request, jobId, target, services, dryRun);
                });
            });
        });
    }


    private void runApply(final HttpServerRequest request, final String jobId,
                          final org.entcore.common.user.UserInfos target,
                          final List<String> services, final boolean dryRun) {
        importService.apply(jobId, target, services,
                        I18n.acceptLanguage(request), getHost(request), dryRun)
                .onSuccess(report -> {
                    eventStore.createAndStoreEvent("OEIP_IMPORT_APPLY", request,
                            new JsonObject().put("jobId", jobId)
                                    .put("dryRun", dryRun)
                                    .put("targetUserId", target.getUserId()));
                    renderJson(request, report.copy().put("targetUserId", target.getUserId()));
                })
                .onFailure(err -> renderError(request, new JsonObject()
                        .put("error", String.valueOf(err.getMessage()))));
    }

    @Get("/import/:jobId")
    @SecuredAction(value = "", type = ActionType.RESOURCE)
    @ResourceFilter(SuperAdminFilter.class)
    @MfaProtected()
    public void status(final HttpServerRequest request) {
        importService.get(request.params().get("jobId"))
                .onSuccess(job -> {
                    if (job == null) {
                        notFound(request);
                    } else {
                        renderJson(request, OeipJob.toPublic(job));
                    }
                })
                .onFailure(err -> notFound(request));
    }
}
