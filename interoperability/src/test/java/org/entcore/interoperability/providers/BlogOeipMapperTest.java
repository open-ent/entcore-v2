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
 * Le mapper blog, éprouvé sur une charge utile d'archive fidèle à celle qu'un module produit.
 *
 * Deux exigences priment : les documents produits doivent valider contre les schémas, et aucun
 * identifiant brut ne doit survivre dans un corps HTML — sans quoi le contenu arriverait avec des
 * liens qui ne désignent rien sur la plateforme de destination.
 */
public class BlogOeipMapperTest {

    private static final String SS = "ent.exemple-a.fr";
    private static final String FILE_ID = "9f8e7d6c-1111-4222-8333-444455556666";
    private static final String ORPHAN_ID = "00000000-dead-4bee-8fff-999999999999";

    private static OeipValidator validator;
    private static Path work;

    @BeforeClass
    public static void setUp() throws IOException {
        validator = new OeipValidator(new OeipSchemaRegistry());
        work = Paths.get("target", "blog-mapper-test");
        deleteRecursive(work);
        Files.createDirectories(work);
    }

    /** Reproduit la forme réelle : un fichier JSON par ressource, sans extension. */
    private static Path payload(String name) throws IOException {
        Path folder = work.resolve(name);
        Files.createDirectories(folder.resolve("Documents"));

        JsonObject blog = new JsonObject()
                .put("_id", "a14fc47b-de25-4de4-9dc9-5538cbb4ea85")
                .put("title", "blog_Le blog de la 5e A")
                .put("description", "Les travaux de la classe")
                .put("visibility", "OWNER")
                .put("shared", new JsonArray())
                .put("created", new JsonObject().put("$date", "2026-08-26T09:51:59.671Z"))
                .put("author", new JsonObject()
                        .put("userId", "ec847027-d5c6-455f-a599-43753924dff2")
                        .put("username", "SHAFY001 Emaël")
                        .put("login", "emael.shafy001"));

        JsonObject post = new JsonObject()
                .put("_id", "b25fd58c-ef36-4ef5-8eda-6649dcc5fb96")
                .put("title", "post_Sortie au muséum")
                .put("state", "PUBLISHED")
                .put("blog", new JsonObject().put("$id", "a14fc47b-de25-4de4-9dc9-5538cbb4ea85"))
                .put("content", "<p>Photo : <img src=\"/workspace/document/" + FILE_ID + "\"></p>"
                        + "<p>Disparue : <img src=\"/workspace/document/" + ORPHAN_ID + "\"></p>")
                .put("created", new JsonObject().put("$date", "2026-09-10T16:00:00.000Z"))
                .put("author", new JsonObject()
                        .put("userId", "ec847027-d5c6-455f-a599-43753924dff2"));

        write(folder.resolve("blog_Le blog de la 5e A"), blog.encodePrettily());
        write(folder.resolve("post_Sortie au museum"), post.encodePrettily());
        // Nommage des pièces jointes par l'export d'archive : « nom_identifiant.ext ».
        write(folder.resolve("Documents").resolve("squelette_" + FILE_ID + ".png"), "binaire-png");
        // Un fichier illisible doit être signalé, pas ignoré en silence.
        write(folder.resolve("miettes.tmp"), "ceci n'est pas du json");
        return folder;
    }

    private static OeipCoreExport run(Path folder, boolean includeBinaries) throws IOException {
        return new BlogOeipMapper().transcode(
                new OeipExportContext("ec847027-d5c6-455f-a599-43753924dff2", "fr", SS,
                        folder, "Blog", includeBinaries));
    }

    private static String render(List<OeipValidationError> errors) {
        StringBuilder sb = new StringBuilder();
        for (OeipValidationError e : errors) sb.append("\n  ").append(e);
        return sb.toString();
    }

    // ------------------------------------------------------------------ conformité

    @Test
    public void lesDocumentsProduitsValidentContreLeSchema() throws IOException {
        OeipCoreExport out = run(payload("conforme"), true);
        assertFalse(out.getDocuments().isEmpty());
        for (Map.Entry<String, JsonObject> doc : out.getDocuments().entrySet()) {
            List<OeipValidationError> errors =
                    validator.validate(doc.getKey(), doc.getValue(), OeipFormat.SCHEMA_RESOURCE);
            assertTrue(doc.getKey() + render(errors), errors.isEmpty());
        }
    }

    // ------------------------------------------------------------------ titres

    @Test
    public void retireLesPrefixesDeCollection() throws IOException {
        JsonArray items = run(payload("prefixes"), true)
                .getDocuments().get("resources/blog/resources.json").getJsonArray("items");
        String titles = items.encode();
        assertTrue(titles, titles.contains("Le blog de la 5e A"));
        assertTrue(titles, titles.contains("Sortie au muséum"));
        // Un préfixe qui survit produit des titres corrompus, doublement préfixés au deuxième
        // aller-retour.
        assertFalse("le préfixe blog_ ne doit pas survivre", titles.contains("blog_Le blog"));
        assertFalse("le préfixe post_ ne doit pas survivre", titles.contains("post_Sortie"));
    }

