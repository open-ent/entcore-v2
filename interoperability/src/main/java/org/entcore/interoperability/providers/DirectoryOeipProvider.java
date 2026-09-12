package org.entcore.interoperability.providers;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.entcore.common.neo4j.Neo4j;
import org.entcore.interoperability.OeipFormat;
import org.entcore.interoperability.spi.OeipCapability;
import org.entcore.interoperability.spi.OeipCoreExport;
import org.entcore.interoperability.spi.OeipServiceMapper;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.List;

/**
 * Décrit l'annuaire dans le modèle commun : établissements, personnes, groupes, adhésions.
 *
 * C'est le seul service dont la description ne peut pas être dérivée d'un export d'archive :
 * l'annuaire n'en produit aucun. Les données sont donc lues directement dans le graphe.
 *
 * Le périmètre est celui de la personne exportée : elle-même, les établissements auxquels elle
 * est rattachée, les groupes dont elle est membre, et les adhésions correspondantes. C'est ce
 * qu'il faut — et cela suffit — pour qu'une autre plateforme sache où la replacer.
 */
public class DirectoryOeipProvider implements OeipServiceMapper {

    public static final String SERVICE_ID = "directory";

    /** Syntaxe de paramètre du graphe : accolades, pas dollar. */
    private static final String Q_PERSON =
            "MATCH (u:User {id:{id}}) " +
            "RETURN u.id AS id, u.externalId AS externalId, u.login AS login, " +
            "u.firstName AS firstName, u.lastName AS lastName, u.displayName AS displayName, " +
            "u.birthDate AS birthDate, u.email AS email, head(u.profiles) AS profile, " +
            "u.deleteDate AS deleteDate";

    private static final String Q_ORGS =
            "MATCH (u:User {id:{id}})-[:IN]->(:Group)-[:DEPENDS]->(s:Structure) " +
            "RETURN DISTINCT s.id AS id, s.UAI AS uai, s.name AS name, s.externalId AS externalId";

    private static final String Q_GROUPS =
            "MATCH (u:User {id:{id}})-[:IN]->(g:Group) " +
            "OPTIONAL MATCH (g)-[:DEPENDS]->(s:Structure) " +
            "OPTIONAL MATCH (g)-[:DEPENDS]->(:Class)-[:BELONGS]->(cs:Structure) " +
            "RETURN DISTINCT g.id AS id, g.name AS name, g.externalId AS externalId, " +
            "labels(g) AS labels, coalesce(s.id, cs.id) AS orgId";

    private final Neo4j neo4j;
    private final String sourceSystem;
    private final boolean pseudonymize;

    public DirectoryOeipProvider(Neo4j neo4j, String sourceSystem, boolean pseudonymize) {
        this.neo4j = neo4j;
        this.sourceSystem = sourceSystem;
        this.pseudonymize = pseudonymize;
    }

    @Override
    public String serviceId() {
        return SERVICE_ID;
    }

    @Override
    public boolean supportsCore() {
        return true;
    }

    @Override
    public OeipCapability capability() {
        return new OeipCapability(SERVICE_ID, "Annuaire", "Directory", null,
                true, false, false, false,
                OeipFormat.FIDELITY_PARTIAL,
                "Les fonctions et rattachements administratifs ne sont pas modélisés en 1.0. "
                + "L'import ne crée jamais de compte : il rattache des données à des comptes existants.");
    }

    @Override
    public Future<OeipCoreExport> exportCore(final String scopeUserId, String locale) {
        final JsonObject params = new JsonObject().put("id", scopeUserId);
        return query(Q_PERSON, params)
                .compose(persons -> query(Q_ORGS, params)
                .compose(orgs -> query(Q_GROUPS, params)
                .map(groups -> assemble(scopeUserId, persons, orgs, groups))));
    }

