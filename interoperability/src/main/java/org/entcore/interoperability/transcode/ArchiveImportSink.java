package org.entcore.interoperability.transcode;

import io.vertx.core.json.JsonObject;
import org.entcore.interoperability.packaging.OeipChecksums;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Reconstruit une archive personnelle à partir de la charge utile du niveau Native, pour la
 * réinjecter dans le chemin d'import éprouvé du module archive.
 *
 * C'est la pièce qui donne à OEIP la couverture de tous les modules déjà gréés, sans une ligne
 * de code par module. Le procédé n'est pas une invention : DefaultStructureImportService fait
 * déjà exactement cela pour la restauration groupée — il isole le dossier d'un compte, le
 * re-zippe et appelle importFromFile.
 *
 * Deux contraintes du format d'archive sont impératives ici :
 *  - le zip doit contenir UN dossier racine unique, sans quoi analyzeArchive refuse ;
 *  - les noms de dossiers doivent être les libellés de l'instance DESTINATAIRE, et non ceux du
 *    paquet, sans quoi un paquet émis par une instance anglophone échoue à l'import.
 */
public class ArchiveImportSink {

    /** deleteArchive impose cette forme : l'identifiant porte le compte destinataire. */
    private static final Pattern IMPORT_ID = Pattern.compile("^[0-9]+_[0-9a-fA-F-]{36}$");

    private final ArchiveFolderResolver folders;

    public ArchiveImportSink(ArchiveFolderResolver folders) {
        this.folders = folders;
    }

    public static String newImportId(String userId) {
        return System.currentTimeMillis() + "_" + userId;
    }

    public static boolean isValidImportId(String importId) {
        return importId != null && IMPORT_ID.matcher(importId).matches();
    }

    /**
     * Écrit l'archive reconstruite à l'emplacement attendu par importFromFile.
     *
     * @param nativeDirs   serviceId -> dossier de la charge utile native
     * @param versions     serviceId -> version du module ayant produit la charge utile, ou null
     * @param importId     identifiant d'import, également nom du fichier et du dossier racine
     * @param importPath   répertoire d'import configuré du module archive
     * @return le chemin du zip écrit
     */
    public Path buildArchive(Map<String, Path> nativeDirs, Map<String, String> versions,
                             String importId, Path importPath) throws IOException {
        if (!isValidImportId(importId)) {
            throw new IOException("Identifiant d'import invalide : " + importId
                    + " (forme attendue « <millis>_<userId> »)");
        }
        Files.createDirectories(importPath);
        Path target = importPath.resolve(importId);

        JsonObject manifest = new JsonObject();
        OutputStream out = Files.newOutputStream(target);
        ZipOutputStream zos = new ZipOutputStream(out);
        try {
            for (Map.Entry<String, Path> e : nativeDirs.entrySet()) {
                String serviceId = e.getKey();
                Path source = e.getValue();
                if (source == null || !Files.isDirectory(source)) {
                    continue;
                }
                // Le dossier est nommé avec le libellé de CETTE instance, pas celui du paquet.
                String folder = folders.folderFor(serviceId);

                JsonObject entry = new JsonObject().put("folder", folder);
                String version = versions == null ? null : versions.get(serviceId);
                if (version != null && !version.isEmpty()) {
                    entry.put("version", version);
                }
                manifest.put(serviceId, entry);

                List<Path> files = OeipChecksums.listFiles(source);
                for (Path f : files) {
                    String rel = OeipChecksums.relative(source, f);
                    writeEntry(zos, importId + "/" + folder + "/" + rel, Files.readAllBytes(f));
                }
            }
            writeEntry(zos, importId + "/" + ArchiveBundle.MANIFEST,
                    manifest.encodePrettily().getBytes(StandardCharsets.UTF_8));
            // Aucune signature n'est reconstruite : la signature d'archive suppose la même paire
            // de clés des deux côtés, ce qui n'a pas de sens entre plateformes. verifyImport
            // accepte une archive sans signature, sauf si force-encryption est actif — auquel cas
            // la réinjection native est impossible et doit être refusée en amont.
        } finally {
            zos.close();
        }
        return target;
    }

    private static void writeEntry(ZipOutputStream zos, String name, byte[] content) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setTime(0L);
        zos.putNextEntry(entry);
        zos.write(content);
        zos.closeEntry();
    }
}
