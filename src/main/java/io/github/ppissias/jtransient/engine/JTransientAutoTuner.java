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
import io.github.ppissias.jtransient.core.MasterMapGenerator;
import io.github.ppissias.jtransient.core.SourceExtractor;
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

    // The number of highest-quality frames to sample for tuning.
    public static int AUTO_TUNE_SAMPLE_SIZE = 5;

    // The search radius (pixels) to find matching stars across frames for jitter analysis.
    public static double SEARCH_RADIUS_PX = 4.0;

    // --- NEW: STRICT NOISE GATES ---
    // The strict noise gate. The ratio of transients to total objects must be <= this value.
    // Lowered to 5% to demand highly pure tracking frames.
    public static double MAX_TRANSIENT_RATIO = 0.05;

    // The absolute minimum number of stable stars required across the sample to consider the sweep valid.
    public static int MIN_STABLE_STARS = 15;

    // The target number of stars. Once the engine finds this many, it stops rewarding raw count
    // and starts optimizing purely for safety (higher sigmas) and zero noise.
    public static double OPTIMAL_STAR_COUNT = 100.0;

    // The absolute maximum objects allowed across the sample frames to prevent memory/CPU freezing
    public static int MAX_TOTAL_EXTRACTED_OBJECTS = 500000;

    // The absolute minimum objects allowed across the sample frames to ensure valid data exists
    public static int MIN_TOTAL_EXTRACTED_OBJECTS = 10;

    // The parameter grid to sweep during Phase 1
    public static double[] SIGMAS_TO_TEST = {4.0, 5.0,  6.0, 7.0};
    public static int[] MIN_PIXELS_TO_TEST = {3, 5, 7, 9, 12};


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
    public static AutoTunerResult tune(List<ImageFrame> sampleFrames, DetectionConfig baseConfig, TransientEngineProgressListener listener) { // <--- Added Listener
        if (DEBUG) {
            System.out.println("\n==================================================");
            System.out.println("      JTRANSIENT AUTO-TUNER INITIALIZED");
            System.out.println("==================================================");
            System.out.println("Total frames to process: " + sampleFrames.size());
        }

        if (listener != null) {
            listener.onProgressUpdate(0, "Initializing Auto-Tuner...");
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

        int sensorHeight = sampleFrames.get(0).pixelData.length;
        int sensorWidth = sampleFrames.get(0).pixelData[0].length;

        if (listener != null) {
            listener.onProgressUpdate(5, "Generating sample Master Stack...");
        }

        // --- Generate the Master Stack once for the entire tuning process! ---
        if (DEBUG) System.out.println("\n[PRE-COMPUTE] Generating Master Stack for Auto-Tuning Sample...");
        short[][] masterStackData = MasterMapGenerator.createMedianMasterStack(sampleFrames);

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

        // --- PREPARE FOR PROGRESS TRACKING ---
        int totalCombinations = MIN_PIXELS_TO_TEST.length * SIGMAS_TO_TEST.length;
        int currentCombination = 0;

        for (int minPix : MIN_PIXELS_TO_TEST) {
            for (double sigma : SIGMAS_TO_TEST) {

                currentCombination++;
                if (listener != null) {
                    // Map this nested loop to progress from 5% to 90%
                    int progress = 5 + (int) (((double) currentCombination / totalCombinations) * 85.0);
                    listener.onProgressUpdate(progress, String.format("Testing Thresholds: Sigma %.1f, MinPix %d", sigma, minPix));
                }

                if (DEBUG) {
                    System.out.println("\n--------------------------------------------------");
                    System.out.println("   -> Testing Sigma: " + sigma + " | MinPix: " + minPix);
                }

                DetectionConfig testConfig = cloneConfig(baseConfig);
                testConfig.detectionSigmaMultiplier = sigma;
                testConfig.minDetectionPixels = minPix;
                //testConfig.growSigmaMultiplier = sigma * GROW_SIGMA_RATIO;

                // -----------------------------------------------------------
                // STEP A: EXTRACT THE MASTER MAP
                // -----------------------------------------------------------
                double masterSigma = testConfig.masterSigmaMultiplier;
                int masterMinPix = testConfig.masterMinDetectionPixels;

                // --- APPLY THE CORE-ONLY MASTER MAP OPTIMIZATIONS (Matching Engine Phase 0) ---
                double originalGrowSigma = testConfig.growSigmaMultiplier;
                testConfig.growSigmaMultiplier = masterSigma;

                int originalEdgeMargin = testConfig.edgeMarginPixels;
                int originalVoidProximity = testConfig.voidProximityRadius;
                double originalStreakElongation = testConfig.streakMinElongation;

                testConfig.edgeMarginPixels = 5;
                testConfig.voidProximityRadius = 5;
                testConfig.streakMinElongation = 999.0; // Prevent optically distorted corner stars from being rejected

                List<SourceExtractor.DetectedObject> masterStars = SourceExtractor.extractSources(
                        masterStackData, masterSigma, masterMinPix, testConfig
                );

                // --- RESTORE ORIGINAL CONFIGURATION ---
                testConfig.growSigmaMultiplier = originalGrowSigma;
                testConfig.edgeMarginPixels = originalEdgeMargin;
                testConfig.voidProximityRadius = originalVoidProximity;
                testConfig.streakMinElongation = originalStreakElongation;

                // -----------------------------------------------------------
                // STEP B: BUILD THE BINARY VETO MASK
                // -----------------------------------------------------------
                boolean[][] masterMask = new boolean[sensorHeight][sensorWidth];
                int dilationRadius = (int) Math.ceil(testConfig.maxStarJitter);

                for (SourceExtractor.DetectedObject mStar : masterStars) {
                    if (mStar.rawPixels != null) {
                        for (SourceExtractor.Pixel p : mStar.rawPixels) {
                            for (int dx = -dilationRadius; dx <= dilationRadius; dx++) {
                                for (int dy = -dilationRadius; dy <= dilationRadius; dy++) {
                                    if (dx * dx + dy * dy <= dilationRadius * dilationRadius) {
                                        int mx = p.x + dx;
                                        int my = p.y + dy;
                                        if (mx >= 0 && mx < sensorWidth && my >= 0 && my < sensorHeight) {
                                            masterMask[my][mx] = true;
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // -----------------------------------------------------------
                // STEP C: EXTRACT FRAMES & TEST AGAINST MASK
                // -----------------------------------------------------------
                List<List<SourceExtractor.DetectedObject>> extractedFrames = new ArrayList<>();
                int totalObjectsExtracted = 0;
                int totalStableStars = 0;
                int totalTransients = 0;

                for (ImageFrame frame : sampleFrames) {
                    List<SourceExtractor.DetectedObject> objects = SourceExtractor.extractSources(
                            frame.pixelData, testConfig.detectionSigmaMultiplier, testConfig.minDetectionPixels, testConfig
                    );
                    extractedFrames.add(objects);
                    totalObjectsExtracted += objects.size();

                    // Instantly check if the extracted objects hit the mask
                    for (SourceExtractor.DetectedObject obj : objects) {
                        boolean isPurged = false;
                        if (obj.rawPixels != null) {
                            for (SourceExtractor.Pixel p : obj.rawPixels) {
                                if (p.x >= 0 && p.x < sensorWidth && p.y >= 0 && p.y < sensorHeight) {
                                    if (masterMask[p.y][p.x]) {
                                        isPurged = true;
                                        break;
                                    }
                                }
                            }
                        }
                        if (isPurged) {
                            totalStableStars++;
                        } else {
                            totalTransients++;
                        }
                    }
                }

                if (totalObjectsExtracted > MAX_TOTAL_EXTRACTED_OBJECTS || totalObjectsExtracted < MIN_TOTAL_EXTRACTED_OBJECTS) {
                    if (DEBUG) System.out.println("      [REJECTED] Extracted " + totalObjectsExtracted + " total objects (Out of bounds).");
                    report.append(String.format("Skip -> Sigma: %.1f, MinPix: %d | Extracted %d objects (Out of bounds)%n",
                            sigma, minPix, totalObjectsExtracted));
                    continue;
                }

                double transientRatio = (totalObjectsExtracted == 0) ? 1.0 : (double) totalTransients / totalObjectsExtracted;

                if (DEBUG) System.out.println(String.format("      [RESULT] Stars: %d | Transients (Noise): %d | Noise Ratio: %.1f%%", totalStableStars, totalTransients, transientRatio * 100));

                report.append(String.format("Test -> Sigma: %.1f, MinPix: %d | Stars: %d | Transients: %d | Ratio: %.1f%%%n",
                        sigma, minPix, totalStableStars, totalTransients, transientRatio * 100));

                boolean isClean = (transientRatio <= MAX_TRANSIENT_RATIO) && (totalStableStars >= MIN_STABLE_STARS);

                if (isClean) {
                    // Average the stable stars per frame so the scoring formula remains balanced
                    double avgStableStars = (double) totalStableStars / sampleFrames.size();
                    double avgTransients = (double) totalTransients / sampleFrames.size();

                    // --- NEW SCORING HEURISTIC ---
                    // Cap the reward for raw star count. After ~100 stars, the tracker has plenty.
                    // This forces the algorithm to maximize Sigma (safety) instead of hunting for 5,000 noisy stars.
                    double cappedStars = Math.min(avgStableStars, OPTIMAL_STAR_COUNT);

                    // Start with the stars, massively penalize noise, and gently penalize higher thresholds
                    // to force the tuner to find the lowest (most sensitive) clean configuration.
                    double score = cappedStars
                            - (avgTransients * testConfig.scoreWeightTransientPenalty)
                            - (sigma * testConfig.scoreWeightSigmaPenalty)
                            - (minPix * testConfig.scoreWeightMinPixPenalty);

                    if (DEBUG) System.out.println("      [SCORE] Configuration passed noise gates! Score: " + score);

                    if (score > bestScore) {
                        if (DEBUG) System.out.println("      *** NEW BEST CONFIGURATION FOUND ***");
                        bestScore = score;
                        maxStableStars = totalStableStars; // Keep raw total for telemetry
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

            if (listener != null) {
                listener.onProgressUpdate(92, "Measuring optical jitter and morphology...");
            }

            if (DEBUG) {
                System.out.println("\n--------------------------------------------------");
                System.out.println("[START] Phase 2: Optical & Kinematic Measurement...");
            }
            report.append("\n--- PHASE 2: Optical & Kinematic Measurement ---\n");

            List<Double> jitterDistances = new ArrayList<>();
            List<Double> elongations = new ArrayList<>();

            // Measure jitter between consecutive frames in the best extraction
            for (int i = 0; i < bestExtractedFrames.size() - 1; i++) {
                List<SourceExtractor.DetectedObject> frameA = bestExtractedFrames.get(i);
                List<SourceExtractor.DetectedObject> frameB = bestExtractedFrames.get(i + 1);

                for (SourceExtractor.DetectedObject objA : frameA) {

                    double closestDist = Double.MAX_VALUE;
                    for (SourceExtractor.DetectedObject objB : frameB) {
                        double dx = objA.x - objB.x;
                        double dy = objA.y - objB.y;
                        double dist = Math.sqrt(dx * dx + dy * dy);
                        if (dist < closestDist) {
                            closestDist = dist;
                        }
                    }

                    // We only care about objects that are clearly stationary (real stars)
                    if (closestDist <= SEARCH_RADIUS_PX) {
                        jitterDistances.add(closestDist);
                        elongations.add(objA.elongation);
                    }
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
            report.append(String.format("Min Pixels:      %d%n", bestConfig.minDetectionPixels));
            report.append(String.format("Max Star Jitter: %.2f px%n", bestConfig.maxStarJitter));
            report.append(String.format("Streak Min Elong:%.2f%n", bestConfig.streakMinElongation));

            result.optimizedConfig = bestConfig;
            result.bestStarCount = maxStableStars;
            result.bestTransientRatio = bestRatio;
            result.success = true;

            if (listener != null) {
                listener.onProgressUpdate(100, "Auto-Tuning Complete!");
            }

            if (DEBUG) System.out.println("\n[SUCCESS] Auto-Tuning Complete.");

        } else {
            if (listener != null) {
                listener.onProgressUpdate(100, "Auto-Tuning Failed. Using base config.");
            }
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
        clone.masterSigmaMultiplier = base.masterSigmaMultiplier;
        clone.masterMinDetectionPixels = base.masterMinDetectionPixels;
        clone.growSigmaMultiplier = base.growSigmaMultiplier;
        clone.minDetectionPixels = base.minDetectionPixels;
        clone.qualitySigmaMultiplier = base.qualitySigmaMultiplier;
        clone.qualityMinDetectionPixels = base.qualityMinDetectionPixels;
        clone.errorFallbackValue = base.errorFallbackValue;
        clone.maxStarJitter = base.maxStarJitter;
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
        clone.timeBasedVelocityTolerance = base.timeBasedVelocityTolerance;
        clone.absoluteMaxPointsRequired = base.absoluteMaxPointsRequired;
        clone.maxJumpPixels = base.maxJumpPixels;
        clone.maxSizeRatio = base.maxSizeRatio;
        clone.maxFluxRatio = base.maxFluxRatio;
        clone.predictionTolerance = base.predictionTolerance;
        clone.rhythmAllowedVariance = base.rhythmAllowedVariance;
        clone.rhythmStationaryThreshold = base.rhythmStationaryThreshold;
        clone.rhythmMinConsistencyRatio = base.rhythmMinConsistencyRatio;
        clone.scoreWeightTransientPenalty = base.scoreWeightTransientPenalty;
        clone.scoreWeightSigmaPenalty = base.scoreWeightSigmaPenalty;
        clone.scoreWeightMinPixPenalty = base.scoreWeightMinPixPenalty;
        return clone;
    }
}