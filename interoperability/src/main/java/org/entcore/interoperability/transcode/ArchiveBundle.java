package org.entcore.interoperability.transcode;

import io.vertx.core.json.JsonObject;
import org.entcore.interoperability.packaging.SafeZip;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Une archive personnelle dézippée, et son manifeste.
 *
 * Rappel du format d'archive, qu'OEIP consomme sans le modifier : le zip contient UN dossier
 * racine unique nommé « <millis>_<userId> », qui porte Manifest.json et un dossier par
 * application. Les clés du manifeste sont les préfixes de route — stables — mais les valeurs
 * « folder » sont des libellés TRADUITS, ce qui est précisément la raison pour laquelle
 * l'archive ne peut pas servir de format d'échange telle quelle.
 */
public class ArchiveBundle {

    public static final String MANIFEST = "Manifest.json";
    public static final String SIGNATURE = "archive.signature";

    private final Path root;
    private final JsonObject manifest;
    private final Map<String, String> folderByService = new LinkedHashMap<String, String>();
    private final Map<String, String> versionByService = new LinkedHashMap<String, String>();

    private ArchiveBundle(Path root, JsonObject manifest) {
        this.root = root;
        this.manifest = manifest;
        parseManifest();
    }

    /**
     * Dézippe une archive dans {@code targetDir} après contrôle de chaque entrée.
     *
     * @throws IOException si une entrée est dangereuse, si la racine n'est pas unique, ou si
     *                     le manifeste est absent
     */
    public static ArchiveBundle unzip(byte[] zipBytes, Path targetDir, long maxTotalBytes) throws IOException {
        Files.createDirectories(targetDir);

        long total = 0L;
        int entries = 0;
        ZipInputStream zis = new ZipInputStream(new java.io.ByteArrayInputStream(zipBytes));
        try {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String name = entry.getName();
                if (name.endsWith("/")) {
                    continue;
                }
                String reason = SafeZip.rejectEntryName(name);
                if (reason != null) {
                    throw new IOException("Archive refusée — " + reason);
                }
                if (++entries > SafeZip.DEFAULT_MAX_ENTRIES) {
                    throw new IOException("Archive refusée — trop d'entrées (> "
                            + SafeZip.DEFAULT_MAX_ENTRIES + ")");
                }
                Path target = targetDir.resolve(name).normalize();
                if (!target.startsWith(targetDir.normalize())) {
                    throw new IOException("Archive refusée — entrée hors du dossier cible : " + name);
                }
                Files.createDirectories(target.getParent());
                OutputStream out = Files.newOutputStream(target);
                try {
                    byte[] buf = new byte[1 << 16];
                    int n;
                    while ((n = zis.read(buf)) != -1) {
                        total += n;
                        // Une taille déclarée peut mentir : le plafond est appliqué pendant
                        // la lecture du flux, pas d'après l'en-tête.
                        if (total > maxTotalBytes) {
                            throw new IOException("Archive refusée — volume décompressé au-delà du plafond");
                        }
                        out.write(buf, 0, n);
                    }
                } finally {
                    out.close();
                }
            }
        } finally {
            zis.close();
        }

        Path root = singleRoot(targetDir);
        Path manifestPath = root.resolve(MANIFEST);
        if (!Files.isRegularFile(manifestPath)) {
            throw new IOException("Archive non reconnue — " + MANIFEST + " absent de la racine");
        }
        JsonObject manifest = new JsonObject(
                new String(Files.readAllBytes(manifestPath), StandardCharsets.UTF_8));
        return new ArchiveBundle(root, manifest);
    }

    /** Le format d'archive impose une racine unique ; c'est aussi ce que vérifie analyzeArchive. */
    private static Path singleRoot(Path targetDir) throws IOException {
        java.io.File[] children = targetDir.toFile().listFiles();
        if (children == null || children.length != 1 || !children[0].isDirectory()) {
            throw new IOException("Archive non reconnue — un dossier racine unique est attendu");
        }
        return children[0].toPath();
    }

    private void parseManifest() {
        for (String key : manifest.fieldNames()) {
            Object value = manifest.getValue(key);
            // Le préfixe de route est la clé. Deux formes de manifeste coexistent :
            // {app: {folder, version}} — l'actuelle — et {app: "version"} — héritée, où le
            // dossier doit être redéduit du libellé traduit.
            if (value instanceof JsonObject) {
                JsonObject entry = (JsonObject) value;
                String folder = entry.getString("folder");
                if (folder != null) {
                    folderByService.put(key, folder);
                }
                if (entry.getString("version") != null) {
                    versionByService.put(key, entry.getString("version"));
                }
            } else if (value instanceof String) {
                versionByService.put(key, (String) value);
            }
        }
    }

    public Path getRoot() { return root; }
    public JsonObject getManifest() { return manifest; }

    public List<String> getServiceIds() {
        return new ArrayList<String>(manifest.fieldNames());
    }

    public String getVersion(String serviceId) { return versionByService.get(serviceId); }

    /**
     * @return le dossier de ce service dans l'archive, ou {@code null} s'il est introuvable.
     *         Le manifeste fait foi ; à défaut on retombe sur le préfixe de route brut, que
     *         createExportDirectory utilise quand la clé de traduction manque.
     */
    public Path folderFor(String serviceId) {
        String folder = folderByService.get(serviceId);
        if (folder != null) {
            Path p = root.resolve(folder);
            if (Files.isDirectory(p)) {
                return p;
            }
        }
        Path fallback = root.resolve(serviceId);
        return Files.isDirectory(fallback) ? fallback : null;
    }

    /** Compte les fichiers réellement produits par un service, hors sous-dossiers de binaires. */
    public int countFiles(String serviceId) throws IOException {
        Path dir = folderFor(serviceId);
        if (dir == null) {
            return 0;
        }
        return org.entcore.interoperability.packaging.OeipChecksums.listFiles(dir).size();
    }
}
