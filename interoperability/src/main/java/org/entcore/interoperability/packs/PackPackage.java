package org.entcore.interoperability.packs;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.entcore.interoperability.OeipFormat;
import org.entcore.interoperability.packaging.OeipChecksums;
import org.entcore.interoperability.packaging.OeipPackageReader;
import org.entcore.interoperability.schema.OeipValidationError;
import org.entcore.interoperability.schema.OeipValidator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Lecture d'un paquet OEIP 1.1 de profil {@code pack} en descripteur d'installation
 * (cf. {@link PackService}).
 *
 * <p>Un pack est refusé en entier s'il n'est pas intègre (relevé de sommes) ou si un de ses
 * fichiers normatifs ne respecte pas le schéma 1.1 : installer une partie d'un pack douteux
 * partagerait avec des établissements entiers un contenu dont on ne sait rien. Une ressource sans
 * document natif, elle, est seulement écartée et nommée dans {@code warnings}.</p>
 *
 * <p>Correspondance avec le modèle OEIP : une ressource de {@code resources/<service>/*.json}
 * (jeu {@code resources}) devient une ressource du pack ; son document natif est
 * {@code extensions.<service>}, son binaire éventuel {@code body.href} (fiche PDF de
 * l'espace documentaire), ses énoncés de référentiel {@code alignments[].item}. L'icône du pack
 * ({@code pack.icon}) devient {@code icon: {mediaType, sha256, data}}, l'image en base 64.</p>
 */
public final class PackPackage {

    /** Types d'icône admis : ceux que l'on peut montrer tels quels dans une balise {@code <img>}. */
    public static final Set<String> ICON_TYPES = new HashSet<String>(
            Arrays.asList("image/svg+xml", "image/png", "image/webp"));
    /** Une icône est montrée à côté de chaque ressource du pack : elle reste petite. */
    public static final long ICON_MAX_BYTES = 256L * 1024;

    private PackPackage() {
    }

    public static JsonObject read(OeipPackageReader pkg, OeipValidator validator) throws PackException, IOException {
        final JsonObject manifest = pkg.getManifest();
        final String version = manifest == null ? null : manifest.getString("oeipVersion");
        if (manifest == null || !OeipFormat.VERSION_1_1.equals(version)
                || !OeipFormat.PROFILE_PACK.equals(manifest.getString("profile"))) {
            throw new PackException("ce paquet n'est pas un pack : OEIP 1.1 de profil « pack » attendu (lu : "
                    + version + ", profil " + (manifest == null ? null : manifest.getString("profile")) + ")");
        }
        final List<String> integrity = pkg.verifyIntegrity();
        if (!integrity.isEmpty()) {
            throw new PackException("paquet altéré : " + head(integrity));
        }
        final List<String> errors = new ArrayList<String>();
        collect(errors, validator.validate(OeipFormat.MANIFEST, manifest,
                OeipValidator.schemaFor(OeipFormat.MANIFEST, version)));

        final JsonArray resources = new JsonArray();
        final JsonArray warnings = new JsonArray();
        final Path root = pkg.getRoot();
        for (Path file : OeipChecksums.listFiles(root)) {
            final String path = OeipChecksums.relative(root, file);
            final String schema = OeipValidator.schemaFor(path, version);
            if (schema == null || !path.startsWith("resources/")) {
                continue;
            }
            final JsonObject dataset = pkg.getJson(path);
            collect(errors, validator.validate(path, dataset, schema));
            if (dataset == null || !"resources".equals(dataset.getString("dataset"))) {
                continue;
            }
            for (Object o : dataset.getJsonArray("items", new JsonArray())) {
                final JsonObject item = (JsonObject) o;
                final String service = item.getString("serviceId");
                final JsonObject nativeDocument = item.getJsonObject("extensions", new JsonObject()).getJsonObject(service);
                final String href = item.getJsonObject("body", new JsonObject()).getString("href");
                if (nativeDocument == null && href == null) {
                    warnings.add("ressource " + item.getString("globalId") + " sans document natif "
                            + "(extensions." + service + ") ni fichier (body.href) : écartée");
                    continue;
                }
                final JsonObject document = nativeDocument == null ? new JsonObject() : nativeDocument;
                final JsonArray alignments = new JsonArray();
                for (Object a : item.getJsonArray("alignments", new JsonArray())) {
                    alignments.add(((JsonObject) a).getString("item"));
                }
                final JsonObject resource = new JsonObject();
                if (href != null) {
                    // Le binaire est couvert par le relevé de sommes : son intégrité est déjà vérifiée.
                    final Path binary = root.resolve(href).normalize();
                    if (!binary.startsWith(root)) {
                        throw new PackException("chemin hors du paquet : " + href);
                    }
                    resource.put("file", binary.toString());
                    document.put("contentType", document.getString("contentType",
                            item.getJsonObject("body").getString("mediaType")));
                }
                resources.add(resource
                        .put("globalId", item.getString("globalId"))
                        .put("service", service)
                        .put("title", item.getString("title"))
                        .put("alignments", alignments)
                        .put("document", document));
            }
        }
        if (!errors.isEmpty()) {
            throw new PackException("pack non conforme au schéma OEIP 1.1 : " + head(errors));
        }
        final JsonObject descriptor = new JsonObject()
                .put("pack", manifest.getJsonObject("pack"))
                .put("resources", resources)
                .put("warnings", warnings);
        final JsonObject icon = icon(root, manifest.getJsonObject("pack"));
        return icon == null ? descriptor : descriptor.put("icon", icon);
    }

    /**
     * Icône déclarée par le pack, vérifiée : dans le paquet, d'un type d'image admis, petite, et
     * conforme à l'empreinte du manifeste (le relevé de sommes l'a déjà couverte, mais le manifeste
     * pourrait désigner un autre fichier que celui qu'il décrit).
     */
    private static JsonObject icon(Path root, JsonObject pack) throws PackException, IOException {
        final JsonObject declared = pack == null ? null : pack.getJsonObject("icon");
        if (declared == null) {
            return null;
        }
        final String href = declared.getString("href");
        final String mediaType = declared.getString("mediaType");
        final Path file = root.resolve(href).normalize();
        if (!file.startsWith(root) || !Files.isRegularFile(file)) {
            throw new PackException("icône du pack introuvable dans le paquet : " + href);
        }
        if (!ICON_TYPES.contains(mediaType)) {
            throw new PackException("icône du pack : type " + mediaType + " refusé (SVG, PNG ou WebP attendu)");
        }
        if (Files.size(file) > ICON_MAX_BYTES) {
            throw new PackException("icône du pack trop lourde : " + Files.size(file) + " octets, "
                    + ICON_MAX_BYTES + " au plus");
        }
        if (!OeipChecksums.sha256(file).equals(declared.getString("sha256"))) {
            throw new PackException("icône du pack : empreinte différente de celle du manifeste");
        }
        return new JsonObject().put("mediaType", mediaType).put("sha256", declared.getString("sha256"))
                .put("data", Base64.getEncoder().encodeToString(Files.readAllBytes(file)));
    }

    private static void collect(List<String> acc, List<OeipValidationError> errors) {
        for (OeipValidationError e : errors) {
            acc.add(e.toString());
        }
    }

    private static String head(List<String> problems) {
        return problems.size() <= 5 ? String.join(" ; ", problems)
                : String.join(" ; ", problems.subList(0, 5)) + " (et " + (problems.size() - 5) + " autres)";
    }

    /** Pack refusé : le message est destiné à la personne qui l'installe. */
    public static class PackException extends Exception {
        public PackException(String message) {
            super(message);
        }
    }
}
