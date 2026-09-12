package org.entcore.interoperability.providers;

import org.entcore.interoperability.OeipFormat;

/**
 * Fabrique les identifiants d'échange.
 *
 * Un identifiant interne ne veut rien dire sur une autre plateforme : le réutiliser tel quel
 * provoquerait des collisions ou des rattachements faux. Chaque objet porte donc un identifiant
 * propre au format, et — lorsqu'un référentiel national existe — un alias qui permet à la
 * plateforme d'arrivée d'apparier sans rien connaître de celle de départ.
 */
public final class OeipUrn {

    /** Référentiel des établissements. */
    public static final String AUTHORITY_UAI = "fr.men.uai";
    /** Référentiel d'alimentation des personnes. */
    public static final String AUTHORITY_AAF = "fr.men.aaf";

    private OeipUrn() {}

    public static String of(String kind, String authority, String localPart) {
        return "urn:oeip:" + OeipFormat.VERSION + ":" + kind + ":" + authority + ":"
                + sanitize(localPart);
    }

    public static String org(String sourceSystem, String id)        { return of("org", sourceSystem, id); }
    public static String person(String sourceSystem, String id)     { return of("person", sourceSystem, id); }
    public static String group(String sourceSystem, String id)      { return of("group", sourceSystem, id); }
    public static String membership(String sourceSystem, String id) { return of("membership", sourceSystem, id); }

    public static String uaiAlias(String uai)          { return of("org", AUTHORITY_UAI, uai); }
    public static String aafPersonAlias(String extId)  { return of("person", AUTHORITY_AAF, extId); }

    /** Identifiant d'adhésion, dérivé du couple personne / conteneur pour rester reproductible. */
    public static String membershipLocalPart(String personId, String containerId) {
        return sanitize(personId) + "--" + sanitize(containerId);
    }

    /**
     * La partie locale d'une URN n'admet qu'un jeu restreint de caractères. Plutôt que de rejeter
     * un identifiant exotique — ce qui ferait échouer tout l'export pour un seul objet — les
     * caractères hors jeu sont remplacés, de façon déterministe.
     */
    static String sanitize(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "inconnu";
        }
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '.' || c == '_' || c == '~' || c == '-';
            sb.append(ok ? c : '_');
        }
        return sb.toString();
    }
}
