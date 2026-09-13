package org.entcore.interoperability.packaging;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.entcore.interoperability.OeipFormat;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Construit le manifeste normatif du paquet.
 *
 * Sa responsabilité la plus importante n'est pas de décrire ce que le paquet contient, mais de
 * dire honnêtement ce qu'il ne contient PAS : la fidélité est déclarée service par service, et
 * toute fidélité dégradée doit être justifiée en clair. C'est ce qui remplace la perte de
 * données silencieuse du format d'archive, où un module sans implémentation disparaît sans
 * que personne en soit informé.
 */
public class OeipManifestBuilder {

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);

    private final String sourceSystem;
    private final String productVersion;
    private final String archiveVersion;
    private String platformLabel;
    private String generatedAt;

    private String scopeType = "person";
    private final JsonArray scopeRefs = new JsonArray();
    private final JsonArray scopeUai = new JsonArray();

    private final List<JsonObject> services = new ArrayList<JsonObject>();
    private final JsonArray warnings = new JsonArray();

    private boolean emitCc = false;
    private boolean emitNative = false;
    private boolean pseudonymized = false;
    private boolean includeBinaries = true;
    private boolean includeSharedResources = true;
    private String schemaBundleSha256;
    private JsonArray ccMapping;

    public OeipManifestBuilder(String sourceSystem, String productVersion, String archiveVersion) {
        this.sourceSystem = sourceSystem;
        this.productVersion = productVersion;
        this.archiveVersion = archiveVersion;
        this.generatedAt = ISO.format(Instant.now());
    }

    public OeipManifestBuilder generatedAt(String iso) { this.generatedAt = iso; return this; }
    public String getGeneratedAt() { return generatedAt; }
    public OeipManifestBuilder platformLabel(String label) { this.platformLabel = label; return this; }
    public OeipManifestBuilder schemaBundleSha256(String sha) { this.schemaBundleSha256 = sha; return this; }
    public OeipManifestBuilder emitCc(boolean v) { this.emitCc = v; return this; }

    /**
     * Table de correspondance entre les identifiants du cartouche et les identifiants d'échange.
     * C'est le SEUL pont entre les deux manifestes : aucune extension propriétaire n'est injectée
     * dans le XML, que des validateurs tiers rejetteraient.
     */
    public OeipManifestBuilder ccMapping(JsonArray mapping) { this.ccMapping = mapping; return this; }
    public OeipManifestBuilder emitNative(boolean v) { this.emitNative = v; return this; }
    public OeipManifestBuilder includeBinaries(boolean v) { this.includeBinaries = v; return this; }
    public OeipManifestBuilder includeSharedResources(boolean v) { this.includeSharedResources = v; return this; }

    public OeipManifestBuilder pseudonymized(boolean v) {
        // La charge utile native n'est PAS filtrée par le modèle sémantique : elle contient les
        // données brutes. Les deux options s'excluent donc, et le schéma le vérifie aussi.
        if (v && emitNative) {
            throw new IllegalStateException(
                    "pseudonymisation incompatible avec le niveau Native : la charge utile native n'est pas filtrée");
        }
        this.pseudonymized = v;
        return this;
    }

    public OeipManifestBuilder scope(String type, String... refs) {
        this.scopeType = type;
        for (String r : refs) {
            scopeRefs.add(r);
        }
        return this;
    }

    public OeipManifestBuilder uai(String uai) {
        scopeUai.add(uai);
        return this;
    }

    /**
     * Déclare un service dont seule la charge utile d'origine a pu être transportée.
     *
     * @param notice explication destinée à un humain — obligatoire, c'est tout l'intérêt
     */
    public OeipManifestBuilder addNativeOnlyService(String serviceId, String labelFr, String labelEn,
                                                    String moduleVersion, JsonObject counts, String notice) {
        return addNativeOnlyService(serviceId, labelFr, labelEn, moduleVersion, counts, notice, null);
    }

    /**
     * @param nativeFolder nom du dossier d'origine dans l'archive — un libellé traduit. Le
     *                     destinataire en a besoin : certains modules nomment leur fichier
     *                     d'index d'après ce libellé, et le cherchent ensuite sous LEUR propre
     *                     traduction.
     */
    public OeipManifestBuilder addNativeOnlyService(String serviceId, String labelFr, String labelEn,
                                                    String moduleVersion, JsonObject counts,
                                                    String notice, String nativeFolder) {
        if (notice == null || notice.trim().isEmpty()) {
            throw new IllegalArgumentException(
                    "une fidélité « native-only » doit être justifiée : notice requise pour " + serviceId);
        }
        JsonObject labels = new JsonObject();
        if (labelFr != null) labels.put("fr", labelFr);
        if (labelEn != null) labels.put("en", labelEn);

        JsonObject svc = new JsonObject()
                .put("id", serviceId)
                .put("normalized", false)
                .put("native", true)
                .put("fidelity", OeipFormat.FIDELITY_NATIVE_ONLY)
                .put("notice", notice)
                .put("paths", new JsonObject().put("native", "native/" + serviceId));
        if (labels.size() > 0) svc.put("labels", labels);
        if (moduleVersion != null) svc.put("moduleVersion", moduleVersion);
        if (counts != null) svc.put("counts", counts);
        if (nativeFolder != null) svc.put("nativeFolder", nativeFolder);
        services.add(svc);
        return this;
    }

    /**
     * Déclare un service décrit dans le modèle commun.
     *
     * @param fidelity « full » si rien de connu n'est perdu, « partial » sinon
     * @param notice   obligatoire dès que la fidélité n'est pas « full »
     * @param alsoNative true si la charge utile d'origine accompagne la description
     */
    public OeipManifestBuilder addNormalizedService(String serviceId, String labelFr, String labelEn,
                                                    String moduleVersion, JsonObject counts,
                                                    String fidelity, String notice,
                                                    boolean alsoNative, String nativeFolder) {
        if (!OeipFormat.FIDELITY_FULL.equals(fidelity) && (notice == null || notice.trim().isEmpty())) {
            throw new IllegalArgumentException(
                    "une fidélité « " + fidelity + " » doit être justifiée : notice requise pour " + serviceId);
        }
        JsonObject labels = new JsonObject();
        if (labelFr != null) labels.put("fr", labelFr);
        if (labelEn != null) labels.put("en", labelEn);

        JsonObject paths = new JsonObject().put("core", "directory".equals(serviceId)
                ? "directory" : "resources/" + serviceId);
        if (alsoNative) paths.put("native", "native/" + serviceId);

        JsonObject svc = new JsonObject()
                .put("id", serviceId)
                .put("normalized", true)
                .put("native", alsoNative)
                .put("fidelity", fidelity)
                .put("paths", paths);
        if (notice != null && !notice.trim().isEmpty()) svc.put("notice", notice);
        if (labels.size() > 0) svc.put("labels", labels);
        if (moduleVersion != null) svc.put("moduleVersion", moduleVersion);
        if (counts != null) svc.put("counts", counts);
        if (alsoNative && nativeFolder != null) svc.put("nativeFolder", nativeFolder);
        services.add(svc);
        return this;
    }

    public OeipManifestBuilder addWarning(String code, String serviceId, String message) {
        JsonObject w = new JsonObject().put("code", code).put("message", message);
        if (serviceId != null) w.put("serviceId", serviceId);
        warnings.add(w);
        return this;
    }

    public JsonObject build() {
        if (schemaBundleSha256 == null) {
            throw new IllegalStateException("empreinte du lot de schémas requise");
        }
        boolean anyNormalized = false;
        for (JsonObject s : services) {
            if (Boolean.TRUE.equals(s.getBoolean("normalized"))) {
                anyNormalized = true;
            }
        }
        if (!anyNormalized && !services.isEmpty()) {
            // Un paquet sans aucun service normalisé porte l'enveloppe Core mais aucun contenu
            // interopérable. Le dire explicitement évite de le présenter comme un livrable
            // d'interopérabilité : c'est un tampon de migration entre plateformes du même produit.
            addWarning("core.empty", null,
                    "Aucun service n'est normalisé : ce paquet n'est relisible que par un Open ENT. "
                    + "Il ne vaut pas comme preuve d'interopérabilité.");
        }

        JsonObject levels = new JsonObject()
                .put(OeipFormat.LEVEL_CORE, true)
                .put(OeipFormat.LEVEL_CC, emitCc)
                .put(OeipFormat.LEVEL_NATIVE, emitNative);

        JsonObject scope = new JsonObject().put("type", scopeType).put("refs", scopeRefs);
        if (scopeUai.size() > 0) scope.put("uai", scopeUai);

        JsonObject manifest = new JsonObject()
                .put("format", OeipFormat.FORMAT_ID)
                .put("oeipVersion", OeipFormat.VERSION)
                .put("profile", anyNormalized ? "full" : "resources-only")
                .put("levels", levels)
                .put("source", buildSource())
                .put("generatedAt", generatedAt)
                .put("scope", scope)
                .put("services", new JsonArray(new ArrayList<Object>(services)))
                .put("schemaBundle", new JsonObject()
                        .put("path", OeipFormat.SCHEMA_PACKAGE_DIR)
                        .put("sha256", schemaBundleSha256))
                .put("options", new JsonObject()
                        .put("includeBinaries", includeBinaries)
                        .put("includeSharedResources", includeSharedResources)
                        .put("pseudonymized", pseudonymized));

        if (emitCc && ccMapping != null) {
            manifest.put("ccMapping", ccMapping);
        }
        if (emitNative) {
            manifest.put("nativeFormat", new JsonObject()
                    .put("product", OeipFormat.NATIVE_PRODUCT)
                    .put("archiveVersion", archiveVersion));
        }
        if (warnings.size() > 0) {
            manifest.put("warnings", warnings);
        }
        // « integrity » est posé par OeipPackageWriter.seal : il dépend du relevé de sommes,
        // qui ne peut être calculé qu'une fois tous les fichiers écrits.
        return manifest;
    }

    private JsonObject buildSource() {
        JsonObject source = new JsonObject()
                .put("sourceSystem", sourceSystem)
                .put("product", OeipFormat.NATIVE_PRODUCT);
        if (productVersion != null) source.put("productVersion", productVersion);
        if (platformLabel != null) source.put("platformLabel", platformLabel);
        return source;
    }

    /** Index d'identifiants. Obligatoire même quand aucun objet n'est indexé. */
    public JsonObject buildEmptyIdentifiers() {
        return buildIdentifiers(new JsonArray(), new JsonArray());
    }

    public JsonObject buildIdentifiers(JsonArray entries, JsonArray aliases) {
        return buildIdentifiers(entries, aliases, null, null);
    }

    public JsonObject buildIdentifiers(JsonArray entries, JsonArray aliases,
                                       JsonArray rewrites, JsonArray unresolved) {
        JsonObject json = new JsonObject()
                .put("oeipVersion", OeipFormat.VERSION)
                .put("sourceSystem", sourceSystem)
                .put("generatedAt", generatedAt)
                .put("entries", entries == null ? new JsonArray() : entries);
        if (aliases != null && aliases.size() > 0) {
            json.put("aliases", aliases);
        }
        // Journal des réécritures : sans lui, la substitution serait aussi opaque que celle
        // qu'OEIP remplace.
        if (rewrites != null && rewrites.size() > 0) {
            json.put("rewrites", rewrites);
        }
        // Les liens qu'on n'a pas su résoudre sont AVOUÉS, pas masqués.
        if (unresolved != null && unresolved.size() > 0) {
            json.put("unresolvedReferences", unresolved);
        }
        return json;
    }

    /** Document de relations, omis quand aucun lien n'a été déclaré. */
    public JsonObject buildRelations(JsonArray relations) {
        if (relations == null || relations.size() == 0) {
            return null;
        }
        return new JsonObject()
                .put("oeipVersion", OeipFormat.VERSION)
                .put("sourceSystem", sourceSystem)
                .put("generatedAt", generatedAt)
                .put("items", relations);
    }
}
