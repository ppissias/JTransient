/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 * author: Petros Pissias <petrospis at gmail.com>
 *
 */
package io.github.ppissias.jtransient.core;

/**
 * Cooperative deadline, cancellation and progress bridge passed to {@link CoarseSearchBackend#search}.
 */
public interface CoarseControl {

    /**
     * Checks whether the faint-mover search deadline has elapsed.
     *
     * @return {@code true} when the tile should stop and be discarded as truncated
     */
    boolean pastDeadline();

    /** Checks the active {@code CancellationScope} and aborts immediately when cancelled. */
    void checkCancelled();

    /**
     * Reports monotonic coarse-pass progress and an optional sample motion index for live preview.
     *
     * @param done completed work units in {@code [0, total]}
     * @param total total work units for this tile's coarse pass
     * @param sampleMotionIndex valid index into the run's motion list for preview, or {@code -1}
     */
    void progress(long done, long total, int sampleMotionIndex);
}
