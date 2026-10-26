/*
 * Connecteur de provisioning SCIM/SET pour l'IAM (Magellan / Worldline Trusted Workspace).
 *
 * Reçoit des événements SCIM (Security Event Token décodé) et les applique à l'annuaire Neo4j,
 * en réutilisant les writers du feeder et en taguant tout avec source = "SCIM" (isolation vis-à-vis
 * d'AAF / CSV / MANUAL).
 *
 * Corrélation inter-événements : les ressources sont clées sur l'`id` SCIM (UUID), car c'est cet
 * identifiant que les RoleAssignment référencent (subject.value / scope.value). On stocke donc
 * externalId = "urn:x-scim:<id>". Le externalId SCIM d'origine (ex. urn:x-aaf:...) est conservé à part
 * dans `scimExternalId` pour corrélation ultérieure avec l'AAF.
 *
 * Tranches couvertes (idempotentes, MERGE par externalId) : Organisation -> Structure ;
 * User -> utilisateur ; Group -> Class ou groupe fonctionnel ; RoleAssignment -> rattachements ;
 * patch User ; activate/deactivate (blocked) ; delete. Le delete d'un User suit le cycle de vie des
 * autres sources (backupRelationship + preDelete, purge différée) et un re-create le restaure.
 *
 * Décisions intérimaires (à confirmer avec Worldline) :
 *  - membership = via RoleAssignment (on ignore le patch:full "members" redondant) ;
 *  - profil = heuristique : extension éducation grade/track présents -> Student, sinon Teacher.
 * TODO : mapping profil exact (attribut Worldline).
 */
package org.entcore.feeder.scim;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.entcore.common.audit.AuditEvent;
import org.entcore.common.neo4j.Neo4j;
import org.entcore.common.neo4j.TransactionHelper;
import org.entcore.feeder.dictionary.structures.User;
import org.entcore.feeder.exceptions.TransactionException;
import org.entcore.feeder.utils.StatementsBuilder;
import org.entcore.feeder.utils.TransactionManager;
import org.entcore.feeder.utils.Validator;
import org.joda.time.DateTime;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

public class ScimFeeder {

    /** Étiquette de source posée sur tous les nœuds/relations créés par ce connecteur. */
    public static final String SOURCE = "SCIM";

    private static final String EDU_USER_EXT = "urn:twid:scim:schemas:extension:education:2.0:User";
    private static final String TW_GROUP_EXT = "urn:twid:scim:schemas:extension:tw:2.0:Group";
    /** Extensions Open ENT de l'API /scim/v2 (voir ScimMapper côté directory). */
    static final String ODE_USER_EXT = "urn:openent:scim:schemas:extension:education:2.0:User";
    static final String ODE_GROUP_EXT = "urn:openent:scim:schemas:extension:education:2.0:Group";

    private static final Validator structureValidator = new Validator("dictionary/schema/Structure.json");
    private static final Map<String, Validator> profileValidators = new HashMap<>();
    static {
        profileValidators.put("Teacher", new Validator("dictionary/schema/Personnel.json"));
        profileValidators.put("Personnel", new Validator("dictionary/schema/Personnel.json"));
        profileValidators.put("Student", new Validator("dictionary/schema/Student.json"));
        profileValidators.put("Relative", new Validator("dictionary/schema/User.json"));
        profileValidators.put("Guest", new Validator("dictionary/schema/User.json"));
    }

    private final Neo4j neo4j;

    public ScimFeeder(Neo4j neo4j) {
        this.neo4j = neo4j;
    }

    /** externalId entcore dérivé de l'id SCIM (UUID) — clé de corrélation inter-événements. */
    static String scimEid(String scimId) {
        return "urn:x-scim:" + scimId;
    }

    /** Applique un événement SET/SCIM déjà décodé (le payload du JWT). */
    public Future<JsonObject> handleEvent(JsonObject setPayload) {
        final JsonObject events = setPayload.getJsonObject("events", new JsonObject());
        if (events.isEmpty()) {
            return Future.failedFuture("scim.event.empty");
        }
        final String urn = events.fieldNames().iterator().next();
        final String type = urn.toLowerCase();
        final JsonObject data = events.getJsonObject(urn, new JsonObject()).getJsonObject("data", new JsonObject());
        final String resourceType = resolveResourceType(data, setPayload);
        if (type.contains(":prov:create:")) {
            if ("Organization".equals(resourceType)) return upsertStructure(data);
            if ("User".equals(resourceType)) return upsertUser(data);
            if ("Group".equals(resourceType)) return upsertGroup(data);
            if ("RoleAssignment".equals(resourceType)) return linkRoleAssignment(data);
        }
        // Cycle de vie (data souvent vide -> la ressource est identifiée par sub_id.uri).
        if (type.contains(":prov:delete")) {
            return deleteResource(resourceType, subIdValue(setPayload));
        }
        if (type.contains(":prov:activate")) {   // compte actif
            return setUserBlocked(subIdValue(setPayload), false);
        }
        if (type.contains(":prov:deactivate")) { // compte inactif
            return setUserBlocked(subIdValue(setPayload), true);
        }
        if (type.contains(":prov:patch") && "User".equals(resourceType)) {
            return applyUserPatch(subIdValue(setPayload), data);
        }
        return Future.succeededFuture(new JsonObject()
                .put("status", "ignored").put("resourceType", String.valueOf(resourceType)).put("event", type));
    }

    // ---------------------------------------------------------------- API REST /scim/v2 (directory)

