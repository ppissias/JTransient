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
 * Grouped output of the slow-mover analysis branch.
 */
public final class SlowMoverAnalysis {
    /** Deep stack built from the configured slow-mover median band. */
    public final short[][] slowMoverStackData;
    /** Veto mask built from objects extracted on the median master stack with slow-mover thresholds. */
    public final boolean[][] medianVetoMask;
    /** Accepted slow-mover detections with attached diagnostics. */
    public final List<SlowMoverCandidateResult> candidates;
    /** Aggregate counters and thresholds describing the slow-mover stage. */
    public final SlowMoverSummaryTelemetry telemetry;

    public SlowMoverAnalysis(short[][] slowMoverStackData,
                             boolean[][] medianVetoMask,
                             List<SlowMoverCandidateResult> candidates,
                             SlowMoverSummaryTelemetry telemetry) {
        this.slowMoverStackData = slowMoverStackData;
        this.medianVetoMask = medianVetoMask;
        this.candidates = Collections.unmodifiableList(new ArrayList<>(candidates));
        this.telemetry = telemetry;
    }

    public static SlowMoverAnalysis empty() {
        return new SlowMoverAnalysis(null, null, Collections.emptyList(), SlowMoverSummaryTelemetry.empty());
    }
}
