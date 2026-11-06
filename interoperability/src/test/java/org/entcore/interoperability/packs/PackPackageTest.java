package org.entcore.interoperability.packs;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.entcore.interoperability.OeipFormat;
import org.entcore.interoperability.packaging.OeipChecksums;
import org.entcore.interoperability.packaging.OeipPackageReader;
import org.entcore.interoperability.schema.OeipSchemaRegistry;
import org.entcore.interoperability.schema.OeipValidator;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class PackPackageTest {

    private static final String PACK = "urn:openent:pack:essai-ce1-soustraction";
    private static final String CFITEM = "5d7f3c1e-1b2a-5c3d-9e4f-0a1b2c3d4e5f";

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private final OeipValidator validator = new OeipValidator(new OeipSchemaRegistry(OeipFormat.VERSION_1_1));

    @Test
    public void un_pack_devient_un_descripteur_d_installation() throws Exception {
        final JsonObject descriptor = PackPackage.read(open(build(manifest(), true, null)), validator);
        assertEquals(PACK, descriptor.getJsonObject("pack").getString("id"));
        final JsonArray resources = descriptor.getJsonArray("resources");
        assertEquals(1, resources.size());
        final JsonObject wiki = resources.getJsonObject(0);
        assertEquals("wiki", wiki.getString("service"));
        assertEquals(new JsonArray().add(CFITEM), wiki.getJsonArray("alignments"));
        assertEquals("La soustraction posée", wiki.getJsonObject("document").getString("title"));
        // La ressource sans document natif est écartée, et nommée.
        assertEquals(1, descriptor.getJsonArray("warnings").size());
    }

    @Test
    public void un_pack_altere_est_refuse_en_entier() throws Exception {
        try {
            PackPackage.read(open(build(manifest(), true, "resources/wiki/resources.json")), validator);
            fail("paquet altéré accepté");
        } catch (PackPackage.PackException e) {
            assertTrue(e.getMessage(), e.getMessage().startsWith("paquet altéré"));
        }
    }

    @Test
    public void un_paquet_qui_n_est_pas_un_pack_est_refuse() throws Exception {
        try {
            PackPackage.read(open(build(manifest().put("profile", "resources-only"), true, null)), validator);
            fail("paquet non pack accepté");
        } catch (PackPackage.PackException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("profil « pack »"));
        }
    }

    @Test
    public void un_pack_sans_identite_est_non_conforme() throws Exception {
        final JsonObject manifest = manifest();
        manifest.remove("pack");
        try {
            PackPackage.read(open(build(manifest, true, null)), validator);
            fail("pack sans identité accepté");
        } catch (PackPackage.PackException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("schéma OEIP 1.1"));
        }
    }

    @Test
    public void l_icone_du_pack_accompagne_le_descripteur() throws Exception {
        final JsonObject manifest = manifest();
        manifest.getJsonObject("pack").put("icon", new JsonObject().put("href", "pack/icon.svg")
                .put("mediaType", "image/svg+xml").put("sha256", sha256(ICON)));
        final JsonObject icon = PackPackage.read(open(build(manifest, true, null)), validator).getJsonObject("icon");
        assertEquals("image/svg+xml", icon.getString("mediaType"));
        assertEquals(ICON, new String(Base64.getDecoder().decode(icon.getString("data")), StandardCharsets.UTF_8));
    }

    @Test
    public void une_icone_qui_n_est_pas_une_image_est_refusee() throws Exception {
        final JsonObject manifest = manifest();
        manifest.getJsonObject("pack").put("icon", new JsonObject().put("href", "pack/icon.svg")
                .put("mediaType", "text/html").put("sha256", sha256(ICON)));
        try {
            PackPackage.read(open(build(manifest, true, null)), validator);
            fail("icône HTML acceptée");
        } catch (PackPackage.PackException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("schéma OEIP 1.1"));
        }
    }

    @Test
    public void une_icone_qui_ne_correspond_pas_au_manifeste_est_refusee() throws Exception {
        final JsonObject manifest = manifest();
        manifest.getJsonObject("pack").put("icon", new JsonObject().put("href", "pack/icon.svg")
                .put("mediaType", "image/svg+xml").put("sha256", sha256("<svg/>")));
        try {
            PackPackage.read(open(build(manifest, true, null)), validator);
            fail("icône d'empreinte fausse acceptée");
        } catch (PackPackage.PackException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("empreinte"));
        }
    }

    // ------------------------------------------------------------------ construction du paquet

    private static final String ICON = "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 8 8\"><circle cx=\"4\" cy=\"4\" r=\"4\"/></svg>";

    private static String sha256(String content) throws Exception {
        final StringBuilder hex = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8))) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }

    private static JsonObject manifest() {
        return new JsonObject()
                .put("format", OeipFormat.FORMAT_ID)
                .put("oeipVersion", "1.1")
                .put("profile", "pack")
                .put("levels", new JsonObject().put("core", true).put("cc", false).put("native", false))
                .put("source", new JsonObject().put("sourceSystem", "ent-scolaire.fr").put("product", "open-ent"))
                .put("generatedAt", "2026-11-03T12:00:00Z")
                .put("scope", new JsonObject().put("type", "platform").put("refs", new JsonArray()))
                .put("services", new JsonArray().add(new JsonObject().put("id", "wiki").put("normalized", true)
                        .put("native", false).put("fidelity", "full")
                        .put("paths", new JsonObject().put("core", "resources/wiki"))))
                .put("integrity", new JsonObject().put("algorithm", "sha256").put("checksumsFile", "checksums.sha256"))
                .put("schemaBundle", new JsonObject().put("path", "schemas/1.1").put("sha256",
                        new OeipSchemaRegistry(OeipFormat.VERSION_1_1).getBundleSha256()))
                .put("pack", new JsonObject().put("id", PACK).put("version", "0.1.0")
                        .put("title", "CE1 — La soustraction posée")
                        .put("framework", "2b931521-4371-534c-8201-b5c0ab29f0d8"));
    }

    private static JsonObject resources() {
        final JsonObject withDocument = new JsonObject()
                .put("globalId", "urn:oeip:1.1:resource:ent-scolaire.fr:pack-ce1-soustraction-wiki")
                .put("sourceSystem", "ent-scolaire.fr").put("serviceId", "wiki")
                .put("resourceType", "urn:oeip:restype:wiki.wiki")
                .put("title", "La soustraction posée")
                .put("alignments", new JsonArray().add(new JsonObject().put("item", CFITEM).put("code", "C2.MAT.1.3.2")))
                .put("extensions", new JsonObject().put("wiki", new JsonObject().put("title", "La soustraction posée")
                        .put("pages", new JsonArray())));
        final JsonObject withoutDocument = new JsonObject()
                .put("globalId", "urn:oeip:1.1:resource:ent-scolaire.fr:pack-ce1-soustraction-vide")
                .put("sourceSystem", "ent-scolaire.fr").put("serviceId", "wiki")
                .put("resourceType", "urn:oeip:restype:wiki.wiki").put("title", "Sans contenu");
        return new JsonObject().put("oeipVersion", "1.1").put("sourceSystem", "ent-scolaire.fr")
                .put("serviceId", "wiki").put("dataset", "resources")
                .put("items", new JsonArray().add(withDocument).add(withoutDocument));
    }

    /** Écrit le paquet, son relevé de sommes, puis altère {@code tamper} après coup s'il est donné. */
    private Path build(JsonObject manifest, boolean checksums, String tamper) throws IOException {
        final Path root = tmp.newFolder().toPath();
        write(root.resolve(OeipFormat.MANIFEST), manifest.encodePrettily());
        write(root.resolve("resources/wiki/resources.json"), resources().encodePrettily());
        write(root.resolve("pack/icon.svg"), ICON);
        if (checksums) {
            OeipChecksums.write(root);
        }
        if (tamper != null) {
            write(root.resolve(tamper), resources().put("generatedAt", "2026-11-04T00:00:00Z").encodePrettily());
        }
        final Path zip = tmp.newFile("pack.oeip").toPath();
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            for (Path file : OeipChecksums.listFiles(root)) {
                out.putNextEntry(new ZipEntry(OeipChecksums.relative(root, file)));
                out.write(Files.readAllBytes(file));
                out.closeEntry();
            }
        }
        return zip;
    }

    private OeipPackageReader open(Path zip) throws IOException {
        return OeipPackageReader.open(zip, tmp.newFolder().toPath(), 100L * 1024 * 1024);
    }

    private static void write(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        try (OutputStream out = Files.newOutputStream(path)) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
        }
    }
}
