package org.entcore.feeder.scim;

import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.entcore.common.neo4j.Neo4j;
import org.entcore.feeder.utils.TransactionManager;
import org.entcore.feeder.utils.Validator;
import org.entcore.test.TestHelper;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.testcontainers.containers.Neo4jContainer;

/**
 * Tests de bout en bout (Testcontainers Neo4j — base jetable, aucun impact réel) du connecteur SCIM/SET.
 * Lancement : voir RUNBOOK — mvn -Drevision=6.14.9-patched -DtestContainerVersion=1.21.4 test -Dtest=ScimFeederTest
 */
@RunWith(VertxUnitRunner.class)
public class ScimFeederTest {

    private static final TestHelper test = TestHelper.helper();

    @ClassRule
    public static Neo4jContainer<?> neo4jContainer = test.database().createNeo4jContainer();

    private static final String ORG_ID = "11111111-1111-1111-1111-111111111111";
    private static final String USER_ID = "22222222-2222-2222-2222-222222222222";
    private static final String GROUP_ID = "44444444-4444-4444-4444-444444444444";

    @BeforeClass
    public static void setUp(TestContext context) throws Exception {
        test.database().initNeo4j(context, neo4jContainer);
        final String base = neo4jContainer.getHttpUrl() + "/db/data/";
        final Neo4j neo4j = Neo4j.getInstance();
        neo4j.init(test.vertx(), new JsonObject()
                .put("server-uri", base).put("poolSize", 1).put("ignore-empty-statements-error", false));
        Validator.initLogin(neo4j, test.vertx());
        TransactionManager.getInstance().setNeo4j(neo4j);
    }

    @org.junit.Before
    public void cleanGraph(TestContext context) {
        final Async a = context.async();
        test.database().executeNeo4j("MATCH (n) DETACH DELETE n", new JsonObject())
                .onComplete(r -> a.complete());
    }

    private Future<JsonArray> seedProfiles() {
        return test.database().executeNeo4j(
                "UNWIND ['Student','Teacher','Relative','Personnel','Guest'] AS n " +
                "CREATE (:Profile {name:n, externalId:'PROFILE_'+n})", new JsonObject());
    }

    private JsonObject event(String urn, JsonObject data, String uri) {
        return new JsonObject()
                .put("iss", "https://idm.example.com/base")
                .put("jti", "test-" + uri)
                .put("events", new JsonObject().put(urn, new JsonObject().put("data", data)))
                .put("sub_id", new JsonObject().put("format", "scim").put("uri", uri));
    }

    private JsonObject orgCreate() {
        final JsonObject data = new JsonObject()
                .put("id", ORG_ID)
                .put("externalId", "urn:x-scim:demo:org:academie-demo")
                .put("displayName", "Academie de demonstration Magellan")
                .put("meta", new JsonObject().put("resourceType", "Organization"));
        return event("urn:ietf:params:scim:event:prov:create:full", data, "/Organizations/" + ORG_ID);
    }

    private JsonObject userCreate() {
        final JsonObject data = new JsonObject()
                .put("id", USER_ID)
                .put("userName", "sabine.seguin")
                .put("name", new JsonObject().put("givenName", "Sabine").put("familyName", "Seguin"))
                .put("emails", new JsonArray().add(new JsonObject().put("value", "sabine.seguin@demo.fr").put("primary", true)))
                .put("meta", new JsonObject().put("resourceType", "User")); // pas de grade/track -> profil Teacher
        return event("urn:ietf:params:scim:event:prov:create:full", data, "/Users/" + USER_ID);
    }

    private JsonObject roleAssignmentUserToOrg() {
        final JsonObject data = new JsonObject()
                .put("id", "33333333-3333-3333-3333-333333333333")
                .put("subject", new JsonObject().put("type", "User").put("value", USER_ID))
                .put("scope", new JsonObject().put("type", "Organization").put("value", ORG_ID))
                .put("role", new JsonObject().put("value", "member"))
                .put("meta", new JsonObject().put("resourceType", "RoleAssignment"));
        return event("urn:ietf:params:scim:event:prov:create:full", data, "/RoleAssignments/33333333-3333-3333-3333-333333333333");
    }

