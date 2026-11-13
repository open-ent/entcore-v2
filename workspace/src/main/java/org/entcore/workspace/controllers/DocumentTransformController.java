package org.entcore.workspace.controllers;

import fr.wseduc.rs.Post;
import fr.wseduc.security.ActionType;
import fr.wseduc.security.SecuredAction;
import fr.wseduc.webutils.http.BaseController;
import fr.wseduc.webutils.request.RequestUtils;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.entcore.common.folders.ElementQuery;
import org.entcore.common.storage.Storage;
import org.entcore.common.user.UserInfos;
import org.entcore.common.user.UserUtils;
import org.entcore.workspace.service.DocumentToolsClient;
import org.entcore.workspace.service.DocumentToolsClient.Input;
import org.entcore.workspace.service.DocumentToolsClient.ToolException;
import org.entcore.workspace.service.WorkspaceService;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Transformations de documents de l'espace documentaire : conversions de format, fusion et
 * pages d'un PDF, protection par mot de passe et déverrouillage, aplatissement, rastérisation
 * (texte non copiable), compression, rendu en images, assemblage d'images en PDF.
 *
 * <p>{@code POST /workspace/document/transform}, corps JSON :
 * <pre>
 * { "operation": "merge", "ids": ["…", "…"], "options": { … },
 *   "target": "new" | "version", "parentId": "…", "name": "Fusion.pdf" }
 * </pre>
 * Les opérations elles-mêmes sont faites par le service de documents (node-pdf-generator,
 * routes {@code /tools}) ; ici, on contrôle les droits (lecture des sources, écriture de la
 * destination), le quota et l'extension produite, puis on range le résultat : nouveau document
 * dans le dossier demandé, ou nouvelle version du document source.
 *
 * <p>Droit : celui d'ajouter un document ({@code WorkspaceController|addDocument}). La route est
 * déclarée {@code AUTHENTICATED} et vérifie ce droit elle-même, pour ne pas créer une nouvelle
 * action de workflow à attribuer à tous les rôles.
 */
public class DocumentTransformController extends BaseController {

	private static final String ADD_DOCUMENT_ACTION = "WorkspaceController|addDocument";
	private static final int MAX_FILES = 50;
	/** Opérations qui rendent un fichier de même format que la source (nouvelle version possible). */
	private static final Set<String> SAME_FORMAT = Set.of("pages", "encrypt", "decrypt", "flatten", "rasterize", "compress");
	private static final Set<String> PDF_ONLY = Set.of("info", "merge", "pages", "encrypt", "decrypt", "flatten",
			"rasterize", "compress", "pdf-to-images");
	private static final Set<String> OPERATIONS;
	static {
		final Set<String> ops = new HashSet<>(PDF_ONLY);
		ops.add("convert");
		ops.add("images-to-pdf");
		OPERATIONS = Collections.unmodifiableSet(ops);
	}

	private final Storage storage;
	private final WorkspaceService workspaceService;
	private final DocumentToolsClient tools;
	private final WorkspaceController workspaceController;
	private final long maxBytes;

	public DocumentTransformController(Storage storage, WorkspaceService workspaceService, DocumentToolsClient tools,
			WorkspaceController workspaceController, long maxBytes) {
		this.storage = storage;
		this.workspaceService = workspaceService;
		this.tools = tools;
		this.workspaceController = workspaceController;
		this.maxBytes = maxBytes;
	}

	@Post("/document/transform")
	@SecuredAction(value = "", type = ActionType.AUTHENTICATED)
	public void transform(final HttpServerRequest request) {
		UserUtils.getUserInfos(eb, request, user -> {
			if (user == null) {
				unauthorized(request);
				return;
			}
			if (!canAddDocument(user)) {
				forbidden(request, "workspace.transform.forbidden");
				return;
			}
			RequestUtils.bodyToJson(request, body -> run(request, user, body)
					.onSuccess(result -> {
						if (result.containsKey("info")) {
							renderJson(request, result.getJsonObject("info"));
						} else {
							renderJson(request, result, 201);
						}
					})
					.onFailure(e -> {
						if (e instanceof ToolException) {
							final ToolException te = (ToolException) e;
							final JsonObject error = new JsonObject().put("error", te.code);
							if (te.getMessage() != null && !te.getMessage().isEmpty()) error.put("message", te.getMessage());
							renderJson(request, error, te.status);
						} else {
							log.error("[DocumentTransform] échec inattendu", e);
							renderError(request, new JsonObject().put("error", "workspace.transform.failed"));
						}
					}));
		});
	}

