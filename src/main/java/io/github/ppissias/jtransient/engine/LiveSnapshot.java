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
 * An optional {@code curve} holds light-curve values (e.g. differential magnitudes) for a single candidate star.
 * The engine builds one only after {@link PipelineStageObserver#wantsUpdate()} says yes.
 * For phase "Faint-mover search", four X/Y entries describe the active tile's opposite corners followed by
 * the trial path's endpoints. This is work geometry on an example source frame, never a retained detection.
 */
public record LiveSnapshot(String phase, int frameIndex, String caption, double[] x, double[] y, double[] curve) {

    public LiveSnapshot(String phase, int frameIndex, String caption, double[] x, double[] y) {
        this(phase, frameIndex, caption, x, y, null);
    }

    /** Copies the coordinates and curve so the caller can reuse its arrays. */
    public LiveSnapshot {
        x = x == null ? new double[0] : x.clone();
        y = y == null ? new double[0] : y.clone();
        curve = curve == null ? null : curve.clone();
    }
}