    private JsonObject groupCreate() {
        final JsonObject data = new JsonObject()
                .put("id", GROUP_ID)
                .put("displayName", "Classe 6eA")
                .put("urn:twid:scim:schemas:extension:tw:2.0:Group", new JsonObject().put("type", "class"))
                .put("meta", new JsonObject().put("resourceType", "Group"));
        return event("urn:ietf:params:scim:event:prov:create:full", data, "/Groups/" + GROUP_ID);
    }

    private JsonObject ra(String id, String subjType, String subjId, String scopeType, String scopeId) {
        final JsonObject data = new JsonObject()
                .put("id", id)
                .put("subject", new JsonObject().put("type", subjType).put("value", subjId))
                .put("scope", new JsonObject().put("type", scopeType).put("value", scopeId))
                .put("role", new JsonObject().put("value", "member"))
                .put("meta", new JsonObject().put("resourceType", "RoleAssignment"));
        return event("urn:ietf:params:scim:event:prov:create:full", data, "/RoleAssignments/" + id);
    }

    private JsonObject deleteEvent(String resType, String id) {
        return event("urn:ietf:params:scim:event:prov:delete", new JsonObject(), "/" + resType + "s/" + id);
    }
    private JsonObject deactivateEvent(String userId) {
        return event("urn:ietf:params:scim:event:prov:deactivate", new JsonObject(), "/Users/" + userId);
    }
    private JsonObject patchDisplayNameEvent(String userId, String newName) {
        final JsonObject data = new JsonObject()
                .put("schemas", new JsonArray().add("urn:ietf:params:scim:api:messages:2.0:PatchOp"))
                .put("Operations", new JsonArray().add(new JsonObject()
                        .put("op", "replace").put("path", "displayName")
                        .put("value", new JsonArray().add(newName))))
                .put("meta", new JsonObject().put("resourceType", "User"));
        return event("urn:ietf:params:scim:event:prov:patch:full", data, "/Users/" + userId);
    }

    @Test
    public void shouldBeIdempotentOnReplay(TestContext context) {
        final Async async = context.async();
        final ScimFeeder scim = new ScimFeeder(Neo4j.getInstance());
        seedProfiles()
                .compose(x -> scim.handleEvent(orgCreate()))
                .compose(x -> scim.handleEvent(orgCreate())) // rejeu du MÊME événement
                .compose(x -> test.database().executeNeo4j(
                        "MATCH (s:Structure {source:'SCIM'}) OPTIONAL MATCH (s)<-[:DEPENDS]-(pg:ProfileGroup) " +
                        "RETURN count(DISTINCT s) AS s, count(DISTINCT pg) AS pg", new JsonObject()))
                .onComplete(context.asyncAssertSuccess(rows -> {
                    context.assertEquals(1, rows.getJsonObject(0).getInteger("s"), "une seule structure (pas de doublon)");
                    context.assertEquals(5, rows.getJsonObject(0).getInteger("pg"), "5 ProfileGroups (pas 10)");
                    async.complete();
                }));
    }

    private Future<JsonArray> seedDeleteGroup() {
        return test.database().executeNeo4j("CREATE (:DeleteGroup:ProfileGroup:Group)", new JsonObject());
    }

