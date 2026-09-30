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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Maximum-stack morphology candidates and the median-stack context used to filter them.
 * Candidates have not been temporally confirmed as moving sources.
 */
public final class SlowMoverAnalysis {
    /** Maximum stack used for candidate extraction, in the source image's signed-short encoding. */
    public final short[][] maximumStackData;
    /** Exact raw-pixel mask of detected median-stack objects, indexed as [y][x]. */
    public final boolean[][] medianMask;
    /** Compatibility alias for maximumStackData. */
    public final short[][] slowMoverStackData;
    /** Compatibility alias for medianMask. */
    public final boolean[][] medianVetoMask;
    /** Accepted maximum-stack objects with their morphology and mask-overlap diagnostics. */
    public final List<SlowMoverCandidateResult> candidates;
    /** Aggregate counters and thresholds describing the slow-mover stage. */
    public final SlowMoverSummaryTelemetry telemetry;

    /**
     * Groups the stack, mask, accepted candidates, and telemetry. The candidate list is copied;
     * image and mask arrays are shared with the caller.
     *
     * @param maximumStackData maximum stack used for candidate extraction
     * @param medianMask exact median-stack object footprint mask
     * @param candidates accepted slow-mover detections with diagnostics
     * @param telemetry aggregate slow-mover counters and thresholds
     */
    public SlowMoverAnalysis(short[][] maximumStackData,
                             boolean[][] medianMask,
                             List<SlowMoverCandidateResult> candidates,
                             SlowMoverSummaryTelemetry telemetry) {
        this.maximumStackData = maximumStackData;
        this.medianMask = medianMask;
        this.slowMoverStackData = maximumStackData;
        this.medianVetoMask = medianMask;
        this.candidates = Collections.unmodifiableList(new ArrayList<>(candidates));
        this.telemetry = telemetry;
    }

    /**
     * Returns an empty result when the branch is disabled or required input is unavailable.
     *
     * @return empty slow-mover analysis
     */
    public static SlowMoverAnalysis empty() {
        return new SlowMoverAnalysis(null, null, Collections.emptyList(), SlowMoverSummaryTelemetry.empty());
    }
}
