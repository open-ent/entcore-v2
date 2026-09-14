package org.entcore.interoperability.live;

import io.vertx.core.json.JsonObject;
import org.entcore.interoperability.packaging.OeipPackageReader;
import org.entcore.interoperability.transcode.ArchiveBundle;
import org.entcore.interoperability.transcode.ArchiveFolderResolver;
import org.entcore.interoperability.transcode.ArchiveImportSink;
import org.junit.Assume;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.*;

/**
 * Vérification sur un paquet RÉEL, produit par la plateforme locale.
 *
 * Les tests unitaires travaillent sur une archive synthétique : celui-ci prend la charge utile
 * réellement produite par les modules blog et workspace, pour s'assurer que la reconversion en
 * archive reste acceptable. Il ne s'exécute que si le paquet est présent.
 */
public class LivePackageReinjectionTest {

    /**
     * Chemin d'un paquet réellement produit par une plateforme, surchargeable par
     * {@code -Doeip.live.package=...}. Le test est IGNORÉ quand le fichier est absent — il
     * apparaît alors en « Skipped » dans le rapport, jamais en vert trompeur.
     */
    private static final Path LIVE = Paths.get(
            System.getProperty("oeip.live.package", "/tmp/oeip-live-export.oeip"));

    private static void deleteRecursive(Path p) throws java.io.IOException {
        if (!Files.exists(p)) return;
        java.io.File[] children = p.toFile().listFiles();
        if (children != null) {
            for (java.io.File c : children) deleteRecursive(c.toPath());
        }
        Files.deleteIfExists(p);
    }

    @Test
    public void lePaquetReelSeReconvertitEnArchiveRelisible() throws Exception {
        Assume.assumeTrue("paquet réel absent — test ignoré", Files.isRegularFile(LIVE));

        Path work = Paths.get("target", "live-reinjection");
        // Un test qui rejoue par-dessus son propre résultat précédent se ment à lui-même :
        // deux racines apparaissent là où une seule est attendue.
        deleteRecursive(work);
        OeipPackageReader reader = OeipPackageReader.open(LIVE, work.resolve("unzipped"), 1L << 30);

        assertTrue("l'intégrité doit être vérifiable sans clé", reader.verifyIntegrity().isEmpty());
        assertTrue("la charge utile native vient bien d'un Open ENT", reader.isNativeReadable());
        assertFalse("le paquet doit contenir des services", reader.nativeDirs().isEmpty());

        // Instance destinataire francophone : les dossiers doivent être renommés.
        JsonObject i18n = new JsonObject()
                .put("blog", "Blog")
                .put("workspace", "Espace documentaire");

        String importId = ArchiveImportSink.newImportId("ec847027-d5c6-455f-a599-43753924dff2");
        Path archive = new ArchiveImportSink(new ArchiveFolderResolver(i18n))
                .buildArchive(reader.nativeDirs(), reader.nativeVersions(), importId,
                        work.resolve("import"));

        // L'archive reconstruite doit satisfaire les contraintes d'analyzeArchive : racine
        // unique et Manifest.json présent — c'est exactement ce que vérifie notre lecteur.
        ArchiveBundle rebuilt = ArchiveBundle.unzip(Files.readAllBytes(archive),
                work.resolve("check"), 1L << 30);

        assertEquals(importId, rebuilt.getRoot().getFileName().toString());
        for (String serviceId : reader.nativeDirs().keySet()) {
            assertNotNull("service absent de l'archive reconstruite : " + serviceId,
                    rebuilt.folderFor(serviceId));
        }
        assertEquals("Espace documentaire",
                rebuilt.folderFor("workspace").getFileName().toString());

        System.out.println("[live] " + reader.nativeDirs().keySet()
                + " reconvertis en archive " + importId
                + " (" + Files.size(archive) + " octets)");
    }
}
