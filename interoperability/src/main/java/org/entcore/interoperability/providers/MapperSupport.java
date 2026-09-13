package org.entcore.interoperability.providers;

import io.vertx.core.json.JsonObject;

/**
 * Utilitaires communs aux mappers qui transcodent une charge utile d'archive.
 *
 * Ces règles sont partagées volontairement : une table de types de média ou une lecture de date
 * dupliquée finit toujours par diverger d'un service à l'autre, et le paquet cesse alors d'être
 * homogène.
 */
public final class MapperSupport {

    private MapperSupport() {}

    /**
     * Évite un répertoire à dizaines de milliers d'entrées.
     *
     * L'assainissement précède le découpage : un identifiant contenant un séparateur — vu en
     * production, un champ « file » portant un chemin absolu — injecterait sinon ce séparateur
     * au milieu d'un segment, et le chemin déclaré cesserait de désigner le fichier écrit.
     */
    public static String shard(String localPart) {
        String clean = OeipUrn.sanitize(localPart);
        return clean.length() >= 2 ? clean.substring(0, 2) : "00";
    }

    /**
     * Identifiant utilisable comme partie locale d'une URN.
     *
     * Un champ censé porter un identifiant peut contenir tout autre chose : on préfère alors le
     * repli, plutôt que de bâtir une identité sur une valeur qui n'en est pas une.
     */
    public static String identifierOr(String preferred, String fallback) {
        if (preferred != null && preferred.matches("^[A-Za-z0-9._~-]{1,200}$")) {
            return preferred;
        }
        return fallback;
    }

    /** Les pièces jointes sont nommées « nom_identifiant.ext » par l'export d'archive. */
    public static String extractFileId(String fileName) {
        if (fileName == null) {
            return null;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})")
                .matcher(fileName);
        return m.find() ? m.group(1) : null;
    }

    public static String cleanAttachmentName(String fileName, String fileId) {
        if (fileId == null || fileName == null) {
            return fileName;
        }
        String cleaned = fileName.replace("_" + fileId, "").replace(fileId + "_", "");
        return cleaned.isEmpty() ? fileName : cleaned;
    }

    public static String mediaType(String fileName) {
        if (fileName == null) {
            return "application/octet-stream";
        }
        String n = fileName.toLowerCase();
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".gif")) return "image/gif";
        if (n.endsWith(".svg")) return "image/svg+xml";
        if (n.endsWith(".webp")) return "image/webp";
        if (n.endsWith(".pdf")) return "application/pdf";
        if (n.endsWith(".txt")) return "text/plain";
        if (n.endsWith(".md")) return "text/markdown";
        if (n.endsWith(".csv")) return "text/csv";
        if (n.endsWith(".html") || n.endsWith(".htm")) return "text/html";
        if (n.endsWith(".mp4")) return "video/mp4";
        if (n.endsWith(".webm")) return "video/webm";
        if (n.endsWith(".mp3")) return "audio/mpeg";
        if (n.endsWith(".zip")) return "application/zip";
        if (n.endsWith(".odt")) return "application/vnd.oasis.opendocument.text";
        if (n.endsWith(".ods")) return "application/vnd.oasis.opendocument.spreadsheet";
        if (n.endsWith(".odp")) return "application/vnd.oasis.opendocument.presentation";
        if (n.endsWith(".docx")) {
            return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        }
        if (n.endsWith(".xlsx")) {
            return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        }
        if (n.endsWith(".pptx")) {
            return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
        }
        return "application/octet-stream";
    }

    /**
     * Un type de média déclaré par le module prime sur la déduction par extension — mais
     * seulement s'il est bien formé, le schéma imposant la forme « type/sous-type ».
     */
    public static String mediaType(JsonObject metadata, String fileName) {
        if (metadata != null) {
            String declared = metadata.getString("content-type");
            if (declared != null && declared.matches("^[A-Za-z0-9][\\w!#$&^.+-]*/[A-Za-z0-9][\\w!#$&^.+-]*$")) {
                return declared;
            }
        }
        return mediaType(fileName);
    }

    /**
     * Normalise une date d'archive vers un horodatage à fuseau explicite.
     *
     * Les dates prennent trois formes selon le module et la version : un objet de base de
     * données, un nombre de millisecondes, ou une chaîne. Une date illisible est OMISE plutôt
     * que transmise telle quelle — le schéma exige un fuseau, et une date fausse vaut moins que
     * pas de date.
     */
    public static String isoDate(Object raw) {
        if (raw instanceof JsonObject) {
            // getValue et non getString : sur un nombre, getString ne lève rien et rend sa
            // représentation décimale, qu'on tenterait alors de lire comme une date ISO — et la
            // date serait perdue en silence.
            return isoDate(((JsonObject) raw).getValue("$date"));
        }
        if (raw instanceof Number) {
            return java.time.Instant.ofEpochMilli(((Number) raw).longValue()).toString();
        }
        if (raw instanceof String) {
            try {
                return java.time.Instant.parse((String) raw).toString();
            } catch (Exception ignored) {
                return null;
            }
        }
        return null;
    }

    public static void putIfText(JsonObject target, String key, String value) {
        if (value != null && !value.isEmpty()) {
            target.put(key, value);
        }
    }
}
