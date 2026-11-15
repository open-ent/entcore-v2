package org.entcore.interoperability.packs;

import fr.tech.openent.interoperability.model.Activity;
import fr.tech.openent.interoperability.model.ActivityType;
import fr.tech.openent.interoperability.model.Asset;
import fr.tech.openent.interoperability.model.ConversionReport;
import fr.tech.openent.interoperability.model.Course;
import fr.tech.openent.interoperability.model.Section;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Pack lu par {@link PackPackage#read} projeté en cours du modèle neutre de la bibliothèque
 * d'interopérabilité, pour l'exporter dans les formats du marché (Common Cartridge, SCORM, Moodle…).
 *
 * <p>Plan du cours produit, dans l'ordre où un enseignant parcourt un pack : la séquence (une
 * section par wiki, une page par page du wiki), les cartes mentales, les exercices (un quiz par
 * sujet), puis les fiches à imprimer (un fichier par fiche). Ce qui ne passe pas tel quel est
 * consigné dans le rapport de conversion, jamais travesti en silence.</p>
 */
public final class PackCourse {

    /** Ordre des applications dans le cours produit. */
    private static final List<String> ORDER = Arrays.asList("wiki", "mindmap", "exercizer", "workspace");

    private PackCourse() {
    }

    /**
     * @param descriptor descripteur rendu par {@link PackPackage#read} ; les fichiers qu'il
     *                   désigne doivent rester lisibles jusqu'à l'écriture du cours
     */
    public static Course convert(JsonObject descriptor, ConversionReport report) {
        final JsonObject pack = descriptor.getJsonObject("pack", new JsonObject());
        final String description = pack.getString("description");
        final Course course = new Course()
                .id(pack.getString("id"))
                .title(pack.getString("title"))
                .descriptionHtml(description == null ? null : "<p>" + escape(description) + "</p>")
                .language(pack.getString("language", "fr"))
                .sourceSystem("open-ent")
                .metadata("version", pack.getString("version"))
                .metadata("license", pack.getString("license"));

        final List<JsonObject> resources = new ArrayList<JsonObject>();
        for (Object o : descriptor.getJsonArray("resources", new JsonArray())) {
            resources.add((JsonObject) o);
        }
        // Tri stable : l'ordre des ressources d'une même application est celui du paquet.
        resources.sort(Comparator.comparingInt(r -> rank(r.getString("service"))));

        final PackExercises exercises = new PackExercises(report);
        Section maps = null;
        Section quizzes = null;
        Section sheets = null;
        int n = 0;
        for (JsonObject resource : resources) {
            final String service = resource.getString("service");
            final String title = resource.getString("title");
            final JsonObject document = resource.getJsonObject("document", new JsonObject());
            final String id = "r" + (++n);
            if ("wiki".equals(service)) {
                course.addSection(wiki(id, title, document, course.sections().size()));
            } else if ("mindmap".equals(service)) {
                if (maps == null) {
                    maps = section(course, "mindmaps", "Cartes mentales");
                }
                maps.addActivity(mindmap(id, title, document, maps.activities().size(), report));
            } else if ("exercizer".equals(service)) {
                final Activity quiz = exercises.toActivity(id, title, document);
                if (quiz != null) {
                    if (quizzes == null) {
                        quizzes = section(course, "exercises", "Exercices");
                    }
                    quizzes.addActivity(quiz.order(quizzes.activities().size()));
                }
            } else if ("workspace".equals(service) && resource.getString("file") != null) {
                if (sheets == null) {
                    sheets = section(course, "sheets", "Fiches à imprimer");
                }
                final Asset asset = asset(id, resource, document);
                course.addAsset(asset);
                sheets.addActivity(new Activity().id(id).type(ActivityType.FILE).title(title)
                        .order(sheets.activities().size()).addAssetRef(asset.id()).sourceType("workspace-file"));
            } else {
                report.dropped("pack.service.unsupported", "La ressource « " + title + " » ("
                        + service + ") n'a pas de projection dans un cours : elle n'est pas exportée.", title);
            }
        }
        return course;
    }

    private static Section section(Course course, String id, String title) {
        final Section section = new Section().id(id).title(title).order(course.sections().size());
        course.addSection(section);
        return section;
    }

