package org.entcore.interoperability.providers;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.entcore.interoperability.OeipFormat;
import org.entcore.interoperability.schema.OeipSchemaRegistry;
import org.entcore.interoperability.schema.OeipValidationError;
import org.entcore.interoperability.schema.OeipValidator;
import org.entcore.interoperability.spi.OeipCoreExport;
import org.entcore.interoperability.spi.OeipExportContext;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * Le mapper de l'espace documentaire.
 *
 * L'assertion la plus importante de ce fichier n'est pas qu'un fichier présent soit décrit — elle
 * est qu'un fichier ANNONCÉ MAIS ABSENT soit avoué. C'est la seule chose que le niveau Interne ne
 * sait pas faire, et donc la justification du niveau Core.
 */
public class WorkspaceOeipMapperTest {

    private static final String SS = "ent.exemple-a.fr";
    private static final String INDEX = "Espace documentaire";

    private static OeipValidator validator;
    private static Path work;

    @BeforeClass
    public static void setUp() throws IOException {
        validator = new OeipValidator(new OeipSchemaRegistry());
        work = Paths.get("target", "workspace-mapper-test");
        deleteRecursive(work);
        Files.createDirectories(work);
    }

    private static JsonObject fileEntry(String id, String fileId, String name, String path) {
        return new JsonObject()
                .put("_id", id).put("eType", "file").put("file", fileId).put("name", name)
                .put("owner", "ec847027-d5c6-455f-a599-43753924dff2")
                .put("ownerName", "SHAFY001 Emaël")
                .put("localArchivePath", path)
                .put("created", new JsonObject().put("$date", "2026-08-14T13:00:50.310Z"))
                .put("metadata", new JsonObject().put("content-type", "text/plain"));
    }

    /**
     * @param withBinaries nombre de fichiers dont le binaire est réellement déposé ; l'index en
     *                     annonce toujours trois — l'écart est le sujet du test
     */
    private static Path payload(String name, int withBinaries, boolean skipDocs) throws IOException {
        Path folder = work.resolve(name);
        Files.createDirectories(folder.resolve("Documents personnels"));

        JsonArray index = new JsonArray()
                .add(new JsonObject().put("_id", "dossier-1").put("eType", "folder")
                        .put("name", "Sorties scolaires")
                        .put("owner", "ec847027-d5c6-455f-a599-43753924dff2")
                        .put("created", new JsonObject().put("$date", "2026-08-01T08:05:00.000Z")))
                .add(fileEntry("doc-1", "f1a11111-1111-4111-8111-111111111111", "Compte rendu.txt",
                        "Documents personnels/Compte rendu.txt"))
                .add(fileEntry("doc-2", "f2a22222-2222-4222-8222-222222222222", "Photo.png",
                        "Documents personnels/Photo.png"))
                .add(fileEntry("doc-3", "f3a33333-3333-4333-8333-333333333333", "Disparu.docx",
                        "Documents personnels/Disparu.docx"));

        write(folder.resolve(INDEX), index.encodePrettily());
        if (withBinaries > 0) {
            write(folder.resolve("Documents personnels/Compte rendu.txt"), "un compte rendu");
        }
        if (withBinaries > 1) {
            write(folder.resolve("Documents personnels/Photo.png"), "binaire-png");
        }
        // « Disparu.docx » n'est JAMAIS déposé : c'est le cas qui compte.
        if (skipDocs) {
            write(folder.resolve("skipDocs"), "");
        }
        return folder;
    }

    private static OeipCoreExport run(Path folder, boolean includeBinaries) throws IOException {
        return new WorkspaceOeipMapper().transcode(
                new OeipExportContext("ec847027-d5c6-455f-a599-43753924dff2", "fr", SS,
                        folder, INDEX, includeBinaries));
    }

    private static String render(List<OeipValidationError> errors) {
        StringBuilder sb = new StringBuilder();
        for (OeipValidationError e : errors) sb.append("\n  ").append(e);
        return sb.toString();
    }

    // ------------------------------------------------------------------ le cœur du sujet

    @Test
    public void avoueLesFichiersAnnoncesMaisAbsents() throws IOException {
        OeipCoreExport out = run(payload("ecart", 2, false), true);

        assertEquals("trois documents sont recensés", 3, (int) out.getCounts().getInteger("announced"));
        assertEquals("deux seulement sont emportés", 2, (int) out.getCounts().getInteger("attachments"));

        String warnings = out.getWarnings().encode();
        assertTrue("l'écart doit être avoué : " + warnings, warnings.contains("binaries.missing"));
        assertTrue(warnings.contains("1 document"));
        // Et la notice destinée à un humain doit le dire aussi, pas seulement le code.
        assertTrue(out.getNotice(), out.getNotice().contains("n'ont pas pu être emportés"));
    }

    @Test
    public void neDecritJamaisUnFichierQuIlNaPas() throws IOException {
        JsonArray items = run(payload("absents", 2, false), true)
                .getDocuments().get("resources/workspace/attachments.json").getJsonArray("items");
        String all = items.encode();
        assertFalse("un document sans fichier ne doit pas être décrit", all.contains("Disparu.docx"));
        assertTrue(all.contains("Compte rendu.txt"));
        assertTrue(all.contains("Photo.png"));
    }

    @Test
    public void distingueUneOmissionVolontaireDUneAbsenceSubie() throws IOException {
        OeipCoreExport out = run(payload("omission", 0, true), true);
        String warnings = out.getWarnings().encode();
        // Des binaires volontairement exclus ne sont pas une anomalie : le message doit différer.
        assertTrue(warnings, warnings.contains("binaries.omitted"));
        assertFalse(warnings, warnings.contains("binaries.missing"));
    }