    /* visible pour les tests */ OeipCoreExport assemble(String scopeUserId, JsonArray persons, JsonArray orgs, JsonArray groups) {
        final OeipCoreExport out = new OeipCoreExport();

        JsonArray orgItems = new JsonArray();
        for (int i = 0; i < orgs.size(); i++) {
            JsonObject row = orgs.getJsonObject(i);
            String gid = OeipUrn.org(sourceSystem, row.getString("id"));
            JsonObject org = new JsonObject()
                    .put("globalId", gid)
                    .put("sourceSystem", sourceSystem)
                    .put("name", nonEmpty(row.getString("name"), "Établissement"))
                    .put("type", "School");
            putIfPresent(org, "sourceId", pseudonymize ? null : row.getString("id"));
            putIfPresent(org, "externalId", row.getString("externalId"));
            String uai = row.getString("uai");
            if (isUai(uai)) {
                org.put("uai", uai);
                // L'ancre nationale est ce qui permet à une plateforme tierce d'apparier
                // l'établissement sans rien connaître de nos identifiants.
                out.alias(new JsonObject().put("globalId", gid)
                        .put("sameAs", new JsonArray().add(OeipUrn.uaiAlias(uai))));
            }
            orgItems.add(org);
            out.identifier(entry(gid, "org", row.getString("id"),
                    "directory/organizations.json#/items/" + i));
        }

        JsonArray personItems = new JsonArray();
        JsonArray membershipItems = new JsonArray();
        String personGid = null;

        for (int i = 0; i < persons.size(); i++) {
            JsonObject row = persons.getJsonObject(i);
            personGid = OeipUrn.person(sourceSystem, row.getString("id"));
            String profile = normalizeProfile(row.getString("profile"));

            JsonObject person = new JsonObject()
                    .put("globalId", personGid)
                    .put("sourceSystem", sourceSystem)
                    .put("profile", profile)
                    .put("deleted", row.getValue("deleteDate") != null)
                    .put("sensitivity", sensitivity(profile, row.getString("birthDate")));

            putIfPresent(person, "sourceId", pseudonymize ? null : row.getString("id"));
            putIfPresent(person, "externalId", row.getString("externalId"));
            if (!pseudonymize) {
                putIfPresent(person, "login", row.getString("login"));
                putIfPresent(person, "birthDate", isDate(row.getString("birthDate"))
                        ? row.getString("birthDate") : null);
                String email = row.getString("email");
                if (email != null && email.indexOf('@') > 0) {
                    person.put("emails", new JsonArray().add(email));
                }
            }
            putIfPresent(person, "firstName", row.getString("firstName"));
            putIfPresent(person, "lastName", row.getString("lastName"));
            putIfPresent(person, "displayName", row.getString("displayName"));

            JsonArray orgRefs = new JsonArray();
            for (int j = 0; j < orgItems.size(); j++) {
                orgRefs.add(orgItems.getJsonObject(j).getString("globalId"));
            }
            if (orgRefs.size() > 0) {
                person.put("orgRefs", orgRefs);
            }
            personItems.add(person);
            out.identifier(entry(personGid, "person", row.getString("id"),
                    "directory/persons.json#/items/" + i));

            String externalId = row.getString("externalId");
            if (!pseudonymize && externalId != null && !externalId.isEmpty()) {
                out.alias(new JsonObject().put("globalId", personGid)
                        .put("sameAs", new JsonArray().add(OeipUrn.aafPersonAlias(externalId))));
            }

            // Adhésion à l'établissement : c'est elle qui porte le rôle, pas un simple tableau
            // d'identifiants sur la personne.
            for (int j = 0; j < orgItems.size(); j++) {
                addMembership(out, membershipItems, personGid, row.getString("id"),
                        orgItems.getJsonObject(j).getString("globalId"),
                        orgRefsSourceId(orgs, j), roleForProfile(profile));
            }
        }

        JsonArray groupItems = new JsonArray();
        for (int i = 0; i < groups.size(); i++) {
            JsonObject row = groups.getJsonObject(i);
            String gid = OeipUrn.group(sourceSystem, row.getString("id"));
            JsonObject group = new JsonObject()
                    .put("globalId", gid)
                    .put("sourceSystem", sourceSystem)
                    .put("name", nonEmpty(row.getString("name"), "Groupe"))
                    .put("groupType", groupType(row.getJsonArray("labels")));
            putIfPresent(group, "sourceId", pseudonymize ? null : row.getString("id"));
            putIfPresent(group, "externalId", row.getString("externalId"));
            String orgId = row.getString("orgId");
            if (orgId != null) {
                group.put("orgRef", OeipUrn.org(sourceSystem, orgId));
            }
            groupItems.add(group);
            out.identifier(entry(gid, "group", row.getString("id"),
                    "directory/groups.json#/items/" + i));

            if (personGid != null) {
                addMembership(out, membershipItems, personGid,
                        persons.getJsonObject(0).getString("id"), gid, row.getString("id"), "member");
                out.relation(new JsonObject().put("type", "memberOf")
                        .put("fromRef", personGid).put("toRef", gid));
            }
        }

        out.document("directory/organizations.json", envelope("organizations", orgItems));
        out.document("directory/persons.json", envelope("persons", personItems));
        out.document("directory/groups.json", envelope("groups", groupItems));
        out.document("directory/memberships.json", envelope("memberships", membershipItems));

        out.count("organizations", orgItems.size())
           .count("persons", personItems.size())
           .count("groups", groupItems.size())
           .count("memberships", membershipItems.size());

        OeipCapability c = capability();
        out.fidelity(c.getFidelity(), c.getNotice());
        return out;
    }

