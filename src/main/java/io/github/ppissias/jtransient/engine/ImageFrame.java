/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 * author: Petros Pissias <petrospis at gmail.com>
 *
 */
package io.github.ppissias.jtransient.engine;

/**
 * Immutable image payload consumed by the pipeline.
 */
public class ImageFrame {
    /** Monotonic frame order used to restore chronological processing. */
    public final int sequenceIndex;
    /** Human-readable frame identifier, usually the source filename. */
    public final String filename;
    /** Raw pixel matrix in signed-short sensor units. */
    public final short[][] pixelData;
    /** Capture timestamp in milliseconds, or {@code -1} when unknown. */
    public final long timestamp;
    /** Exposure time in milliseconds, or {@code -1} when unknown. */
    public final long exposureDuration;

    /**
     * Creates a frame descriptor for one image in the sequence.
     *
     * @param sequenceIndex chronological index used by the engine
     * @param filename source label for diagnostics and output
     * @param pixelData raw pixel matrix
     * @param timestamp capture timestamp in milliseconds, or {@code -1} if unavailable
     * @param exposureDuration exposure duration in milliseconds, or {@code -1} if unavailable
     */
    public ImageFrame(int sequenceIndex, String filename, short[][] pixelData, long timestamp, long exposureDuration) {
        this.sequenceIndex = sequenceIndex;
        this.filename = filename;
        this.pixelData = pixelData;
        this.timestamp = timestamp;
        this.exposureDuration = exposureDuration;
    }
}
