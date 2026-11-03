package org.entcore.interoperability.packs;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class PackSharesTest {

    private static final List<String> READ = Arrays.asList(
            "net.atos.entng.wiki.controllers.WikiController|getWiki",
            "net.atos.entng.wiki.controllers.WikiController|listPages");

    private static Map<String, Map<String, String>> groups() {
        final Map<String, Map<String, String>> groups = new HashMap<String, Map<String, String>>();
        final Map<String, String> a = new HashMap<String, String>();
        a.put("Teacher", "ens-A");
        a.put("Student", "elv-A");
        final Map<String, String> b = new HashMap<String, String>();
        b.put("Teacher", "ens-B");
        b.put("Student", "elv-B");
        groups.put("A", a);
        groups.put("B", b);
        return groups;
    }

    @Test
    public void enseignants_seuls_ne_partage_pas_aux_eleves() {
        final Map<String, String> audiences = Collections.singletonMap("A", PackShares.TEACHERS);
        final JsonArray shared = PackShares.compute(audiences, groups(), READ, null);
        assertEquals(1, shared.size());
        final JsonObject entry = shared.getJsonObject(0);
        assertEquals("ens-A", entry.getString("groupId"));
        // Clés au format des modules : points remplacés par des tirets.
        assertTrue(entry.getBoolean("net-atos-entng-wiki-controllers-WikiController|getWiki"));
        assertTrue(entry.getBoolean("net-atos-entng-wiki-controllers-WikiController|listPages"));
    }

    @Test
    public void enseignants_et_eleves_de_deux_etablissements_sans_copie() {
        final Map<String, String> audiences = new LinkedHashMap<String, String>();
        audiences.put("A", PackShares.TEACHERS_AND_STUDENTS);
        audiences.put("B", PackShares.TEACHERS);
        final JsonArray shared = PackShares.compute(audiences, groups(), READ, null);
        assertEquals(3, shared.size());
        assertEquals("ens-A", shared.getJsonObject(0).getString("groupId"));
        assertEquals("elv-A", shared.getJsonObject(1).getString("groupId"));
        assertEquals("ens-B", shared.getJsonObject(2).getString("groupId"));
    }

    @Test
    public void etablissement_desabonne_ou_avec_variante_ne_garde_aucun_droit() {
        final Map<String, String> audiences = new LinkedHashMap<String, String>();
        audiences.put("A", PackShares.TEACHERS_AND_STUDENTS);
        audiences.put("B", PackShares.TEACHERS);
        final JsonArray shared = PackShares.compute(audiences, groups(), READ, Collections.singleton("A"));
        assertEquals(1, shared.size());
        assertEquals("ens-B", shared.getJsonObject(0).getString("groupId"));
        assertEquals(0, PackShares.compute(new HashMap<String, String>(), groups(), READ, null).size());
    }

    @Test
    public void etablissement_sans_groupe_dans_l_annuaire_est_ignore() {
        final JsonArray shared = PackShares.compute(Collections.singletonMap("Z", PackShares.TEACHERS),
                groups(), READ, null);
        assertEquals(0, shared.size());
    }

    @Test
    public void un_document_de_pack_ne_peut_imposer_ni_auteur_ni_droits() {
        final JsonObject doc = new JsonObject().put("_id", "x").put("title", "T")
                .put("owner", new JsonObject().put("userId", "super-admin"))
                .put("shared", new JsonArray().add(new JsonObject().put("groupId", "g")))
                .put("version", 1L);
        final JsonObject cleaned = ExplorerMongoPackAdapter.withoutLocalFields(doc);
        assertEquals(new JsonObject().put("title", "T"), cleaned);
    }
}
