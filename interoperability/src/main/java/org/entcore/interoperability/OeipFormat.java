package org.entcore.interoperability;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Constantes normatives du format d'échange OEIP.
 *
 * La version du FORMAT est indépendante de la version de l'artefact entcore qui le produit :
 * un paquet OEIP 1.0 reste un paquet OEIP 1.0 quelle que soit la version du module.
 */
public final class OeipFormat {

    private OeipFormat() {}

    public static final String VERSION = "1.0";
    public static final String FORMAT_ID = "open-ent-interoperability-package";
    public static final String MEDIA_TYPE = "application/vnd.oeip+zip";
    public static final String EXTENSION = ".oeip";

    /** Identifie le produit émetteur de la charge utile du niveau Native. */
    public static final String NATIVE_PRODUCT = "open-ent";

    /** Emplacement des schémas dans le classpath, et dans le paquet. */
    public static final String SCHEMA_CLASSPATH_DIR = "oeip/schemas/" + VERSION;
    public static final String SCHEMA_PACKAGE_DIR = "schemas/" + VERSION;
    public static final String SCHEMA_BASE_URI = "https://open-ent.fr/oeip/" + VERSION + "/";

    public static final String VOCAB_SERVICE_IDS = "oeip/vocab/service-ids.json";

    /** Fichiers normatifs à la racine du paquet. */
    public static final String MANIFEST = "oeip-manifest.json";
    public static final String IMS_MANIFEST = "imsmanifest.xml";
    public static final String CHECKSUMS = "checksums.sha256";
    public static final String IDENTIFIERS = "identifiers.json";
    public static final String RELATIONS = "relations.json";
    public static final String SIGNATURE = "META/signature.json";

    /**
     * Le lot de schémas est fermé et énuméré : lister des ressources du classpath n'est pas
     * fiable depuis un fat-jar, et le lot doit de toute façon être reproductible à l'octet près
     * pour que son empreinte ait un sens.
     */
    public static final List<String> SCHEMA_FILES = Collections.unmodifiableList(Arrays.asList(
            "common-1.0.schema.json",
            "directory-1.0.schema.json",
            "identifiers-1.0.schema.json",
            "oeip-manifest-1.0.schema.json",
            "relations-1.0.schema.json",
            "resource-1.0.schema.json"
    ));

    public static final String SCHEMA_MANIFEST = "oeip-manifest-1.0.schema.json";
    public static final String SCHEMA_IDENTIFIERS = "identifiers-1.0.schema.json";
    public static final String SCHEMA_DIRECTORY = "directory-1.0.schema.json";
    public static final String SCHEMA_RESOURCE = "resource-1.0.schema.json";
    public static final String SCHEMA_RELATIONS = "relations-1.0.schema.json";

    /** Niveaux de conformité. Seul CORE vaut comme interopérabilité. */
    public static final String LEVEL_CORE = "core";
    public static final String LEVEL_CC = "cc";
    public static final String LEVEL_NATIVE = "native";

    /** Fidélité déclarée service par service. */
    public static final String FIDELITY_FULL = "full";
    public static final String FIDELITY_PARTIAL = "partial";
    public static final String FIDELITY_NATIVE_ONLY = "native-only";
    public static final String FIDELITY_NONE = "none";

    // ------------------------------------------------------------------ versions

    /**
     * Versions du format que ce module sait LIRE. Il n'écrit en 1.1 que les packs : un export de
     * compte reste en 1.0, lisible par tout destinataire qui ne connaît que 1.0.
     *
     * <p>1.1 = 1.0 + profil « pack » (manifeste) + {@code alignments} (ressources). Changements
     * purement additifs : un lecteur 1.1 lit aussi bien un paquet 1.0.</p>
     */
    public static final String VERSION_1_1 = "1.1";
    public static final List<String> READABLE_VERSIONS =
            Collections.unmodifiableList(Arrays.asList(VERSION, VERSION_1_1));

    /** Profil d'un paquet de ressources pédagogiques communes (OEIP 1.1). */
    public static final String PROFILE_PACK = "pack";

    /** Nom d'un schéma du lot pour une version : {@code schemaName("resource", "1.1")}. */
    public static String schemaName(String kind, String version) {
        return kind + "-" + version + ".schema.json";
    }

    public static List<String> schemaFiles(String version) {
        if (VERSION.equals(version)) {
            return SCHEMA_FILES;
        }
        final List<String> files = new java.util.ArrayList<String>();
        for (String name : SCHEMA_FILES) {
            files.add(name.replace("-" + VERSION + ".", "-" + version + "."));
        }
        return Collections.unmodifiableList(files);
    }

    public static String schemaClasspathDir(String version) {
        return "oeip/schemas/" + version;
    }

    public static String schemaPackageDir(String version) {
        return "schemas/" + version;
    }

    public static String schemaBaseUri(String version) {
        return "https://open-ent.fr/oeip/" + version + "/";
    }
}
