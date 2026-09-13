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
                true, true, true, true,
                OeipFormat.FIDELITY_PARTIAL,
                "Les commentaires et l'historique des versions ne sont pas modélisés en 1.0. "
                + "Les partages sont décrits mais devront être rétablis à l'arrivée.");
    }

    @Override
    public boolean supportsCoreImport() {
        return true;
    }

    /** Après l'espace documentaire : un billet cite des fichiers qui doivent déjà exister. */
    @Override
    public int importOrder() {
        return 30;
    }

    @Override
    public Future<JsonObject> importCore(org.entcore.interoperability.spi.OeipImportContext context) {
        // MongoDb n'est câblé qu'après le démarrage du verticle : on l'obtient à l'appel, pas à
        // la construction du mapper.
        return new BlogOeipImporter(fr.wseduc.mongodb.MongoDb.getInstance()).importCore(context);
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
                String sourceId = MapperSupport.extractFileId(fileName);
                String localPart = OeipUrn.sanitize(
                        sourceId != null ? sourceId : fileName);
                String globalId = OeipUrn.of("file", ss, localPart);
                String cleanName = MapperSupport.cleanAttachmentName(fileName, sourceId);
                String packagePath = "resources/" + SERVICE_ID + "/content/"
                        + MapperSupport.shard(localPart) + "/" + localPart + "/" + OeipUrn.sanitize(cleanName);

                attachments.add(new JsonObject()
                        .put("globalId", globalId)
                        .put("sourceSystem", ss)
                        .put("serviceId", SERVICE_ID)
                        .put("sourceId", sourceId == null ? fileName : sourceId)
                        .put("fileName", cleanName)
                        .put("mediaType", MapperSupport.mediaType(cleanName))
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
        final java.util.Map<String, String> bodies = new LinkedHashMap<String, String>();
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
            JsonObject resource = toResource(doc, isPost, ss, rewriter, bodies);
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

        // Matérialise les corps, et complète chaque ressource de l'empreinte de son fichier.
        java.nio.file.Path bodyDir = java.nio.file.Files.createTempDirectory("oeip-blog-bodies");
        for (Map.Entry<String, String> b : bodies.entrySet()) {
            String path = bodyPath(b.getKey());
            java.nio.file.Path file = bodyDir.resolve(
                    path.substring(path.lastIndexOf('/') + 1) + "-" + bodies.size() + "-"
                    + Integer.toHexString(b.getKey().hashCode()) + ".html");
            java.nio.file.Files.write(file, b.getValue().getBytes(StandardCharsets.UTF_8));
            out.file(path, file);
            for (int i = 0; i < resources.size(); i++) {
                JsonObject r = resources.getJsonObject(i);
                if (b.getKey().equals(r.getString("globalId")) && r.getJsonObject("body") != null) {
                    r.getJsonObject("body").put("sha256", OeipChecksums.sha256(file));
                }
            }
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

    /** Chemin du corps HTML d'une ressource dans le paquet. */
    static String bodyPath(String globalId) {
        String localPart = globalId.substring(globalId.lastIndexOf(':') + 1);
        return "resources/" + SERVICE_ID + "/content/" + MapperSupport.shard(localPart) + "/"
                + localPart + "/index.html";
    }

    private JsonObject toResource(JsonObject doc, boolean isPost, String ss,
                                  HtmlReferenceRewriter rewriter,
                                  java.util.Map<String, String> bodies) {
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
        MapperSupport.putIfText(resource, "description", doc.getString("description"));

        JsonObject author = doc.getJsonObject("author");
        if (author != null && author.getString("userId") != null) {
            resource.put("authorRef", OeipUrn.person(ss, author.getString("userId")));
            resource.put("ownerRef", OeipUrn.person(ss, author.getString("userId")));
        }
        MapperSupport.putIfText(resource, "createdAt", MapperSupport.isoDate(doc.getValue("created")));
        MapperSupport.putIfText(resource, "modifiedAt", MapperSupport.isoDate(doc.getValue("modified")));

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
                // Le corps est écrit dans un fichier plutôt qu'inséré dans le JSON : c'est ce qui
                // permet à la projection pédagogique de le désigner, et cela évite de charger un
                // billet volumineux en mémoire à chaque lecture de l'index.
                // Le corps est conservé tel quel : la réécriture des liens a lieu APRÈS, quand
                // tous les services ont été décrits. Un billet peut citer un document de
                // l'espace documentaire, que ce mapper ne connaît pas.
                bodies.put(globalId, content);
                resource.put("body", new JsonObject()
                        .put("mediaType", "text/html")
                        .put("href", bodyPath(globalId)));
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

    private static JsonObject envelope(String ss, String dataset, JsonArray items) {
        return new JsonObject()
                .put("oeipVersion", OeipFormat.VERSION)
                .put("sourceSystem", ss)
                .put("serviceId", SERVICE_ID)
                .put("dataset", dataset)
                .put("items", items);
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
