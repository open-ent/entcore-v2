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

package org.entcore.archive.services.impl;

import fr.wseduc.webutils.security.RSA;
import io.vertx.core.CompositeFuture;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.eventbus.EventBus;
import io.vertx.core.file.FileSystem;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.core.shareddata.AsyncMap;
import io.vertx.core.Future;
import org.entcore.archive.controllers.ArchiveController;
import org.entcore.archive.services.StructureExportService;
import org.entcore.common.neo4j.Neo4j;
import org.entcore.common.storage.Storage;
import org.entcore.common.user.UserInfos;
import org.entcore.common.user.UserUtils;
import org.entcore.common.utils.StringUtils;
import org.entcore.common.utils.Zip;

import java.io.File;
import java.security.PrivateKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.Deflater;

import static io.vertx.core.Future.failedFuture;
import static io.vertx.core.Future.succeededFuture;

/**
 * Voir {@link StructureExportService}. Un lot ({@code batchId}) publie un export personnel
 * standard (action {@code "export"} sur le bus {@code user.repository}) pour chaque compte du
 * groupe, avec un {@code exportId} synthétique {@code struct-<batchId>_<userId>} — c'est ce
 * préfixe qui permet à {@link ArchiveController} de distinguer, sur le bus {@code entcore.export}
 * partagé avec les exports personnels, les réponses qui concernent un lot.
 *
 * L'état d'un lot (comptes attendus, applications reçues par compte, statut global) vit dans une
 * AsyncMap dédiée, entièrement séparée de {@code userExportInProgress}/{@code userExport}
 * (FileSystemExportService) : un compte du groupe peut donc démarrer SON PROPRE export personnel
 * pendant qu'il est inclus dans un lot, sans collision.
 */
public class DefaultStructureExportService implements StructureExportService {

	private static final Logger log = LoggerFactory.getLogger(DefaultStructureExportService.class);

	private static final String BATCH_MANIFEST_NAME = "Batch-Manifest.json";
	private static final String USER_MANIFEST_NAME = "Manifest.json";
	private static final String BATCH_LOCK_PREFIX = "STRUCT_EXPORT_LOCK";
	private static final long BATCH_LOCK_TIMEOUT = 60000L;

	private final Vertx vertx;
	private final FileSystem fs;
	private final EventBus eb;
	private final Storage storage;
	private final String exportPath;
	private final PrivateKey signKey;
	private final boolean forceEncryption;
	private final JsonObject moduleVersions;
	private final int maxUsersPerBatch;

	private AsyncMap<String, JsonObject> batches;

	public DefaultStructureExportService(Vertx vertx, Storage storage, String exportPath, PrivateKey signKey,
			boolean forceEncryption, JsonObject moduleVersions, int maxUsersPerBatch, boolean localState) {
		this.vertx = vertx;
		this.fs = vertx.fileSystem();
		this.eb = vertx.eventBus();
		this.storage = storage;
		this.exportPath = exportPath;
		this.signKey = signKey;
		this.forceEncryption = forceEncryption;
		this.moduleVersions = moduleVersions != null ? moduleVersions : new JsonObject();
		this.maxUsersPerBatch = maxUsersPerBatch;
		final Future<AsyncMap<String, JsonObject>> mapFuture = localState
				? vertx.sharedData().getLocalAsyncMap("structureExportBatches")
				: vertx.sharedData().getAsyncMap("structureExportBatches");
		mapFuture.onSuccess(map -> this.batches = map)
				.onFailure(th -> log.error("Could not initialize structureExportBatches map", th));
	}

	// ─── Launch ───────────────────────────────────────────────────────────────

