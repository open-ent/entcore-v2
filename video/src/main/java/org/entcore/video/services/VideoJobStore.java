package org.entcore.video.services;

import io.vertx.core.json.JsonObject;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * DRAFT — tracks the state of async encode jobs so that GET /video/status/:id can be
 * polled, matching the contract expected by VideoUploadService.ts / VideoService.ts
 * (state: "running" | "succeed" | "error").
 *
 * LIMITATION connue : stockage en mémoire locale au process. Ça marche pour du dev/mono-nœud
 * mais pas en cluster (un /video/status/:id peut retomber sur un autre pod que celui qui a
 * lancé l'encodage). À remplacer par du SharedData clusterisé Vert.x ou une petite table
 * (ex: Postgres, comme le fait déjà le module "stats") avant tout déploiement multi-instance.
 */
public class VideoJobStore {

	public static final String STATE_RUNNING = "running";
	public static final String STATE_SUCCEED = "succeed";
	public static final String STATE_ERROR = "error";

	private final Map<String, JsonObject> jobs = new ConcurrentHashMap<>();

	/** Creates a new job in "running" state, owned by the given user. Returns the processid. */
	public String createJob(String ownerId) {
		final String processId = UUID.randomUUID().toString();
		jobs.put(processId, new JsonObject()
				.put("processid", processId)
				.put("state", STATE_RUNNING)
				.put("ownerId", ownerId));
		return processId;
	}

	public JsonObject get(String processId) {
		return jobs.get(processId);
	}

	public void succeed(String processId, String videoId, long videoSize, String videoWorkspaceId) {
		final JsonObject job = jobs.get(processId);
		if (job != null) {
			job.put("state", STATE_SUCCEED)
					.put("videoid", videoId)
					.put("videosize", videoSize)
					.put("videoworkspaceid", videoWorkspaceId);
		}
	}

	public void fail(String processId, String errorCode) {
		final JsonObject job = jobs.get(processId);
		if (job != null) {
			job.put("state", STATE_ERROR).put("code", errorCode);
		}
	}

	/** Drops jobs older than maxAgeMs, based on their processid creation order is NOT tracked here. */
	// TODO(sketch): ajouter un timestamp + un vertx.setPeriodic() de nettoyage si ce store
	// reste en mémoire ; sinon la table de remplacement (cf. classdoc) portera son propre TTL.
	public void remove(String processId) {
		jobs.remove(processId);
	}
}
