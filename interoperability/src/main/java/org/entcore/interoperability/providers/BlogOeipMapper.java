package org.entcore.interoperability.providers;

import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.entcore.interoperability.OeipFormat;
import org.entcore.interoperability.packaging.OeipChecksums;
import org.entcore.interoperability.spi.OeipCapability;
import org.entcore.interoperability.spi.OeipCoreExport;
import org.entcore.interoperability.spi.OeipExportContext;
import org.entcore.interoperability.spi.OeipServiceMapper;
import org.entcore.interoperability.transcode.HtmlReferenceRewriter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Décrit les blogs et leurs billets dans le modèle commun.
 *
 * Le mapper ne réextrait rien : il transcode ce que le module a déjà produit à l'export
 * d'archive — un fichier JSON par ressource, nommé d'après son titre, et un dossier de pièces
 * jointes. Réécrire l'extraction n'aurait aucun intérêt et doublerait la surface de bogue.
 */
public class BlogOeipMapper implements OeipServiceMapper {

    public static final String SERVICE_ID = "blog";

    /** Dossier de pièces jointes créé par l'export d'archive. */
    private static final String ATTACHMENTS_DIR = "Documents";

    /**
     * Préfixes ajoutés aux titres à l'export d'archive pour retrouver la collection d'origine à
     * l'import. Ils ne doivent jamais survivre dans une description sémantique, sous peine de
     * titres corrompus — et doublement préfixés au deuxième aller-retour.
     */
    private static final String BLOG_PREFIX = "blog_";
    private static final String POST_PREFIX = "post_";

    @Override
    public String serviceId() {
        return SERVICE_ID;
    }

    @Override
    public boolean supportsCore() {
        return true;
    }

    @Override
    public boolean transcodesNativePayload() {
        return true;
    }

    @Override
    public OeipCapability capability() {
        return new OeipCapability(SERVICE_ID, "Blog", "Blog", null,
                true, true, false, true,
                OeipFormat.FIDELITY_PARTIAL,
                "Les commentaires et l'historique des versions ne sont pas modélisés en 1.0. "
                + "Les partages sont décrits mais devront être rétablis à l'arrivée.");
    }

    @Override
    public Future<OeipCoreExport> exportCore(OeipExportContext context) {
        try {
            return Future.succeededFuture(transcode(context));
        } catch (Exception e) {
            return Future.failedFuture(e);
        }
    }

