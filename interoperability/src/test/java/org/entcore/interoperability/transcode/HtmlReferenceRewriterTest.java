package org.entcore.interoperability.transcode;

import io.vertx.core.json.JsonObject;
import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * La résolution des liens internes d'un contenu.
 *
 * Le cas qui a motivé ce composant n'est pas le lien ordinaire, mais le lien qui TRAVERSE les
 * services : un billet de blog citant un document de l'espace documentaire. Aucun mapper pris
 * isolément ne dispose de la table complète — la résolution ne peut donc avoir lieu qu'après que
 * tous les services ont été décrits, avec la table réunie.
 */
public class HtmlReferenceRewriterTest {

    private static final String SS = "ent.exemple-a.fr";
    private static final String BLOG_IMG = "9f8e7d6c-1111-4222-8333-444455556666";
    private static final String WS_DOC = "d7a0a401-02f2-4bff-b302-e60e9f7e4b10";
    private static final String ORPHAN = "00000000-dead-4bee-8fff-999999999999";

    private static Map<String, String> table(String... sourceIds) {
        Map<String, String> m = new LinkedHashMap<String, String>();
        for (String id : sourceIds) {
            m.put(id, "urn:oeip:1.0:file:" + SS + ":" + id);
        }
        return m;
    }

    private static String html(String... ids) {
        StringBuilder sb = new StringBuilder("<h2>Sortie</h2>");
        for (String id : ids) {
            sb.append("<p><img src=\"/workspace/document/").append(id).append("\"></p>");
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ le cas qui compte

    @Test
    public void resoutUnLienVersUnAutreService() {
        // Le document cité appartient à l'espace documentaire, pas au blog.
        HtmlReferenceRewriter r = new HtmlReferenceRewriter(table(WS_DOC));
        String out = r.rewrite(html(WS_DOC), "urn:oeip:1.0:resource:" + SS + ":post-1", "body.content");

        assertTrue(out.contains("oeip:file/urn:oeip:1.0:file:" + SS + ":" + WS_DOC));
        assertFalse("aucun lien brut ne doit survivre", out.contains("/workspace/document/"));
        assertEquals(1, r.getRewrites().size());
        assertTrue(r.getUnresolvedReferences().isEmpty());
    }

    @Test
    public void avecUneTablePartielleLeLienResteBrutEtEstSignale() {
        // Ce que faisait le mapper blog seul : il ne connaissait que ses propres pièces jointes.
        HtmlReferenceRewriter r = new HtmlReferenceRewriter(table(BLOG_IMG));
        String out = r.rewrite(html(WS_DOC), "urn:oeip:1.0:resource:" + SS + ":post-1", "body.content");

        assertTrue("un lien non résolu reste tel quel", out.contains("/workspace/document/" + WS_DOC));
        assertEquals(1, r.getUnresolvedReferences().size());
        JsonObject miss = r.getUnresolvedReferences().getJsonObject(0);
        assertEquals("not-found", miss.getString("reason"));
        assertEquals("body.content", miss.getString("field"));
    }

    // ------------------------------------------------------------------ traçabilité

    @Test
    public void journaliseChaqueReecritureAvecSonCompte() {
        HtmlReferenceRewriter r = new HtmlReferenceRewriter(table(BLOG_IMG));
        r.rewrite(html(BLOG_IMG, BLOG_IMG), "urn:oeip:1.0:resource:" + SS + ":post-1", "body.content");

        assertEquals(1, r.getRewrites().size());
        JsonObject entry = r.getRewrites().getJsonObject(0);
        assertEquals(2, (int) entry.getInteger("count"));
        assertTrue(entry.getString("from").contains(BLOG_IMG));
        assertTrue(entry.getString("to").startsWith("oeip:file/"));
    }

    @Test
    public void rendLesIdentifiantsCitesPourLesDeclarerEnPiecesJointes() {
        HtmlReferenceRewriter r = new HtmlReferenceRewriter(table(BLOG_IMG, WS_DOC));
        r.rewrite(html(BLOG_IMG, WS_DOC), "urn:oeip:1.0:resource:" + SS + ":post-1", "body.content");
        // La projection pédagogique liste les fichiers qui accompagnent un contenu : elle a
        // besoin de savoir lesquels sont réellement cités.
        assertEquals(2, r.getReferenced().size());
    }

    // ------------------------------------------------------------------ robustesse

    @Test
    public void melangeResoluEtNonResoluSansPerdreNiLunNiLautre() {
        HtmlReferenceRewriter r = new HtmlReferenceRewriter(table(BLOG_IMG));
        String out = r.rewrite(html(BLOG_IMG, ORPHAN),
                "urn:oeip:1.0:resource:" + SS + ":post-1", "body.content");

        assertTrue(out.contains("oeip:file/urn:oeip:1.0:file:" + SS + ":" + BLOG_IMG));
        assertTrue(out.contains("/workspace/document/" + ORPHAN));
        assertEquals(1, r.getRewrites().size());
        assertEquals(1, r.getUnresolvedReferences().size());
    }

    @Test
    public void reconnaitLaFormePubliqueDuLien() {
        HtmlReferenceRewriter r = new HtmlReferenceRewriter(table(BLOG_IMG));
        String out = r.rewrite("<img src=\"/workspace/pub/document/" + BLOG_IMG + "\">",
                "urn:oeip:1.0:resource:" + SS + ":post-1", "body.content");
        assertTrue(out.contains("oeip:file/"));
    }

    @Test
    public void resteIdempotent() {
        // Une seconde passe ne doit rien casser : les liens déjà réécrits ne correspondent plus
        // au motif recherché.
        HtmlReferenceRewriter first = new HtmlReferenceRewriter(table(BLOG_IMG));
        String once = first.rewrite(html(BLOG_IMG), "urn:oeip:1.0:resource:" + SS + ":p", "body.content");
        HtmlReferenceRewriter second = new HtmlReferenceRewriter(table(BLOG_IMG));
        String twice = second.rewrite(once, "urn:oeip:1.0:resource:" + SS + ":p", "body.content");

        assertEquals(once, twice);
        assertEquals(0, second.getRewrites().size());
    }

    @Test
    public void neTouchePasAuTexteLibre() {
        HtmlReferenceRewriter r = new HtmlReferenceRewriter(table(BLOG_IMG));
        String texte = "<p>La référence " + BLOG_IMG + " est citée dans le texte.</p>";
        // Seuls les liens sont réécrits : un identifiant dans une phrase n'est pas une référence.
        assertEquals(texte, r.rewrite(texte, "urn:oeip:1.0:resource:" + SS + ":p", "body.content"));
        assertEquals(0, r.getRewrites().size());
    }

    @Test
    public void supporteUnContenuVideOuAbsent() {
        HtmlReferenceRewriter r = new HtmlReferenceRewriter(null);
        assertNull(r.rewrite(null, "x", "body.content"));
        assertEquals("", r.rewrite("", "x", "body.content"));
    }
}