    @Test
    public void shouldPreDeleteUser(TestContext context) {
        final Async async = context.async();
        final ScimFeeder scim = new ScimFeeder(Neo4j.getInstance());
        seedProfiles()
                .compose(x -> seedDeleteGroup())
                .compose(x -> scim.handleEvent(orgCreate()))
                .compose(x -> scim.handleEvent(userCreate()))
                .compose(x -> scim.handleEvent(ra("ra1", "User", USER_ID, "Organization", ORG_ID)))
                .compose(x -> scim.handleEvent(deleteEvent("User", USER_ID)))
                .compose(x -> test.database().executeNeo4j(
                        "MATCH (u:User {source:'SCIM'}) " +
                        "OPTIONAL MATCH (u)-[:IN]->(dg:DeleteGroup) " +
                        "OPTIONAL MATCH (u)-[:IN]->(pg:ProfileGroup) WHERE NOT pg:DeleteGroup " +
                        "OPTIONAL MATCH (u)-[:HAS_RELATIONSHIPS]->(b:Backup) " +
                        "RETURN HAS(u.deleteDate) AS pending, count(DISTINCT dg) AS dg, count(DISTINCT pg) AS pg, " +
                        "b.IN_OUTGOING AS backup", new JsonObject()))
                .onComplete(context.asyncAssertSuccess(rows -> {
                    context.assertEquals(1, rows.size(), "le compte est conservé jusqu'à la purge");
                    final JsonObject r = rows.getJsonObject(0);
                    context.assertTrue(r.getBoolean("pending"), "deleteDate posé");
                    context.assertEquals(1, r.getInteger("dg"), "dans le DeleteGroup");
                    context.assertEquals(0, r.getInteger("pg"), "plus rattaché à la structure");
                    context.assertEquals(1, r.getJsonArray("backup").size(), "rattachement sauvegardé");
                    async.complete();
                }));
    }

    @Test
    public void shouldRestorePreDeletedUserOnCreate(TestContext context) {
        final Async async = context.async();
        final ScimFeeder scim = new ScimFeeder(Neo4j.getInstance());
        seedProfiles()
                .compose(x -> seedDeleteGroup())
                .compose(x -> scim.handleEvent(orgCreate()))
                .compose(x -> scim.handleEvent(userCreate()))
                .compose(x -> scim.handleEvent(ra("ra1", "User", USER_ID, "Organization", ORG_ID)))
                .compose(x -> scim.handleEvent(deleteEvent("User", USER_ID)))
                .compose(x -> scim.handleEvent(userCreate()))
                .compose(x -> test.database().executeNeo4j(
                        "MATCH (u:User {source:'SCIM'}) " +
                        "OPTIONAL MATCH (u)-[:IN]->(dg:DeleteGroup) " +
                        "OPTIONAL MATCH (u)-[:IN]->(pg:ProfileGroup {filter:'Teacher'})-[:DEPENDS]->(:Structure) " +
                        "RETURN HAS(u.deleteDate) AS pending, count(DISTINCT dg) AS dg, count(DISTINCT pg) AS pg",
                        new JsonObject()))
                .onComplete(context.asyncAssertSuccess(rows -> {
                    context.assertEquals(1, rows.size(), "un seul compte (pas de doublon)");
                    final JsonObject r = rows.getJsonObject(0);
                    context.assertFalse(r.getBoolean("pending"), "deleteDate retiré");
                    context.assertEquals(0, r.getInteger("dg"), "sorti du DeleteGroup");
                    context.assertEquals(1, r.getInteger("pg"), "rattachement à la structure restauré");
                    async.complete();
                }));
    }

    // ------------------------------------------------------------------ API REST /scim/v2

    private static final String REST_USER_ID = "55555555-5555-5555-5555-555555555555";
    private static final String REST_GROUP_ID = "66666666-6666-6666-6666-666666666666";

    private Future<JsonArray> seedAafStructureAndUser() {
        return test.database().executeNeo4j(
                "CREATE (s:Structure {id:'s-aaf', externalId:'AAF-S', name:'Lycée AAF', source:'AAF'}) " +
                "WITH s MATCH (p:Profile) " +
                "CREATE (s)<-[:DEPENDS]-(:Group:ProfileGroup {id:'pg-'+p.name, name:'Lycée AAF-'+p.name, filter:p.name})-[:HAS_PROFILE]->(p) " +
                "WITH DISTINCT s CREATE (:User {id:'u-aaf', externalId:'AAF-U', login:'jean.aaf', source:'AAF', profiles:['Teacher']})",
                new JsonObject());
    }

    private JsonObject restUser() {
        return new JsonObject()
                .put("externalId", "iam-42")
                .put("name", new JsonObject().put("givenName", "Ines").put("familyName", "Rest"))
                .put("emails", new JsonArray().add(new JsonObject().put("value", "ines@demo.fr").put("primary", true)))
                .put(ScimFeeder.ODE_USER_EXT, new JsonObject().put("profile", "Teacher")
                        .put("structures", new JsonArray().add(new JsonObject().put("value", "s-aaf"))));
    }

