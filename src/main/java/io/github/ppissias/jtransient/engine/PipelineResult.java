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

import java.util.ArrayList;
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
    /** Pixel mask built from objects extracted on the median stack with the slow-mover extraction thresholds. */
    public final boolean[][] slowMoverMedianArtifactMask;
    /** Elongated candidates that survived the slow-mover artifact filters. */
    public final List<SourceExtractor.DetectedObject> slowMoverCandidates;
    /** Single-frame anomalies rescued after tracking without being treated as tracks. */
    public final List<TrackLinker.AnomalyDetection> anomalies;

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
        /** Raw candidate count extracted from the slow-mover stack before any filtering. */
        public int rawCandidatesExtracted;
        /** Candidates that cleared the elongation baseline before morphology and median-support checks. */
        public int candidatesAboveElongationThreshold;
        /** Candidates that reached the median-support overlap stage after morphology filters. */
        public int candidatesEvaluatedAgainstMasks;
        /** Candidates rejected because the footprint shape is too irregular for a trustworthy mover. */
        public int rejectedIrregularShape;
        /** Candidates rejected because the footprint is more consistent with a merged binary star. */
        public int rejectedBinaryAnomaly;
        /** Candidates rejected by the slow-mover-only short/kinked shape veto. */
        public int rejectedSlowMoverShape;
        /** Slow-mover shape rejects caused by a footprint that is too short along its binary major axis. */
        public int rejectedSlowMoverShapeTooShort;
        /** Slow-mover shape rejects caused by an elongated footprint that is too sparse inside its oriented box. */
        public int rejectedSlowMoverShapeLowFill;
        /** Slow-mover shape rejects caused by too few occupied longitudinal bins along the fitted major axis. */
        public int rejectedSlowMoverShapeSparseBins;
        /** Slow-mover shape rejects caused by internal empty bins along the fitted major axis. */
        public int rejectedSlowMoverShapeGappedBins;
        /** Slow-mover shape rejects caused by a binary footprint whose fitted centerline bends too much. */
        public int rejectedSlowMoverShapeCurvedCenterline;
        /** Slow-mover shape rejects caused by strong width bulges or abrupt width changes along the footprint. */
        public int rejectedSlowMoverShapeBulgedWidth;
        /** Candidates rejected because their median-stack overlap stayed below the configured support floor. */
        public int rejectedLowMedianSupport;
        /** Candidates rejected because their median-stack overlap exceeded the configured support ceiling. */
        public int rejectedHighMedianSupport;
        /** Number of slow-mover candidates retained after all filters. */
        public int candidatesDetected;
        /** Median elongation measured from the raw slow-mover extraction pass. */
        public double medianElongation;
        /** Median absolute deviation of the raw elongation distribution, after the safety floor. */
        public double madElongation;
        /** Dynamic elongation threshold derived from the baseline elongation distribution. */
        public double dynamicElongationThreshold;
        /** Minimum overlap fraction required for a slow mover to be considered supported by the median stack. */
        public double medianSupportOverlapThreshold;
        /** Maximum overlap fraction allowed before a slow mover is treated as too similar to the median stack. */
        public double medianSupportMaxOverlapThreshold;
        /** Mean overlap with the median-stack artifact mask across candidates that reached the mask stage. */
        public double avgMedianSupportOverlap;
        /** Per-candidate overlap fractions for accepted slow movers, in the same order as {@code slowMoverCandidates}. */
        public final List<Double> candidateMedianSupportOverlaps = new ArrayList<>();
    }

    /**
     * Creates the unified pipeline result payload.
     */
    public PipelineResult(List<TrackLinker.Track> tracks,
                          PipelineTelemetry telemetry,
                          short[][] masterStackData,
                          List<SourceExtractor.DetectedObject> masterStars,
                          short[][] slowMoverStackData,
                          boolean[][] slowMoverMedianArtifactMask,
                          List<SourceExtractor.DetectedObject> slowMoverCandidates,
                          List<TrackLinker.AnomalyDetection> anomalies,
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
        this.slowMoverMedianArtifactMask = slowMoverMedianArtifactMask;
        this.slowMoverCandidates = slowMoverCandidates;
        this.anomalies = anomalies;
        this.allTransients = allTransients;
        this.masterMask = masterMask;
        this.driftPoints = driftPoints;
        this.slowMoverTelemetry = slowMoverTelemetry;
        this.masterMaximumStackData = masterMaximumStackData;
        this.masterMaximumStackAllStreaks = masterMaximumStackAllStreaks;
        this.masterMaximumStackTransientStreaks = masterMaximumStackTransientStreaks;
    }
}
