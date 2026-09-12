package org.entcore.interoperability.transcode;

import io.vertx.core.json.JsonObject;
import org.entcore.interoperability.OeipFormat;
import org.entcore.interoperability.packaging.OeipChecksums;
import org.entcore.interoperability.packaging.OeipManifestBuilder;
import org.entcore.interoperability.packaging.OeipPackageWriter;
import org.entcore.interoperability.schema.OeipSchemaRegistry;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Aller-retour du niveau Native : archive -> paquet OEIP -> archive.
 *
 * C'est ce qui donne à OEIP la couverture de tous les modules déjà gréés. Le paquet produit est
 * laissé dans target/ pour être validé ensuite par l'outil oeip_lint — les deux implémentations
 * doivent s'accorder, sans quoi un paquet produit par le Java serait suspect côté outillage.
 */
public class NativeRoundTripTest {

    private static Path work;

    /** Libellés de l'instance ÉMETTRICE : anglophone, volontairement. */
    private static final JsonObject I18N_SOURCE = new JsonObject()
            .put("blog", "Blog")
            .put("workspace", "Workspace");

    /** Libellés de l'instance DESTINATAIRE : francophone. */
    private static final JsonObject I18N_TARGET = new JsonObject()
            .put("blog", "Blog")
            .put("workspace", "Espace documentaire");

    @BeforeClass
    public static void setUp() throws IOException {
        work = Paths.get("target", "oeip-native-roundtrip");
        deleteRecursive(work);
        Files.createDirectories(work);
    }

    // ------------------------------------------------------------------ archive de départ

    /**
     * Fabrique une archive au format réel : un dossier racine unique, un Manifest.json dont les
     * clés sont les préfixes de route et les valeurs des libellés TRADUITS, et un dossier par
     * application.
     */
    private static byte[] syntheticArchive(String exportId, JsonObject i18n) throws IOException {
        JsonObject manifest = new JsonObject()
                .put("blog", new JsonObject()
                        .put("folder", i18n.getString("blog"))
                        .put("version", "6.14.9-patched"))
                .put("workspace", new JsonObject()
                        .put("folder", i18n.getString("workspace"))
                        .put("version", "6.14.9-patched"));

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ZipOutputStream zos = new ZipOutputStream(bos);
        put(zos, exportId + "/Manifest.json", manifest.encodePrettily());
        put(zos, exportId + "/" + i18n.getString("blog") + "/blog_Le blog de la 5e A",
                "{\"_id\":\"a1\",\"title\":\"blog_Le blog de la 5e A\"}");
        put(zos, exportId + "/" + i18n.getString("blog") + "/post_Sortie au museum",
                "{\"_id\":\"a2\",\"title\":\"post_Sortie au museum\"}");
        put(zos, exportId + "/" + i18n.getString("blog") + "/Documents/photo_a3.jpg", "binaire");
        put(zos, exportId + "/" + i18n.getString("workspace") + "/" + i18n.getString("workspace"),
                "[{\"_id\":\"w1\",\"name\":\"rapport.txt\"}]");
        put(zos, exportId + "/archive.signature", "{\"Manifest.json\":\"c2lnbmF0dXJl\"}");
        zos.close();
        return bos.toByteArray();
    }

    private static void put(ZipOutputStream zos, String name, String content) throws IOException {
        zos.putNextEntry(new ZipEntry(name));
        zos.write(content.getBytes(StandardCharsets.UTF_8));
        zos.closeEntry();
    }

    // ------------------------------------------------------------------ lecture de l'archive

    @Test
    public void litUneArchiveEtSesDossiersTraduits() throws IOException {
        Path dir = work.resolve("read");
        ArchiveBundle bundle = ArchiveBundle.unzip(
                syntheticArchive("1700000000000_" + uuid(), I18N_SOURCE), dir, 1 << 20);

        assertEquals(2, bundle.getServiceIds().size());
        assertTrue(bundle.getServiceIds().contains("blog"));
        assertEquals("6.14.9-patched", bundle.getVersion("blog"));

        // Le dossier est retrouvé via le manifeste, malgré un nom traduit.
        Path blog = bundle.folderFor("blog");
        assertNotNull("le dossier blog doit être résolu par le manifeste", blog);
        assertEquals("Blog", blog.getFileName().toString());
        assertEquals(3, bundle.countFiles("blog"));

        Path ws = bundle.folderFor("workspace");
        assertNotNull(ws);
        assertEquals("Workspace", ws.getFileName().toString());
    }