    @Test
    public void sansBinairesDemandesAucuneAlerteDAbsence() throws IOException {
        OeipCoreExport out = run(payload("sans-binaires", 0, false), false);
        String warnings = out.getWarnings().encode();
        assertTrue(warnings, warnings.contains("binaries.omitted"));
        assertFalse(warnings, warnings.contains("binaries.missing"));
    }

    // ------------------------------------------------------------------ conformité

    @Test
    public void lesDocumentsProduitsValidentContreLeSchema() throws IOException {
        OeipCoreExport out = run(payload("conforme", 2, false), true);
        assertFalse(out.getDocuments().isEmpty());
        for (Map.Entry<String, JsonObject> doc : out.getDocuments().entrySet()) {
            List<OeipValidationError> errors =
                    validator.validate(doc.getKey(), doc.getValue(), OeipFormat.SCHEMA_RESOURCE);
            assertTrue(doc.getKey() + render(errors), errors.isEmpty());
        }
    }

    @Test
    public void decritLesDossiersEtLeursProprietaires() throws IOException {
        JsonArray items = run(payload("dossiers", 2, false), true)
                .getDocuments().get("resources/workspace/folders.json").getJsonArray("items");
        assertEquals(1, items.size());
        JsonObject f = items.getJsonObject(0);
        assertEquals("Sorties scolaires", f.getString("name"));
        assertTrue(f.getString("ownerRef").contains(":person:"));
        assertEquals("2026-08-01T08:05:00Z", f.getString("createdAt"));
    }

    @Test
    public void respecteLeTypeDeMediaDeclareParLeModule() throws IOException {
        JsonArray items = run(payload("media", 2, false), true)
                .getDocuments().get("resources/workspace/attachments.json").getJsonArray("items");
        // L'index déclare text/plain ; il prime sur la déduction par extension.
        assertEquals("text/plain", items.getJsonObject(1).getString("mediaType"));
        assertTrue(items.getJsonObject(0).getString("sha256").matches("^[a-f0-9]{64}$"));
        assertTrue(items.getJsonObject(0).getString("path").startsWith("resources/workspace/content/"));
    }

    // ------------------------------------------------------------------ robustesse

    @Test
    public void unIndexIntrouvableEstAvoueEtNonDevine() throws IOException {
        Path folder = work.resolve("sans-index");
        Files.createDirectories(folder);
        OeipCoreExport out = new WorkspaceOeipMapper().transcode(
                new OeipExportContext("u1", "fr", SS, folder, INDEX, true));
        assertTrue(out.getWarnings().encode().contains("payload.index.missing"));
        assertEquals(0, (int) out.getCounts().getInteger("attachments"));
    }

    @Test
    public void retrouveLIndexMemeSousUnAutreLibelle() throws IOException {
        // Le nom de l'index est un libellé traduit : un paquet venu d'ailleurs peut l'avoir
        // nommé autrement. Le repli cherche le seul fichier sans extension à la racine.
        Path folder = payload("autre-libelle", 2, false);
        Files.move(folder.resolve(INDEX), folder.resolve("Workspace"));
        OeipCoreExport out = new WorkspaceOeipMapper().transcode(
                new OeipExportContext("u1", "fr", SS, folder, INDEX, true));
        assertFalse(out.getWarnings().encode().contains("payload.index.missing"));
        assertEquals(2, (int) out.getCounts().getInteger("attachments"));
    }

    /**
     * Cas réel : un document dont le champ « file » porte un chemin absolu au lieu d'un
     * identifiant. Sans assainissement préalable, les deux premiers caractères servant de
     * sous-répertoire valaient « /m », injectant un séparateur au milieu d'un segment — le chemin
     * déclaré cessait alors de désigner le fichier écrit, et l'index devenait faux.
     */
    @Test
    public void unIdentifiantDeFichierAberrantNeCorrompPasLeChemin() throws IOException {
        Path folder = work.resolve("identifiant-aberrant");
        Files.createDirectories(folder.resolve("Documents personnels"));
        JsonArray index = new JsonArray().add(fileEntry("doc-x",
                "/mnt/sda/storage/5c/02/c6387cd8-a232-4ecc-848c-acab27a0025c",
                "signature.png", "Documents personnels/signature.png"));
        write(folder.resolve(INDEX), index.encodePrettily());
        write(folder.resolve("Documents personnels/signature.png"), "binaire");

        OeipCoreExport out = new WorkspaceOeipMapper().transcode(
                new OeipExportContext("u1", "fr", SS, folder, INDEX, true));

        JsonObject a = out.getDocuments().get("resources/workspace/attachments.json")
                .getJsonArray("items").getJsonObject(0);
        String path = a.getString("path");

        assertFalse("aucun double séparateur : " + path, path.contains("//"));
        assertTrue("le chemin déclaré doit être celui du fichier réellement écrit",
                out.getFiles().containsKey(path));
        // Le repli sur _id vaut mieux que bâtir une identité sur une valeur qui n'en est pas une.
        assertEquals("doc-x", a.getString("sourceId"));

        List<OeipValidationError> errors = validator.validate("attachments",
                out.getDocuments().get("resources/workspace/attachments.json"),
                OeipFormat.SCHEMA_RESOURCE);
        assertTrue(render(errors), errors.isEmpty());
    }

    // ------------------------------------------------------------------ utilitaires

    private static void write(Path p, String content) throws IOException {
        Files.createDirectories(p.getParent());
        Files.write(p, content.getBytes(StandardCharsets.UTF_8));
    }

    private static void deleteRecursive(Path p) throws IOException {
        if (!Files.exists(p)) return;
        java.io.File[] children = p.toFile().listFiles();
        if (children != null) {
            for (java.io.File c : children) deleteRecursive(c.toPath());
        }
        Files.deleteIfExists(p);
    }
}
