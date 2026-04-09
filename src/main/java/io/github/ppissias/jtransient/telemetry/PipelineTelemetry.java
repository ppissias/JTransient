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
    /** Number of frames submitted to the pipeline before quality rejection. */
    public int totalFramesLoaded = 0;
    /** Number of raw extracted objects measured before frame rejection and stationary-star vetoing. */
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
    /** Per-frame extraction statistics in chronological order. */
    public List<FrameExtractionStat> frameExtractionStats = new ArrayList<>();

    // --- PHASE 2 & 3: Quality & Filtering ---
    /** Number of frames rejected by the frame-quality stage. */
    public int totalFramesRejected = 0;
    /** Number of frames retained after quality filtering. */
    public int totalFramesKept = 0;

    /**
     * Session-wide quality thresholds derived from the robust frame baseline.
     */
    public static class FrameQualityThresholds {
        public boolean available;
        public double minAllowedStarCount = Double.NaN;
        public double maxAllowedFwhm = Double.NaN;
        public double maxAllowedEccentricity = Double.NaN;
        public double maxAllowedBrightStarEccentricity = Double.NaN;
        public double backgroundMedianBaseline = Double.NaN;
        public double maxAllowedBackgroundDeviation = Double.NaN;
        public double minAllowedBackgroundMedian = Double.NaN;
        public double maxAllowedBackgroundMedian = Double.NaN;
    }
    /** Session-level thresholds derived from the quality-control baseline. */
    public FrameQualityThresholds qualityThresholds = new FrameQualityThresholds();

    /**
     * Per-frame quality summary captured after frame-quality analysis and session rejection.
     */
    public static class FrameQualityStat {
        public int frameIndex;
        public String filename;
        public double backgroundMedian;
        public double backgroundNoise;
        public double medianFWHM;
        public double medianEccentricity;
        public double brightStarMedianEccentricity = Double.NaN;
        public int starCount;
        public int usableShapeStarCount;
        public int brightStarShapeStarCount;
        public int fwhmStarCount;
        public boolean rejected;
        public String rejectionReason;
    }
    /** Per-frame quality statistics in chronological order. */
    public List<FrameQualityStat> frameQualityStats = new ArrayList<>();

    /**
     * Per-frame rejection record emitted by the quality-control stage.
     */
    public static class FrameRejectionStat {
        public int frameIndex;
        public String filename;
        public String reason;
        public double medianEccentricity;
        public double brightStarMedianEccentricity = Double.NaN;
        public int brightStarShapeStarCount;
    }
    /** Rejection records for frames removed by the quality-control stage. */
    public List<FrameRejectionStat> rejectedFrames = new ArrayList<>();

    // --- PHASE 4: Tracking ---
    /** Number of stationary master-stack stars extracted for the veto mask. */
    public int totalMasterStarsIdentified = 0;
    /** Number of returned track-like detections, including suspected streak groupings. */
    public int totalTracksFound = 0;
    /** Number of rescued standalone anomalies returned outside the track list. */
    public int totalAnomaliesFound = 0;
    /** Number of returned tracks flagged as suspected same-frame streak groupings. */
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
        /** Candidates that cleared the elongation baseline before median-support and residual checks. */
        public int candidatesAboveElongationThreshold;
        /** Candidates that reached the median-support overlap stage after the elongation baseline. */
        public int candidatesEvaluatedAgainstMasks;
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
    /** End-to-end pipeline wall-clock runtime in milliseconds. */
    public long processingTimeMs = 0;

    /**
     * Generates a human-readable text summary of the recorded telemetry.
     *
     * @return multiline report suitable for logs, diagnostics, or HTML embedding
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
                sb.append(String.format(
                        "  Frame %03d (%s) -> %s | Ecc: %s | BrightEcc: %s | BrightStars: %d\n",
                        rej.frameIndex + 1,
                        rej.filename,
                        rej.reason,
                        formatMetric(rej.medianEccentricity),
                        formatMetric(rej.brightStarMedianEccentricity),
                        rej.brightStarShapeStarCount
                ));
            }
            sb.append("\n");
        }

        if (qualityThresholds.available) {
            sb.append("--- QUALITY CONTROL: SESSION THRESHOLDS ---\n");
            sb.append(String.format(
                    "  Stars >= %.2f | FWHM <= %s | Ecc <= %s | BrightEcc <= %s | Bg Median in [%s, %s] (center %s, dev %s)\n\n",
                    qualityThresholds.minAllowedStarCount,
                    formatMetric(qualityThresholds.maxAllowedFwhm),
                    formatMetric(qualityThresholds.maxAllowedEccentricity),
                    formatMetric(qualityThresholds.maxAllowedBrightStarEccentricity),
                    formatMetric(qualityThresholds.minAllowedBackgroundMedian),
                    formatMetric(qualityThresholds.maxAllowedBackgroundMedian),
                    formatMetric(qualityThresholds.backgroundMedianBaseline),
                    formatMetric(qualityThresholds.maxAllowedBackgroundDeviation)
            ));
        }

        if (!frameQualityStats.isEmpty()) {
            sb.append("--- FRAME QUALITY STATISTICS ---\n");
            for (FrameQualityStat stat : frameQualityStats) {
                sb.append(String.format(
                        "  Frame %03d (%s) -> Noise: %.2f, Bg: %.2f, Stars: %d, ShapeStars: %d, BrightShapeStars: %d, FwhmStars: %d, FWHM: %s, Ecc: %s, BrightEcc: %s%s\n",
                        stat.frameIndex + 1,
                        stat.filename,
                        stat.backgroundNoise,
                        stat.backgroundMedian,
                        stat.starCount,
                        stat.usableShapeStarCount,
                        stat.brightStarShapeStarCount,
                        stat.fwhmStarCount,
                        formatMetric(stat.medianFWHM),
                        formatMetric(stat.medianEccentricity),
                        formatMetric(stat.brightStarMedianEccentricity),
                        stat.rejected ? " [REJECTED: " + stat.rejectionReason + "]" : ""
                ));
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

    /**
     * Formats optional floating-point metrics for reports.
     */
    private static String formatMetric(double value) {
        return Double.isFinite(value) ? String.format("%.2f", value) : "n/a";
    }
}
