package org.entcore.directory.utils;

import io.vertx.core.json.JsonObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class CypherSetTest {

	@Test
	public void writesOpenCypherParameters() {
		final JsonObject json = new JsonObject().put("displayName", "A").put("mobile", "06");
		assertEquals("u.displayName = $displayName,u.mobile = $mobile ", CypherSet.set("u", json));
	}

	@Test
	public void skipsExcludedKeysAndKeepsOnlyWordCharactersInParameterNames() {
		final JsonObject json = new JsonObject().put("id", "1").put("home-phone", "0102");
		assertEquals("u.homephone = $homephone ", CypherSet.set("u", json, "id"));
	}

	@Test
	public void emptyObjectGivesEmptyClause() {
		assertEquals(" ", CypherSet.set("u", new JsonObject()));
	}
}
