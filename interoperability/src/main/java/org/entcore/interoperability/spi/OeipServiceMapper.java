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
    default io.vertx.core.Future<OeipCoreExport> exportCore(OeipExportContext context) {
        return io.vertx.core.Future.failedFuture(
                "[OEIP] aucun mapper sémantique pour le service " + serviceId());
    }

    /**
     * Reprend la description sémantique de ce service sur CETTE plateforme.
     *
     * <p>Aucun importeur ne crée de compte : la création des utilisateurs reste le métier de
     * l'alimentation de l'annuaire. Un import rattache des données à des comptes qui existent
     * déjà ; il ne peuple pas un ENT vide.
     *
     * @return un rapport décrivant ce qui a été apparié, et ce qui ne l'a pas été
     */
    default io.vertx.core.Future<io.vertx.core.json.JsonObject> importCore(OeipImportContext context) {
        return io.vertx.core.Future.failedFuture(
                "[OEIP] aucun importeur sémantique pour le service " + serviceId());
    }

    /** true si ce mapper sait reprendre une description de niveau Core. */
    default boolean supportsCoreImport() {
        return false;
    }

    /**
     * Rang de passage à la reprise, du plus petit au plus grand.
     *
     * L'ordre n'est pas cosmétique : un contenu ne peut rétablir ses liens que vers des fichiers
     * DÉJÀ recréés. Les services qui produisent des fichiers passent donc avant ceux qui les
     * citent. Un paquet n'étant pas tenu de présenter ses services dans cet ordre, c'est ici que
     * la garantie se joue.
     */
    default int importOrder() {
        return 100;
    }

    /**
     * true si ce mapper a besoin de la charge utile d'archive pour travailler.
     *
     * <p>Un mapper qui transcode n'extrait rien lui-même : il reprend ce que le module a déjà
     * produit. C'est ce qui permet de décrire un service sans réécrire son extraction.
     */
    default boolean transcodesNativePayload() {
        return false;
    }

    /** true si ce mapper sait produire du niveau Core. */
    default boolean supportsCore() {
        return false;
    }
}
