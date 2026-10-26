/*
 * Conversion annuaire entcore (lignes Neo4j) <-> ressources SCIM 2.0 (RFC 7643).
 *
 * id SCIM = id entcore (u.id / g.id). externalId SCIM = identifiant fourni par l'IAM
 * (scimExternalId) ou, à défaut, l'externalId entcore (AAF, CSV…).
 * Ce qui est propre à l'ENT (profil, source, structures, activation) est porté par l'extension
 * {@link #EXT_USER}.
 */
package org.entcore.directory.scim;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.HashMap;
import java.util.Map;

public final class ScimMapper {

    public static final String USER = "urn:ietf:params:scim:schemas:core:2.0:User";
    public static final String GROUP = "urn:ietf:params:scim:schemas:core:2.0:Group";
    public static final String EXT_USER = "urn:openent:scim:schemas:extension:education:2.0:User";
    public static final String EXT_GROUP = "urn:openent:scim:schemas:extension:education:2.0:Group";
    public static final String LIST = "urn:ietf:params:scim:api:messages:2.0:ListResponse";
    public static final String ERROR = "urn:ietf:params:scim:api:messages:2.0:Error";
    public static final String PATCH_OP = "urn:ietf:params:scim:api:messages:2.0:PatchOp";

    /** Attributs filtrables des Users (clé en minuscules) -> expression Cypher sur le nœud u. */
    public static final Map<String, String> USER_FILTER = new HashMap<>();
    /** Attributs filtrables des Groups -> expression Cypher sur le nœud g. */
    public static final Map<String, String> GROUP_FILTER = new HashMap<>();
    static {
        USER_FILTER.put("id", "u.id");
        USER_FILTER.put("username", "u.login");
        USER_FILTER.put("externalid", "coalesce(u.scimExternalId, u.externalId)");
        USER_FILTER.put("displayname", "u.displayName");
        USER_FILTER.put("name.givenname", "u.firstName");
        USER_FILTER.put("name.familyname", "u.lastName");
        USER_FILTER.put("emails", "u.email");
        USER_FILTER.put("emails.value", "u.email");
        USER_FILTER.put("profile", "head(u.profiles)");
        GROUP_FILTER.put("id", "g.id");
        GROUP_FILTER.put("displayname", "g.name");
        GROUP_FILTER.put("externalid", "coalesce(g.scimExternalId, g.externalId)");
    }

    private ScimMapper() {}

    public static JsonObject user(JsonObject row, String baseUrl) {
        final String id = row.getString("id");
        final JsonObject user = new JsonObject()
                .put("schemas", new JsonArray().add(USER).add(EXT_USER))
                .put("id", id)
                .put("userName", row.getString("login"))
                .put("name", new JsonObject()
                        .put("givenName", row.getString("firstName"))
                        .put("familyName", row.getString("lastName"))
                        .put("formatted", row.getString("displayName")))
                .put("displayName", row.getString("displayName"))
                // Bloqué ou en attente de suppression : le compte n'est plus utilisable.
                .put("active", !Boolean.TRUE.equals(row.getBoolean("blocked")) && row.getValue("deleteDate") == null);
        final String externalId = row.getString("scimExternalId", row.getString("externalId"));
        if (externalId != null) user.put("externalId", externalId);
        if (row.getString("email") != null) {
            user.put("emails", new JsonArray().add(new JsonObject()
                    .put("value", row.getString("email")).put("type", "work").put("primary", true)));
        }
        final JsonArray groups = new JsonArray();
        for (Object o : row.getJsonArray("groups", new JsonArray())) {
            final JsonObject g = (JsonObject) o;
            if (g.getString("id") == null) continue;
            groups.add(new JsonObject().put("value", g.getString("id")).put("display", g.getString("name"))
                    .put("$ref", baseUrl + "/Groups/" + g.getString("id")).put("type", "direct"));
        }
        if (!groups.isEmpty()) user.put("groups", groups);

        final JsonObject ext = new JsonObject()
                .put("profile", first(row.getJsonArray("profiles")))
                .put("source", row.getString("source"))
                .put("activated", row.getValue("activationCode") == null)
                .put("pendingDeletion", row.getValue("deleteDate") != null);
        if (row.getString("birthDate") != null) ext.put("birthDate", row.getString("birthDate"));
        final JsonArray structures = new JsonArray();
        for (Object o : row.getJsonArray("structures", new JsonArray())) {
            final JsonObject s = (JsonObject) o;
            if (s.getString("id") != null) structures.add(new JsonObject().put("value", s.getString("id")).put("display", s.getString("name")));
        }
        ext.put("structures", structures);
        user.put(EXT_USER, ext);
        user.put("meta", meta("User", id, row, baseUrl + "/Users/" + id));
        return user;
    }

    public static JsonObject group(JsonObject row, String baseUrl, boolean withMembers) {
        final String id = row.getString("id");
        final JsonObject group = new JsonObject()
                .put("schemas", new JsonArray().add(GROUP).add(EXT_GROUP))
                .put("id", id)
                .put("displayName", row.getString("name"));
        final String externalId = row.getString("scimExternalId", row.getString("externalId"));
        if (externalId != null) group.put("externalId", externalId);
        if (withMembers) {
            final JsonArray members = new JsonArray();
            for (Object o : row.getJsonArray("members", new JsonArray())) {
                final JsonObject m = (JsonObject) o;
                if (m.getString("id") == null) continue;
                members.add(new JsonObject().put("value", m.getString("id")).put("display", m.getString("displayName"))
                        .put("$ref", baseUrl + "/Users/" + m.getString("id")).put("type", "User"));
            }
            group.put("members", members);
        }
        group.put(EXT_GROUP, new JsonObject()
                .put("type", row.getString("type"))
                .put("source", row.getString("source"))
                .put("structure", row.getString("structureId") == null ? null
                        : new JsonObject().put("value", row.getString("structureId")).put("display", row.getString("structureName"))));
        group.put("meta", meta("Group", id, row, baseUrl + "/Groups/" + id));
        return group;
    }

    public static JsonObject listResponse(JsonArray resources, long total, int startIndex) {
        return new JsonObject()
                .put("schemas", new JsonArray().add(LIST))
                .put("totalResults", total)
                .put("startIndex", startIndex)
                .put("itemsPerPage", resources.size())
                .put("Resources", resources);
    }

    public static JsonObject error(int status, String scimType, String detail) {
        final JsonObject error = new JsonObject()
                .put("schemas", new JsonArray().add(ERROR))
                .put("status", String.valueOf(status))
                .put("detail", detail);
        if (scimType != null) error.put("scimType", scimType);
        return error;
    }

    private static JsonObject meta(String type, String id, JsonObject row, String location) {
        final JsonObject meta = new JsonObject().put("resourceType", type).put("location", location);
        if (row.getValue("created") != null) meta.put("created", String.valueOf(row.getValue("created")));
        if (row.getValue("modified") != null) meta.put("lastModified", String.valueOf(row.getValue("modified")));
        return meta;
    }

    private static String first(JsonArray array) {
        return array == null || array.isEmpty() ? null : array.getString(0);
    }
}
