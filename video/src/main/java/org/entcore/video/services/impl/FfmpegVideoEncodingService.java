package org.entcore.video.services.impl;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import org.entcore.video.services.VideoEncodingService;

import java.io.File;
import java.util.concurrent.TimeUnit;

/**
 * DRAFT — shells out to a local ffmpeg binary. This is the simplest possible strategy;
 * it is NOT what a production deployment should keep as-is:
 *
 *  - ffmpeg must be installed in the video module's container image (not part of the
 *    base entcore/vertx image today — a Dockerfile change is needed, cf. how
 *    fr.wseduc~mod-pdf-generator or lool bundle their native tooling).
 *  - Running ffmpeg via ProcessBuilder on the Vert.x worker pool blocks a worker thread
 *    for the whole encode duration; fine for a handful of concurrent captations, but if
 *    this module gets real traffic, prefer a dedicated worker verticle pool sized
 *    (workerPoolSize) independently from the rest of the app, or delegate to an external
 *    queue/service instead of encoding in-process.
 *  - No thumbnail generation here yet, unlike images/other videos already thumbnailed by
 *    WorkspaceController (see its "getDocumentFile" handling of video/* content-types,
 *    workspace/src/main/java/.../WorkspaceController.java around line 1071). Add an
 *    extra "-vframes 1" ffmpeg pass if the "video" app should produce its own thumbnail
 *    instead of relying on that fallback.
 */
public class FfmpegVideoEncodingService implements VideoEncodingService {

	private static final Logger log = LoggerFactory.getLogger(FfmpegVideoEncodingService.class);

	private final Vertx vertx;
	private final String ffmpegPath;
	private final long timeoutMs;

	public FfmpegVideoEncodingService(Vertx vertx, String ffmpegPath, long timeoutMs) {
		this.vertx = vertx;
		this.ffmpegPath = ffmpegPath;
		this.timeoutMs = timeoutMs;
	}

	@Override
	public Future<File> encode(File inputFile, File outputFile) {
		return vertx.executeBlocking(promise -> {
			try {
				final ProcessBuilder pb = new ProcessBuilder(
						ffmpegPath,
						"-y",
						"-i", inputFile.getAbsolutePath(),
						"-c:v", "libx264",
						"-preset", "veryfast",
						"-c:a", "aac",
						// makes the mp4 streamable (moov atom at the front) instead of
						// requiring a full download before playback can start.
						"-movflags", "+faststart",
						outputFile.getAbsolutePath());
				pb.redirectErrorStream(true);
				final Process process = pb.start();

				final boolean finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
				if (!finished) {
					process.destroyForcibly();
					promise.fail("video.encode.timeout");
					return;
				}
				if (process.exitValue() != 0 || !outputFile.exists() || outputFile.length() == 0) {
					log.error("[FfmpegVideoEncodingService] ffmpeg failed for " + inputFile.getAbsolutePath()
							+ " (exit=" + process.exitValue() + ")");
					promise.fail("video.encode.error");
					return;
				}
				promise.complete(outputFile);
			} catch (Exception e) {
				log.error("[FfmpegVideoEncodingService] encode error", e);
				promise.fail("video.encode.error");
			}
		});
	}
}
