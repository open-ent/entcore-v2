package org.entcore.interoperability;

import io.vertx.core.Promise;
import io.vertx.core.json.JsonObject;
import org.entcore.common.events.EventStore;
import org.entcore.common.events.EventStoreFactory;
import org.entcore.common.http.BaseServer;
import org.entcore.common.storage.Storage;
import org.entcore.common.storage.StorageFactory;
import org.entcore.interoperability.controllers.OeipDiscoveryController;
import org.entcore.interoperability.controllers.OeipExportController;
import org.entcore.interoperability.controllers.OeipImportController;
import org.entcore.interoperability.schema.OeipSchemaRegistry;
import org.entcore.interoperability.services.impl.DefaultOeipExportService;
import org.entcore.interoperability.services.impl.DefaultOeipImportService;
import org.entcore.interoperability.services.impl.MongoOeipJobStore;
import org.entcore.interoperability.spi.OeipProviderRegistry;
import org.entcore.interoperability.transcode.ArchiveExportSource;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Module d'interopérabilité : produit et consomme les paquets d'échange OEIP.
 *
 * Ce module ne remplace pas le module archive et ne modifie pas son format. L'archive reste le
 * format INTERNE, conçu pour restaurer un compte sur la même plateforme ; OEIP est le profil
 * d'échange PUBLIC, versionné, destiné à une autre plateforme — éventuellement d'un autre
 * éditeur.
 *
 * Il ne consomme pas non plus le bus « user.repository » : il pilote l'export d'archive par
 * « entcore.export » et réinjecte par « entcore.import / import-file ». C'est ce qui lui donne
 * la couverture de tous les modules déjà gréés sans exiger une ligne de code par module.
 *
 * État : le niveau Native est opérationnel. Les mappers sémantiques (annuaire, espace
 * documentaire, blog) et la projection Common Cartridge viendront s'insérer sans changer cette
 * mécanique.
 */
public class Interoperability extends BaseServer {

    private final OeipProviderRegistry registry = new OeipProviderRegistry();

    @Override
    public void start(final Promise<Void> startPromise) throws Exception {
        final Promise<Void> promise = Promise.promise();
        super.start(promise);

        promise.future()
                .compose(init -> StorageFactory.build(vertx, config))
                .onComplete(res -> {
                    if (res.failed()) {
                        startPromise.fail(res.cause());
                        return;
                    }
                    try {
                        initInteroperability(res.result().getStorage());
                        startPromise.complete();
                    } catch (Exception e) {
                        log.error("[OEIP] Démarrage du module d'interopérabilité impossible", e);
                        startPromise.fail(e);
                    }
                });
    }

    private void initInteroperability(Storage storage) {
        final JsonObject oeipConfig = config.getJsonObject("oeip", new JsonObject());

        // Charger le lot de schémas au DÉMARRAGE, pas à la première requête : un lot incomplet
        // est un défaut de packaging, il doit faire échouer le déploiement tout de suite plutôt
        // que de produire des paquets invalides en silence.
        final OeipSchemaRegistry schemas = new OeipSchemaRegistry();

        final Path workDir = Paths.get(config.getString("export-path",
                System.getProperty("java.io.tmpdir") + "/oeip"));
        // Le répertoire d'import du module ARCHIVE : c'est là que l'archive reconstruite doit
        // atterrir pour qu'importFromFile la trouve.
        final Path archiveImportPath = Paths.get(config.getString("archive-import-path", "/tmp"));

        // Aucun accès à Mongo ici : MongoDb n'est câblé qu'après super.start(), et un appel
        // prématuré échoue par un « eb null » difficile à diagnostiquer.
        final MongoOeipJobStore jobs = new MongoOeipJobStore();

        final ArchiveExportSource archiveSource = new ArchiveExportSource(vertx, storage,
                workDir.resolve("archives"),
                config.getLong("export-timeout-ms", 1800000L),
                config.getLong("max-package-size", 2147483648L));

        final DefaultOeipExportService exportService = new DefaultOeipExportService(
                vertx, jobs, archiveSource, schemas, registry, config, workDir);
        final DefaultOeipImportService importService = new DefaultOeipImportService(
                vertx, jobs, config, workDir, archiveImportPath);

        final EventStore eventStore = EventStoreFactory.getFactory().getEventStore(Interoperability.class.getSimpleName());

        // Les mappers sémantiques s'enregistreront ici. Tant qu'un service n'est pas enregistré,
        // il n'est pas annoncé comme normalisé, et une demande le concernant est refusée
        // explicitement plutôt que de produire un dossier vide.
        addController(new OeipDiscoveryController(registry, schemas, oeipConfig));
        addController(new OeipExportController(exportService, eventStore));
        addController(new OeipImportController(importService, eventStore));

        log.info("[OEIP] Module d'interopérabilité démarré — format " + OeipFormat.VERSION
                + ", lot de schémas " + schemas.getBundleSha256().substring(0, 12)
                + ", " + registry.size() + " mapper(s) sémantique(s), niveau Native actif");
    }
}
