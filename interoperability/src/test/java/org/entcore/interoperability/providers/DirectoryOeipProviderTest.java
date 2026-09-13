package org.entcore.interoperability.providers;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.entcore.interoperability.OeipFormat;
import org.entcore.interoperability.schema.OeipSchemaRegistry;
import org.entcore.interoperability.schema.OeipValidationError;
import org.entcore.interoperability.schema.OeipValidator;
import org.entcore.interoperability.spi.OeipCoreExport;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * Le mapper d'annuaire, éprouvé sur des lignes de graphe synthétiques.
 *
 * L'assertion qui compte n'est pas « le code s'exécute » mais « les documents produits valident
 * contre les schémas du format » : c'est la seule garantie qu'un ENT tiers saura les relire.
 */
public class DirectoryOeipProviderTest {

    private static final String SS = "ent.exemple-a.fr";
    private static OeipValidator validator;

    @BeforeClass
    public static void setUp() {
        validator = new OeipValidator(new OeipSchemaRegistry());
    }

    private static DirectoryOeipProvider provider() {
        return new DirectoryOeipProvider(null, SS);
    }

    private static JsonArray persons() {
        return new JsonArray().add(new JsonObject()
                .put("id", "ec847027-d5c6-455f-a599-43753924dff2")
                .put("externalId", "852309")
                .put("login", "emael.shafy001")
                .put("firstName", "Emaël")
                .put("lastName", "SHAFY001")
                .put("displayName", "SHAFY001 Emaël")
                .put("birthDate", "1971-02-28")
                .put("email", "emael.shafy001@exemple-a.fr")
                .put("profile", "Personnel"));
    }

    private static JsonArray orgs() {
        return new JsonArray().add(new JsonObject()
                .put("id", "90836eba-baed-4c56-8e65-71add9494fb4")
                .put("uai", "0291104T")
                .put("name", "CLG-PIERRE MENDES FRANCE-MORLAIX")
                .put("externalId", "707249"));
    }

    private static JsonArray groups() {
        return new JsonArray()
                .add(new JsonObject().put("id", "1642-1670935093305")
                        .put("name", "CLG-PIERRE MENDES FRANCE-MORLAIX-Personnel")
                        .put("labels", new JsonArray().add("ProfileGroup").add("Group").add("Visible"))
                        .put("orgId", "90836eba-baed-4c56-8e65-71add9494fb4"))
                .add(new JsonObject().put("id", "744498-1782115509350")
                        .put("name", "Communauté Personnel & Direction-manager")
                        .put("labels", new JsonArray().add("Group").add("CommunityGroup")));
    }

    private static OeipCoreExport export(boolean pseudonymize) {
        return provider().assemble("ec847027-d5c6-455f-a599-43753924dff2",
                persons(), orgs(), groups(), pseudonymize);
    }

    private static String render(List<OeipValidationError> errors) {
        StringBuilder sb = new StringBuilder();
        for (OeipValidationError e : errors) sb.append("\n  ").append(e);
        return sb.toString();
    }

    // ------------------------------------------------------------------ conformité au schéma

    @Test
    public void lesQuatreDocumentsValidentContreLeSchema() {
        OeipCoreExport out = export(false);
        assertEquals(4, out.getDocuments().size());
        for (Map.Entry<String, JsonObject> doc : out.getDocuments().entrySet()) {
            List<OeipValidationError> errors =
                    validator.validate(doc.getKey(), doc.getValue(), OeipFormat.SCHEMA_DIRECTORY);
            assertTrue(doc.getKey() + render(errors), errors.isEmpty());
        }
    }

    @Test
    public void lIndexDIdentifiantsValideEtCouvreChaqueObjet() {
        OeipCoreExport out = export(false);
        JsonObject identifiers = new JsonObject()
                .put("oeipVersion", OeipFormat.VERSION)
                .put("sourceSystem", SS)
                .put("entries", out.getIdentifierEntries())
                .put("aliases", out.getAliases());
        List<OeipValidationError> errors = validator.validate(
                OeipFormat.IDENTIFIERS, identifiers, OeipFormat.SCHEMA_IDENTIFIERS);
        assertTrue(render(errors), errors.isEmpty());

        // 1 établissement + 1 personne + 2 groupes + 3 adhésions
        assertEquals(7, out.getIdentifierEntries().size());
    }

