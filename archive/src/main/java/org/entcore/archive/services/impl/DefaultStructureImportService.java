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

import fr.wseduc.webutils.Either;
import fr.wseduc.webutils.collections.JsonArray;
import io.vertx.core.CompositeFuture;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.eventbus.EventBus;
import io.vertx.core.eventbus.Message;
import io.vertx.core.eventbus.MessageConsumer;
import io.vertx.core.file.FileSystem;
import io.vertx.core.file.OpenOptions;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.core.shareddata.AsyncMap;
import org.entcore.archive.services.ImportService;
import org.entcore.archive.services.StructureImportService;
import org.entcore.common.neo4j.Neo4j;
import org.entcore.common.user.UserInfos;
import org.entcore.common.utils.FileUtils;
import org.entcore.common.utils.StringUtils;
import org.entcore.common.utils.Zip;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.UUID;
import java.util.zip.Deflater;

import static io.vertx.core.Future.failedFuture;
import static io.vertx.core.Future.succeededFuture;

/**
 * Voir {@link StructureImportService}. Restaure un lot d'établissement en rejouant, pour chaque
 * sous-dossier, l'import personnel standard — mais en le destinant au compte d'origine et non à
 * la personne connectée, ce que {@link ImportService#importFromFile} sait déjà faire (c'est aussi
 * par là que passe la reprise depuis une autre plate-forme).
 *
 * <p>Les comptes sont traités <b>un par un</b>, et non en parallèle : un import mobilise tous les
 * modules de l'ENT, et une classe entière lancée d'un coup les saturerait. La lenteur est ici une
 * qualité.
 */
public class DefaultStructureImportService implements StructureImportService {

	private static final Logger log = LoggerFactory.getLogger(DefaultStructureImportService.class);

	private static final String BATCH_MANIFEST_NAME = "Batch-Manifest.json";
	private static final String USER_MANIFEST_NAME = "Manifest.json";

	/** Un identifiant de compte est un UUID : sert à valider le suffixe d'un nom de dossier. */
	private static final String UUID_REGEX =
			"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

	private final Vertx vertx;
	private final FileSystem fs;
	private final EventBus eb;
	private final ImportService importService;
	private final JsonObject archiveConfig;
	private final String importPath;
	private final int maxUsersPerBatch;

	private final Neo4j neo = Neo4j.getInstance();

	private final long accountTimeoutMs;

	/**
	 * Comptes déjà tranchés (par la réponse du bus ou par le minuteur), pour n'avancer qu'une
	 * fois : les deux peuvent se produire, et enchaîner deux fois sauterait un compte.
	 */
	private final Set<String> finished = ConcurrentHashMap.newKeySet();

	private AsyncMap<String, JsonObject> restores;

	public DefaultStructureImportService(Vertx vertx, ImportService importService, JsonObject archiveConfig,
			String importPath, int maxUsersPerBatch, boolean localState) {
		this.vertx = vertx;
		this.fs = vertx.fileSystem();
		this.eb = vertx.eventBus();
		this.importService = importService;
		this.archiveConfig = archiveConfig != null ? archiveConfig : new JsonObject();
		this.importPath = importPath;
		this.maxUsersPerBatch = maxUsersPerBatch;
		this.accountTimeoutMs = this.archiveConfig.getLong("structure-import-account-timeout", 1800000L);
		final Future<AsyncMap<String, JsonObject>> mapFuture = localState
				? vertx.sharedData().getLocalAsyncMap("structureImportRestores")
				: vertx.sharedData().getAsyncMap("structureImportRestores");
		mapFuture.onSuccess(map -> this.restores = map)
				.onFailure(th -> log.error("Could not initialize structureImportRestores map", th));
	}

	// ─── Dépôt du lot ──────────────────────────────────────────────────────────

	@Override
	public void uploadBatch(HttpServerRequest request, UserInfos requester,
			Handler<Either<String, String>> handler) {
		final String restoreId = UUID.randomUUID().toString();
		final String zipPath = workDir(restoreId) + ".zip";

		request.pause();
		request.setExpectMultipart(true);
		fs.mkdirs(structureImportDir(), dirDone -> {
			if (dirDone.failed()) {
				handler.handle(new Either.Left<>(dirDone.cause().getMessage()));
				return;
			}
			fs.open(zipPath, new OpenOptions(), tmpFile -> {
				if (tmpFile.failed()) {
					handler.handle(new Either.Left<>(tmpFile.cause().getMessage()));
					return;
				}
				request.uploadHandler(upload -> upload.pipeTo(tmpFile.result()));
				request.endHandler(v -> {
					final JsonObject state = new JsonObject()
							.put("restoreId", restoreId)
							.put("requesterId", requester.getUserId())
							.put("status", "uploaded")
							.put("uploadedAt", System.currentTimeMillis());
					restores.put(restoreId, state)
							.onSuccess(ignored -> handler.handle(new Either.Right<>(restoreId)))
							.onFailure(th -> handler.handle(new Either.Left<>(th.getMessage())));
				});
				request.resume();
			});
		});
	}