	private static boolean canAddDocument(UserInfos user) {
		return user.getAuthorizedActions() != null && user.getAuthorizedActions().stream()
				.anyMatch(a -> a.getName() != null && a.getName().endsWith(ADD_DOCUMENT_ACTION));
	}

	private static ToolException refused(int status, String code) {
		return new ToolException(status, code, null);
	}

	private Future<JsonObject> run(HttpServerRequest request, UserInfos user, JsonObject body) {
		final String op = body.getString("operation", "");
		final List<String> ids = body.getJsonArray("ids", new JsonArray()).stream()
				.filter(String.class::isInstance).map(String.class::cast).distinct().collect(Collectors.toList());
		final JsonObject options = body.getJsonObject("options", new JsonObject());
		final boolean asVersion = "version".equals(body.getString("target"));
		final String parentId = body.getString("parentId");

		if (!OPERATIONS.contains(op)) return Future.failedFuture(refused(400, "workspace.transform.operation.unknown"));
		if (ids.isEmpty() || ids.size() > MAX_FILES) return Future.failedFuture(refused(400, "workspace.transform.missing.file"));
		if (!"merge".equals(op) && !"images-to-pdf".equals(op) && ids.size() > 1) {
			return Future.failedFuture(refused(400, "workspace.transform.single.file"));
		}
		if (asVersion && !SAME_FORMAT.contains(op)) return Future.failedFuture(refused(400, "workspace.transform.version.impossible"));

		// 1. sources : visibles de l'usager (propriétaire ou partage), fichiers, hors corbeille
		return visibleFiles(user, ids).compose(sources -> {
			long total = 0;
			for (JsonObject doc : sources) {
				total += doc.getJsonObject("metadata", new JsonObject()).getLong("size", 0L);
				if (PDF_ONLY.contains(op) && !"pdf".equals(extension(doc))) {
					return Future.failedFuture(refused(400, "workspace.transform.pdf.invalid"));
				}
			}
			if (total > maxBytes) return Future.failedFuture(refused(413, "workspace.transform.file.too.large"));
			// 2. destination : droit de contribution (version) ou d'écriture dans le dossier (nouveau document)
			final Future<Optional<JsonObject>> destination = asVersion
					? canWrite(user, ids.get(0)).compose(ok -> ok.isPresent() ? Future.succeededFuture(Optional.<JsonObject>empty())
							: Future.failedFuture(refused(403, "workspace.transform.forbidden")))
					: parentFolder(user, parentId);
			return destination.compose(parent -> readAll(sources).compose(inputs -> {
				// 3. transformation
				final Future<DocumentToolsClient.Result> done = tools.apply(op, inputs, options);
				if ("info".equals(op)) {
					return done.map(r -> new JsonObject().put("info", r.content.toJsonObject()));
				}
				// 4. rangement du résultat
				return done.compose(result -> store(user, sources.get(0), result, body.getString("name"), asVersion, parent));
			}));
		});
	}

	private Future<List<JsonObject>> visibleFiles(UserInfos user, List<String> ids) {
		final Promise<List<JsonObject>> promise = Promise.promise();
		final ElementQuery query = new ElementQuery(true);
		query.setIds(ids);
		workspaceService.findByQuery(query, user, res -> {
			if (res.failed()) {
				promise.fail(res.cause());
				return;
			}
			final Map<String, JsonObject> byId = new HashMap<>();
			res.result().forEach(o -> {
				final JsonObject doc = (JsonObject) o;
				byId.put(doc.getString("_id"), doc);
			});
			final List<JsonObject> ordered = new ArrayList<>();
			for (String id : ids) {
				final JsonObject doc = byId.get(id);
				if (doc == null || !"file".equals(doc.getString("eType")) || doc.getBoolean("deleted", false)
						|| doc.getString("file") == null) {
					promise.fail(refused(404, "workspace.transform.not.found"));
					return;
				}
				ordered.add(doc);
			}
			promise.complete(ordered);
		});
		return promise.future();
	}

