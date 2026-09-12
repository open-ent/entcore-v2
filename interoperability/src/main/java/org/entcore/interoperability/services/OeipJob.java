package org.entcore.interoperability.services;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * Un travail d'export ou d'import.
 *
 * L'état est persisté en base et non dans une carte partagée : les cartes distribuées du module
 * archive sont la cause documentée des exports qui restent « en cours » indéfiniment. Un
 * document survit au redémarrage, se lit depuis n'importe quel pod, et tient lieu de piste
 * d'audit — ce dont un export de données personnelles a besoin de toute façon.
 */
public class OeipJob {

    public static final String COLLECTION = "oeip_jobs";

    public static final String TYPE_EXPORT = "export";
    public static final String TYPE_IMPORT = "import";

    public static final String QUEUED = "queued";
    public static final String RUNNING = "running";
    public static final String READY = "ready";
    public static final String ANALYZED = "analyzed";
    public static final String DONE = "done";
    public static final String ERROR = "error";

    private OeipJob() {}

    public static JsonObject create(String jobId, String type, String userId, JsonArray services,
                                    String locale, String host, long ttlHours) {
        long now = System.currentTimeMillis();
        return new JsonObject()
                .put("_id", jobId)
                .put("type", type)
                .put("state", QUEUED)
                .put("userId", userId)
                .put("services", services == null ? new JsonArray() : services)
                .put("locale", locale)
                .put("host", host)
                .put("createdAt", now)
                .put("updatedAt", now)
                .put("expiresAt", now + ttlHours * 3600_000L);
    }

    /** Vue rendue au client : jamais le chemin du paquet sur disque. */
    public static JsonObject toPublic(JsonObject job) {
        if (job == null) {
            return null;
        }
        JsonObject json = new JsonObject()
                .put("jobId", job.getString("_id"))
                .put("type", job.getString("type"))
                .put("state", job.getString("state"))
                .put("services", job.getJsonArray("services", new JsonArray()))
                .put("createdAt", job.getLong("createdAt"))
                .put("updatedAt", job.getLong("updatedAt"))
                .put("expiresAt", job.getLong("expiresAt"));
        if (job.getString("error") != null) {
            json.put("error", job.getString("error"));
        }
        if (job.getJsonObject("manifest") != null) {
            json.put("manifest", job.getJsonObject("manifest"));
        }
        if (job.getJsonObject("report") != null) {
            json.put("report", job.getJsonObject("report"));
        }
        if (job.getJsonArray("warnings") != null) {
            json.put("warnings", job.getJsonArray("warnings"));
        }
        return json;
    }
}