    @Test
    public void lesRelationsValidentContreLeSchema() {
        OeipCoreExport out = export(false);
        JsonObject relations = new JsonObject()
                .put("oeipVersion", OeipFormat.VERSION)
                .put("sourceSystem", SS)
                .put("items", out.getRelations());
        List<OeipValidationError> errors = validator.validate(
                OeipFormat.RELATIONS, relations, OeipFormat.SCHEMA_RELATIONS);
        assertTrue(render(errors), errors.isEmpty());
        assertEquals(2, out.getRelations().size());
    }

    // ------------------------------------------------------------------ ancres nationales

    @Test
    public void publieLesAncresNationalesQuandEllesExistent() {
        OeipCoreExport out = export(false);
        String all = out.getAliases().encode();
        assertTrue("l'UAI doit servir d'ancre pour l'établissement",
                all.contains("urn:oeip:1.0:org:fr.men.uai:0291104T"));
        assertTrue("l'identifiant d'alimentation doit servir d'ancre pour la personne",
                all.contains("urn:oeip:1.0:person:fr.men.aaf:852309"));
    }

    @Test
    public void nInventePasDAncreQuandLUaiEstAbsentOuInvalide() {
        JsonArray sansUai = new JsonArray().add(new JsonObject()
                .put("id", "org-x").put("name", "Structure sans UAI").put("uai", "PAS-UN-UAI"));
        OeipCoreExport out = provider().assemble("u1", persons(), sansUai, new JsonArray(), false);
        assertFalse("un UAI mal formé ne doit pas produire d'ancre",
                out.getAliases().encode().contains("fr.men.uai"));
        assertNull(out.getDocuments().get("directory/organizations.json")
                .getJsonArray("items").getJsonObject(0).getString("uai"));
    }

    // ------------------------------------------------------------------ données personnelles

    @Test
    public void laPseudonymisationRetireLesElementsIdentifiants() {
        JsonObject person = export(true).getDocuments()
                .get("directory/persons.json").getJsonArray("items").getJsonObject(0);
        assertNull("le login est directement identifiant", person.getString("login"));
        assertNull("la date de naissance est directement identifiante", person.getString("birthDate"));
        assertNull("l'identifiant d'origine permet de remonter à la personne", person.getString("sourceId"));
        assertNull(person.getJsonArray("emails"));
        assertNull("un nom est directement identifiant", person.getString("firstName"));
        assertNull("un nom est directement identifiant", person.getString("lastName"));
        assertNull(person.getString("displayName"));
        // L'identifiant d'alimentation permet de retrouver la personne par rapprochement avec un
        // extrait d'annuaire : il est identifiant en lui-même.
        assertNull(person.getString("externalId"));
        // Le profil et le rattachement restent : sans eux le paquet n'aurait plus d'utilité.
        assertEquals("Personnel", person.getString("profile"));
        assertNotNull(person.getJsonArray("orgRefs"));
    }

    @Test
    public void signaleLesPersonnesMineures() {
        assertEquals("standard", DirectoryOeipProvider.sensitivity("Personnel", "1971-02-28"));
        assertEquals("minor", DirectoryOeipProvider.sensitivity("Student", "2015-04-17"));
        // Dans le doute, on signale : un élève sans date de naissance est traité comme mineur.
        assertEquals("minor", DirectoryOeipProvider.sensitivity("Student", null));
        assertEquals("standard", DirectoryOeipProvider.sensitivity("Teacher", null));
        // Un élève majeur ne doit pas être signalé à tort.
        assertEquals("standard", DirectoryOeipProvider.sensitivity("Student", "1990-01-01"));
    }

    @Test
    public void laPseudonymisationRendLesIdentifiantsOpaques() {
        String clair = export(false).getDocuments().get("directory/persons.json")
                .getJsonArray("items").getJsonObject(0).getString("globalId");
        String opaque = export(true).getDocuments().get("directory/persons.json")
                .getJsonArray("items").getJsonObject(0).getString("globalId");

        assertTrue("en clair, l'identifiant d'origine est lisible",
                clair.endsWith("ec847027-d5c6-455f-a599-43753924dff2"));
        assertFalse("pseudonymisé, il ne doit plus l'être",
                opaque.contains("ec847027-d5c6-455f-a599-43753924dff2"));
        assertTrue(opaque, opaque.matches("^urn:oeip:1\\.0:person:[^:]+:p[a-f0-9]{32}$"));
        // Déterministe : deux exports du même compte doivent produire la même identité, sans
        // quoi rien ne pourrait être rapproché d'un envoi à l'autre.
        assertEquals(opaque, export(true).getDocuments().get("directory/persons.json")
                .getJsonArray("items").getJsonObject(0).getString("globalId"));
    }

