package org.entcore.directory.utils;

import io.vertx.core.json.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Clause {@code SET} d'une mise à jour de propriétés à partir d'un objet JSON, avec des paramètres au format
 * OpenCypher ({@code $nom}). La version de {@code Neo4jUtils} fournie par la bibliothèque commune écrit l'ancienne
 * syntaxe {@code {nom}}, refusée par ArcadeDB (« legacy parameter '{nom}' is no longer supported »).
 */
public final class CypherSet {

	private CypherSet() {}

	/**
	 * Ex. {@code set("u", {displayName: "A", mobile: "06"})} → {@code u.displayName = $displayName,u.mobile = $mobile}.
	 * Les clés listées dans {@code excluded} sont ignorées ; le nom d'un paramètre ne garde que ses caractères de mot.
	 */
	public static String set(final String alias, final JsonObject json, final String... excluded) {
		final List<String> skip = java.util.Arrays.asList(excluded);
		final List<String> parts = new ArrayList<>();
		for (final String key : json.fieldNames()) {
			if (skip.contains(key)) continue;
			final String param = key.replaceAll("\\W+", "");
			parts.add(alias + "." + param + " = $" + param);
		}
		// espace final : les appelants enchaînent directement la clause suivante (RETURN, WHERE…)
		return String.join(",", parts) + " ";
	}
}
