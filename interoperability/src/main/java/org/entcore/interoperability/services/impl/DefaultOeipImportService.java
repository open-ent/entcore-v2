package org.entcore.interoperability.services.impl;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.eventbus.DeliveryOptions;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.entcore.common.user.UserInfos;
import org.entcore.interoperability.OeipFormat;
import org.entcore.interoperability.packaging.OeipPackageReader;
import org.entcore.interoperability.services.OeipJob;
import org.entcore.interoperability.transcode.ArchiveFolderResolver;
import org.entcore.interoperability.transcode.ArchiveImportSink;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Consomme un paquet d'échange.
 *
 * L'import ne réécrit pas le chemin d'import : il reconvertit la charge utile native en archive
 * et la réinjecte par le module archive, qui sait déjà remapper les identifiants de chaque
 * module. C'est ce qui évite de dupliquer une vingtaine d'implémentations.
 */
public class DefaultOeipImportService {

    private static final io.vertx.core.impl.logging.Logger log =
            io.vertx.core.impl.logging.LoggerFactory.getLogger(DefaultOeipImportService.class);

    private final Vertx vertx;
    private final MongoOeipJobStore jobs;
    private final JsonObject config;
    private final Path workDir;
    private final Path archiveImportPath;

    public DefaultOeipImportService(Vertx vertx, MongoOeipJobStore jobs, JsonObject config,
                                    Path workDir, Path archiveImportPath) {
        this.vertx = vertx;
        this.jobs = jobs;
        this.config = config;
        this.workDir = workDir;
        this.archiveImportPath = archiveImportPath;
    }

    public String newJobId() {
        return UUID.randomUUID().toString();
    }

    public Path uploadTarget(String jobId) {
        return workDir.resolve(jobId).resolve("package" + OeipFormat.EXTENSION);
    }

    public Future<Void> register(String jobId, UserInfos user, String locale, String host) {
        return jobs.save(OeipJob.create(jobId, OeipJob.TYPE_IMPORT, user.getUserId(),
                new JsonArray(), locale, host, config.getLong("package-ttl-hours", 48L)));
    }

    /**
     * Ouvre le paquet, vérifie son intégrité, et décrit honnêtement ce qui sera réellement
     * reprenable ici — et ce qui ne le sera pas, avec le motif.
     */
    public Future<JsonObject> analyze(final String jobId) {
        final Promise<JsonObject> promise = Promise.promise();
        vertx.<JsonObject>executeBlocking(blocking -> {
            try {
                Path pkg = uploadTarget(jobId);
                Path unzipped = workDir.resolve(jobId).resolve("unzipped");
                OeipPackageReader reader = OeipPackageReader.open(pkg, unzipped,
                        config.getLong("max-package-size", 2147483648L));

                // L'intégrité se vérifie AVANT toute exploitation, et sans aucune clé.
                List<String> problems = reader.verifyIntegrity();
                if (!problems.isEmpty()) {
                    blocking.fail(new IllegalStateException(
                            "intégrité du paquet en défaut : " + problems.get(0)
                            + (problems.size() > 1 ? " (+" + (problems.size() - 1) + " autres)" : "")));
                    return;
                }

                JsonObject manifest = reader.getManifest();
                JsonObject analysis = new JsonObject()
                        .put("oeipVersion", manifest.getString("oeipVersion"))
                        .put("sourceSystem", manifest.getJsonObject("source", new JsonObject())
                                .getString("sourceSystem"))
                        .put("levels", manifest.getJsonObject("levels", new JsonObject()))
                        .put("nativeReadable", reader.isNativeReadable())
                        .put("services", reader.describeServices());

                if (!OeipFormat.VERSION.equals(manifest.getString("oeipVersion"))) {
                    blocking.fail(new IllegalStateException("version de format non prise en charge : "
                            + manifest.getString("oeipVersion")));
                    return;
                }
                blocking.complete(analysis);
            } catch (Exception e) {
                blocking.fail(e);
            }
        }, false, res -> {
            if (res.failed()) {
                jobs.fail(jobId, String.valueOf(res.cause().getMessage()))
                    .onComplete(v -> promise.fail(res.cause()));
                return;
            }
            jobs.update(jobId, new JsonObject()
                    .put("state", OeipJob.ANALYZED)
                    .put("analysis", res.result()))
                .onComplete(v -> promise.complete(res.result()));
        });
        return promise.future();
    }

    /**
     * Réinjecte la charge utile native au nom du compte indiqué.
     *
     * @param dryRun si vrai, on va jusqu'à la reconstruction de l'archive mais rien n'est écrit
     *               dans les bases : c'est le mode par défaut d'une première prise de contact
     */
    public Future<JsonObject> apply(final String jobId, final UserInfos user, final List<String> serviceIds,
                                    final String locale, final String host, final boolean dryRun) {
        final Promise<JsonObject> promise = Promise.promise();

        // Les libellés viennent de CETTE instance : un paquet émis par une plateforme anglophone
        // doit voir ses dossiers renommés avant d'être relu ici.
        vertx.eventBus().request("portal",
                new JsonObject().put("action", "getI18n").put("acceptLanguage", locale),
                new DeliveryOptions().setSendTimeout(30000L), i18nReply -> {
            JsonObject i18n = i18nReply.succeeded()
                    ? (JsonObject) i18nReply.result().body()
                    : new JsonObject();
            if (i18nReply.failed()) {
                log.warn("[OEIP] libellés indisponibles, repli sur les préfixes de route : "
                        + i18nReply.cause().getMessage());
            }
            buildAndSubmit(jobId, user, serviceIds, locale, host, dryRun, i18n, promise);
        });
        return promise.future();
    }

