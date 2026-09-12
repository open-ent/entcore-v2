package org.entcore.interoperability.spi;

/**
 * Point d'extension d'un service dans OEIP.
 *
 * Pourquoi une interface propre plutôt qu'une extension de {@code RepositoryEvents} :
 *
 *  - {@code user.repository} est diffusé en publish, sans accusé de réception. Un module qui
 *    n'implémente rien ne répond jamais, ce qui est indistinguable d'un module tombé. OEIP doit
 *    au contraire pouvoir dire « ce service n'est pas exportable ici » et échouer explicitement.
 *  - {@code common} est consommé par une vingtaine de modules externes à des versions variables :
 *    y ajouter une méthode, même par défaut, impose un cycle de republication transverse.
 *  - {@code exportResources} ne rend qu'un chemin de dossier opaque, alors que la validation de
 *    schéma et la construction de l'index d'identifiants doivent être faites par le noyau.
 *
 */
public interface OeipServiceMapper {

    /** Préfixe de route du module, tel que rendu par {@code Server.getPathPrefix()}. */
    String serviceId();

    /** Ce que ce service sait faire, tel que publié par /capabilities. */
    OeipCapability capability();

    /**
     * Produit la description sémantique de ce service pour le périmètre demandé.
     *
     * <p>Par défaut, un mapper ne sait rien produire : le service sort alors en
     * {@code native-only}, et le manifeste le déclare. L'absence de description est ainsi
     * TOUJOURS visible dans le paquet, jamais silencieuse.
     *
     * @param scopeUserId compte concerné par l'export
     * @param locale      langue demandée, pour les libellés destinés à un humain
     */
    default io.vertx.core.Future<OeipCoreExport> exportCore(String scopeUserId, String locale) {
        return io.vertx.core.Future.failedFuture(
                "[OEIP] aucun mapper sémantique pour le service " + serviceId());
    }

    /** true si ce mapper sait produire du niveau Core. */
    default boolean supportsCore() {
        return false;
    }
}
