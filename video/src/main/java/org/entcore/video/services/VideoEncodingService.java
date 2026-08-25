package org.entcore.video.services;

import io.vertx.core.Future;

import java.io.File;

/**
 * DRAFT — abstraction over the transcoding step, so VideoController doesn't depend
 * directly on ffmpeg. Kept minimal on purpose: one input file on local disk in, one
 * streamable mp4 file on local disk out.
 */
public interface VideoEncodingService {

	/**
	 * Transcodes the given input video file into a streamable H.264/AAC mp4.
	 * @param inputFile  raw file as received from the client (webm/mp4/mov/avi...)
	 * @param outputFile destination path for the encoded mp4 (parent dir must already exist)
	 * @return a future completing with the outputFile itself once encoding succeeded
	 */
	Future<File> encode(File inputFile, File outputFile);
}
