package org.entcore.interoperability.schema;

import io.vertx.core.json.JsonObject;

/**
 * Une erreur de validation localisée.
 *
 * Le verticle mod-json-schema-validator rend un {@code Set.toString()}, dont on ne peut tirer
 * aucun rapport exploitable. OEIP a besoin de savoir QUEL fichier, à QUEL endroit, pour QUELLE
 * contrainte — sans quoi un rapport d'import est inutilisable.
 */
public final class OeipValidationError {

    private final String file;
    private final String jsonPointer;
    private final String keyword;
    private final String message;

    public OeipValidationError(String file, String jsonPointer, String keyword, String message) {
        this.file = file;
        this.jsonPointer = jsonPointer;
        this.keyword = keyword;
        this.message = message;
    }

    public String getFile() { return file; }
    public String getJsonPointer() { return jsonPointer; }
    public String getKeyword() { return keyword; }
    public String getMessage() { return message; }

    public JsonObject toJson() {
        return new JsonObject()
                .put("file", file)
                .put("jsonPointer", jsonPointer)
                .put("keyword", keyword)
                .put("message", message);
    }

    @Override
    public String toString() {
        return file + (jsonPointer == null ? "" : jsonPointer) + " : " + message;
    }
}
