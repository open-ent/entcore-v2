package org.entcore.interoperability.packaging;

import io.vertx.core.json.JsonObject;
import org.entcore.interoperability.OeipFormat;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Écrit un paquet OEIP à partir d'un dossier de préparation.
 *
 * Le zip est écrit ici plutôt que par mod-zip pour deux raisons : mod-zip n'accepte qu'un
 * niveau de compression GLOBAL, alors qu'OEIP veut compresser le JSON et stocker les binaires
 * tels quels ; et écrire nous-mêmes donne la maîtrise de l'ordre des entrées, indispensable à
 * la reproductibilité — sans quoi aucune régression de format n'est détectable par diff.
 */
public class OeipPackageWriter {

    /** Horodatage figé : un paquet au contenu identique doit donner un zip identique. */
    private static final long FIXED_TIME = 315532800000L; // 1980-01-01T00:00:00Z

    private static final Set<String> TEXT_SUFFIXES = new HashSet<String>();
    static {
        TEXT_SUFFIXES.add(".json");
        TEXT_SUFFIXES.add(".xml");
        TEXT_SUFFIXES.add(".html");
        TEXT_SUFFIXES.add(".txt");
        TEXT_SUFFIXES.add(".md");
        TEXT_SUFFIXES.add(".csv");
        TEXT_SUFFIXES.add(".sha256");
    }

    private final Path stagingDir;

    public OeipPackageWriter(Path stagingDir) {
        this.stagingDir = stagingDir;
    }

    public Path getStagingDir() {
        return stagingDir;
    }

    /** Dépose un document JSON dans le paquet, en écriture canonique. */
    public void putJson(String packagePath, JsonObject document) throws IOException {
        putBytes(packagePath, (document.encodePrettily() + "\n").getBytes(StandardCharsets.UTF_8));
    }

    public void putText(String packagePath, String content) throws IOException {
        putBytes(packagePath, content.getBytes(StandardCharsets.UTF_8));
    }

    public void putBytes(String packagePath, byte[] content) throws IOException {
        Path target = resolveSafely(packagePath);
        Files.createDirectories(target.getParent());
        Files.write(target, content);
    }

    public void copyFile(String packagePath, Path source) throws IOException {
        Path target = resolveSafely(packagePath);
        Files.createDirectories(target.getParent());
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
    }

    /** Recopie récursive d'un dossier, utilisée pour la charge utile du niveau Native. */
    public void copyTree(String packagePathPrefix, Path sourceDir) throws IOException {
        List<Path> files = OeipChecksums.listFiles(sourceDir);
        for (Path f : files) {
            String rel = OeipChecksums.relative(sourceDir, f);
            copyFile(packagePathPrefix + "/" + rel, f);
        }
    }

    /** Recopie le lot de schémas : c'est ce qui rend le paquet validable hors ligne. */
    public void putSchemas(java.util.Map<String, String> rawSchemas) throws IOException {
        for (java.util.Map.Entry<String, String> e : rawSchemas.entrySet()) {
            putText(OeipFormat.SCHEMA_PACKAGE_DIR + "/" + e.getKey(), e.getValue());
        }
    }

    /**
     * Scelle le paquet : relevé de sommes, épinglage dans le manifeste, puis zip.
     *
     * L'ordre compte. Le manifeste est exclu du relevé et épingle l'empreinte de celui-ci :
     * le relevé doit donc être écrit AVANT que le manifeste soit finalisé.
     */
    public Path seal(JsonObject manifest, Path targetZip) throws IOException {
        OeipChecksums.write(stagingDir);

        String checksumsSha = OeipChecksums.sha256(stagingDir.resolve(OeipFormat.CHECKSUMS));
        manifest.put("integrity", new JsonObject()
                .put("algorithm", "sha256")
                .put("checksumsFile", OeipFormat.CHECKSUMS)
                .put("checksumsSha256", checksumsSha));
        putJson(OeipFormat.MANIFEST, manifest);

        return zip(targetZip);
    }

    private Path zip(Path targetZip) throws IOException {
        if (targetZip.getParent() != null) {
            Files.createDirectories(targetZip.getParent());
        }
        OutputStream out = Files.newOutputStream(targetZip);
        ZipOutputStream zos = new ZipOutputStream(out);
        try {
            for (Path f : OeipChecksums.listFiles(stagingDir)) {
                String name = OeipChecksums.relative(stagingDir, f);
                ZipEntry entry = new ZipEntry(name);
                entry.setTime(FIXED_TIME);
                byte[] content = Files.readAllBytes(f);
                if (isText(name)) {
                    entry.setMethod(ZipEntry.DEFLATED);
                } else {
                    // Les binaires sont déjà compressés dans la plupart des cas : les stocker
                    // tels quels évite de gonfler le temps d'export pour rien.
                    entry.setMethod(ZipEntry.STORED);
                    entry.setSize(content.length);
                    entry.setCompressedSize(content.length);
                    java.util.zip.CRC32 crc = new java.util.zip.CRC32();
                    crc.update(content, 0, content.length);
                    entry.setCrc(crc.getValue());
                }
                zos.putNextEntry(entry);
                zos.write(content);
                zos.closeEntry();
            }
        } finally {
            zos.close();
        }
        return targetZip;
    }

    static boolean isText(String name) {
        int dot = name.lastIndexOf('.');
        return dot >= 0 && TEXT_SUFFIXES.contains(name.substring(dot).toLowerCase());
    }

    /**
     * Aucun chemin ne sort du dossier de préparation, même construit à partir d'un nom de
     * dossier venu d'une archive : c'est le même risque qu'un zip-slip, pris à l'écriture.
     */
    private Path resolveSafely(String packagePath) throws IOException {
        String reason = SafeZip.rejectEntryName(packagePath);
        if (reason != null) {
            throw new IOException("Chemin de paquet refusé — " + reason);
        }
        Path target = stagingDir.resolve(packagePath).normalize();
        if (!target.startsWith(stagingDir.normalize())) {
            throw new IOException("Chemin de paquet hors du dossier de préparation : " + packagePath);
        }
        return target;
    }
}
