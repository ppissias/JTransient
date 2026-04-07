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
 * Accepted slow-mover candidate together with the diagnostics used to evaluate it.
 */
public final class SlowMoverCandidateResult {
    /** Accepted slow-mover detection. */
    public final SourceExtractor.DetectedObject object;
    /** Per-candidate diagnostics captured during slow-mover filtering. */
    public final SlowMoverCandidateDiagnostics diagnostics;

    public SlowMoverCandidateResult(SourceExtractor.DetectedObject object,
                                    SlowMoverCandidateDiagnostics diagnostics) {
        this.object = object;
        this.diagnostics = diagnostics;
    }
}
