/*
 * Règles d'attribution des accès privilégiés (fonctions d'administration), configurées dans le
 * module directory :
 *
 *   "pim": {
 *     "require-expiry": ["ADMIN_LOCAL", "SUPER_ADMIN"],  // fonctions qui doivent avoir une échéance
 *     "max-days": 365,                                   // durée maximale accordée
 *     "require-reason": true                             // justification obligatoire
 *   }
 *
 * Sans configuration, rien n'est exigé (comportement historique).
 */
package org.entcore.directory.pim;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

public class PimPolicy {

    private static final long DAY = 24L * 3600 * 1000;

    private final JsonArray requireExpiry;
    private final Integer maxDays;
    private final boolean requireReason;

    public PimPolicy(JsonObject config) {
        final JsonObject pim = config == null ? new JsonObject() : config.getJsonObject("pim", new JsonObject());
        this.requireExpiry = pim.getJsonArray("require-expiry", new JsonArray());
        this.maxDays = pim.getInteger("max-days");
        this.requireReason = pim.getBoolean("require-reason", false);
    }

    /** @return null si l'attribution est conforme, sinon la clé d'erreur à renvoyer. */
    public String check(String functionCode, Long validUntil, String reason, long now) {
        final boolean governed = requireExpiry.contains(functionCode);
        if (validUntil != null && validUntil <= now) return "pim.expiry.in.past";
        if (governed && validUntil == null) return "pim.expiry.required";
        if (governed && maxDays != null && validUntil - now > maxDays * DAY) return "pim.expiry.too.long";
        if (governed && requireReason && (reason == null || reason.trim().isEmpty())) return "pim.reason.required";
        return null;
    }
}