    @Test
    public void refuseUneArchiveSansRacineUnique() throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ZipOutputStream zos = new ZipOutputStream(bos);
        put(zos, "a/Manifest.json", "{}");
        put(zos, "b/autre.json", "{}");
        zos.close();
        try {
            ArchiveBundle.unzip(bos.toByteArray(), work.resolve("two-roots"), 1 << 20);
            org.junit.Assert.fail("une archive à deux racines doit être refusée");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("racine unique"));
        }
    }

    @Test
    public void refuseUneEntreeQuiRemonte() throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ZipOutputStream zos = new ZipOutputStream(bos);
        put(zos, "../evasion.json", "{}");
        zos.close();
        try {
            ArchiveBundle.unzip(bos.toByteArray(), work.resolve("slip"), 1 << 20);
            org.junit.Assert.fail("une entrée remontant hors du dossier doit être refusée");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("remontée"));
        }
    }

    @Test
    public void plafonneLeVolumeDecompresse() throws IOException {
        StringBuilder gros = new StringBuilder();
        for (int i = 0; i < 5000; i++) {
            gros.append("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ZipOutputStream zos = new ZipOutputStream(bos);
        put(zos, "x/Manifest.json", "{}");
        put(zos, "x/gros.bin", gros.toString());
        zos.close();
        try {
            ArchiveBundle.unzip(bos.toByteArray(), work.resolve("bomb"), 1024);
            org.junit.Assert.fail("le volume décompressé doit être plafonné");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("plafond"));
        }
    }

    // ------------------------------------------------------------------ production du paquet

    @Test
    public void produitUnPaquetNativeOnlyValidable() throws IOException {
        Path dir = work.resolve("pack-src");
        ArchiveBundle bundle = ArchiveBundle.unzip(
                syntheticArchive("1700000000000_" + uuid(), I18N_SOURCE), dir, 1 << 20);

        Path staging = work.resolve("package");
        Files.createDirectories(staging);
        OeipPackageWriter writer = new OeipPackageWriter(staging);
        OeipSchemaRegistry schemas = new OeipSchemaRegistry();

        OeipManifestBuilder builder = new OeipManifestBuilder(
                "ent.exemple-a.fr", "6.14.9-patched", "6.14.9-patched")
                .generatedAt("2026-09-12T09:00:00Z")
                .emitNative(true)
                .schemaBundleSha256(schemas.getBundleSha256())
                .scope("person", "urn:oeip:1.0:person:ent.exemple-a.fr:person-0001");

        for (String serviceId : bundle.getServiceIds()) {
            Path source = bundle.folderFor(serviceId);
            if (source == null) {
                continue;
            }
            // Le dossier est renommé avec le PRÉFIXE DE ROUTE : le paquet ne doit jamais
            // dépendre de la langue de l'instance qui l'a produit.
            writer.copyTree("native/" + serviceId, source);
            builder.addNativeOnlyService(serviceId, null, null, bundle.getVersion(serviceId),
                    new JsonObject().put("files", bundle.countFiles(serviceId)),
                    "Aucun mapping sémantique en 1.0 : réimportable uniquement dans un Open ENT.");
        }

        writer.putSchemas(schemas.getRawSchemas());
        writer.putJson(OeipFormat.IDENTIFIERS, builder.buildEmptyIdentifiers());

        JsonObject manifest = builder.build();
        Path pkg = writer.seal(manifest, work.resolve("native-only-1.0.oeip"));

        assertTrue(Files.isRegularFile(pkg));

        // Le dossier natif porte le serviceId, pas le libellé traduit de l'émetteur.
        assertTrue(Files.isDirectory(staging.resolve("native/workspace")));
        assertFalse("le libellé traduit ne doit pas survivre dans le paquet",
                Files.exists(staging.resolve("native/Workspace")));

        JsonObject sealed = new JsonObject(
                new String(Files.readAllBytes(staging.resolve(OeipFormat.MANIFEST)), StandardCharsets.UTF_8));

        assertEquals(OeipFormat.FORMAT_ID, sealed.getString("format"));
        assertTrue(sealed.getJsonObject("levels").getBoolean("native"));
        assertNotNull(sealed.getJsonObject("nativeFormat"));
        assertEquals(64, sealed.getJsonObject("integrity").getString("checksumsSha256").length());

        // La fidélité est déclarée service par service, et justifiée.
        JsonObject blog = sealed.getJsonArray("services").getJsonObject(0);
        assertEquals(OeipFormat.FIDELITY_NATIVE_ONLY, blog.getString("fidelity"));
        assertNotNull(blog.getString("notice"));

        // Et le paquet avertit franchement qu'il ne vaut pas comme interopérabilité.
        String warnings = sealed.getJsonArray("warnings").encode();
        assertTrue("un paquet sans service normalisé doit le dire", warnings.contains("core.empty"));

        assertTrue("le relevé de sommes doit être intègre",
                OeipChecksums.verify(staging).isEmpty());
    }

    @Test
    public void detecteUnPaquetAltere() throws IOException {
        Path staging = work.resolve("tamper");
        Files.createDirectories(staging);
        OeipPackageWriter writer = new OeipPackageWriter(staging);
        writer.putText("resources/blog/note.txt", "contenu d'origine");
        OeipChecksums.write(staging);
        assertTrue(OeipChecksums.verify(staging).isEmpty());

        writer.putText("resources/blog/note.txt", "contenu altéré");
        assertFalse("une altération doit être détectée", OeipChecksums.verify(staging).isEmpty());
    }

    // ------------------------------------------------------------------ réinjection

    @Test
    public void reinjecteEnRenommantLesDossiersDansLaLangueDeLaCible() throws IOException {
        Path dir = work.resolve("sink-src");
        ArchiveBundle bundle = ArchiveBundle.unzip(
                syntheticArchive("1700000000000_" + uuid(), I18N_SOURCE), dir, 1 << 20);

        Map<String, Path> natives = new LinkedHashMap<String, Path>();
        Map<String, String> versions = new LinkedHashMap<String, String>();
        for (String serviceId : bundle.getServiceIds()) {
            natives.put(serviceId, bundle.folderFor(serviceId));
            versions.put(serviceId, bundle.getVersion(serviceId));
        }

        String userId = uuid();
        String importId = ArchiveImportSink.newImportId(userId);
        Path importPath = work.resolve("import");

        // Instance destinataire francophone : les dossiers doivent être renommés.
        ArchiveImportSink sink = new ArchiveImportSink(new ArchiveFolderResolver(I18N_TARGET));
        Path archive = sink.buildArchive(natives, versions, importId, importPath);

        assertTrue(Files.isRegularFile(archive));
        assertEquals("le fichier doit porter l'identifiant d'import, sans extension",
                importId, archive.getFileName().toString());

        // On relit l'archive reconstruite avec le lecteur : elle doit être acceptable.
        Path back = work.resolve("sink-check");
        ArchiveBundle rebuilt = ArchiveBundle.unzip(Files.readAllBytes(archive), back, 1 << 20);

        assertEquals(importId, rebuilt.getRoot().getFileName().toString());
        assertEquals("6.14.9-patched", rebuilt.getVersion("workspace"));

        // Le point capital : le dossier porte désormais le libellé de la CIBLE.
        Path ws = rebuilt.folderFor("workspace");
        assertNotNull(ws);
        assertEquals("Espace documentaire", ws.getFileName().toString());
        assertEquals("Blog", rebuilt.folderFor("blog").getFileName().toString());
        assertEquals(3, rebuilt.countFiles("blog"));
    }

    @Test
    public void refuseUnIdentifiantDImportMalForme() throws IOException {
        ArchiveImportSink sink = new ArchiveImportSink(new ArchiveFolderResolver(I18N_TARGET));
        assertFalse(ArchiveImportSink.isValidImportId("import-42"));
        assertTrue(ArchiveImportSink.isValidImportId("1700000000000_" + uuid()));
        try {
            sink.buildArchive(new LinkedHashMap<String, Path>(), null, "import-42", work.resolve("bad"));
            org.junit.Assert.fail("deleteArchive impose la forme <millis>_<userId>");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("invalide"));
        }
    }

    // ------------------------------------------------------------------ utilitaires

    private static String uuid() {
        return java.util.UUID.randomUUID().toString();
    }

    private static void deleteRecursive(Path p) throws IOException {
        if (!Files.exists(p)) {
            return;
        }
        java.io.File[] children = p.toFile().listFiles();
        if (children != null) {
            for (java.io.File c : children) {
                deleteRecursive(c.toPath());
            }
        }
        Files.deleteIfExists(p);
    }
}
