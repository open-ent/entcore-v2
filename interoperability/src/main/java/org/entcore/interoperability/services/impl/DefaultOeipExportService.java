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
import org.entcore.interoperability.spi.OeipCoreExport;
import org.entcore.interoperability.spi.OeipExportContext;
import org.entcore.interoperability.spi.OeipProviderRegistry;
import org.entcore.interoperability.spi.OeipServiceMapper;
import org.entcore.interoperability.transcode.ArchiveBundle;
import org.entcore.interoperability.transcode.ArchiveExportSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Produit un paquet d'échange.
 *
 * Deux sources alimentent un même paquet. Les mappers sémantiques décrivent leur service dans le
 * modèle commun — soit en lisant la base, soit en transcodant la charge utile d'archive. Les
 * services sans mapper sont repris tels quels, et le manifeste le déclare.
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

    public Future<String> start(UserInfos user, String locale, String host, List<String> serviceIds,
                                boolean includeBinaries, boolean includeSharedResources) {
        return start(user, locale, host, serviceIds, includeBinaries, includeSharedResources, false);
    }

    public Future<String> start(UserInfos user, String locale, String host, List<String> serviceIds,
                                boolean includeBinaries, boolean includeSharedResources,
                                boolean pseudonymize) {
        final String jobId = UUID.randomUUID().toString();
        final JsonArray services = new JsonArray();
        for (String s : serviceIds) {
            services.add(s);
        }
        JsonObject job = OeipJob.create(jobId, OeipJob.TYPE_EXPORT, user.getUserId(), services,
                locale, host, config.getLong("package-ttl-hours", 48L));

        return jobs.save(job).compose(saved -> {
            // On rend la main tout de suite : un export mobilise tous les modules et peut durer.
            produce(jobId, user, locale, serviceIds, includeBinaries, includeSharedResources,
                    pseudonymize);
            return Future.succeededFuture(jobId);
        });
    }

    private void produce(final String jobId, final UserInfos user, final String locale,
                         final List<String> serviceIds, final boolean includeBinaries,
                         final boolean includeSharedResources, final boolean pseudonymize) {

        // Un service passe par l'export d'archive s'il n'a pas de mapper, ou si son mapper
        // travaille justement à partir de cette charge utile.
        final List<String> archiveIds = new ArrayList<String>();
        for (String id : serviceIds) {
            OeipServiceMapper mapper = providers.get(id);
            boolean hasCore = mapper != null && mapper.supportsCore();
            if (pseudonymize && hasCore && !mapper.transcodesNativePayload()) {
                continue;
            }
            if (!hasCore || mapper.transcodesNativePayload()) {
                archiveIds.add(id);
            }
        }
        if (pseudonymize) {
            // Les services sans description commune n'ont aucune façon d'être pseudonymisés :
            // les emporter quand même reviendrait à livrer des données brutes sous une étiquette
            // trompeuse.
            for (java.util.Iterator<String> it = archiveIds.iterator(); it.hasNext(); ) {
                OeipServiceMapper m = providers.get(it.next());
                if (m == null || !m.supportsCore()) {
                    it.remove();
                }
            }
        }

        jobs.update(jobId, new JsonObject().put("state", OeipJob.RUNNING))
            .compose(v -> archiveIds.isEmpty()
                    ? Future.<ArchiveBundle>succeededFuture(null)
                    : archiveSource.export(user.getUserId(), locale, archiveIds,
                            includeBinaries, includeSharedResources))
            .compose(bundle -> runMappers(serviceIds, user.getUserId(), locale, bundle,
                    includeBinaries, pseudonymize)
                    .compose(core -> pack(jobId, user, bundle, core, pseudonymize)))
            .onSuccess(v -> log.info("[OEIP] paquet prêt pour le travail " + jobId))
            .onFailure(err -> {
                log.error("[OEIP] export " + jobId + " en échec", err);
                jobs.fail(jobId, String.valueOf(err.getMessage()));
            });
    }

    /** Exécute les mappers en séquence, pour ne pas saturer les bases. */
    private Future<Map<String, OeipCoreExport>> runMappers(List<String> serviceIds, String scopeUserId,
                                                           String locale, ArchiveBundle bundle,
                                                           boolean includeBinaries,
                                                           boolean pseudonymize) {
        final Map<String, OeipCoreExport> results = new LinkedHashMap<String, OeipCoreExport>();
        final String sourceSystem = config.getJsonObject("oeip", new JsonObject())
                .getString("source-system", "localhost");

        Future<Void> chain = Future.succeededFuture();
        for (final String id : serviceIds) {
            final OeipServiceMapper mapper = providers.get(id);
            if (mapper == null || !mapper.supportsCore()) {
                continue;
            }
            final Path folder = bundle == null ? null : bundle.folderFor(id);
            if (mapper.transcodesNativePayload() && folder == null) {
                // Le module n'a rien produit : inutile d'appeler le mapper, et surtout il ne faut
                // pas prétendre avoir décrit un service vide.
                continue;
            }
            final OeipExportContext context = new OeipExportContext(scopeUserId, locale, sourceSystem,
                    folder, folder == null ? null : folder.getFileName().toString(),
                    includeBinaries, pseudonymize);

            chain = chain.compose(v -> mapper.exportCore(context)
                    .map(export -> { results.put(id, export); return (Void) null; })
                    .otherwise(err -> {
                        // Un mapper en échec ne fait pas échouer tout l'export : le service
                        // retombera en « interne seulement », et le manifeste le dira.
                        log.error("[OEIP] mapper " + id + " en échec", err);
                        return null;
                    }));
        }
        return chain.map(v -> results);
    }

    private Future<Void> pack(final String jobId, final UserInfos user, final ArchiveBundle bundle,
                              final Map<String, OeipCoreExport> core, final boolean pseudonymize) {
        final Promise<Void> promise = Promise.promise();
        final JsonObject oeip = config.getJsonObject("oeip", new JsonObject());

        vertx.<JsonObject>executeBlocking(blocking -> {
            try {
                Path staging = workDir.resolve(jobId).resolve("package");
                Files.createDirectories(staging);
                OeipPackageWriter writer = new OeipPackageWriter(staging);

                String sourceSystem = oeip.getString("source-system", "localhost");
                OeipManifestBuilder builder = new OeipManifestBuilder(sourceSystem,
                        oeip.getString("archive-version"), oeip.getString("archive-version"))
                        .emitNative(bundle != null && !pseudonymize)
                        .schemaBundleSha256(schemas.getBundleSha256())
                        .scope("person", "urn:oeip:" + OeipFormat.VERSION + ":person:" + sourceSystem
                                + ":" + org.entcore.interoperability.providers.OeipUrn.localPart(
                                        user.getUserId(), sourceSystem, pseudonymize));
                if (pseudonymize) {
                    builder.pseudonymized(true);
                }

                JsonArray identifiers = new JsonArray();
                JsonArray aliases = new JsonArray();
                JsonArray relations = new JsonArray();
                JsonArray rewrites = new JsonArray();
                JsonArray unresolved = new JsonArray();
                Map<String, JsonArray> ccResources = new LinkedHashMap<String, JsonArray>();
                Map<String, JsonArray> ccAttachments = new LinkedHashMap<String, JsonArray>();
                Map<String, String> linkTargets = new LinkedHashMap<String, String>();

                // 1. Ce que les mappers ont su décrire.
                for (Map.Entry<String, OeipCoreExport> e : core.entrySet()) {
                    String serviceId = e.getKey();
                    OeipCoreExport ex = e.getValue();
                    for (Map.Entry<String, JsonObject> doc : ex.getDocuments().entrySet()) {
                        writer.putJson(doc.getKey(), doc.getValue());
                        String dataset = doc.getValue().getString("dataset");
                        if ("resources".equals(dataset)) {
                            ccResources.put(serviceId, doc.getValue().getJsonArray("items", new JsonArray()));
                        } else if ("attachments".equals(dataset)) {
                            ccAttachments.put(serviceId, doc.getValue().getJsonArray("items", new JsonArray()));
                        }
                    }
                    for (Map.Entry<String, Path> f : ex.getFiles().entrySet()) {
                        writer.copyFile(f.getKey(), f.getValue());
                    }
                    identifiers.addAll(ex.getIdentifierEntries());
                    aliases.addAll(ex.getAliases());
                    relations.addAll(ex.getRelations());
                    rewrites.addAll(ex.getRewrites());
                    linkTargets.putAll(ex.getLinkTargets());
                    unresolved.addAll(ex.getUnresolvedReferences());

                    Path nativeFolder = bundle == null || pseudonymize
                            ? null : bundle.folderFor(serviceId);
                    boolean alsoNative = nativeFolder != null;
                    if (alsoNative) {
                        // La charge utile d'origine est conservée à côté de la description : elle
                        // seule permet aujourd'hui de réimporter dans un Open ENT, aucun importeur
                        // sémantique n'existant encore.
                        writer.copyTree("native/" + serviceId, nativeFolder);
                    }
                    builder.addNormalizedService(serviceId, null, null, bundle == null ? null
                                    : bundle.getVersion(serviceId), ex.getCounts(),
                            ex.getFidelity(), ex.getNotice(), alsoNative,
                            alsoNative ? nativeFolder.getFileName().toString() : null);
                    for (Object w : ex.getWarnings()) {
                        JsonObject warn = (JsonObject) w;
                        builder.addWarning(warn.getString("code"), serviceId, warn.getString("message"));
                    }
                }

                // 2. Ce qui n'a pas de mapper : repris tel quel, et déclaré comme tel.
                List<String> nativeOnly = bundle == null || pseudonymize
                        ? Collections.<String>emptyList() : bundle.getServiceIds();
                for (String serviceId : nativeOnly) {
                    if (core.containsKey(serviceId)) {
                        continue;
                    }
                    Path source = bundle.folderFor(serviceId);
                    if (source == null) {
                        builder.addWarning("service.empty", serviceId,
                                "Le module n'a produit aucune donnée pour ce compte.");
                        continue;
                    }
                    writer.copyTree("native/" + serviceId, source);
                    builder.addNativeOnlyService(serviceId, null, null, bundle.getVersion(serviceId),
                            new JsonObject().put("files", bundle.countFiles(serviceId)),
                            "Aucun mapping sémantique en 1.0 : réimportable uniquement dans un Open ENT.",
                            source.getFileName().toString());
                }

                // Résolution des liens internes, une fois TOUS les services décrits. Un billet
                // peut citer un document de l'espace documentaire : aucun mapper pris isolément
                // ne dispose de la table complète, la réécriture ne peut donc avoir lieu qu'ici.
                resolveReferences(writer, staging, ccResources, ccAttachments, linkTargets,
                        rewrites, unresolved);

                // Projection pédagogique, si la plateforme l'émet et qu'il y a de quoi projeter.
                if (oeip.getJsonObject("cc", new JsonObject()).getBoolean("emit", true)) {
                    // Le titre du cartouche est du texte libre destiné à être affiché : il ne
                    // doit pas nommer la personne quand le paquet est annoncé pseudonymisé.
                    org.entcore.interoperability.packaging.ImsManifestWriter cc =
                            new org.entcore.interoperability.packaging.ImsManifestWriter(
                                    pseudonymize ? "Export Open ENT"
                                                 : "Export Open ENT — " + user.getUsername(), "fr");
                    String xml = cc.build(ccResources, ccAttachments);
                    if (xml != null) {
                        writer.putText(OeipFormat.IMS_MANIFEST, xml);
                        builder.emitCc(true).ccMapping(cc.getCcMapping());
                    }
                }

                // Métadonnées : d'où vient ce paquet, et à quelles conditions il peut être traité.
                // La notice de traitement accompagne CHAQUE paquet, y compris — et surtout —
                // quand la plateforme n'a rien renseigné.
                org.entcore.interoperability.packaging.OeipMetaBuilder meta =
                        new org.entcore.interoperability.packaging.OeipMetaBuilder(
                                oeip.getJsonObject("rgpd"), sourceSystem,
                                oeip.getString("archive-version"));
                String generatedAt = manifestGeneratedAt(builder);
                writer.putJson(org.entcore.interoperability.packaging.OeipMetaBuilder.PROVENANCE,
                        meta.provenance(jobId, "urn:oeip:" + OeipFormat.VERSION + ":person:"
                                + sourceSystem + ":"
                                + org.entcore.interoperability.providers.OeipUrn.localPart(
                                        user.getUserId(), sourceSystem, pseudonymize),
                                generatedAt));
                writer.putJson(org.entcore.interoperability.packaging.OeipMetaBuilder.RGPD,
                        meta.rgpd(containsMinors(core), pseudonymize,
                                config.getLong("package-ttl-hours", 48L)));
                if (!meta.isConfigured()) {
                    builder.addWarning(
                            org.entcore.interoperability.packaging.OeipMetaBuilder.WARNING_UNCONFIGURED,
                            null,
                            "Le responsable de traitement ou le délégué à la protection des "
                            + "données ne sont pas renseignés sur cette plateforme : le "
                            + "destinataire devra les obtenir avant tout traitement.");
                }

                writer.putSchemas(schemas.getRawSchemas());
                writer.putJson(OeipFormat.IDENTIFIERS,
                        builder.buildIdentifiers(identifiers, aliases, rewrites, unresolved));
                JsonObject relationsDoc = builder.buildRelations(relations);
                if (relationsDoc != null) {
                    writer.putJson(OeipFormat.RELATIONS, relationsDoc);
                }

                JsonObject manifest = builder.build();
                Path target = workDir.resolve(jobId).resolve(jobId + OeipFormat.EXTENSION);
                writer.seal(manifest, target);

                blocking.complete(new JsonObject()
                        .put("packagePath", target.toAbsolutePath().toString())
                        .put("manifest", manifest));
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

        if (bundle == null) {
            return promise.future();
        }
        // L'archive intermédiaire contient des données personnelles : elle n'a aucune raison de
        // s'attarder dans le stockage une fois le paquet produit.
        return promise.future().compose(v ->
                archiveSource.discard(bundle.getRoot().getFileName().toString()));
    }


    /**
     * Réécrit les liens internes des contenus, avec la table de TOUS les services du paquet.
     *
     * Chaque réécriture est journalisée et chaque lien non résolu signalé : un lien mort annoncé
     * vaut mieux qu'un lien réécrit vers n'importe quoi. Les identifiants réellement cités
     * deviennent les pièces jointes déclarées de la ressource, ce dont la projection pédagogique
     * a besoin pour lister les fichiers qui accompagnent un contenu.
     */
    private void resolveReferences(OeipPackageWriter writer, Path staging,
                                   Map<String, JsonArray> resourcesByService,
                                   Map<String, JsonArray> attachmentsByService,
                                   Map<String, String> linkTargets,
                                   JsonArray rewrites, JsonArray unresolved) throws java.io.IOException {
        // La table vient des mappers, pas des documents publiés : en mode pseudonymisé, ceux-ci
        // ne portent plus l'identifiant d'origine, et la résolution serait impossible.
        Map<String, String> globalBySourceId = new LinkedHashMap<String, String>(linkTargets);

        org.entcore.interoperability.transcode.HtmlReferenceRewriter rewriter =
                new org.entcore.interoperability.transcode.HtmlReferenceRewriter(globalBySourceId);

        for (Map.Entry<String, JsonArray> e : resourcesByService.entrySet()) {
            JsonArray items = e.getValue();
            for (int i = 0; i < items.size(); i++) {
                JsonObject r = items.getJsonObject(i);
                JsonObject body = r.getJsonObject("body");
                if (body == null || body.getString("href") == null) {
                    continue;
                }
                Path file = staging.resolve(body.getString("href"));
                if (!java.nio.file.Files.isRegularFile(file)) {
                    continue;
                }
                String before = new String(java.nio.file.Files.readAllBytes(file),
                        java.nio.charset.StandardCharsets.UTF_8);
                java.util.Set<String> seen =
                        new java.util.LinkedHashSet<String>(rewriter.getReferenced());
                String after = rewriter.rewrite(before, r.getString("globalId"), "body.content");
                if (!after.equals(before)) {
                    java.nio.file.Files.write(file, after.getBytes(
                            java.nio.charset.StandardCharsets.UTF_8));
                    body.put("sha256", org.entcore.interoperability.packaging.OeipChecksums.sha256(file));
                }
                JsonArray refs = r.getJsonArray("attachmentRefs", new JsonArray());
                for (String gid : rewriter.getReferenced()) {
                    if (!seen.contains(gid) && !refs.contains(gid)) {
                        refs.add(gid);
                    }
                }
                if (refs.size() > 0) {
                    r.put("attachmentRefs", refs);
                }
            }
            // L'index sur disque doit refléter les corrections apportées ici.
            String serviceId = e.getKey();
            String path = "resources/" + serviceId + "/resources.json";
            if (java.nio.file.Files.isRegularFile(staging.resolve(path))) {
                JsonObject doc = new JsonObject(new String(java.nio.file.Files.readAllBytes(
                        staging.resolve(path)), java.nio.charset.StandardCharsets.UTF_8));
                doc.put("items", items);
                writer.putJson(path, doc);
            }
        }
        rewrites.addAll(rewriter.getRewrites());
        unresolved.addAll(rewriter.getUnresolvedReferences());
    }


    /**
     * Une personne signalée mineure engage le destinataire : il doit le savoir avant d'ouvrir
     * quoi que ce soit, et non le découvrir en parcourant l'annuaire.
     */
    private static boolean containsMinors(Map<String, OeipCoreExport> core) {
        for (OeipCoreExport ex : core.values()) {
            JsonObject persons = ex.getDocuments().get("directory/persons.json");
            if (persons == null) {
                continue;
            }
            JsonArray items = persons.getJsonArray("items", new JsonArray());
            for (int i = 0; i < items.size(); i++) {
                if ("minor".equals(items.getJsonObject(i).getString("sensitivity"))) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Même horodatage que le manifeste : deux dates différentes seraient inexplicables. */
    private static String manifestGeneratedAt(OeipManifestBuilder builder) {
        return builder.getGeneratedAt();
    }

    /** Services que cette plateforme sait exporter. */
    public List<String> defaultServices() {
        List<String> all = new ArrayList<String>();
        JsonArray configured = config.getJsonObject("oeip", new JsonObject())
                .getJsonArray("services", new JsonArray());
        for (int i = 0; i < configured.size(); i++) {
            all.add(configured.getString(i));
        }
        for (OeipServiceMapper m : providers.all()) {
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
