package org.entcore.interoperability.packaging;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.Test;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * La projection au profil des plateformes d'apprentissage.
 *
 * Trois exigences priment, et chacune a sa raison d'être : le XML doit être bien formé et porter
 * la version attendue ; aucune entité d'annuaire ne doit y apparaître, car le standard n'en a pas
 * la notion et ce serait de surcroît indiscret ; et chaque fichier cité doit exister, faute de
 * quoi le cartouche promet ce qu'il ne livre pas.
 */
public class ImsManifestWriterTest {

    private static final String SS = "ent.exemple-a.fr";
    private static final String BLOG = "urn:oeip:1.0:resource:" + SS + ":blog-1";
    private static final String POST = "urn:oeip:1.0:resource:" + SS + ":post-1";
    private static final String IMG = "urn:oeip:1.0:file:" + SS + ":img-1";
    private static final String DOC = "urn:oeip:1.0:file:" + SS + ":doc-1";

    private static Map<String, JsonArray> resources() {
        JsonArray blog = new JsonArray()
                .add(new JsonObject().put("globalId", BLOG)
                        .put("resourceType", "urn:oeip:restype:blog.blog")
                        .put("title", "Le blog de la 5e A <A & B>"))
                .add(new JsonObject().put("globalId", POST)
                        .put("resourceType", "urn:oeip:restype:blog.post")
                        .put("title", "Sortie au muséum")
                        .put("parentResourceRef", BLOG)
                        .put("attachmentRefs", new JsonArray().add(IMG))
                        .put("body", new JsonObject().put("mediaType", "text/html")
                                .put("href", "resources/blog/content/po/post-1/index.html")));
        Map<String, JsonArray> m = new LinkedHashMap<String, JsonArray>();
        m.put("blog", blog);
        return m;
    }

    private static Map<String, JsonArray> attachments() {
        Map<String, JsonArray> m = new LinkedHashMap<String, JsonArray>();
        m.put("blog", new JsonArray().add(new JsonObject().put("globalId", IMG)
                .put("fileName", "squelette.png")
                .put("path", "resources/blog/content/im/img-1/squelette.png")));
        m.put("workspace", new JsonArray().add(new JsonObject().put("globalId", DOC)
                .put("fileName", "Compte rendu.txt")
                .put("path", "resources/workspace/content/do/doc-1/Compte rendu.txt")));
        return m;
    }