	// ─── Analyse ───────────────────────────────────────────────────────────────

	@Override
	public Future<JsonObject> analyze(String restoreId) {
		return restores.get(restoreId).compose(state -> {
			if (state == null) {
				return failedFuture("structure.import.unknown");
			}
			final String dir = workDir(restoreId);
			final Promise<Void> unzipped = Promise.promise();
			fs.mkdirs(dir, mk -> {
				if (mk.failed()) { unzipped.fail(mk.cause()); return; }
				FileUtils.unzip(dir + ".zip", dir, res -> {
					if (res.isRight()) unzipped.complete();
					else unzipped.fail(res.left().getValue());
				});
			});
			return unzipped.future()
					.compose(v -> readBatchManifest(dir))
					.compose(manifest -> checkAccounts(dir, manifest))
					.compose(analysis -> {
						state.put("status", Boolean.TRUE.equals(analysis.getBoolean("restorable")) ? "analyzed" : "rejected")
								.put("analysis", analysis)
								.put("analyzedAt", System.currentTimeMillis());
						return restores.put(restoreId, state).map(analysis);
					});
		});
	}

	/**
	 * Le manifeste du lot, seule source qui relie un dossier à un compte. Son absence
	 * disqualifie le fichier : c'est ce qui distingue un lot d'une archive personnelle déposée
	 * ici par erreur.
	 */
	private Future<JsonObject> readBatchManifest(String dir) {
		final Promise<JsonObject> promise = Promise.promise();
		final String manifestPath = dir + File.separator + BATCH_MANIFEST_NAME;
		fs.exists(manifestPath, exists -> {
			if (exists.failed() || !Boolean.TRUE.equals(exists.result())) {
				promise.fail("structure.import.not.a.batch");
				return;
			}
			fs.readFile(manifestPath, read -> {
				if (read.failed()) { promise.fail("structure.import.manifest.unreadable"); return; }
				try {
					promise.complete(new JsonObject(read.result().toString("UTF-8")));
				} catch (RuntimeException e) {
					promise.fail("structure.import.manifest.malformed");
				}
			});
		});
		return promise.future();
	}

	/**
	 * Vérifie chaque compte du lot. Trois contrôles indépendants, décrits dans
	 * {@link StructureImportService} ; le lot n'est restaurable que si tous les comptes passent.
	 */
	private Future<JsonObject> checkAccounts(String dir, JsonObject manifest) {
		final io.vertx.core.json.JsonArray declared = manifest.getJsonArray("users");
		if (declared == null || declared.isEmpty()) {
			return failedFuture("structure.import.batch.empty");
		}
		if (declared.size() > maxUsersPerBatch) {
			return failedFuture("structure.import.batch.too.large:" + declared.size() + ">" + maxUsersPerBatch);
		}

		final List<Future<JsonObject>> checks = new ArrayList<>();
		for (Object row : declared) {
			if (!(row instanceof JsonObject)) continue;
			checks.add(checkOneAccount(dir, (JsonObject) row));
		}

		return CompositeFuture.all(new ArrayList<>(checks)).map(cf -> {
			final io.vertx.core.json.JsonArray accounts = new JsonArray();
			boolean restorable = true;
			for (int i = 0; i < checks.size(); i++) {
				final JsonObject account = cf.resultAt(i);
				accounts.add(account);
				if (!"ok".equals(account.getString("check"))) restorable = false;
			}
			return new JsonObject()
					.put("structureId", manifest.getString("structureId"))
					.put("groupName", manifest.getString("groupName"))
					.put("generatedAt", manifest.getValue("generatedAt"))
					.put("total", accounts.size())
					.put("restorable", restorable)
					.put("accounts", accounts);
		});
	}

