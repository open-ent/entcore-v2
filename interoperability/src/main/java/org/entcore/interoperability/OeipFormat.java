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
}