    @Test
    public void restCreateUserKeepsIdAndAttachesToExistingStructure(TestContext context) {
        final Async async = context.async();
        final ScimFeeder scim = new ScimFeeder(Neo4j.getInstance());
        seedProfiles()
                .compose(x -> seedAafStructureAndUser())
                .compose(x -> scim.handleRest("user-create", new JsonObject().put("id", REST_USER_ID).put("resource", restUser())))
                .compose(x -> test.database().executeNeo4j(
                        "MATCH (u:User {id:{id}})-[r:IN {source:'SCIM'}]->(:ProfileGroup {filter:'Teacher'})-[:DEPENDS]->(s:Structure) " +
                        "RETURN u.scimExternalId AS ext, u.login AS login, s.id AS s", new JsonObject().put("id", REST_USER_ID)))
                .onComplete(context.asyncAssertSuccess(rows -> {
                    context.assertEquals(1, rows.size(), "rattaché à la structure AAF existante");
                    context.assertEquals("iam-42", rows.getJsonObject(0).getString("ext"));
                    context.assertNotNull(rows.getJsonObject(0).getString("login"), "login généré par l'ENT");
                    async.complete();
                }));
    }

    @Test
    public void restCreateRejectsDuplicateExternalId(TestContext context) {
        final ScimFeeder scim = new ScimFeeder(Neo4j.getInstance());
        seedProfiles()
                .compose(x -> seedAafStructureAndUser())
                .compose(x -> scim.handleRest("user-create", new JsonObject().put("id", REST_USER_ID).put("resource", restUser())))
                .compose(x -> scim.handleRest("user-create", new JsonObject().put("id", "other-id").put("resource", restUser())))
                .onComplete(context.asyncAssertFailure(err ->
                        context.assertTrue(err.getMessage().startsWith("scim.conflict"), err.getMessage())));
    }

    @Test
    public void restCreateRestoresDeletedUserWithSameExternalId(TestContext context) {
        final Async async = context.async();
        final ScimFeeder scim = new ScimFeeder(Neo4j.getInstance());
        final JsonObject again = restUser().put("name", new JsonObject().put("givenName", "Inès").put("familyName", "Rest"));
        seedProfiles()
                .compose(x -> seedDeleteGroup())
                .compose(x -> seedAafStructureAndUser())
                .compose(x -> scim.handleRest("user-create", new JsonObject().put("id", REST_USER_ID).put("resource", restUser())))
                .compose(x -> scim.handleRest("user-delete", new JsonObject().put("id", REST_USER_ID)))
                .compose(x -> scim.handleRest("user-create", new JsonObject().put("id", "other-id").put("resource", again)))
                .compose(created -> {
                    context.assertEquals(REST_USER_ID, created.getString("id"), "id d'origine renvoyé");
                    return test.database().executeNeo4j(
                            "MATCH (u:User {scimExternalId:'iam-42'}) " +
                            "OPTIONAL MATCH (u)-[:IN]->(dg:DeleteGroup) " +
                            "OPTIONAL MATCH (u)-[:IN]->(pg:ProfileGroup {filter:'Teacher'})-[:DEPENDS]->(:Structure {id:'s-aaf'}) " +
                            "RETURN u.id AS id, u.firstName AS fn, HAS(u.deleteDate) AS pending, count(DISTINCT dg) AS dg, count(DISTINCT pg) AS pg",
                            new JsonObject());
                })
                .onComplete(context.asyncAssertSuccess(rows -> {
                    context.assertEquals(1, rows.size(), "un seul compte (pas de doublon)");
                    final JsonObject r = rows.getJsonObject(0);
                    context.assertEquals(REST_USER_ID, r.getString("id"));
                    context.assertEquals("Inès", r.getString("fn"), "ressource reçue appliquée");
                    context.assertFalse(r.getBoolean("pending"), "deleteDate retiré");
                    context.assertEquals(0, r.getInteger("dg"), "sorti du DeleteGroup");
                    context.assertEquals(1, r.getInteger("pg"), "rattachement à la structure restauré");
                    async.complete();
                }));
    }

