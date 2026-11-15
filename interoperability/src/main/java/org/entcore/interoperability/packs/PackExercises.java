package org.entcore.interoperability.packs;

import fr.tech.openent.interoperability.model.Activity;
import fr.tech.openent.interoperability.model.ActivityType;
import fr.tech.openent.interoperability.model.Answer;
import fr.tech.openent.interoperability.model.ConversionReport;
import fr.tech.openent.interoperability.model.Question;
import fr.tech.openent.interoperability.model.QuestionType;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Sujet d'exercices d'un pack (document natif d'exercizer : {@code {subject, grains}}) projeté en
 * activité de quiz du modèle de cours neutre, pour l'export du pack.
 *
 * <p>Même projection que {@code SubjectToCourseConverter} du module exercizer, qui sert l'export
 * d'un sujet depuis l'application : un même grain doit donner la même question, d'où l'on
 * exporte. Seule l'entrée diffère : un grain de pack porte {@code grain_type_id} (la constante de
 * base de données d'exercizer) et non le nom du type.</p>
 */
final class PackExercises {

    /** Types de grain d'exercizer par identifiant, tels que les sème {@code sql/002-exercizer.sql}. */
    private static final Map<Integer, String> GRAIN_TYPES = new LinkedHashMap<Integer, String>();

    static {
        GRAIN_TYPES.put(3, "statement");
        GRAIN_TYPES.put(4, "simple_answer");
        GRAIN_TYPES.put(5, "open_answer");
        GRAIN_TYPES.put(6, "multiple_answers");
        GRAIN_TYPES.put(7, "qcm");
        GRAIN_TYPES.put(8, "association");
        GRAIN_TYPES.put(9, "order_by");
        GRAIN_TYPES.put(10, "text_to_fill");
        GRAIN_TYPES.put(11, "area_select");
        GRAIN_TYPES.put(12, "area_select_image");
    }

    /** Un trou dans le contenu d'un texte à trous, tel que l'écrit exercizer. */
    private static final Pattern FILL_ZONE =
            Pattern.compile("<fill-zone[^>]*zone-id=\"(\\d+)\"[^>]*>(?:.*?</fill-zone>)?", Pattern.DOTALL);

    private final ConversionReport report;

    PackExercises(ConversionReport report) {
        this.report = report;
    }

    /**
     * @param id       identifiant de l'activité dans le cours produit
     * @param title    titre de la ressource du pack
     * @param document document natif {@code {subject, grains}}
     * @return l'activité, ou {@code null} si aucun grain n'est exportable
     */
    Activity toActivity(String id, String title, JsonObject document) {
        final JsonObject subject = document.getJsonObject("subject", new JsonObject());
        final Activity activity = new Activity()
                .id(id)
                .type(ActivityType.QUIZ)
                .title(title)
                .contentHtml(subject.getString("description"))
                .sourceType("exercizer-subject");
        final JsonArray grains = document.getJsonArray("grains", new JsonArray());
        for (int i = 0; i < grains.size(); i++) {
            final Object raw = grains.getValue(i);
            if (raw instanceof JsonObject) {
                final Question question = toQuestion((JsonObject) raw, title, i + 1);
                if (question != null) {
                    activity.addQuestion(question);
                }
            }
        }
        if (activity.questions().isEmpty()) {
            report.dropped("pack.exercises.empty",
                    "Le sujet « " + title + " » ne contient aucun grain exportable : il n'est pas exporté.", title);
            return null;
        }
        return activity;
    }

    private Question toQuestion(JsonObject grain, String subject, int rank) {
        final String type = GRAIN_TYPES.get(grain.getInteger("grain_type_id", -1));
        final JsonObject data = grain.getJsonObject("grain_data", new JsonObject());
        final JsonObject customData = data.getJsonObject("custom_data", new JsonObject());
        final String location = "« " + subject + " », grain " + rank + (type == null ? "" : " (" + type + ")");

        if ("area_select".equals(type) || "area_select_image".equals(type)) {
            // Les zones à cliquer reposent sur des coordonnées dans une image : aucun des formats
            // cibles ne sait les représenter, et les aplatir en liste changerait l'exercice.
            report.dropped("exercizer.area.not.exportable",
                    "Le grain à zones de " + location + " n'a d'équivalent dans aucun format cible : "
                            + "il n'est pas exporté.", location);
            return null;
        }

        final Question question = new Question()
                .id(String.valueOf(rank))
                .title(data.getString("title"))
                .sourceType(type)
                .defaultMark(readDouble(data.getValue("max_score"), 1d))
                .generalFeedbackHtml(data.getString("answer_explanation"));

        if ("statement".equals(type)) {
            // Piège du modèle : pour un énoncé, le texte vit dans custom_data.statement.
            return question.type(QuestionType.STATEMENT)
                    .promptHtml(customData.getString("statement", data.getString("statement", "")));
        }
        question.promptHtml(data.getString("statement", ""));
        if ("qcm".equals(type)) {
            return fillQcm(question, customData);
        }
        if ("simple_answer".equals(type)) {
            return question.type(QuestionType.SHORT_ANSWER)
                    .addAnswer(new Answer().textHtml(customData.getString("correct_answer", "")).fraction(1d));
        }
        if ("multiple_answers".equals(type)) {
            final JsonArray answers = customData.getJsonArray("correct_answer_list", new JsonArray());
            for (int i = 0; i < answers.size(); i++) {
                question.addAnswer(new Answer().textHtml(answers.getJsonObject(i).getString("text", "")).fraction(1d));
            }
            return question.type(QuestionType.SHORT_ANSWER).multiple(answers.size() > 1);
        }
        if ("open_answer".equals(type)) {
            if (!customData.getString("starting_answer", "").isEmpty()
                    || !customData.getString("model_answer", "").isEmpty()) {
                report.dropped("exercizer.openanswer.start.not.exportable",
                        "La réponse de départ et le corrigé de la réponse ouverte de " + location
                                + " ne sont pas exportés : la question l'est sans eux.", location);
            }
            return question.type(QuestionType.ESSAY);
        }
        if ("association".equals(type)) {
            final JsonArray pairs = customData.getJsonArray("correct_answer_list", new JsonArray());
            for (int i = 0; i < pairs.size(); i++) {
                final JsonObject pair = pairs.getJsonObject(i);
                question.addAnswer(new Answer().textHtml(pair.getString("text_left", ""))
                        .matchHtml(pair.getString("text_right", "")).fraction(1d));
            }
            return question.type(QuestionType.MATCHING);
        }
        if ("order_by".equals(type)) {
            final JsonArray items = customData.getJsonArray("correct_answer_list", new JsonArray());
            for (int i = 0; i < items.size(); i++) {
                final JsonObject item = items.getJsonObject(i);
                question.addAnswer(new Answer().textHtml(item.getString("text", ""))
                        .order(item.getInteger("order_by", i + 1)).fraction(1d));
            }
            return question.type(QuestionType.ORDERING);
        }
        if ("text_to_fill".equals(type)) {
            return fillCloze(question, customData, location);
        }
        report.dropped("exercizer.grain.unsupported",
                "Le grain de " + location + " est d'un type inconnu : il n'est pas exporté.", location);
        return null;
    }

    private static Question fillQcm(Question question, JsonObject customData) {
        final JsonArray answers = customData.getJsonArray("correct_answer_list", new JsonArray());
        int correct = 0;
        for (int i = 0; i < answers.size(); i++) {
            if (answers.getJsonObject(i).getBoolean("isChecked", false)) {
                correct++;
            }
        }
        for (int i = 0; i < answers.size(); i++) {
            final JsonObject answer = answers.getJsonObject(i);
            final boolean checked = answer.getBoolean("isChecked", false);
            // Le crédit se répartit entre les bonnes réponses, comme dans l'export d'exercizer.
            question.addAnswer(new Answer().textHtml(answer.getString("text", ""))
                    .fraction(checked && correct > 0 ? 1d / correct : 0d));
        }
        final boolean multiple = correct > 1 || customData.getBoolean("multipleAnswers", false);
        return question.multiple(multiple).type(multiple ? QuestionType.MULTIPLE_CHOICE : QuestionType.SINGLE_CHOICE);
    }

    /**
     * Texte à trous : les balises {@code <fill-zone zone-id="N">} redeviennent des marqueurs
     * {@code [[n]]}, numérotés à partir de 1 comme le veut le modèle neutre.
     */
    private Question fillCloze(Question question, JsonObject customData, String location) {
        final String html = customData.getString("htmlContent", "");
        final JsonArray zones = customData.getJsonArray("zones", new JsonArray());
        if (zones.isEmpty()) {
            report.dropped("exercizer.cloze.no.zone",
                    "Le texte à trous de " + location + " n'a aucune zone : il n'est pas exporté.", location);
            return null;
        }
        final StringBuffer rewritten = new StringBuffer();
        final Matcher matcher = FILL_ZONE.matcher(html);
        final Map<String, Integer> gapByZoneId = new LinkedHashMap<String, Integer>();
        while (matcher.find()) {
            Integer gap = gapByZoneId.get(matcher.group(1));
            if (gap == null) {
                gap = gapByZoneId.size() + 1;
                gapByZoneId.put(matcher.group(1), gap);
            }
            matcher.appendReplacement(rewritten, "[[" + gap + "]]");
        }
        matcher.appendTail(rewritten);
        if (gapByZoneId.isEmpty()) {
            report.degraded("exercizer.cloze.markup.unreadable",
                    "Le texte à trous de " + location + " ne porte pas de balise de zone reconnaissable : "
                            + "son texte est exporté sans ses trous.", location);
        }
        for (int i = 0; i < zones.size(); i++) {
            final JsonObject zone = zones.getJsonObject(i);
            final Integer gap = gapByZoneId.get(String.valueOf(zone.getValue("id")));
            question.addAnswer(new Answer().textHtml(zone.getString("answer", ""))
                    .gap(gap == null ? i + 1 : gap).fraction(1d));
        }
        return question.type(QuestionType.CLOZE).promptHtml(rewritten.toString()).multiple(zones.size() > 1);
    }

    private static double readDouble(Object value, double fallback) {
        try {
            final double parsed = value instanceof Number ? ((Number) value).doubleValue()
                    : value instanceof String ? Double.parseDouble((String) value) : fallback;
            return parsed > 0d ? parsed : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
