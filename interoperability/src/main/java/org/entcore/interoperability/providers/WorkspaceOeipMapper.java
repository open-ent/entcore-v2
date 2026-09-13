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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Décrit l'espace documentaire dans le modèle commun : dossiers, fichiers, rattachements.
 *
 * Ce mapper illustre mieux qu'aucun autre l'apport du niveau Core. La charge utile d'archive
 * contient un index listant les documents du compte, et à côté les fichiers eux-mêmes. Rien ne
 * garantit que les deux coïncident : un binaire absent du stockage au moment de l'export laisse
 * son entrée dans l'index, et le paquet reste parfaitement intègre.
 *
 * Recopier cet index sans le lire — ce que fait le niveau Interne — revient à transmettre une
 * promesse qu'on ne peut pas tenir. En le lisant, le mapper constate l'écart et l'inscrit dans
 * le manifeste : le destinataire sait AVANT d'importer ce qui manquera.
 */
public class WorkspaceOeipMapper implements OeipServiceMapper {

    public static final String SERVICE_ID = "workspace";

    /** Sentinelle écrite par l'export d'archive quand les binaires ont été volontairement omis. */
    private static final String SKIP_DOCS = "skipDocs";

    private final org.entcore.common.storage.Storage storage;

    public WorkspaceOeipMapper(org.entcore.common.storage.Storage storage) {
        this.storage = storage;
    }

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
        return new OeipCapability(SERVICE_ID, "Espace documentaire", "Workspace", null,
                true, true, true, true,
                OeipFormat.FIDELITY_PARTIAL,
                "Les partages sont décrits mais devront être rétablis à l'arrivée : les groupes "
                + "de la plateforme de départ n'y existent pas. L'historique des versions et la "
                + "corbeille ne sont pas modélisés en 1.0.");
    }

    @Override
    public boolean supportsCoreImport() {
        return true;
    }

    /** Les fichiers d'abord : les contenus qui les citent en dépendent. */
    @Override
    public int importOrder() {
        return 10;
    }

    @Override
    public Future<JsonObject> importCore(org.entcore.interoperability.spi.OeipImportContext context) {
        return new WorkspaceOeipImporter(fr.wseduc.mongodb.MongoDb.getInstance(), storage)
                .importCore(context);
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

        JsonArray index = readIndex(folder, context.getNativeFolderName());
        if (index == null) {
            // Sans index, on ne sait pas ce que contient ce dossier : mieux vaut l'admettre que
            // décrire à moitié.
            out.warning("payload.index.missing",
                    "L'index de l'espace documentaire est introuvable dans la charge utile : "
                    + "aucune description commune n'a pu être produite.");
            out.document("resources/" + SERVICE_ID + "/attachments.json",
                    envelope(ss, "attachments", new JsonArray()));
            out.count("folders", 0).count("attachments", 0);
            out.fidelity(OeipFormat.FIDELITY_PARTIAL, capability().getNotice()
                    + " Index introuvable : le contenu n'est pas décrit.");
            return out;
        }

        final boolean binariesOmitted = Files.isRegularFile(folder.resolve(SKIP_DOCS))
                || !context.isIncludeBinaries();

        final JsonArray folders = new JsonArray();
        final JsonArray attachments = new JsonArray();
        int announced = 0;
        int missing = 0;

        for (int i = 0; i < index.size(); i++) {
            JsonObject doc = index.getJsonObject(i);
            if (doc == null || doc.getString("_id") == null) {
                continue;
            }
            if ("folder".equals(doc.getString("eType"))) {
                folders.add(toFolder(doc, ss, folders.size(), out));
                continue;
            }
            announced++;

            String archivePath = doc.getString("localArchivePath");
            Path binary = archivePath == null ? null : folder.resolve(archivePath);
            boolean present = binary != null && Files.isRegularFile(binary);

            if (!present) {
                if (!binariesOmitted) {
                    // L'index l'annonce, le fichier n'est pas là : c'est exactement ce que le
                    // niveau Interne ne sait pas voir.
                    missing++;
                }
                continue;
            }
            attachments.add(toAttachment(doc, binary, ss, attachments.size(), out));
        }

        out.document("resources/" + SERVICE_ID + "/folders.json", envelope(ss, "folders", folders));
        out.document("resources/" + SERVICE_ID + "/attachments.json",
                envelope(ss, "attachments", attachments));
        out.count("folders", folders.size())
           .count("attachments", attachments.size())
           .count("announced", announced);

        String notice = capability().getNotice();
        if (binariesOmitted && announced > 0) {
            out.warning("binaries.omitted",
                    announced + " document(s) sont recensés mais leurs fichiers n'ont pas été "
                    + "inclus à la demande de l'export.");
            notice = notice + " Les fichiers eux-mêmes n'ont pas été inclus dans ce paquet.";
        } else if (missing > 0) {
            out.warning("binaries.missing",
                    missing + " document(s) sur " + announced + " sont recensés par la plateforme "
                    + "de départ mais leur fichier n'y était plus disponible : ils ne figurent pas "
                    + "dans ce paquet et ne pourront pas être restitués.");
            notice = notice + " " + missing + " document(s) annoncés n'ont pas pu être emportés, "
                    + "faute de fichier disponible au départ.";
        }
        out.fidelity(OeipFormat.FIDELITY_PARTIAL, notice);
        return out;
    }

    private JsonObject toFolder(JsonObject doc, String ss, int position, OeipCoreExport out) {
        String sourceId = doc.getString("_id");
        String globalId = OeipUrn.of("folder", ss, sourceId);
        JsonObject f = new JsonObject()
                .put("globalId", globalId)
                .put("sourceSystem", ss)
                .put("serviceId", SERVICE_ID)
                .put("sourceId", sourceId)
                .put("name", nonEmpty(doc.getString("name"), "Dossier"));
        MapperSupport.putIfText(f, "createdAt", MapperSupport.isoDate(doc.getValue("created")));
        if (doc.getString("eParent") != null) {
            f.put("parentFolderRef", OeipUrn.of("folder", ss, doc.getString("eParent")));
        }
        if (doc.getString("owner") != null) {
            f.put("ownerRef", OeipUrn.person(ss, doc.getString("owner")));
        }
        if (Boolean.TRUE.equals(doc.getBoolean("trashed"))) {
            f.put("trashed", true);
        }
        out.identifier(entry(globalId, "folder", sourceId, ss,
                "resources/" + SERVICE_ID + "/folders.json#/items/" + position));
        return f;
    }

    private JsonObject toAttachment(JsonObject doc, Path binary, String ss, int position,
                                    OeipCoreExport out) throws IOException {
        // L'identifiant retenu est celui du DOCUMENT, pas celui du binaire : c'est le document
        // que désignent les liens « /workspace/document/… » — la route résout par findById. Se
        // fonder sur le champ « file » ferait échouer la résolution de toutes les images.
        String sourceId = MapperSupport.identifierOr(doc.getString("_id"), doc.getString("file"));
        String localPart = OeipUrn.sanitize(sourceId);
        String globalId = OeipUrn.of("file", ss, sourceId);
        String name = nonEmpty(doc.getString("name"), binary.getFileName().toString());
        String packagePath = "resources/" + SERVICE_ID + "/content/"
                + MapperSupport.shard(localPart) + "/" + localPart + "/" + OeipUrn.sanitize(name);

        JsonObject a = new JsonObject()
                .put("globalId", globalId)
                .put("sourceSystem", ss)
                .put("serviceId", SERVICE_ID)
                .put("sourceId", sourceId)
                .put("fileName", name)
                .put("mediaType", MapperSupport.mediaType(doc.getJsonObject("metadata"), name))
                .put("size", (int) Files.size(binary))
                .put("sha256", OeipChecksums.sha256(binary))
                .put("path", packagePath);
        MapperSupport.putIfText(a, "createdAt", MapperSupport.isoDate(doc.getValue("created")));
        if (doc.getString("owner") != null) {
            a.put("ownerRef", OeipUrn.person(ss, doc.getString("owner")));
            out.relation(new JsonObject().put("type", "ownedBy")
                    .put("fromRef", globalId)
                    .put("toRef", OeipUrn.person(ss, doc.getString("owner"))));
        }
        if (doc.getString("eParent") != null) {
            String parent = OeipUrn.of("folder", ss, doc.getString("eParent"));
            a.put("folderRef", parent);
            out.relation(new JsonObject().put("type", "containedIn")
                    .put("fromRef", globalId).put("toRef", parent));
        }

        out.file(packagePath, binary);
        out.identifier(entry(globalId, "file", sourceId, ss, packagePath)
                .put("sha256", a.getString("sha256")));
        return a;
    }

    private JsonObject entry(String globalId, String kind, String sourceId, String ss, String href) {
        return new JsonObject()
                .put("globalId", globalId)
                .put("kind", kind)
                .put("level", OeipFormat.LEVEL_CORE)
                .put("sourceSystem", ss)
                .put("serviceId", SERVICE_ID)
                .put("sourceId", sourceId)
                .put("href", href);
    }

    /**
     * L'index porte le nom du dossier lui-même — un libellé traduit. Le nom d'origine est donc
     * fourni par le contexte ; à défaut on cherche le seul fichier sans extension à la racine.
     */
    static JsonArray readIndex(Path folder, String folderName) throws IOException {
        Path candidate = folderName == null ? null : folder.resolve(folderName);
        if (candidate != null && Files.isRegularFile(candidate)) {
            return parse(candidate);
        }
        for (Path p : OeipChecksums.listFiles(folder)) {
            if (p.getParent().equals(folder) && !p.getFileName().toString().contains(".")
                    && !SKIP_DOCS.equals(p.getFileName().toString())) {
                JsonArray parsed = parse(p);
                if (parsed != null) {
                    return parsed;
                }
            }
        }
        return null;
    }

    private static JsonArray parse(Path p) {
        try {
            String raw = new String(Files.readAllBytes(p), StandardCharsets.UTF_8).trim();
            return raw.startsWith("[") ? new JsonArray(raw) : null;
        } catch (Exception e) {
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

    private static String nonEmpty(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value;
    }
}
