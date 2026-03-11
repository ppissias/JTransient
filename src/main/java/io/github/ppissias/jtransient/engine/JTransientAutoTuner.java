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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class JTransientAutoTuner {

    public static class AutoTunerResult {
        public DetectionConfig optimizedConfig;
        public String telemetryReport;
        public int bestStarCount;
        public double bestTransientRatio;
    }

    /**
     * Automatically determines the optimal extraction and kinematic settings for a given image sequence.
     */
    public static AutoTunerResult tune(List<ImageFrame> allFrames, DetectionConfig baseConfig) {
        if (JTransientEngine.DEBUG) {
            System.out.println("\n--- JTRANSIENT AUTO-TUNER INITIALIZED ---");
        }

        AutoTunerResult result = new AutoTunerResult();
        StringBuilder report = new StringBuilder("=== JTransient Auto-Tuning Report ===\n");

        int numFrames = allFrames.size();
        if (numFrames < 5) {
            report.append("Warning: Not enough frames for Auto-Tuning. Falling back to base config.\n");
            result.optimizedConfig = baseConfig;
            result.telemetryReport = report.toString();
            return result;
        }

        int startIndex = numFrames / 2 - 2;
        List<ImageFrame> sampleFrames = allFrames.subList(startIndex, startIndex + 5);
        report.append("Sampled 5 frames starting at index ").append(startIndex).append("\n\n");

        // =====================================================================
        // PHASE 1: THE SIGNAL-TO-NOISE SWEEP
        // =====================================================================
        report.append("--- PHASE 1: Detection Threshold Sweep ---\n");
        double[] sigmasToTest = {2.5, 3.0, 3.5, 4.0, 5.0, 6.0};

        // Expanded to include 7 to allow for more conservative cuts
        int[] minPixelsToTest = {3, 5, 7};

        DetectionConfig bestConfig = null;
        List<List<SourceExtractor.DetectedObject>> bestExtractedFrames = null;

        int maxStableStars = -1;
        double bestRatio = 1.0;

        // NEW: Track the best overall fitness score
        double bestScore = -Double.MAX_VALUE;

        for (int minPix : minPixelsToTest) {
            for (double sigma : sigmasToTest) {

                DetectionConfig testConfig = cloneConfig(baseConfig);
                testConfig.detectionSigmaMultiplier = sigma;
                testConfig.minDetectionPixels = minPix;
                testConfig.growSigmaMultiplier = sigma * 0.5;

                List<List<SourceExtractor.DetectedObject>> extractedFrames = new ArrayList<>();
                int totalObjectsExtracted = 0;

                for (ImageFrame frame : sampleFrames) {
                    List<SourceExtractor.DetectedObject> objects = SourceExtractor.extractSources(
                            frame.pixelData,
                            testConfig.detectionSigmaMultiplier,
                            testConfig.minDetectionPixels,
                            testConfig
                    );
                    extractedFrames.add(objects);
                    totalObjectsExtracted += objects.size();
                }

                if (totalObjectsExtracted > 50000 || totalObjectsExtracted < 10) {
                    report.append(String.format("Skip -> Sigma: %.1f, MinPix: %d | Extracted %d objects (Out of bounds)%n",
                            sigma, minPix, totalObjectsExtracted));
                    continue;
                }

                int stableStars = 0;
                int transients = 0;
                double generousBaselineJitter = 10.0;
                List<SourceExtractor.DetectedObject> baseFrame = extractedFrames.get(2);

                for (SourceExtractor.DetectedObject candidate : baseFrame) {
                    int detections = 1;

                    for (int i = 0; i < 5; i++) {
                        if (i == 2) continue;
                        for (SourceExtractor.DetectedObject other : extractedFrames.get(i)) {
                            double dx = candidate.x - other.x;
                            double dy = candidate.y - other.y;
                            if (Math.sqrt(dx * dx + dy * dy) <= generousBaselineJitter) {
                                detections++;
                                break;
                            }
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

                report.append(String.format("Test -> Sigma: %.1f, MinPix: %d | Stars: %d | Transients: %d | Ratio: %.1f%%%n",
                        sigma, minPix, stableStars, transients, transientRatio * 100));

                // --- THE FIX: STRICTER GATES & FITNESS SCORE ---

                // 1. Strict Gate: Noise ratio must be under 5% AND absolute transients under 150.
                // We also require at least 15 stable stars to consider the map valid.
                boolean isClean = (transientRatio <= 0.05) && (transients <= 150) && (stableStars >= 15);

                if (isClean) {
                    // 2. Fitness Score: Reward stable stars, but heavily penalize transients.
                    // We also add a bonus to higher 'minPixels' to favor cleaner, larger shapes 
                    // if the star count difference is negligible.
                    double score = stableStars - (transients * 10.0) + (minPix * 25.0);

                    if (score > bestScore) {
                        bestScore = score;
                        maxStableStars = stableStars;
                        bestConfig = testConfig;
                        bestRatio = transientRatio;
                        bestExtractedFrames = extractedFrames;
                    }
                }
            }
        }

        // =====================================================================
        // PHASE 2: OPTICAL & KINEMATIC MEASUREMENT
        // =====================================================================
        if (bestConfig != null && bestExtractedFrames != null) {
            report.append("\n--- PHASE 2: Optical & Kinematic Measurement ---\n");

            List<Double> jitterDistances = new ArrayList<>();
            List<Double> elongations = new ArrayList<>();

            List<SourceExtractor.DetectedObject> frameA = bestExtractedFrames.get(2);
            List<SourceExtractor.DetectedObject> frameB = bestExtractedFrames.get(3);

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
                if (closestDist < 10.0) {
                    jitterDistances.add(closestDist);
                }
            }

            if (!jitterDistances.isEmpty()) {
                Collections.sort(jitterDistances);
                int index90th = (int) (jitterDistances.size() * 0.90);
                double p90Jitter = jitterDistances.get(index90th);

                bestConfig.maxStarJitter = Math.max(1.0, p90Jitter * 1.5);
                report.append(String.format("Measured 90th Percentile Jitter: %.2f px -> Set maxStarJitter to %.2f%n", p90Jitter, bestConfig.maxStarJitter));
            }

            if (!elongations.isEmpty()) {
                Collections.sort(elongations);
                double medianElongation = elongations.get(elongations.size() / 2);

                bestConfig.maxElongationForFwhm = medianElongation + 0.4;
                bestConfig.streakMinElongation = Math.max(bestConfig.streakMinElongation, medianElongation + 1.0);

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

        } else {
            report.append("\nFAILED TO FIND STABLE CONFIGURATION. FALLING BACK TO BASE SETTINGS.\n");
            result.optimizedConfig = baseConfig;
        }

        result.telemetryReport = report.toString();

        if (JTransientEngine.DEBUG) {
            System.out.println(result.telemetryReport);
        }

        return result;
    }

    private static DetectionConfig cloneConfig(DetectionConfig base) {
        DetectionConfig clone = new DetectionConfig();

        clone.detectionSigmaMultiplier = base.detectionSigmaMultiplier;
        clone.growSigmaMultiplier = base.growSigmaMultiplier;
        clone.minDetectionPixels = base.minDetectionPixels;

        clone.maxStarJitter = base.maxStarJitter;
        clone.starJitterExpansionFactor = base.starJitterExpansionFactor;

        clone.maxElongationForFwhm = base.maxElongationForFwhm;
        clone.streakMinElongation = base.streakMinElongation;
        clone.pointSourceMinPixels = base.pointSourceMinPixels;
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
        clone.requiredDetectionsToBeStar = base.requiredDetectionsToBeStar;
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