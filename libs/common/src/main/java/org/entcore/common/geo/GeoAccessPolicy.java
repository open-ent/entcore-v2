package org.entcore.common.geo;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.net.InetAddress;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Zones géographiques autorisées pour se connecter.
 *
 * <p>Une politique ({@code geo_access_policies} dans Mongo) a une portée :</p>
 * <ul>
 *   <li>{@code platform} : réglée par le super-admin, vaut pour tous les comptes ;</li>
 *   <li>{@code structure} : réglée par l'ADML de collectivité (ou le super-admin) pour un
 *   établissement et ceux qui lui sont rattachés ({@code structureIds}, calculé à
 *   l'enregistrement).</li>
 * </ul>
 *
 * <p>Champs : {@code enabled}, {@code mode} ({@value #MODE_OBSERVE} : journaliser et alerter ;
 * {@value #MODE_MFA} : exiger en plus le second facteur dès la connexion),
 * {@code allowedCountries} (codes ISO-2), {@code allowedCidrs} (adresses ou réseaux toujours
 * admis), {@code profiles} (profils visés parmi {@code Personnel, Teacher, Student, Relative,
 * Guest} et les fonctions {@code ADMC}, {@code ADML} ; vide = tous).</p>
 *
 * <p>Règles d'évaluation, choisies pour ne jamais enfermer quelqu'un dehors : une adresse
 * interne est dans la zone ; une adresse que la base ne sait pas localiser n'est ni dans ni
 * hors zone (aucun effet) ; une politique sans pays ni réseau autorisé ne restreint rien.</p>
 */
public final class GeoAccessPolicy {

    public static final String COLLECTION = "geo_access_policies";
    public static final String SCOPE_PLATFORM = "platform";
    public static final String SCOPE_STRUCTURE = "structure";
    public static final String MODE_OBSERVE = "observe";
    public static final String MODE_MFA = "mfa";
    public static final String TARGET_ADMC = "ADMC";
    public static final String TARGET_ADML = "ADML";

    private GeoAccessPolicy() {
    }

    /**
     * Profils et fonctions d'administration d'un compte, tels que les politiques les visent,
     * à partir des informations de session ({@code type}, {@code functions}).
     */
    public static Set<String> targetsOf(final JsonObject sessionInfos) {
        final Set<String> targets = new HashSet<>();
        if (sessionInfos == null) {
            return targets;
        }
        final String type = sessionInfos.getString("type");
        if (type != null) {
            targets.add(type);
        }
        final JsonObject functions = sessionInfos.getJsonObject("functions");
        if (functions != null) {
            if (functions.containsKey("SUPER_ADMIN")) {
                targets.add(TARGET_ADMC);
            }
            if (functions.containsKey("ADMIN_LOCAL")) {
                targets.add(TARGET_ADML);
            }
        }
        return targets;
    }

    /** Vrai si la politique est active et concerne ce compte (portée et profil). */
    public static boolean appliesTo(final JsonObject policy, final Set<String> targets, final Set<String> structureIds) {
        if (policy == null || !Boolean.TRUE.equals(policy.getBoolean("enabled"))) {
            return false;
        }
        if (SCOPE_STRUCTURE.equals(policy.getString("scope"))) {
            final JsonArray covered = policy.getJsonArray("structureIds", new JsonArray());
            boolean match = false;
            for (Object id : covered) {
                if (id instanceof String && structureIds.contains(id)) {
                    match = true;
                    break;
                }
            }
            if (!match) {
                return false;
            }
        } else if (!SCOPE_PLATFORM.equals(policy.getString("scope"))) {
            return false;
        }
        final JsonArray profiles = policy.getJsonArray("profiles", new JsonArray());
        if (profiles.isEmpty()) {
            return true;
        }
        for (Object p : profiles) {
            if (p instanceof String && targets.contains(p)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Situe une connexion par rapport à une politique.
     *
     * @param geo résultat de {@link GeoIpLocator#locate(String)}, éventuellement {@code null}
     * @return {@code TRUE} hors zone, {@code FALSE} dans la zone, {@code null} indéterminé
     */
    public static Boolean isOutOfZone(final JsonObject policy, final String ip, final JsonObject geo) {
        final JsonArray countries = policy.getJsonArray("allowedCountries", new JsonArray());
        final JsonArray cidrs = policy.getJsonArray("allowedCidrs", new JsonArray());
        if (countries.isEmpty() && cidrs.isEmpty()) {
            return Boolean.FALSE;
        }
        if (geo != null && Boolean.TRUE.equals(geo.getBoolean("private"))) {
            return Boolean.FALSE;
        }
        final String normalized = GeoIpLocator.normalizeIp(ip);
        if (normalized == null) {
            return null;
        }
        try {
            if (normalized.matches("[0-9a-fA-F:.]+") && GeoIpLocator.isPrivate(InetAddress.getByName(normalized))) {
                return Boolean.FALSE;
            }
        } catch (Exception e) {
            return null;
        }
        for (Object c : cidrs) {
            if (c instanceof String && inCidr(normalized, (String) c)) {
                return Boolean.FALSE;
            }
        }
        final String country = geo != null ? geo.getString("country") : null;
        if (country == null) {
            // Ni la base ni les réseaux autorisés ne permettent de conclure
            return countries.isEmpty() ? Boolean.TRUE : null;
        }
        for (Object c : countries) {
            if (c instanceof String && country.equalsIgnoreCase((String) c)) {
                return Boolean.FALSE;
            }
        }
        return Boolean.TRUE;
    }

    /**
     * Décision pour une connexion : politiques applicables hors zone, et si l'une d'elles
     * est en mode {@value #MODE_MFA}.
     *
     * @return {@code {outOfZone, mfa, policies: [ids], ip, geo}} ; {@code outOfZone} faux si
     * aucune politique ne s'applique ou si la position est indéterminée
     */
    public static JsonObject evaluate(final List<JsonObject> policies, final JsonObject sessionInfos,
                                      final String ip, final JsonObject geo) {
        final Set<String> targets = targetsOf(sessionInfos);
        final Set<String> structures = new HashSet<>();
        if (sessionInfos != null) {
            for (Object s : sessionInfos.getJsonArray("structures", new JsonArray())) {
                if (s instanceof String) {
                    structures.add((String) s);
                }
            }
        }
        final JsonArray matched = new JsonArray();
        boolean mfa = false;
        if (policies != null) {
            for (JsonObject policy : policies) {
                if (!appliesTo(policy, targets, structures)) {
                    continue;
                }
                if (Boolean.TRUE.equals(isOutOfZone(policy, ip, geo))) {
                    matched.add(policy.getValue("_id") != null ? policy.getValue("_id").toString()
                            : policy.getString("scope"));
                    mfa |= MODE_MFA.equals(policy.getString("mode"));
                }
            }
        }
        final JsonObject decision = new JsonObject()
                .put("outOfZone", !matched.isEmpty())
                .put("mfa", mfa)
                .put("policies", matched);
        if (ip != null) {
            decision.put("ip", GeoIpLocator.normalizeIp(ip));
        }
        if (geo != null) {
            decision.put("geo", geo);
        }
        return decision;
    }

    /** Appartenance d'une adresse à un réseau {@code a.b.c.d/n} (ou à une adresse seule). */
    public static boolean inCidr(final String ip, final String cidr) {
        if (ip == null || cidr == null || cidr.trim().isEmpty()) {
            return false;
        }
        try {
            final String[] parts = cidr.trim().split("/");
            if (!parts[0].matches("[0-9a-fA-F:.]+") || !ip.matches("[0-9a-fA-F:.]+")) {
                return false;
            }
            final byte[] network = InetAddress.getByName(parts[0]).getAddress();
            final byte[] address = InetAddress.getByName(ip).getAddress();
            if (network.length != address.length) {
                return false;
            }
            final int bits = parts.length > 1 ? Integer.parseInt(parts[1].trim()) : network.length * 8;
            if (bits < 0 || bits > network.length * 8) {
                return false;
            }
            for (int i = 0; i < bits; i++) {
                final int mask = 0x80 >> (i % 8);
                if ((network[i / 8] & mask) != (address[i / 8] & mask)) {
                    return false;
                }
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Validation d'une saisie de réseau, pour refuser un formulaire incorrect. */
    public static boolean isValidCidr(final String cidr) {
        if (cidr == null) {
            return false;
        }
        final String[] parts = cidr.trim().split("/");
        if (parts.length > 2 || !parts[0].matches("[0-9a-fA-F:.]+")) {
            return false;
        }
        try {
            final byte[] network = InetAddress.getByName(parts[0]).getAddress();
            if (parts.length == 2) {
                final int bits = Integer.parseInt(parts[1]);
                return bits >= 0 && bits <= network.length * 8;
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