    OeipCoreExport transcode(OeipExportContext context) throws IOException {
        final String ss = context.getSourceSystem();
        final OeipCoreExport out = new OeipCoreExport();
        final Path folder = context.getNativeFolder();

        // 1. Les pièces jointes d'abord : les corps HTML y renvoient, il faut leurs identifiants
        //    avant de pouvoir réécrire quoi que ce soit.
        final Map<String, String> fileGlobalIds = new LinkedHashMap<String, String>();
        final JsonArray attachments = new JsonArray();
        Path attachmentsDir = folder.resolve(ATTACHMENTS_DIR);
        if (Files.isDirectory(attachmentsDir) && context.isIncludeBinaries()) {
            for (Path file : OeipChecksums.listFiles(attachmentsDir)) {
                String fileName = file.getFileName().toString();
                String sourceId = extractFileId(fileName);
                String localPart = sourceId != null ? sourceId : OeipUrn.sanitize(fileName);
                String globalId = OeipUrn.of("file", ss, localPart);
                String cleanName = cleanAttachmentName(fileName, sourceId);
                String packagePath = "resources/" + SERVICE_ID + "/content/"
                        + shard(localPart) + "/" + localPart + "/" + OeipUrn.sanitize(cleanName);

                attachments.add(new JsonObject()
                        .put("globalId", globalId)
                        .put("sourceSystem", ss)
                        .put("serviceId", SERVICE_ID)
                        .put("sourceId", sourceId == null ? fileName : sourceId)
                        .put("fileName", cleanName)
                        .put("mediaType", mediaType(cleanName))
                        .put("size", (int) Files.size(file))
                        .put("sha256", OeipChecksums.sha256(file))
                        .put("path", packagePath));
                out.file(packagePath, file);
                out.identifier(new JsonObject()
                        .put("globalId", globalId).put("kind", "file")
                        .put("level", OeipFormat.LEVEL_CORE).put("sourceSystem", ss)
                        .put("serviceId", SERVICE_ID).put("href", packagePath)
                        .put("sha256", OeipChecksums.sha256(file)));
                if (sourceId != null) {
                    fileGlobalIds.put(sourceId, globalId);
                }
            }
        }

        // 2. Les ressources : un fichier JSON par blog ou billet, sans extension.
        final HtmlReferenceRewriter rewriter = new HtmlReferenceRewriter(fileGlobalIds);
        final JsonArray resources = new JsonArray();
        int blogs = 0;
        int posts = 0;
        int unreadable = 0;

        List<Path> files = new ArrayList<Path>();
        for (Path p : OeipChecksums.listFiles(folder)) {
            if (!p.startsWith(attachmentsDir)) {
                files.add(p);
            }
        }
        for (Path p : files) {
            JsonObject doc = readJson(p);
            if (doc == null) {
                unreadable++;
                continue;
            }
            boolean isPost = doc.containsKey("blog") || rawTitle(doc).startsWith(POST_PREFIX);
            JsonObject resource = toResource(doc, isPost, ss, rewriter);
            resources.add(resource);
            out.identifier(new JsonObject()
                    .put("globalId", resource.getString("globalId")).put("kind", "resource")
                    .put("level", OeipFormat.LEVEL_CORE).put("sourceSystem", ss)
                    .put("serviceId", SERVICE_ID)
                    .put("oeipType", resource.getString("resourceType"))
                    .put("sourceId", resource.getString("sourceId"))
                    .put("href", "resources/" + SERVICE_ID + "/resources.json#/items/"
                            + (resources.size() - 1)));
            if (resource.getString("authorRef") != null) {
                out.relation(new JsonObject().put("type", "authoredBy")
                        .put("fromRef", resource.getString("globalId"))
                        .put("toRef", resource.getString("authorRef")));
            }
            if (resource.getString("parentResourceRef") != null) {
                out.relation(new JsonObject().put("type", "containedIn")
                        .put("fromRef", resource.getString("globalId"))
                        .put("toRef", resource.getString("parentResourceRef")));
            }
            if (isPost) { posts++; } else { blogs++; }
        }

        out.document("resources/" + SERVICE_ID + "/resources.json",
                envelope(ss, "resources", resources));
        if (attachments.size() > 0) {
            out.document("resources/" + SERVICE_ID + "/attachments.json",
                    envelope(ss, "attachments", attachments));
        }

        out.count("resources", resources.size())
           .count("attachments", attachments.size());

        if (unreadable > 0) {
            // Le niveau Interne recopierait ces fichiers sans rien remarquer. Les comprendre,
            // c'est pouvoir signaler qu'ils ne sont pas exploitables.
            out.warning("payload.unreadable",
                    unreadable + " fichier(s) de ce service n'ont pas pu être interprétés et ne "
                    + "figurent donc pas dans la description commune.");
        }
        for (Object u : rewriter.getUnresolvedReferences()) {
            out.unresolved((JsonObject) u);
        }
        for (Object r : rewriter.getRewrites()) {
            out.rewrite((JsonObject) r);
        }

        OeipCapability c = capability();
        String notice = c.getNotice();
        if (blogs + posts > 0 && attachments.size() == 0 && context.isIncludeBinaries()) {
            notice = notice + " Aucune pièce jointe n'accompagne ces contenus.";
        }
        out.fidelity(c.getFidelity(), notice);
        return out;
    }

    private JsonObject toResource(JsonObject doc, boolean isPost, String ss,
                                  HtmlReferenceRewriter rewriter) {
        String sourceId = doc.getString("_id");
        String globalId = OeipUrn.of("resource", ss, sourceId == null ? "inconnu" : sourceId);

        JsonObject resource = new JsonObject()
                .put("globalId", globalId)
                .put("sourceSystem", ss)
                .put("serviceId", SERVICE_ID)
                .put("resourceType", "urn:oeip:restype:blog." + (isPost ? "post" : "blog"));
        if (sourceId != null) {
            resource.put("sourceId", sourceId);
        }

        String title = stripPrefix(rawTitle(doc));
        if (!title.isEmpty()) {
            resource.put("title", title);
        }
        putIfText(resource, "description", doc.getString("description"));

        JsonObject author = doc.getJsonObject("author");
        if (author != null && author.getString("userId") != null) {
            resource.put("authorRef", OeipUrn.person(ss, author.getString("userId")));
            resource.put("ownerRef", OeipUrn.person(ss, author.getString("userId")));
        }
        putIfText(resource, "createdAt", isoDate(doc.getValue("created")));
        putIfText(resource, "modifiedAt", isoDate(doc.getValue("modified")));

        String visibility = doc.getString("visibility");
        if ("PUBLIC".equalsIgnoreCase(visibility)) {
            resource.put("visibility", "public");
        } else if (doc.getJsonArray("shared") != null && doc.getJsonArray("shared").size() > 0) {
            resource.put("visibility", "shared");
        } else if (visibility != null) {
            resource.put("visibility", "owner");
        }

        if (isPost) {
            JsonObject blogRef = doc.getJsonObject("blog");
            String parentId = blogRef == null ? null : blogRef.getString("$id");
            if (parentId != null) {
                resource.put("parentResourceRef", OeipUrn.of("resource", ss, parentId));
            }
            String content = doc.getString("content");
            if (content != null && !content.isEmpty()) {
                resource.put("body", new JsonObject()
                        .put("mediaType", "text/html")
                        .put("content", rewriter.rewrite(content, globalId, "body.content")));
            }
            String state = doc.getString("state");
            if ("PUBLISHED".equalsIgnoreCase(state)) {
                resource.put("state", "published");
            } else if ("SUBMITTED".equalsIgnoreCase(state)) {
                resource.put("state", "submitted");
            } else if (state != null) {
                resource.put("state", "draft");
            }
        }
        return resource;
    }

