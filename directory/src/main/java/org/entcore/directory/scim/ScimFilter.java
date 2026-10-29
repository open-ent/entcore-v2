/*
 * Traduction d'un filtre SCIM 2.0 (RFC 7644 §3.4.2.2) en clause WHERE Cypher paramétrée.
 *
 * Sous-ensemble supporté : comparaisons attrPath op valeur, reliées par "and".
 *   opérateurs : eq, ne, co, sw, ew (insensibles à la casse) et pr (présent).
 * Non supporté (400 invalidFilter) : or, not, parenthèses, filtres de valeur [..], gt/ge/lt/le.
 * C'est ce qu'utilisent les IAM pour rapprocher les comptes (userName eq, externalId eq,
 * emails.value eq) ; on élargira à la demande.
 */
package org.entcore.directory.scim;

import io.vertx.core.json.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ScimFilter {

    public static final class InvalidFilterException extends Exception {
        public InvalidFilterException(String message) {
            super(message);
        }
    }

    /** Clause WHERE (sans le mot-clé) et ses paramètres. */
    public static final class Clause {
        public final String where;
        public final JsonObject params;

        Clause(String where, JsonObject params) {
            this.where = where;
            this.params = params;
        }
    }

    private static final Pattern COMPARISON = Pattern.compile(
            "^\\s*([A-Za-z][\\w.:]*)\\s+(eq|ne|co|sw|ew)\\s+(\"(?:[^\"\\\\]|\\\\.)*\"|true|false|null)\\s*$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern PRESENT = Pattern.compile("^\\s*([A-Za-z][\\w.:]*)\\s+pr\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern AND = Pattern.compile("\\s+and\\s+", Pattern.CASE_INSENSITIVE);

    private ScimFilter() {}

    /**
     * @param filter     filtre SCIM ; null ou vide -> pas de restriction
     * @param attributes attribut SCIM (en minuscules) -> expression Cypher
     */
    public static Clause toCypher(String filter, Map<String, String> attributes) throws InvalidFilterException {
        final JsonObject params = new JsonObject();
        if (filter == null || filter.trim().isEmpty()) return new Clause("true", params);
        final String structure = stripStrings(filter);
        if (structure.contains("(") || structure.contains("[") || Pattern.compile("\\s(or|not)\\s", Pattern.CASE_INSENSITIVE)
                .matcher(" " + structure + " ").find()) {
            throw new InvalidFilterException("unsupported filter syntax (only 'and' of simple comparisons)");
        }
        final List<String> parts = new ArrayList<>();
        int i = 0;
        for (String term : splitOutsideStrings(filter)) {
            final String param = "f" + i++;
            final Matcher pr = PRESENT.matcher(term);
            if (pr.matches()) {
                parts.add(attribute(pr.group(1), attributes) + " IS NOT NULL");
                continue;
            }
            final Matcher m = COMPARISON.matcher(term);
            if (!m.matches()) throw new InvalidFilterException("invalid comparison: " + term.trim());
            final String expr = attribute(m.group(1), attributes);
            final String op = m.group(2).toLowerCase();
            final String raw = m.group(3);
            if (!raw.startsWith("\"")) {
                if ("null".equalsIgnoreCase(raw)) {
                    parts.add(expr + ("eq".equals(op) ? " IS NULL" : " IS NOT NULL"));
                } else if ("eq".equals(op) || "ne".equals(op)) {
                    params.put(param, Boolean.parseBoolean(raw.toLowerCase()));
                    parts.add(expr + ("eq".equals(op) ? " = $" : " <> $") + param);
                } else {
                    throw new InvalidFilterException("operator " + op + " needs a string");
                }
                continue;
            }
            params.put(param, unquote(raw).toLowerCase());
            final String lower = "toLower(" + expr + ")";
            switch (op) {
                case "eq": parts.add(lower + " = $" + param); break;
                case "ne": parts.add("(" + expr + " IS NULL OR " + lower + " <> $" + param + ")"); break;
                case "co": parts.add(lower + " CONTAINS $" + param); break;
                case "sw": parts.add(lower + " STARTS WITH $" + param); break;
                default:   parts.add(lower + " ENDS WITH $" + param); break;
            }
        }
        return new Clause(String.join(" AND ", parts), params);
    }

    private static String attribute(String path, Map<String, String> attributes) throws InvalidFilterException {
        String key = path.toLowerCase();
        // Forme pleinement qualifiée : urn:...:User:userName -> userName
        final int colon = key.lastIndexOf(':');
        if (colon >= 0 && key.startsWith("urn:")) key = key.substring(colon + 1);
        final String expr = attributes.get(key);
        if (expr == null) throw new InvalidFilterException("unsupported attribute: " + path);
        return expr;
    }

    private static String unquote(String quoted) {
        return quoted.substring(1, quoted.length() - 1).replace("\\\"", "\"").replace("\\\\", "\\");
    }

    /** Remplace le contenu des chaînes pour que les mots-clés qu'elles contiennent ne soient pas analysés. */
    private static String stripStrings(String filter) {
        return filter.replaceAll("\"(?:[^\"\\\\]|\\\\.)*\"", "\"\"");
    }

    /** Découpe sur "and" hors des chaînes entre guillemets. */
    private static List<String> splitOutsideStrings(String filter) {
        final List<String> terms = new ArrayList<>();
        final StringBuilder current = new StringBuilder();
        boolean inString = false;
        for (int k = 0; k < filter.length(); k++) {
            final char c = filter.charAt(k);
            if (c == '"' && (k == 0 || filter.charAt(k - 1) != '\\')) inString = !inString;
            if (!inString) {
                final Matcher and = AND.matcher(filter).region(k, filter.length());
                if (and.lookingAt()) {
                    terms.add(current.toString());
                    current.setLength(0);
                    k = and.end() - 1;
                    continue;
                }
            }
            current.append(c);
        }
        terms.add(current.toString());
        return terms;
    }
}
