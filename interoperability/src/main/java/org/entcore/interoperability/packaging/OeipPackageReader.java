package org.entcore.interoperability.packaging;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.entcore.interoperability.OeipFormat;

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
 * Ouvre un paquet d'échange reçu d'une autre plateforme.
 *
 * Tout ce qui vient de l'extérieur est traité comme hostile : chaque entrée est contrôlée, le
 * volume décompressé est plafonné pendant la lecture, et l'intégrité est vérifiée AVANT toute
 * exploitation du contenu.
 */
public class OeipPackageReader {

    private final Path root;
    private final JsonObject manifest;

    private OeipPackageReader(Path root, JsonObject manifest) {
        this.root = root;
        this.manifest = manifest;
    }

    public static OeipPackageReader open(Path zipFile, Path targetDir, long maxUncompressedBytes)
            throws IOException {
        Files.createDirectories(targetDir);

        long total = 0L;
        int count = 0;
        InputStream raw = Files.newInputStream(zipFile);
        ZipInputStream zis = new ZipInputStream(raw);
        try {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                String name = entry.getName();
                String reason = SafeZip.rejectEntryName(name);
                if (reason != null) {
                    throw new IOException("Paquet refusé — " + reason);
                }
                if (++count > SafeZip.DEFAULT_MAX_ENTRIES) {
                    throw new IOException("Paquet refusé — trop d'entrées");
                }
                Path target = targetDir.resolve(name).normalize();
                if (!target.startsWith(targetDir.normalize())) {
                    throw new IOException("Paquet refusé — entrée hors du dossier cible : " + name);
                }
                Files.createDirectories(target.getParent());
                OutputStream out = Files.newOutputStream(target);
                try {
                    byte[] buf = new byte[1 << 16];
                    int n;
                    while ((n = zis.read(buf)) != -1) {
                        total += n;
                        if (total > maxUncompressedBytes) {
                            throw new IOException("Paquet refusé — volume décompressé au-delà du plafond");
                        }
                        out.write(buf, 0, n);
                    }
                } finally {
                    out.close();
                }
            }
        } finally {
            zis.close();
            raw.close();
        }

        Path manifestPath = targetDir.resolve(OeipFormat.MANIFEST);
        if (!Files.isRegularFile(manifestPath)) {
            // Message utile : c'est exactement l'erreur qu'on veut éviter de voir posée comme
            // « fichier non reconnu » sans explication.
            throw new IOException("Ce fichier n'est pas un paquet OEIP : " + OeipFormat.MANIFEST + " absent");
        }
        JsonObject manifest = new JsonObject(
                new String(Files.readAllBytes(manifestPath), StandardCharsets.UTF_8));
        return new OeipPackageReader(targetDir, manifest);
    }

    public Path getRoot() { return root; }
    public JsonObject getManifest() { return manifest; }

    public List<String> verifyIntegrity() throws IOException {
        return OeipChecksums.verify(root);
    }

    public JsonObject getJson(String packagePath) throws IOException {
        Path p = root.resolve(packagePath);
        if (!Files.isRegularFile(p)) {
            return null;
        }
        return new JsonObject(new String(Files.readAllBytes(p), StandardCharsets.UTF_8));
    }

    public boolean hasNativeLevel() {
        JsonObject levels = manifest.getJsonObject("levels", new JsonObject());
        return Boolean.TRUE.equals(levels.getBoolean(OeipFormat.LEVEL_NATIVE));
    }

    /**
     * La charge utile native n'est lisible que si elle vient du MÊME produit. Un ENT tiers
     * ignore ce niveau par construction : il n'en connaît pas la forme.
     */
    public boolean isNativeReadable() {
        if (!hasNativeLevel()) {
            return false;
        }
        JsonObject nf = manifest.getJsonObject("nativeFormat");
        return nf != null && OeipFormat.NATIVE_PRODUCT.equals(nf.getString("product"));
    }

    public String nativeArchiveVersion() {
        JsonObject nf = manifest.getJsonObject("nativeFormat");
        return nf == null ? null : nf.getString("archiveVersion");
    }

    /** serviceId -> dossier de charge utile native présent dans le paquet. */
    public Map<String, Path> nativeDirs() {
        Map<String, Path> dirs = new LinkedHashMap<String, Path>();
        JsonArray services = manifest.getJsonArray("services", new JsonArray());
        for (int i = 0; i < services.size(); i++) {
            JsonObject svc = services.getJsonObject(i);
            if (!Boolean.TRUE.equals(svc.getBoolean("native"))) {
                continue;
            }
            String id = svc.getString("id");
            Path dir = root.resolve("native").resolve(id);
            if (Files.isDirectory(dir)) {
                dirs.put(id, dir);
            }
        }
        return dirs;
    }

    /** serviceId -> nom du dossier chez l'émetteur, nécessaire au renommage de son index. */
    public Map<String, String> nativeFolders() {
        Map<String, String> folders = new LinkedHashMap<String, String>();
        JsonArray services = manifest.getJsonArray("services", new JsonArray());
        for (int i = 0; i < services.size(); i++) {
            JsonObject svc = services.getJsonObject(i);
            if (svc.getString("nativeFolder") != null) {
                folders.put(svc.getString("id"), svc.getString("nativeFolder"));
            }
        }
        return folders;
    }

    public Map<String, String> nativeVersions() {
        Map<String, String> versions = new LinkedHashMap<String, String>();
        JsonArray services = manifest.getJsonArray("services", new JsonArray());
        for (int i = 0; i < services.size(); i++) {
            JsonObject svc = services.getJsonObject(i);
            if (svc.getString("moduleVersion") != null) {
                versions.put(svc.getString("id"), svc.getString("moduleVersion"));
            }
        }
        return versions;
    }

    /** Description honnête de ce que le destinataire pourra réellement reprendre. */
    public JsonArray describeServices() {
        JsonArray out = new JsonArray();
        JsonArray services = manifest.getJsonArray("services", new JsonArray());
        Map<String, Path> natives = nativeDirs();
        for (int i = 0; i < services.size(); i++) {
            JsonObject svc = services.getJsonObject(i);
            String id = svc.getString("id");
            boolean nativeHere = natives.containsKey(id) && isNativeReadable();
            JsonObject desc = new JsonObject()
                    .put("id", id)
                    .put("declaredFidelity", svc.getString("fidelity"))
                    .put("applicableLevel", nativeHere ? OeipFormat.LEVEL_NATIVE : null)
                    .put("importable", nativeHere);
            if (svc.getString("notice") != null) {
                desc.put("notice", svc.getString("notice"));
            }
            if (!nativeHere) {
                desc.put("reason", isNativeReadable()
                        ? "charge utile absente du paquet"
                        : "charge utile native produite par un autre produit");
            }
            out.add(desc);
        }
        return out;
    }

    public List<String> importableServiceIds() {
        List<String> ids = new ArrayList<String>();
        if (isNativeReadable()) {
            ids.addAll(nativeDirs().keySet());
        }
        return ids;
    }
}
