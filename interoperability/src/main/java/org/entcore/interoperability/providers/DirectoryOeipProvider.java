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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    /**
     * Appariement par identifiant d'alimentation, puis par login.
     *
     * L'identifiant d'alimentation est privilégié : il est stable entre plateformes alimentées
     * par la même source, alors qu'un login peut être réattribué ou différer d'une convention à
     * l'autre.
     */
    private static final String Q_MATCH_EXTERNAL =
            "MATCH (u:User) WHERE u.externalId IN {values} " +
            "RETURN u.externalId AS key, u.id AS id, u.login AS login";

    private static final String Q_MATCH_LOGIN =
            "MATCH (u:User) WHERE u.login IN {values} " +
            "RETURN u.login AS key, u.id AS id, u.login AS login";

    private static final String Q_MATCH_UAI =
            "MATCH (s:Structure) WHERE s.UAI IN {values} " +
            "RETURN s.UAI AS key, s.id AS id, s.name AS name";

    /**
     * Marque un compte existant d'un alias vers son identité d'échange.
     *
     * C'est la SEULE écriture que cet importeur s'autorise sur l'annuaire. Créer un compte
     * court-circuiterait l'alimentation et corromprait le graphe ; la reprise des contenus, elle,
     * se rattache à des comptes déjà présents.
     */
    private static final String Q_TAG_ALIAS =
            "MATCH (u:User {id:{id}}) SET u._oeipGlobalId = {globalId} RETURN u.id AS id";

    private final Neo4j neo4j;
    private final String sourceSystem;

    public DirectoryOeipProvider(Neo4j neo4j, String sourceSystem) {
        this.neo4j = neo4j;
        this.sourceSystem = sourceSystem;
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
    public Future<OeipCoreExport> exportCore(final org.entcore.interoperability.spi.OeipExportContext context) {
        final String scopeUserId = context.getScopeUserId();
        final boolean pseudonymize = context.isPseudonymize();
        final JsonObject params = new JsonObject().put("id", scopeUserId);
        return query(Q_PERSON, params)
                .compose(persons -> query(Q_ORGS, params)
                .compose(orgs -> query(Q_GROUPS, params)
                .map(groups -> assemble(scopeUserId, persons, orgs, groups, pseudonymize))));
    }

    /* visible pour les tests */ OeipCoreExport assemble(String scopeUserId, JsonArray persons,
                                                        JsonArray orgs, JsonArray groups,
                                                        boolean pseudonymize) {
        final OeipCoreExport out = new OeipCoreExport();

        JsonArray orgItems = new JsonArray();
        for (int i = 0; i < orgs.size(); i++) {
            JsonObject row = orgs.getJsonObject(i);
            String gid = OeipUrn.of("org", sourceSystem,
                    OeipUrn.localPart(row.getString("id"), sourceSystem, pseudonymize));
            JsonObject org = new JsonObject()
                    .put("globalId", gid)
                    .put("sourceSystem", sourceSystem)
                    .put("name", nonEmpty(row.getString("name"), "Établissement"))
                    .put("type", "School");
            if (!pseudonymize) {
                putIfPresent(org, "sourceId", row.getString("id"));
                putIfPresent(org, "externalId", row.getString("externalId"));
            }
            String uai = row.getString("uai");
            if (isUai(uai) && !pseudonymize) {
                org.put("uai", uai);
                // L'ancre nationale est ce qui permet à une plateforme tierce d'apparier
                // l'établissement sans rien connaître de nos identifiants.
                out.alias(new JsonObject().put("globalId", gid)
                        .put("sameAs", new JsonArray().add(OeipUrn.uaiAlias(uai))));
            }
            orgItems.add(org);
            out.identifier(entry(gid, "org", pseudonymize ? null : row.getString("id"),
                    "directory/organizations.json#/items/" + i));
        }

        JsonArray personItems = new JsonArray();
        JsonArray membershipItems = new JsonArray();
        String personGid = null;

        for (int i = 0; i < persons.size(); i++) {
            JsonObject row = persons.getJsonObject(i);
            personGid = OeipUrn.of("person", sourceSystem,
                    OeipUrn.localPart(row.getString("id"), sourceSystem, pseudonymize));
            String profile = normalizeProfile(row.getString("profile"));

            JsonObject person = new JsonObject()
                    .put("globalId", personGid)
                    .put("sourceSystem", sourceSystem)
                    .put("profile", profile)
                    .put("deleted", row.getValue("deleteDate") != null)
                    .put("sensitivity", sensitivity(profile, row.getString("birthDate")));

            if (!pseudonymize) {
                putIfPresent(person, "sourceId", row.getString("id"));
                // L'identifiant d'alimentation permet de retrouver la personne par simple
                // rapprochement avec un extrait d'annuaire : il est identifiant en lui-même.
                putIfPresent(person, "externalId", row.getString("externalId"));
                putIfPresent(person, "login", row.getString("login"));
                putIfPresent(person, "birthDate", isDate(row.getString("birthDate"))
                        ? row.getString("birthDate") : null);
                String email = row.getString("email");
                if (email != null && email.indexOf('@') > 0) {
                    person.put("emails", new JsonArray().add(email));
                }
                // Un nom est la donnée la plus directement identifiante qui soit : le conserver
                // viderait la pseudonymisation de tout sens.
                putIfPresent(person, "firstName", row.getString("firstName"));
                putIfPresent(person, "lastName", row.getString("lastName"));
                putIfPresent(person, "displayName", row.getString("displayName"));
            }

            JsonArray orgRefs = new JsonArray();
            for (int j = 0; j < orgItems.size(); j++) {
                orgRefs.add(orgItems.getJsonObject(j).getString("globalId"));
            }
            if (orgRefs.size() > 0) {
                person.put("orgRefs", orgRefs);
            }
            personItems.add(person);
            out.identifier(entry(personGid, "person", pseudonymize ? null : row.getString("id"),
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
                        orgRefsSourceId(orgs, j), roleForProfile(profile), pseudonymize);
            }
        }

        JsonArray groupItems = new JsonArray();
        for (int i = 0; i < groups.size(); i++) {
            JsonObject row = groups.getJsonObject(i);
            String gid = OeipUrn.of("group", sourceSystem,
                    OeipUrn.localPart(row.getString("id"), sourceSystem, pseudonymize));
            JsonObject group = new JsonObject()
                    .put("globalId", gid)
                    .put("sourceSystem", sourceSystem)
                    .put("name", nonEmpty(row.getString("name"), "Groupe"))
                    .put("groupType", groupType(row.getJsonArray("labels")));
            if (!pseudonymize) {
                putIfPresent(group, "sourceId", row.getString("id"));
                // L'identifiant d'alimentation d'un groupe porte celui de son établissement.
                putIfPresent(group, "externalId", row.getString("externalId"));
            }
            String orgId = row.getString("orgId");
            if (orgId != null) {
                group.put("orgRef", OeipUrn.of("org", sourceSystem,
                        OeipUrn.localPart(orgId, sourceSystem, pseudonymize)));
            }
            groupItems.add(group);
            out.identifier(entry(gid, "group", pseudonymize ? null : row.getString("id"),
                    "directory/groups.json#/items/" + i));

            if (personGid != null) {
                addMembership(out, membershipItems, personGid,
                        persons.getJsonObject(0).getString("id"), gid, row.getString("id"),
                        "member", pseudonymize);
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
                               String role, boolean pseudonymize) {
        String local = OeipUrn.membershipLocalPart(
                OeipUrn.localPart(personSourceId, sourceSystem, pseudonymize),
                OeipUrn.localPart(containerSourceId, sourceSystem, pseudonymize));
        String gid = OeipUrn.of("membership", sourceSystem, local);
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
        // sourceId nul = pseudonymisé : rien ne doit permettre de remonter à l'objet d'origine.
        JsonObject e = new JsonObject()
                .put("globalId", globalId)
                .put("kind", kind)
                .put("level", OeipFormat.LEVEL_CORE)
                .put("sourceSystem", sourceSystem)
                .put("href", href);
        if (sourceId != null) {
            e.put("sourceId", sourceId);
        }
        return e;
    }

    @Override
    public boolean supportsCoreImport() {
        return true;
    }

    /** L'appariement d'identité d'abord : il conditionne le rattachement des contenus. */
    @Override
    public int importOrder() {
        return 20;
    }

    @Override
    public Future<JsonObject> importCore(final org.entcore.interoperability.spi.OeipImportContext context) {
        final JsonArray persons = readItems(context, "directory/persons.json");
        final JsonArray orgs = readItems(context, "directory/organizations.json");

        return matchPersons(persons)
                .compose(personReport -> matchOrganizations(orgs)
                .compose(orgReport -> applyAliases(context, personReport)
                .map(applied -> new JsonObject()
                        .put("service", SERVICE_ID)
                        .put("dryRun", context.isDryRun())
                        .put("persons", personReport)
                        .put("organizations", orgReport)
                        .put("aliasesWritten", applied)
                        .put("notice", "Aucun compte n'est créé par un import : la création des "
                                + "utilisateurs reste le métier de l'alimentation de l'annuaire."))));
    }

    /** Apparie d'abord par identifiant d'alimentation, puis par login pour le reste. */
    private Future<JsonObject> matchPersons(final JsonArray persons) {
        final Map<String, JsonObject> byExternal = new LinkedHashMap<String, JsonObject>();
        final Map<String, JsonObject> byLogin = new LinkedHashMap<String, JsonObject>();
        for (int i = 0; i < persons.size(); i++) {
            JsonObject p = persons.getJsonObject(i);
            if (p.getString("externalId") != null) {
                byExternal.put(p.getString("externalId"), p);
            } else if (p.getString("login") != null) {
                byLogin.put(p.getString("login"), p);
            }
        }

        final JsonArray matched = new JsonArray();
        final JsonArray unmatched = new JsonArray();

        return lookup(Q_MATCH_EXTERNAL, byExternal.keySet())
            .compose(foundExternal -> {
                for (Map.Entry<String, JsonObject> e : byExternal.entrySet()) {
                    JsonObject local = foundExternal.get(e.getKey());
                    if (local != null) {
                        matched.add(describeMatch(e.getValue(), local, "externalId"));
                    } else if (e.getValue().getString("login") != null) {
                        // Repli sur le login : moins sûr, mais mieux que renoncer.
                        byLogin.put(e.getValue().getString("login"), e.getValue());
                    } else {
                        unmatched.add(describeMiss(e.getValue(), "aucun identifiant d'alimentation correspondant"));
                    }
                }
                return lookup(Q_MATCH_LOGIN, byLogin.keySet());
            })
            .map(foundLogin -> {
                for (Map.Entry<String, JsonObject> e : byLogin.entrySet()) {
                    JsonObject local = foundLogin.get(e.getKey());
                    if (local != null) {
                        matched.add(describeMatch(e.getValue(), local, "login"));
                    } else {
                        unmatched.add(describeMiss(e.getValue(),
                                "aucun compte correspondant sur cette plateforme"));
                    }
                }
                return new JsonObject()
                        .put("total", persons.size())
                        .put("matchedCount", matched.size())
                        .put("unmatchedCount", unmatched.size())
                        .put("matched", matched)
                        .put("unmatched", unmatched);
            });
    }

    private Future<JsonObject> matchOrganizations(final JsonArray orgs) {
        final Map<String, JsonObject> byUai = new LinkedHashMap<String, JsonObject>();
        for (int i = 0; i < orgs.size(); i++) {
            JsonObject o = orgs.getJsonObject(i);
            if (isUai(o.getString("uai"))) {
                byUai.put(o.getString("uai"), o);
            }
        }
        return lookup(Q_MATCH_UAI, byUai.keySet()).map(found -> {
            JsonArray matched = new JsonArray();
            JsonArray unmatched = new JsonArray();
            for (Map.Entry<String, JsonObject> e : byUai.entrySet()) {
                JsonObject local = found.get(e.getKey());
                if (local != null) {
                    matched.add(new JsonObject()
                            .put("globalId", e.getValue().getString("globalId"))
                            .put("uai", e.getKey())
                            .put("localId", local.getString("id"))
                            .put("matchedBy", "uai"));
                } else {
                    unmatched.add(new JsonObject()
                            .put("globalId", e.getValue().getString("globalId"))
                            .put("uai", e.getKey())
                            .put("reason", "établissement inconnu de cette plateforme"));
                }
            }
            return new JsonObject()
                    .put("total", orgs.size())
                    .put("matchedCount", matched.size())
                    .put("unmatchedCount", unmatched.size())
                    .put("matched", matched)
                    .put("unmatched", unmatched);
        });
    }

    /**
     * Pose l'alias d'échange sur les comptes appariés — et rien d'autre.
     *
     * En mode d'essai, rien n'est écrit : le rapport est identique, ce qui permet de comparer
     * avant et après sans surprise.
     */
    private Future<Integer> applyAliases(org.entcore.interoperability.spi.OeipImportContext context,
                                         JsonObject personReport) {
        JsonArray matched = personReport.getJsonArray("matched", new JsonArray());
        if (context.isDryRun() || matched.isEmpty()) {
            return Future.succeededFuture(0);
        }
        Future<Integer> chain = Future.succeededFuture(0);
        for (int i = 0; i < matched.size(); i++) {
            final JsonObject m = matched.getJsonObject(i);
            chain = chain.compose(count -> query(Q_TAG_ALIAS, new JsonObject()
                    .put("id", m.getString("localId"))
                    .put("globalId", m.getString("globalId")))
                    .map(rows -> count + rows.size()));
        }
        return chain;
    }

    private JsonObject describeMatch(JsonObject remote, JsonObject local, String by) {
        return new JsonObject()
                .put("globalId", remote.getString("globalId"))
                .put("localId", local.getString("id"))
                .put("login", local.getString("login"))
                .put("matchedBy", by);
    }

    private JsonObject describeMiss(JsonObject remote, String reason) {
        JsonObject miss = new JsonObject()
                .put("globalId", remote.getString("globalId"))
                .put("reason", reason);
        // Le rapport nomme la personne : sans cela l'opérateur ne peut rien faire de l'échec.
        // Il détient déjà le paquet, qui porte ces mêmes champs — les taire ici ne protégerait
        // rien et rendrait le diagnostic impossible.
        for (String key : new String[] { "profile", "login", "externalId", "displayName" }) {
            if (remote.getString(key) != null) {
                miss.put(key, remote.getString(key));
            }
        }
        return miss;
    }

    private Future<Map<String, JsonObject>> lookup(String cypher, java.util.Collection<String> values) {
        final Map<String, JsonObject> found = new LinkedHashMap<String, JsonObject>();
        if (values.isEmpty()) {
            return Future.succeededFuture(found);
        }
        JsonArray list = new JsonArray();
        for (String v : values) {
            list.add(v);
        }
        return query(cypher, new JsonObject().put("values", list)).map(rows -> {
            for (int i = 0; i < rows.size(); i++) {
                JsonObject row = rows.getJsonObject(i);
                if (row.getString("key") != null) {
                    found.put(row.getString("key"), row);
                }
            }
            return found;
        });
    }

    private JsonArray readItems(org.entcore.interoperability.spi.OeipImportContext context, String path) {
        try {
            java.nio.file.Path p = context.getPackageRoot().resolve(path);
            if (!java.nio.file.Files.isRegularFile(p)) {
                return new JsonArray();
            }
            JsonObject doc = new JsonObject(new String(
                    java.nio.file.Files.readAllBytes(p), java.nio.charset.StandardCharsets.UTF_8));
            return doc.getJsonArray("items", new JsonArray());
        } catch (Exception e) {
            return new JsonArray();
        }
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
