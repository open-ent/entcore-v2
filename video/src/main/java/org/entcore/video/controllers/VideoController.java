/* Copyright © "Open Digital Education", 2026
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
 */

package org.entcore.video.controllers;

import fr.wseduc.rs.Get;
import fr.wseduc.rs.Post;
import fr.wseduc.security.ActionType;
import fr.wseduc.security.SecuredAction;
import fr.wseduc.webutils.http.BaseController;
import fr.wseduc.webutils.request.RequestUtils;
import io.vertx.core.AsyncResult;
import io.vertx.core.Handler;
import io.vertx.core.eventbus.EventBus;
import io.vertx.core.eventbus.Message;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import org.entcore.common.events.EventStore;
import org.entcore.common.storage.Storage;
import org.entcore.common.user.UserInfos;
import org.entcore.video.services.VideoEncodingService;
import org.entcore.video.services.VideoJobStore;

import java.io.File;
import java.nio.file.Files;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static org.entcore.common.user.UserUtils.getUserInfos;

/**
 * DRAFT / esquisse — implémente le contrat d'API déjà consommé par le frontend
 * (infra-front: VideoUploadService.ts, et son équivalent moderne
 * edifice-frontend-framework: video/Service.ts) :
 *
 *   GET  /video/conf/public     -> déjà couvert par ConfController (voir Video.java),
 *                                  pas réimplémenté ici.
 *   POST /video/encode?captation=<bool>&duration=<ms>   (multipart, champ "file")
 *   GET  /video/status/:id
 *   POST /video/event/save      (JSON)
 *
 * Ce contrôleur n'a pas été buildé/testé en conditions réelles (pas de nœud "video"
 * déployé dans ce fork, cf. discussion). Les TODO marquent les points à valider avant
 * un premier déploiement, notamment :
 *   - l'intégration quota (le service "quota" existe déjà côté workspace mais n'est pas
 *     branché ici — cf. commit "feat: quota service" sur libs/entcore) ;
 *   - la génération de vignette (pas faite ici, WorkspaceController a un fallback qui
 *     sert le fichier original si aucune vignette n'existe) ;
 *   - la présence réelle de ffmpeg dans l'image du conteneur du module.
 */
public class VideoController extends BaseController {

	private static final Logger log = LoggerFactory.getLogger(VideoController.class);

	public static final String RIGHT_CAPTURE = "video.capture";
	public static final String RIGHT_UPLOAD = "video.upload";
	private static final String WORKSPACE_BUS_ADDRESS = "org.entcore.workspace";
	private static final String EVENT_TYPE_VIDEO_STREAM = "VIDEO_EVENT_STREAM";

	private final Storage storage;
	private final VideoEncodingService encodingService;
	private final VideoJobStore jobStore;
	private final EventStore eventStore;
	private final EventBus eb;

	public VideoController(Storage storage, VideoEncodingService encodingService, VideoJobStore jobStore,
							EventStore eventStore, EventBus eb) {
		this.storage = storage;
		this.encodingService = encodingService;
		this.jobStore = jobStore;
		this.eventStore = eventStore;
		this.eb = eb;
	}

	// -----------------------------------------------------------------------------------
	// Droits WORKFLOW "video.capture" / "video.upload"
	//
	// Le frontend n'appelle qu'UNE seule route pour les deux usages (POST /video/encode,
	// le paramètre "captation" distingue webcam vs fichier local), or @SecuredAction est
	// posée sur une méthode = une seule action possible. Ces deux routes ne servent donc
	// qu'à faire connaître les deux droits au scanner de SecuredAction (donc à l'admin et
	// à la table des droits) ; c'est /encode qui fait la vérification effective au moment
	// de la requête via hasAuthorizedAction(...). À simplifier en un seul droit générique
	// "video.publish" si cette distinction fine s'avère inutile en pratique.
	// -----------------------------------------------------------------------------------

	@Get("/access/capture")
	@SecuredAction(value = RIGHT_CAPTURE, type = ActionType.WORKFLOW)
	public void probeCaptureRight(final HttpServerRequest request) {
		renderJson(request, new JsonObject().put("granted", true));
	}

	@Get("/access/upload")
	@SecuredAction(value = RIGHT_UPLOAD, type = ActionType.WORKFLOW)
	public void probeUploadRight(final HttpServerRequest request) {
		renderJson(request, new JsonObject().put("granted", true));
	}

