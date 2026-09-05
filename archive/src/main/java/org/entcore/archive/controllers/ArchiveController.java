/* Copyright © "Open Digital Education", 2014
 *
 * This program is published by "Open Digital Education".
 * You must indicate the name of the software and the company in any production /contribution
 * using the software and indicate on the home page of the software industry in question,
 * "powered by Open Digital Education" with a reference to the website: https://opendigitaleducation.com/.
 *
 * This program is free software, licensed under the terms of the GNU Affero General Public License
 * as published by the Free Software Foundation, version 3 of the License.
 *
 * You can redistribute this application and/or modify it since you respect the terms of the GNU Affero General Public License.
 * If you modify the source code and then use this modified source code in your creation, you must make available the source code of your modifications.
 *
 * You should have received a copy of the GNU Affero General Public License along with the software.
 * If not, please see : <http://www.gnu.org/licenses/>. Full compliance requires reading the terms of this license and following its directives.

 *
 */

package org.entcore.archive.controllers;

import fr.wseduc.bus.BusAddress;
import fr.wseduc.rs.Delete;
import fr.wseduc.rs.Get;
import fr.wseduc.rs.Post;
import fr.wseduc.security.ActionType;
import fr.wseduc.security.MfaProtected;
import fr.wseduc.security.SecuredAction;
import fr.wseduc.webutils.I18n;
import fr.wseduc.webutils.email.EmailSender;
import fr.wseduc.webutils.http.BaseController;
import fr.wseduc.webutils.http.Renders;
import fr.wseduc.webutils.request.RequestUtils;
import io.vertx.core.Future;
import io.vertx.core.eventbus.MessageConsumer;
import org.entcore.archive.Archive;
import org.entcore.archive.services.ExportService;
import org.entcore.archive.services.StructureExportService;
import org.entcore.archive.services.StructureImportService;
import org.entcore.archive.services.impl.DefaultStructureExportService;
import org.entcore.archive.services.impl.FileSystemExportService;
import org.entcore.archive.filters.StructureExportFilter;
import org.entcore.common.email.EmailFactory;
import org.entcore.common.events.EventStore;
import org.entcore.common.events.EventStoreFactory;
import org.entcore.common.http.filter.ResourceFilter;
import org.entcore.common.http.filter.SuperAdminFilter;
import org.entcore.common.http.request.JsonHttpServerRequest;
import org.entcore.common.notification.TimelineHelper;
import org.entcore.common.storage.Storage;
import org.entcore.common.user.DefaultFunctions;
import org.entcore.common.user.UserUtils;
import io.vertx.core.AsyncResult;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.eventbus.Message;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.vertx.java.core.http.RouteMatcher;


import java.security.PrivateKey;
import java.util.*;

import static fr.wseduc.webutils.Utils.getOrElse;
import static io.vertx.core.Future.succeededFuture;

public class ArchiveController extends BaseController {

	public static final String SIGNATURE_NAME = "archive.signature";

	private ExportService exportService;
	private StructureExportService structureExportService;
	/**
	 * Restauration groupée. Construit par le verticle {@link org.entcore.archive.Archive}, seul
	 * endroit où l'{@code ImportService} existe déjà : la restauration s'appuie dessus plutôt que
	 * de refaire un import à elle.
	 */
	private StructureImportService structureImportService;

	public void setStructureImportService(StructureImportService structureImportService) {
		this.structureImportService = structureImportService;
	}
	private EventStore eventStore;
	private Storage storage;
	private PrivateKey signKey;
	private boolean forceEncryption;

	private enum ArchiveEvent { ACCESS }

	public ArchiveController(Storage storage, PrivateKey signKey, boolean forceEncryption) {
		this.storage = storage;
		this.signKey = signKey;
		this.forceEncryption = forceEncryption;
	}

