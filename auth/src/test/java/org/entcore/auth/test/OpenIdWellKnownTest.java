package org.entcore.auth.test;

import org.entcore.auth.controllers.OpenIdConnectController;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/** Adresses du document de découverte : le navigateur va à l'adresse publique, les serveurs à celle de leur requête. */
public class OpenIdWellKnownTest {

	@Test
	public void browserUsesTheConfiguredIssuer() {
		assertEquals("http://localhost:8090",
				OpenIdConnectController.browserBaseUrl("http://localhost:8090", "http://nextcloud.local:8090"));
	}

	@Test
	public void trailingSlashOfTheIssuerIsDropped() {
		assertEquals("https://www.ent-scolaire.fr",
				OpenIdConnectController.browserBaseUrl("https://www.ent-scolaire.fr/", "https://www.ent-scolaire.fr"));
	}

	@Test
	public void withoutIssuerTheRequestAddressIsUsed() {
		assertEquals("http://nextcloud.local:8090", OpenIdConnectController.browserBaseUrl(null, "http://nextcloud.local:8090"));
		assertEquals("http://nextcloud.local:8090", OpenIdConnectController.browserBaseUrl("  ", "http://nextcloud.local:8090"));
	}
}