	@Override
	public Future<String> launch(UserInfos requester, String structureId, String groupId, JsonArray apps,
			boolean exportDocuments, boolean exportSharedResources, String locale, String host) {
		if (apps == null || apps.isEmpty()) {
			return failedFuture("structure.export.no.apps");
		}
		// Pas de contrôle des applications demandées contre `publicConf.apps` : ce bloc ne liste
		// que les applications RESTAURABLES par l'import « dans son propre compte » (Sauvegarde &
		// Restauration), pas les applications exportABLES — l'export personnel standard
		// (/archive/export) n'applique lui non plus aucun tel filtre. Un lot demandant une
		// application dont le module ne répondrait jamais reste couvert par purgeStuckBatches.

		final Future<JsonObject> groupF = neo4jExecute(
				"MATCH (g:Group {id: {groupId}}) RETURN g.name as name",
				new JsonObject().put("groupId", groupId));
		final Future<JsonObject> usersF = neo4jExecute(
				"MATCH (g:Group {id: {groupId}})<-[:IN]-(u:User) " +
						"RETURN u.id as id, u.firstName as firstName, u.lastName as lastName, u.login as login",
				new JsonObject().put("groupId", groupId));

		return CompositeFuture.all(groupF, usersF).compose(cf -> {
			final JsonObject groupRes = cf.resultAt(0);
			final JsonObject usersRes = cf.resultAt(1);
			if (!"ok".equals(groupRes.getString("status")) || !"ok".equals(usersRes.getString("status"))) {
				return failedFuture("structure.export.query.error");
			}
			final JsonArray groupRows = groupRes.getJsonArray("result");
			if (groupRows == null || groupRows.isEmpty()) {
				return failedFuture("structure.export.group.not.found");
			}
			final JsonArray userRows = usersRes.getJsonArray("result");
			if (userRows == null || userRows.isEmpty()) {
				return failedFuture("structure.export.group.empty");
			}
			if (userRows.size() > maxUsersPerBatch) {
				return failedFuture("structure.export.group.too.large:" + userRows.size() + ">" + maxUsersPerBatch);
			}
			final String groupName = groupRows.getJsonObject(0).getString("name", groupId);
			return doLaunch(requester, structureId, groupId, groupName, userRows, apps,
					exportDocuments, exportSharedResources, locale, host);
		});
	}

	private Future<String> doLaunch(UserInfos requester, String structureId, String groupId, String groupName,
			JsonArray userRows, JsonArray apps, boolean exportDocuments, boolean exportSharedResources,
			String locale, String host) {
		final String batchId = UUID.randomUUID().toString();
		final String batchDir = exportPath + File.separator + "structure" + File.separator + batchId;

		final JsonObject usersState = new JsonObject();
		for (Object row : userRows) {
			final JsonObject u = (JsonObject) row;
			final String userId = u.getString("id");
			if (userId == null) continue;
			String displayName = String.join(" ",
					u.getString("firstName", ""), u.getString("lastName", "")).trim();
			if (displayName.isEmpty()) displayName = u.getString("login", userId);
			// Le suffixe userId (unique par construction) garantit l'unicité du nom de dossier
			// même en cas d'homonymie entre deux comptes du groupe.
			final String folder = StringUtils.replaceForbiddenCharacters(displayName) + "_" + userId;
			usersState.put(userId, new JsonObject()
					.put("folder", folder)
					.put("expectedApps", apps)
					.put("appStatus", new JsonObject())
					.put("finished", false));
		}

		final JsonObject batch = new JsonObject()
				.put("batchId", batchId)
				.put("structureId", structureId)
				.put("groupId", groupId)
				.put("groupName", groupName)
				.put("requesterId", requester == null ? null : requester.getUserId())
				.put("apps", apps)
				.put("locale", locale)
				.put("host", host)
				.put("path", batchDir)
				.put("status", "running")
				.put("startedAt", System.currentTimeMillis())
				.put("totalUsers", usersState.size())
				.put("finishedUsers", 0)
				.put("errorUsers", 0)
				.put("users", usersState);

		final Future<String> result = batches.put(batchId, batch).compose(v -> fs.mkdirs(batchDir)).compose(v -> {
			log.info("[StructureExport] Batch " + batchId + " started for group " + groupId +
					" (" + usersState.size() + " accounts)");
			final List<Future<Void>> publishes = new ArrayList<>();
			for (String userId : usersState.fieldNames()) {
				final Promise<Void> p = Promise.promise();
				publishes.add(p.future());
				final String folder = usersState.getJsonObject(userId).getString("folder");
				final String exportId = STRUCT_PREFIX + batchId + "_" + userId;
				UserUtils.getUserInfos(eb, userId, user -> {
					final JsonArray groupIds = user != null && user.getGroupsIds() != null
							? new JsonArray(new ArrayList<>(user.getGroupsIds()))
							: new JsonArray();
					final JsonObject message = new JsonObject()
							.put("action", "export")
							.put("exportId", exportId)
							.put("userId", userId)
							.put("groups", groupIds)
							.put("path", batchDir + File.separator + folder)
							.put("locale", locale)
							.put("host", host)
							.put("apps", apps)
							.put("exportDocuments", exportDocuments)
							.put("exportSharedResources", exportSharedResources);
					eb.publish("user.repository", message);
					p.complete();
				});
			}
			return Future.all(publishes).mapEmpty();
		}).map(v -> batchId);

		result.onFailure(th -> log.error("[StructureExport] Could not start batch " + batchId, th));
		return result;
	}