	@Post("/encode")
	@SecuredAction(value = "video.publish", type = ActionType.AUTHENTICATED)
	public void encode(final HttpServerRequest request) {
		final boolean captation = "true".equalsIgnoreCase(request.params().get("captation"));
		final String durationParam = request.params().get("duration");

		// on met le flux en pause le temps de résoudre l'utilisateur/droits de manière
		// async, comme WorkspaceController#addDocument, pour ne pas perdre le body.
		request.pause();
		getUserInfos(eb, request, user -> {
			if (user == null) {
				request.resume();
				unauthorized(request);
				return;
			}
			final String requiredRight = captation ? RIGHT_CAPTURE : RIGHT_UPLOAD;
			if (!hasAuthorizedAction(user, requiredRight)) {
				request.resume();
				forbidden(request, "video." + (captation ? "capture" : "upload") + ".forbidden");
				return;
			}

			final JsonObject publicConf = config.getJsonObject("publicConf", new JsonObject());
			final long maxSizeBytes = publicConf.getInteger("max-videosize-mbytes", 50) * 1024L * 1024L;

			// Le client (recordMaxTime) borne déjà la durée d'un enregistrement webcam,
			// mais ça reste une limite côté client : on la revalide ici. duration n'est
			// envoyé que pour la captation (cf. VideoUploadService.upload()).
			if (captation && durationParam != null) {
				final long maxDurationMs = publicConf.getInteger("max-videoduration-minutes", 3) * 60_000L;
				try {
					if (Long.parseLong(durationParam) > maxDurationMs) {
						request.resume();
						badRequest(request, "video.file.too.large");
						return;
					}
				} catch (NumberFormatException ignore) {
					// paramètre malformé : on laisse ffmpeg/la suite du pipeline trancher.
				}
			}

			request.resume();
			storage.writeUploadFile(request, maxSizeBytes, rawUploaded -> {
				if (!"ok".equals(rawUploaded.getString("status"))) {
					badRequest(request, rawUploaded.getString("message", "video.upload.error"));
					return;
				}

				// storage.writeUploadFile extrait déjà le nom du fichier depuis l'en-tête
				// Content-Disposition de la partie multipart "file" (cf.
				// formData.append("file", file, filename) côté VideoUploadService.ts).
				final String filename = rawUploaded.getJsonObject("metadata", new JsonObject())
						.getString("filename", "video");

				if (!captation && !hasAllowedExtension(filename)) {
					storage.removeFile(rawUploaded.getString("_id"), r -> {});
					badRequest(request, "video.extension.forbidden");
					return;
				}

				final String processId = jobStore.createJob(user.getUserId());
				// 202 Accepted immédiat : le client bascule alors en polling sur
				// GET /video/status/:id (cf. VideoUploadService.upload()).
				request.response().setStatusCode(202).end(new JsonObject()
						.put("processid", processId)
						.put("state", VideoJobStore.STATE_RUNNING)
						.encode());

				runEncodingPipeline(processId, rawUploaded.getString("_id"), filename, user);
			});
		});
	}

	/**
	 * Runs after the response has already been sent (202). Downloads the raw upload to a
	 * local tmp file, transcodes it, re-ingests the result into storage, then registers it
	 * as a Workspace document over the event bus (@BusAddress("org.entcore.workspace"),
	 * action "addDocument" — see WorkspaceController#workspaceEventBusHandler).
	 */
	private void runEncodingPipeline(String processId, String rawStorageId, String filename, UserInfos user) {
		final File tmpDir = new File(System.getProperty("java.io.tmpdir"), "video-encode-" + UUID.randomUUID());
		tmpDir.mkdirs();
		final File inputFile = new File(tmpDir, "input");
		final File outputFile = new File(tmpDir, "output.mp4");

		storage.readFile(rawStorageId, buffer -> {
			try {
				Files.write(inputFile.toPath(), buffer.getBytes());
			} catch (Exception e) {
				log.error("[VideoController] cannot write tmp input file", e);
				failJob(processId, "video.encode.error", rawStorageId, tmpDir);
				return;
			}

			encodingService.encode(inputFile, outputFile).onComplete(encodeRes -> {
				if (encodeRes.failed()) {
					log.error("[VideoController] encode failed for job " + processId, encodeRes.cause());
					failJob(processId, "video.encode.error", rawStorageId, tmpDir);
					return;
				}

				storage.writeFsFile(outputFile.getAbsolutePath(), encodedUploaded -> {
					if (!"ok".equals(encodedUploaded.getString("status"))) {
						failJob(processId, "video.encode.error", rawStorageId, tmpDir);
						return;
					}
					final JsonObject metadata = encodedUploaded.getJsonObject("metadata", new JsonObject())
							.put("filename", withMp4Extension(filename))
							.put("content-type", "video/mp4");
					encodedUploaded.put("metadata", metadata);

					registerInWorkspace(encodedUploaded, filename, user, videoWorkspaceId -> {
						// Le fichier brut (webm/mov/...) n'a plus d'usage une fois l'mp4
						// encodé publié dans Workspace : on l'efface pour ne pas doubler
						// le stockage. À retirer si un historique du brut est utile.
						storage.removeFile(rawStorageId, r -> {});
						deleteQuietly(tmpDir);

						if (videoWorkspaceId == null) {
							jobStore.fail(processId, "video.publish.error");
							return;
						}
						final long size = outputFile.length();
						jobStore.succeed(processId, encodedUploaded.getString("_id"), size, videoWorkspaceId);
					});
				});
			});
		});
	}

