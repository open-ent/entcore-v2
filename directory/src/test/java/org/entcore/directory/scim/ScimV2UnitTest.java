package org.entcore.directory.scim;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Tests sans base de données de l'API /scim/v2 : filtres, conversion, contrôle des claims. */
public class ScimV2UnitTest {

    @Test
    public void filterEqualityIsCaseInsensitiveAndParameterized() throws Exception {
        final ScimFilter.Clause c = ScimFilter.toCypher("userName eq \"Sabine.Seguin\"", ScimMapper.USER_FILTER);
        assertEquals("toLower(u.login) = $f0", c.where);
        assertEquals("sabine.seguin", c.params.getString("f0"));
    }

    @Test
    public void filterSupportsAndPresentAndQualifiedNames() throws Exception {
        final ScimFilter.Clause c = ScimFilter.toCypher(
                "urn:ietf:params:scim:schemas:core:2.0:User:externalId sw \"urn:x\" and emails pr and name.familyName co \"and\"",
                ScimMapper.USER_FILTER);
        assertEquals("toLower(coalesce(u.scimExternalId, u.externalId)) STARTS WITH $f0 AND u.email IS NOT NULL"
                + " AND toLower(u.lastName) CONTAINS $f2", c.where);
        assertEquals("and", c.params.getString("f2"));
    }

    @Test
    public void filterKeepsKeywordsInsideStrings() throws Exception {
        final ScimFilter.Clause c = ScimFilter.toCypher("displayName eq \"Durand or (Martin)\"", ScimMapper.USER_FILTER);
        assertEquals("durand or (martin)", c.params.getString("f0"));
    }

    @Test(expected = ScimFilter.InvalidFilterException.class)
    public void filterRejectsOr() throws Exception {
        ScimFilter.toCypher("userName eq \"a\" or userName eq \"b\"", ScimMapper.USER_FILTER);
    }

    @Test(expected = ScimFilter.InvalidFilterException.class)
    public void filterRejectsUnknownAttribute() throws Exception {
        ScimFilter.toCypher("password eq \"x\"", ScimMapper.USER_FILTER);
    }

    @Test(expected = ScimFilter.InvalidFilterException.class)
    public void filterRejectsInjectionAttempt() throws Exception {
        ScimFilter.toCypher("userName eq \"a\" RETURN u //", ScimMapper.USER_FILTER);
    }

    @Test
    public void emptyFilterMatchesEverything() throws Exception {
        assertEquals("true", ScimFilter.toCypher(null, ScimMapper.USER_FILTER).where);
    }

    @Test
    public void userMappingReflectsLifecycle() {
        final JsonObject row = new JsonObject()
                .put("id", "u1").put("login", "sabine.seguin").put("externalId", "AAF-1")
                .put("firstName", "Sabine").put("lastName", "Seguin").put("displayName", "Seguin Sabine")
                .put("email", "s@demo.fr").put("blocked", false).put("deleteDate", 1700000000000L)
                .put("profiles", new JsonArray().add("Teacher")).put("source", "AAF")
                .put("structures", new JsonArray().add(new JsonObject().put("id", "s1").put("name", "Lycée")).add(new JsonObject()))
                .put("groups", new JsonArray().add(new JsonObject().put("id", "c1").put("name", "6eA")));
        final JsonObject user = ScimMapper.user(row, "https://ent/directory/scim/v2");
        assertFalse("en attente de suppression -> inactif", user.getBoolean("active"));
        assertEquals("AAF-1", user.getString("externalId"));
        assertEquals("s@demo.fr", user.getJsonArray("emails").getJsonObject(0).getString("value"));
        final JsonObject ext = user.getJsonObject(ScimMapper.EXT_USER);
        assertEquals("Teacher", ext.getString("profile"));
        assertTrue(ext.getBoolean("pendingDeletion"));
        assertEquals(1, ext.getJsonArray("structures").size());
        assertEquals("https://ent/directory/scim/v2/Users/u1", user.getJsonObject("meta").getString("location"));
    }

    @Test
    public void groupMappingCanOmitMembers() {
        final JsonObject row = new JsonObject().put("id", "g1").put("name", "Club théâtre").put("type", "functional")
                .put("structureId", "s1").put("structureName", "Lycée")
                .put("members", new JsonArray().add(new JsonObject().put("id", "u1").put("displayName", "A")));
        assertTrue(ScimMapper.group(row, "b", true).containsKey("members"));
        assertFalse(ScimMapper.group(row, "b", false).containsKey("members"));
    }

    @Test
    public void clientTokenRules() {
        final JsonArray allowed = new JsonArray().add("iam-scim");
        final JsonObject token = new JsonObject().put("client_id", "iam-scim")
                .put("remote_user", ScimClientAuth.SYSTEM_USER).put("scope", "directory scim:read");
        assertEquals(null, ScimClientAuth.check(token, "scim:read", allowed));
        assertEquals("scim.token.scope", ScimClientAuth.check(token, "scim:write", allowed));
        assertEquals("scim.client.not.allowed", ScimClientAuth.check(token, "scim:read", new JsonArray().add("other")));
        assertEquals("scim.client.not.allowed", ScimClientAuth.check(token, "scim:read", null));
        // Jeton obtenu au nom d'un utilisateur (code / password grant) : refusé.
        assertEquals("scim.token.not.client.credentials",
                ScimClientAuth.check(token.copy().put("remote_user", "u-123"), "scim:read", allowed));
        assertFalse(ScimClientAuth.hasScope("scim:readonly", "scim:read"));
    }
}