    @Test
    public void distingueLeConteneurDuBillet() throws IOException {
        JsonArray items = run(payload("types"), true)
                .getDocuments().get("resources/blog/resources.json").getJsonArray("items");
        assertEquals(2, items.size());
        boolean blog = false, post = false;
        for (int i = 0; i < items.size(); i++) {
            String type = items.getJsonObject(i).getString("resourceType");
            if ("urn:oeip:restype:blog.blog".equals(type)) blog = true;
            if ("urn:oeip:restype:blog.post".equals(type)) {
                post = true;
                assertNotNull("un billet doit désigner son blog",
                        items.getJsonObject(i).getString("parentResourceRef"));
            }
        }
        assertTrue(blog && post);
    }

    // ------------------------------------------------------------------ références HTML

    @Test
    public void reecritLesLiensInternesEnReferencesDEchange() throws IOException {
        OeipCoreExport out = run(payload("liens"), true);
        String body = out.getDocuments().get("resources/blog/resources.json").encode();

        assertTrue("le lien résolu doit devenir une référence d'échange",
                body.contains("oeip:file/urn:oeip:1.0:file:" + SS + ":" + FILE_ID));
        assertFalse("aucun lien interne résolu ne doit survivre",
                body.contains("/workspace/document/" + FILE_ID));
        assertEquals("chaque réécriture doit être journalisée", 1, out.getRewrites().size());
    }

    @Test
    public void signaleUnLienNonResoluAuLieuDeLeReecrireAuHasard() throws IOException {
        OeipCoreExport out = run(payload("orphelin"), true);
        String body = out.getDocuments().get("resources/blog/resources.json").encode();

        // Laissé tel quel : un lien mort annoncé vaut mieux qu'un lien réécrit vers n'importe quoi.
        assertTrue(body.contains("/workspace/document/" + ORPHAN_ID));
        assertEquals(1, out.getUnresolvedReferences().size());
        JsonObject ref = out.getUnresolvedReferences().getJsonObject(0);
        assertEquals("not-found", ref.getString("reason"));
        assertTrue(ref.getString("rawValue").contains(ORPHAN_ID));
    }

    // ------------------------------------------------------------------ pièces jointes

    @Test
    public void decritLesPiecesJointesAvecCheminEtEmpreinte() throws IOException {
        OeipCoreExport out = run(payload("jointes"), true);
        JsonArray items = out.getDocuments()
                .get("resources/blog/attachments.json").getJsonArray("items");
        assertEquals(1, items.size());
        JsonObject a = items.getJsonObject(0);

        assertEquals("squelette.png", a.getString("fileName"));
        assertEquals("image/png", a.getString("mediaType"));
        assertTrue(a.getString("sha256").matches("^[a-f0-9]{64}$"));
        assertTrue(a.getString("path").startsWith("resources/blog/content/"));
        // Le binaire doit être réellement recopié dans le paquet, à ce chemin exact.
        assertTrue(out.getFiles().containsKey(a.getString("path")));
    }

    @Test
    public void sansBinairesAucunePieceJointeNEstAnnoncee() throws IOException {
        OeipCoreExport out = run(payload("sans-binaires"), false);
        assertNull(out.getDocuments().get("resources/blog/attachments.json"));
        assertTrue(out.getFiles().isEmpty());
        assertEquals(0, (int) out.getCounts().getInteger("attachments"));
    }

    // ------------------------------------------------------------------ honnêteté

    @Test
    public void avoueLesFichiersQuIlNaPasSuInterpreter() throws IOException {
        OeipCoreExport out = run(payload("illisible"), true);
        String warnings = out.getWarnings().encode();
        assertTrue("un fichier non interprétable doit être avoué : " + warnings,
                warnings.contains("payload.unreadable"));
    }

    @Test
    public void declareUneFideliteJustifiee() throws IOException {
        OeipCoreExport out = run(payload("fidelite"), true);
        assertEquals(OeipFormat.FIDELITY_PARTIAL, out.getFidelity());
        assertNotNull(out.getNotice());
    }

    // ------------------------------------------------------------------ dates

    @Test
    public void normaliseLesDatesEtOmetCellesQuiSontIllisibles() {
        assertEquals("2026-08-26T09:51:59.671Z", BlogOeipMapper.isoDate(
                new JsonObject().put("$date", "2026-08-26T09:51:59.671Z")));
        // Certaines archives portent la date en millisecondes depuis l'époque.
        long epochMillis = java.time.Instant.parse("2026-08-26T09:51:59.671Z").toEpochMilli();
        assertEquals("2026-08-26T09:51:59.671Z", BlogOeipMapper.isoDate(epochMillis));
        assertEquals("2026-08-26T09:51:59.671Z", BlogOeipMapper.isoDate(
                new JsonObject().put("$date", epochMillis)));
        // Le schéma exige un fuseau explicite : plutôt omettre que transmettre une date bancale.
        assertNull(BlogOeipMapper.isoDate("2026-08-11 13:57.49.854"));
        assertNull(BlogOeipMapper.isoDate(null));
    }

    @Test
    public void extraitLIdentifiantDUnePieceJointe() {
        assertEquals(FILE_ID, BlogOeipMapper.extractFileId("squelette_" + FILE_ID + ".png"));
        assertNull(BlogOeipMapper.extractFileId("squelette.png"));
        assertEquals("squelette.png",
                BlogOeipMapper.cleanAttachmentName("squelette_" + FILE_ID + ".png", FILE_ID));
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