    private static org.w3c.dom.Document parse(String xml) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        return f.newDocumentBuilder().parse(
                new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    // ------------------------------------------------------------------ forme

    @Test
    public void produitUnXmlBienFormeAlaBonneVersion() throws Exception {
        String xml = new ImsManifestWriter("Export", "fr").build(resources(), attachments());
        assertNotNull(xml);
        org.w3c.dom.Document doc = parse(xml);
        assertEquals("manifest", doc.getDocumentElement().getLocalName());
        assertEquals("1.3.0", doc.getElementsByTagNameNS(
                "http://www.imsglobal.org/xsd/imsccv1p3/imscp_v1p1", "schemaversion")
                .item(0).getTextContent());
        assertEquals("une seule organisation est admise", 1, doc.getElementsByTagNameNS(
                "http://www.imsglobal.org/xsd/imsccv1p3/imscp_v1p1", "organization").getLength());
    }

    @Test
    public void echappeLesCaracteresSpeciauxDesTitres() throws Exception {
        // Un titre contenant « & » ou « < » casserait le XML s'il n'était pas échappé.
        String xml = new ImsManifestWriter("Export", "fr").build(resources(), attachments());
        assertTrue(xml.contains("&lt;A &amp; B&gt;"));
        parse(xml); // ne doit pas lever
    }

    // ------------------------------------------------------------------ la règle cardinale

    @Test
    public void aucuneEntiteDAnnuaireDansLeCartouche() throws Exception {
        Map<String, JsonArray> res = resources();
        res.get("blog").getJsonObject(1)
                .put("authorRef", "urn:oeip:1.0:person:" + SS + ":personne-1")
                .put("ownerRef", "urn:oeip:1.0:person:" + SS + ":personne-1");
        String xml = new ImsManifestWriter("Export", "fr").build(res, attachments());

        // Le standard n'a aucune notion d'utilisateur, de groupe ni d'inscription : les y faire
        // figurer serait invalide, et indiscret.
        for (String kind : new String[] { ":person:", ":group:", ":org:", ":membership:" }) {
            assertFalse("le cartouche ne doit pas contenir " + kind, xml.contains(kind));
        }
    }

    // ------------------------------------------------------------------ structure

    @Test
    public void leConteneurDevientUneRubriqueSansRessource() throws Exception {
        String xml = new ImsManifestWriter("Export", "fr").build(resources(), attachments());
        org.w3c.dom.NodeList items = parse(xml).getElementsByTagNameNS(
                "http://www.imsglobal.org/xsd/imsccv1p3/imscp_v1p1", "item");
        boolean containerFound = false;
        for (int i = 0; i < items.getLength(); i++) {
            org.w3c.dom.Element e = (org.w3c.dom.Element) items.item(i);
            if (e.getTextContent().contains("Le blog de la 5e A")) {
                // Un blog n'est pas un contenu : c'est une rubrique, sans ressource associée.
                assertFalse("un conteneur ne doit pas désigner de ressource",
                        e.hasAttribute("identifierref"));
                containerFound = true;
            }
        }
        assertTrue(containerFound);
    }

    @Test
    public void chaqueContenuCiteSonFichierEtSesImages() throws Exception {
        String xml = new ImsManifestWriter("Export", "fr").build(resources(), attachments());
        assertTrue(xml.contains("resources/blog/content/po/post-1/index.html"));
        assertTrue("l'image citée par le billet doit l'accompagner",
                xml.contains("resources/blog/content/im/img-1/squelette.png"));
        assertTrue("un fichier de l'espace documentaire est un contenu à part entière",
                xml.contains("resources/workspace/content/do/doc-1/Compte rendu.txt"));
    }

    // ------------------------------------------------------------------ le pont vers OEIP

    @Test
    public void publieUneCorrespondanceCompleteEtStable() throws Exception {
        ImsManifestWriter w = new ImsManifestWriter("Export", "fr");
        String xml = w.build(resources(), attachments());
        JsonArray mapping = w.getCcMapping();

        // Chaque ressource du cartouche doit avoir sa correspondance, sans quoi le pont est rompu.
        org.w3c.dom.NodeList list = parse(xml).getElementsByTagNameNS(
                "http://www.imsglobal.org/xsd/imsccv1p3/imscp_v1p1", "resource");
        assertEquals(list.getLength(), mapping.size());
        for (int i = 0; i < list.getLength(); i++) {
            String id = ((org.w3c.dom.Element) list.item(i)).getAttribute("identifier");
            assertTrue(id.matches("^R_[a-f0-9]{12}$"));
            assertTrue("identifiant sans correspondance : " + id, mapping.encode().contains(id));
        }
        // Reproductible : deux exécutions donnent les mêmes identifiants.
        ImsManifestWriter again = new ImsManifestWriter("Export", "fr");
        again.build(resources(), attachments());
        assertEquals(mapping.encode(), again.getCcMapping().encode());
    }

    @Test
    public void lIdentifiantDEchangeEstDeclareDansLesMetadonnees() throws Exception {
        String xml = new ImsManifestWriter("Export", "fr").build(resources(), attachments());
        // Seul emplacement admis par le standard pour porter un identifiant externe.
        assertTrue(xml.contains("<lom:catalog>OEIP</lom:catalog>"));
        assertTrue(xml.contains("<lom:entry>" + POST + "</lom:entry>"));
        // Et surtout : aucune BALISE d'un espace de noms qui nous serait propre. L'identifiant
        // « urn:oeip:… » contient bien « oeip: », mais comme valeur, à l'emplacement admis.
        assertFalse("aucune balise propriétaire", xml.contains("<oeip:"));
        assertFalse("aucun espace de noms propriétaire déclaré", xml.contains("xmlns:oeip"));
    }

    // ------------------------------------------------------------------ abstention

    @Test
    public void neProduitRienQuandIlNyAPasDeContenuPedagogique() {
        Map<String, JsonArray> vide = new LinkedHashMap<String, JsonArray>();
        // Un cartouche vide serait pire que pas de cartouche : le paquet ne doit alors pas
        // déclarer le niveau pédagogique.
        assertNull(new ImsManifestWriter("Export", "fr").build(vide, vide));
    }
}
