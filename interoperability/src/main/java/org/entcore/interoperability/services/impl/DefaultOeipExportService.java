package org.entcore.interoperability.services.impl;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.entcore.common.user.UserInfos;
import org.entcore.interoperability.OeipFormat;
import org.entcore.interoperability.packaging.OeipManifestBuilder;
import org.entcore.interoperability.packaging.OeipPackageWriter;
import org.entcore.interoperability.schema.OeipSchemaRegistry;
import org.entcore.interoperability.services.OeipJob;
import org.entcore.interoperability.spi.OeipProviderRegistry;
import org.entcore.interoperability.transcode.ArchiveBundle;
import org.entcore.interoperability.transcode.ArchiveExportSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Produit un paquet d'échange.
 *
 * En 1.0 seul le niveau Native est produit : la charge utile d'archive est reprise telle quelle,
 * ce qui donne la couverture de tous les modules gréés. Les mappers sémantiques viendront
 * s'insérer ici, service par service, sans changer la mécanique.
 */
public class DefaultOeipExportService {

    private static final io.vertx.core.impl.logging.Logger log =
            io.vertx.core.impl.logging.LoggerFactory.getLogger(DefaultOeipExportService.class);

    private final Vertx vertx;
    private final MongoOeipJobStore jobs;
    private final ArchiveExportSource archiveSource;
    private final OeipSchemaRegistry schemas;
    private final OeipProviderRegistry providers;
    private final JsonObject config;
    private final Path workDir;

    public DefaultOeipExportService(Vertx vertx, MongoOeipJobStore jobs, ArchiveExportSource archiveSource,
                                    OeipSchemaRegistry schemas, OeipProviderRegistry providers,
                                    JsonObject config, Path workDir) {
        this.vertx = vertx;
        this.jobs = jobs;
        this.archiveSource = archiveSource;
        this.schemas = schemas;
        this.providers = providers;
        this.config = config;
        this.workDir = workDir;
    }

    /**
     * @param serviceIds services demandés ; vide = tous ceux que la plateforme sait exporter
     * @return l'identifiant du travail, immédiatement — la production se poursuit en arrière-plan
     */
    public Future<String> start(UserInfos user, String locale, String host, List<String> serviceIds,
                                boolean includeBinaries, boolean includeSharedResources) {
        final String jobId = UUID.randomUUID().toString();
        final JsonArray services = new JsonArray();
        for (String s : serviceIds) {
            services.add(s);
        }
        JsonObject job = OeipJob.create(jobId, OeipJob.TYPE_EXPORT, user.getUserId(), services,
                locale, host, config.getLong("package-ttl-hours", 48L));

        return jobs.save(job).compose(saved -> {
            // On rend la main tout de suite : un export mobilise tous les modules et peut durer.
            produce(jobId, user, locale, host, serviceIds, includeBinaries, includeSharedResources);
            return Future.succeededFuture(jobId);
        });
    }

    private void produce(final String jobId, final UserInfos user, final String locale, final String host,
                         final List<String> serviceIds, final boolean includeBinaries,
                         final boolean includeSharedResources) {
        jobs.update(jobId, new JsonObject().put("state", OeipJob.RUNNING))
            .compose(v -> archiveSource.export(user.getUserId(), locale, serviceIds,
                    includeBinaries, includeSharedResources))
            .compose(bundle -> pack(jobId, user, bundle))
            .onSuccess(v -> log.info("[OEIP] paquet prêt pour le travail " + jobId))
            .onFailure(err -> {
                log.error("[OEIP] export " + jobId + " en échec", err);
                jobs.fail(jobId, String.valueOf(err.getMessage()));
            });
    }

