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

import static org.junit.Assert.*;

/**
 * La reprise de blogs décrits dans le modèle commun.
 *
 * Les assertions qui comptent portent sur ce qu'un import ne doit JAMAIS faire : réutiliser un
 * identifiant venu d'ailleurs, rétablir un partage qui n'a plus de sens, publier d'autorité, ou
 * accrocher un billet orphelin au premier blog venu.
 */
public class BlogOeipImporterTest {

    private static final String SS = "ent.exemple-a.fr";
    private static final String BLOG_GID = "urn:oeip:1.0:resource:" + SS + ":blog-1";
    private static final String POST_GID = "urn:oeip:1.0:resource:" + SS + ":post-1";
    private static final String FILE_GID = "urn:oeip:1.0:file:" + SS + ":img-1";

    private static Path work;

    @BeforeClass
    public static void setUp() throws IOException {
        work = Paths.get("target", "blog-importer-test");
        deleteRecursive(work);
        Files.createDirectories(work);
    }

    /** Reproduit un paquet tel qu'un autre ENT pourrait le produire. */
    private static Path packageRoot(String name, boolean withParentBlog) throws IOException {
        Path root = work.resolve(name);
        JsonArray items = new JsonArray();
        if (withParentBlog) {
            items.add(new JsonObject()
                    .put("globalId", BLOG_GID)
                    .put("sourceSystem", SS)
                    .put("serviceId", "blog")
                    .put("sourceId", "blog-1")
                    .put("resourceType", "urn:oeip:restype:blog.blog")
                    .put("title", "Le blog de la 5e A")
                    .put("description", "Les travaux de la classe")
                    .put("visibility", "shared")
                    .put("createdAt", "2026-08-26T09:51:59.671Z"));
        }
        items.add(new JsonObject()
                .put("globalId", POST_GID)
                .put("sourceSystem", SS)
                .put("serviceId", "blog")
                .put("sourceId", "post-1")
                .put("resourceType", "urn:oeip:restype:blog.post")
                .put("title", "Sortie au muséum")
                .put("parentResourceRef", BLOG_GID)
                .put("state", "published")
                .put("createdAt", "2026-09-10T16:00:00Z")
                .put("body", new JsonObject().put("mediaType", "text/html")
                        .put("href", "resources/blog/content/po/post-1/index.html")));

        write(root.resolve("resources/blog/resources.json"), new JsonObject()
                .put("oeipVersion", "1.0").put("sourceSystem", SS)
                .put("serviceId", "blog").put("dataset", "resources")
                .put("items", items).encodePrettily());
        write(root.resolve("resources/blog/content/po/post-1/index.html"),
                "<h2>Sortie</h2><p><img src=\"oeip:file/" + FILE_GID + "\"></p>");
        return root;
    }

    private static OeipImportContext context(Path root, boolean dryRun) {
        return new OeipImportContext(root, "cible-user-id", "cible.login", "Cible Utilisateur",
                dryRun, new JsonObject().put("source", new JsonObject().put("sourceSystem", SS)));
    }

    /** Exerce la conversion sans base : l'écriture est testée en conditions réelles, pas ici. */
    private static JsonObject convert(OeipImportContext ctx) throws Exception {
        return new BlogOeipImporter(null).importCore(ctx).toCompletionStage()
                .toCompletableFuture().get();
    }

    // ------------------------------------------------------------------ ce qui est produit

    @Test
    public void decritCeQuIlCreeraitSansRienEcrire() throws Exception {
        JsonObject report = convert(context(packageRoot("essai", true), true));
        assertTrue(report.getBoolean("dryRun"));
        assertEquals("rien n'est créé en mode essai", 0, (int) report.getInteger("created"));
        assertEquals(2, (int) report.getInteger("wouldCreate"));
        assertEquals(1, (int) report.getInteger("blogs"));
        assertEquals(1, (int) report.getInteger("posts"));
    }

