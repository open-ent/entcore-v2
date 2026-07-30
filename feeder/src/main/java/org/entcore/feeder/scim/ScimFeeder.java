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
 * Tranches couvertes : Organisation -> Structure ; User -> utilisateur (profil via heuristique) ;
 * RoleAssignment(User -> Organization) -> rattachement à la structure (ProfileGroup du profil).
 *
 * Décisions intérimaires (à confirmer avec Worldline) :
 *  - membership = via RoleAssignment (on ignore le patch:full "members" redondant) ;
 *  - profil = heuristique : extension éducation grade/track présents -> Student, sinon Teacher.
 * TODO tranches suivantes : Group/Classe, patch:full, activate/deactivate, delete + cascade,
 *      idempotence (CREATE -> MERGE), mapping profil exact (attribut Worldline), birthDate (Student).
 */
package org.entcore.feeder.scim;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.entcore.common.neo4j.Neo4j;
import org.entcore.feeder.utils.StatementsBuilder;
import org.entcore.feeder.utils.Validator;

import java.util.HashMap;
import java.util.Map;

public class ScimFeeder {

    /** Étiquette de source posée sur tous les nœuds/relations créés par ce connecteur. */
    public static final String SOURCE = "SCIM";

    private static final String EDU_USER_EXT = "urn:twid:scim:schemas:extension:education:2.0:User";

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
        if (validator == null) return Future.failedFuture("scim.user.profile.unknown:" + profile);

        final JsonObject name = scimUser.getJsonObject("name", new JsonObject());
        final JsonObject user = new JsonObject()
                .put("externalId", scimEid(scimUser.getString("id")))
                .put("firstName", name.getString("givenName"))
                .put("lastName", name.getString("familyName"));
        final String email = primaryEmail(scimUser);
        if (email != null) user.put("email", email);
        // Extension éducation : birthDate (obligatoire pour un Élève ; ISO attendu par le schéma).
        final JsonObject edu = scimUser.getJsonObject(EDU_USER_EXT);
        if (edu != null) {
            final String iso = toIsoDate(edu.getString("birthDate"));
            if (iso != null) user.put("birthDate", iso);
        }

        final String error = validator.validate(user); // génère id, login, displayName, activationCode…
        if (error != null) return Future.failedFuture(error);

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
        return exec(query, params, "scim.user.create.error");
    }

    /** Heuristique intérimaire de profil (à remplacer par l'attribut Worldline dès qu'il est connu). */
    private String resolveProfile(JsonObject scimUser) {
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
        final JsonObject tw = scimGroup.getJsonObject("urn:twid:scim:schemas:extension:tw:2.0:Group", new JsonObject());
        // Nœud :Group neutre porteur du type ; la nature (Class vs FunctionalGroup) est décidée lors du
        // rattachement à une organisation (RoleAssignment Group->Organization), qui donne la structure.
        final String query =
                "MERGE (g:Group {externalId:{externalId}}) " +
                "ON CREATE SET g.id = id(g)+'-'+timestamp() " +
                "SET g.name = {name}, g.source = {source}, g.scimType = {scimType} " +
                "RETURN g.id as id ";
        final JsonObject params = new JsonObject()
                .put("externalId", scimEid(scimGroup.getString("id")))
                .put("name", scimGroup.getString("displayName"))
                .put("source", SOURCE)
                .put("scimType", tw.getString("type"));
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
            // Rattachement du groupe à la structure. scimType=='class' -> vraie Class (BELONGS +
            // ProfileGroups par profil, calqués sur ceux de la structure) ; sinon groupe fonctionnel.
            final String q =
                    "MATCH (g:Group {externalId:{groupEid}}) " +
                    "MATCH (s:Structure {externalId:{structEid}}) " +
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
            return exec(q, new JsonObject()
                    .put("groupEid", scimEid(subject.getString("value")))
                    .put("structEid", scimEid(scope.getString("value")))
                    .put("source", SOURCE), "scim.ra.group.org.error");
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

    // ------------------------------------------------------------------------ cycle de vie

    /** Suppression d'une ressource (source=SCIM). Cascade des ProfileGroups/Classes pour une structure. */
    public Future<JsonObject> deleteResource(String resourceType, String scimId) {
        if (scimId == null) return Future.failedFuture("scim.delete.no.id");
        final String eid = scimEid(scimId);
        final String q;
        if ("User".equals(resourceType)) {
            q = "OPTIONAL MATCH (u:User {externalId:{eid}}) DETACH DELETE u RETURN 1 AS ok ";
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

    /** Activation / désactivation d'un compte (User) -> propriété blocked. */
    public Future<JsonObject> setUserBlocked(String scimId, boolean blocked) {
        if (scimId == null) return Future.failedFuture("scim.activate.no.id");
        return exec("OPTIONAL MATCH (u:User {externalId:{eid}}) SET u.blocked = {blocked} RETURN 1 AS ok ",
                new JsonObject().put("eid", scimEid(scimId)).put("blocked", blocked), "scim.activate.error");
    }

    /** Mise à jour d'un User (SCIM PatchOp). Gère replace/add sur displayName, email, active. */
    public Future<JsonObject> applyUserPatch(String scimId, JsonObject data) {
        if (scimId == null) return Future.failedFuture("scim.patch.no.id");
        final JsonArray ops = data.getJsonArray("Operations", new JsonArray());
        final JsonObject props = new JsonObject();
        for (int i = 0; i < ops.size(); i++) {
            final JsonObject op = ops.getJsonObject(i);
            final String verb = op.getString("op", "");
            if (!"replace".equalsIgnoreCase(verb) && !"add".equalsIgnoreCase(verb)) continue;
            final String path = op.getString("path", "");
            final Object value = firstIfArray(op.getValue("value"));
            if ("displayName".equals(path)) props.put("displayName", String.valueOf(value));
            else if ("emails".equals(path) || "email".equals(path)) props.put("email", String.valueOf(value));
            else if ("active".equals(path)) props.put("blocked", !truthy(value));
            // "members" et autres chemins : ignorés (l'appartenance est gérée via RoleAssignment).
        }
        return exec("OPTIONAL MATCH (u:User {externalId:{eid}}) SET u += {props} RETURN 1 AS ok ",
                new JsonObject().put("eid", scimEid(scimId)).put("props", props), "scim.patch.error");
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
