package org.entcore.auth.utils;

import io.vertx.core.json.JsonObject;
import org.junit.Test;

import static org.junit.Assert.*;

public class PlatformVersionTest {

	@Test
	public void versionDateAndCommitAreReadable() {
		final JsonObject v = PlatformVersion.fromAttributes("6.16.14-patched", "2026-11-04T08:20:27Z", "3065e2d1");
		assertEquals("6.16.14", v.getString("version"));
		assertEquals("04/11/2026", v.getString("built"));
		assertEquals("3065e2d", v.getString("commit"));
	}

	@Test
	public void missingOrOddValuesAreLeftOut() {
		assertTrue(PlatformVersion.fromAttributes(null, "2026-11-04", "abc").isEmpty());
		final JsonObject v = PlatformVersion.fromAttributes("6.16.14", "hier", "<script>");
		assertNull(v.getString("built"));
		assertNull(v.getString("commit"));
	}
}
