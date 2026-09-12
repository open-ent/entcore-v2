package org.entcore.interoperability;

import io.vertx.core.Promise;
import io.vertx.core.json.JsonObject;
import org.entcore.common.http.BaseServer;
import org.entcore.interoperability.controllers.OeipDiscoveryController;
import org.entcore.interoperability.schema.OeipSchemaRegistry;
import org.entcore.interoperability.schema.OeipValidator;
import org.entcore.interoperability.spi.OeipProviderRegistry;

/**
 * Module d'interopérabilité : produit et consomme les paquets d'échange OEIP.
 *
 * Ce module ne remplace pas le module archive et ne le modifie pas. L'archive reste le format
 * INTERNE, conçu pour restaurer un compte sur la même plateforme ; OEIP est le profil d'échange
 * PUBLIC, versionné, destiné à une autre plateforme — éventuellement d'un autre éditeur.
 *
 * Il ne consomme pas non plus le bus « user.repository » directement : il pilote l'export
 * d'archive par « entcore.export » et réinjecte à l'import par importFromFile, ce qui lui donne
 * la couverture de tous les modules déjà gréés sans exiger une ligne de code par module.
 *
 * État : squelette. Les routes d'export et d'import, les mappers sémantiques et la projection
 * Common Cartridge arrivent dans les phases suivantes ; seule la découverte est exposée.
 */
public class Interoperability extends BaseServer {

    private final OeipProviderRegistry registry = new OeipProviderRegistry();

    @Override
    public void start(final Promise<Void> startPromise) throws Exception {
        final Promise<Void> promise = Promise.promise();
        super.start(promise);

        promise.future().onComplete(init -> {
            if (init.failed()) {
                startPromise.fail(init.cause());
                return;
            }
            try {
                initInteroperability();
                startPromise.complete();
            } catch (Exception e) {
                log.error("[OEIP] Démarrage du module d'interopérabilité impossible", e);
                startPromise.fail(e);
            }
        });
    }

    private void initInteroperability() {
        final JsonObject oeipConfig = config.getJsonObject("oeip", new JsonObject());

        // Charger le lot de schémas au démarrage, et non à la première requête : un lot
        // incomplet est un défaut de packaging, il doit faire échouer le déploiement tout de
        // suite plutôt que de produire des paquets invalides en silence.
        final OeipSchemaRegistry schemas = new OeipSchemaRegistry();
        final OeipValidator validator = new OeipValidator(schemas);

        // Les mappers sémantiques (annuaire, espace documentaire, blog) s'enregistrent ici,
        // à partir de la phase 3. Tant qu'un service n'est pas enregistré, il n'est pas
        // annoncé par /capabilities et toute demande le concernant est refusée explicitement.

        addController(new OeipDiscoveryController(registry, schemas, oeipConfig));

        log.info("[OEIP] Module d'interopérabilité démarré — format " + OeipFormat.VERSION
                + ", lot de schémas " + schemas.getBundleSha256().substring(0, 12)
                + ", " + registry.size() + " service(s) gréé(s)");
    }
}