    /**
     * Écritures de l'API /scim/v2. Seules les ressources de source SCIM sont modifiables : un compte
     * AAF, CSV ou manuel reste géré par sa source (erreur "scim.readonly", traduite en 403).
     * Réponse : {id} de la ressource concernée.
     */
    public Future<JsonObject> handleRest(String op, JsonObject payload) {
        final String id = payload.getString("id");
        if (id == null) return Future.failedFuture("scim.invalid.no.id");
        switch (op) {
            case "user-create":
                return createUser(id, payload.getJsonObject("resource", new JsonObject()));
            case "user-replace":
                return requireScimSource("User", id).compose(x -> replaceUser(id, payload.getJsonObject("resource", new JsonObject())));
            case "user-patch":
                return requireScimSource("User", id).compose(x -> exec("MATCH (u:User {id:{id}}) SET u += {props} RETURN u.id AS id ",
                        new JsonObject().put("id", id).put("props", userPatchProps(payload.getJsonObject("resource", new JsonObject()))),
                        "scim.patch.error"))
                        .compose(x -> syncUserStructuresFromPatch(id, payload.getJsonObject("resource", new JsonObject())));
            case "user-delete":
                return requireScimSource("User", id).compose(x -> preDeleteUserById(id));
            case "group-create":
                return createGroup(id, payload.getJsonObject("resource", new JsonObject()));
            case "group-patch":
                return requireScimSource("Group", id).compose(x -> patchGroup(id, payload.getJsonObject("resource", new JsonObject())));
            case "group-delete":
                return requireScimSource("Group", id).compose(x -> exec("MATCH (g:Group {id:{id}}) " +
                        "OPTIONAL MATCH (g)<-[:DEPENDS]-(cpg:ProfileGroup) DETACH DELETE cpg, g RETURN 1 AS ok ",
                        new JsonObject().put("id", id), "scim.delete.error"));
            default:
                return Future.failedFuture("scim.invalid.op:" + op);
        }
    }

    private Future<JsonObject> requireScimSource(String label, String id) {
        return exec("OPTIONAL MATCH (n:" + label + " {id:{id}}) RETURN n.id AS id, n.source AS source ",
                new JsonObject().put("id", id), "scim.read.error").compose(r -> {
            final JsonObject row = r.getJsonArray("result", new JsonArray()).isEmpty() ? new JsonObject()
                    : r.getJsonArray("result").getJsonObject(0);
            if (row.getString("id") == null) return Future.failedFuture("scim.notfound:" + label + "/" + id);
            if (!SOURCE.equals(row.getString("source"))) {
                return Future.failedFuture("scim.readonly:" + label + " managed by source " + row.getString("source"));
            }
            return Future.succeededFuture(r);
        });
    }

    private Future<JsonObject> createUser(String id, JsonObject resource) {
        final String externalId = resource.getString("externalId");
        final JsonObject check = new JsonObject().put("id", id).put("externalId", externalId);
        return exec("OPTIONAL MATCH (u:User) WHERE u.id = {id} OR (u.scimExternalId IS NOT NULL AND u.scimExternalId = {externalId}) " +
                "RETURN u.id AS id, u.source AS source, HAS(u.deleteDate) AS pending ", check, "scim.read.error").compose(r -> {
            final JsonObject existing = r.getJsonArray("result").getJsonObject(0);
            final String existingId = existing.getString("id");
            if (existingId == null) {
                return upsertUser(resource.copy().put("id", id))
                        .compose(x -> exec("MATCH (u:User {id:{id}}) SET u.scimExternalId = {externalId}, u.blocked = {blocked} RETURN u.id AS id ",
                                check.copy().put("blocked", Boolean.FALSE.equals(resource.getBoolean("active"))), "scim.user.create.error"))
                        .compose(x -> setUserStructures(id, structureIds(resource)))
                        .map(x -> new JsonObject().put("id", id));
            }
            // Compte SCIM supprimé puis provisionné de nouveau (même externalId) : il est restauré
            // avec son id d'origine, puis aligné sur la ressource reçue, au lieu d'un 409.
            if (externalId != null && SOURCE.equals(existing.getString("source")) && Boolean.TRUE.equals(existing.getBoolean("pending"))) {
                return restorePreDeletedById(existingId).compose(x -> replaceUser(existingId, resource));
            }
            return Future.failedFuture("scim.conflict:user already exists (id or externalId)");
        });
    }

    private Future<JsonObject> replaceUser(String id, JsonObject resource) {
        final JsonObject name = resource.getJsonObject("name", new JsonObject());
        final JsonObject props = new JsonObject()
                .put("firstName", name.getString("givenName"))
                .put("lastName", name.getString("familyName"))
                .put("blocked", Boolean.FALSE.equals(resource.getBoolean("active")))
                .put("modified", DateTime.now().toString());
        if (resource.getString("displayName") != null) props.put("displayName", resource.getString("displayName"));
        if (resource.getString("externalId") != null) props.put("scimExternalId", resource.getString("externalId"));
        // PUT remplace : un attribut absent est effacé (email), sauf les identifiants générés par l'ENT.
        props.put("email", primaryEmail(resource));
        return exec("MATCH (u:User {id:{id}}) SET u += {props} RETURN u.id AS id ",
                new JsonObject().put("id", id).put("props", props), "scim.user.replace.error")
                .compose(x -> setUserStructures(id, structureIds(resource)))
                .map(x -> new JsonObject().put("id", id));
    }