	@Override
	public void init(Vertx vertx, final JsonObject config, RouteMatcher rm,
			Map<String, fr.wseduc.webutils.security.SecuredAction> securedActions)
	{
		super.init(vertx, config, rm, securedActions);

		String exportPath = config.getString("export-path", System.getProperty("java.io.tmpdir"));

		EmailFactory emailFactory = EmailFactory.getInstance();
		EmailSender notification = config.getBoolean("send.export.email", false) ?
				emailFactory.getSender() : null;

		exportService = new FileSystemExportService(vertx, vertx.fileSystem(),
				eb, exportPath, null, notification, storage, new TimelineHelper(vertx, eb, config),
				signKey, forceEncryption, config.getJsonObject("module-versions", new JsonObject()), config.getBoolean("local-state", false));
		eventStore = EventStoreFactory.getFactory().getEventStore(Archive.class.getSimpleName());

		// Sauvegarde d'un établissement (super-admin) : un export personnel standard par compte
		// du groupe choisi, assemblé en un lot. Un lot resté bloqué (application demandée dont le
		// module ne répond jamais) est couvert par purgeStuckBatches ci-dessous, pas par un
		// contrôle des applications au lancement : /archive/export (export personnel standard)
		// n'en fait pas non plus.
		structureExportService = new DefaultStructureExportService(vertx, storage, exportPath, signKey,
				forceEncryption, config.getJsonObject("module-versions", new JsonObject()),
				config.getInteger("max-users-per-batch", 200), config.getBoolean("local-state", false));
		// StructureExportFilter doit résoudre structureId à partir d'un batchId (statut/téléchargement/
		// suppression d'un lot) pour autoriser les comptes ADMIN_COLLECTIVITE scopés.
		StructureExportFilter.setStructureExportService(structureExportService);

		Long periodicUserClear = config.getLong("periodicUserClear");

		if (periodicUserClear != null)
		{
			vertx.setPeriodic(periodicUserClear, new Handler<Long>()
			{
				@Override
				public void handle(Long event)
				{
					final long limit = System.currentTimeMillis() - config.getLong("userClearDelay", 3600000l);
          exportService.getUserExportInProgress().onFailure(th -> {
            log.error("An error occurred while fetching user exports in progress", th);
          }).onSuccess(entries -> {
            for (Map.Entry<String, Long> e : entries.entrySet())
            {
              if (e.getValue() == null || e.getValue() < limit)
              {
                exportService.removeUserExportInProgress(e.getKey());
              }
            }
          });
				}
			});
		}

		// Purge des lots de sauvegarde d'établissement restés bloqués (application demandée non
		// déployée malgré le contrôle au lancement, module tombé pendant le lot…), sur le même
		// principe que periodicUserClear ci-dessus pour les exports personnels.
		Long structureExportPurgePeriod = config.getLong("structureExportPurgePeriod");
		if (structureExportPurgePeriod != null) {
			vertx.setPeriodic(structureExportPurgePeriod, event ->
					structureExportService.purgeStuckBatches(config.getLong("structureExportMaxAge", 21600000L))
							.onFailure(th -> log.error("An error occurred while purging stuck structure export batches", th)));
		}
	}

	@Get("")
	@SecuredAction("archive.view")
	public void view(HttpServerRequest request) {
		renderView(request);
		eventStore.createAndStoreEvent(ArchiveEvent.ACCESS.name(), request);
	}

	@Post("/export")
	@SecuredAction("archive.export")
	public void export(final HttpServerRequest request) {
		UserUtils.getUserInfos(eb, request, user -> {
			if(user != null) {
				RequestUtils.bodyToJson(request, body -> {
					body.put("userId", user.getUserId());
					body.put("login", user.getLogin());
					initExport(request, body);
				});
			}
			else {
				unauthorized(request);
			}
		});
	}

	@Post("/export/user")
	@SecuredAction(value = "", type = ActionType.RESOURCE)
	@ResourceFilter(SuperAdminFilter.class)
	@MfaProtected()
	public void exportForUser(final HttpServerRequest request) {
		RequestUtils.bodyToJson(request, body -> {
			JsonObject apps = getOrElse(getOrElse(this.config.getJsonObject("publicConf"), new JsonObject())
					.getJsonObject("apps"), new JsonObject());
			JsonArray appsAsJsonArray = new JsonArray(new ArrayList(apps.getMap().keySet()));
			body.put("apps", appsAsJsonArray);
			initExport(request, body);
		});
	}