    // ------------------------------------------------------------------ utilitaires

    static String rawTitle(JsonObject doc) {
        String t = doc.getString("title");
        return t == null ? "" : t;
    }

    /** Retire le préfixe de collection ajouté par l'export d'archive. */
    static String stripPrefix(String title) {
        if (title.startsWith(BLOG_PREFIX)) {
            return title.substring(BLOG_PREFIX.length());
        }
        if (title.startsWith(POST_PREFIX)) {
            return title.substring(POST_PREFIX.length());
        }
        return title;
    }

    /** Les pièces jointes sont nommées « nom_identifiant.ext » par l'export d'archive. */
    static String extractFileId(String fileName) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})")
                .matcher(fileName);
        return m.find() ? m.group(1) : null;
    }

    static String cleanAttachmentName(String fileName, String fileId) {
        if (fileId == null) {
            return fileName;
        }
        String cleaned = fileName.replace("_" + fileId, "").replace(fileId + "_", "");
        return cleaned.isEmpty() ? fileName : cleaned;
    }

    /** Évite un répertoire à dizaines de milliers d'entrées. */
    static String shard(String localPart) {
        return localPart.length() >= 2 ? localPart.substring(0, 2) : "00";
    }

    static String mediaType(String fileName) {
        String n = fileName.toLowerCase();
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".gif")) return "image/gif";
        if (n.endsWith(".svg")) return "image/svg+xml";
        if (n.endsWith(".webp")) return "image/webp";
        if (n.endsWith(".pdf")) return "application/pdf";
        if (n.endsWith(".txt")) return "text/plain";
        if (n.endsWith(".html") || n.endsWith(".htm")) return "text/html";
        if (n.endsWith(".mp4")) return "video/mp4";
        if (n.endsWith(".mp3")) return "audio/mpeg";
        if (n.endsWith(".docx")) {
            return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        }
        if (n.endsWith(".xlsx")) {
            return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        }
        return "application/octet-stream";
    }

    /**
     * Les dates d'archive prennent deux formes : l'objet date de la base, ou une chaîne. Une
     * date illisible est omise plutôt que transmise telle quelle : le schéma exige un
     * horodatage avec fuseau explicite.
     */
    static String isoDate(Object raw) {
        if (raw instanceof JsonObject) {
            // getValue et non getString : sur un nombre, getString ne lève rien et rend sa
            // représentation décimale, qu'on tenterait alors de lire comme une date ISO — et la
            // date serait perdue en silence.
            return isoDate(((JsonObject) raw).getValue("$date"));
        }
        if (raw instanceof Number) {
            return java.time.Instant.ofEpochMilli(((Number) raw).longValue()).toString();
        }
        return raw instanceof String ? normalizeIso((String) raw) : null;
    }

    private static String normalizeIso(String value) {
        try {
            return java.time.Instant.parse(value).toString();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static JsonObject envelope(String ss, String dataset, JsonArray items) {
        return new JsonObject()
                .put("oeipVersion", OeipFormat.VERSION)
                .put("sourceSystem", ss)
                .put("serviceId", SERVICE_ID)
                .put("dataset", dataset)
                .put("items", items);
    }

    private static void putIfText(JsonObject target, String key, String value) {
        if (value != null && !value.isEmpty()) {
            target.put(key, value);
        }
    }

    private static JsonObject readJson(Path p) {
        try {
            String raw = new String(Files.readAllBytes(p), StandardCharsets.UTF_8).trim();
            if (raw.isEmpty() || raw.charAt(0) != '{') {
                return null;
            }
            return new JsonObject(raw);
        } catch (Exception e) {
            return null;
        }
    }
}
