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
 * Per-candidate diagnostics exported for accepted slow movers.
 */
public final class SlowMoverCandidateDiagnostics {
    /** Fraction of the accepted footprint that overlaps the median-stack veto mask. */
    public final double medianSupportOverlap;
    /** Fraction of centroid-core footprint pixels that stay positive in slowMoverStack - medianStack. */
    public final double residualCorePositiveFraction;
    /** Count of positive centroid-core pixels in slowMoverStack - medianStack. */
    public final int residualCorePositivePixels;
    /** Total count of accepted footprint pixels that fell inside the centroid-core radius. */
    public final int residualCorePixels;
    /** Effective residual-core radius used by the algorithm after internal clamping. */
    public final double residualCoreRadiusPixelsUsed;
    /** Whether the residual-core veto was enabled for acceptance decisions. */
    public final boolean residualCoreFilteringEnabled;

    public SlowMoverCandidateDiagnostics(double medianSupportOverlap,
                                         double residualCorePositiveFraction,
                                         int residualCorePositivePixels,
                                         int residualCorePixels,
                                         double residualCoreRadiusPixelsUsed,
                                         boolean residualCoreFilteringEnabled) {
        this.medianSupportOverlap = medianSupportOverlap;
        this.residualCorePositiveFraction = residualCorePositiveFraction;
        this.residualCorePositivePixels = residualCorePositivePixels;
        this.residualCorePixels = residualCorePixels;
        this.residualCoreRadiusPixelsUsed = residualCoreRadiusPixelsUsed;
        this.residualCoreFilteringEnabled = residualCoreFilteringEnabled;
    }
}
