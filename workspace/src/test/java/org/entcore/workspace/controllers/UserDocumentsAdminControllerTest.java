package org.entcore.workspace.controllers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.util.Collections;

import org.entcore.common.folders.ElementQuery;
import org.junit.Test;

public class UserDocumentsAdminControllerTest {

	@Test
	public void laRequeteNePorteQueSurLeCompteVise() {
		final ElementQuery query = UserDocumentsAdminController.ownedBy("u1");
		assertEquals(Collections.singleton("u1"), query.getOwnerIds());
	}

	/**
	 * Sans propriétaire, le filtre disparaît de la requête Mongo : la purge viderait l'espace
	 * documentaire de toute la plate-forme.
	 */
	@Test
	public void unIdentifiantVideEstRefuse() {
		for (String userId : new String[] { null, "", "   " }) {
			try {
				UserDocumentsAdminController.ownedBy(userId);
				fail("identifiant accepté : [" + userId + "]");
			} catch (IllegalArgumentException expected) {
				assertEquals("user.id.required", expected.getMessage());
			}
		}
	}
}
