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

import io.github.ppissias.jtransient.config.DetectionConfig;
import io.github.ppissias.jtransient.core.SourceExtractor;
import io.github.ppissias.jtransient.core.SpatialGrid;
import io.github.ppissias.jtransient.quality.FrameQualityAnalyzer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class JTransientAutoTuner {

    // =========================================================================
    // DEBUG FLAG
    // =========================================================================
    public static boolean DEBUG = true;

    // =========================================================================
    // TUNABLE AUTO-TUNER CONSTRAINTS & MAGIC NUMBERS
    // =========================================================================

    // The number of highest-quality frames to sample for tuning. (Must be odd, e.g., 3, 5, 7)
    public static int AUTO_TUNE_SAMPLE_SIZE = 5;

    // The search radius (pixels) to find matching stars across frames.
    public static double SEARCH_RADIUS_PX = 4.0;

    // The strict noise gate. The ratio of transients to total objects must be <= this value.
    public static double MAX_TRANSIENT_RATIO = 0.25; // 25%

    // The absolute minimum number of stable stars required to consider the sweep valid.
    public static int MIN_STABLE_STARS = 15;

    // The absolute maximum objects allowed across the sample frames to prevent memory/CPU freezing
    public static int MAX_TOTAL_EXTRACTED_OBJECTS = 500000;

    // The absolute minimum objects allowed across the sample frames to ensure valid data exists
    public static int MIN_TOTAL_EXTRACTED_OBJECTS = 10;

    // The parameter grid to sweep during Phase 1
    public static double[] SIGMAS_TO_TEST = {2.5, 3.0, 3.5, 4.0, 5.0, 6.0, 7.0};
    public static int[] MIN_PIXELS_TO_TEST = {3, 5, 7, 9, 12};

    // --- HEURISTIC ALGORITHM WEIGHTS ---
    public static double GROW_SIGMA_RATIO = 0.5;
    public static double SCORE_WEIGHT_TRANSIENT_PENALTY = 10.0;
    public static double SCORE_WEIGHT_MINPIX_REWARD = 25.0;

    // --- OPTICAL & KINEMATIC TUNING LIMITS ---
    public static double JITTER_PERCENTILE = 0.90;
    public static double MIN_JITTER_FLOOR = 1.0;
    public static double JITTER_SAFETY_MULTIPLIER = 2.0;

    public static double FWHM_ELONGATION_BUFFER = 0.4;
    public static double STREAK_ELONGATION_BUFFER = 1.0;

    // =========================================================================

    public static class AutoTunerResult {
        public boolean success;
        public DetectionConfig optimizedConfig;
        public String telemetryReport;
        public int bestStarCount;
        public double bestTransientRatio;
    }

    private static class FrameQualityRecord {
        ImageFrame frame;
        FrameQualityAnalyzer.FrameMetrics metrics;
        double qualityScore;

        public FrameQualityRecord(ImageFrame frame, FrameQualityAnalyzer.FrameMetrics metrics, double qualityScore) {
            this.frame = frame;
            this.metrics = metrics;
            this.qualityScore = qualityScore;
        }
    }

    /**
     * Automatically determines the optimal extraction and kinematic settings for a given image sequence.
     */
    public static AutoTunerResult tune(List<ImageFrame> sampleFrames, DetectionConfig baseConfig) {
        if (DEBUG) {
            System.out.println("\n==================================================");
            System.out.println("      JTRANSIENT AUTO-TUNER INITIALIZED");
            System.out.println("==================================================");
            System.out.println("Total frames to process: " + sampleFrames.size());
        }

        AutoTunerResult result = new AutoTunerResult();
        StringBuilder report = new StringBuilder("=== JTransient Auto-Tuning Report ===\n");

        if (sampleFrames.size() < AUTO_TUNE_SAMPLE_SIZE) {
            report.append("Warning: Not enough frames for Auto-Tuning. Falling back to base config.\n");
            result.optimizedConfig = baseConfig;
            result.success = false;
            result.telemetryReport = report.toString();
            return result;
        }

        // We already did the quality pass in AutoTuneTask!
        // Just go straight to Phase 1:
        if (DEBUG) System.out.println("\n[START] Phase 1: Detection Threshold Sweep...");
        report.append("\n--- PHASE 1: Detection Threshold Sweep ---\n");

        // =====================================================================
        // PHASE 1: THE SIGNAL-TO-NOISE SWEEP
        // =====================================================================
        if (DEBUG) System.out.println("\n[START] Phase 1: Detection Threshold Sweep...");
        report.append("\n--- PHASE 1: Detection Threshold Sweep ---\n");

        DetectionConfig bestConfig = null;
        List<List<SourceExtractor.DetectedObject>> bestExtractedFrames = null;

        int maxStableStars = -1;
        double bestRatio = 1.0;
        double bestScore = -Double.MAX_VALUE;
        int baseIndex = AUTO_TUNE_SAMPLE_SIZE / 2; // Center frame of the sample

        for (int minPix : MIN_PIXELS_TO_TEST) {
            for (double sigma : SIGMAS_TO_TEST) {

                if (DEBUG) {
                    System.out.println("\n--------------------------------------------------");
                    System.out.println("   -> Testing Sigma: " + sigma + " | MinPix: " + minPix);
                }

                DetectionConfig testConfig = cloneConfig(baseConfig);
                testConfig.detectionSigmaMultiplier = sigma;
                testConfig.minDetectionPixels = minPix;
                testConfig.growSigmaMultiplier = sigma * GROW_SIGMA_RATIO;

                List<List<SourceExtractor.DetectedObject>> extractedFrames = new ArrayList<>();
                int totalObjectsExtracted = 0;

                // --- DETAILED EXTRACTION DEBUG ---
                for (int i = 0; i < sampleFrames.size(); i++) {
                    ImageFrame frame = sampleFrames.get(i);
                    List<SourceExtractor.DetectedObject> objects = SourceExtractor.extractSources(
                            frame.pixelData,
                            testConfig.detectionSigmaMultiplier,
                            testConfig.minDetectionPixels,
                            testConfig
                    );
                    extractedFrames.add(objects);
                    totalObjectsExtracted += objects.size();

                    if (DEBUG) System.out.println("      -> Frame " + frame.sequenceIndex + " extracted: " + objects.size() + " objects.");
                }

                if (totalObjectsExtracted > MAX_TOTAL_EXTRACTED_OBJECTS || totalObjectsExtracted < MIN_TOTAL_EXTRACTED_OBJECTS) {
                    if (DEBUG) System.out.println("      [REJECTED] Extracted " + totalObjectsExtracted + " total objects (Out of bounds). Moving to next test.");
                    report.append(String.format("Skip -> Sigma: %.1f, MinPix: %d | Extracted %d objects (Out of bounds)%n",
                            sigma, minPix, totalObjectsExtracted));
                    continue;
                }

                int stableStars = 0;
                int transients = 0;
                List<SourceExtractor.DetectedObject> baseFrame = extractedFrames.get(baseIndex);

                // --- DETAILED CROSS-MATCH DEBUG ---
                if (DEBUG) {
                    System.out.println("      -> Cross-matching " + baseFrame.size() + " objects using Spatial Grid...");
                }

                // 1. Build the fast-search grids for the other frames
                List<SpatialGrid> searchGrids = new ArrayList<>();
                for (int i = 0; i < AUTO_TUNE_SAMPLE_SIZE; i++) {
                    if (i == baseIndex) {
                        searchGrids.add(null); // We don't need a grid for the base frame
                    } else {
                        // Cell size equal to search radius is highly optimal
                        searchGrids.add(new SpatialGrid(extractedFrames.get(i), SEARCH_RADIUS_PX));
                    }
                }

                // 2. Perform the lightning-fast cross-match
                for (SourceExtractor.DetectedObject candidate : baseFrame) {
                    int detections = 1;

                    for (int i = 0; i < AUTO_TUNE_SAMPLE_SIZE; i++) {
                        if (i == baseIndex) continue;

                        // Instantly ask the grid if there is a match nearby
                        if (searchGrids.get(i).hasMatch(candidate.x, candidate.y, SEARCH_RADIUS_PX)) {
                            detections++;
                        }
                    }

                    if (detections >= 3) {
                        stableStars++;
                    } else {
                        transients++;
                    }
                }

                int totalBaseObjects = baseFrame.size();
                double transientRatio = (totalBaseObjects == 0) ? 1.0 : (double) transients / totalBaseObjects;

                if (DEBUG) System.out.println(String.format("      [RESULT] Stars: %d | Transients (Noise): %d | Noise Ratio: %.1f%%", stableStars, transients, transientRatio * 100));

                report.append(String.format("Test -> Sigma: %.1f, MinPix: %d | Stars: %d | Transients: %d | Ratio: %.1f%%%n",
                        sigma, minPix, stableStars, transients, transientRatio * 100));

                boolean isClean = (transientRatio <= MAX_TRANSIENT_RATIO) &&
                        (stableStars >= MIN_STABLE_STARS);

                if (isClean) {
                    double score = stableStars - (transients * SCORE_WEIGHT_TRANSIENT_PENALTY) + (minPix * SCORE_WEIGHT_MINPIX_REWARD);

                    if (DEBUG) System.out.println("      [SCORE] Configuration passed noise gates! Score: " + score);

                    if (score > bestScore) {
                        if (DEBUG) System.out.println("      *** NEW BEST CONFIGURATION FOUND ***");
                        bestScore = score;
                        maxStableStars = stableStars;
                        bestConfig = testConfig;
                        bestRatio = transientRatio;
                        bestExtractedFrames = extractedFrames;
                    }
                } else {
                    if (DEBUG) System.out.println("      [REJECTED] Failed noise ratio or stable star requirements.");
                }
            }
        }

        // =====================================================================
        // PHASE 2: OPTICAL & KINEMATIC MEASUREMENT
        // =====================================================================
        if (bestConfig != null && bestExtractedFrames != null) {
            if (DEBUG) {
                System.out.println("\n--------------------------------------------------");
                System.out.println("[START] Phase 2: Optical & Kinematic Measurement...");
            }
            report.append("\n--- PHASE 2: Optical & Kinematic Measurement ---\n");

            List<Double> jitterDistances = new ArrayList<>();
            List<Double> elongations = new ArrayList<>();

            List<SourceExtractor.DetectedObject> frameA = bestExtractedFrames.get(baseIndex);
            List<SourceExtractor.DetectedObject> frameB = bestExtractedFrames.get(baseIndex + 1);

            for (SourceExtractor.DetectedObject objA : frameA) {
                elongations.add(objA.elongation);

                double closestDist = Double.MAX_VALUE;
                for (SourceExtractor.DetectedObject objB : frameB) {
                    double dx = objA.x - objB.x;
                    double dy = objA.y - objB.y;
                    double dist = Math.sqrt(dx * dx + dy * dy);
                    if (dist < closestDist) {
                        closestDist = dist;
                    }
                }

                if (closestDist <= SEARCH_RADIUS_PX) {
                    jitterDistances.add(closestDist);
                }
            }

            if (!jitterDistances.isEmpty()) {
                Collections.sort(jitterDistances);
                int indexPercentile = (int) (jitterDistances.size() * JITTER_PERCENTILE);
                double pJitter = jitterDistances.get(indexPercentile);

                bestConfig.maxStarJitter = Math.max(MIN_JITTER_FLOOR, pJitter * JITTER_SAFETY_MULTIPLIER);

                if (DEBUG) System.out.println(String.format("   -> Measured %.0fth Percentile Jitter: %.2f px -> Set maxStarJitter to %.2f",
                        JITTER_PERCENTILE * 100, pJitter, bestConfig.maxStarJitter));

                report.append(String.format("Measured %.0fth Percentile Jitter: %.2f px -> Set maxStarJitter to %.2f%n",
                        JITTER_PERCENTILE * 100, pJitter, bestConfig.maxStarJitter));
            }

            if (!elongations.isEmpty()) {
                Collections.sort(elongations);
                double medianElongation = elongations.get(elongations.size() / 2);

                bestConfig.maxElongationForFwhm = medianElongation + FWHM_ELONGATION_BUFFER;
                bestConfig.streakMinElongation = Math.max(bestConfig.streakMinElongation, medianElongation + STREAK_ELONGATION_BUFFER);

                if (DEBUG) System.out.println(String.format("   -> Measured Median Elongation: %.2f -> Set streakMinElongation to %.2f",
                        medianElongation, bestConfig.streakMinElongation));

                report.append(String.format("Measured Median Star Elongation: %.2f -> Set streakMinElongation to %.2f%n", medianElongation, bestConfig.streakMinElongation));
            }

            report.append("\n=== FINAL OPTIMIZED CONFIGURATION ===\n");
            report.append(String.format("Detection Sigma: %.1f%n", bestConfig.detectionSigmaMultiplier));
            report.append(String.format("Grow Sigma:      %.1f%n", bestConfig.growSigmaMultiplier));
            report.append(String.format("Min Pixels:      %d%n", bestConfig.minDetectionPixels));
            report.append(String.format("Max Star Jitter: %.2f px%n", bestConfig.maxStarJitter));
            report.append(String.format("Streak Min Elong:%.2f%n", bestConfig.streakMinElongation));

            result.optimizedConfig = bestConfig;
            result.bestStarCount = maxStableStars;
            result.bestTransientRatio = bestRatio;
            result.success = true;

            if (DEBUG) System.out.println("\n[SUCCESS] Auto-Tuning Complete.");

        } else {
            if (DEBUG) System.err.println("\n[FAILED] Could not find stable configuration. Falling back to base settings.");
            report.append("\nFAILED TO FIND STABLE CONFIGURATION. FALLING BACK TO BASE SETTINGS.\n");
            result.optimizedConfig = baseConfig;
            result.success = false;
        }

        result.telemetryReport = report.toString();
        return result;
    }

    // =====================================================================
    // QUALITY ASSURANCE UTILITIES
    // =====================================================================

    /**
     * Evaluates frames using the FrameQualityAnalyzer and returns the top quality frames.
     */
    private static List<ImageFrame> getBestSampleFrames(List<ImageFrame> allFrames, DetectionConfig config, StringBuilder report) {
        List<FrameQualityRecord> records = new ArrayList<>();
        int totalFrames = allFrames.size();

        for (int i = 0; i < totalFrames; i++) {
            ImageFrame frame = allFrames.get(i);

            if (DEBUG) System.out.println("   -> Evaluating Frame Quality: " + (i + 1) + " of " + totalFrames + " (Index: " + frame.sequenceIndex + ")...");

            FrameQualityAnalyzer.FrameMetrics metrics = FrameQualityAnalyzer.evaluateFrame(frame.pixelData, config);
            double score = metrics.backgroundNoise * metrics.medianFWHM;
            records.add(new FrameQualityRecord(frame, metrics, score));
        }

        records.sort(Comparator.comparingDouble(r -> r.qualityScore));

        List<ImageFrame> bestFrames = new ArrayList<>();
        report.append(String.format("Selected Top %d Frames based on Noise * FWHM:%n", AUTO_TUNE_SAMPLE_SIZE));

        for (int i = 0; i < Math.min(AUTO_TUNE_SAMPLE_SIZE, records.size()); i++) {
            FrameQualityRecord rec = records.get(i);
            bestFrames.add(rec.frame);
            report.append(String.format(" -> Frame %d: Noise=%.2f, FWHM=%.2f, Stars=%d (Score: %.2f)%n",
                    rec.frame.sequenceIndex, rec.metrics.backgroundNoise, rec.metrics.medianFWHM, rec.metrics.starCount, rec.qualityScore));
        }

        bestFrames.sort(Comparator.comparingInt(f -> f.sequenceIndex));
        return bestFrames;
    }



    // =====================================================================

    private static DetectionConfig cloneConfig(DetectionConfig base) {
        DetectionConfig clone = new DetectionConfig();
        clone.detectionSigmaMultiplier = base.detectionSigmaMultiplier;
        clone.growSigmaMultiplier = base.growSigmaMultiplier;
        clone.minDetectionPixels = base.minDetectionPixels;
        clone.qualitySigmaMultiplier = base.qualitySigmaMultiplier;
        clone.qualityMinDetectionPixels = base.qualityMinDetectionPixels;
        clone.errorFallbackValue = base.errorFallbackValue;
        clone.maxStarJitter = base.maxStarJitter;
        clone.starJitterExpansionFactor = base.starJitterExpansionFactor;
        clone.maxElongationForFwhm = base.maxElongationForFwhm;
        clone.streakMinElongation = base.streakMinElongation;
        clone.streakMinPixels = base.streakMinPixels;
        clone.edgeMarginPixels = base.edgeMarginPixels;
        clone.voidProximityRadius = base.voidProximityRadius;
        clone.voidThresholdFraction = base.voidThresholdFraction;
        clone.bgClippingIterations = base.bgClippingIterations;
        clone.bgClippingFactor = base.bgClippingFactor;
        clone.minFramesForAnalysis = base.minFramesForAnalysis;
        clone.fwhmSigmaDeviation = base.fwhmSigmaDeviation;
        clone.backgroundSigmaDeviation = base.backgroundSigmaDeviation;
        clone.starCountSigmaDeviation = base.starCountSigmaDeviation;
        clone.eccentricitySigmaDeviation = base.eccentricitySigmaDeviation;
        clone.minBackgroundDeviationADU = base.minBackgroundDeviationADU;
        clone.minEccentricityEnvelope = base.minEccentricityEnvelope;
        clone.minFwhmEnvelope = base.minFwhmEnvelope;
        clone.zeroSigmaFallback = base.zeroSigmaFallback;
        clone.stationaryDefectThreshold = base.stationaryDefectThreshold;
        clone.angleToleranceDegrees = base.angleToleranceDegrees;
        clone.trackMinFrameRatio = base.trackMinFrameRatio;
        clone.absoluteMaxPointsRequired = base.absoluteMaxPointsRequired;
        clone.maxJumpPixels = base.maxJumpPixels;
        clone.maxSizeRatio = base.maxSizeRatio;
        clone.maxFluxRatio = base.maxFluxRatio;
        clone.predictionTolerance = base.predictionTolerance;
        clone.rhythmAllowedVariance = base.rhythmAllowedVariance;
        clone.rhythmStationaryThreshold = base.rhythmStationaryThreshold;
        clone.rhythmMinConsistencyRatio = base.rhythmMinConsistencyRatio;
        return clone;
    }
}