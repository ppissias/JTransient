/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 * author: Petros Pissias <petrospis at gmail.com>
 *
 */
package io.github.ppissias.jtransient.telemetry;

import java.util.ArrayList;
import java.util.List;

/**
 * Top-level telemetry bundle describing what happened during one pipeline run.
 */
public class PipelineTelemetry {

    // --- PHASE 1: Extraction ---
    public int totalFramesLoaded = 0;
    public int totalRawObjectsExtracted = 0;

    /**
     * Per-frame extraction summary captured immediately after source extraction.
     */
    public static class FrameExtractionStat {
        public int frameIndex;
        public String filename;
        public int objectCount;
        public double bgMedian;
        public double bgSigma;
        public double seedThreshold;
        public double growThreshold;
    }
    public List<FrameExtractionStat> frameExtractionStats = new ArrayList<>();

    // --- PHASE 2 & 3: Quality & Filtering ---
    public int totalFramesRejected = 0;
    public int totalFramesKept = 0;

    /**
     * Per-frame rejection record emitted by the quality-control stage.
     */
    public static class FrameRejectionStat {
        public int frameIndex;
        public String filename;
        public String reason;
    }
    public List<FrameRejectionStat> rejectedFrames = new ArrayList<>();

    // --- PHASE 4: Tracking ---
    public int totalMasterStarsIdentified = 0;
    public int totalTracksFound = 0;
    public int totalAnomaliesFound = 0;
    public int totalSuspectedStreakTracksFound = 0;

    /**
     * Detailed diagnostics emitted by the track-linking stages.
     */
    public TrackerTelemetry trackerTelemetry;

    /**
     * Diagnostic summary for the slow-mover detection branch.
     */
    public SlowMoverTelemetry slowMoverTelemetry;

    /**
     * Diagnostic counters for the slow-mover detection branch.
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
        /** Candidates rejected because their slow-mover footprint retained too little positive residual flux. */
        public int rejectedLowResidualFootprintSupport;
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
        /** Mean overlap with the median-stack veto mask across candidates that reached the mask stage. */
        public double avgMedianSupportOverlap;
        /** Minimum residual-footprint flux fraction required by the slowMover-minus-median veto. */
        public double residualFootprintMinFluxFractionThreshold;
        /** Mean residual-footprint flux fraction across candidates retained by the slow-mover stage. */
        public double avgResidualFootprintFluxFraction;
        /** Per-candidate overlap fractions for accepted slow movers, in the same order as the exported detections. */
        public final List<Double> candidateMedianSupportOverlaps = new ArrayList<>();
    }

    // --- Processing ---
    public long processingTimeMs = 0;

    /**
     * Generates a human-readable text summary of the recorded telemetry.
     */
    public String generateReport() {
        StringBuilder sb = new StringBuilder();
        sb.append("==================================================\n");
        sb.append("          JTRANSIENT DETECTION REPORT             \n");
        sb.append("==================================================\n\n");

        sb.append("--- PIPELINE SUMMARY ---\n");
        sb.append(String.format("Total Processing Time : %.2f seconds\n", processingTimeMs / 1000.0));
        sb.append(String.format("Total Frames Processed: %d\n", totalFramesLoaded));
        sb.append(String.format("Frames Kept / Rejected: %d / %d\n", totalFramesKept, totalFramesRejected));
        sb.append(String.format("Total Raw Objects     : %d\n", totalRawObjectsExtracted));

        sb.append(String.format("Master Stars          : %d\n", totalMasterStarsIdentified));

        sb.append(String.format("Tracks Returned       : %d\n", totalTracksFound));
        sb.append(String.format("Anomalies Found       : %d\n", totalAnomaliesFound));
        sb.append(String.format("Suspected Streak Tracks: %d\n\n", totalSuspectedStreakTracksFound));

        if (!rejectedFrames.isEmpty()) {
            sb.append("--- QUALITY CONTROL: REJECTED FRAMES ---\n");
            for (FrameRejectionStat rej : rejectedFrames) {
                sb.append(String.format("  Frame %03d (%s) -> %s\n",
                        rej.frameIndex + 1, rej.filename, rej.reason));
            }
            sb.append("\n");
        }

        sb.append("--- EXTRACTION STATISTICS ---\n");
        for (FrameExtractionStat stat : frameExtractionStats) {
            sb.append(String.format("  Frame %03d (%s) -> %d objects extracted | Median: %.2f, Sigma: %.2f, Seed: %.2f, Grow: %.2f\n",
                    stat.frameIndex + 1, stat.filename, stat.objectCount, 
                    stat.bgMedian, stat.bgSigma, stat.seedThreshold, stat.growThreshold));
        }
        sb.append("\n");
        sb.append("==================================================\n");

        return sb.toString();
    }
}