    @Test
    public void restCannotModifyAccountOfAnotherSource(TestContext context) {
        final ScimFeeder scim = new ScimFeeder(Neo4j.getInstance());
        final JsonObject patch = new JsonObject().put("Operations", new JsonArray().add(new JsonObject()
                .put("op", "replace").put("value", new JsonObject().put("active", false))));
        seedProfiles()
                .compose(x -> seedAafStructureAndUser())
                .compose(x -> scim.handleRest("user-patch", new JsonObject().put("id", "u-aaf").put("resource", patch)))
                .onComplete(context.asyncAssertFailure(err ->
                        context.assertTrue(err.getMessage().startsWith("scim.readonly"), err.getMessage())));
    }

    @Test
    public void restPatchWithoutPathDeactivatesUser(TestContext context) {
        final Async async = context.async();
        final ScimFeeder scim = new ScimFeeder(Neo4j.getInstance());
        final JsonObject patch = new JsonObject().put("Operations", new JsonArray().add(new JsonObject()
                .put("op", "Replace").put("value", new JsonObject().put("active", false)
                        .put("name", new JsonObject().put("familyName", "Rest-Martin")))));
        seedProfiles()
                .compose(x -> seedAafStructureAndUser())
                .compose(x -> scim.handleRest("user-create", new JsonObject().put("id", REST_USER_ID).put("resource", restUser())))
                .compose(x -> scim.handleRest("user-patch", new JsonObject().put("id", REST_USER_ID).put("resource", patch)))
                .compose(x -> test.database().executeNeo4j("MATCH (u:User {id:{id}}) RETURN u.blocked AS b, u.lastName AS ln",
                        new JsonObject().put("id", REST_USER_ID)))
                .onComplete(context.asyncAssertSuccess(rows -> {
                    context.assertEquals(true, rows.getJsonObject(0).getBoolean("b"));
                    context.assertEquals("Rest-Martin", rows.getJsonObject(0).getString("ln"));
                    async.complete();
                }));
    }

    @Test
    public void restGroupMembershipKeepsOtherSources(TestContext context) {
        final Async async = context.async();
        final ScimFeeder scim = new ScimFeeder(Neo4j.getInstance());
        final JsonObject group = new JsonObject().put("displayName", "Club robotique")
                .put(ScimFeeder.ODE_GROUP_EXT, new JsonObject().put("type", "functional")
                        .put("structure", new JsonObject().put("value", "s-aaf")))
                .put("members", new JsonArray().add(new JsonObject().put("value", REST_USER_ID)));
        final JsonObject removeAll = new JsonObject().put("Operations", new JsonArray()
                .add(new JsonObject().put("op", "remove").put("path", "members")));
        seedProfiles()
                .compose(x -> seedAafStructureAndUser())
                .compose(x -> scim.handleRest("user-create", new JsonObject().put("id", REST_USER_ID).put("resource", restUser())))
                .compose(x -> scim.handleRest("group-create", new JsonObject().put("id", REST_GROUP_ID).put("resource", group)))
                // adhésion posée par une autre source : ne doit pas être retirée par SCIM
                .compose(x -> test.database().executeNeo4j("MATCH (u:User {id:'u-aaf'}), (g:Group {id:{g}}) CREATE (u)-[:IN]->(g)",
                        new JsonObject().put("g", REST_GROUP_ID)))
                .compose(x -> scim.handleRest("group-patch", new JsonObject().put("id", REST_GROUP_ID).put("resource", removeAll)))
                .compose(x -> test.database().executeNeo4j(
                        "MATCH (g:FunctionalGroup {id:{g}})-[:DEPENDS]->(:Structure {id:'s-aaf'}) " +
                        "OPTIONAL MATCH (u:User)-[:IN]->(g) RETURN collect(u.id) AS members",
                        new JsonObject().put("g", REST_GROUP_ID)))
                .onComplete(context.asyncAssertSuccess(rows -> {
                    context.assertEquals(1, rows.size(), "groupe fonctionnel rattaché à la structure");
                    context.assertEquals(new JsonArray().add("u-aaf"), rows.getJsonObject(0).getJsonArray("members"));
                    async.complete();
                }));
    }

