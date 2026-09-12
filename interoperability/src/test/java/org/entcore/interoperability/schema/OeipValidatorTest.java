package org.entcore.interoperability.schema;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.entcore.interoperability.OeipFormat;
import org.entcore.interoperability.TestFixtures;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class OeipValidatorTest {

    private static OeipValidator validator;

    @BeforeClass
    public static void setUp() {
        validator = new OeipValidator(new OeipSchemaRegistry());
    }

    private static String render(List<OeipValidationError> errors) {
        StringBuilder sb = new StringBuilder();
        for (OeipValidationError e : errors) {
            sb.append("\n  ").append(e);
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ cas conformes

    @Test
    public void leManifesteDeReferenceEstConforme() {
        List<OeipValidationError> errors = validator.validate(
                OeipFormat.MANIFEST, TestFixtures.json(OeipFormat.MANIFEST), OeipFormat.SCHEMA_MANIFEST);
        assertTrue("le manifeste de référence devrait être conforme :" + render(errors), errors.isEmpty());
    }

    @Test
    public void lIndexDIdentifiantsDeReferenceEstConforme() {
        List<OeipValidationError> errors = validator.validate(
                OeipFormat.IDENTIFIERS, TestFixtures.json(OeipFormat.IDENTIFIERS), OeipFormat.SCHEMA_IDENTIFIERS);
        assertTrue(render(errors), errors.isEmpty());
    }

    @Test
    public void lAnnuaireDeReferenceEstConforme() {
        String[] datasets = { "organizations", "persons", "groups", "memberships" };
        for (String ds : datasets) {
            String path = "directory/" + ds + ".json";
            List<OeipValidationError> errors = validator.validate(
                    path, TestFixtures.json(path), OeipFormat.SCHEMA_DIRECTORY);
            assertTrue(path + render(errors), errors.isEmpty());
        }
    }

    @Test
    public void lesRessourcesDeReferenceSontConformes() {
        String[] paths = {
                "resources/blog/resources.json",
                "resources/blog/attachments.json",
                "resources/workspace/folders.json",
                "resources/workspace/attachments.json"
        };
        for (String path : paths) {
            List<OeipValidationError> errors = validator.validate(
                    path, TestFixtures.json(path), OeipFormat.SCHEMA_RESOURCE);
            assertTrue(path + render(errors), errors.isEmpty());
        }
    }

    @Test
    public void lesRelationsDeReferenceSontConformes() {
        List<OeipValidationError> errors = validator.validate(
                OeipFormat.RELATIONS, TestFixtures.json(OeipFormat.RELATIONS), OeipFormat.SCHEMA_RELATIONS);
        assertTrue(render(errors), errors.isEmpty());
    }

    // ------------------------------------------------------------------ cas fautifs
    // Un validateur qui accepte le correct ne prouve rien : ce qui compte est qu'il rejette.

    @Test
    public void refuseUneFideliteDegradeeSansNotice() {
        JsonObject manifest = TestFixtures.json(OeipFormat.MANIFEST);
        JsonArray services = manifest.getJsonArray("services");
        for (int i = 0; i < services.size(); i++) {
            services.getJsonObject(i).remove("notice");
        }
        List<OeipValidationError> errors = validator.validate(
                OeipFormat.MANIFEST, manifest, OeipFormat.SCHEMA_MANIFEST);
        assertFalse("une fidélité « partial » sans notice doit être refusée", errors.isEmpty());
    }

    @Test
    public void refuseUnServiceNonNormaliseDeclareFull() {
        JsonObject manifest = TestFixtures.json(OeipFormat.MANIFEST);
        manifest.getJsonArray("services").getJsonObject(1).put("normalized", false);
        List<OeipValidationError> errors = validator.validate(
                OeipFormat.MANIFEST, manifest, OeipFormat.SCHEMA_MANIFEST);
        assertFalse("un service non normalisé ne peut pas être « full »", errors.isEmpty());
    }

    @Test
    public void refuseLeNiveauNatifSansNativeFormat() {
        JsonObject manifest = TestFixtures.json(OeipFormat.MANIFEST);
        manifest.getJsonObject("levels").put("native", true);
        List<OeipValidationError> errors = validator.validate(
                OeipFormat.MANIFEST, manifest, OeipFormat.SCHEMA_MANIFEST);
        assertFalse("levels.native impose nativeFormat", errors.isEmpty());
    }

    @Test
    public void refuseUnIdentifiantGlobalMalForme() {
        JsonObject persons = TestFixtures.json("directory/persons.json");
        persons.getJsonArray("items").getJsonObject(0).put("globalId", "person-0001");
        List<OeipValidationError> errors = validator.validate(
                "directory/persons.json", persons, OeipFormat.SCHEMA_DIRECTORY);
        assertFalse("un globalId doit être une URN oeip", errors.isEmpty());
    }

    @Test
    public void refuseUnTypeDeRelationHorsVocabulaire() {
        JsonObject relations = TestFixtures.json(OeipFormat.RELATIONS);
        relations.getJsonArray("items").getJsonObject(0).put("type", "teleportedTo");
        List<OeipValidationError> errors = validator.validate(
                OeipFormat.RELATIONS, relations, OeipFormat.SCHEMA_RELATIONS);
        assertFalse("le vocabulaire de relations est fermé en 1.0", errors.isEmpty());
    }

    @Test
    public void refuseUneEmpreinteDePieceJointeMalFormee() {
        JsonObject attachments = TestFixtures.json("resources/blog/attachments.json");
        attachments.getJsonArray("items").getJsonObject(0).put("sha256", "pas-une-empreinte");
        List<OeipValidationError> errors = validator.validate(
                "resources/blog/attachments.json", attachments, OeipFormat.SCHEMA_RESOURCE);
        assertFalse(errors.isEmpty());
    }

    @Test
    public void refuseUnCheminDePieceJointeRemontant() {
        JsonObject attachments = TestFixtures.json("resources/blog/attachments.json");
        attachments.getJsonArray("items").getJsonObject(0)
                .put("path", "../../../etc/passwd");
        List<OeipValidationError> errors = validator.validate(
                "resources/blog/attachments.json", attachments, OeipFormat.SCHEMA_RESOURCE);
        assertFalse("un chemin de paquet ne doit jamais remonter", errors.isEmpty());
    }

    @Test
    public void signaleLeCheminExactDeLErreur() {
        JsonObject persons = TestFixtures.json("directory/persons.json");
        persons.getJsonArray("items").getJsonObject(1).put("profile", "Wizard");
        List<OeipValidationError> errors = validator.validate(
                "directory/persons.json", persons, OeipFormat.SCHEMA_DIRECTORY);
        assertFalse(errors.isEmpty());
        boolean located = false;
        for (OeipValidationError e : errors) {
            if (e.getJsonPointer() != null && e.getJsonPointer().contains("/items/1/profile")) {
                located = true;
            }
        }
        // Tout l'intérêt d'OeipValidator par rapport au verticle existant est là : un rapport
        // qui dit QUEL fichier et QUEL endroit, et non un Set.toString().
        assertTrue("l'erreur doit être localisée précisément :" + render(errors), located);
    }

    // ------------------------------------------------------------------ aiguillage

    @Test
    public void choisitLeSchemaSelonLEmplacement() {
        assertEquals(OeipFormat.SCHEMA_MANIFEST, OeipValidator.schemaFor(OeipFormat.MANIFEST));
        assertEquals(OeipFormat.SCHEMA_IDENTIFIERS, OeipValidator.schemaFor(OeipFormat.IDENTIFIERS));
        assertEquals(OeipFormat.SCHEMA_RELATIONS, OeipValidator.schemaFor(OeipFormat.RELATIONS));
        assertEquals(OeipFormat.SCHEMA_DIRECTORY, OeipValidator.schemaFor("directory/persons.json"));
        assertEquals(OeipFormat.SCHEMA_RESOURCE, OeipValidator.schemaFor("resources/blog/resources.json"));
        // Un binaire n'est pas un document : il n'a pas de schéma.
        assertNull(OeipValidator.schemaFor("resources/blog/content/fi/file-0001/squelette.png"));
        assertNull(OeipValidator.schemaFor("native/blog/quelque-chose.json"));
    }
}
