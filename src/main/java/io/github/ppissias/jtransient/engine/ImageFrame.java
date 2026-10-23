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
 * Stable frame metadata with a borrowed, mutable pixel matrix consumed by the pipeline.
 *
 * <p>{@link JTransientEngine} orders frames by {@link #sequenceIndex}. Timestamps and exposure
 * durations are optional, but when present they enable the time-aware tracking logic.
 * No pixels are copied: callers must not mutate the matrix or its rows until all borrowing
 * operations have returned, including joined cancellation. Final fields do not make pixels immutable.</p>
 */
public class ImageFrame {
    /** Monotonic frame order used to restore chronological processing. */
    public final int sequenceIndex;
    /** Human-readable frame identifier, usually the source filename. */
    public final String filename;
    /** Borrowed raw pixel matrix in signed-short sensor units; shared read-only during processing. */
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
     * @param pixelData borrowed raw pixel matrix; not cloned
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
