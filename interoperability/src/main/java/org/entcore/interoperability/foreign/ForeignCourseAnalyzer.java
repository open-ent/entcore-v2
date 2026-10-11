package org.entcore.interoperability.foreign;

import fr.tech.openent.interoperability.CourseFormat;
import fr.tech.openent.interoperability.CourseFormats;
import fr.tech.openent.interoperability.ReadOptions;
import fr.tech.openent.interoperability.io.ArchiveLimits;
import fr.tech.openent.interoperability.model.Activity;
import fr.tech.openent.interoperability.model.ConversionReport;
import fr.tech.openent.interoperability.model.Course;
import fr.tech.openent.interoperability.model.Notice;
import fr.tech.openent.interoperability.model.Section;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Analyse d'un paquet déposé par un enseignant, avant tout import.
 *
 * Rend ce que le paquet contient ET ce qui serait perdu à l'import. C'est la même doctrine que
 * l'analyse OEIP : l'essai à blanc est le défaut, et l'usager voit le rapport de fidélité avant
 * de valider. Un convertisseur qui échoue en silence est pire qu'un convertisseur qui refuse.
 *
 * La lecture d'un paquet est bloquante — dépaquetage, analyse XML, empreintes — et ne doit donc
 * jamais s'exécuter sur la boucle d'événements.
 */
public class ForeignCourseAnalyzer {

    private final Vertx vertx;
    private final ArchiveLimits limits;

    public ForeignCourseAnalyzer(Vertx vertx, long maxPackageSize) {
        this.vertx = vertx;
        this.limits = maxPackageSize > 0
                ? ArchiveLimits.DEFAULT.copy().maxTotalBytes(maxPackageSize)
                : ArchiveLimits.DEFAULT;
    }

    /**
     * @param path chemin du paquet déposé
     * @return l'analyse, ou un échec si le format n'est pas reconnu — mieux vaut refuser que
     *         deviner.
     */
    public Future<JsonObject> analyze(final String path) {
        final Promise<JsonObject> promise = Promise.promise();
        vertx.executeBlocking(new io.vertx.core.Handler<Promise<JsonObject>>() {
            @Override
            public void handle(Promise<JsonObject> blocking) {
                try {
                    blocking.complete(analyzeBlocking(Paths.get(path)));
                } catch (IOException e) {
                    blocking.fail(e);
                }
            }
        }, false, promise);
        return promise.future();
    }

    JsonObject analyzeBlocking(Path file) throws IOException {
        CourseFormat format = CourseFormats.detect(file);
        if (format == null) {
            throw new IOException("interoperability.error.format.unknown");
        }

        ConversionReport report = new ConversionReport();
        // Les binaires ne sont pas lus : une analyse doit être rapide, et les octets
        // n'apprennent rien sur la structure du cours.
        ReadOptions options = ReadOptions.DEFAULT.copy()
                .limits(limits)
                .includeBinaries(false);

        Course course = CourseFormats.read(file, format, options, report);
        try {
            return new JsonObject()
                    .put("format", format.id())
                    .put("formatLabel", format.label())
                    .put("sourceSystem", course.sourceSystem())
                    .put("sourceVersion", course.sourceVersion())
                    .put("title", course.title())
                    .put("counts", counts(course))
                    .put("sections", outline(course))
                    .put("fidelity", report.fidelity())
                    .put("notices", notices(report));
        } finally {
            course.close();
        }
    }

    private JsonObject counts(Course course) {
        Map<String, Integer> byType = new LinkedHashMap<String, Integer>();
        for (Activity activity : course.allActivities()) {
            String key = activity.type().name().toLowerCase();
            Integer current = byType.get(key);
            byType.put(key, current == null ? 1 : current + 1);
        }
        JsonObject activities = new JsonObject();
        for (Map.Entry<String, Integer> entry : byType.entrySet()) {
            activities.put(entry.getKey(), entry.getValue());
        }
        return new JsonObject()
                .put("sections", course.sections().size())
                .put("activities", course.allActivities().size())
                .put("questions", course.allQuestions().size())
                .put("files", course.assets().size())
                .put("activitiesByType", activities);
    }

    private JsonArray outline(Course course) {
        JsonArray sections = new JsonArray();
        for (Section section : course.sections()) {
            JsonArray activities = new JsonArray();
            for (Activity activity : section.activities()) {
                activities.add(new JsonObject()
                        .put("title", activity.title())
                        .put("type", activity.type().name().toLowerCase())
                        .put("sourceType", activity.sourceType())
                        .put("questions", activity.questions().size()));
            }
            sections.add(new JsonObject()
                    .put("title", section.title())
                    .put("activities", activities));
        }
        return sections;
    }

    /**
     * Les observations de la conversion, telles quelles.
     *
     * Rien n'est filtré ni agrégé : c'est précisément la liste de ce que l'enseignant doit voir
     * avant de décider. Masquer les notices « informatives » reviendrait à décider à sa place.
     */
    private JsonArray notices(ConversionReport report) {
        JsonArray notices = new JsonArray();
        for (Notice notice : report.notices()) {
            notices.add(new JsonObject()
                    .put("level", notice.level().name().toLowerCase())
                    .put("code", notice.code())
                    .put("message", notice.message())
                    .put("location", notice.location()));
        }
        return notices;
    }
}
