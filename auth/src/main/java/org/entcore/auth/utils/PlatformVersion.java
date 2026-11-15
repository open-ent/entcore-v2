package org.entcore.auth.utils;

import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;

import java.io.InputStream;
import java.net.URL;
import java.util.Enumeration;
import java.util.jar.Attributes;
import java.util.jar.Manifest;

/**
 * La version de la plateforme affichée au pied de la page de connexion, lue dans le MANIFEST du module
 * auth (posé au build : Implementation-Version, Build-Time, SCM-Commit-Id). Le socle est publié d'un
 * bloc : la version d'auth est celle de la plateforme.
 */
public final class PlatformVersion {

	private static final Logger log = LoggerFactory.getLogger(PlatformVersion.class);
	private static volatile JsonObject cached;

	private PlatformVersion() {}

	/** {name, version, built (jj/mm/aaaa), commit}, ou null si le MANIFEST est introuvable. */
	public static JsonObject get(final String name) {
		JsonObject manifest = cached;
		if (manifest == null) {
			manifest = read();
			cached = manifest;
		}
		if (manifest.isEmpty()) return null;
		return manifest.copy().put("name", name);
	}

	private static JsonObject read() {
		try {
			final Enumeration<URL> manifests = PlatformVersion.class.getClassLoader().getResources("META-INF/MANIFEST.MF");
			while (manifests.hasMoreElements()) {
				try (InputStream in = manifests.nextElement().openStream()) {
					final Attributes attributes = new Manifest(in).getMainAttributes();
					if (!"auth".equals(attributes.getValue("Implementation-Title"))) continue;
					return fromAttributes(attributes.getValue("Implementation-Version"),
							attributes.getValue("Build-Time"), attributes.getValue("SCM-Commit-Id"));
				}
			}
		} catch (Exception e) {
			log.warn("[Auth] platform version unreadable", e);
		}
		return new JsonObject();
	}

	static JsonObject fromAttributes(final String version, final String buildTime, final String commit) {
		final JsonObject out = new JsonObject();
		if (version == null || version.isEmpty()) return out;
		out.put("version", version.replaceFirst("-patched$", ""));
		// 2026-11-04T08:20:27Z → 04/11/2026
		if (buildTime != null && buildTime.matches("\\d{4}-\\d{2}-\\d{2}.*")) {
			out.put("built", buildTime.substring(8, 10) + "/" + buildTime.substring(5, 7) + "/" + buildTime.substring(0, 4));
		}
		if (commit != null && commit.matches("[0-9a-f]{7,40}")) out.put("commit", commit.substring(0, 7));
		return out;
	}
}
