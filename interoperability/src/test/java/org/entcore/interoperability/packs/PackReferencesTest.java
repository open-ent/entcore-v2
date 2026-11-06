package org.entcore.interoperability.packs;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class PackReferencesTest {

    private static final String AUDIO = "urn:oeip:1.1:resource:ent-scolaire.fr:6e-dictees.audio-1-phrase-1";

    private static JsonObject sujet() {
        return new JsonObject().put("globalId", "urn:oeip:1.1:resource:ent-scolaire.fr:6e-dictees.dictee-1")
                .put("service", "exercizer")
                .put("document", new JsonObject().put("grains", new JsonArray().add(new JsonObject()
                        .put("grain_data", new JsonObject().put("statement",
                                "<p>Écoute :</p><audio controls src=\"oeip-ref:" + AUDIO + "\"></audio>")))));
    }

    private static JsonObject audio() {
        return new JsonObject().put("globalId", AUDIO).put("service", "workspace")
                .put("document", new JsonObject().put("name", "phrase-1.mp3"));
    }

    @Test
    public void la_reference_devient_l_adresse_du_fichier_installe() {
        final Map<String, String> hrefs = Collections.singletonMap(AUDIO, "/workspace/document/abc-123");
        final JsonObject resolved = PackReferences.resolve(sujet(), hrefs);
        final String statement = resolved.getJsonObject("document").getJsonArray("grains").getJsonObject(0)
                .getJsonObject("grain_data").getString("statement");
        assertEquals("<p>Écoute :</p><audio controls src=\"/workspace/document/abc-123\"></audio>", statement);
        // La ressource d'origine n'est pas touchée : une réinstallation repart du paquet.
        assertTrue(sujet().encode().contains("oeip-ref:"));
    }

    @Test
    public void une_reference_sans_adresse_fait_refuser_la_ressource() {
        try {
            PackReferences.resolve(sujet(), Collections.<String, String>emptyMap());
            fail("une référence non résolue doit être refusée");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains(AUDIO));
        }
    }

    @Test
    public void une_ressource_sans_reference_reste_telle_quelle() {
        final JsonObject audio = audio();
        assertTrue(PackReferences.targets(audio).isEmpty());
        assertEquals(audio, PackReferences.resolve(audio, new LinkedHashMap<String, String>()));
        final JsonObject sansDocument = new JsonObject().put("globalId", "x");
        assertSame(sansDocument, PackReferences.resolve(sansDocument, new LinkedHashMap<String, String>()));
    }

    @Test
    public void les_ressources_citees_s_installent_avant_celles_qui_les_citent() {
        // Le paquet range les services par ordre alphabétique : exercizer passe avant workspace.
        final JsonArray paquet = new JsonArray().add(sujet()).add(audio());
        final List<JsonObject> ordre = PackReferences.installOrder(paquet);
        assertEquals("workspace", ordre.get(0).getString("service"));
        assertEquals("exercizer", ordre.get(1).getString("service"));
        assertEquals(Collections.singleton(AUDIO), PackReferences.components(paquet));
        assertFalse(PackReferences.components(paquet).contains(sujet().getString("globalId")));
    }

    @Test
    public void un_composant_est_ouvert_aux_eleves_de_tout_abonne() {
        final Map<String, String> audiences = new LinkedHashMap<String, String>();
        audiences.put("A", PackShares.TEACHERS);
        audiences.put("B", PackShares.TEACHERS_AND_STUDENTS);
        final Map<String, String> all = PackShares.withStudents(audiences);
        assertEquals(PackShares.TEACHERS_AND_STUDENTS, all.get("A"));
        assertEquals(PackShares.TEACHERS_AND_STUDENTS, all.get("B"));
        assertEquals(2, all.size());
    }
}
