package org.entcore.interoperability.transcode;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Remplace les liens internes d'un contenu HTML par des références d'échange.
 *
 * Le remplacement a lieu UNE SEULE FOIS, à l'export, là où la base est encore connue. Chaque
 * réécriture est journalisée, et toute référence non résolue est signalée plutôt que remplacée
 * au hasard : c'est ce qui distingue ce traitement de la substitution d'identifiants historique,
 * qui réécrivait à l'aveugle toute chaîne ressemblant à un identifiant, texte libre compris.
 */
public class HtmlReferenceRewriter {

    /** Liens de pièce jointe du produit : /workspace/document/ID, /workspace/pub/document/ID. */
    private static final Pattern DOC_LINK = Pattern.compile(
            "/workspace/(?:pub/)?document/([0-9a-fA-F-]{36})");

    private final Map<String, String> fileGlobalIdBySourceId;
    private final JsonArray rewrites = new JsonArray();
    private final JsonArray unresolved = new JsonArray();

    public HtmlReferenceRewriter(Map<String, String> fileGlobalIdBySourceId) {
        this.fileGlobalIdBySourceId = fileGlobalIdBySourceId == null
                ? new LinkedHashMap<String, String>() : fileGlobalIdBySourceId;
    }

    /**
     * @param ownerGlobalId la ressource dont on réécrit le corps, pour la traçabilité
     * @param field         champ réécrit, pour la traçabilité
     */
    public String rewrite(String html, String ownerGlobalId, String field) {
        if (html == null || html.isEmpty()) {
            return html;
        }
        Matcher m = DOC_LINK.matcher(html);
        StringBuffer sb = new StringBuffer();
        Map<String, Integer> counts = new LinkedHashMap<String, Integer>();
        while (m.find()) {
            String sourceId = m.group(1);
            String globalId = fileGlobalIdBySourceId.get(sourceId);
            if (globalId == null) {
                // Laissée telle quelle, et signalée : un lien mort annoncé vaut mieux qu'un lien
                // réécrit vers n'importe quoi.
                unresolved.add(new JsonObject()
                        .put("in", ownerGlobalId)
                        .put("field", field)
                        .put("rawValue", m.group())
                        .put("reason", "not-found"));
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group()));
                continue;
            }
            String replacement = "oeip:file/" + globalId;
            String key = m.group() + " " + replacement;
            Integer previous = counts.get(key);
            counts.put(key, previous == null ? 1 : previous + 1);
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);

        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            String[] parts = e.getKey().split(" ", 2);
            rewrites.add(new JsonObject()
                    .put("in", ownerGlobalId)
                    .put("field", field)
                    .put("from", parts[0])
                    .put("to", parts[1])
                    .put("count", e.getValue()));
        }
        return sb.toString();
    }

    public JsonArray getRewrites() { return rewrites; }
    public JsonArray getUnresolvedReferences() { return unresolved; }
}
