package org.entcore.interoperability.providers;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.entcore.interoperability.spi.OeipImportContext;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.Assert.*;

/**
 * La reprise de l'espace documentaire.
 *
 * Deux points méritent d'être garantis par un test plutôt que par la relecture : l'ordre des
 * dossiers, un enfant ne pouvant être rattaché à un parent pas encore créé ; et le refus de
 * fabriquer un document pour un fichier que le paquet décrit sans le contenir.
 */
public class WorkspaceOeipImporterTest {

    private static final String SS = "ent.exemple-a.fr";
    private static Path work;

    @BeforeClass
    public static void setUp() throws IOException {
        work = Paths.get("target", "workspace-importer-test");
        deleteRecursive(work);
        Files.createDirectories(work);
    }

    private static JsonObject folder(String id, String parent) {
        JsonObject f = new JsonObject()
                .put("globalId", "urn:oeip:1.0:folder:" + SS + ":" + id)
                .put("sourceSystem", SS).put("serviceId", "workspace")
                .put("name", "Dossier " + id);
        if (parent != null) {
            f.put("parentFolderRef", "urn:oeip:1.0:folder:" + SS + ":" + parent);
        }
        return f;
    }

    private static JsonObject attachment(String id, String path) {
        return new JsonObject()
                .put("globalId", "urn:oeip:1.0:file:" + SS + ":" + id)
                .put("sourceSystem", SS).put("serviceId", "workspace")
                .put("fileName", id + ".txt").put("mediaType", "text/plain")
                .put("size", 12).put("sha256", "0".repeat(64))
                .put("path", path);
    }

    private static Path packageRoot(String name, boolean withBinary) throws IOException {
        Path root = work.resolve(name);
        write(root.resolve("resources/workspace/folders.json"), new JsonObject()
                .put("oeipVersion", "1.0").put("sourceSystem", SS).put("serviceId", "workspace")
                .put("dataset", "folders")
                // Volontairement en désordre : l'enfant avant son parent.
                .put("items", new JsonArray().add(folder("b", "a")).add(folder("a", null)))
                .encodePrettily());
        write(root.resolve("resources/workspace/attachments.json"), new JsonObject()
                .put("oeipVersion", "1.0").put("sourceSystem", SS).put("serviceId", "workspace")
                .put("dataset", "attachments")
                .put("items", new JsonArray()
                        .add(attachment("present", "resources/workspace/content/pr/present/present.txt"))
                        .add(attachment("absent", "resources/workspace/content/ab/absent/absent.txt")))
                .encodePrettily());
        if (withBinary) {
            write(root.resolve("resources/workspace/content/pr/present/present.txt"), "un contenu");
        }
        return root;
    }

    private static OeipImportContext context(Path root) {
        return new OeipImportContext(root, "cible-id", "cible.login", "Cible", true,
                new JsonObject().put("source", new JsonObject().put("sourceSystem", SS)));
    }

    private static JsonObject run(Path root) throws Exception {
        return new WorkspaceOeipImporter(null, null).importCore(context(root))
                .toCompletionStage().toCompletableFuture().get();
    }

    // ------------------------------------------------------------------ l'ordre des dossiers

    @Test
    public void ordonneLesDossiersDuParentVersLEnfant() {
        JsonArray desordre = new JsonArray().add(folder("b", "a")).add(folder("a", null));
        List<JsonObject> ordered = WorkspaceOeipImporter.orderByDepth(desordre);

        // Sans ce tri, l'enfant se retrouverait à la racine sans qu'on le remarque.
        assertEquals("Dossier a", ordered.get(0).getString("name"));
        assertEquals("Dossier b", ordered.get(1).getString("name"));
    }

    @Test
    public void unCycleDeParenteNeFaitPasBoucler() {
        // Donnée corrompue à la source : deux dossiers parents l'un de l'autre.
        JsonArray cycle = new JsonArray().add(folder("x", "y")).add(folder("y", "x"));
        List<JsonObject> ordered = WorkspaceOeipImporter.orderByDepth(cycle);
        assertEquals("aucun dossier ne doit être perdu", 2, ordered.size());
    }

    @Test
    public void unParentHorsDuPaquetNeBloquePasSonEnfant() {
        JsonArray orphelin = new JsonArray().add(folder("seul", "parent-absent"));
        assertEquals(1, WorkspaceOeipImporter.orderByDepth(orphelin).size());
    }

    // ------------------------------------------------------------------ ce qu'il refuse

    @Test
    public void neFabriquePasDeDocumentPourUnFichierAbsentDuPaquet() throws Exception {
        JsonObject report = run(packageRoot("fichier-absent", true));

        assertEquals("seul le fichier réellement présent est repris", 1,
                (int) report.getInteger("files"));
        JsonArray skipped = report.getJsonArray("skipped");
        assertNotNull(skipped);
        assertEquals(1, skipped.size());
        assertTrue(skipped.getJsonObject(0).getString("reason").contains("absent du paquet"));
    }

    @Test
    public void decritCeQuIlCreeraitSansRienEcrire() throws Exception {
        JsonObject report = run(packageRoot("essai", true));
        assertTrue(report.getBoolean("dryRun"));
        assertEquals(0, (int) report.getInteger("created"));
        assertEquals("2 dossiers + 1 fichier présent", 3, (int) report.getInteger("wouldCreate"));
        assertEquals(2, (int) report.getInteger("folders"));
    }

    @Test
    public void aucunFichierPresentNEmpechePasLaRepriseDesDossiers() throws Exception {
        JsonObject report = run(packageRoot("sans-binaire", false));
        assertEquals(2, (int) report.getInteger("folders"));
        assertEquals(0, (int) report.getInteger("files"));
        assertEquals(2, report.getJsonArray("skipped").size());
    }

    @Test
    public void unPaquetSansEspaceDocumentaireNeFaitPasEchouerLaReprise() throws Exception {
        Path vide = work.resolve("vide");
        Files.createDirectories(vide);
        JsonObject report = run(vide);
        assertEquals(0, (int) report.getInteger("created"));
        assertTrue(report.getString("notice").contains("Aucun contenu"));
    }

    @Test
    public void annonceQueRienNEstRepartage() throws Exception {
        JsonObject report = run(packageRoot("partages", true));
        assertTrue(report.getString("notice"), report.getString("notice").contains("privés"));
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