	private Future<JsonObject> checkOneAccount(String dir, JsonObject declared) {
		final String userId = declared.getString("userId");
		final String folder = declared.getString("folder");
		final JsonObject account = new JsonObject()
				.put("userId", userId)
				.put("folder", folder)
				.put("exportStatus", declared.getString("status"));

		if (StringUtils.isEmpty(userId) || StringUtils.isEmpty(folder)) {
			return succeededFuture(account.put("check", "manifest.incomplete"));
		}
		// Contrôle 2 : le nom du dossier porte l'identifiant du compte en suffixe. Deux sources
		// qu'il faudrait falsifier de concert pour verser une archive dans le mauvais compte.
		if (!userId.matches(UUID_REGEX) || !folder.endsWith("_" + userId)) {
			return succeededFuture(account.put("check", "folder.mismatch"));
		}
		// Un export dont toutes les applications n'ont pas abouti n'est pas restaurable tel quel :
		// mieux vaut le dire ici que livrer un compte à moitié rempli sans le signaler.
		if (!"ok".equals(declared.getString("status"))) {
			return succeededFuture(account.put("check", "export.incomplete"));
		}

		final String userFolder = dir + File.separator + folder;
		final Promise<JsonObject> promise = Promise.promise();
		fs.exists(userFolder + File.separator + USER_MANIFEST_NAME, exists -> {
			if (exists.failed() || !Boolean.TRUE.equals(exists.result())) {
				promise.complete(account.put("check", "archive.missing"));
				return;
			}
			// Contrôle 3 : le compte existe encore, et n'est pas en attente de suppression.
			neo.execute("MATCH (u:User {id: {userId}}) "
							+ "RETURN u.login as login, u.displayName as displayName, "
							+ "       exists(u.deleteDate) as deletePending",
					new JsonObject().put("userId", userId), res -> {
						final JsonObject body = res.body();
						final io.vertx.core.json.JsonArray rows = body.getJsonArray("result");
						if (!"ok".equals(body.getString("status")) || rows == null || rows.isEmpty()) {
							promise.complete(account.put("check", "user.not.found"));
							return;
						}
						final JsonObject u = rows.getJsonObject(0);
						if (Boolean.TRUE.equals(u.getBoolean("deletePending"))) {
							promise.complete(account.put("check", "user.delete.pending"));
							return;
						}
						promise.complete(account
								.put("login", u.getString("login"))
								.put("displayName", u.getString("displayName"))
								.put("check", "ok"));
					});
		});
		return promise.future();
	}

	// ─── Restauration ──────────────────────────────────────────────────────────

	@Override
	public Future<Void> launch(String restoreId, UserInfos requester, String locale, String host) {
		return restores.get(restoreId).compose(state -> {
			if (state == null) {
				return failedFuture("structure.import.unknown");
			}
			if ("running".equals(state.getString("status"))) {
				return failedFuture("structure.import.already.running");
			}
			final JsonObject analysis = state.getJsonObject("analysis");
			if (analysis == null || !Boolean.TRUE.equals(analysis.getBoolean("restorable"))) {
				// Refus délibéré : restaurer partiellement un lot laisserait l'établissement dans
				// un état que personne ne saurait décrire.
				return failedFuture("structure.import.not.restorable");
			}
			state.put("status", "running")
					.put("startedAt", System.currentTimeMillis())
					.put("launchedBy", requester.getUserId())
					.put("locale", locale)
					.put("host", host)
					.put("done", 0)
					.put("results", new JsonObject());
			return restores.put(restoreId, state).map(v -> {
				restoreNext(restoreId, 0);
				return (Void) null;
			});
		});
	}

	/**
	 * Restaure le compte d'indice {@code index}, puis enchaîne sur le suivant. Séquentiel à
	 * dessein : un import mobilise tous les modules de l'ENT.
	 */
	private void restoreNext(String restoreId, int index) {
		restores.get(restoreId).onSuccess(state -> {
			if (state == null) return;
			final io.vertx.core.json.JsonArray accounts =
					state.getJsonObject("analysis").getJsonArray("accounts");
			if (index >= accounts.size()) {
				state.put("status", "completed").put("completedAt", System.currentTimeMillis());
				restores.put(restoreId, state);
				return;
			}
			final JsonObject account = accounts.getJsonObject(index);
			final String userId = account.getString("userId");

			importService.isUserAlreadyImporting(userId).onComplete(busy -> {
				if (busy.succeeded() && Boolean.TRUE.equals(busy.result())) {
					// Un import personnel de cette personne est en cours : on ne s'y superpose pas.
					recordResult(restoreId, index, userId, "skipped.already.importing");
					return;
				}
				stageAndImport(restoreId, index, state, account);
			});
		});
	}

