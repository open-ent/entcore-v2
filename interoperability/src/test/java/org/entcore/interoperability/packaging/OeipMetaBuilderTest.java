package org.entcore.interoperability.packaging;

import io.vertx.core.json.JsonObject;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Les métadonnées qui accompagnent un paquet.
 *
 * La notice de traitement n'est pas une formalité : elle est ce qui permet au destinataire de
 * savoir à quelle fin des données personnelles lui parviennent, qui en répond, et combien de
 * temps il peut les garder. Les tests portent donc surtout sur ce qu'elle ne doit jamais taire.
 */
public class OeipMetaBuilderTest {

    private static final String SS = "ent.exemple-a.fr";

    private static JsonObject configuree() {
        return new JsonObject()
                .put("controller", new JsonObject()
                        .put("name", "Collectivité de rattachement")
                        .put("contact", "dpo@exemple-a.fr"))
                .put("dpo", new JsonObject().put("contact", "dpo@exemple-a.fr"));
    }

    private static OeipMetaBuilder builder(JsonObject config) {
        return new OeipMetaBuilder(config, SS, "6.14.9-patched");
    }

    // ------------------------------------------------------------------ ce qui n'est jamais tu

    @Test
    public void uneConfigurationAbsenteEstAvoueeEtNonPassseeSousSilence() {
        JsonObject rgpd = builder(null).rgpd(false, false, 48L);

        // Un paquet muet sur ce point laisserait croire que la question a été traitée.
        JsonObject incomplete = rgpd.getJsonObject("incomplete");
        assertNotNull("l'absence de responsable doit être dite", incomplete);
        assertTrue(incomplete.getJsonArray("missing").contains("controller"));
        assertTrue(incomplete.getJsonArray("missing").contains("dpo"));
        assertTrue(incomplete.getString("notice").contains("avant tout traitement"));
        assertFalse(builder(null).isConfigured());
    }

    @Test
    public void uneConfigurationCompleteNAjoutePasDAvertissement() {
        JsonObject rgpd = builder(configuree()).rgpd(false, false, 48L);
        assertNull(rgpd.getJsonObject("incomplete"));
        assertEquals("dpo@exemple-a.fr", rgpd.getJsonObject("dpo").getString("contact"));
        assertTrue(builder(configuree()).isConfigured());
    }

    @Test
    public void laPresenceDeMineursEstAnnoncee() {
        JsonObject avec = builder(configuree()).rgpd(true, false, 48L);
        JsonObject sans = builder(configuree()).rgpd(false, false, 48L);

        assertTrue(avec.getBoolean("containsMinors"));
        assertTrue("le destinataire doit le savoir avant d'ouvrir quoi que ce soit",
                avec.getString("transferNotice").contains("mineures"));
        assertFalse(sans.getBoolean("containsMinors"));
        assertFalse(sans.getString("transferNotice").contains("mineures"));
    }

    // ------------------------------------------------------------------ honnêteté de la notice

    @Test
    public void uneNoticeNeRevendiqueLAnonymatQueSiLePaquetLEst() {
        String clair = builder(configuree()).rgpd(false, false, 48L).getString("transferNotice");
        String pseudo = builder(configuree()).rgpd(false, true, 48L).getString("transferNotice");

        assertTrue(clair.contains("données à caractère personnel"));
        assertTrue(pseudo.contains("ne contient pas de donnée permettant d'identifier"));
        // Et sans promettre plus que ce que la pseudonymisation offre réellement.
        assertTrue("la limite de l'anonymat doit être dite",
                pseudo.contains("rapprochement avec d'autres sources"));
    }

    @Test
    public void laDureeDeConservationEstPortee() {
        JsonObject rgpd = builder(configuree()).rgpd(false, false, 72L);
        assertEquals(72L, (long) rgpd.getJsonObject("retention").getLong("packageTtlHours"));
        assertTrue(rgpd.getJsonObject("retention").getString("notice").contains("détruit"));
    }

    @Test
    public void laFinaliteEtLaBaseLegaleOntUnDefautExplicite() {
        JsonObject rgpd = builder(null).rgpd(false, false, 48L);
        assertTrue(rgpd.getString("purpose").contains("Portabilité"));
        assertTrue(rgpd.getString("legalBasis").contains("Article 20"));
        // Une plateforme peut les redéfinir : son cas d'usage n'est pas forcément la portabilité.
        JsonObject perso = builder(configuree().put("purpose", "Migration de plateforme")
                .put("legalBasis", "Intérêt légitime")).rgpd(false, false, 48L);
        assertEquals("Migration de plateforme", perso.getString("purpose"));
        assertEquals("Intérêt légitime", perso.getString("legalBasis"));
    }

    // ------------------------------------------------------------------ provenance

    @Test
    public void laProvenanceDitDouVientLePaquet() {
        JsonObject p = builder(configuree()).provenance("job-1",
                "urn:oeip:1.0:person:" + SS + ":p1", "2026-09-13T09:00:00Z");

        assertEquals(SS, p.getString("sourceSystem"));
        assertEquals("job-1", p.getString("jobId"));
        assertEquals("2026-09-13T09:00:00Z", p.getString("exportedAt"));
        assertEquals("interoperability", p.getJsonObject("toolchain").getString("module"));
        assertEquals("urn:oeip:1.0:person:" + SS + ":p1",
                p.getJsonObject("exportedBy").getString("globalId"));
    }

    @Test
    public void uneProvenanceSansAuteurResteValide() {
        JsonObject p = builder(configuree()).provenance("job-1", null, "2026-09-13T09:00:00Z");
        assertNull(p.getJsonObject("exportedBy"));
        assertEquals("job-1", p.getString("jobId"));
    }
}
