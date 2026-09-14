package org.entcore.archive.controllers;

import fr.wseduc.bus.BusAddress;
import fr.wseduc.rs.Get;
import fr.wseduc.rs.Post;
import fr.wseduc.security.ActionType;
import fr.wseduc.security.SecuredAction;
import fr.wseduc.webutils.I18n;
import fr.wseduc.webutils.http.BaseController;
import fr.wseduc.webutils.request.RequestUtils;
import io.vertx.core.Handler;
import io.vertx.core.eventbus.Message;
import io.vertx.core.eventbus.MessageConsumer;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;

import org.entcore.archive.services.ImportService;
import org.entcore.common.storage.Storage;
import org.entcore.common.user.UserUtils;

public class ImportController extends BaseController {
    private static final Logger log = LoggerFactory.getLogger(ImportController.class);

    private ImportService importService;
    private Storage storage;

    public ImportController(ImportService importService, Storage storage) {
        this.importService = importService;
        this.storage = storage;
    }

    @Get("/import/clear")
    public void clear(final HttpServerRequest request) {
        importService.clear();
        renderJson(request, new JsonObject().put("ok", true));
    }

    @Post("/import/upload")
    @SecuredAction(value = "", type = ActionType.RESOURCE)
    public void upload(final HttpServerRequest request) {
        UserUtils.getUserInfos(eb, request, user -> {
            request.pause();
            importService.isUserAlreadyImporting(user.getUserId())
            .onSuccess(isAlreadyImported -> {
                request.resume();
                if (isAlreadyImported) {
                    renderError(request);
                    log.error("[upload] User is already importing " + user.getUsername());
                } else {
                    importService.uploadArchive(request, user, handler -> {
                        if (handler.isLeft()) {
                            badRequest(request, handler.left().getValue());
                            log.error("[upload] User import failed " + user.getUsername() + " - " + handler.left().getValue());
                        } else {
                            renderJson(request, new JsonObject().put("importId", handler.right().getValue()));
                        }
                    });
                }
            })
            .onFailure(th -> {
                log.error("An error occurred while checking if user import is already running", th);
                renderError(request);
            });
        });
    }

    @Get("import/analyze/:importId")
    @SecuredAction(value = "", type = ActionType.RESOURCE)
    public void analyze(final HttpServerRequest request) {
        final String importId = request.params().get("importId");
        UserUtils.getUserInfos(eb, request, user -> {
            importService.analyzeArchive(user, importId, I18n.acceptLanguage(request), config, handler -> {
                if (handler.isLeft()) {
                    renderError(request, new JsonObject().put("error", handler.left().getValue()));
                    log.error("[analyze] Analyze import failed " + user.getUsername()+" - "+handler.left().getValue());
                } else {
                    renderJson(request, handler.right().getValue());
                }
            });
        });
    }

    @Get("import/delete/:importId")
    @SecuredAction(value = "", type = ActionType.RESOURCE)
    public void delete(final HttpServerRequest request) {
        final String importId = request.params().get("importId");
        importService.deleteArchive(importId);
        request.response().setStatusCode(200).end();
    }

    @Post("import/:importId/launch")
    @SecuredAction(value = "", type = ActionType.RESOURCE)
    public void launchImport(final HttpServerRequest request)
    {
        final String importId = request.params().get("importId");
        RequestUtils.bodyToJson(request, body ->
        {
            JsonObject apps = body.getJsonObject("apps");

            UserUtils.getUserInfos(eb, request, user ->
            {
                importService.launchImport(user.getUserId(), user.getLogin(), user.getUsername(), importId,
                    I18n.acceptLanguage(request), request.headers().get("Host"), apps);
                final String address = importService.getImportBusAddress(importId);
                final MessageConsumer<JsonObject> consumer = eb.consumer(address);

                final Handler<Message<JsonObject>> importHandler = event -> {
                    if ("ok".equals(event.body().getString("status"))) {
                        event.reply(new JsonObject().put("status", "ok"));
                        renderJson(request, event.body().getJsonObject("result"));
                    } else {
                        event.reply(new JsonObject().put("status", "error"));
                        renderError(request, event.body());
                        log.error("[launch] Launch import failed " + user.getUsername()+" - "+event.body().getString("message"));
                    }
                    consumer.unregister();
                };
                request.response().closeHandler(new Handler<Void>() {
                    @Override
                    public void handle(Void event) {
                        consumer.unregister();
                        if (log.isDebugEnabled()) {
                            log.debug("Unregister handler : " + address);
                        }
                    }
                });
                consumer.handler(importHandler);
            });
        });
    }

