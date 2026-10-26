package org.entcore.directory.pim;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class PimPolicyTest {

    private static final long NOW = 1_800_000_000_000L;
    private static final long DAY = 24L * 3600 * 1000;

    private final PimPolicy policy = new PimPolicy(new JsonObject().put("pim", new JsonObject()
            .put("require-expiry", new JsonArray().add("ADMIN_LOCAL").add("SUPER_ADMIN"))
            .put("max-days", 90)
            .put("require-reason", true)));

    @Test
    public void withoutConfigurationNothingIsRequired() {
        assertNull(new PimPolicy(new JsonObject()).check("ADMIN_LOCAL", null, null, NOW));
    }

    @Test
    public void governedFunctionNeedsExpiryAndReason() {
        assertEquals("pim.expiry.required", policy.check("ADMIN_LOCAL", null, "rentrée", NOW));
        assertEquals("pim.reason.required", policy.check("ADMIN_LOCAL", NOW + DAY, " ", NOW));
        assertNull(policy.check("ADMIN_LOCAL", NOW + 30 * DAY, "remplacement du directeur", NOW));
    }

    @Test
    public void expiryIsBounded() {
        assertEquals("pim.expiry.too.long", policy.check("SUPER_ADMIN", NOW + 91 * DAY, "audit", NOW));
        assertEquals("pim.expiry.in.past", policy.check("DOCUMENTALISTE", NOW - 1, null, NOW));
    }

    @Test
    public void otherFunctionsStayFree() {
        assertNull(policy.check("DOCUMENTALISTE", null, null, NOW));
    }
}
