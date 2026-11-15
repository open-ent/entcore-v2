package org.entcore.interoperability.packs;

import fr.tech.openent.interoperability.CourseFormat;
import fr.tech.openent.interoperability.WriteOptions;
import fr.tech.openent.interoperability.model.ActivityType;
import fr.tech.openent.interoperability.model.ConversionReport;
import fr.tech.openent.interoperability.model.Course;
import fr.tech.openent.interoperability.model.QuestionType;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.entcore.interoperability.OeipFormat;
import org.entcore.interoperability.packaging.OeipChecksums;
import org.entcore.interoperability.packaging.OeipPackageReader;
import org.entcore.interoperability.schema.OeipSchemaRegistry;
import org.entcore.interoperability.schema.OeipValidator;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class PackExportTest {

    private static final long MAX = 100L * 1024 * 1024;
    private static final String PDF = "%PDF-1.4\n%fiche\n";

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private final OeipValidator validator = new OeipValidator(new OeipSchemaRegistry(OeipFormat.VERSION_1_1));

    @Test
    public void le_pack_devient_un_cours_dans_l_ordre_de_la_sequence() throws Exception {
        final JsonObject descriptor = PackPackage.read(
                OeipPackageReader.open(build(), tmp.newFolder().toPath(), MAX), validator);
        final ConversionReport report = new ConversionReport();
        try (Course course = PackCourse.convert(descriptor, report)) {
            assertEquals("CE1 — La soustraction posée", course.title());
            assertEquals(4, course.sections().size());
            // Séquence : les pages visibles seulement, dans l'ordre du wiki.
            assertEquals("La soustraction posée", course.sections().get(0).title());
            assertEquals(2, course.sections().get(0).activities().size());
            assertEquals("Accueil", course.sections().get(0).activities().get(0).title());
            // Carte mentale : son plan, en listes imbriquées.
            final String outline = course.sections().get(1).activities().get(0).contentHtml();
            assertTrue(outline, outline.contains("<h2>Soustraire</h2><ul><li>Poser</li><li>Retenir"));
            // Exercices : la zone sur image est écartée, et dite.
            assertEquals(ActivityType.QUIZ, course.sections().get(2).activities().get(0).type());
            assertEquals(3, course.allQuestions().size());
            assertEquals(QuestionType.CLOZE, course.allQuestions().get(2).type());
            assertEquals("Le résultat est [[1]].", course.allQuestions().get(2).promptHtml());
            // Fiche : un fichier, son binaire dans les assets du cours.
            assertEquals(ActivityType.FILE, course.sections().get(3).activities().get(0).type());
            assertEquals(1, course.assets().size());
            assertTrue(report.toString(), report.notices().stream()
                    .anyMatch(n -> "exercizer.area.not.exportable".equals(n.code())));
        }
    }

    @Test
    public void le_pack_s_exporte_dans_chaque_format_ecrit_par_la_bibliotheque() throws Exception {
        final Path pack = build();
        for (Object o : PackExport.formats()) {
            final CourseFormat format = CourseFormat.byId(((JsonObject) o).getString("id"));
            final PackExport.Exported exported = PackExport.export(pack, tmp.newFolder().toPath(), validator,
                    format, WriteOptions.DEFAULT.copy().deterministic(true), MAX);
            assertTrue(format.id(), Files.size(exported.file) > 0);
            assertTrue(exported.fileName, exported.fileName.equals(
                    "essai-ce1-soustraction-0.1.0-" + format.id() + format.extension()));
            if (format == CourseFormat.COMMON_CARTRIDGE) {
                try (ZipFile zip = new ZipFile(exported.file.toFile())) {
                    assertNotNull(zip.getEntry("imsmanifest.xml"));
                    assertTrue("la fiche PDF voyage dans le cartouche", Collections.list(zip.entries()).stream()
                            .anyMatch(e -> e.getName().endsWith(".pdf")));
                }
            }
        }
    }

    /**
     * Exporte les packs réels ({@code -Dpacks.dist=…/open-ent-mods/packs/dist}) dans tous les
     * formats ; ignoré sans la propriété. Sert à vérifier une montée de la bibliothèque.
     */
    @Test
    public void les_packs_publies_s_exportent_tous() throws Exception {
        final String dist = System.getProperty("packs.dist");
        Assume.assumeTrue(dist != null);
        final List<Path> packs = new ArrayList<Path>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(Paths.get(dist), "*.oeip")) {
            files.forEach(packs::add);
        }
        final List<String> failures = new ArrayList<String>();
        for (Path pack : packs) {
            for (Object o : PackExport.formats()) {
                final CourseFormat format = CourseFormat.byId(((JsonObject) o).getString("id"));
                try {
                    final PackExport.Exported exported = PackExport.export(pack, tmp.newFolder().toPath(),
                            validator, format, WriteOptions.DEFAULT.copy(), 2L * 1024 * 1024 * 1024);
                    System.out.println(pack.getFileName() + " → " + format.id() + " : " + Files.size(exported.file)
                            + " o, " + exported.report.fidelity() + ", " + exported.report.notices().size()
                            + " remarque(s) " + codes(exported.report));
                } catch (Exception e) {
                    failures.add(pack.getFileName() + " → " + format.id() + " : " + e);
                }
            }
        }
        assertTrue(String.join("\n", failures), failures.isEmpty());
    }

    /** Codes des remarques et leur nombre : ce qui se perd, format par format. */
    private static String codes(ConversionReport report) {
        final java.util.Map<String, Integer> counts = new java.util.TreeMap<String, Integer>();
        report.notices().forEach(n -> counts.merge(n.level() + ":" + n.code(), 1, Integer::sum));
        return counts.toString();
    }

    // ------------------------------------------------------------------ construction du paquet

    private Path build() throws IOException {
        final Path root = tmp.newFolder().toPath();
        write(root.resolve(OeipFormat.MANIFEST), manifest().encodePrettily());
        write(root.resolve("resources/wiki/resources.json"), dataset("wiki", item("wiki", "wiki.wiki",
                "La soustraction posée", new JsonObject().put("title", "La soustraction posée")
                        .put("pages", new JsonArray()
                                .add(page("p2", "Méthode", 2, true))
                                .add(page("p3", "Brouillon", 3, false))
                                .add(page("p1", "Accueil", 1, true))))).encodePrettily());
        write(root.resolve("resources/mindmap/resources.json"), dataset("mindmap", item("mindmap", "mindmap.mindmap",
                "Carte : soustraire", new JsonObject().put("name", "Carte").put("map",
                        "<map name=\"Carte\"><topic central=\"true\" text=\"Soustraire\" id=\"1\">"
                                + "<topic order=\"1\" text=\"Retenir\" id=\"3\"/>"
                                + "<topic order=\"0\" text=\"Poser\" id=\"2\"/></topic></map>"))).encodePrettily());
        write(root.resolve("resources/exercizer/resources.json"), dataset("exercizer", item("exercizer",
                "exercizer.subject", "Soustractions", new JsonObject()
                        .put("subject", new JsonObject().put("title", "Soustractions"))
                        .put("grains", new JsonArray()
                                .add(grain(3, new JsonObject().put("title", "Consigne").put("custom_data",
                                        new JsonObject().put("statement", "<p>Calcule.</p>"))))
                                .add(grain(4, new JsonObject().put("title", "52 − 17").put("statement", "52 − 17 ?")
                                        .put("custom_data", new JsonObject().put("correct_answer", "35"))))
                                .add(grain(10, new JsonObject().put("title", "Trou").put("custom_data", new JsonObject()
                                        .put("htmlContent", "Le résultat est <fill-zone zone-id=\"7\"></fill-zone>.")
                                        .put("zones", new JsonArray().add(new JsonObject().put("id", 7).put("answer", "12"))))))
                                .add(grain(11, new JsonObject().put("title", "Zones")))))).encodePrettily());
        write(root.resolve("resources/workspace/content/fiche.pdf"), PDF);
        write(root.resolve("resources/workspace/resources.json"), dataset("workspace", item("workspace",
                "workspace.file", "Fiche-mémo (PDF)", new JsonObject().put("name", "Fiche-mémo.pdf"))
                .put("body", new JsonObject().put("mediaType", "application/pdf")
                        .put("href", "resources/workspace/content/fiche.pdf"))).encodePrettily());
        OeipChecksums.write(root);
        final Path zip = tmp.newFolder().toPath().resolve("pack.oeip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            for (Path file : OeipChecksums.listFiles(root)) {
                out.putNextEntry(new ZipEntry(OeipChecksums.relative(root, file)));
                out.write(Files.readAllBytes(file));
                out.closeEntry();
            }
        }
        return zip;
    }

    private static JsonObject page(String id, String title, int position, boolean visible) {
        return new JsonObject().put("_id", id).put("title", title).put("position", position)
                .put("isVisible", visible).put("content", "<p>" + title + "</p>");
    }

    private static JsonObject grain(int type, JsonObject data) {
        return new JsonObject().put("grain_type_id", type).put("grain_data", data);
    }

    private static JsonObject item(String service, String type, String title, JsonObject document) {
        return new JsonObject()
                .put("globalId", "urn:oeip:1.1:resource:ent-scolaire.fr:essai." + service)
                .put("sourceSystem", "ent-scolaire.fr").put("serviceId", service)
                .put("resourceType", "urn:oeip:restype:" + type).put("title", title)
                .put("extensions", new JsonObject().put(service, document));
    }

    private static JsonObject dataset(String service, JsonObject item) {
        return new JsonObject().put("oeipVersion", "1.1").put("sourceSystem", "ent-scolaire.fr")
                .put("serviceId", service).put("dataset", "resources").put("items", new JsonArray().add(item));
    }

    private static JsonObject manifest() {
        final JsonArray services = new JsonArray();
        for (String s : new String[]{"exercizer", "mindmap", "wiki", "workspace"}) {
            services.add(new JsonObject().put("id", s).put("normalized", true).put("native", false)
                    .put("fidelity", "full").put("paths", new JsonObject().put("core", "resources/" + s)));
        }
        return new JsonObject()
                .put("format", OeipFormat.FORMAT_ID)
                .put("oeipVersion", "1.1")
                .put("profile", "pack")
                .put("levels", new JsonObject().put("core", true).put("cc", false).put("native", false))
                .put("source", new JsonObject().put("sourceSystem", "ent-scolaire.fr").put("product", "open-ent"))
                .put("generatedAt", "2026-11-03T12:00:00Z")
                .put("scope", new JsonObject().put("type", "platform").put("refs", new JsonArray()))
                .put("services", services)
                .put("integrity", new JsonObject().put("algorithm", "sha256").put("checksumsFile", "checksums.sha256"))
                .put("schemaBundle", new JsonObject().put("path", "schemas/1.1").put("sha256",
                        new OeipSchemaRegistry(OeipFormat.VERSION_1_1).getBundleSha256()))
                .put("pack", new JsonObject().put("id", "urn:openent:pack:essai-ce1-soustraction")
                        .put("version", "0.1.0").put("title", "CE1 — La soustraction posée")
                        .put("description", "Poser une soustraction avec retenue.")
                        .put("framework", "2b931521-4371-534c-8201-b5c0ab29f0d8"));
    }

    private static void write(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        try (OutputStream out = Files.newOutputStream(path)) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
        }
    }
}
