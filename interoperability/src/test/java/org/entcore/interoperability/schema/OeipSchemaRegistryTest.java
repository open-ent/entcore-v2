package org.entcore.interoperability.schema;

import io.vertx.core.json.JsonObject;
import org.entcore.interoperability.OeipFormat;
import org.entcore.interoperability.TestFixtures;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class OeipSchemaRegistryTest {

    @Test
    public void chargeLeLotCompletDepuisLeClasspath() {
        OeipSchemaRegistry registry = new OeipSchemaRegistry();
        assertEquals(OeipFormat.SCHEMA_FILES.size(), registry.getRawSchemas().size());
        for (String name : OeipFormat.SCHEMA_FILES) {
            assertNotNull("schéma absent : " + name, registry.getRawSchema(name));
        }
    }

    @Test
    public void empreinteDuLotStableEtBienFormee() {
        OeipSchemaRegistry registry = new OeipSchemaRegistry();
        String sha = registry.getBundleSha256();
        assertTrue("empreinte mal formée : " + sha, sha.matches("^[a-f0-9]{64}$"));
        // Déterminisme : deux chargements doivent donner la même empreinte.
        assertEquals(sha, new OeipSchemaRegistry().getBundleSha256());
    }

    /**
     * Le paquet de référence a été produit par l'outil Python. Si Java calcule la même
     * empreinte de lot, c'est que les deux implémentations s'accordent sur la forme canonique
     * du listing — sans quoi un paquet produit d'un côté serait suspecté de l'autre.
     */
    @Test
    public void empreinteIdentiqueACelleCalculeeParLOutilPython() {
        OeipSchemaRegistry registry = new OeipSchemaRegistry();
        JsonObject manifest = TestFixtures.json(OeipFormat.MANIFEST);
        String declared = manifest.getJsonObject("schemaBundle").getString("sha256");
        assertEquals("Java et Python divergent sur l'empreinte du lot de schémas",
                declared, registry.getBundleSha256());
    }

    @Test
    public void indexDecritLeLot() {
        JsonObject index = new OeipSchemaRegistry().index();
        assertEquals(OeipFormat.VERSION, index.getString("oeipVersion"));
        assertEquals(OeipFormat.SCHEMA_FILES.size(), index.getJsonArray("schemas").size());
    }
}
