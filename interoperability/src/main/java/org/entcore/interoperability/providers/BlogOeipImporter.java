package org.entcore.interoperability.providers;

import fr.wseduc.mongodb.MongoDb;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.entcore.interoperability.spi.OeipImportContext;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reprend des blogs et des billets décrits dans le modèle commun.
 *
 * C'est la première reprise de contenu qui ne dépend pas d'une charge utile Open ENT : un paquet
 * produit par un autre ENT, pourvu qu'il respecte le format, est repris ici.
 *
 * Trois principes gouvernent cette reprise.
 *
 * <p><b>Les identifiants d'origine ne sont jamais réutilisés.</b> Ils appartiennent à la
 * plateforme émettrice ; les reprendre provoquerait des collisions ou, pire, l'écrasement
 * silencieux d'un contenu local portant le même identifiant. Tout objet créé reçoit un
 * identifiant neuf, et la correspondance est publiée dans le rapport.
 *
 * <p><b>Rien n'est écrasé.</b> Une reprise ajoute ; elle ne fusionne pas et ne remplace pas. Un
 * contenu déjà présent le reste.
 *
 * <p><b>Un lien qu'on ne sait pas rétablir est signalé.</b> Un billet peut citer un fichier que
 * la plateforme d'arrivée n'a pas repris — parce qu'il manquait au départ, ou parce que le service
 * correspondant n'a pas été demandé. Le lien est alors laissé en l'état et inscrit au rapport,
 * plutôt que pointé vers un contenu arbitraire.
 */
public class BlogOeipImporter {

    private static final String BLOGS = "blogs";
    private static final String POSTS = "posts";

    /** Référence d'échange telle qu'elle apparaît dans un corps repris. */
    private static final Pattern OEIP_FILE = Pattern.compile(
            "oeip:file/(urn:oeip:[^\"'\\s<>)]+)");

    private final MongoDb mongo;

    public BlogOeipImporter(MongoDb mongo) {
        this.mongo = mongo;
    }

    public Future<JsonObject> importCore(OeipImportContext context) {
        final JsonArray resources = readItems(context, "resources/blog/resources.json");
        if (resources.isEmpty()) {
            return Future.succeededFuture(new JsonObject()
                    .put("service", BlogOeipMapper.SERVICE_ID)
                    .put("dryRun", context.isDryRun())
                    .put("created", 0)
                    .put("notice", "Aucun contenu de blog décrit dans ce paquet."));
        }

        // 1. Les conteneurs d'abord : un billet ne peut être rattaché qu'à un blog déjà créé.
        final Map<String, String> newIdByGlobalId = new LinkedHashMap<String, String>();
        final List<JsonObject> blogDocs = new ArrayList<JsonObject>();
        final List<JsonObject> postDocs = new ArrayList<JsonObject>();
        final JsonArray unresolved = new JsonArray();

        for (int i = 0; i < resources.size(); i++) {
            JsonObject r = resources.getJsonObject(i);
            if (isPost(r)) {
                continue;
            }
            String newId = UUID.randomUUID().toString();
            newIdByGlobalId.put(r.getString("globalId"), newId);
            blogDocs.add(toBlogDocument(r, newId, context));
        }

        for (int i = 0; i < resources.size(); i++) {
            JsonObject r = resources.getJsonObject(i);
            if (!isPost(r)) {
                continue;
            }
            String parent = newIdByGlobalId.get(r.getString("parentResourceRef"));
            if (parent == null) {
                // Un billet sans blog n'a nulle part où vivre : on le dit plutôt que de
                // l'accrocher au premier blog venu.
                unresolved.add(new JsonObject()
                        .put("globalId", r.getString("globalId"))
                        .put("title", r.getString("title"))
                        .put("reason", "le blog qui porte ce billet n'est pas dans le paquet"));
                continue;
            }
            String newId = UUID.randomUUID().toString();
            newIdByGlobalId.put(r.getString("globalId"), newId);
            postDocs.add(toPostDocument(r, newId, parent, context, unresolved));
        }

        final JsonObject report = new JsonObject()
                .put("service", BlogOeipMapper.SERVICE_ID)
                .put("dryRun", context.isDryRun())
                .put("blogs", blogDocs.size())
                .put("posts", postDocs.size())
                .put("created", blogDocs.size() + postDocs.size())
                .put("idMap", asIdMap(newIdByGlobalId))
                .put("notice", "Une reprise ajoute : rien n'est écrasé, et les identifiants "
                        + "d'origine ne sont jamais réutilisés.");
        if (!unresolved.isEmpty()) {
            report.put("unresolved", unresolved);
        }

        if (context.isDryRun()) {
            // Le raisonnement est allé à son terme ; seule l'écriture manque.
            return Future.succeededFuture(report.put("created", 0)
                    .put("wouldCreate", blogDocs.size() + postDocs.size()));
        }

        for (Map.Entry<String, String> e : newIdByGlobalId.entrySet()) {
            context.getLocalByGlobalId().put(e.getKey(), e.getValue());
        }
        return insertAll(BLOGS, blogDocs)
                .compose(v -> insertAll(POSTS, postDocs))
                .map(v -> report);
    }

    // ------------------------------------------------------------------ conversion

    static boolean isPost(JsonObject resource) {
        return "urn:oeip:restype:blog.post".equals(resource.getString("resourceType"));
    }