    private void addMembership(OeipCoreExport out, JsonArray items, String personGid,
                               String personSourceId, String containerGid, String containerSourceId,
                               String role) {
        String local = OeipUrn.membershipLocalPart(personSourceId, containerSourceId);
        String gid = OeipUrn.membership(sourceSystem, local);
        items.add(new JsonObject()
                .put("globalId", gid)
                .put("sourceSystem", sourceSystem)
                .put("personRef", personGid)
                .put("containerRef", containerGid)
                .put("role", role));
        out.identifier(entry(gid, "membership", null,
                "directory/memberships.json#/items/" + (items.size() - 1)));
    }

    private JsonObject envelope(String dataset, JsonArray items) {
        return new JsonObject()
                .put("oeipVersion", OeipFormat.VERSION)
                .put("sourceSystem", sourceSystem)
                .put("dataset", dataset)
                .put("items", items);
    }

    private JsonObject entry(String globalId, String kind, String sourceId, String href) {
        JsonObject e = new JsonObject()
                .put("globalId", globalId)
                .put("kind", kind)
                .put("level", OeipFormat.LEVEL_CORE)
                .put("sourceSystem", sourceSystem)
                .put("href", href);
        if (sourceId != null && !pseudonymize) {
            e.put("sourceId", sourceId);
        }
        return e;
    }

    // ------------------------------------------------------------------ correspondances

    private static final List<String> PROFILES =
            Arrays.asList("Student", "Teacher", "Relative", "Personnel", "Guest");

    static String normalizeProfile(String raw) {
        if (raw != null && PROFILES.contains(raw)) {
            return raw;
        }
        // Un profil inconnu ne doit pas faire échouer tout l'export : « Guest » est le repli
        // le moins engageant, et la fidélité déclarée reste « partial ».
        return "Guest";
    }

    static String roleForProfile(String profile) {
        if ("Teacher".equals(profile)) return "teacher";
        if ("Student".equals(profile)) return "student";
        if ("Relative".equals(profile)) return "relative";
        if ("Guest".equals(profile)) return "guest";
        return "member";
    }

    static String groupType(JsonArray labels) {
        if (labels == null) {
            return "ManualGroup";
        }
        List<Object> l = labels.getList();
        if (l.contains("ProfileGroup")) return "ProfileGroup";
        if (l.contains("ClassGroup")) return "ClassGroup";
        if (l.contains("CommunityGroup")) return "CommunityGroup";
        if (l.contains("FunctionGroup") || l.contains("FuncGroup") || l.contains("DirectionGroup")) {
            return "FunctionalGroup";
        }
        return "ManualGroup";
    }

    /**
     * Le signalement d'une personne mineure engage le destinataire : dans le doute, on signale.
     * Un élève sans date de naissance est donc traité comme mineur.
     */
    static String sensitivity(String profile, String birthDate) {
        if (isDate(birthDate)) {
            try {
                if (LocalDate.parse(birthDate).isAfter(LocalDate.now().minusYears(18))) {
                    return "minor";
                }
                return "standard";
            } catch (DateTimeParseException ignored) {
                // date illisible : on retombe sur la règle par profil
            }
        }
        return "Student".equals(profile) ? "minor" : "standard";
    }

    static boolean isDate(String s) {
        return s != null && s.matches("^[0-9]{4}-[0-9]{2}-[0-9]{2}$");
    }

    static boolean isUai(String s) {
        return s != null && s.matches("^[0-9]{7}[A-Z]$");
    }

    private static String nonEmpty(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value;
    }

    private static void putIfPresent(JsonObject target, String key, String value) {
        if (value != null && !value.isEmpty()) {
            target.put(key, value);
        }
    }

    private static String orgRefsSourceId(JsonArray orgs, int index) {
        return orgs.getJsonObject(index).getString("id");
    }

    private Future<JsonArray> query(String cypher, JsonObject params) {
        final Promise<JsonArray> promise = Promise.promise();
        neo4j.execute(cypher, params, res -> {
            JsonObject body = res.body();
            if ("ok".equals(body.getString("status"))) {
                promise.complete(body.getJsonArray("result", new JsonArray()));
            } else {
                promise.fail("[OEIP] annuaire : " + body.getString("message"));
            }
        });
        return promise.future();
    }
}
