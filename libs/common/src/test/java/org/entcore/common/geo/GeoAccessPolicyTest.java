package org.entcore.common.geo;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.*;

/** Zones géographiques autorisées : qui est visé, quand une connexion est hors zone. */
public class GeoAccessPolicyTest {

    private static JsonObject platform(String mode, String... countries) {
        return new JsonObject().put("_id", "platform").put("scope", "platform").put("enabled", true)
                .put("mode", mode).put("allowedCountries", new JsonArray(Arrays.asList(countries)));
    }

    private static JsonObject admc() {
        return new JsonObject().put("type", "Personnel")
                .put("functions", new JsonObject().put("SUPER_ADMIN", new JsonObject()))
                .put("structures", new JsonArray().add("s1"));
    }

    private static JsonObject student(String structureId) {
        return new JsonObject().put("type", "Student").put("functions", new JsonObject())
                .put("structures", new JsonArray().add(structureId));
    }

    private static JsonObject geo(String country) {
        return new JsonObject().put("country", country);
    }

    @Test
    public void targetsIncludeAdminFunctions() {
        assertTrue(GeoAccessPolicy.targetsOf(admc()).containsAll(Arrays.asList("Personnel", "ADMC")));
        final JsonObject adml = new JsonObject().put("type", "Teacher")
                .put("functions", new JsonObject().put("ADMIN_LOCAL", new JsonObject()));
        assertTrue(GeoAccessPolicy.targetsOf(adml).contains("ADML"));
        final JsonObject collectivite = new JsonObject().put("type", "Personnel")
                .put("functions", new JsonObject().put("ADMIN_COLLECTIVITE", new JsonObject()));
        assertTrue(GeoAccessPolicy.targetsOf(collectivite).contains("ADMCOL"));
        assertFalse(GeoAccessPolicy.targetsOf(collectivite).contains("ADML"));
    }

    @Test
    public void collectivityTargetAloneIsCovered() {
        final JsonObject policy = platform("observe", "FR").put("profiles", new JsonArray().add("ADMCOL"));
        final JsonObject referent = new JsonObject().put("type", "Personnel")
                .put("functions", new JsonObject().put("ADMIN_COLLECTIVITE", new JsonObject()))
                .put("structures", new JsonArray().add("s1"));
        assertTrue(GeoAccessPolicy.evaluate(Collections.singletonList(policy), referent, "8.8.8.8", geo("US"))
                .getBoolean("outOfZone"));
        assertFalse(GeoAccessPolicy.evaluate(Collections.singletonList(policy), admc(), "8.8.8.8", geo("US"))
                .getBoolean("outOfZone"));
    }

    @Test
    public void inZoneWhenCountryAllowed() {
        final JsonObject d = GeoAccessPolicy.evaluate(Collections.singletonList(platform("mfa", "FR")),
                admc(), "80.9.155.84", geo("FR"));
        assertFalse(d.getBoolean("outOfZone"));
    }

    @Test
    public void outOfZoneRequiresMfa() {
        final JsonObject d = GeoAccessPolicy.evaluate(Collections.singletonList(platform("mfa", "FR", "BE")),
                admc(), "8.8.8.8, 10.0.0.1", geo("US"));
        assertTrue(d.getBoolean("outOfZone"));
        assertTrue(d.getBoolean("mfa"));
        assertEquals("8.8.8.8", d.getString("ip"));
    }

    @Test
    public void observeModeDoesNotRequireMfa() {
        final JsonObject d = GeoAccessPolicy.evaluate(Collections.singletonList(platform("observe", "FR")),
                admc(), "8.8.8.8", geo("US"));
        assertTrue(d.getBoolean("outOfZone"));
        assertFalse(d.getBoolean("mfa"));
    }

    @Test
    public void profileFilter() {
        final JsonObject policy = platform("mfa", "FR").put("profiles", new JsonArray().add("ADMC").add("ADML"));
        assertTrue(GeoAccessPolicy.evaluate(Collections.singletonList(policy), admc(), "8.8.8.8", geo("US"))
                .getBoolean("outOfZone"));
        assertFalse(GeoAccessPolicy.evaluate(Collections.singletonList(policy), student("s1"), "8.8.8.8", geo("US"))
                .getBoolean("outOfZone"));
    }