	// ─── Progress ─────────────────────────────────────────────────────────────

	@Override
	public void onAppExportDone(String exportId, String status, String app) {
		final String batchId = batchIdOf(exportId);
		final String userId = userIdOf(exportId);
		if (batchId.isEmpty() || userId.isEmpty()) {
			log.error("[StructureExport] Malformed batch exportId : " + exportId);
			return;
		}
		vertx.sharedData().getLockWithTimeout(BATCH_LOCK_PREFIX + "_" + batchId, BATCH_LOCK_TIMEOUT)
				.onFailure(th -> log.error("[StructureExport] Could not get lock for batch " + batchId, th))
				.onSuccess(lock -> batches.get(batchId).onComplete(res -> {
					if (res.failed() || res.result() == null) {
						log.error("[StructureExport] Received a completion for unknown batch " + batchId);
						lock.release();
						return;
					}
					final JsonObject batch = res.result();
					final JsonObject users = batch.getJsonObject("users", new JsonObject());
					final JsonObject userState = users.getJsonObject(userId);
					if (userState == null || Boolean.TRUE.equals(userState.getBoolean("finished"))) {
						lock.release();
						return;
					}
					userState.getJsonObject("appStatus").put(app, status);
					final JsonArray expected = userState.getJsonArray("expectedApps");
					final JsonObject appStatus = userState.getJsonObject("appStatus");
					final boolean userDone = expected.stream().allMatch(a -> appStatus.containsKey(String.valueOf(a)));
					if (!userDone) {
						batches.put(batchId, batch).onComplete(v -> lock.release());
						return;
					}
					userState.put("finished", true);
					batch.put("finishedUsers", batch.getInteger("finishedUsers", 0) + 1);
					final boolean userOk = isAllOk(appStatus);
					if (!userOk) {
						batch.put("errorUsers", batch.getInteger("errorUsers", 0) + 1);
					}
					final boolean batchDone = batch.getInteger("finishedUsers", 0) >= batch.getInteger("totalUsers", 0)
							&& "running".equals(batch.getString("status"));
					if (batchDone) {
						batch.put("status", "assembling");
					}
					batches.put(batchId, batch).onComplete(v -> {
						lock.release();
						if (userOk) {
							finalizeUserFolder(batch, userId, userState);
						}
						if (batchDone) {
							assembleBatch(batchId);
						}
					});
				}));
	}

