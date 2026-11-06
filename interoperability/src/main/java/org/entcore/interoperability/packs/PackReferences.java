package org.entcore.interoperability.packs;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Références d'une ressource de pack vers une autre ressource du même pack.
 *
 * <p>Un sujet d'exercices qui fait écouter une dictée doit désigner l'enregistrement que le même
 * pack dépose dans l'espace documentaire. Or l'identifiant local de ce fichier n'existe qu'une fois
 * le pack installé, et il diffère d'une plateforme à l'autre. Le pack écrit donc
 * {@code oeip-ref:<globalId>} dans le document natif, et l'installation remplace chaque référence
 * par l'adresse de la ressource installée ({@link PackResourceAdapter#href}).</p>
 *
 * <p>Une ressource référencée est un COMPOSANT : on y accède par la ressource qui la cite. Un élève
 * qui reçoit le sujet doit pouvoir écouter l'enregistrement, même si le pack n'est ouvert qu'aux
 * enseignants ; les partages en tiennent compte (cf. {@code PackService#applyAccess}).</p>
 */
public final class PackReferences {

    /** Caractères d'un identifiant global, tels que {@code oeip_pack.py} les produit. */
    static final Pattern REF = Pattern.compile("oeip-ref:(urn:oeip:[A-Za-z0-9._~:-]+)");

    private PackReferences() {
    }

    /** Identifiants globaux cités par le document natif d'une ressource. */
    public static Set<String> targets(JsonObject resource) {
        final Set<String> targets = new LinkedHashSet<String>();
        final JsonObject document = resource.getJsonObject("document");
        if (document == null) {
            return targets;
        }
        final Matcher m = REF.matcher(document.encode());
        while (m.find()) {
            targets.add(m.group(1));
        }
        return targets;
    }

    /** Toutes les ressources citées par au moins une ressource du descripteur. */
    public static Set<String> components(JsonArray resources) {
        final Set<String> components = new LinkedHashSet<String>();
        for (Object o : resources) {
            components.addAll(targets((JsonObject) o));
        }
        return components;
    }

    /**
     * Ordre d'installation : les ressources qui ne citent rien d'abord, dans l'ordre du paquet, puis
     * celles qui citent — leurs cibles ont alors un identifiant local. Un composant ne cite pas
     * lui-même d'autre ressource : un seul niveau suffit.
     */
    public static List<JsonObject> installOrder(JsonArray resources) {
        final List<JsonObject> first = new ArrayList<JsonObject>();
        final List<JsonObject> then = new ArrayList<JsonObject>();
        for (Object o : resources) {
            final JsonObject resource = (JsonObject) o;
            (targets(resource).isEmpty() ? first : then).add(resource);
        }
        first.addAll(then);
        return first;
    }

    /**
     * La ressource, son document natif réécrit : chaque {@code oeip-ref:<globalId>} devient
     * l'adresse donnée par {@code hrefs}. Une référence sans adresse fait refuser la ressource —
     * un sujet qui ferait écouter un fichier absent ne doit pas s'installer comme s'il était complet.
     */
    public static JsonObject resolve(JsonObject resource, Map<String, String> hrefs) {
        final JsonObject document = resource.getJsonObject("document");
        if (document == null) {
            return resource;
        }
        final Matcher m = REF.matcher(document.encode());
        final StringBuffer out = new StringBuffer();
        while (m.find()) {
            final String href = hrefs.get(m.group(1));
            if (href == null) {
                throw new IllegalArgumentException("référence vers une ressource absente ou sans adresse : "
                        + m.group(1));
            }
            m.appendReplacement(out, Matcher.quoteReplacement(href));
        }
        m.appendTail(out);
        return resource.copy().put("document", new JsonObject(out.toString()));
    }
}
