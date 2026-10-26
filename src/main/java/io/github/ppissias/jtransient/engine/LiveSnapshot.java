/*
 * JTransient
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 * author: Petros Pissias <petrospis at gmail.com>
 */
package io.github.ppissias.jtransient.engine;

/**
 * A small read-only picture of work inside a running stage. Coordinates are copies in full-frame pixels.
 * The engine builds one only after {@link PipelineStageObserver#wantsUpdate()} says yes.
 */
public record LiveSnapshot(String phase, int frameIndex, String caption, double[] x, double[] y) {

    /** Copies the coordinates so the caller can reuse its arrays. */
    public LiveSnapshot {
        x = x == null ? new double[0] : x.clone();
        y = y == null ? new double[0] : y.clone();
    }
}
