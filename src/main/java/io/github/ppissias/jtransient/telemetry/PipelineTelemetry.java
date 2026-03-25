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

public class PipelineTelemetry {

    // --- PHASE 1: Extraction ---
    public int totalFramesLoaded = 0;
    public int totalRawObjectsExtracted = 0;

    // Per-frame basic stats
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

    public static class FrameRejectionStat {
        public int frameIndex;
        public String filename;
        public String reason;
    }
    public List<FrameRejectionStat> rejectedFrames = new ArrayList<>();

    // --- PHASE 4: Tracking ---
    public int totalStationaryStarsIdentified = 0;
    public int totalMovingTargetsFound = 0;

    // NEW: We nest the detailed TrackerTelemetry inside here!
    public TrackerTelemetry trackerTelemetry;

    // --- Processing ---
    public long processingTimeMs = 0;

    /**
     * Generates a formatted text report.
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

        // Pull the star count directly from the nested tracker telemetry if it exists
        int stars = (trackerTelemetry != null) ? trackerTelemetry.totalStationaryStarsPurged : totalStationaryStarsIdentified;
        sb.append(String.format("Stationary Stars      : %d\n", stars));

        sb.append(String.format("Moving Targets Found  : %d\n\n", totalMovingTargetsFound));

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