    @Test
    public void lesReferencesCroiseesSurviventALaPseudonymisation() {
        OeipCoreExport out = export(true);
        String personGid = out.getDocuments().get("directory/persons.json")
                .getJsonArray("items").getJsonObject(0).getString("globalId");
        JsonArray memberships = out.getDocuments().get("directory/memberships.json")
                .getJsonArray("items");

        // Le calcul doit être identique partout : une divergence romprait la référence sans que
        // rien ne le signale.
        for (int i = 0; i < memberships.size(); i++) {
            assertEquals("l'adhésion doit désigner la personne pseudonymisée",
                    personGid, memberships.getJsonObject(i).getString("personRef"));
        }
        String orgGid = out.getDocuments().get("directory/organizations.json")
                .getJsonArray("items").getJsonObject(0).getString("globalId");
        assertTrue(out.getDocuments().get("directory/persons.json").getJsonArray("items")
                .getJsonObject(0).getJsonArray("orgRefs").contains(orgGid));
    }

    @Test
    public void aucuneAncreNationaleNEstPublieeEnPseudonymise() {
        OeipCoreExport out = export(true);
        String aliases = out.getAliases().encode();
        // L'UAI et l'identifiant d'alimentation sont par nature identifiants : les publier
        // viderait la pseudonymisation de son sens.
        assertFalse("aucune ancre d'établissement", aliases.contains("fr.men.uai"));
        assertFalse("aucune ancre de personne", aliases.contains("fr.men.aaf"));
        assertNull(out.getDocuments().get("directory/organizations.json")
                .getJsonArray("items").getJsonObject(0).getString("uai"));
        // Et l'index ne doit pas conserver l'identifiant d'origine.
        assertFalse(out.getIdentifierEntries().encode().contains("\"sourceId\""));
    }

    // ------------------------------------------------------------------ correspondances

    @Test
    public void traduitLesEtiquettesDeGroupe() {
        assertEquals("ProfileGroup", DirectoryOeipProvider.groupType(
                new JsonArray().add("Group").add("ProfileGroup")));
        assertEquals("CommunityGroup", DirectoryOeipProvider.groupType(
                new JsonArray().add("Group").add("CommunityGroup")));
        assertEquals("FunctionalGroup", DirectoryOeipProvider.groupType(
                new JsonArray().add("Group").add("DirectionGroup")));
        assertEquals("FunctionalGroup", DirectoryOeipProvider.groupType(
                new JsonArray().add("Group").add("FuncGroup")));
        assertEquals("ManualGroup", DirectoryOeipProvider.groupType(new JsonArray().add("Group")));
        assertEquals("ManualGroup", DirectoryOeipProvider.groupType(null));
    }

    @Test
    public void unProfilInconnuNeFaitPasEchouerLExport() {
        assertEquals("Teacher", DirectoryOeipProvider.normalizeProfile("Teacher"));
        assertEquals("Guest", DirectoryOeipProvider.normalizeProfile("Sorcier"));
        assertEquals("Guest", DirectoryOeipProvider.normalizeProfile(null));
    }

    @Test
    public void assainitLesIdentifiantsExotiques() {
        // La partie locale d'une URN n'admet qu'un jeu restreint : un identifiant exotique est
        // transformé, pas rejeté — sinon un seul objet ferait échouer tout l'export.
        String urn = OeipUrn.of("person", SS, OeipUrn.localPart("id avec espace/et#slash", SS, false));
        assertTrue(urn, urn.matches(
                "^urn:oeip:1\\.0:person:[A-Za-z0-9][A-Za-z0-9.-]*:[A-Za-z0-9._~-]+$"));
        assertEquals("inconnu", OeipUrn.sanitize(null));
    }

    @Test
    public void lAdhesionPorteUnRoleEtUnConteneur() {
        JsonArray items = export(false).getDocuments()
                .get("directory/memberships.json").getJsonArray("items");
        assertEquals(3, items.size());
        JsonObject org = items.getJsonObject(0);
        assertTrue(org.getString("containerRef").contains(":org:"));
        assertEquals("member", org.getString("role"));
        for (int i = 0; i < items.size(); i++) {
            assertNotNull(items.getJsonObject(i).getString("personRef"));
            assertNotNull(items.getJsonObject(i).getString("globalId"));
        }
    }

    @Test
    public void declareUneFideliteJustifiee() {
        OeipCoreExport out = export(false);
        assertEquals(OeipFormat.FIDELITY_PARTIAL, out.getFidelity());
        assertNotNull("une fidélité dégradée doit être justifiée", out.getNotice());
        assertEquals(1, (int) out.getCounts().getInteger("organizations"));
        assertEquals(2, (int) out.getCounts().getInteger("groups"));
    }
}
