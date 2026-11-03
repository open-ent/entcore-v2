package org.entcore.interoperability.packs;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Droits qu'une ressource de pack doit porter, déduits des abonnements.
 *
 * <p>Le tableau {@code shared} d'une ressource de pack n'est jamais modifié pas à pas : il est
 * RECALCULÉ en entier à partir des abonnements en cours. Un désabonnement, un changement de public
 * ou une variante d'établissement se traduisent ainsi par le même geste, et une erreur passée ne
 * survit pas au calcul suivant. C'est possible parce que la ressource appartient au compte
 * technique : personne d'autre n'y a posé de partage qu'il faudrait préserver.</p>
 */
public final class PackShares {

    public static final String TEACHERS = "teachers";
    public static final String TEACHERS_AND_STUDENTS = "teachers+students";

    private PackShares() {
    }

    /** Profils de l'annuaire qu'un public d'abonnement désigne. */
    public static boolean includesStudents(String audience) {
        return TEACHERS_AND_STUDENTS.equals(audience);
    }

    /**
     * Tableau {@code shared} au format des modules (clé d'action : nom de la méthode sécurisée,
     * points remplacés par des tirets).
     *
     * @param audiences   public retenu par établissement abonné
     * @param groups      groupes de profil par établissement : profil ({@code Teacher},
     *                    {@code Student}) → identifiant de groupe
     * @param actions     noms des actions de lecture de l'application (tels que dans Neo4j)
     * @param excluded    établissements qui ont leur propre variante de cette ressource
     */
    public static JsonArray compute(Map<String, String> audiences, Map<String, Map<String, String>> groups,
                                    List<String> actions, Set<String> excluded) {
        final Map<String, JsonObject> byGroup = new LinkedHashMap<String, JsonObject>();
        for (Map.Entry<String, String> subscription : audiences.entrySet()) {
            final String structure = subscription.getKey();
            if (excluded != null && excluded.contains(structure)) {
                continue;
            }
            final Map<String, String> profiles = groups.get(structure);
            if (profiles == null) {
                continue;
            }
            grant(byGroup, profiles.get("Teacher"), actions);
            if (includesStudents(subscription.getValue())) {
                grant(byGroup, profiles.get("Student"), actions);
            }
        }
        return new JsonArray(new java.util.ArrayList<Object>(byGroup.values()));
    }

    private static void grant(Map<String, JsonObject> byGroup, String groupId, Collection<String> actions) {
        if (groupId == null) {
            return;
        }
        JsonObject entry = byGroup.get(groupId);
        if (entry == null) {
            entry = new JsonObject().put("groupId", groupId);
            byGroup.put(groupId, entry);
        }
        for (String action : actions) {
            entry.put(shareKey(action), true);
        }
    }

    /**
     * Ajoute des personnes nommées au tableau {@code shared} (éditeurs d'une variante
     * d'établissement), avec les actions données. Rend le même tableau.
     */
    public static JsonArray withUsers(JsonArray shared, Collection<String> userIds, Collection<String> actions) {
        for (String userId : userIds) {
            final JsonObject entry = new JsonObject().put("userId", userId);
            for (String action : actions) {
                entry.put(shareKey(action), true);
            }
            shared.add(entry);
        }
        return shared;
    }

    /** {@code net.atos.entng.wiki.controllers.WikiController|getWiki} → clé du tableau shared. */
    public static String shareKey(String action) {
        return action.replace('.', '-');
    }
}