    private JsonObject toBlogDocument(JsonObject r, String newId, OeipImportContext context) {
        JsonObject doc = new JsonObject()
                .put("_id", newId)
                .put("title", nonEmpty(r.getString("title"), "Blog importé"))
                .put("description", nonEmpty(r.getString("description"), ""))
                .put("thumbnail", "")
                .put("comment-type", "NONE")
                .put("publish-type", "RESTRAINT")
                .put("allowReplies", false)
                // Le partage n'est pas transposable : les groupes de la plateforme d'origine
                // n'existent pas ici. Le contenu revient donc privé, à repartager sciemment.
                .put("visibility", "OWNER")
                .put("shared", new JsonArray())
                .put("author", author(context))
                .put("created", mongoDate(r.getString("createdAt")))
                .put("modified", mongoDate(r.getString("modifiedAt")));
        return doc;
    }

    private JsonObject toPostDocument(JsonObject r, String newId, String blogId,
                                      OeipImportContext context, JsonArray unresolved) {
        String content = readBody(context, r);
        content = restoreLinks(content, r.getString("globalId"), context, unresolved);

        return new JsonObject()
                .put("_id", newId)
                .put("title", nonEmpty(r.getString("title"), "Billet importé"))
                .put("content", content)
                .put("contentPlain", stripTags(content))
                // Un contenu venu d'ailleurs revient en brouillon : sa publication est une
                // décision de la personne qui le reçoit, pas une conséquence de l'import.
                .put("state", "DRAFT")
                .put("comments", new JsonArray())
                .put("views", 0)
                .put("author", author(context))
                .put("created", mongoDate(r.getString("createdAt")))
                .put("modified", mongoDate(r.getString("modifiedAt")))
                .put("sorted", mongoDate(r.getString("createdAt")))
                .put("blog", new JsonObject().put("$ref", BLOGS).put("$id", blogId));
    }

    /**
     * Rétablit les liens vers les fichiers effectivement repris.
     *
     * Un fichier non repris laisse son lien en l'état : c'est visible, corrigeable, et infiniment
     * préférable à un lien qui pointerait vers un contenu sans rapport.
     */
    static String restoreLinks(String content, String ownerGlobalId, OeipImportContext context,
                               JsonArray unresolved) {
        if (content == null || content.isEmpty()) {
            return content;
        }
        Matcher m = OEIP_FILE.matcher(content);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String localId = context.getLocalByGlobalId().get(m.group(1));
            if (localId == null) {
                unresolved.add(new JsonObject()
                        .put("in", ownerGlobalId)
                        .put("rawValue", m.group())
                        .put("reason", "le fichier cité n'a pas été repris sur cette plateforme"));
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group()));
            } else {
                m.appendReplacement(sb,
                        Matcher.quoteReplacement("/workspace/document/" + localId));
            }
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private JsonObject author(OeipImportContext context) {
        return new JsonObject()
                .put("userId", context.getTargetUserId())
                .put("username", context.getTargetUserName())
                .put("login", context.getTargetUserLogin());
    }

    private String readBody(OeipImportContext context, JsonObject r) {
        JsonObject body = r.getJsonObject("body");
        if (body == null) {
            return "";
        }
        if (body.getString("content") != null) {
            return body.getString("content");
        }
        String href = body.getString("href");
        if (href == null) {
            return "";
        }
        try {
            Path p = context.getPackageRoot().resolve(href);
            return Files.isRegularFile(p)
                    ? new String(Files.readAllBytes(p), StandardCharsets.UTF_8) : "";
        } catch (Exception e) {
            return "";
        }
    }

    // ------------------------------------------------------------------ écriture

    private Future<Void> insertAll(String collection, List<JsonObject> documents) {
        Future<Void> chain = Future.succeededFuture();
        for (final JsonObject doc : documents) {
            chain = chain.compose(v -> insert(collection, doc));
        }
        return chain;
    }

    private Future<Void> insert(String collection, JsonObject document) {
        final Promise<Void> promise = Promise.promise();
        mongo.insert(collection, document, res -> {
            if ("ok".equals(res.body().getString("status"))) {
                promise.complete();
            } else {
                promise.fail("[OEIP] insertion impossible dans " + collection + " : "
                        + res.body().getString("message"));
            }
        });
        return promise.future();
    }

    // ------------------------------------------------------------------ utilitaires

    private static JsonObject asIdMap(Map<String, String> map) {
        JsonObject json = new JsonObject();
        for (Map.Entry<String, String> e : map.entrySet()) {
            json.put(e.getKey(), e.getValue());
        }
        return json;
    }

    private static JsonObject mongoDate(String iso) {
        String value = iso;
        if (value == null) {
            value = java.time.Instant.now().toString();
        }
        return new JsonObject().put("$date", value);
    }

    static String stripTags(String html) {
        return html == null ? "" : html.replaceAll("<[^>]*>", " ").replaceAll("\\s+", " ").trim();
    }

    private static String nonEmpty(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value;
    }

    private static JsonArray readItems(OeipImportContext context, String path) {
        try {
            Path p = context.getPackageRoot().resolve(path);
            if (!Files.isRegularFile(p)) {
                return new JsonArray();
            }
            return new JsonObject(new String(Files.readAllBytes(p), StandardCharsets.UTF_8))
                    .getJsonArray("items", new JsonArray());
        } catch (Exception e) {
            return new JsonArray();
        }
    }
}