    /** PATCH sur l'extension Open ENT (structures) : remplace la liste des structures. */
    private Future<JsonObject> syncUserStructuresFromPatch(String id, JsonObject patch) {
        JsonArray structures = null;
        for (Object o : patch.getJsonArray("Operations", new JsonArray())) {
            final JsonObject op = (JsonObject) o;
            final String path = op.getString("path", "");
            if ((ODE_USER_EXT + ":structures").equals(path)) {
                structures = op.getValue("value") instanceof JsonArray ? op.getJsonArray("value") : new JsonArray();
            } else if (path.isEmpty() && op.getValue("value") instanceof JsonObject
                    && op.getJsonObject("value").containsKey(ODE_USER_EXT)) {
                structures = op.getJsonObject("value").getJsonObject(ODE_USER_EXT).getJsonArray("structures");
            }
        }
        if (structures == null) return Future.succeededFuture(new JsonObject().put("id", id));
        return setUserStructures(id, valuesOf(structures)).map(x -> new JsonObject().put("id", id));
    }

    private static JsonArray structureIds(JsonObject resource) {
        final JsonObject ext = resource.getJsonObject(ODE_USER_EXT);
        if (ext == null || ext.getJsonArray("structures") == null) return null;
        return valuesOf(ext.getJsonArray("structures"));
    }

    private static JsonArray valuesOf(JsonArray refs) {
        final JsonArray ids = new JsonArray();
        for (Object o : refs) {
            if (o instanceof JsonObject && ((JsonObject) o).getString("value") != null) ids.add(((JsonObject) o).getString("value"));
            else if (o instanceof String) ids.add(o);
        }
        return ids;
    }

    /**
     * Aligne les rattachements SCIM de l'utilisateur sur la liste de structures (ids entcore) :
     * ajout au ProfileGroup de son profil, retrait des rattachements SCIM hors liste. Les
     * rattachements d'autres sources ne sont pas touchés. null = liste non fournie, rien à faire.
     */
    private Future<JsonObject> setUserStructures(String userId, JsonArray structureIds) {
        if (structureIds == null) return Future.succeededFuture(new JsonObject());
        final JsonObject params = new JsonObject().put("id", userId).put("structures", structureIds).put("source", SOURCE);
        return exec("MATCH (u:User {id:{id}})-[r:IN {source:{source}}]->(:ProfileGroup)-[:DEPENDS]->(s:Structure) " +
                "WHERE NOT s.id IN {structures} DELETE r RETURN count(r) AS removed ", params, "scim.user.structures.error")
                .compose(x -> exec("MATCH (u:User {id:{id}}) MATCH (s:Structure)<-[:DEPENDS]-(pg:ProfileGroup) " +
                        "WHERE s.id IN {structures} AND pg.filter = coalesce(u.profileScim, head(u.profiles)) " +
                        "MERGE (u)-[:IN {source:{source}}]->(pg) " +
                        "WITH u, collect(DISTINCT s.externalId) AS eids " +
                        "SET u.structures = eids RETURN u.id AS id ", params, "scim.user.structures.error"));
    }

    private Future<JsonObject> createGroup(String id, JsonObject resource) {
        final JsonObject ext = resource.getJsonObject(ODE_GROUP_EXT, new JsonObject());
        final String structureId = ext.getJsonObject("structure", new JsonObject()).getString("value");
        if (structureId == null) return Future.failedFuture("scim.invalid:" + ODE_GROUP_EXT + ":structure required");
        if (resource.getString("displayName") == null) return Future.failedFuture("scim.invalid:displayName required");
        return exec("OPTIONAL MATCH (g:Group {id:{id}}) OPTIONAL MATCH (s:Structure {id:{structureId}}) " +
                "RETURN g.id AS id, s.id AS structureId ", new JsonObject().put("id", id).put("structureId", structureId),
                "scim.read.error").compose(r -> {
            final JsonObject row = r.getJsonArray("result").getJsonObject(0);
            if (row.getString("id") != null) return Future.failedFuture("scim.conflict:group already exists");
            if (row.getString("structureId") == null) return Future.failedFuture("scim.invalid:unknown structure " + structureId);
            return upsertGroup(resource.copy().put("id", id));
        })
        .compose(x -> exec("MATCH (g:Group {id:{id}}) SET g.scimExternalId = {externalId} RETURN g.id AS id ",
                new JsonObject().put("id", id).put("externalId", resource.getString("externalId")), "scim.group.create.error"))
        .compose(x -> linkGroupToStructure("MATCH (g:Group {id:{id}}) MATCH (s:Structure {id:{structureId}}) ",
                new JsonObject().put("id", id).put("structureId", structureId)))
        .compose(x -> addMembers(id, valuesOf(resource.getJsonArray("members", new JsonArray()))))
        .map(x -> new JsonObject().put("id", id));
    }