    @BusAddress("entcore.import")
    public void export(Message<JsonObject> message) {
        String action = message.body().getString("action", "");
        switch (action) {
            case "imported" :
                String importId = message.body().getString("importId");
                String app = message.body().getString("app");
                JsonObject rapport = message.body().getJsonObject("rapport");

                importService.imported(importId, app, rapport);
                break;
            case "import-file" :
                importFromFile(message);
                break;
            default: log.error("Archive : invalid action " + action);
        }
    }


    /**
     * Lance un import à partir d'une archive DÉJÀ déposée dans le répertoire d'import, au nom
     * du compte indiqué, et répond quand l'import est terminé.
     *
     * <p>Ce point d'entrée existe pour le module d'interopérabilité : un paquet d'échange
     * provenant d'un autre Open ENT est reconverti en archive, puis réinjecté ici. Il n'y avait
     * jusqu'ici aucun moyen de déclencher {@link ImportService#importFromFile} depuis le bus,
     * alors que la restauration groupée et la reprise de plate-forme s'en servent déjà en
     * interne : la seule alternative aurait été de réécrire le chemin d'import, c'est-à-dire de
     * dupliquer la logique de remappage d'identifiants de chaque module.
     *
     * <p>Corps attendu : {@code {importId, userId, userLogin, userName, locale, host}}.
     * {@code importId} est le nom du fichier dans le répertoire d'import, de la forme
     * {@code <millis>_<userId>} — forme imposée par la purge des archives.
     */
    private void importFromFile(Message<JsonObject> message) {
        final JsonObject body = message.body();
        final String importId = body.getString("importId");
        final String userId = body.getString("userId");
        if (importId == null || userId == null) {
            message.reply(new JsonObject().put("status", "error")
                    .put("message", "missing.importId.or.userId"));
            return;
        }
        final String userLogin = body.getString("userLogin", userId);
        final String userName = body.getString("userName", userLogin);
        final String locale = body.getString("locale", "fr");
        final String host = body.getString("host");
        final long timeout = body.getLong("timeout", 1800000L);

        // L'abonnement DOIT précéder l'appel : c'est cet événement, et lui seul, qui signale la
        // fin de l'import. Et le minuteur est la seule garantie de terminaison — un module muet
        // laisserait autrement l'appelant en attente indéfinie.
        final MessageConsumer<JsonObject> consumer =
                eb.consumer(importService.getImportBusAddress(importId));
        final java.util.concurrent.atomic.AtomicBoolean settled =
                new java.util.concurrent.atomic.AtomicBoolean(false);

        final long timer = vertx.setTimer(timeout, id -> {
            if (settled.compareAndSet(false, true)) {
                consumer.unregister();
                log.error("Archive : no import result for " + importId + " after " + timeout + "ms");
                message.reply(new JsonObject().put("status", "error")
                        .put("message", "import.timeout").put("importId", importId));
            }
        });

        consumer.handler(reply -> {
            reply.reply(new JsonObject().put("status", "ok"));
            if (!settled.compareAndSet(false, true)) {
                return; // le minuteur a déjà tranché
            }
            vertx.cancelTimer(timer);
            consumer.unregister();
            message.reply(new JsonObject()
                    .put("status", reply.body().getString("status", "error"))
                    .put("importId", importId)
                    .put("result", reply.body().getJsonObject("result", new JsonObject())));
        });

        importService.importFromFile(importId, userId, userLogin, userName, locale, host, config);
    }

}