    @Test
    public void structureScope() {
        final JsonObject policy = new JsonObject().put("scope", "structure").put("enabled", true).put("mode", "mfa")
                .put("structureIds", new JsonArray().add("head").add("attached"))
                .put("allowedCountries", new JsonArray().add("FR"));
        assertTrue(GeoAccessPolicy.evaluate(Collections.singletonList(policy), student("attached"), "8.8.8.8", geo("US"))
                .getBoolean("outOfZone"));
        assertFalse(GeoAccessPolicy.evaluate(Collections.singletonList(policy), student("other"), "8.8.8.8", geo("US"))
                .getBoolean("outOfZone"));
    }

    @Test
    public void disabledPolicyIgnored() {
        final JsonObject policy = platform("mfa", "FR").put("enabled", false);
        assertFalse(GeoAccessPolicy.evaluate(Collections.singletonList(policy), admc(), "8.8.8.8", geo("US"))
                .getBoolean("outOfZone"));
    }

    @Test
    public void allowedCidrWinsOverCountry() {
        final JsonObject policy = platform("mfa", "FR").put("allowedCidrs", new JsonArray().add("8.8.8.0/24"));
        assertFalse(GeoAccessPolicy.evaluate(Collections.singletonList(policy), admc(), "8.8.8.8", geo("US"))
                .getBoolean("outOfZone"));
    }

    @Test
    public void privateAndUnknownAddressesNeverBlock() {
        final JsonObject policy = platform("mfa", "FR");
        assertFalse(GeoAccessPolicy.evaluate(Collections.singletonList(policy), admc(), "192.168.1.10", null)
                .getBoolean("outOfZone"));
        // Base indisponible : aucune conclusion
        assertFalse(GeoAccessPolicy.evaluate(Collections.singletonList(policy), admc(), "8.8.8.8", null)
                .getBoolean("outOfZone"));
    }

    @Test
    public void cidrOnlyPolicy() {
        final JsonObject policy = new JsonObject().put("scope", "platform").put("enabled", true).put("mode", "mfa")
                .put("allowedCidrs", new JsonArray().add("2001:db8::/32"));
        assertFalse(GeoAccessPolicy.evaluate(Collections.singletonList(policy), admc(), "2001:db8::1", null)
                .getBoolean("outOfZone"));
        assertTrue(GeoAccessPolicy.evaluate(Collections.singletonList(policy), admc(), "8.8.8.8", null)
                .getBoolean("outOfZone"));
    }

    @Test
    public void emptyPolicyRestrictsNothing() {
        final JsonObject policy = new JsonObject().put("scope", "platform").put("enabled", true).put("mode", "mfa");
        assertFalse(GeoAccessPolicy.evaluate(Collections.singletonList(policy), admc(), "8.8.8.8", geo("US"))
                .getBoolean("outOfZone"));
    }

    @Test
    public void cidrHelpers() {
        assertTrue(GeoAccessPolicy.inCidr("10.1.2.3", "10.0.0.0/8"));
        assertFalse(GeoAccessPolicy.inCidr("11.1.2.3", "10.0.0.0/8"));
        assertTrue(GeoAccessPolicy.inCidr("80.9.155.84", "80.9.155.84"));
        assertFalse(GeoAccessPolicy.inCidr("80.9.155.84", "example.org"));
        assertTrue(GeoAccessPolicy.isValidCidr("192.168.0.0/16"));
        assertFalse(GeoAccessPolicy.isValidCidr("192.168.0.0/33"));
        assertFalse(GeoAccessPolicy.isValidCidr("pas une ip"));
    }

    @Test
    public void locatorNormalizesAndHandlesMissingDatabase() {
        assertEquals("8.8.8.8", GeoIpLocator.normalizeIp(" 8.8.8.8 , 10.0.0.1"));
        assertEquals("8.8.8.8", GeoIpLocator.normalizeIp("8.8.8.8:443"));
        assertEquals("2001:db8::1", GeoIpLocator.normalizeIp("[2001:db8::1]:443"));
        assertNull(GeoIpLocator.normalizeIp(" "));
        final GeoIpLocator locator = new GeoIpLocator("/nonexistent/geo.mmdb");
        assertFalse(locator.isAvailable());
        assertNull(locator.locate("8.8.8.8"));
        assertTrue(locator.locate("10.0.0.1").getBoolean("private"));
        assertNull(locator.locate("not-an-ip.example.org"));
    }
}