	/** Manifeste + signature du dossier d'un compte dont TOUTES les applications ont réussi. */
	private void finalizeUserFolder(JsonObject batch, String userId, JsonObject userState) {
		final String userFolderPath = batch.getString("path") + File.separator + userState.getString("folder");
		final String locale = batch.getString("locale", "fr");
		final JsonObject appStatus = userState.getJsonObject("appStatus");
		final JsonArray succeededApps = new JsonArray();
		for (String app : appStatus.fieldNames()) {
			if ("ok".equals(appStatus.getString(app))) succeededApps.add(app);
		}

		vertx.eventBus().request("portal", new JsonObject().put("action", "getI18n").put("acceptLanguage", locale), json -> {
			if (json.failed()) {
				log.error("[StructureExport] Could not build manifest for " + userFolderPath, json.cause());
				return;
			}
			final JsonObject i18n = (JsonObject) json.result().body();
			final JsonObject manifest = new JsonObject();
			for (Object appObj : succeededApps) {
				final String app = String.valueOf(appObj);
				final String label = i18n.getString(app);
				final JsonObject entry = new JsonObject()
						.put("folder", StringUtils.stripAccents(label == null ? app : label));
				final String version = moduleVersions.getString(app);
				if (version != null && !version.isEmpty()) entry.put("version", version);
				manifest.put(app, entry);
			}
			fs.writeFile(userFolderPath + File.separator + USER_MANIFEST_NAME,
					Buffer.buffer(manifest.encodePrettily()), written -> {
						if (written.failed()) {
							log.error("[StructureExport] Could not write manifest " + userFolderPath, written.cause());
							return;
						}
						signUserFolder(userFolderPath);
					});
		});
	}

	private void signUserFolder(String userFolderPath) {
		if (signKey == null) {
			if (forceEncryption) {
				log.error("[StructureExport] No signing key configured, cannot sign " + userFolderPath);
			}
			return;
		}
		final File directory = new File(userFolderPath);
		final File[] files = directory.listFiles();
		if (files == null) return;
		final JsonObject signContents = new JsonObject();
		vertx.executeBlocking(() -> {
			for (File file : files) {
				signContents.put(file.getName(), RSA.signFile(userFolderPath + File.separator + file.getName(), signKey));
			}
			return (Void) null;
		}, false).onSuccess(v -> fs.writeFile(userFolderPath + File.separator + ArchiveController.SIGNATURE_NAME,
				signContents.toBuffer(), written -> {
					if (written.failed()) {
						log.error("[StructureExport] Could not write signature " + userFolderPath, written.cause());
					}
				}))
				.onFailure(th -> log.error("[StructureExport] Could not sign " + userFolderPath, th));
	}

	// ─── Assembly ─────────────────────────────────────────────────────────────

	private void assembleBatch(String batchId) {
		batches.get(batchId).onComplete(res -> {
			if (res.failed() || res.result() == null) return;
			final JsonObject batch = res.result();
			final String batchDir = batch.getString("path");
			final JsonObject users = batch.getJsonObject("users", new JsonObject());

			final JsonArray report = new JsonArray();
			for (String userId : users.fieldNames()) {
				final JsonObject u = users.getJsonObject(userId);
				report.add(new JsonObject()
						.put("userId", userId)
						.put("folder", u.getString("folder"))
						.put("status", isAllOk(u.getJsonObject("appStatus")) ? "ok" : "error")
						.put("apps", u.getJsonObject("appStatus")));
			}
			final JsonObject batchManifest = new JsonObject()
					.put("structureId", batch.getString("structureId"))
					.put("groupId", batch.getString("groupId"))
					.put("groupName", batch.getString("groupName"))
					.put("requesterId", batch.getString("requesterId"))
					.put("apps", batch.getJsonArray("apps"))
					.put("generatedAt", System.currentTimeMillis())
					.put("note", "Lot d'export d'établissement : chaque sous-dossier est un export personnel " +
							"autonome, réimportable seul via /archive/import. Ce lot lui-même n'est pas conçu pour être importé.")
					.put("users", report);

			fs.writeFile(batchDir + File.separator + BATCH_MANIFEST_NAME,
					Buffer.buffer(batchManifest.encodePrettily()), written -> {
						if (written.failed()) {
							log.error("[StructureExport] Could not write batch manifest " + batchId, written.cause());
						}
						Zip.getInstance().zipFolder(batchDir, batchDir + ".zip", true, Deflater.NO_COMPRESSION,
								zipResult -> {
									if (!"ok".equals(zipResult.body().getString("status"))) {
										log.error("[StructureExport] Zip failed for batch " + batchId + " : " +
												zipResult.body().getString("message"));
										markBatchFailed(batchId);
										return;
									}
									storage.writeFsFile(batchId, batchDir + ".zip", storeResult -> {
										fs.delete(batchDir + ".zip", ignored -> { });
										if (!"ok".equals(storeResult.getString("status"))) {
											log.error("[StructureExport] Could not store zip for batch " + batchId + " : " +
													storeResult.getString("message"));
											markBatchFailed(batchId);
											return;
										}
										batches.get(batchId).onSuccess(b -> {
											if (b == null) return;
											b.put("status", "completed").put("completedAt", System.currentTimeMillis());
											batches.put(batchId, b);
										});
									});
								});
					});
		});
	}

