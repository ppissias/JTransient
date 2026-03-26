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

import io.github.ppissias.jtransient.core.SourceExtractor;
import io.github.ppissias.jtransient.core.TrackLinker;
import io.github.ppissias.jtransient.telemetry.PipelineTelemetry;

import java.util.List;

/**
 * Aggregates all exported data products produced by a full pipeline run.
 */
public class PipelineResult {
    /** Confirmed moving-object tracks, including streaks, point tracks, and rescued anomalies. */
    public final List<TrackLinker.Track> tracks;
    /** Per-phase counters and diagnostics collected during processing. */
    public final PipelineTelemetry telemetry;
    /** Median master stack built from the quality-filtered frames. */
    public final short[][] masterStackData;
    /** Stationary objects extracted from the median master stack. */
    public final List<SourceExtractor.DetectedObject> masterStars;

    /** Slow-mover stack produced from the configured middle band of sorted pixels. */
    public final short[][] slowMoverStackData;
    /** Elongated candidates that survived the slow-mover artifact filters. */
    public final List<SourceExtractor.DetectedObject> slowMoverCandidates;

    /** A chronological, frame-by-frame list of all surviving transients */
    public final List<List<SourceExtractor.DetectedObject>> allTransients;

    /** The pixel-perfect Boolean Veto Mask generated from the Master Star Map */
    public final boolean[][] masterMask;

    /** The diagnostic points representing the relative translation vector of the dither/drift */
    public final List<SourceExtractor.Pixel> driftPoints;

    /** Statistical baseline metrics calculated during the slow mover detection phase */
    public final SlowMoverTelemetry slowMoverTelemetry;

    /** A stack containing the maximum pixel values across the sequence */
    public final short[][] masterMaximumStackData;

    /** Reserved for future use. Max-stack streak detection is currently disabled, so this is empty. */
    public final List<SourceExtractor.DetectedObject> masterMaximumStackAllStreaks;

    /** Reserved for future use. Max-stack streak detection is currently disabled, so this is empty. */
    public final List<SourceExtractor.DetectedObject> masterMaximumStackTransientStreaks;


    /**
     * Diagnostic summary for the slow-mover detection branch.
     */
    public static class SlowMoverTelemetry {
        /** Number of slow-mover candidates retained after all filters. */
        public int candidatesDetected;
        /** Median elongation measured from the raw slow-mover extraction pass. */
        public double medianElongation;
        /** Dynamic elongation threshold derived from the baseline elongation distribution. */
        public double dynamicElongationThreshold;
    }

    /**
     * Creates the unified pipeline result payload.
     */
    public PipelineResult(List<TrackLinker.Track> tracks,
                          PipelineTelemetry telemetry,
                          short[][] masterStackData,
                          List<SourceExtractor.DetectedObject> masterStars,
                          short[][] slowMoverStackData,
                          List<SourceExtractor.DetectedObject> slowMoverCandidates,
                          List<List<SourceExtractor.DetectedObject>> allTransients,
                          boolean[][] masterMask,
                          List<SourceExtractor.Pixel> driftPoints,
                          SlowMoverTelemetry slowMoverTelemetry,
                          short[][] masterMaximumStackData,
                          List<SourceExtractor.DetectedObject> masterMaximumStackAllStreaks,
                          List<SourceExtractor.DetectedObject> masterMaximumStackTransientStreaks) {
        this.tracks = tracks;
        this.telemetry = telemetry;
        this.masterStackData = masterStackData;
        this.masterStars = masterStars;
        this.slowMoverStackData = slowMoverStackData;
        this.slowMoverCandidates = slowMoverCandidates;
        this.allTransients = allTransients;
        this.masterMask = masterMask;
        this.driftPoints = driftPoints;
        this.slowMoverTelemetry = slowMoverTelemetry;
        this.masterMaximumStackData = masterMaximumStackData;
        this.masterMaximumStackAllStreaks = masterMaximumStackAllStreaks;
        this.masterMaximumStackTransientStreaks = masterMaximumStackTransientStreaks;
    }
}
