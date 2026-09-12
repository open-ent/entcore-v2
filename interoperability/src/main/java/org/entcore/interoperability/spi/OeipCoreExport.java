package org.entcore.interoperability.spi;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Ce qu'un mapper sémantique produit : des documents de niveau Core, et les entrées d'index
 * correspondantes.
 *
 * Le mapper rend des objets, pas un chemin de dossier. C'est la différence essentielle avec
 * l'export d'archive : le noyau peut ainsi valider contre les schémas et construire l'index des
 * identifiants lui-même, au lieu de faire confiance à ce que chaque module a bien voulu écrire
 * sur le disque.
 */
public class OeipCoreExport {

    private final Map<String, JsonObject> documents = new LinkedHashMap<String, JsonObject>();
    private final JsonArray identifierEntries = new JsonArray();
    private final JsonArray aliases = new JsonArray();
    private final JsonArray relations = new JsonArray();
    private final Map<String, java.nio.file.Path> files = new LinkedHashMap<String, java.nio.file.Path>();
    private final JsonArray warnings = new JsonArray();
    private final JsonArray unresolved = new JsonArray();
    private final JsonArray rewrites = new JsonArray();
    private final JsonObject counts = new JsonObject();
    private String fidelity;
    private String notice;

    public OeipCoreExport document(String packagePath, JsonObject document) {
        documents.put(packagePath, document);
        return this;
    }

    public OeipCoreExport identifier(JsonObject entry) {
        identifierEntries.add(entry);
        return this;
    }

    public OeipCoreExport alias(JsonObject alias) {
        aliases.add(alias);
        return this;
    }

    public OeipCoreExport relation(JsonObject relation) {
        relations.add(relation);
        return this;
    }

    /** Binaire à recopier dans le paquet : chemin dans le paquet -> fichier source. */
    public OeipCoreExport file(String packagePath, java.nio.file.Path source) {
        files.put(packagePath, source);
        return this;
    }

    /**
     * Anomalie non bloquante : ce que le mapper a constaté et que le paquet doit avouer.
     *
     * C'est ici que se manifeste l'intérêt du niveau Core sur le niveau Interne : en
     * comprenant ce qu'il transporte, un mapper peut signaler ce qui manquait déjà au départ.
     */
    public OeipCoreExport warning(String code, String message) {
        warnings.add(new io.vertx.core.json.JsonObject().put("code", code).put("message", message));
        return this;
    }

    /**
     * Référence rencontrée mais non résolue. Elle reste telle quelle dans les données : un lien
     * mort annoncé vaut mieux qu'un lien réécrit vers n'importe quoi.
     */
    public OeipCoreExport unresolved(io.vertx.core.json.JsonObject reference) {
        unresolved.add(reference);
        return this;
    }

    /** Réécriture de référence effectuée à l'export, journalisée pour être auditable. */
    public OeipCoreExport rewrite(io.vertx.core.json.JsonObject entry) {
        rewrites.add(entry);
        return this;
    }

    public OeipCoreExport count(String key, int value) {
        counts.put(key, value);
        return this;
    }

    /**
     * @param fidelity une des valeurs du format ; « partial » impose une notice
     */
    public OeipCoreExport fidelity(String fidelity, String notice) {
        this.fidelity = fidelity;
        this.notice = notice;
        return this;
    }

    public Map<String, JsonObject> getDocuments() { return documents; }
    public JsonArray getIdentifierEntries() { return identifierEntries; }
    public JsonArray getAliases() { return aliases; }
    public JsonArray getRelations() { return relations; }
    public Map<String, java.nio.file.Path> getFiles() { return files; }
    public JsonArray getWarnings() { return warnings; }
    public JsonArray getUnresolvedReferences() { return unresolved; }
    public JsonArray getRewrites() { return rewrites; }
    public JsonObject getCounts() { return counts; }
    public String getFidelity() { return fidelity; }
    public String getNotice() { return notice; }

    public boolean isEmpty() { return documents.isEmpty(); }
}
