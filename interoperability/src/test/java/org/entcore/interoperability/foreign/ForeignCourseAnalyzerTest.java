package org.entcore.interoperability.foreign;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Éprouve le pont vers les formats du marché, côté entcore.
 *
 * Le but n'est pas de retester la bibliothèque — elle a ses propres tests — mais de vérifier que
 * la dépendance est bien câblée et que l'analyse rendue est exploitable par une IHM.
 */
public class ForeignCourseAnalyzerTest {

    private static final Charset UTF8 = Charset.forName("UTF-8");

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private Path scormPackage() throws IOException {
        Path target = folder.newFile("paquet.zip").toPath();
        ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(target));
        try {
            write(out, "imsmanifest.xml",
                    "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                    + "<manifest identifier=\"M1\" version=\"1.0\""
                    + " xmlns=\"http://www.imsproject.org/xsd/imscp_rootv1p1p2\""
                    + " xmlns:adlcp=\"http://www.adlnet.org/xsd/adlcp_rootv1p2\">"
                    + "<metadata><schema>ADL SCORM</schema>"
                    + "<schemaversion>1.2</schemaversion></metadata>"
                    + "<organizations default=\"O1\"><organization identifier=\"O1\">"
                    + "<title>Les volcans</title>"
                    + "<item identifier=\"I1\" identifierref=\"R1\"><title>Module</title></item>"
                    + "</organization></organizations>"
                    + "<resources><resource identifier=\"R1\" type=\"webcontent\""
                    + " adlcp:scormtype=\"sco\" href=\"index.html\">"
                    + "<file href=\"index.html\"/></resource></resources></manifest>");
            write(out, "index.html", "<html><body>Module</body></html>");
        } finally {
            out.close();
        }
        return target;
    }

    private static void write(ZipOutputStream out, String name, String content) throws IOException {
        out.putNextEntry(new ZipEntry(name));
        out.write(content.getBytes(UTF8));
        out.closeEntry();
    }

    @Test
    public void analyse_un_paquet_scorm_depose() throws Exception {
        JsonObject analysis = new ForeignCourseAnalyzer(null, 0L).analyzeBlocking(scormPackage());

        assertEquals("scorm12", analysis.getString("format"));
        assertEquals("Les volcans", analysis.getString("title"));

        JsonObject counts = analysis.getJsonObject("counts");
        assertEquals(1, (int) counts.getInteger("sections"));
        assertEquals(1, (int) counts.getInteger("activities"));
        // SCORM ne transporte pas d'interactions : aucune question n'est à attendre.
        assertEquals(0, (int) counts.getInteger("questions"));
        assertEquals(1, (int) counts.getJsonObject("activitiesByType").getInteger("scorm"));

        JsonArray sections = analysis.getJsonArray("sections");
        assertEquals("Les volcans", sections.getJsonObject(0).getString("title"));
    }

    @Test
    public void refuse_un_fichier_de_format_inconnu() throws Exception {
        Path target = folder.newFile("inconnu.zip").toPath();
        ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(target));
        try {
            write(out, "lisezmoi.txt", "rien à voir");
        } finally {
            out.close();
        }
        try {
            new ForeignCourseAnalyzer(null, 0L).analyzeBlocking(target);
            fail("un format inconnu doit être refusé, jamais deviné");
        } catch (IOException expected) {
            assertEquals("interoperability.error.format.unknown", expected.getMessage());
        }
    }

    @Test
    public void le_catalogue_annonce_ce_qui_est_reellement_gree() {
        JsonArray formats = ForeignFormats.toJson();
        assertEquals(5, formats.size());

        boolean scorm12Write = false;
        boolean moodleBackupWrite = true;
        for (int i = 0; i < formats.size(); i++) {
            JsonObject format = formats.getJsonObject(i);
            assertTrue("tout format annoncé doit au moins être lisible",
                    format.getBoolean("read"));
            if ("scorm12".equals(format.getString("id"))) {
                scorm12Write = format.getBoolean("write");
            }
            if ("moodle".equals(format.getString("id"))) {
                moodleBackupWrite = format.getBoolean("write");
            }
        }
        assertTrue("SCORM 1.2 est écrit", scorm12Write);
        // L'écriture du .mbz reste à faire : le catalogue ne doit pas l'annoncer avant.
        assertEquals(false, moodleBackupWrite);
    }
}