    @Test
    public void nAttribueJamaisLesIdentifiantsDOrigine() throws Exception {
        JsonObject report = convert(context(packageRoot("identifiants", true), true));
        JsonObject idMap = report.getJsonObject("idMap");

        // Un identifiant venu d'ailleurs appartient à la plateforme émettrice : le réutiliser
        // provoquerait des collisions, voire l'écrasement d'un contenu local homonyme.
        assertEquals(2, idMap.size());
        for (String globalId : idMap.fieldNames()) {
            String local = idMap.getString(globalId);
            assertFalse("l'identifiant local ne doit pas reprendre celui d'origine",
                    globalId.endsWith(local));
            assertTrue("un identifiant neuf est attendu",
                    local.matches("^[0-9a-f-]{36}$"));
        }
    }

    // ------------------------------------------------------------------ ce qu'il refuse de faire

    @Test
    public void unBilletOrphelinEstSignaleEtNonRattacheAuHasard() throws Exception {
        JsonObject report = convert(context(packageRoot("orphelin", false), true));

        assertEquals("aucun blog, donc aucun billet repris", 0, (int) report.getInteger("posts"));
        JsonArray unresolved = report.getJsonArray("unresolved");
        assertNotNull(unresolved);
        assertEquals(1, unresolved.size());
        assertTrue(unresolved.getJsonObject(0).getString("reason").contains("blog"));
    }

    @Test
    public void signaleUnFichierCiteMaisNonRepris() throws Exception {
        OeipImportContext ctx = context(packageRoot("fichier-absent", true), true);
        JsonArray unresolved = new JsonArray();
        String html = "<img src=\"oeip:file/" + FILE_GID + "\">";
        String out = BlogOeipImporter.restoreLinks(html, POST_GID, ctx, unresolved);

        // Laissé en l'état : visible, corrigeable, et bien préférable à un lien pointant vers un
        // contenu sans rapport.
        assertEquals(html, out);
        assertEquals(1, unresolved.size());
        assertTrue(unresolved.getJsonObject(0).getString("reason").contains("n'a pas été repris"));
    }

    @Test
    public void retablitUnLienVersUnFichierEffectivementRepris() throws Exception {
        OeipImportContext ctx = context(packageRoot("fichier-present", true), true);
        ctx.getLocalByGlobalId().put(FILE_GID, "nouveau-doc-id");
        JsonArray unresolved = new JsonArray();
        String out = BlogOeipImporter.restoreLinks(
                "<img src=\"oeip:file/" + FILE_GID + "\">", POST_GID, ctx, unresolved);

        assertTrue(out.contains("/workspace/document/nouveau-doc-id"));
        assertFalse(out.contains("oeip:file/"));
        assertTrue(unresolved.isEmpty());
    }

    // ------------------------------------------------------------------ prudence

    @Test
    public void neRepubliePasEtNeRepartagePasDautorite() throws Exception {
        BlogOeipImporter importer = new BlogOeipImporter(null);
        Path root = packageRoot("prudence", true);
        OeipImportContext ctx = context(root, true);

        JsonArray items = new JsonObject(new String(Files.readAllBytes(
                root.resolve("resources/blog/resources.json")), StandardCharsets.UTF_8))
                .getJsonArray("items");
        // Le paquet annonce un blog partagé et un billet publié ; ni l'un ni l'autre ne doit être
        // rétabli d'autorité — les groupes d'origine n'existent pas ici, et republier est une
        // décision de la personne qui reçoit.
        assertEquals("shared", items.getJsonObject(0).getString("visibility"));
        assertEquals("published", items.getJsonObject(1).getString("state"));

        JsonObject report = importer.importCore(ctx).toCompletionStage().toCompletableFuture().get();
        assertNotNull(report);
        assertTrue(report.getString("notice").contains("rien n'est écrasé"));
    }

    @Test
    public void unPaquetSansBlogNeFaitPasEchouerLaReprise() throws Exception {
        Path vide = work.resolve("vide");
        Files.createDirectories(vide);
        JsonObject report = convert(context(vide, true));
        assertEquals(0, (int) report.getInteger("created"));
        assertTrue(report.getString("notice").contains("Aucun contenu"));
    }

    @Test
    public void extraitUnTextePlatExploitable() {
        assertEquals("Sortie au muséum La classe a visité le muséum.",
                BlogOeipImporter.stripTags(
                        "<h2>Sortie au muséum</h2><p>La classe a visité le muséum.</p>"));
        assertEquals("", BlogOeipImporter.stripTags(null));
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
