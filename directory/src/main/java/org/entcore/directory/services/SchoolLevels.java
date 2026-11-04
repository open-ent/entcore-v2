package org.entcore.directory.services;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Niveaux d'enseignement qu'un établissement sert : maternelle, élémentaire, collège, lycée (général
 * et technologique), lycée professionnel, supérieur. Un établissement privé peut en servir plusieurs.
 *
 * Le vocabulaire est celui des groupes de niveaux d'Exercices ({@code level_group}) ; la même table
 * de déduction est recopiée dans Exercices (Java) et dans le dashboard (TypeScript) — toute
 * modification se reporte aux trois.
 *
 * Les niveaux d'un établissement sont ceux que l'administrateur a choisis ({@code s.schoolLevels}),
 * sinon ceux que l'on déduit de sa nature AAF ({@code s.type}, « ECOLE DE NIVEAU ELEMENTAIRE »,
 * « COLLEGE », « LYCEE PROFESSIONNEL »…).
 */
public final class SchoolLevels {

	public static final List<String> ALL = Collections.unmodifiableList(Arrays.asList(
			"maternelle", "elementaire", "college", "lycee", "lycee_pro", "superieur"));

	private static final Set<String> PRIMARY = new LinkedHashSet<>(Arrays.asList("maternelle", "elementaire"));

	private SchoolLevels() {}

	/** Les niveaux connus de cette liste, sans doublon, dans l'ordre de la scolarité ; null si elle n'est pas lisible. */
	public static List<String> validate(final JsonArray values) {
		if (values == null) return null;
		final Set<String> asked = new LinkedHashSet<>();
		for (Object value : values) {
			if (!(value instanceof String) || !ALL.contains(value)) return null;
			asked.add((String) value);
		}
		final List<String> ordered = new ArrayList<>();
		for (String level : ALL) {
			if (asked.contains(level)) ordered.add(level);
		}
		return ordered;
	}

	/** Les niveaux déduits de la nature AAF d'un établissement ; vide si elle ne dit rien. */
	public static List<String> fromType(final String type) {
		if (type == null || type.trim().isEmpty()) return Collections.emptyList();
		final String t = Normalizer.normalize(type, Normalizer.Form.NFD)
				.replaceAll("\\p{M}", "").toUpperCase(Locale.ROOT);
		if (t.contains("MATERNELLE")) return Collections.singletonList("maternelle");
		if (t.contains("ECOLE") || t.contains("ELEMENTAIRE") || t.contains("PRIMAIRE")) {
			return Arrays.asList("maternelle", "elementaire");
		}
		if (t.contains("COLLEGE") || t.contains("SEGPA") || t.contains("EREA")
				|| t.contains("ENSEIGNT ADAPTE") || t.contains("ENSEIGNEMENT ADAPTE")) {
			return Collections.singletonList("college");
		}
		if (t.contains("LYCEE")) {
			if (t.contains("POLYVALENT")) return Arrays.asList("lycee", "lycee_pro");
			if (t.contains("PROFESSIONNEL")) return Collections.singletonList("lycee_pro");
			return Collections.singletonList("lycee");
		}
		return Collections.emptyList();
	}

	/** Les clés de la correspondance niveau → thème : primaire, collège, lycée. */
	public static final List<String> THEME_KEYS = Collections.unmodifiableList(Arrays.asList("primaire", "college", "lycee"));

	/**
	 * Une correspondance niveau → thème lisible ({@code primaire}, {@code college}, {@code lycee} vers
	 * un nom de thème), sans les entrées vides ; null si une clé ou un nom n'est pas acceptable.
	 */
	public static JsonObject validateLevelThemes(final JsonObject asked) {
		final JsonObject valid = new JsonObject();
		for (String key : asked.fieldNames()) {
			final Object theme = asked.getValue(key);
			if (!THEME_KEYS.contains(key)) return null;
			if (theme == null || "".equals(theme)) continue;
			if (!(theme instanceof String) || !((String) theme).matches("^[A-Za-z0-9_-]{1,64}$")) return null;
			valid.put(key, theme);
		}
		return valid;
	}

	/**
	 * Les degrés au sens d'entcore ({@code levelsOfEducation} : 1 = premier, 2 = second) de ces
	 * niveaux, pour que les filtres existants de la console suivent le choix de l'administrateur.
	 */
	public static List<Integer> degrees(final List<String> levels) {
		final List<Integer> degrees = new ArrayList<>();
		if (levels.stream().anyMatch(PRIMARY::contains)) degrees.add(1);
		if (levels.stream().anyMatch(level -> !PRIMARY.contains(level))) degrees.add(2);
		return degrees;
	}
}