	private void initExport(final HttpServerRequest request, final JsonObject body) {
		final String login = body.getString("login");
		final String userId = body.getString("userId");
		log.info("Début d'export par l'utilisateur " + login);
		eb.request("entcore.export",
				new JsonObject()
						.put("action", "start")
						.put("userId", userId)
						.put("locale", I18n.acceptLanguage(request))
						.put("apps", body.getJsonArray("apps"))
						.put("exportDocuments", body.getBoolean("exportDocuments", true))
						.put("exportSharedResources", body.getBoolean("exportSharedResources", true))
						.put("request", new JsonObject().put("headers", new JsonObject().put("Host", request.getHeader("Host")))),
				new Handler<AsyncResult<Message<JsonObject>>>() {
					@Override
					public void handle(AsyncResult<Message<JsonObject>> res) {
						if(res.succeeded() == true) {
							JsonObject msg = res.result().body();
							if(msg.getString("status").equals("ok")) {
								log.info("Fin d'export pour l'utilisateur " + login + " exportId: " + msg.getString("exportId"));
								renderJson(request, new JsonObject().put("message", "export.in.progress").put("exportId", msg.getString("exportId")));
							} else {
								log.info("Echec de l'export pour l'utilisateur " + login + " exportId: " + msg.getString("exportId"));
								badRequest(request, msg.getString("message"));
							}
						} else {
							log.info("Echec de l'export pour l'utilisateur " + login);
							badRequest(request, res.cause().getMessage());
						}
					}
				});
	}

	@Get("/export/verify")
	@SecuredAction(value = "", type = ActionType.AUTHENTICATED)
	public void verifyUserExport(final HttpServerRequest request)
	{
		UserUtils.getUserInfos(eb, request, user -> {
			if(user != null) {
				exportService.userExportExists(user, new Handler<Boolean>()
				{
					@Override
					public void handle(Boolean exists)
					{
						renderJson(request, new JsonObject().put("exists", exists.booleanValue()));
					}
				});
			}
			else {
				unauthorized(request);
			}
		});
	}

