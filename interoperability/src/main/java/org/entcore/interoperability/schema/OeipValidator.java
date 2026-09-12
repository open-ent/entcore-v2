package org.entcore.interoperability.schema;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.ValidationMessage;
import io.vertx.core.json.JsonObject;
import org.entcore.interoperability.OeipFormat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * Valide un document OEIP contre le lot de schémas embarqué.
 *
 * La validation ne porte QUE sur le niveau Core. La charge utile du niveau Native n'est pas
 * schématisée : c'est, par définition, le format d'un autre produit. Le manifeste et les
 * sommes de contrôle la couvrent, et c'est tout ce qui est promis à son sujet.
 */
public class OeipValidator {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final OeipSchemaRegistry registry;

    public OeipValidator(OeipSchemaRegistry registry) {
        this.registry = registry;
    }

    public OeipSchemaRegistry getRegistry() {
        return registry;
    }

    /**
     * @param fileName   nom du fichier dans le paquet, repris tel quel dans le rapport
     * @param document   le document à valider
     * @param schemaFile un des noms de {@link OeipFormat#SCHEMA_FILES}
     * @return la liste des erreurs, vide si le document est conforme
     */
    public List<OeipValidationError> validate(String fileName, JsonObject document, String schemaFile) {
        List<OeipValidationError> errors = new ArrayList<>();
        if (document == null) {
            errors.add(new OeipValidationError(fileName, "", "document", "document absent ou illisible"));
            return errors;
        }
        JsonNode node;
        try {
            node = MAPPER.readTree(document.encode());
        } catch (Exception e) {
            errors.add(new OeipValidationError(fileName, "", "json", "JSON illisible : " + e.getMessage()));
            return errors;
        }
        Set<ValidationMessage> messages;
        try {
            messages = registry.schema(schemaFile).validate(node);
        } catch (Exception e) {
            errors.add(new OeipValidationError(fileName, "", "schema",
                    "validation impossible contre " + schemaFile + " : " + e.getMessage()));
            return errors;
        }
        for (ValidationMessage m : messages) {
            errors.add(new OeipValidationError(fileName, pointer(m.getPath()), m.getType(), m.getMessage()));
        }
        Collections.sort(errors, new Comparator<OeipValidationError>() {
            @Override
            public int compare(OeipValidationError a, OeipValidationError b) {
                String pa = a.getJsonPointer() == null ? "" : a.getJsonPointer();
                String pb = b.getJsonPointer() == null ? "" : b.getJsonPointer();
                return pa.compareTo(pb);
            }
        });
        return errors;
    }

    /** Choisit le schéma applicable d'après l'emplacement du fichier dans le paquet. */
    public static String schemaFor(String packagePath) {
        if (OeipFormat.MANIFEST.equals(packagePath)) {
            return OeipFormat.SCHEMA_MANIFEST;
        }
        if (OeipFormat.IDENTIFIERS.equals(packagePath)) {
            return OeipFormat.SCHEMA_IDENTIFIERS;
        }
        if (OeipFormat.RELATIONS.equals(packagePath)) {
            return OeipFormat.SCHEMA_RELATIONS;
        }
        if (packagePath.startsWith("directory/") && packagePath.endsWith(".json")) {
            return OeipFormat.SCHEMA_DIRECTORY;
        }
        // Les binaires vivent sous resources/<service>/content/ : ils ne sont pas des documents.
        if (packagePath.startsWith("resources/") && packagePath.endsWith(".json")
                && !packagePath.contains("/content/")) {
            return OeipFormat.SCHEMA_RESOURCE;
        }
        return null;
    }

    /** « $.items[0].globalId » (networknt) devient « /items/0/globalId » (pointeur JSON). */
    static String pointer(String path) {
        if (path == null || path.isEmpty()) {
            return "";
        }
        String p = path;
        if (p.startsWith("$")) {
            p = p.substring(1);
        }
        p = p.replace("[", ".").replace("]", "");
        StringBuilder sb = new StringBuilder();
        for (String segment : p.split("\\.")) {
            if (!segment.isEmpty()) {
                sb.append('/').append(segment);
            }
        }
        return sb.toString();
    }
}
