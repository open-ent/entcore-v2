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

    /**
     * Partie locale d'un identifiant, pseudonymisée ou non.
     *
     * Le calcul doit être IDENTIQUE pour tous les services : l'auteur d'un billet et la personne
     * décrite dans l'annuaire sont le même objet, et une divergence de calcul romprait la
     * référence sans que rien ne le signale.
     */
    public static String localPart(String rawId, String sourceSystem, boolean pseudonymize) {
        if (!pseudonymize) {
            return sanitize(rawId);
        }
        return "p" + sha256Prefix(sourceSystem + "|" + (rawId == null ? "" : rawId));
    }

    static String sha256Prefix(String value) {
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 16; i++) {
                sb.append(Character.forDigit((d[i] >> 4) & 0xF, 16));
                sb.append(Character.forDigit(d[i] & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 indisponible", e);
        }
    }

    public static String of(String kind, String authority, String localPart) {
        return "urn:oeip:" + OeipFormat.VERSION + ":" + kind + ":" + authority + ":"
                + sanitize(localPart);
    }

    /*
     * Il n'existe volontairement pas de raccourci « org(ss, id) » : un tel raccourci contourne
     * localPart() et produit, en mode pseudonymisé, une référence qui ne désigne plus rien.
     * Le défaut est silencieux — le paquet reste bien formé, seules les références se brisent.
     * Toute construction passe donc par of(kind, authority, localPart(...)).
     */

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