    private void buildAndSubmit(final String jobId, final UserInfos user, final List<String> serviceIds,
                                final String locale, final String host, final boolean dryRun,
                                final JsonObject i18n, final Promise<JsonObject> promise) {
        vertx.<JsonObject>executeBlocking(blocking -> {
            try {
                Path unzipped = workDir.resolve(jobId).resolve("unzipped");
                OeipPackageReader reader = OeipPackageReader.open(uploadTarget(jobId), unzipped,
                        config.getLong("max-package-size", 2147483648L));

                if (!reader.isNativeReadable()) {
                    blocking.fail(new IllegalStateException(
                            "aucun contenu reprenable : la charge utile native vient d'un autre produit, "
                            + "et aucun mapper sémantique n'est disponible en 1.0"));
                    return;
                }

                Map<String, Path> all = reader.nativeDirs();
                Map<String, Path> selected = new LinkedHashMap<String, Path>();
                List<String> refused = new ArrayList<String>();
                for (String id : (serviceIds == null || serviceIds.isEmpty())
                        ? new ArrayList<String>(all.keySet()) : serviceIds) {
                    if (all.containsKey(id)) {
                        selected.put(id, all.get(id));
                    } else {
                        // Refus explicite : jamais un service silencieusement absent du résultat.
                        refused.add(id);
                    }
                }
                if (selected.isEmpty()) {
                    blocking.fail(new IllegalStateException("aucun service reprenable dans ce paquet"));
                    return;
                }

                String importId = ArchiveImportSink.newImportId(user.getUserId());
                ArchiveImportSink sink = new ArchiveImportSink(new ArchiveFolderResolver(i18n));
                Path archive = sink.buildArchive(selected, reader.nativeVersions(),
                        reader.nativeFolders(), importId, archiveImportPath);

                JsonObject out = new JsonObject()
                        .put("importId", importId)
                        .put("archive", archive.toAbsolutePath().toString())
                        .put("services", new JsonArray(new ArrayList<Object>(selected.keySet())))
                        .put("refused", new JsonArray(new ArrayList<Object>(refused)));
                blocking.complete(out);
            } catch (Exception e) {
                blocking.fail(e);
            }
        }, false, res -> {
            if (res.failed()) {
                jobs.fail(jobId, String.valueOf(res.cause().getMessage()))
                    .onComplete(v -> promise.fail(res.cause()));
                return;
            }
            final JsonObject built = res.result();
            if (dryRun) {
                JsonObject report = new JsonObject()
                        .put("dryRun", true)
                        .put("services", built.getJsonArray("services"))
                        .put("refused", built.getJsonArray("refused"))
                        .put("notice", "Archive reconstruite et vérifiée ; aucune écriture en base.");
                jobs.update(jobId, new JsonObject().put("state", OeipJob.DONE).put("report", report))
                    .onComplete(v -> promise.complete(report));
                return;
            }
            submit(jobId, built, user, locale, host, promise);
        });
    }

    /** Remet l'archive reconstruite au module archive, et attend son rapport. */
    private void submit(final String jobId, final JsonObject built, final UserInfos user,
                        final String locale, final String host, final Promise<JsonObject> promise) {
        JsonObject message = new JsonObject()
                .put("action", "import-file")
                .put("importId", built.getString("importId"))
                .put("userId", user.getUserId())
                .put("userLogin", user.getLogin())
                .put("userName", user.getUsername())
                .put("locale", locale)
                .put("host", host)
                .put("timeout", config.getLong("import-timeout-ms", 1800000L));

        long sendTimeout = config.getLong("import-timeout-ms", 1800000L) + 60000L;
        jobs.update(jobId, new JsonObject().put("state", OeipJob.RUNNING))
            .onComplete(ignored ->
                vertx.eventBus().request("entcore.import", message,
                        new DeliveryOptions().setSendTimeout(sendTimeout), reply -> {
                    if (reply.failed()) {
                        jobs.fail(jobId, "import sans réponse : " + reply.cause().getMessage())
                            .onComplete(v -> promise.fail(reply.cause()));
                        return;
                    }
                    JsonObject body = (JsonObject) reply.result().body();
                    JsonObject report = new JsonObject()
                            .put("dryRun", false)
                            .put("status", body.getString("status"))
                            .put("services", built.getJsonArray("services"))
                            .put("refused", built.getJsonArray("refused"))
                            .put("result", body.getJsonObject("result", new JsonObject()));
                    String state = "ok".equals(body.getString("status")) ? OeipJob.DONE : OeipJob.ERROR;
                    jobs.update(jobId, new JsonObject().put("state", state).put("report", report))
                        .onComplete(v -> promise.complete(report));
                }));
    }

    public Future<JsonObject> get(String jobId) {
        return jobs.get(jobId);
    }
}