    /** PATCH Group : add/remove members, replace displayName (formes avec ou sans path). */
    private Future<JsonObject> patchGroup(String id, JsonObject patch) {
        Future<JsonObject> chain = Future.succeededFuture(new JsonObject());
        for (Object o : patch.getJsonArray("Operations", new JsonArray())) {
            final JsonObject op = (JsonObject) o;
            final String verb = op.getString("op", "").toLowerCase();
            final String path = op.getString("path", "");
            final Object value = op.getValue("value");
            if (path.startsWith("members")) {
                // remove avec filtre : members[value eq "id"]
                final java.util.regex.Matcher m = java.util.regex.Pattern
                        .compile("members\\[value eq \"([^\"]+)\"\\]").matcher(path);
                final JsonArray ids = m.matches() ? new JsonArray().add(m.group(1))
                        : valuesOf(value instanceof JsonArray ? (JsonArray) value : new JsonArray());
                if ("add".equals(verb)) chain = chain.compose(x -> addMembers(id, ids));
                else if ("remove".equals(verb)) chain = chain.compose(x -> removeMembers(id, ids.isEmpty() ? null : ids));
                else if ("replace".equals(verb)) chain = chain.compose(x -> removeMembers(id, null)).compose(x -> addMembers(id, ids));
            } else if ("displayName".equals(path) || (path.isEmpty() && value instanceof JsonObject
                    && ((JsonObject) value).getString("displayName") != null)) {
                final String name = "displayName".equals(path) ? String.valueOf(firstIfArray(value))
                        : ((JsonObject) value).getString("displayName");
                chain = chain.compose(x -> exec("MATCH (g:Group {id:{id}}) SET g.name = {name}, g.modified = {now} " +
                        "WITH g OPTIONAL MATCH (g)<-[:DEPENDS]-(cpg:ProfileGroup) SET cpg.name = {name} + '-' + cpg.filter " +
                        "RETURN g.id AS id ", new JsonObject().put("id", id).put("name", name).put("now", DateTime.now().toString()),
                        "scim.group.patch.error"));
            }
        }
        return chain.map(x -> new JsonObject().put("id", id));
    }

    /** Adhésion (source SCIM) : ProfileGroup de la classe selon le profil, sinon le groupe lui-même. */
    private Future<JsonObject> addMembers(String groupId, JsonArray userIds) {
        if (userIds.isEmpty()) return Future.succeededFuture(new JsonObject());
        return exec("MATCH (g:Group {id:{id}}) MATCH (u:User) WHERE u.id IN {users} " +
                "OPTIONAL MATCH (g)<-[:DEPENDS]-(cpg:ProfileGroup) WHERE cpg.filter = coalesce(u.profileScim, head(u.profiles)) " +
                "FOREACH (_ IN CASE WHEN cpg IS NOT NULL THEN [1] ELSE [] END | MERGE (u)-[:IN {source:{source}}]->(cpg) ) " +
                "FOREACH (_ IN CASE WHEN cpg IS NULL THEN [1] ELSE [] END | MERGE (u)-[:IN {source:{source}}]->(g) ) " +
                "RETURN count(DISTINCT u) AS added ",
                new JsonObject().put("id", groupId).put("users", userIds).put("source", SOURCE), "scim.group.members.error");
    }

    /** Retrait des adhésions de source SCIM (null = toutes) ; les adhésions d'autres sources restent. */
    private Future<JsonObject> removeMembers(String groupId, JsonArray userIds) {
        final String filter = userIds == null ? "" : "AND u.id IN {users} ";
        return exec("MATCH (g:Group {id:{id}}) " +
                "OPTIONAL MATCH (u:User)-[r:IN {source:{source}}]->(g) WHERE true " + filter +
                "WITH g, collect(r) AS direct " +
                "OPTIONAL MATCH (u:User)-[r:IN {source:{source}}]->(:ProfileGroup)-[:DEPENDS]->(g) WHERE true " + filter +
                "WITH direct + collect(r) AS rels FOREACH (r IN rels | DELETE r) RETURN size(rels) AS removed ",
                new JsonObject().put("id", groupId).put("users", userIds == null ? new JsonArray() : userIds).put("source", SOURCE),
                "scim.group.members.error");
    }

    /** Entrée d'audit d'une écriture de l'API /scim/v2. */
    public static AuditEvent restAuditEvent(String op, JsonObject payload, String client, int status) {
        return AuditEvent.of("scim.rest." + op)
                .actor(null, client)
                .target(op.startsWith("group") ? "Group" : "User", payload.getString("id"))
                .source(AuditEvent.Source.SCIM)
                .application("feeder")
                .after(payload.getJsonObject("resource"))
                .status(status);
    }

    /** Entrée d'audit d'un événement SET reçu : action = URN de l'événement, cible = sub_id.uri. */
    public static AuditEvent auditEvent(JsonObject setPayload, int status) {
        final JsonObject events = setPayload.getJsonObject("events", new JsonObject());
        final String urn = events.isEmpty() ? "scim.event.empty" : events.fieldNames().iterator().next();
        final JsonObject subId = setPayload.getJsonObject("sub_id", new JsonObject());
        final AuditEvent event = AuditEvent.of(urn)
                .actor(null, setPayload.getString("iss"))
                .target("scim", subId.getString("uri"))
                .source(AuditEvent.Source.SCIM)
                .application("feeder")
                .status(status);
        if (setPayload.getString("jti") != null) event.correlationId(setPayload.getString("jti"));
        if (!events.isEmpty()) event.after(events.getJsonObject(urn, new JsonObject()).getJsonObject("data", new JsonObject()));
        return event;
    }

    /** Dernier segment de sub_id.uri (l'UUID SCIM de la ressource concernée). */
    private String subIdValue(JsonObject setPayload) {
        final JsonObject subId = setPayload.getJsonObject("sub_id");
        if (subId == null || subId.getString("uri") == null) return null;
        final String[] parts = subId.getString("uri").split("/");
        return parts.length == 0 ? null : parts[parts.length - 1];
    }

