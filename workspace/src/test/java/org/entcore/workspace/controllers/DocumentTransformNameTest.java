package org.entcore.workspace.controllers;

import io.vertx.core.json.JsonObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/** Nom et extension du document produit par une transformation. */
public class DocumentTransformNameTest {

	private static JsonObject source(String name, String filename) {
		return new JsonObject().put("name", name).put("metadata", new JsonObject().put("filename", filename));
	}

	@Test
	public void lExtensionDuResultatRemplaceCelleDeLaSource() {
		assertEquals("Sujet.pdf", DocumentTransformController.resultName(null, source("Sujet.docx", "Sujet.docx"), "pdf"));
		assertEquals("Sujet (protégé).pdf", DocumentTransformController.resultName("Sujet (protégé).pdf", source("Sujet.pdf", "Sujet.pdf"), "pdf"));
	}

	@Test
	public void leNomProposeEstAssaini() {
		assertEquals("a b c.pdf", DocumentTransformController.resultName("a/b\\c", source("x.pdf", "x.pdf"), "pdf"));
		assertEquals("document.zip", DocumentTransformController.resultName("  ", source("", ""), "zip"));
		assertEquals("Fusion v1.2 finale.pdf", DocumentTransformController.resultName("Fusion v1.2 finale", source("a.pdf", "a.pdf"), "pdf"));
	}

	@Test
	public void lExtensionSeLitDansLeNomDeFichier() {
		assertEquals("pdf", DocumentTransformController.extension(source("Sans extension", "Scan.PDF")));
		assertEquals("", DocumentTransformController.extension(source("x", "x")));
	}
}