	private void markBatchFailed(String batchId) {
		batches.get(batchId).onSuccess(b -> {
			if (b == null) return;
			b.put("status", "error");
			batches.put(batchId, b);
		});
	}

	// ─── Status / lifecycle ─────────────────────────────────────────────────────

	@Override
	public Future<JsonObject> status(String batchId) {
		return batches.get(batchId);
	}

	@Override
	public Future<Void> deleteBatch(String batchId) {
		return batches.get(batchId).compose(batch -> {
			final String path = batch == null ? null : batch.getString("path");

			final Promise<Void> removePromise = Promise.promise();
			storage.removeFile(batchId, res -> removePromise.complete());

			return removePromise.future().compose(v -> {
				if (path == null) return succeededFuture();
				final Promise<Void> deletePromise = Promise.promise();
				fs.deleteRecursive(path, true, event -> {
					if (event.failed()) {
						log.error("[StructureExport] Could not delete batch directory " + path, event.cause());
					}
					deletePromise.complete();
				});
				return deletePromise.future();
			});
		}).compose(v -> batches.remove(batchId)).mapEmpty();
	}

	@Override
	public Future<List<JsonObject>> getAllBatchesStatus() {
		return batches.entries().map(entries -> new ArrayList<>(entries.values()));
	}

	@Override
	public Future<Void> purgeStuckBatches(long maxAgeMs) {
		final long limit = System.currentTimeMillis() - maxAgeMs;
		return batches.entries().compose(entries -> {
			final List<Future<Void>> deletions = new ArrayList<>();
			for (JsonObject batch : entries.values()) {
				final boolean stuck = !"completed".equals(batch.getString("status"))
						&& batch.getLong("startedAt", 0L) < limit;
				if (stuck) {
					log.warn("[StructureExport] Purging stuck batch " + batch.getString("batchId"));
					deletions.add(deleteBatch(batch.getString("batchId")));
				}
			}
			return Future.all(deletions).mapEmpty();
		});
	}

	// ─── Helpers ─────────────────────────────────────────────────────────────

	private Future<JsonObject> neo4jExecute(String query, JsonObject params) {
		final Promise<JsonObject> promise = Promise.promise();
		Neo4j.getInstance().execute(query, params, message -> promise.complete(message.body()));
		return promise.future();
	}

	/** {@code true} si toutes les entrées {@code app -> status} de cette map valent "ok". */
	private static boolean isAllOk(JsonObject appStatus) {
		for (Map.Entry<String, Object> entry : appStatus.getMap().entrySet()) {
			if (!"ok".equals(entry.getValue())) return false;
		}
		return true;
	}

	private static String batchIdOf(String exportId) {
		final String rest = exportId.substring(STRUCT_PREFIX.length());
		final int sep = rest.indexOf('_');
		return sep == -1 ? rest : rest.substring(0, sep);
	}

	private static String userIdOf(String exportId) {
		final String rest = exportId.substring(STRUCT_PREFIX.length());
		final int sep = rest.indexOf('_');
		return sep == -1 ? "" : rest.substring(sep + 1);
	}
}