    @Test
    public void shouldDeactivateUser(TestContext context) {
        final Async async = context.async();
        final ScimFeeder scim = new ScimFeeder(Neo4j.getInstance());
        seedProfiles()
                .compose(x -> scim.handleEvent(orgCreate()))
                .compose(x -> scim.handleEvent(userCreate()))
                .compose(x -> scim.handleEvent(deactivateEvent(USER_ID)))
                .compose(x -> test.database().executeNeo4j(
                        "MATCH (u:User {source:'SCIM'}) RETURN u.blocked AS blocked", new JsonObject()))
                .onComplete(context.asyncAssertSuccess(rows -> {
                    context.assertEquals(true, rows.getJsonObject(0).getBoolean("blocked"), "compte désactivé -> blocked=true");
                    async.complete();
                }));
    }

    @Test
    public void shouldPatchUserDisplayName(TestContext context) {
        final Async async = context.async();
        final ScimFeeder scim = new ScimFeeder(Neo4j.getInstance());
        seedProfiles()
                .compose(x -> scim.handleEvent(orgCreate()))
                .compose(x -> scim.handleEvent(userCreate()))
                .compose(x -> scim.handleEvent(patchDisplayNameEvent(USER_ID, "Sabine SEGUIN-MARTIN")))
                .compose(x -> test.database().executeNeo4j(
                        "MATCH (u:User {source:'SCIM'}) RETURN u.displayName AS dn", new JsonObject()))
                .onComplete(context.asyncAssertSuccess(rows -> {
                    context.assertEquals("Sabine SEGUIN-MARTIN", rows.getJsonObject(0).getString("dn"), "displayName mis à jour");
                    async.complete();
                }));
    }

    private JsonObject studentCreate() {
        final JsonObject data = new JsonObject().put("id", USER_ID).put("userName", "leo.martin")
                .put("name", new JsonObject().put("givenName", "Leo").put("familyName", "Martin"))
                .put("urn:twid:scim:schemas:extension:education:2.0:User", new JsonObject()
                        .put("grade", "PREMIERE GENERALE").put("birthDate", "10/03/2010"))
                .put("meta", new JsonObject().put("resourceType", "User"));
        return event("urn:ietf:params:scim:event:prov:create:full", data, "/Users/" + USER_ID);
    }

    @Test
    public void shouldRemoveMembershipOnRoleAssignmentDelete(TestContext context) {
        final Async async = context.async();
        final ScimFeeder scim = new ScimFeeder(Neo4j.getInstance());
        seedProfiles()
                .compose(x -> scim.handleEvent(orgCreate()))
                .compose(x -> scim.handleEvent(userCreate()))
                .compose(x -> scim.handleEvent(ra("RADEL", "User", USER_ID, "Organization", ORG_ID)))
                .compose(x -> scim.handleEvent(deleteEvent("RoleAssignment", "RADEL")))
                .compose(x -> test.database().executeNeo4j(
                        "MATCH (u:User {source:'SCIM'})-[:IN]->() RETURN count(*) AS c", new JsonObject()))
                .onComplete(context.asyncAssertSuccess(rows -> {
                    context.assertEquals(0, rows.getJsonObject(0).getInteger("c"), "l'appartenance doit être retirée au delete de la RoleAssignment");
                    async.complete();
                }));
    }

    @Test
    public void shouldProvisionStudentWithBirthDate(TestContext context) {
        final Async async = context.async();
        final ScimFeeder scim = new ScimFeeder(Neo4j.getInstance());
        seedProfiles()
                .compose(x -> scim.handleEvent(orgCreate()))
                .compose(x -> scim.handleEvent(studentCreate()))
                .compose(x -> test.database().executeNeo4j(
                        "MATCH (u:User {source:'SCIM'}) RETURN u.profileScim AS p, u.birthDate AS bd", new JsonObject()))
                .onComplete(context.asyncAssertSuccess(rows -> {
                    context.assertEquals("Student", rows.getJsonObject(0).getString("p"), "profil Élève (grade présent)");
                    context.assertEquals("2010-03-10", rows.getJsonObject(0).getString("bd"), "birthDate converti en ISO");
                    async.complete();
                }));
    }