	@Get("/export/verify/:exportId")
	@SecuredAction(value = "", type = ActionType.RESOURCE)
	public void verifyExport(final HttpServerRequest request) {
		final String exportId = request.params().get("exportId");
		exportService.waitingExport(exportId, new Handler<Boolean>() {
			@Override
			public void handle(Boolean event) {
				if (Boolean.TRUE.equals(event)) {
					log.debug("waiting export true");
					final String address = exportService.getExportBusAddress(exportId);
					final MessageConsumer<JsonObject> consumer = eb.consumer(address);
					final Handler<Message<JsonObject>> downloadHandler = new Handler<Message<JsonObject>>() {
						@Override
						public void handle(Message<JsonObject> event) {
							String path = event.body().getString("destZip");
							if ("ok".equals(event.body().getString("status")) && path != null) {
								log.debug("Download export " + exportId);
								event.reply(new JsonObject().put("status", "ok"));
								verifyExport(request, exportId);
							} else {
								event.reply(new JsonObject().put("status", "error"));
								renderError(request, event.body());
							}
							consumer.unregister();
						}
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
					consumer.handler(downloadHandler);
				} else {
					log.debug("waiting export false");
					verifyExport(request, exportId);
				}
			}
		});
	}

	private void verifyExport(final HttpServerRequest request, final String exportId) {
		exportService.setDownloadInProgress(exportId)
        .onSuccess(e -> {
          storage.fileStats(exportId, ar -> {
            if (ar.succeeded() && ar.result().getSizeInBytes() > 0 && request.response().getStatusCode() == 200) {
              renderJson(request, new JsonObject().put("status", "ok"));
            } else if (!request.response().ended()) {
              notFound(request);
            }
          });
        })
      .onFailure(th -> {
        log.error("An error occurred while verifying download in progress", th);
        renderError(request);
      });
	}

	@Get("/export/:exportId")
	@SecuredAction(value = "", type = ActionType.RESOURCE)
	public void downloadExport(final HttpServerRequest request)
	{
		final String exportId = request.params().get("exportId");
		storage.sendFile(exportId, exportId + ".zip", request, false, null, new Handler<AsyncResult<Void>>()
		{
			@Override
			public void handle(AsyncResult<Void> event)
			{
				if (event.succeeded() && request.response().getStatusCode() == 200)
				{
					exportService.deleteExport(exportId);
				}
				else if (!request.response().ended())
				{
					notFound(request);
				}
			}
		});
	}

  @Get("/export/clear")
  @ResourceFilter(SuperAdminFilter.class)
  @SecuredAction(value = "", type = ActionType.RESOURCE)
  @MfaProtected()
  public void clearUserExports(final HttpServerRequest request)
  {
    exportService.getUserExportInProgress()
      .map(Map::keySet)
      .onSuccess(keys -> {
        for (String key : keys) {
          exportService.clearUserExport(key);
        }
      });
    Renders.ok(request);
  }

	/**
	 * Supervision admin : liste tous les exports actuellement suivis (en cours, prêts à
	 * télécharger, ou en erreur), tous utilisateurs confondus — y compris ceux lancés depuis
	 * l'application "Mes données" (front natif), pas seulement via un outil d'administration.
	 * Même protection que /export/clear (SuperAdmin), aucune autre vue équivalente n'existant.
	 */
	@Get("/export/admin/list")
	@ResourceFilter(SuperAdminFilter.class)
	@SecuredAction(value = "", type = ActionType.RESOURCE)
	@MfaProtected()
	public void listAllExports(final HttpServerRequest request)
	{
		exportService.getAllExportsStatus()
			.onSuccess(list -> renderJson(request, new JsonArray(list)))
			.onFailure(th -> {
				log.error("An error occurred while listing all exports for admin supervision", th);
				renderError(request);
			});
	}

	@Delete("/export/clear/user/:userId")
	@ResourceFilter(SuperAdminFilter.class)
	@SecuredAction(value = "", type = ActionType.RESOURCE)
	@MfaProtected()
	public void clearUserExport(final HttpServerRequest request)
	{
		exportService.clearUserExport(request.params().get("userId"));
		Renders.ok(request);
	}

	@BusAddress("entcore.export")
	public void export(Message<JsonObject> message)
	{
		String action = message.body().getString("action", "");
		switch (action)
		{
			case "start":
				JsonObject body = message.body();

				String userId = body.getString("userId");
				String locale = body.getString("locale");
				JsonArray apps = body.getJsonArray("apps");
				JsonArray resourcesIds = body.getJsonArray("resourcesIds");
				Boolean synchroniseReply = body.getBoolean("synchroniseReply", false);
				Boolean exportDocuments = body.getBoolean("exportDocuments", true);
				Boolean exportSharedResources = body.getBoolean("exportSharedResources", true);
				Boolean force = body.getBoolean("force", false);
				HttpServerRequest request = new JsonHttpServerRequest(body.getJsonObject("request", new JsonObject()));

				if(userId == null || apps == null || locale == null)
				{
					message.reply(new JsonObject().put("status", "error").put("message", "Missing arguments userId or apps or locale"));
					break;
				}

				UserUtils.getUserInfos(eb, userId, user ->
				{
          final Future<Void> future;
					if(Boolean.TRUE.equals(force)){
						future = exportService.removeUserExportInProgress(userId);
					} else {
            future = succeededFuture();
          }
          future.onFailure(th -> {
            log.error("An error occurred while remove user export in progress: " + userId, th);
              message.reply(new JsonObject().put("status", "error").put("message", "internal error"));
            })
            .onSuccess(e -> {
              exportService.export(user, locale, apps, resourcesIds, exportDocuments.booleanValue(), exportSharedResources.booleanValue(), request,
                event -> {
                  if (event.isRight()) {
                    String exportId = event.right().getValue();

                    if (!Boolean.TRUE.equals(synchroniseReply)) {
                      message.reply(
                        new JsonObject()
                          .put("status", "ok")
                          .put("exportId", exportId)
                          .put("exportPath", exportId + ".zip")
                      );
                    } else {
                      final String address = exportService.getExportBusAddress(exportId);

                      final MessageConsumer<JsonObject> consumer = eb.consumer(address);
                      consumer.handler(event1 -> {
                        event1.reply(new JsonObject().put("status", "ok").put("sendNotifications", false));
                        consumer.unregister();

                        message.reply(
                          new JsonObject()
                            .put("status", "ok")
                            .put("exportId", exportId)
                            .put("exportPath", exportId + ".zip")
                        );
                      });
                    }
                  } else {
                    message.reply(new JsonObject().put("status", "error").put("message", event.left().getValue()));
                  }
                });
            });
				});
				break;
			case "delete":
				String exportId = message.body().getString("exportId");

				if(exportId == null)
					message.reply(new JsonObject().put("status", "error").put("message", "Missing argument userId"));
				else
				{
					exportService.deleteExport(exportId);
					message.reply(new JsonObject().put("status", "ok"));
				}
				break;
			case "exported" :
				// Une réponse "exported" concerne soit un export personnel, soit un compte
				// d'un lot de sauvegarde d'établissement — distingués par le préfixe de
				// l'exportId synthétique que StructureExportService.launch() a construit.
				final String exportedId = message.body().getString("exportId");
				if (StructureExportService.isBatchExportId(exportedId)) {
					structureExportService.onAppExportDone(
							exportedId,
							message.body().getString("status"),
							message.body().getString("app")
					);
				} else {
					exportService.onExportDone(
							exportedId,
							message.body().getString("status"),
							message.body().getString("locale", "fr"),
							message.body().getString("host", config.getString("host", "")),
							message.body().getString("app")
					);
				}
				break;
			default: log.error("Archive : invalid action " + action);
		}
	}

	@Get("/export")
	@SecuredAction(value = "", type = ActionType.AUTHENTICATED)
	public void unitaryExport(final HttpServerRequest request)
	{
		final String application = request.params().get("application");
		final String resourceId = request.params().get("resourceId");

		if (application == null || resourceId == null)
				badRequest(request);
		else
		{
			UserUtils.getUserInfos(eb, request, user ->
			{
				if(user != null)
				{
					eb.request("entcore.export",
						new JsonObject()
							.put("action", "start")
							.put("userId", user.getUserId())
							.put("locale", I18n.acceptLanguage(request))
							.put("apps", new JsonArray().add(application))
							.put("resourcesIds", new JsonArray().add(resourceId)),
						new Handler<AsyncResult<Message<JsonObject>>>()
					{
						@Override
						public void handle(AsyncResult<Message<JsonObject>> res)
						{
							if(res.succeeded() == true)
							{
								JsonObject msg = res.result().body();
								if(msg.getString("status").equals("ok"))
									renderJson(request, new JsonObject().put("message", "export.in.progress").put("exportId", msg.getString("exportId")));
								else
									badRequest(request, msg.getString("message"));
							}
							else
								badRequest(request, res.cause().getMessage());
						}
					});
				}
				else
					unauthorized(request);
			});
		}
	}

	// ─── Sauvegarde d'un établissement (super-admin, ou ADMIN_COLLECTIVITE scopé) ────────────
	//
	// entcore n'exporte que par compte : ces routes lancent un export personnel par membre
	// du groupe choisi et l'assemblent en un lot. Réservées au super-administrateur avec MFA,
	// comme /export/user dont elles partagent le même principe (export « pour un autre compte »
	// que celui qui appelle) — voir StructureExportFilter pour le périmètre exact. Un compte
	// ADMIN_COLLECTIVITE (techniciens/ouvriers de service, cf. ability.ts dashboard) n'a
	// vocation à voir aucune donnée pédagogique : ses exports sont restreints à des modules non
	// pédagogiques, quels que soient les modules proposés par le client.
	private static final Set<String> NON_PEDAGOGICAL_MODULES = new HashSet<>(Arrays.asList("workspace"));

	@Post("/export/structure")
	@SecuredAction(value = "", type = ActionType.RESOURCE)
	@ResourceFilter(StructureExportFilter.class)
	@MfaProtected()
	public void exportStructure(final HttpServerRequest request)
	{
		RequestUtils.bodyToJson(request, body -> {
			final String structureId = body.getString("structureId");
			final String groupId = body.getString("groupId");
			final JsonArray apps = body.getJsonArray("apps");
			if (structureId == null || groupId == null || apps == null || apps.isEmpty())
			{
				badRequest(request, "structure.export.missing.arguments");
				return;
			}
			UserUtils.getUserInfos(eb, request, user -> {
				if (user == null)
				{
					unauthorized(request);
					return;
				}
				final Map<String, org.entcore.common.user.UserInfos.Function> functions = user.getFunctions();
				final boolean isSuperAdmin = functions != null && functions.containsKey(DefaultFunctions.SUPER_ADMIN);
				if (!isSuperAdmin)
				{
					for (Object app : apps)
					{
						if (!NON_PEDAGOGICAL_MODULES.contains(String.valueOf(app)))
						{
							forbidden(request, "structure.export.module.not.allowed");
							return;
						}
					}
				}
				structureExportService.launch(user, structureId, groupId, apps,
						body.getBoolean("exportDocuments", true),
						body.getBoolean("exportSharedResources", true),
						I18n.acceptLanguage(request),
						request.headers().get("Host"))
					.onSuccess(batchId -> renderJson(request, new JsonObject()
							.put("message", "structure.export.in.progress")
							.put("batchId", batchId)))
					.onFailure(th -> badRequest(request, th.getMessage()));
			});
		});
	}

	// Chemin à 3 segments (pas "/export/structure") : "/export/:exportId" (téléchargement d'un
	// export personnel, ci-dessus) a la même forme à 2 segments que "/export/structure" et
	// l'intercepterait (":exportId" == "structure").
	@Get("/export/structure/admin/list")
	@SecuredAction(value = "", type = ActionType.RESOURCE)
	@ResourceFilter(SuperAdminFilter.class)
	@MfaProtected()
	public void listStructureExports(final HttpServerRequest request)
	{
		structureExportService.getAllBatchesStatus()
			.onSuccess(list -> renderJson(request, new JsonArray(list)))
			.onFailure(th -> {
				log.error("An error occurred while listing structure export batches", th);
				renderError(request);
			});
	}

	@Get("/export/structure/:batchId/status")
	@SecuredAction(value = "", type = ActionType.RESOURCE)
	@ResourceFilter(StructureExportFilter.class)
	@MfaProtected()
	public void structureExportStatus(final HttpServerRequest request)
	{
		final String batchId = request.params().get("batchId");
		structureExportService.status(batchId)
			.onSuccess(status -> {
				if (status == null) notFound(request);
				else renderJson(request, status);
			})
			.onFailure(th -> renderError(request));
	}

	@Get("/export/structure/:batchId")
	@SecuredAction(value = "", type = ActionType.RESOURCE)
	@ResourceFilter(StructureExportFilter.class)
	@MfaProtected()
	public void downloadStructureExport(final HttpServerRequest request)
	{
		final String batchId = request.params().get("batchId");
		structureExportService.status(batchId).onSuccess(status -> {
			if (status == null || !"completed".equals(status.getString("status")))
			{
				notFound(request);
				return;
			}
			storage.sendFile(batchId, "etablissement-" + batchId + ".zip", request, false, null,
					event -> {
						if (!event.succeeded() && !request.response().ended())
						{
							notFound(request);
						}
					});
		}).onFailure(th -> renderError(request));
	}

	@Delete("/export/structure/:batchId")
	@SecuredAction(value = "", type = ActionType.RESOURCE)
	@ResourceFilter(StructureExportFilter.class)
	@MfaProtected()
	public void deleteStructureExport(final HttpServerRequest request)
	{
		final String batchId = request.params().get("batchId");
		structureExportService.deleteBatch(batchId)
			.onSuccess(v -> Renders.ok(request))
			.onFailure(th -> renderError(request));
	}


	// ─── Restauration groupée (lot d'établissement) ───────────────────────────
	// Symétrique de /export/structure. Réservé au super-administrateur : contrairement à
	// l'export, la restauration ÉCRIT dans les comptes d'autres personnes — ce n'est pas un
	// pouvoir qui se délègue à un périmètre d'établissement.

	/** Dépose le .zip d'un lot. Répond l'identifiant de restauration, à analyser ensuite. */
	@Post("/import/structure/upload")
	@SecuredAction(value = "", type = ActionType.RESOURCE)
	@ResourceFilter(SuperAdminFilter.class)
	@MfaProtected()
	public void uploadStructureImport(final HttpServerRequest request)
	{
		UserUtils.getUserInfos(eb, request, user -> {
			if (user == null) { unauthorized(request); return; }
			structureImportService.uploadBatch(request, user, res -> {
				if (res.isLeft()) {
					badRequest(request, res.left().getValue());
				} else {
					renderJson(request, new JsonObject().put("restoreId", res.right().getValue()));
				}
			});
		});
	}

	/** Inventaire du lot, compte par compte, sans rien restaurer. */
	@Get("/import/structure/:restoreId/analyze")
	@SecuredAction(value = "", type = ActionType.RESOURCE)
	@ResourceFilter(SuperAdminFilter.class)
	@MfaProtected()
	public void analyzeStructureImport(final HttpServerRequest request)
	{
		structureImportService.analyze(request.params().get("restoreId"))
			.onSuccess(analysis -> renderJson(request, analysis))
			.onFailure(th -> badRequest(request, th.getMessage()));
	}

	/** Lance la restauration. Refusée tant que l'analyse n'a pas déclaré le lot restaurable. */
	@Post("/import/structure/:restoreId/launch")
	@SecuredAction(value = "", type = ActionType.RESOURCE)
	@ResourceFilter(SuperAdminFilter.class)
	@MfaProtected()
	public void launchStructureImport(final HttpServerRequest request)
	{
		final String restoreId = request.params().get("restoreId");
		UserUtils.getUserInfos(eb, request, user -> {
			if (user == null) { unauthorized(request); return; }
			structureImportService.launch(restoreId, user, I18n.acceptLanguage(request),
					request.headers().get("Host"))
				.onSuccess(v -> renderJson(request, new JsonObject()
						.put("message", "structure.import.in.progress")
						.put("restoreId", restoreId)))
				.onFailure(th -> badRequest(request, th.getMessage()));
		});
	}

	/** Avancement : comptes traités, résultat par compte, statut global. */
	@Get("/import/structure/:restoreId/status")
	@SecuredAction(value = "", type = ActionType.RESOURCE)
	@ResourceFilter(SuperAdminFilter.class)
	@MfaProtected()
	public void statusStructureImport(final HttpServerRequest request)
	{
		structureImportService.status(request.params().get("restoreId"))
			.onSuccess(state -> {
				if (state == null) notFound(request, "structure.import.unknown");
				else renderJson(request, state);
			})
			.onFailure(th -> badRequest(request, th.getMessage()));
	}

	/** Supprime l'espace de travail d'une restauration. N'annule pas ce qui a déjà été restauré. */
	@Delete("/import/structure/:restoreId")
	@SecuredAction(value = "", type = ActionType.RESOURCE)
	@ResourceFilter(SuperAdminFilter.class)
	@MfaProtected()
	public void deleteStructureImport(final HttpServerRequest request)
	{
		structureImportService.delete(request.params().get("restoreId"))
			.onSuccess(v -> renderJson(request, new JsonObject().put("status", "ok")))
			.onFailure(th -> badRequest(request, th.getMessage()));
	}

	/** Chemin à 3 segments, comme /export/structure/admin/list : vue plateforme. */
	@Get("/import/structure/admin/list")
	@SecuredAction(value = "", type = ActionType.RESOURCE)
	@ResourceFilter(SuperAdminFilter.class)
	@MfaProtected()
	public void listStructureImports(final HttpServerRequest request)
	{
		structureImportService.getAllRestoresStatus()
			.onSuccess(list -> renderJson(request, new JsonArray(list)))
			.onFailure(th -> {
				log.error("An error occurred while listing structure import restores", th);
				renderError(request);
			});
	}

}