    /** Un wiki devient une section ; ses pages visibles, des pages, dans l'ordre du wiki. */
    private static Section wiki(String id, String title, JsonObject document, int order) {
        final String description = document.getString("description");
        final Section section = new Section().id(id).title(title).order(order)
                .summaryHtml(description == null ? null : "<p>" + escape(description) + "</p>");
        final List<JsonObject> pages = new ArrayList<JsonObject>();
        for (Object o : document.getJsonArray("pages", new JsonArray())) {
            final JsonObject page = (JsonObject) o;
            if (page.getBoolean("isVisible", true)) {
                pages.add(page);
            }
        }
        pages.sort(Comparator.comparingInt(p -> p.getInteger("position", Integer.MAX_VALUE)));
        for (JsonObject page : pages) {
            section.addActivity(new Activity()
                    .id(id + "-" + (section.activities().size() + 1))
                    .type(ActivityType.PAGE)
                    .title(page.getString("title"))
                    .order(section.activities().size())
                    .contentHtml(page.getString("content", ""))
                    .sourceType("wiki-page"));
        }
        return section;
    }

    /**
     * Une carte mentale devient une page portant son plan (listes imbriquées) : aucun format cible
     * n'a de carte mentale, et le plan en garde tout le texte et la hiérarchie.
     */
    private static Activity mindmap(String id, String title, JsonObject document, int order, ConversionReport report) {
        final StringBuilder html = new StringBuilder();
        final String description = document.getString("description");
        if (description != null && !description.isEmpty()) {
            html.append("<p>").append(escape(description)).append("</p>");
        }
        try {
            final Element root = parse(document.getString("map", "")).getDocumentElement();
            final Element central = firstTopic(root);
            if (central != null) {
                html.append("<h2>").append(escape(central.getAttribute("text"))).append("</h2>");
                outline(central, html);
            }
            report.degraded("pack.mindmap.outline", "La carte mentale « " + title
                    + " » est exportée comme une page portant son plan : sa disposition graphique est perdue.", title);
        } catch (Exception e) {
            report.dropped("pack.mindmap.unreadable", "La carte mentale « " + title
                    + " » est illisible : seule sa description est exportée.", title);
        }
        return new Activity().id(id).type(ActivityType.PAGE).title(title).order(order)
                .contentHtml(html.toString()).sourceType("mindmap");
    }

    private static void outline(Element topic, StringBuilder html) {
        final List<Element> children = new ArrayList<Element>();
        for (Node child = topic.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element && "topic".equals(((Element) child).getTagName())) {
                children.add((Element) child);
            }
        }
        if (children.isEmpty()) {
            return;
        }
        children.sort(Comparator.comparingInt(PackCourse::topicOrder));
        html.append("<ul>");
        for (Element child : children) {
            html.append("<li>").append(escape(child.getAttribute("text")));
            outline(child, html);
            html.append("</li>");
        }
        html.append("</ul>");
    }

    private static int topicOrder(Element topic) {
        try {
            return Integer.parseInt(topic.getAttribute("order"));
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE;
        }
    }

    private static Element firstTopic(Element root) {
        for (Node child = root.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element && "topic".equals(((Element) child).getTagName())) {
                return (Element) child;
            }
        }
        return null;
    }

    /** XML d'une carte, sans DTD ni entité externe : il vient d'un paquet reçu. */
    private static Document parse(String xml) throws Exception {
        final DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        return factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
    }

    private static Asset asset(String id, JsonObject resource, JsonObject document) {
        final Path file = Paths.get(resource.getString("file"));
        String name = document.getString("name");
        if (name == null || name.trim().isEmpty()) {
            name = file.getFileName().toString();
        }
        long size;
        try {
            size = Files.size(file);
        } catch (java.io.IOException e) {
            size = -1L;
        }
        return new Asset().id(id + "-file").fileName(name)
                .mimeType(document.getString("contentType", "application/octet-stream"))
                .size(size).sourcePath(file.getFileName().toString())
                .content(() -> Files.newInputStream(file));
    }

    private static int rank(String service) {
        final int i = ORDER.indexOf(service);
        return i < 0 ? ORDER.size() : i;
    }

    private static String escape(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
