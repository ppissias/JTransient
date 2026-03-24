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
import io.github.ppissias.jtransient.quality.FrameQualityAnalyzer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class JTransientAutoTuner {

    public static boolean DEBUG = true;

    // =========================================================================
    // AUTO-TUNER CONSTRAINTS
    // =========================================================================

    public static int AUTO_TUNE_SAMPLE_SIZE = 5;

    public static double SEARCH_RADIUS_PX = 4.0;

    // Hard safety gates
    public static double MAX_TRANSIENT_RATIO = 0.05;
    public static int MIN_STABLE_STARS = 15;
    public static double OPTIMAL_STAR_COUNT = 100.0;
    public static int MAX_TOTAL_EXTRACTED_OBJECTS = 500000;
    public static int MIN_TOTAL_EXTRACTED_OBJECTS = 10;

    // Sweep dimensions
    public static double[] SIGMAS_TO_TEST = {3.5, 4.0, 5.0, 6.0, 7.0};
    public static int[] MIN_PIXELS_TO_TEST = {3, 5, 7, 9, 12};

    // growSigma = detectionSigma - growDelta
    public static double[] GROW_DELTAS_TO_TEST = {0.75, 1.25, 1.75};

    // Tune the actual binary-veto behavior as well
    public static double[] MASK_OVERLAP_TO_TEST = {0.65, 0.75, 0.85};

    // Scoring terms
    public static double SCORE_WEIGHT_STABLE = 100.0;
    public static double SCORE_WEIGHT_TRANSIENT = 120.0;
    public static double SCORE_WEIGHT_VARIANCE = 60.0;
    public static double SCORE_WEIGHT_MASK_COVERAGE = 50.0;
    public static double SCORE_WEIGHT_HARSHNESS = 20.0;

    // Penalize oversized veto masks
    public static double SOFT_MASK_COVERAGE_START = 0.08;
    public static double HARD_MASK_COVERAGE_REJECT = 0.25;

    // CV normalization anchors
    public static double STABLE_STARS_CV_ANCHOR = 0.25;
    public static double TRANSIENTS_CV_ANCHOR = 0.75;

    // Optical measurement
    public static double JITTER_PERCENTILE = 0.90;
    public static double MIN_JITTER_FLOOR = 1.0;
    public static double JITTER_SAFETY_MULTIPLIER = 2.0;

    public static double FWHM_ELONGATION_BUFFER = 0.4;
    public static double STREAK_ELONGATION_BUFFER = 1.0;

    // =========================================================================
    // ROI TUNING SETTINGS
    // =========================================================================

    public static int AUTO_TUNE_CROP_COUNT = 3;
    public static int AUTO_TUNE_BORDER_MARGIN = 200;
    public static int AUTO_TUNE_PREFERRED_CROP_SIZE_LARGE = 1024;
    public static int AUTO_TUNE_PREFERRED_CROP_SIZE_SMALL = 768;
    public static int AUTO_TUNE_MIN_CROP_SIZE = 384;

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

    private static class CropRegion {
        final int x;
        final int y;
        final int width;
        final int height;
        final String label;

        CropRegion(int x, int y, int width, int height, String label) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            this.label = label;
        }
    }

    private static class CroppedFrame {
        final short[][] pixelData;
        final int sequenceIndex;

        CroppedFrame(short[][] pixelData, int sequenceIndex) {
            this.pixelData = pixelData;
            this.sequenceIndex = sequenceIndex;
        }
    }

    /**
     * Automatically determines the optimal extraction and kinematic settings for a given image sequence.
     */
    public static AutoTunerResult tune(List<ImageFrame> allFrames,
                                       DetectionConfig baseConfig,
                                       TransientEngineProgressListener listener) {

        if (DEBUG) {
            System.out.println("\n==================================================");
            System.out.println("      JTRANSIENT AUTO-TUNER INITIALIZED");
            System.out.println("==================================================");
            System.out.println("Total frames available: " + allFrames.size());
        }

        if (listener != null) {
            listener.onProgressUpdate(0, "Initializing Auto-Tuner...");
        }

        AutoTunerResult result = new AutoTunerResult();
        StringBuilder report = new StringBuilder("=== JTransient Auto-Tuning Report ===\n");

        if (allFrames.size() < AUTO_TUNE_SAMPLE_SIZE) {
            report.append("Warning: Not enough frames for Auto-Tuning. Falling back to base config.\n");
            result.optimizedConfig = baseConfig;
            result.success = false;
            result.telemetryReport = report.toString();
            return result;
        }

        if (listener != null) {
            listener.onProgressUpdate(3, "Evaluating frame quality and selecting tuning sample...");
        }

        List<ImageFrame> tuningFrames = getBestSampleFrames(allFrames, baseConfig, report);

        if (tuningFrames.isEmpty()) {
            report.append("Failed to select tuning sample. Falling back to base config.\n");
            result.optimizedConfig = baseConfig;
            result.success = false;
            result.telemetryReport = report.toString();
            return result;
        }

        int sensorHeight = tuningFrames.get(0).pixelData.length;
        int sensorWidth = tuningFrames.get(0).pixelData[0].length;

        if (listener != null) {
            listener.onProgressUpdate(6, "Selecting representative tuning crops...");
        }

        List<CropRegion> cropRegions = buildTuningCropRegions(sensorWidth, sensorHeight, report);
        if (cropRegions.isEmpty()) {
            report.append("Failed to create valid tuning crops. Falling back to base config.\n");
            result.optimizedConfig = baseConfig;
            result.success = false;
            result.telemetryReport = report.toString();
            return result;
        }

        if (DEBUG) {
            System.out.println("\n[ROI] Using " + cropRegions.size() + " crops for tuning.");
            for (CropRegion r : cropRegions) {
                System.out.println("   -> " + r.label + ": x=" + r.x + ", y=" + r.y + ", w=" + r.width + ", h=" + r.height);
            }
        }

        // Pre-crop the selected tuning frames once, so the sweep does not repeatedly crop.
        List<List<CroppedFrame>> croppedFramesByRegion = new ArrayList<>();
        for (CropRegion region : cropRegions) {
            croppedFramesByRegion.add(buildCroppedFramesForRegion(tuningFrames, region));
        }

        if (listener != null) {
            listener.onProgressUpdate(8, "Preparing cropped tuning stacks...");
        }

        // =====================================================================
        // PHASE 1: THRESHOLD / GROW / VETO SWEEP
        // =====================================================================
        if (DEBUG) System.out.println("\n[START] Phase 1: Detection Threshold Sweep on cropped regions...");
        report.append("\n--- PHASE 1: Detection Threshold Sweep (Cropped ROIs) ---\n");

        DetectionConfig bestConfig = null;
        List<List<List<SourceExtractor.DetectedObject>>> bestStableOnlyFramesByCrop = null;

        int bestStableStars = -1;
        double bestTransientRatio = 1.0;
        double bestScore = -Double.MAX_VALUE;

        int totalCombinations =
                MIN_PIXELS_TO_TEST.length
                        * SIGMAS_TO_TEST.length
                        * GROW_DELTAS_TO_TEST.length
                        * MASK_OVERLAP_TO_TEST.length;

        int currentCombination = 0;

        for (int minPix : MIN_PIXELS_TO_TEST) {
            for (double sigma : SIGMAS_TO_TEST) {
                for (double growDelta : GROW_DELTAS_TO_TEST) {
                    for (double overlapFraction : MASK_OVERLAP_TO_TEST) {

                        currentCombination++;
                        double growSigma = Math.max(1.0, sigma - growDelta);

                        if (listener != null) {
                            int progress = 8 + (int) (((double) currentCombination / totalCombinations) * 82.0);
                            listener.onProgressUpdate(
                                    progress,
                                    String.format("Testing σ=%.1f, grow=%.2f, minPix=%d, overlap=%.2f",
                                            sigma, growSigma, minPix, overlapFraction)
                            );
                        }

                        if (DEBUG) {
                            System.out.println("\n--------------------------------------------------");
                            System.out.println(" -> Testing Sigma: " + sigma
                                    + " | GrowSigma: " + growSigma
                                    + " | MinPix: " + minPix
                                    + " | MaskOverlap: " + overlapFraction);
                        }

                        DetectionConfig testConfig = baseConfig.clone();
                        testConfig.detectionSigmaMultiplier = sigma;
                        testConfig.growSigmaMultiplier = growSigma;
                        testConfig.minDetectionPixels = minPix;
                        testConfig.maxMaskOverlapFraction = overlapFraction;

                        int totalObjectsExtracted = 0;
                        int totalStableStars = 0;
                        int totalTransients = 0;

                        int totalMaskTrueCount = 0;
                        int totalMaskArea = 0;

                        List<Integer> stableCountsPerCropFrame = new ArrayList<>();
                        List<Integer> transientCountsPerCropFrame = new ArrayList<>();

                        List<List<List<SourceExtractor.DetectedObject>>> stableOnlyFramesByCrop = new ArrayList<>();

                        boolean rejected = false;

                        // =====================================================
                        // Run the exact same evaluation logic independently on
                        // each crop, then aggregate totals/penalties across crops
                        // =====================================================
                        for (int cropIndex = 0; cropIndex < cropRegions.size(); cropIndex++) {

                            CropRegion cropRegion = cropRegions.get(cropIndex);
                            List<CroppedFrame> cropFrames = croppedFramesByRegion.get(cropIndex);

                            int cropHeight = cropRegion.height;
                            int cropWidth = cropRegion.width;
                            totalMaskArea += cropWidth * cropHeight;

                            // STEP A: Build master stack for this crop only
                            short[][] masterStackData = createMedianMasterStackForCrops(cropFrames);

                            double masterSigma = testConfig.masterSigmaMultiplier;
                            int masterMinPix = testConfig.masterMinDetectionPixels;

                            double originalGrowSigma = testConfig.growSigmaMultiplier;
                            int originalEdgeMargin = testConfig.edgeMarginPixels;
                            int originalVoidProximity = testConfig.voidProximityRadius;

                            testConfig.growSigmaMultiplier = masterSigma;
                            testConfig.edgeMarginPixels = 5;
                            testConfig.voidProximityRadius = 5;

                            List<SourceExtractor.DetectedObject> masterStars =
                                    SourceExtractor.extractSources(masterStackData, masterSigma, masterMinPix, testConfig);

                            testConfig.growSigmaMultiplier = originalGrowSigma;
                            testConfig.edgeMarginPixels = originalEdgeMargin;
                            testConfig.voidProximityRadius = originalVoidProximity;

                            if (masterStars == null || masterStars.isEmpty()) {
                                if (DEBUG) {
                                    System.out.println("      [REJECTED] Crop " + cropRegion.label + " master extraction found no stars.");
                                }
                                rejected = true;
                                break;
                            }

                            // STEP B: Build veto mask for this crop
                            boolean[][] masterMask = new boolean[cropHeight][cropWidth];
                            int dilationRadius = (int) Math.ceil(testConfig.maxStarJitter);
                            int maskTrueCountThisCrop = 0;

                            for (SourceExtractor.DetectedObject mStar : masterStars) {
                                if (mStar.rawPixels == null) continue;

                                for (SourceExtractor.Pixel p : mStar.rawPixels) {
                                    for (int dx = -dilationRadius; dx <= dilationRadius; dx++) {
                                        for (int dy = -dilationRadius; dy <= dilationRadius; dy++) {
                                            if (dx * dx + dy * dy <= dilationRadius * dilationRadius) {
                                                int mx = p.x + dx;
                                                int my = p.y + dy;
                                                if (mx >= 0 && mx < cropWidth && my >= 0 && my < cropHeight) {
                                                    if (!masterMask[my][mx]) {
                                                        masterMask[my][mx] = true;
                                                        maskTrueCountThisCrop++;
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            totalMaskTrueCount += maskTrueCountThisCrop;

                            double maskCoverageThisCrop = (double) maskTrueCountThisCrop / (cropWidth * (double) cropHeight);
                            if (maskCoverageThisCrop > HARD_MASK_COVERAGE_REJECT) {
                                if (DEBUG) {
                                    System.out.printf("      [REJECTED] Crop %s mask coverage too high: %.2f%%%n",
                                            cropRegion.label, maskCoverageThisCrop * 100.0);
                                }
                                rejected = true;
                                break;
                            }

                            // STEP C: Extract each cropped frame and test against this crop mask
                            List<List<SourceExtractor.DetectedObject>> stableOnlyFramesThisCrop = new ArrayList<>();

                            for (CroppedFrame frame : cropFrames) {
                                List<SourceExtractor.DetectedObject> objects = SourceExtractor.extractSources(
                                        frame.pixelData,
                                        testConfig.detectionSigmaMultiplier,
                                        testConfig.minDetectionPixels,
                                        testConfig
                                );

                                totalObjectsExtracted += objects.size();

                                List<SourceExtractor.DetectedObject> stableObjectsThisFrame = new ArrayList<>();
                                int stableThisFrame = 0;
                                int transientThisFrame = 0;

                                for (SourceExtractor.DetectedObject obj : objects) {
                                    double overlap = computeMaskOverlapFraction(obj, masterMask, cropWidth, cropHeight);

                                    if (overlap >= testConfig.maxMaskOverlapFraction) {
                                        stableThisFrame++;
                                        stableObjectsThisFrame.add(obj);
                                    } else {
                                        transientThisFrame++;
                                    }
                                }

                                stableOnlyFramesThisCrop.add(stableObjectsThisFrame);
                                stableCountsPerCropFrame.add(stableThisFrame);
                                transientCountsPerCropFrame.add(transientThisFrame);

                                totalStableStars += stableThisFrame;
                                totalTransients += transientThisFrame;
                            }

                            stableOnlyFramesByCrop.add(stableOnlyFramesThisCrop);
                        }

                        if (rejected) {
                            report.append(String.format(
                                    "Test -> Sigma: %.1f, Grow: %.2f, MinPix: %d, Overlap: %.2f [REJECTED: crop failure]%n",
                                    sigma, growSigma, minPix, overlapFraction));
                            continue;
                        }

                        if (totalObjectsExtracted > MAX_TOTAL_EXTRACTED_OBJECTS
                                || totalObjectsExtracted < MIN_TOTAL_EXTRACTED_OBJECTS) {
                            if (DEBUG) {
                                System.out.println("      [REJECTED] Extracted " + totalObjectsExtracted + " total objects (Out of bounds).");
                            }
                            report.append(String.format(
                                    "Skip -> Sigma: %.1f, Grow: %.2f, MinPix: %d, Overlap: %.2f | Extracted %d objects (Out of bounds)%n",
                                    sigma, growSigma, minPix, overlapFraction, totalObjectsExtracted));
                            continue;
                        }

                        double transientRatio = (totalObjectsExtracted == 0)
                                ? 1.0
                                : (double) totalTransients / totalObjectsExtracted;

                        double aggregatedMaskCoverage = (totalMaskArea == 0)
                                ? 1.0
                                : (double) totalMaskTrueCount / totalMaskArea;

                        if (DEBUG) {
                            System.out.println(String.format(
                                    "      [RESULT] StableStars: %d | Transients: %d | Ratio: %.2f%% | AggregatedMaskCoverage: %.2f%%",
                                    totalStableStars, totalTransients, transientRatio * 100.0, aggregatedMaskCoverage * 100.0));
                        }

                        boolean isClean = (transientRatio <= MAX_TRANSIENT_RATIO)
                                && (totalStableStars >= MIN_STABLE_STARS);

                        if (!isClean) {
                            if (DEBUG) System.out.println("      [REJECTED] Failed noise ratio or stable star requirements.");
                            report.append(String.format(
                                    "Test -> Sigma: %.1f, Grow: %.2f, MinPix: %d, Overlap: %.2f | Stable: %d | Transients: %d | Ratio: %.2f%% [REJECTED]%n",
                                    sigma, growSigma, minPix, overlapFraction, totalStableStars, totalTransients, transientRatio * 100.0));
                            continue;
                        }

                        // =====================================================
                        // Scoring
                        // =====================================================
                        double cropFrameCount = tuningFrames.size() * (double) cropRegions.size();
                        double avgStableStars = totalStableStars / cropFrameCount;

                        double stableYieldScore = clamp01(avgStableStars / OPTIMAL_STAR_COUNT);
                        double transientPenalty = clamp01(transientRatio / MAX_TRANSIENT_RATIO);

                        double stableCv = coefficientOfVariation(stableCountsPerCropFrame);
                        double transientCv = coefficientOfVariation(transientCountsPerCropFrame);

                        double variancePenalty =
                                0.6 * clamp01(stableCv / STABLE_STARS_CV_ANCHOR) +
                                        0.4 * clamp01(transientCv / TRANSIENTS_CV_ANCHOR);

                        double maskCoveragePenalty = 0.0;
                        if (aggregatedMaskCoverage > SOFT_MASK_COVERAGE_START) {
                            maskCoveragePenalty = clamp01(
                                    (aggregatedMaskCoverage - SOFT_MASK_COVERAGE_START)
                                            / (HARD_MASK_COVERAGE_REJECT - SOFT_MASK_COVERAGE_START)
                            );
                        }

                        double harshnessPenalty =
                                0.40 * normalize(sigma, SIGMAS_TO_TEST[0], SIGMAS_TO_TEST[SIGMAS_TO_TEST.length - 1]) +
                                        0.30 * normalize(minPix, MIN_PIXELS_TO_TEST[0], MIN_PIXELS_TO_TEST[MIN_PIXELS_TO_TEST.length - 1]) +
                                        0.30 * normalize(growDelta, GROW_DELTAS_TO_TEST[0], GROW_DELTAS_TO_TEST[GROW_DELTAS_TO_TEST.length - 1]);

                        double score =
                                (SCORE_WEIGHT_STABLE * stableYieldScore)
                                        - (SCORE_WEIGHT_TRANSIENT * transientPenalty)
                                        - (SCORE_WEIGHT_VARIANCE * variancePenalty)
                                        - (SCORE_WEIGHT_MASK_COVERAGE * maskCoveragePenalty)
                                        - (SCORE_WEIGHT_HARSHNESS * harshnessPenalty);

                        if (DEBUG) {
                            System.out.printf(
                                    "      [SCORE] %.2f | stable=%.3f transient=%.3f variance=%.3f mask=%.3f harsh=%.3f%n",
                                    score, stableYieldScore, transientPenalty, variancePenalty, maskCoveragePenalty, harshnessPenalty);
                        }

                        report.append(String.format(
                                "Test -> Sigma: %.1f, Grow: %.2f, MinPix: %d, Overlap: %.2f | Stable: %d | Transients: %d | Ratio: %.2f%% | StableCV: %.3f | TransCV: %.3f | Mask: %.2f%% | Score: %.2f%n",
                                sigma, growSigma, minPix, overlapFraction,
                                totalStableStars, totalTransients, transientRatio * 100.0,
                                stableCv, transientCv, aggregatedMaskCoverage * 100.0, score));

                        if (score > bestScore) {
                            if (DEBUG) System.out.println("      *** NEW BEST CONFIGURATION FOUND ***");

                            bestScore = score;
                            bestStableStars = totalStableStars;
                            bestTransientRatio = transientRatio;
                            bestConfig = testConfig.clone();
                            bestStableOnlyFramesByCrop = stableOnlyFramesByCrop;
                        }
                    }
                }
            }
        }

        // =====================================================================
        // PHASE 2: OPTICAL & KINEMATIC MEASUREMENT
        // Uses stable stars from all crops
        // =====================================================================
        if (bestConfig != null && bestStableOnlyFramesByCrop != null) {

            if (listener != null) {
                listener.onProgressUpdate(93, "Measuring optical jitter and morphology...");
            }

            if (DEBUG) {
                System.out.println("\n--------------------------------------------------");
                System.out.println("[START] Phase 2: Optical & Kinematic Measurement...");
            }
            report.append("\n--- PHASE 2: Optical & Kinematic Measurement ---\n");

            List<Double> jitterDistances = new ArrayList<>();
            List<Double> elongations = new ArrayList<>();

            for (List<List<SourceExtractor.DetectedObject>> stableFramesThisCrop : bestStableOnlyFramesByCrop) {
                for (int i = 0; i < stableFramesThisCrop.size() - 1; i++) {
                    List<SourceExtractor.DetectedObject> frameA = stableFramesThisCrop.get(i);
                    List<SourceExtractor.DetectedObject> frameB = stableFramesThisCrop.get(i + 1);

                    for (SourceExtractor.DetectedObject objA : frameA) {
                        SourceExtractor.DetectedObject closest = null;
                        double closestDist = Double.MAX_VALUE;

                        for (SourceExtractor.DetectedObject objB : frameB) {
                            double dx = objA.x - objB.x;
                            double dy = objA.y - objB.y;
                            double dist = Math.sqrt(dx * dx + dy * dy);

                            if (dist < closestDist) {
                                closestDist = dist;
                                closest = objB;
                            }
                        }

                        if (closest != null && closestDist <= SEARCH_RADIUS_PX) {
                            jitterDistances.add(closestDist);
                            elongations.add(objA.elongation);
                        }
                    }
                }
            }

            if (!jitterDistances.isEmpty()) {
                Collections.sort(jitterDistances);
                double pJitter = getPercentileFromSorted(jitterDistances, JITTER_PERCENTILE);

                bestConfig.maxStarJitter = Math.max(MIN_JITTER_FLOOR, pJitter * JITTER_SAFETY_MULTIPLIER);

                if (DEBUG) {
                    System.out.println(String.format(
                            "   -> Measured %.0fth Percentile Jitter: %.2f px -> Set maxStarJitter to %.2f",
                            JITTER_PERCENTILE * 100, pJitter, bestConfig.maxStarJitter));
                }

                report.append(String.format(
                        "Measured %.0fth Percentile Jitter: %.2f px -> Set maxStarJitter to %.2f%n",
                        JITTER_PERCENTILE * 100, pJitter, bestConfig.maxStarJitter));
            }

            if (!elongations.isEmpty()) {
                Collections.sort(elongations);
                double medianElongation = getMedianFromSorted(elongations);

                bestConfig.maxElongationForFwhm = medianElongation + FWHM_ELONGATION_BUFFER;
                bestConfig.streakMinElongation =
                        Math.max(bestConfig.streakMinElongation, medianElongation + STREAK_ELONGATION_BUFFER);

                if (DEBUG) {
                    System.out.println(String.format(
                            "   -> Measured Median Elongation: %.2f -> Set maxElongationForFwhm to %.2f, streakMinElongation to %.2f",
                            medianElongation, bestConfig.maxElongationForFwhm, bestConfig.streakMinElongation));
                }

                report.append(String.format(
                        "Measured Median Star Elongation: %.2f -> Set maxElongationForFwhm to %.2f, streakMinElongation to %.2f%n",
                        medianElongation, bestConfig.maxElongationForFwhm, bestConfig.streakMinElongation));
            }

            report.append("\n=== FINAL OPTIMIZED CONFIGURATION ===\n");
            report.append(String.format("Detection Sigma:        %.2f%n", bestConfig.detectionSigmaMultiplier));
            report.append(String.format("Grow Sigma:             %.2f%n", bestConfig.growSigmaMultiplier));
            report.append(String.format("Min Pixels:             %d%n", bestConfig.minDetectionPixels));
            report.append(String.format("Mask Overlap Fraction:  %.2f%n", bestConfig.maxMaskOverlapFraction));
            report.append(String.format("Max Star Jitter:        %.2f px%n", bestConfig.maxStarJitter));
            report.append(String.format("Max Elongation FWHM:    %.2f%n", bestConfig.maxElongationForFwhm));
            report.append(String.format("Streak Min Elongation:  %.2f%n", bestConfig.streakMinElongation));

            result.optimizedConfig = bestConfig;
            result.bestStarCount = bestStableStars;
            result.bestTransientRatio = bestTransientRatio;
            result.success = true;

            if (listener != null) {
                listener.onProgressUpdate(100, "Auto-Tuning Complete!");
            }

            if (DEBUG) System.out.println("\n[SUCCESS] Auto-Tuning Complete.");

        } else {
            if (listener != null) {
                listener.onProgressUpdate(100, "Auto-Tuning Failed. Using base config.");
            }
            if (DEBUG) {
                System.err.println("\n[FAILED] Could not find stable configuration. Falling back to base settings.");
            }
            report.append("\nFAILED TO FIND STABLE CONFIGURATION. FALLING BACK TO BASE SETTINGS.\n");
            result.optimizedConfig = baseConfig;
            result.success = false;
        }

        result.telemetryReport = report.toString();
        return result;
    }

    // =====================================================================
    // ROI HELPERS
    // =====================================================================

    private static List<CropRegion> buildTuningCropRegions(int sensorWidth, int sensorHeight, StringBuilder report) {
        List<CropRegion> regions = new ArrayList<>();

        int usableWidth = sensorWidth - (2 * AUTO_TUNE_BORDER_MARGIN);
        int usableHeight = sensorHeight - (2 * AUTO_TUNE_BORDER_MARGIN);

        if (usableWidth <= 0 || usableHeight <= 0) {
            report.append("Frame is too small to honor the requested crop border margin.\n");
            return regions;
        }

        int cropSize;
        if (usableWidth >= AUTO_TUNE_PREFERRED_CROP_SIZE_LARGE && usableHeight >= AUTO_TUNE_PREFERRED_CROP_SIZE_LARGE) {
            cropSize = AUTO_TUNE_PREFERRED_CROP_SIZE_LARGE;
        } else if (usableWidth >= AUTO_TUNE_PREFERRED_CROP_SIZE_SMALL && usableHeight >= AUTO_TUNE_PREFERRED_CROP_SIZE_SMALL) {
            cropSize = AUTO_TUNE_PREFERRED_CROP_SIZE_SMALL;
        } else {
            cropSize = Math.min(usableWidth, usableHeight);
        }

        if (cropSize < AUTO_TUNE_MIN_CROP_SIZE) {
            report.append(String.format(
                    "Computed crop size %d is too small for reliable auto-tuning (minimum %d).%n",
                    cropSize, AUTO_TUNE_MIN_CROP_SIZE));
            return regions;
        }

        int minX = AUTO_TUNE_BORDER_MARGIN;
        int minY = AUTO_TUNE_BORDER_MARGIN;
        int maxX = sensorWidth - AUTO_TUNE_BORDER_MARGIN - cropSize;
        int maxY = sensorHeight - AUTO_TUNE_BORDER_MARGIN - cropSize;

        if (maxX < minX || maxY < minY) {
            report.append("Frame is too small to place crops with the requested border margin.\n");
            return regions;
        }

        int centerX = clampInt((sensorWidth - cropSize) / 2, minX, maxX);
        int centerY = clampInt((sensorHeight - cropSize) / 2, minY, maxY);

        // 3 crops: top-left interior, center, bottom-right interior
        regions.add(new CropRegion(minX, minY, cropSize, cropSize, "upper-left"));
        regions.add(new CropRegion(centerX, centerY, cropSize, cropSize, "center"));
        regions.add(new CropRegion(maxX, maxY, cropSize, cropSize, "lower-right"));

        if (AUTO_TUNE_CROP_COUNT < regions.size()) {
            return regions.subList(0, AUTO_TUNE_CROP_COUNT);
        }

        report.append(String.format(
                "Using %d tuning crops of size %dx%d with border margin >= %d px where possible:%n",
                regions.size(), cropSize, cropSize, AUTO_TUNE_BORDER_MARGIN));
        for (CropRegion r : regions) {
            report.append(String.format(" -> %s crop at x=%d, y=%d%n", r.label, r.x, r.y));
        }

        return regions;
    }

    private static List<CroppedFrame> buildCroppedFramesForRegion(List<ImageFrame> frames, CropRegion region) {
        List<CroppedFrame> cropped = new ArrayList<>(frames.size());
        for (ImageFrame frame : frames) {
            cropped.add(new CroppedFrame(cropPixels(frame.pixelData, region), frame.sequenceIndex));
        }
        return cropped;
    }

    private static short[][] cropPixels(short[][] source, CropRegion region) {
        short[][] out = new short[region.height][region.width];
        for (int y = 0; y < region.height; y++) {
            System.arraycopy(source[region.y + y], region.x, out[y], 0, region.width);
        }
        return out;
    }

    private static short[][] createMedianMasterStackForCrops(List<CroppedFrame> frames) {
        int height = frames.get(0).pixelData.length;
        int width = frames.get(0).pixelData[0].length;
        int depth = frames.size();

        short[][] out = new short[height][width];
        short[] buffer = new short[depth];
        int medianIndex = depth / 2;

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                for (int i = 0; i < depth; i++) {
                    buffer[i] = frames.get(i).pixelData[y][x];
                }
                // sample size is tiny (5), so Collections/streams are unnecessary here
                insertionSort(buffer);
                out[y][x] = buffer[medianIndex];
            }
        }

        return out;
    }

    private static void insertionSort(short[] arr) {
        for (int i = 1; i < arr.length; i++) {
            short key = arr[i];
            int j = i - 1;
            while (j >= 0 && arr[j] > key) {
                arr[j + 1] = arr[j];
                j--;
            }
            arr[j + 1] = key;
        }
    }

    // =====================================================================
    // QUALITY ASSURANCE UTILITIES
    // =====================================================================

    /**
     * Evaluates frames using the FrameQualityAnalyzer and returns a representative tuning sample.
     * Strategy:
     * - mostly best frames
     * - plus a couple of median-ish frames so the tuner does not overfit only to perfect data
     */
    private static List<ImageFrame> getBestSampleFrames(List<ImageFrame> allFrames,
                                                        DetectionConfig config,
                                                        StringBuilder report) {
        List<FrameQualityRecord> records = new ArrayList<>();
        int totalFrames = allFrames.size();

        for (int i = 0; i < totalFrames; i++) {
            ImageFrame frame = allFrames.get(i);

            if (DEBUG) {
                System.out.println("   -> Evaluating Frame Quality: " + (i + 1) + " of " + totalFrames
                        + " (Index: " + frame.sequenceIndex + ")...");
            }

            FrameQualityAnalyzer.FrameMetrics metrics = FrameQualityAnalyzer.evaluateFrame(frame.pixelData, config);
            double score = metrics.backgroundNoise * metrics.medianFWHM;
            records.add(new FrameQualityRecord(frame, metrics, score));
        }

        records.sort(Comparator.comparingDouble(r -> r.qualityScore));

        List<ImageFrame> selected = new ArrayList<>();
        List<Integer> usedIndices = new ArrayList<>();

        int target = Math.min(AUTO_TUNE_SAMPLE_SIZE, records.size());
        int eliteCount = Math.min(3, target);

        report.append(String.format("Selected %d tuning frames (best + representative median quality):%n", target));

        for (int i = 0; i < eliteCount; i++) {
            addSampleFrame(records, i, usedIndices, selected, report);
        }

        int remaining = target - selected.size();
        if (remaining > 0) {
            int mid = records.size() / 2;
            int[] candidateIndices = new int[]{
                    Math.max(0, mid - 1),
                    mid,
                    Math.min(records.size() - 1, mid + 1),
                    Math.min(records.size() - 1, (int) Math.round(records.size() * 0.7))
            };

            for (int idx : candidateIndices) {
                if (selected.size() >= target) break;
                addSampleFrame(records, idx, usedIndices, selected, report);
            }
        }

        for (int i = 0; i < records.size() && selected.size() < target; i++) {
            addSampleFrame(records, i, usedIndices, selected, report);
        }

        selected.sort(Comparator.comparingInt(f -> f.sequenceIndex));
        return selected;
    }

    private static void addSampleFrame(List<FrameQualityRecord> records,
                                       int index,
                                       List<Integer> usedIndices,
                                       List<ImageFrame> selected,
                                       StringBuilder report) {
        if (index < 0 || index >= records.size()) return;
        if (usedIndices.contains(index)) return;

        FrameQualityRecord rec = records.get(index);
        usedIndices.add(index);
        selected.add(rec.frame);

        report.append(String.format(
                " -> Frame %d: Noise=%.2f, FWHM=%.2f, Stars=%d (Score: %.2f)%n",
                rec.frame.sequenceIndex,
                rec.metrics.backgroundNoise,
                rec.metrics.medianFWHM,
                rec.metrics.starCount,
                rec.qualityScore));
    }

    private static double computeMaskOverlapFraction(SourceExtractor.DetectedObject obj,
                                                     boolean[][] mask,
                                                     int sensorWidth,
                                                     int sensorHeight) {
        if (obj == null || obj.rawPixels == null || obj.rawPixels.isEmpty()) {
            return 0.0;
        }

        int touched = 0;
        int total = 0;

        for (SourceExtractor.Pixel p : obj.rawPixels) {
            if (p.x >= 0 && p.x < sensorWidth && p.y >= 0 && p.y < sensorHeight) {
                total++;
                if (mask[p.y][p.x]) {
                    touched++;
                }
            }
        }

        if (total == 0) return 0.0;
        return (double) touched / total;
    }

    private static double coefficientOfVariation(List<Integer> values) {
        if (values == null || values.isEmpty()) return 0.0;

        double mean = 0.0;
        for (int v : values) mean += v;
        mean /= values.size();

        if (mean <= 1e-9) return 0.0;

        double variance = 0.0;
        for (int v : values) {
            double d = v - mean;
            variance += d * d;
        }
        variance /= values.size();

        return Math.sqrt(variance) / mean;
    }

    private static int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private static double normalize(double value, double min, double max) {
        if (max <= min) return 0.0;
        return clamp01((value - min) / (max - min));
    }

    private static double getPercentileFromSorted(List<Double> sortedValues, double percentile) {
        if (sortedValues == null || sortedValues.isEmpty()) return 0.0;
        if (sortedValues.size() == 1) return sortedValues.get(0);

        double p = clamp01(percentile);
        int index = (int) Math.round(p * (sortedValues.size() - 1));
        return sortedValues.get(index);
    }

    private static double getMedianFromSorted(List<Double> sortedValues) {
        if (sortedValues == null || sortedValues.isEmpty()) return 0.0;
        int n = sortedValues.size();
        if ((n & 1) == 1) {
            return sortedValues.get(n / 2);
        }
        return 0.5 * (sortedValues.get((n / 2) - 1) + sortedValues.get(n / 2));
    }
}