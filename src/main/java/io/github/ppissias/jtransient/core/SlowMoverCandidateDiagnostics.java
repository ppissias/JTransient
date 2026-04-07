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
    /** Fraction of the candidate's slow-mover footprint flux that remains as positive residual after subtracting the median stack. */
    public final double residualFootprintFluxFraction;
    /** Sum of positive residual flux on the candidate footprint in slowMoverStack - medianStack. */
    public final double residualFootprintFlux;
    /** Sum of slow-mover stack flux on the candidate footprint. */
    public final double slowMoverFootprintFlux;
    /** Sum of ordinary median-stack flux on the candidate footprint. */
    public final double medianFootprintFlux;
    /** Number of candidate footprint pixels compared between the slow-mover and median stacks. */
    public final int footprintPixelCount;
    /** Whether the residual-footprint veto was enabled for acceptance decisions. */
    public final boolean residualFootprintFilteringEnabled;

    public SlowMoverCandidateDiagnostics(double medianSupportOverlap,
                                         double residualFootprintFluxFraction,
                                         double residualFootprintFlux,
                                         double slowMoverFootprintFlux,
                                         double medianFootprintFlux,
                                         int footprintPixelCount,
                                         boolean residualFootprintFilteringEnabled) {
        this.medianSupportOverlap = medianSupportOverlap;
        this.residualFootprintFluxFraction = residualFootprintFluxFraction;
        this.residualFootprintFlux = residualFootprintFlux;
        this.slowMoverFootprintFlux = slowMoverFootprintFlux;
        this.medianFootprintFlux = medianFootprintFlux;
        this.footprintPixelCount = footprintPixelCount;
        this.residualFootprintFilteringEnabled = residualFootprintFilteringEnabled;
    }
}