	private void registerInWorkspace(JsonObject uploaded, String name, UserInfos user,
									  Consumer<String> resultIdConsumer) {
		final JsonObject document = new JsonObject()
				.put("owner", user.getUserId())
				.put("ownerName", user.getUsername());
		final JsonObject body = new JsonObject()
				.put("action", "addDocument")
				.put("uploaded", uploaded)
				.put("document", document)
				.put("name", name)
				.put("application", "video");

		final Handler<AsyncResult<Message<JsonObject>>> replyHandler = reply -> {
			if (reply.failed() || !"ok".equals(reply.result().body().getString("status"))) {
				log.error("[VideoController] addDocument via eventbus failed: "
						+ (reply.failed() ? reply.cause() : reply.result().body()));
				resultIdConsumer.accept(null);
				return;
			}
			resultIdConsumer.accept(reply.result().body().getString("_id"));
		};
		eb.request(WORKSPACE_BUS_ADDRESS, body, replyHandler);
	}

	@Get("/status/:id")
	@SecuredAction(value = "video.status", type = ActionType.AUTHENTICATED)
	public void status(final HttpServerRequest request) {
		final String processId = request.params().get("id");
		getUserInfos(eb, request, user -> {
			if (user == null) {
				unauthorized(request);
				return;
			}
			final JsonObject job = jobStore.get(processId);
			if (job == null) {
				notFound(request);
				return;
			}
			if (!user.getUserId().equals(job.getString("ownerId"))) {
				forbidden(request);
				return;
			}
			final String state = job.getString("state");
			final int httpStatus = VideoJobStore.STATE_RUNNING.equals(state) ? 202 : 201;
			// axios enveloppe automatiquement ce corps dans `.data` côté client
			// (cf. UploadResult dans VideoUploadService.ts) : on renvoie donc directement
			// l'objet job, sans double-imbrication, en ôtant le champ interne "ownerId".
			final JsonObject responseBody = job.copy();
			responseBody.remove("ownerId");
			request.response().setStatusCode(httpStatus).end(responseBody.encode());
		});
	}

	@Post("/event/save")
	@SecuredAction(value = "video.event.save", type = ActionType.AUTHENTICATED)
	public void saveEvent(final HttpServerRequest request) {
		getUserInfos(eb, request, user -> {
			if (user == null) {
				unauthorized(request);
				return;
			}
			RequestUtils.bodyToJson(request, body -> {
				// Alimente le même flux d'événements que le "TODO: VIDEO_EVENT_STREAM"
				// laissé dans WorkspaceController#getDocumentFile ; à terme le module
				// "stats" (fr.wseduc~stats, cf. migration 018-add-source-column-in-
				// tableau-video.sql) est déjà prêt à exploiter ce type d'événement.
				eventStore.createAndStoreEvent(EVENT_TYPE_VIDEO_STREAM, user, body);
				ok(request);
			});
		});
	}

	// --------------------------------------------------------------------------------

	private boolean hasAuthorizedAction(UserInfos user, String action) {
		return user.getAuthorizedActions() != null
				&& user.getAuthorizedActions().stream().anyMatch(a -> action.equals(a.getName()));
	}

	private boolean hasAllowedExtension(String filename) {
		final List<String> accepted = config.getJsonObject("publicConf", new JsonObject())
				.getJsonArray("accept-videoupload-extensions", new JsonArray())
				.getList();
		final int dot = filename.lastIndexOf('.');
		if (dot < 0) {
			return false;
		}
		final String ext = filename.substring(dot + 1).toUpperCase();
		return accepted.stream().anyMatch(e -> String.valueOf(e).equalsIgnoreCase(ext));
	}

	private String withMp4Extension(String filename) {
		final int dot = filename.lastIndexOf('.');
		return (dot < 0 ? filename : filename.substring(0, dot)) + ".mp4";
	}

	private void failJob(String processId, String code, String rawStorageId, File tmpDir) {
		jobStore.fail(processId, code);
		storage.removeFile(rawStorageId, r -> {});
		deleteQuietly(tmpDir);
	}

	private void deleteQuietly(File dir) {
		final File[] files = dir.listFiles();
		if (files != null) {
			for (File f : files) {
				f.delete();
			}
		}
		dir.delete();
	}
}