	private Future<Optional<JsonObject>> canWrite(UserInfos user, String id) {
		final Promise<Optional<JsonObject>> promise = Promise.promise();
		workspaceService.canWriteOn(Optional.of(id), user, res -> {
			if (res.failed()) promise.fail(res.cause());
			else promise.complete(res.result());
		});
		return promise.future();
	}

	/** Dossier de destination : la racine de « Mes documents » si aucun n'est donné. */
	private Future<Optional<JsonObject>> parentFolder(UserInfos user, String parentId) {
		if (parentId == null || parentId.isEmpty()) return Future.succeededFuture(Optional.empty());
		return canWrite(user, parentId).compose(found -> found.isPresent() ? Future.succeededFuture(found)
				: Future.failedFuture(refused(403, "workspace.transform.forbidden")));
	}

	private Future<List<Input>> readAll(List<JsonObject> sources) {
		Future<List<Input>> chain = Future.succeededFuture(new ArrayList<>());
		for (JsonObject doc : sources) {
			chain = chain.compose(list -> {
				final Promise<List<Input>> p = Promise.promise();
				storage.readFile(doc.getString("file"), buffer -> {
					if (buffer == null) {
						p.fail(refused(404, "workspace.transform.not.found"));
					} else {
						list.add(new Input(buffer, extension(doc)));
						p.complete(list);
					}
				});
				return p.future();
			});
		}
		return chain;
	}

	private Future<JsonObject> store(UserInfos user, JsonObject source, DocumentToolsClient.Result result, String wanted,
			boolean asVersion, Optional<JsonObject> parent) {
		final String ext = result.extension == null ? "" : result.extension.toLowerCase();
		final String filename = resultName(wanted, source, ext);
		final String contentType = result.contentType == null ? "application/octet-stream" : result.contentType.split(";")[0].trim();
		final Promise<JsonObject> promise = Promise.promise();
		workspaceController.checkExtensionAllowed(filename, user, allowed -> {
			if (!allowed) {
				promise.fail(refused(400, "extension.forbidden"));
				return;
			}
			workspaceService.emptySize(user, free -> {
				if (free != null && free < result.content.length()) {
					promise.fail(refused(413, "workspace.transform.quota"));
					return;
				}
				storage.writeBuffer(result.content, contentType, filename, uploaded -> {
					if (!"ok".equals(uploaded.getString("status"))) {
						promise.fail(refused(500, "workspace.transform.save.failed"));
						return;
					}
					uploaded.getJsonObject("metadata").put("name", "file");
					if (asVersion) {
						workspaceService.updateDocument(source.getString("_id"), 1f, source.getString("name"), uploaded, user, res -> {
							if (res != null && "ok".equals(res.body().getString("status"))) {
								promise.complete(new JsonObject().put("_id", source.getString("_id")).put("name", source.getString("name")).put("version", true));
							} else {
								storage.removeFile(uploaded.getString("_id"), r -> {});
								promise.fail(refused(500, "workspace.transform.save.failed"));
							}
						});
					} else {
						final JsonObject doc = new JsonObject().put("eParent", parent.map(p -> p.getString("_id")).orElse(null));
						workspaceService.addDocumentWithParent(parent, user, 1f, filename, null, doc, uploaded, res -> {
							if (res.succeeded()) {
								promise.complete(res.result());
							} else {
								storage.removeFile(uploaded.getString("_id"), r -> {});
								promise.fail(refused(500, "workspace.transform.save.failed"));
							}
						});
					}
				});
			});
		});
		return promise.future();
	}

	/** Nom proposé par l'interface (traduit), sinon celui de la source ; extension imposée par le résultat. */
	static String resultName(String wanted, JsonObject source, String ext) {
		String base = wanted;
		if (base == null || base.trim().isEmpty()) {
			base = source.getString("name", "document");
		}
		base = base.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", " ").trim();
		final int dot = base.lastIndexOf('.');
		if (dot > 0 && base.length() - dot <= 6) base = base.substring(0, dot);
		if (base.length() > 200) base = base.substring(0, 200);
		if (base.isEmpty()) base = "document";
		return ext.isEmpty() ? base : base + "." + ext;
	}

	static String extension(JsonObject doc) {
		final String filename = doc.getJsonObject("metadata", new JsonObject()).getString("filename", doc.getString("name", ""));
		final int dot = filename.lastIndexOf('.');
		return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase();
	}
}