    @Test
    public void shouldAttachUserToClassOfStructure(TestContext context) {
        final Async async = context.async();
        final ScimFeeder scim = new ScimFeeder(Neo4j.getInstance());
        seedProfiles()
                .compose(x -> scim.handleEvent(orgCreate()))
                .compose(x -> scim.handleEvent(groupCreate())) // tw.type=class
                .compose(x -> scim.handleEvent(ra("ra-grp-org", "Group", GROUP_ID, "Organization", ORG_ID)))
                .compose(x -> scim.handleEvent(userCreate()))
                .compose(x -> scim.handleEvent(ra("ra-usr-org", "User", USER_ID, "Organization", ORG_ID)))
                .compose(x -> scim.handleEvent(ra("ra-usr-grp", "User", USER_ID, "Group", GROUP_ID)))
                .compose(x -> test.database().executeNeo4j(
                        "MATCH (u:User {source:'SCIM'})-[:IN]->(cpg:ProfileGroup {filter:'Teacher'})-[:DEPENDS]->(c:Class {source:'SCIM'})-[:BELONGS]->(s:Structure {source:'SCIM'}) " +
                        "RETURN c.name AS className, u.login AS login", new JsonObject()))
                .onComplete(context.asyncAssertSuccess(rows -> {
                    context.assertEquals(1, rows.size(), "l'utilisateur doit être dans le ProfileGroup de la Classe rattachée à la structure");
                    context.assertEquals("Classe 6eA", rows.getJsonObject(0).getString("className"));
                    async.complete();
                }));
    }

    @Test
    public void shouldCreateStructureFromScimOrganizationEvent(TestContext context) {
        final Async async = context.async();
        final ScimFeeder scim = new ScimFeeder(Neo4j.getInstance());
        seedProfiles()
                .compose(x -> scim.handleEvent(orgCreate()))
                .compose(x -> test.database().executeNeo4j(
                        "MATCH (s:Structure {source:'SCIM'}) RETURN s.name AS name, s.externalId AS externalId", new JsonObject()))
                .onComplete(context.asyncAssertSuccess(rows -> {
                    context.assertEquals(1, rows.size(), "une structure SCIM doit exister");
                    final JsonObject s = rows.getJsonObject(0);
                    context.assertEquals("Academie de demonstration Magellan", s.getString("name"));
                    context.assertEquals(ScimFeeder.scimEid(ORG_ID), s.getString("externalId"));
                    async.complete();
                }));
    }

    @Test
    public void shouldProvisionAndAttachUserViaRoleAssignment(TestContext context) {
        final Async async = context.async();
        final ScimFeeder scim = new ScimFeeder(Neo4j.getInstance());
        seedProfiles()
                .compose(x -> scim.handleEvent(orgCreate()))
                .compose(x -> scim.handleEvent(userCreate()))
                .compose(x -> scim.handleEvent(roleAssignmentUserToOrg()))
                .compose(x -> test.database().executeNeo4j(
                        "MATCH (u:User {source:'SCIM'})-[:IN]->(pg:ProfileGroup {filter:'Teacher'})-[:DEPENDS]->(s:Structure {source:'SCIM'}) " +
                        "RETURN u.login AS login, u.activationCode AS activationCode, u.profileScim AS profile, u.displayName AS displayName",
                        new JsonObject()))
                .onComplete(context.asyncAssertSuccess(rows -> {
                    context.assertEquals(1, rows.size(), "l'utilisateur doit être rattaché à la structure (ProfileGroup Teacher)");
                    final JsonObject u = rows.getJsonObject(0);
                    context.assertNotNull(u.getString("login"), "login généré");
                    context.assertNotNull(u.getString("activationCode"), "code d'activation généré (compte non activé)");
                    context.assertEquals("Teacher", u.getString("profile"));
                    context.assertNotNull(u.getString("displayName"), "displayName généré");
                    async.complete();
                }));
    }
}