	/**
	 * Prépare l'archive d'un compte au format attendu par l'import personnel — un zip contenant
	 * UN dossier racine — puis lance l'import à destination de ce compte.
	 */
	private void stageAndImport(String restoreId, int index, JsonObject state, JsonObject account) {
		final String userId = account.getString("userId");
		final String folder = account.getString("folder");
		final String dir = workDir(restoreId);
		// Format de l'identifiant imposé par ImportService#deleteArchive : <millis>_<uuid>.
		final String importId = System.currentTimeMillis() + "_" + userId;
		final String stageDir = dir + File.separator + ".stage_" + index;

		fs.mkdirs(stageDir, mk -> {
			if (mk.failed()) {
				recordResult(restoreId, index, userId, "error.stage:" + mk.cause().getMessage());
				return;
			}
			// Déplacement et non copie : le lot décompressé est un espace de travail jetable, et
			// une classe entière recopiée doublerait l'occupation disque le temps de l'opération.
			fs.move(dir + File.separator + folder, stageDir + File.separator + folder, moved -> {
				if (moved.failed()) {
					recordResult(restoreId, index, userId, "error.stage:" + moved.cause().getMessage());
					return;
				}
				// Le zip doit contenir UN dossier racine : c'est ce qu'attend analyzeArchive, et
				// c'est pourquoi le dossier du compte est isolé dans stageDir avant d'être zippé.
				Zip.getInstance().zipFolder(stageDir, importPath + File.separator + importId,
						true, Deflater.NO_COMPRESSION, zipResult -> {
							if (!"ok".equals(zipResult.body().getString("status"))) {
								recordResult(restoreId, index, userId,
										"error.zip:" + zipResult.body().getString("message"));
								return;
							}
							awaitImport(restoreId, index, importId, userId);
							importService.importFromFile(importId, userId,
									account.getString("login", userId),
									account.getString("displayName", userId),
									state.getString("locale", "fr"),
									state.getString("host"),
									archiveConfig);
						});
			});
		});
	}

	/**
	 * S'abonne à la fin d'import de ce compte. Sans cet abonnement, la chaîne s'arrêterait au
	 * premier compte : c'est cet événement, et lui seul, qui déclenche le suivant.
	 */
	private void awaitImport(String restoreId, int index, String importId, String userId) {
		final MessageConsumer<JsonObject> consumer =
				eb.consumer(importService.getImportBusAddress(importId));
		// Un compte dont l'import ne répond jamais (analyse en échec côté ImportService, module
		// muet) figerait TOUT le lot : la chaîne n'avance que sur cet événement. Le minuteur est
		// donc la seule garantie que la restauration se termine — en erreur s'il le faut.
		final long timer = vertx.setTimer(accountTimeoutMs, id -> {
			if (finished.add(importId)) {
				consumer.unregister();
				log.error("[StructureImport] No import result for " + userId + " after "
						+ accountTimeoutMs + "ms, moving on");
				recordResult(restoreId, index, userId, "error.timeout");
			}
		});
		consumer.handler((Message<JsonObject> message) -> {
			final String status = message.body().getString("status");
			message.reply(new JsonObject().put("status", "ok"));
			if (!finished.add(importId)) {
				return; // le minuteur a déjà tranché
			}
			vertx.cancelTimer(timer);
			consumer.unregister();
			recordResult(restoreId, index, userId, "ok".equals(status) ? "ok" : "error.import");
		});
	}

	private void recordResult(String restoreId, int index, String userId, String result) {
		restores.get(restoreId).onSuccess(state -> {
			if (state == null) return;
			state.getJsonObject("results").put(userId, result);
			state.put("done", state.getInteger("done", 0) + 1);
			restores.put(restoreId, state).onComplete(ignored -> restoreNext(restoreId, index + 1));
		});
	}

	// ─── Suivi ─────────────────────────────────────────────────────────────────

	@Override
	public Future<JsonObject> status(String restoreId) {
		return restores.get(restoreId);
	}

	@Override
	public Future<List<JsonObject>> getAllRestoresStatus() {
		return restores.values().map(ArrayList::new);
	}

	@Override
	public Future<Void> delete(String restoreId) {
		return restores.get(restoreId).compose(state -> {
			if (state == null) {
				return failedFuture("structure.import.unknown");
			}
			final Promise<Void> promise = Promise.promise();
			fs.deleteRecursive(workDir(restoreId), true, ignored ->
					fs.delete(workDir(restoreId) + ".zip", ignored2 ->
							restores.remove(restoreId).onComplete(r -> promise.complete())));
			return promise.future();
		});
	}

	@Override
	public Future<Void> purgeStuckRestores(long maxAgeMs) {
		final long now = System.currentTimeMillis();
		return restores.entries().compose(all -> {
			final List<Future<Void>> deletions = new ArrayList<>();
			all.forEach((restoreId, state) -> {
				final String status = state.getString("status");
				if ("completed".equals(status)) return;
				final long started = state.getLong("startedAt", state.getLong("uploadedAt", now));
				if (now - started > maxAgeMs) {
					log.warn("[StructureImport] Purging stuck restore " + restoreId);
					deletions.add(delete(restoreId).otherwiseEmpty());
				}
			});
			return CompositeFuture.all(new ArrayList<>(deletions)).mapEmpty();
		});
	}

	// ─── Chemins ───────────────────────────────────────────────────────────────

	private String structureImportDir() {
		return importPath + File.separator + "structure";
	}

	private String workDir(String restoreId) {
		return structureImportDir() + File.separator + restoreId;
	}
}