    private String resolveResourceType(JsonObject data, JsonObject setPayload) {
        final JsonObject meta = data.getJsonObject("meta");
        if (meta != null && meta.getString("resourceType") != null) {
            return meta.getString("resourceType");
        }
        final JsonObject subId = setPayload.getJsonObject("sub_id");
        if (subId != null && subId.getString("uri") != null) {
            final String[] parts = subId.getString("uri").split("/");
            if (parts.length >= 2) {
                String seg = parts[1];
                if (seg.endsWith("s")) seg = seg.substring(0, seg.length() - 1);
                return seg;
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- Organisation -> Structure

    public Future<JsonObject> upsertStructure(JsonObject scimOrg) {
        final JsonObject struct = new JsonObject()
                .put("externalId", scimEid(scimOrg.getString("id")))
                .put("name", scimOrg.getString("displayName"))
                .put("timetable", "");
        final String uai = scimOrg.getString("functionalId");
        if (uai != null) struct.put("UAI", uai);

        final String error = structureValidator.validate(struct);
        if (error != null) return Future.failedFuture(error);

        // Idempotent : MERGE par externalId ; ProfileGroups MERGE par externalId dérivé (re-livraison sûre).
        final String query =
                "MERGE (s:Structure {externalId:{externalId}}) " +
                "ON CREATE SET s += {props} " +
                "SET s.source = {source}, s.scimExternalId = {scimExternalId} " +
                "WITH s MATCH (p:Profile) " +
                "MERGE (s)<-[:DEPENDS]-(g:Group:ProfileGroup {externalId: s.externalId + '-' + p.name}) " +
                "  ON CREATE SET g.id = id(g)+'-'+timestamp(), g.name = s.name+'-'+p.name, " +
                "                g.displayNameSearchField = {groupSearchField}, g.filter = p.name, g.source = {source} " +
                "MERGE (g)-[:HAS_PROFILE]->(p) " +
                "RETURN DISTINCT s.id as id ";
        final JsonObject params = new JsonObject()
                .put("externalId", struct.getString("externalId"))
                .put("groupSearchField", Validator.sanitize(struct.getString("name")))
                .put("source", SOURCE)
                .put("scimExternalId", scimOrg.getString("externalId"))
                .put("props", struct);
        return exec(query, params, "scim.structure.create.error");
    }

    // ---------------------------------------------------------------------- User -> utilisateur

    public Future<JsonObject> upsertUser(JsonObject scimUser) {
        final String profile = resolveProfile(scimUser);
        final Validator validator = profileValidators.get(profile);
        if (validator == null) return Future.failedFuture("scim.invalid:unknown profile " + profile);

        final JsonObject name = scimUser.getJsonObject("name", new JsonObject());
        // id entcore = id SCIM : le même identifiant sert dans les deux sens (SET entrant, API /scim/v2).
        final JsonObject user = new JsonObject()
                .put("id", scimUser.getString("id"))
                .put("externalId", scimEid(scimUser.getString("id")))
                .put("firstName", name.getString("givenName"))
                .put("lastName", name.getString("familyName"));
        final String email = primaryEmail(scimUser);
        if (email != null) user.put("email", email);
        // birthDate (obligatoire pour un Élève ; ISO attendu par le schéma) : extension Worldline ou Open ENT.
        final String birthDate = scimUser.getJsonObject(EDU_USER_EXT, new JsonObject())
                .getString("birthDate", scimUser.getJsonObject(ODE_USER_EXT, new JsonObject()).getString("birthDate"));
        final String iso = toIsoDate(birthDate);
        if (iso != null) user.put("birthDate", iso);

        final String error = validator.validate(user); // génère login, displayName, activationCode…
        if (error != null) return Future.failedFuture("scim.invalid:" + error);

        // Idempotent : MERGE par externalId (re-livraison ne recrée pas ; login/activationCode conservés).
        final String query =
                "MERGE (u:User {externalId:{externalId}}) " +
                "ON CREATE SET u += {props} " +
                "SET u.source = {source}, u.profileScim = {profile}, u.profiles = [{profile}] " +
                "RETURN u.id as id, u.login as login ";
        final JsonObject params = new JsonObject()
                .put("externalId", user.getString("externalId"))
                .put("source", SOURCE)
                .put("profile", profile)
                .put("props", user);
        return restoreIfPreDeleted(user.getString("externalId"))
                .compose(x -> exec(query, params, "scim.user.create.error"));
    }

    /**
     * Profil explicite (extension Open ENT, attribut profile) ; à défaut, heuristique intérimaire
     * sur l'extension Worldline (à remplacer par leur attribut dès qu'il est connu).
     */
    private String resolveProfile(JsonObject scimUser) {
        final String explicit = scimUser.getJsonObject(ODE_USER_EXT, new JsonObject()).getString("profile");
        if (explicit != null) return explicit;
        final JsonObject edu = scimUser.getJsonObject(EDU_USER_EXT);
        if (edu != null && (edu.getValue("grade") != null || edu.getValue("track") != null)) {
            return "Student";
        }
        return "Teacher";
    }

    private String primaryEmail(JsonObject scimUser) {
        final JsonArray emails = scimUser.getJsonArray("emails");
        if (emails == null) return null;
        String first = null;
        for (int i = 0; i < emails.size(); i++) {
            final JsonObject e = emails.getJsonObject(i);
            if (first == null) first = e.getString("value");
            if (Boolean.TRUE.equals(e.getBoolean("primary"))) return e.getString("value");
        }
        return first;
    }

    // -------------------------------------------------------------- Group SCIM -> groupe fonctionnel

    /**
     * Groupe SCIM -> groupe fonctionnel entcore (l'utilisateur y adhère directement).
     * Raffinement ultérieur : tw.type == "class" -> vraie ressource Class (BELONGS + ProfileGroups).
     */
    public Future<JsonObject> upsertGroup(JsonObject scimGroup) {
        final String type = scimGroup.getJsonObject(TW_GROUP_EXT, new JsonObject()).getString("type",
                scimGroup.getJsonObject(ODE_GROUP_EXT, new JsonObject()).getString("type"));
        // Nœud :Group neutre porteur du type ; la nature (Class vs FunctionalGroup) est décidée lors du
        // rattachement à une organisation (RoleAssignment Group->Organization), qui donne la structure.
        final String query =
                "MERGE (g:Group {externalId:{externalId}}) " +
                "ON CREATE SET g.id = {id}, g.created = {now} " +
                "SET g.name = {name}, g.source = {source}, g.scimType = {scimType}, g.modified = {now} " +
                "RETURN g.id as id ";
        final JsonObject params = new JsonObject()
                .put("id", scimGroup.getString("id"))
                .put("externalId", scimEid(scimGroup.getString("id")))
                .put("name", scimGroup.getString("displayName"))
                .put("source", SOURCE)
                .put("now", DateTime.now().toString())
                .put("scimType", type);
        return exec(query, params, "scim.group.create.error");
    }

    // ---------------------------------------------------------------- RoleAssignment -> rattachements

    /** Selon (subject.type, scope.type) : User→Organization, User→Group, Group→Organization. */
    public Future<JsonObject> linkRoleAssignment(JsonObject scimRA) {
        final JsonObject subject = scimRA.getJsonObject("subject", new JsonObject());
        final JsonObject scope = scimRA.getJsonObject("scope", new JsonObject());
        final String st = subject.getString("type"), sc = scope.getString("type");
        final String raId = scimRA.getString("id"); // tracé sur la relation -> retrait au delete de la RA

        if ("User".equals(st) && "Organization".equals(sc)) {
            // Rattachement de l'utilisateur au ProfileGroup de son profil dans la structure.
            final String q =
                    "MATCH (u:User {externalId:{userEid}}) " +
                    "MATCH (s:Structure {externalId:{structEid}})<-[:DEPENDS]-(pg:ProfileGroup) " +
                    "WHERE pg.filter = u.profileScim " +
                    "MERGE (u)-[:IN {source:{source}, scimRaId:{raId}}]->(pg) " +
                    "SET u.structures = coalesce(u.structures, []) + s.externalId " +
                    "RETURN u.id as id ";
            return exec(q, new JsonObject()
                    .put("userEid", scimEid(subject.getString("value")))
                    .put("structEid", scimEid(scope.getString("value")))
                    .put("raId", raId)
                    .put("source", SOURCE), "scim.ra.user.org.error");
        }
        if ("Group".equals(st) && "Organization".equals(sc)) {
            return linkGroupToStructure("MATCH (g:Group {externalId:{groupEid}}) MATCH (s:Structure {externalId:{structEid}}) ",
                    new JsonObject()
                    .put("groupEid", scimEid(subject.getString("value")))
                    .put("structEid", scimEid(scope.getString("value"))));
        }
        if ("User".equals(st) && "Group".equals(sc)) {
            // Si le groupe est une Class : adhésion au ProfileGroup de la classe correspondant au profil
            // de l'utilisateur ; sinon adhésion directe au groupe fonctionnel.
            final String q =
                    "MATCH (u:User {externalId:{userEid}}) " +
                    "MATCH (g:Group {externalId:{groupEid}}) " +
                    "OPTIONAL MATCH (g)<-[:DEPENDS]-(cpg:ProfileGroup) WHERE cpg.filter = u.profileScim " +
                    "FOREACH (_ IN CASE WHEN cpg IS NOT NULL THEN [1] ELSE [] END | " +
                    "  MERGE (u)-[:IN {source:{source}, scimRaId:{raId}}]->(cpg) ) " +
                    "FOREACH (_ IN CASE WHEN cpg IS NULL THEN [1] ELSE [] END | " +
                    "  MERGE (u)-[:IN {source:{source}, scimRaId:{raId}}]->(g) ) " +
                    "RETURN u.id as id ";
            return exec(q, new JsonObject()
                    .put("userEid", scimEid(subject.getString("value")))
                    .put("groupEid", scimEid(scope.getString("value")))
                    .put("raId", raId)
                    .put("source", SOURCE), "scim.ra.user.group.error");
        }
        return Future.succeededFuture(new JsonObject().put("status", "ignored")
                .put("reason", "roleassignment.case.not.implemented:" + st + "->" + sc));
    }

    /**
     * Rattachement d'un groupe à une structure. scimType=='class' -> vraie Class (BELONGS +
     * ProfileGroups par profil, calqués sur ceux de la structure) ; sinon groupe fonctionnel.
     * @param match clauses MATCH liant g et s (par externalId pour le SET, par id pour l'API REST)
     */
    private Future<JsonObject> linkGroupToStructure(String match, JsonObject params) {
        final String q = match +
                "FOREACH (_ IN CASE WHEN coalesce(g.scimType,'') = 'class' THEN [1] ELSE [] END | " +
                "  SET g:Class MERGE (s)<-[:BELONGS]-(g) ) " +
                "FOREACH (_ IN CASE WHEN coalesce(g.scimType,'') <> 'class' THEN [1] ELSE [] END | " +
                "  SET g:FunctionalGroup MERGE (g)-[:DEPENDS]->(s) ) " +
                "WITH g, s " +
                "OPTIONAL MATCH (s)<-[:DEPENDS]-(spg:ProfileGroup)-[:HAS_PROFILE]->(p:Profile) WHERE g:Class " +
                "FOREACH (_ IN CASE WHEN spg IS NOT NULL THEN [1] ELSE [] END | " +
                "  MERGE (g)<-[:DEPENDS]-(cpg:Group:ProfileGroup {externalId: g.externalId + '-' + p.name}) " +
                "    ON CREATE SET cpg.id = id(cpg)+'-'+timestamp(), cpg.name = g.name+'-'+p.name, " +
                "                  cpg.filter = p.name, cpg.source = {source} " +
                "  MERGE (cpg)-[:DEPENDS]->(spg) ) " +
                "RETURN g.id as id ";
        return exec(q, params.copy().put("source", SOURCE), "scim.ra.group.org.error");
    }

    // ------------------------------------------------------------------------ cycle de vie

    /** Suppression d'une ressource (source=SCIM). Cascade des ProfileGroups/Classes pour une structure. */
    public Future<JsonObject> deleteResource(String resourceType, String scimId) {
        if (scimId == null) return Future.failedFuture("scim.delete.no.id");
        final String eid = scimEid(scimId);
        final String q;
        if ("User".equals(resourceType)) {
            return preDeleteUser(eid);
        } else if ("Group".equals(resourceType)) {
            q = "OPTIONAL MATCH (g:Group {externalId:{eid}}) " +
                "OPTIONAL MATCH (g)<-[:DEPENDS]-(cpg:ProfileGroup) " +
                "DETACH DELETE cpg, g RETURN 1 AS ok ";
        } else if ("Organization".equals(resourceType)) {
            q = "OPTIONAL MATCH (s:Structure {externalId:{eid}}) " +
                "OPTIONAL MATCH (s)<-[:DEPENDS]-(pg:ProfileGroup) " +
                "OPTIONAL MATCH (s)<-[:BELONGS]-(c:Class) OPTIONAL MATCH (c)<-[:DEPENDS]-(cpg:ProfileGroup) " +
                "DETACH DELETE cpg, c, pg, s RETURN 1 AS ok ";
        } else if ("RoleAssignment".equals(resourceType)) {
            // Retrait de l'appartenance : la relation d'adhésion porte l'id de la RoleAssignment (scimRaId).
            // (scimId est ici l'id brut de la RA, pas un externalId.)
            return exec("OPTIONAL MATCH ()-[r:IN {scimRaId:{raId}}]->() DELETE r RETURN 1 AS ok ",
                    new JsonObject().put("raId", scimId), "scim.ra.delete.error");
        } else {
            return Future.succeededFuture(new JsonObject().put("status", "ignored")
                    .put("reason", "delete.noop.for:" + resourceType));
        }
        return exec(q, new JsonObject().put("eid", eid), "scim.delete.error");
    }

    /**
     * Suppression d'un User : même cycle que les autres sources (sauvegarde des relations, passage dans
     * le DeleteGroup avec deleteDate). La purge définitive est faite par la tâche de suppression du
     * feeder ; d'ici là, un nouveau create SCIM restaure le compte (voir {@link #restoreIfPreDeleted}).
     */
    private Future<JsonObject> preDeleteUser(String eid) {
        return findUserId(eid).compose(userId -> {
            if (userId == null) return Future.succeededFuture(new JsonObject().put("status", "ignored")
                    .put("reason", "user.not.found"));
            return preDeleteUserById(userId);
        });
    }

    private Future<JsonObject> preDeleteUserById(String userId) {
        return runTransaction(tx -> {
            User.backupRelationship(userId, false, tx);
            User.preDelete(userId, tx);
        }, "scim.delete.error");
    }

    /** Restaure un User mis en attente de suppression (deleteDate) qui est de nouveau provisionné. */
    private Future<JsonObject> restoreIfPreDeleted(String eid) {
        return exec("OPTIONAL MATCH (u:User {externalId:{eid}}) WHERE HAS(u.deleteDate) RETURN u.id AS id ",
                new JsonObject().put("eid", eid), "scim.restore.error")
                .compose(r -> {
                    final String userId = firstId(r);
                    if (userId == null) return Future.succeededFuture(r);
                    return restorePreDeletedById(userId);
                });
    }

    private Future<JsonObject> restorePreDeletedById(String userId) {
        return runTransaction(tx -> {
            User.restorePreDeleted(userId, tx);
            // Sans rattachement au moment du delete, aucun Backup n'a été créé et
            // restorePreDeleted ne sort pas le compte du DeleteGroup : on le fait ici.
            tx.add("MATCH (u:User {id:{userId}})-[r:IN]->(:DeleteGroup) " +
                   "REMOVE u.deleteDate, u.disappearanceDate DELETE r",
                   new JsonObject().put("userId", userId));
        }, "scim.restore.error");
    }

    private Future<String> findUserId(String eid) {
        return exec("OPTIONAL MATCH (u:User {externalId:{eid}}) RETURN u.id AS id ",
                new JsonObject().put("eid", eid), "scim.user.find.error").map(ScimFeeder::firstId);
    }

    private static String firstId(JsonObject execResult) {
        final JsonArray rows = execResult.getJsonArray("result", new JsonArray());
        return rows.isEmpty() ? null : rows.getJsonObject(0).getString("id");
    }

    /** Activation / désactivation d'un compte (User) -> propriété blocked. */
    public Future<JsonObject> setUserBlocked(String scimId, boolean blocked) {
        if (scimId == null) return Future.failedFuture("scim.activate.no.id");
        return exec("OPTIONAL MATCH (u:User {externalId:{eid}}) SET u.blocked = {blocked} RETURN 1 AS ok ",
                new JsonObject().put("eid", scimEid(scimId)).put("blocked", blocked), "scim.activate.error");
    }

    /**
     * Mise à jour d'un User (SCIM PatchOp) : replace/add sur displayName, name.givenName,
     * name.familyName, emails, externalId, active. Accepte aussi la forme sans path dont la valeur
     * est un objet (Entra ID, Okta : {"op":"replace","value":{"active":false}}).
     */
    public Future<JsonObject> applyUserPatch(String scimId, JsonObject data) {
        if (scimId == null) return Future.failedFuture("scim.patch.no.id");
        return exec("OPTIONAL MATCH (u:User {externalId:{eid}}) SET u += {props} RETURN 1 AS ok ",
                new JsonObject().put("eid", scimEid(scimId)).put("props", userPatchProps(data)), "scim.patch.error");
    }

    private static JsonObject userPatchProps(JsonObject data) {
        final JsonArray ops = data.getJsonArray("Operations", new JsonArray());
        final JsonObject props = new JsonObject();
        for (int i = 0; i < ops.size(); i++) {
            final JsonObject op = ops.getJsonObject(i);
            final String verb = op.getString("op", "");
            if (!"replace".equalsIgnoreCase(verb) && !"add".equalsIgnoreCase(verb)) continue;
            final String path = op.getString("path", "");
            if (path.isEmpty() && op.getValue("value") instanceof JsonObject) {
                final JsonObject values = op.getJsonObject("value");
                for (String attr : values.fieldNames()) {
                    if ("name".equals(attr) && values.getValue(attr) instanceof JsonObject) {
                        final JsonObject name = values.getJsonObject(attr);
                        for (String sub : name.fieldNames()) patchUserAttribute(props, "name." + sub, name.getValue(sub));
                    } else {
                        patchUserAttribute(props, attr, values.getValue(attr));
                    }
                }
            } else {
                patchUserAttribute(props, path, op.getValue("value"));
            }
        }
        props.put("modified", DateTime.now().toString());
        return props;
    }

    private static void patchUserAttribute(JsonObject props, String path, Object rawValue) {
        Object value = firstIfArray(rawValue);
        if (value instanceof JsonObject && ((JsonObject) value).containsKey("value")) {
            value = ((JsonObject) value).getValue("value"); // emails : [{value, primary}]
        }
        switch (path) {
            case "displayName": props.put("displayName", String.valueOf(value)); break;
            case "name.givenName": props.put("firstName", String.valueOf(value)); break;
            case "name.familyName": props.put("lastName", String.valueOf(value)); break;
            case "emails": case "email": case "emails[type eq \"work\"].value":
                props.put("email", String.valueOf(value)); break;
            case "externalId": props.put("scimExternalId", String.valueOf(value)); break;
            case "active": props.put("blocked", !truthy(value)); break;
            default: break; // "members", "groups"… : l'appartenance passe par RoleAssignment ou PATCH Group.
        }
    }

    private static Object firstIfArray(Object v) {
        return (v instanceof JsonArray && ((JsonArray) v).size() > 0) ? ((JsonArray) v).getValue(0) : v;
    }
    private static boolean truthy(Object v) {
        return Boolean.TRUE.equals(v) || "true".equalsIgnoreCase(String.valueOf(v));
    }

    /** Normalise une date en ISO yyyy-MM-dd (accepte dd/MM/yyyy en entrée, laisse ISO tel quel). */
    static String toIsoDate(String d) {
        if (d == null) return null;
        if (d.matches("^\\d{4}-\\d{2}-\\d{2}$")) return d;
        final java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(\\d{2})/(\\d{2})/(\\d{4})$").matcher(d);
        return m.matches() ? m.group(3) + "-" + m.group(2) + "-" + m.group(1) : null;
    }

    // ------------------------------------------------------------------------------- utilitaire

    /** Exécute dans une transaction feeder les requêtes ajoutées par les helpers de cycle de vie (User.*). */
    private Future<JsonObject> runTransaction(Consumer<TransactionHelper> statements, String errKey) {
        final Promise<JsonObject> promise = Promise.promise();
        try {
            final TransactionHelper tx = TransactionManager.getTransaction();
            statements.accept(tx);
            tx.commit(event -> {
                if ("ok".equals(event.body().getString("status"))) {
                    promise.complete(new JsonObject().put("status", "ok"));
                } else {
                    promise.fail(event.body().getString("message", errKey));
                }
            });
        } catch (TransactionException e) {
            promise.fail(errKey + ":" + e.getMessage());
        }
        return promise.future();
    }

    private Future<JsonObject> exec(String query, JsonObject params, String errKey) {
        final StatementsBuilder statementsBuilder = new StatementsBuilder();
        statementsBuilder.add(query, params);
        final Promise<JsonObject> promise = Promise.promise();
        neo4j.executeTransaction(statementsBuilder.build(), null, true, event -> {
            final JsonArray results = event.body().getJsonArray("results");
            if ("ok".equals(event.body().getString("status")) && results != null && results.size() > 0) {
                promise.complete(event.body().put("result", results.getJsonArray(0)));
            } else {
                promise.fail(event.body().getString("message", errKey));
            }
        });
        return promise.future();
    }
}