    private Future<Void> pack(final String jobId, final UserInfos user, final ArchiveBundle bundle) {
        final Promise<Void> promise = Promise.promise();
        vertx.<JsonObject>executeBlocking(blocking -> {
            try {
                Path staging = workDir.resolve(jobId).resolve("package");
                Files.createDirectories(staging);
                OeipPackageWriter writer = new OeipPackageWriter(staging);

                OeipManifestBuilder builder = new OeipManifestBuilder(
                        config.getJsonObject("oeip", new JsonObject()).getString("source-system", "localhost"),
                        config.getJsonObject("oeip", new JsonObject()).getString("archive-version"),
                        config.getJsonObject("oeip", new JsonObject()).getString("archive-version"))
                        .emitNative(true)
                        .schemaBundleSha256(schemas.getBundleSha256())
                        .scope("person", "urn:oeip:" + OeipFormat.VERSION + ":person:"
                                + config.getJsonObject("oeip", new JsonObject())
                                        .getString("source-system", "localhost")
                                + ":" + user.getUserId());

                int exported = 0;
                for (String serviceId : bundle.getServiceIds()) {
                    Path source = bundle.folderFor(serviceId);
                    if (source == null) {
                        // Le module n'a rien produit : on ne fabrique pas un dossier vide, et on
                        // ne prétend pas non plus l'avoir exporté.
                        builder.addWarning("service.empty", serviceId,
                                "Le module n'a produit aucune donnée pour ce compte.");
                        continue;
                    }
                    // Le dossier prend le PRÉFIXE DE ROUTE : le paquet ne doit jamais dépendre de
                    // la langue de l'instance qui l'a produit.
                    writer.copyTree("native/" + serviceId, source);
                    builder.addNativeOnlyService(serviceId, null, null, bundle.getVersion(serviceId),
                            new JsonObject().put("files", bundle.countFiles(serviceId)),
                            "Aucun mapping sémantique en 1.0 : réimportable uniquement dans un Open ENT.",
                            source.getFileName().toString());
                    exported++;
                }

                writer.putSchemas(schemas.getRawSchemas());
                writer.putJson(OeipFormat.IDENTIFIERS, builder.buildEmptyIdentifiers());

                JsonObject manifest = builder.build();
                Path target = workDir.resolve(jobId).resolve(jobId + OeipFormat.EXTENSION);
                writer.seal(manifest, target);

                blocking.complete(new JsonObject()
                        .put("packagePath", target.toAbsolutePath().toString())
                        .put("manifest", manifest)
                        .put("services", exported));
            } catch (Exception e) {
                blocking.fail(e);
            }
        }, false, res -> {
            if (res.failed()) {
                promise.fail(res.cause());
                return;
            }
            JsonObject out = res.result();
            jobs.update(jobId, new JsonObject()
                    .put("state", OeipJob.READY)
                    .put("packagePath", out.getString("packagePath"))
                    .put("manifest", out.getJsonObject("manifest")))
                .onComplete(u -> promise.complete());
        });

        // L'archive intermédiaire contient des données personnelles : elle n'a aucune raison de
        // s'attarder dans le stockage une fois le paquet produit.
        return promise.future().compose(v ->
                archiveSource.discard(bundle.getRoot().getFileName().toString()));
    }

    /** Services que cette plateforme sait exporter : registre de mappers ∪ modules gréés. */
    public List<String> defaultServices() {
        List<String> all = new ArrayList<String>();
        JsonArray configured = config.getJsonObject("oeip", new JsonObject())
                .getJsonArray("services", new JsonArray());
        for (int i = 0; i < configured.size(); i++) {
            all.add(configured.getString(i));
        }
        for (org.entcore.interoperability.spi.OeipServiceMapper m : providers.all()) {
            if (!all.contains(m.serviceId())) {
                all.add(m.serviceId());
            }
        }
        return all;
    }

    public Future<JsonObject> get(String jobId) {
        return jobs.get(jobId);
    }

    public Path packagePath(JsonObject job) {
        String p = job.getString("packagePath");
        return p == null ? null : Paths.get(p);
    }
}
