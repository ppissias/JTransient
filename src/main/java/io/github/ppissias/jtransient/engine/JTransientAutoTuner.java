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

/**
 * Auto-tunes a {@link DetectionConfig} against representative crops from a frame sequence.
 *
 * <p>The tuner first calibrates {@code maxStarJitter} from stable stars on frozen crops,
 * then sweeps extraction thresholds on those cropped regions, and finally validates
 * the winning configuration on the same frozen sample.</p>
 */
public class JTransientAutoTuner {

    /** Enables verbose console diagnostics for the tuning process. */
    public static boolean DEBUG = true;

    // =========================================================================
    // AUTO-TUNER CONSTRAINTS
    // =========================================================================

    public static int AUTO_TUNE_SAMPLE_SIZE = 5;

    public static double SEARCH_RADIUS_PX = 4.0;

    // Hard safety gates
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
    public static double[] MASK_OVERLAP_TO_TEST = {0.65, 0.70, 0.75};


    // Penalize oversized veto masks
    public static double SOFT_MASK_COVERAGE_START = 0.08;
    public static double HARD_MASK_COVERAGE_REJECT = 0.25;
    public static double AGGRESSIVE_LOWER_SIGMA_SCORE_WINDOW = 5.0;

    // CV normalization anchors
    public static double STABLE_STARS_CV_ANCHOR = 0.25;
    public static double TRANSIENTS_CV_ANCHOR = 0.75;

    // Jitter measurement
    public static double JITTER_PERCENTILE = 0.90;
    public static double MIN_JITTER_FLOOR = 1.0;
    public static double JITTER_SAFETY_MULTIPLIER = 2.0;

    public static double INITIAL_JITTER_FALLBACK_PX = 1.5;

    // Jitter calibration probe settings
    public static double INITIAL_JITTER_PROBE_SIGMA = 4.0;
    public static double INITIAL_JITTER_PROBE_GROW_SIGMA = 3.0;
    public static int INITIAL_JITTER_PROBE_MIN_PIXELS = 5;
    public static double INITIAL_JITTER_PROBE_MAX_ELONGATION = 2.5;
    public static int MIN_INITIAL_JITTER_MATCHES = 8;

    // =========================================================================
    // ROI TUNING SETTINGS
    // =========================================================================

    public static int AUTO_TUNE_CROP_COUNT = 3;
    public static int AUTO_TUNE_BORDER_MARGIN = 200;
    public static int AUTO_TUNE_PREFERRED_CROP_SIZE_LARGE = 1024;
    public static int AUTO_TUNE_PREFERRED_CROP_SIZE_SMALL = 768;
    public static int AUTO_TUNE_MIN_CROP_SIZE = 384;

    // =========================================================================

    /**
     * Result of one auto-tuning run.
     */
    public static class AutoTunerResult {
        /**
         * Validation summary produced by replaying the final tuned configuration on the
         * same frozen crops used during the sweep.
         */
        public static class FinalValidationTelemetry {
            /** Whether the final validation pass was executed. */
            public boolean executed;
            /** Whether the validation completed without a crop-level failure. */
            public boolean completed;
            /** Whether the validated config satisfied the tuner hard gates. */
            public boolean passedHardGates;
            /** Whether the evaluated object count stayed within safety bounds. */
            public boolean objectCountInBounds;
            /** Whether the validated transient ratio stayed within the profile limit. */
            public boolean transientRatioWithinLimit;
            /** Whether the validated stable-star count satisfied the minimum requirement. */
            public boolean stableStarsWithinLimit;
            /** Human-readable one-line status. */
            public String statusMessage;
            /** Label of the crop that failed validation, when applicable. */
            public String failedCropLabel;
            /** Total extracted objects across all crop-frames. */
            public int totalObjectsExtracted;
            /** Total stable stars counted across all crop-frames. */
            public int totalStableStars;
            /** Total transients counted across all crop-frames. */
            public int totalTransients;
            /** Final transient ratio measured during validation. */
            public double transientRatio;
            /** Aggregated veto-mask coverage across all crops. */
            public double aggregatedMaskCoverage;
            /** Average stable stars per crop-frame. */
            public double avgStableStarsPerCropFrame;
            /** Average transients per crop-frame. */
            public double avgTransientsPerCropFrame;
            /** Coefficient of variation for stable counts across crop-frames. */
            public double stableCv;
            /** Coefficient of variation for transient counts across crop-frames. */
            public double transientCv;
        }

        /** Whether a stable optimized configuration was found. */
        public boolean success;
        /** Tuned configuration, or the provided base configuration on fallback. */
        public DetectionConfig optimizedConfig;
        /** Human-readable report describing the explored parameter space. */
        public String telemetryReport;
        /** Stable-star count recorded for the winning configuration. */
        public int bestStarCount;
        /** Fraction of extracted objects that survived as transients for the winning configuration. */
        public double bestTransientRatio;
        /** Replay of the final tuned config on the frozen tuning crops. */
        public FinalValidationTelemetry finalValidationTelemetry;
    }

    /**
     * High-level tuning policy presets.
     */
    public enum AutoTuneProfile {
        CONSERVATIVE,
        BALANCED,
        AGGRESSIVE
    }

    /**
     * Internal policy object defining score weights and hard gates for a tuning profile.
     */
    private static class AutoTunePolicy {

        /**
         * Hard rejection ceiling for transient leakage during the sweep.
         * Lower values are stricter; higher values allow noisier candidates to survive scoring.
         */
        final double maxTransientRatio;

        /**
         * Center of the preferred non-zero transient leakage band used in scoring.
         * Helps avoid over-hardening the detector to a misleading zero-transient result.
         */
        final double targetTransientsPerCropFrame;

        /**
         * Lower edge of the acceptable transient sweet spot.
         * Configurations below this are penalized as too quiet or overly conservative.
         */
        final double transientSweetSpotLow;

        /**
         * Upper edge of the acceptable transient sweet spot.
         * This is a soft scoring preference, separate from the hard `maxTransientRatio` gate.
         */
        final double transientSweetSpotHigh;

        /**
         * Positive weight for stable-star yield in the final score.
         * Higher values favor candidates that recover a healthier stationary star field.
         */
        final double scoreWeightStable;

        /**
         * Negative weight for excessive transient leakage.
         * This is the main score pressure that pushes the tuner away from noisy solutions.
         */
        final double scoreWeightTransientOverflow;

        /**
         * Negative weight for missing the desired transient sweet spot.
         * Higher values more strongly prefer a small but non-zero leakage level.
         */
        final double scoreWeightTransientSweetSpot;

        /**
         * Negative weight for instability across crop-frames.
         * Higher values prefer robust, repeatable settings over brittle high-scoring ones.
         */
        final double scoreWeightVariance;

        /**
         * Negative weight for excessive veto-mask coverage.
         * Prevents the tuner from preferring solutions that look clean only because they mask too much sky.
         */
        final double scoreWeightMaskCoverage;

        /**
         * Negative weight for overall parameter harshness.
         * Biases the tuner away from overly strict thresholds when cleanliness is otherwise similar.
         */
        final double scoreWeightHarshness;

        /**
         * Relative contributions of the harshness model and the low-sigma safety guard.
         * They control how the sweep penalizes very strict settings and unsafe low-sigma/small-minPix combinations.
         */
        final double harshnessSigmaWeight;
        final double harshnessMinPixWeight;
        final double harshnessGrowDeltaWeight;

        final double lowSigmaMinPixGuardWeight;
        final double lowSigmaMinPixPivot;
        final double lowSigmaMinPixSlope;

        AutoTunePolicy(double maxTransientRatio,
                       double targetTransientsPerCropFrame,
                       double transientSweetSpotLow,
                       double transientSweetSpotHigh,
                       double scoreWeightStable,
                       double scoreWeightTransientOverflow,
                       double scoreWeightTransientSweetSpot,
                       double scoreWeightVariance,
                       double scoreWeightMaskCoverage,
                       double scoreWeightHarshness, double harshnessSigmaWeight, double harshnessMinPixWeight, double harshnessGrowDeltaWeight, double lowSigmaMinPixGuardWeight, double lowSigmaMinPixPivot, double lowSigmaMinPixSlope) {
            this.maxTransientRatio = maxTransientRatio;
            this.targetTransientsPerCropFrame = targetTransientsPerCropFrame;
            this.transientSweetSpotLow = transientSweetSpotLow;
            this.transientSweetSpotHigh = transientSweetSpotHigh;
            this.scoreWeightStable = scoreWeightStable;
            this.scoreWeightTransientOverflow = scoreWeightTransientOverflow;
            this.scoreWeightTransientSweetSpot = scoreWeightTransientSweetSpot;
            this.scoreWeightVariance = scoreWeightVariance;
            this.scoreWeightMaskCoverage = scoreWeightMaskCoverage;
            this.scoreWeightHarshness = scoreWeightHarshness;
            this.harshnessSigmaWeight = harshnessSigmaWeight;
            this.harshnessMinPixWeight = harshnessMinPixWeight;
            this.harshnessGrowDeltaWeight = harshnessGrowDeltaWeight;
            this.lowSigmaMinPixGuardWeight = lowSigmaMinPixGuardWeight;
            this.lowSigmaMinPixPivot = lowSigmaMinPixPivot;
            this.lowSigmaMinPixSlope = lowSigmaMinPixSlope;
        }
    }


    /**
     * Internal frame-quality record used when selecting a representative tuning sample.
     */
    private static class FrameQualityRecord {
        ImageFrame frame;
        FrameQualityAnalyzer.FrameMetrics metrics;
        double qualityScore;

        /**
         * Creates a sampled frame-quality record.
         */
        public FrameQualityRecord(ImageFrame frame, FrameQualityAnalyzer.FrameMetrics metrics, double qualityScore) {
            this.frame = frame;
            this.metrics = metrics;
            this.qualityScore = qualityScore;
        }
    }

    /**
     * Score breakdown for one accepted Phase 1 sweep candidate.
     */
    private static class SweepCandidateTelemetry {
        final double sigma;
        final double growSigma;
        final int minPix;
        final double overlapFraction;
        final int totalObjectsExtracted;
        final int totalStableStars;
        final int totalTransients;
        final double transientRatio;
        final double avgTransientsPerCropFrame;
        final double stableCv;
        final double transientCv;
        final double aggregatedMaskCoverage;
        final double stableYieldScore;
        final double transientOverflowPenalty;
        final double transientSweetSpotPenalty;
        final double variancePenalty;
        final double maskCoveragePenalty;
        final double harshnessPenalty;
        final double lowSigmaMinPixGuardPenalty;
        final double score;

        SweepCandidateTelemetry(double sigma,
                               double growSigma,
                               int minPix,
                               double overlapFraction,
                               int totalObjectsExtracted,
                               int totalStableStars,
                               int totalTransients,
                               double transientRatio,
                               double avgTransientsPerCropFrame,
                               double stableCv,
                               double transientCv,
                               double aggregatedMaskCoverage,
                               double stableYieldScore,
                               double transientOverflowPenalty,
                               double transientSweetSpotPenalty,
                               double variancePenalty,
                               double maskCoveragePenalty,
                               double harshnessPenalty,
                               double lowSigmaMinPixGuardPenalty,
                               double score) {
            this.sigma = sigma;
            this.growSigma = growSigma;
            this.minPix = minPix;
            this.overlapFraction = overlapFraction;
            this.totalObjectsExtracted = totalObjectsExtracted;
            this.totalStableStars = totalStableStars;
            this.totalTransients = totalTransients;
            this.transientRatio = transientRatio;
            this.avgTransientsPerCropFrame = avgTransientsPerCropFrame;
            this.stableCv = stableCv;
            this.transientCv = transientCv;
            this.aggregatedMaskCoverage = aggregatedMaskCoverage;
            this.stableYieldScore = stableYieldScore;
            this.transientOverflowPenalty = transientOverflowPenalty;
            this.transientSweetSpotPenalty = transientSweetSpotPenalty;
            this.variancePenalty = variancePenalty;
            this.maskCoveragePenalty = maskCoveragePenalty;
            this.harshnessPenalty = harshnessPenalty;
            this.lowSigmaMinPixGuardPenalty = lowSigmaMinPixGuardPenalty;
            this.score = score;
        }
    }

    /**
     * Rectangular region of interest used during crop-based tuning.
     */
    private static class CropRegion {
        final int x;
        final int y;
        final int width;
        final int height;
        final String label;

        /**
         * Creates a named crop region.
         */
        CropRegion(int x, int y, int width, int height, String label) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            this.label = label;
        }
    }

    /**
     * Lightweight cropped frame used during ROI-based tuning sweeps.
     */
    private static class CroppedFrame {
        final short[][] pixelData;
        final int sequenceIndex;

        /**
         * Creates one cropped-frame descriptor.
         */
        CroppedFrame(short[][] pixelData, int sequenceIndex) {
            this.pixelData = pixelData;
            this.sequenceIndex = sequenceIndex;
        }
    }


    /**
     * Automatically determines the optimal extraction and kinematic settings for a given image sequence.
     *
     * @param allFrames full frame sequence available for sampling
     * @param baseConfig starting configuration that is cloned and refined
     * @param listener optional progress listener
     * @return tuning result using the balanced policy preset
     */
    public static AutoTunerResult tune(List<ImageFrame> allFrames,
                                       DetectionConfig baseConfig,
                                       TransientEngineProgressListener listener) {
        return tune(allFrames, baseConfig, AutoTuneProfile.BALANCED, listener);
    }

    /**
     * Automatically determines an optimized configuration using the supplied tuning policy.
     *
     * @param allFrames full frame sequence available for sampling
     * @param baseConfig starting configuration that is cloned and refined
     * @param profile policy preset controlling scoring aggressiveness
     * @param listener optional progress listener
     * @return tuning result containing the best configuration or a fallback to {@code baseConfig}
     */
    public static AutoTunerResult tune(List<ImageFrame> allFrames,
                                       DetectionConfig baseConfig,
                                       AutoTuneProfile profile,
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

        AutoTunePolicy policy = getPolicy(profile);

        AutoTunerResult result = new AutoTunerResult();
        StringBuilder report = new StringBuilder("=== JTransient Auto-Tuning Report ===\n");
        report.append("Profile: ").append(profile).append("\n");
        appendInputFrameOrderTrace(allFrames, report);
        appendInputConfigTrace(baseConfig, report);

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
            listener.onProgressUpdate(7, "Calibrating maxStarJitter from sampled stars...");
        }

        double measuredMaxStarJitter = measureMaxStarJitter(croppedFramesByRegion, baseConfig, report);

        if (DEBUG) {
            System.out.printf("%n[JITTER] Calibrated maxStarJitter before the sweep: %.2f px%n", measuredMaxStarJitter);
        }

        // =====================================================================
        // PHASE 1: THRESHOLD / GROW / VETO SWEEP
        // =====================================================================
        if (DEBUG) System.out.println("\n[START] Phase 1: Detection Threshold Sweep on cropped regions...");
        report.append("\n--- PHASE 1: Detection Threshold Sweep (Cropped ROIs) ---\n");
        appendSweepBaselineTrace(baseConfig, measuredMaxStarJitter, report);

        DetectionConfig bestConfig = null;
        List<SweepCandidateTelemetry> acceptedCandidates = new ArrayList<>();

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

                        // IMPORTANT: Phase 1 should not depend on the caller's jitter guess.
                        // Use the pre-sweep calibrated jitter for the veto-mask dilation during the sweep.
                        testConfig.maxStarJitter = measuredMaxStarJitter;

                        int totalObjectsExtracted = 0;
                        int totalStableStars = 0;
                        int totalTransients = 0;

                        int totalMaskTrueCount = 0;
                        int totalMaskArea = 0;

                        List<Integer> stableCountsPerCropFrame = new ArrayList<>();
                        List<Integer> transientCountsPerCropFrame = new ArrayList<>();

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
                                    SourceExtractor.extractSources(masterStackData, masterSigma, masterMinPix, testConfig).objects;

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
                            for (CroppedFrame frame : cropFrames) {
                                List<SourceExtractor.DetectedObject> objects = SourceExtractor.extractSources(
                                        frame.pixelData,
                                        testConfig.detectionSigmaMultiplier,
                                        testConfig.minDetectionPixels,
                                        testConfig
                                ).objects;

                                totalObjectsExtracted += objects.size();

                                int stableThisFrame = 0;
                                int transientThisFrame = 0;

                                for (SourceExtractor.DetectedObject obj : objects) {
                                    // Streaks bypass the veto mask in the main engine. 
                                    // We do not want a real meteor in the sample frame to be penalized as noise!
                                    if (obj.isStreak) {
                                        continue;
                                    }

                                    double overlap = computeMaskOverlapFraction(obj, masterMask, cropWidth, cropHeight);

                                    if (overlap >= testConfig.maxMaskOverlapFraction) {
                                        stableThisFrame++;
                                    } else {
                                        transientThisFrame++;
                                    }
                                }

                                stableCountsPerCropFrame.add(stableThisFrame);
                                transientCountsPerCropFrame.add(transientThisFrame);

                                totalStableStars += stableThisFrame;
                                totalTransients += transientThisFrame;
                            }
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

                        boolean isClean = (transientRatio <= policy.maxTransientRatio)
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
                        double avgTransientsPerCropFrame = totalTransients / cropFrameCount;

                        double stableYieldScore = clamp01(avgStableStars / OPTIMAL_STAR_COUNT);

                        // Strong penalty for too many transients
                        double transientOverflowPenalty = clamp01(transientRatio / policy.maxTransientRatio);

                        // Gentle preference for a profile-dependent small non-zero transient count
                        double transientSweetSpotPenalty = computeTransientSweetSpotPenalty(
                                avgTransientsPerCropFrame,
                                policy.transientSweetSpotLow,
                                policy.targetTransientsPerCropFrame,
                                policy.transientSweetSpotHigh
                        );

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

                        double sigmaHarshness = normalize(sigma, SIGMAS_TO_TEST[0], SIGMAS_TO_TEST[SIGMAS_TO_TEST.length - 1]);
                        double minPixHarshness = normalize(minPix, MIN_PIXELS_TO_TEST[0], MIN_PIXELS_TO_TEST[MIN_PIXELS_TO_TEST.length - 1]);
                        double growHarshness = normalize(growDelta, GROW_DELTAS_TO_TEST[0], GROW_DELTAS_TO_TEST[GROW_DELTAS_TO_TEST.length - 1]);

                        double harshnessPenalty =
                                (policy.harshnessSigmaWeight * sigmaHarshness) +
                                        (policy.harshnessMinPixWeight * minPixHarshness) +
                                        (policy.harshnessGrowDeltaWeight * growHarshness);

                        double lowSigmaMinPixGuardPenalty = computeLowSigmaMinPixGuardPenalty(
                                sigma,
                                minPix,
                                policy.lowSigmaMinPixPivot,
                                policy.lowSigmaMinPixSlope
                        );

                        double score =
                                (policy.scoreWeightStable * stableYieldScore)
                                        - (policy.scoreWeightTransientOverflow * transientOverflowPenalty)
                                        - (policy.scoreWeightTransientSweetSpot * transientSweetSpotPenalty)
                                        - (policy.scoreWeightVariance * variancePenalty)
                                        - (policy.scoreWeightMaskCoverage * maskCoveragePenalty)
                                        - (policy.scoreWeightHarshness * harshnessPenalty)
                                        - (policy.lowSigmaMinPixGuardWeight * lowSigmaMinPixGuardPenalty);

                        if (DEBUG) {
                            System.out.printf(
                                    "      [SCORE] %.2f | profile=%s | stable=%.3f overflow=%.3f sweet=%.3f avgTrans/cropFrame=%.3f variance=%.3f mask=%.3f harsh=%.3f%n",
                                    score,
                                    profile,
                                    stableYieldScore,
                                    transientOverflowPenalty,
                                    transientSweetSpotPenalty,
                                    avgTransientsPerCropFrame,
                                    variancePenalty,
                                    maskCoveragePenalty,
                                    harshnessPenalty
                            );
                        }

                        report.append(String.format(
                                "Test -> Profile: %s | Sigma: %.1f, Grow: %.2f, MinPix: %d, Overlap: %.2f | Stable: %d | Transients: %d | Ratio: %.2f%% | AvgTrans/CropFrame: %.3f | StableCV: %.3f | TransCV: %.3f | Mask: %.2f%% | Score: %.2f%n",
                                profile, sigma, growSigma, minPix, overlapFraction,
                                totalStableStars, totalTransients, transientRatio * 100.0,
                                avgTransientsPerCropFrame,
                                stableCv, transientCv, aggregatedMaskCoverage * 100.0, score));

                        acceptedCandidates.add(new SweepCandidateTelemetry(
                                sigma,
                                growSigma,
                                minPix,
                                overlapFraction,
                                totalObjectsExtracted,
                                totalStableStars,
                                totalTransients,
                                transientRatio,
                                avgTransientsPerCropFrame,
                                stableCv,
                                transientCv,
                                aggregatedMaskCoverage,
                                stableYieldScore,
                                transientOverflowPenalty,
                                transientSweetSpotPenalty,
                                variancePenalty,
                                maskCoveragePenalty,
                                harshnessPenalty,
                                lowSigmaMinPixGuardPenalty,
                                score
                        ));

                        if (isBetterSweepCandidate(score, sigma, bestScore, bestConfig, profile)) {
                            if (DEBUG) System.out.println("      *** NEW BEST CONFIGURATION FOUND ***");

                            bestScore = score;
                            bestStableStars = totalStableStars;
                            bestTransientRatio = transientRatio;
                            bestConfig = testConfig.clone();
                        }
                    }
                }
            }
        }

        appendSweepLeaderboard(acceptedCandidates, report);

        if (bestConfig != null) {
            bestConfig.maxStarJitter = measuredMaxStarJitter;

            if (listener != null) {
                listener.onProgressUpdate(93, "Validating final tuned configuration...");
            }

            AutoTunerResult.FinalValidationTelemetry finalValidationTelemetry =
                    validateFinalConfig(cropRegions, croppedFramesByRegion, bestConfig, policy);
            result.finalValidationTelemetry = finalValidationTelemetry;

            report.append("\n--- PHASE 2: FINAL CONFIG VALIDATION ---\n");
            report.append(finalValidationTelemetry.statusMessage).append("\n");
            if (finalValidationTelemetry.failedCropLabel != null) {
                report.append(String.format("Failed Crop:            %s%n", finalValidationTelemetry.failedCropLabel));
            }
            report.append(String.format("Objects Extracted:      %d%n", finalValidationTelemetry.totalObjectsExtracted));
            report.append(String.format("Stable Stars:           %d%n", finalValidationTelemetry.totalStableStars));
            report.append(String.format("Transients:             %d%n", finalValidationTelemetry.totalTransients));
            report.append(String.format("Transient Ratio:        %.2f%%%n", finalValidationTelemetry.transientRatio * 100.0));
            report.append(String.format("Mask Coverage:          %.2f%%%n", finalValidationTelemetry.aggregatedMaskCoverage * 100.0));
            report.append(String.format("Avg Stable/CropFrame:   %.3f%n", finalValidationTelemetry.avgStableStarsPerCropFrame));
            report.append(String.format("Avg Trans/CropFrame:    %.3f%n", finalValidationTelemetry.avgTransientsPerCropFrame));
            report.append(String.format("Stable CV:              %.3f%n", finalValidationTelemetry.stableCv));
            report.append(String.format("Transient CV:           %.3f%n", finalValidationTelemetry.transientCv));

            report.append("\n=== FINAL OPTIMIZED CONFIGURATION ===\n");
            report.append(String.format("Detection Sigma:        %.2f%n", bestConfig.detectionSigmaMultiplier));
            report.append(String.format("Grow Sigma:             %.2f%n", bestConfig.growSigmaMultiplier));
            report.append(String.format("Min Pixels:             %d%n", bestConfig.minDetectionPixels));
            report.append(String.format("Mask Overlap Fraction:  %.2f%n", bestConfig.maxMaskOverlapFraction));
            report.append(String.format("Max Star Jitter:        %.2f px%n", bestConfig.maxStarJitter));
            report.append(String.format("Jitter Calibration:     %.2f px%n", measuredMaxStarJitter));
            report.append(String.format("Quality Max Elong FWHM: %.2f (preserved)%n", bestConfig.qualityMaxElongationForFwhm));
            report.append(String.format("Streak Min Elongation:  %.2f (preserved)%n", bestConfig.streakMinElongation));

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

    /**
     * Builds the set of representative interior crops used during tuning.
     */
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

    /**
     * Crops every sampled frame to the supplied region.
     */
    private static List<CroppedFrame> buildCroppedFramesForRegion(List<ImageFrame> frames, CropRegion region) {
        List<CroppedFrame> cropped = new ArrayList<>(frames.size());
        for (ImageFrame frame : frames) {
            cropped.add(new CroppedFrame(cropPixels(frame.pixelData, region), frame.sequenceIndex));
        }
        return cropped;
    }

    /**
     * Copies one rectangular ROI out of a source frame.
     */
    private static short[][] cropPixels(short[][] source, CropRegion region) {
        short[][] out = new short[region.height][region.width];
        for (int y = 0; y < region.height; y++) {
            System.arraycopy(source[region.y + y], region.x, out[y], 0, region.width);
        }
        return out;
    }

    /**
     * Builds a median master stack for a small set of already cropped frames.
     */
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

    /**
     * Sorts a tiny short array in place using insertion sort.
     */
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
        DetectionConfig samplingConfig = config.clone();
        appendSamplingConfigTrace(config, samplingConfig, report);

        for (int i = 0; i < totalFrames; i++) {
            ImageFrame frame = allFrames.get(i);

            if (DEBUG) {
                System.out.println("   -> Evaluating Frame Quality: " + (i + 1) + " of " + totalFrames
                        + " (Index: " + frame.sequenceIndex + ")...");
            }

            FrameQualityAnalyzer.FrameMetrics metrics = FrameQualityAnalyzer.evaluateFrame(frame.pixelData, samplingConfig);
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

    /**
     * Adds one frame to the tuning sample if it has not already been selected.
     */
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
                " -> Frame %d: Noise=%.2f, FWHM=%.2f, Stars=%d, ShapeStars=%d, FwhmStars=%d (Score: %.2f)%n",
                rec.frame.sequenceIndex,
                rec.metrics.backgroundNoise,
                rec.metrics.medianFWHM,
                rec.metrics.starCount,
                rec.metrics.usableShapeStarCount,
                rec.metrics.fwhmStarCount,
                rec.qualityScore));
    }

    /**
     * Records the incoming base configuration so repeated tuning runs reveal
     * which fields were carried forward from a previous optimized result.
     */
    private static void appendInputConfigTrace(DetectionConfig baseConfig, StringBuilder report) {
        DetectionConfig defaults = new DetectionConfig();
        List<String> samplingDeltas = new ArrayList<>();
        List<String> carriedButIgnored = new ArrayList<>();

        appendIntDelta(samplingDeltas, "edgeMargin", baseConfig.edgeMarginPixels, defaults.edgeMarginPixels);
        appendDoubleDelta(samplingDeltas, "voidThresholdFraction", baseConfig.voidThresholdFraction, defaults.voidThresholdFraction);
        appendIntDelta(samplingDeltas, "voidProximityRadius", baseConfig.voidProximityRadius, defaults.voidProximityRadius);
        appendIntDelta(samplingDeltas, "bgClippingIterations", baseConfig.bgClippingIterations, defaults.bgClippingIterations);
        appendDoubleDelta(samplingDeltas, "bgClippingFactor", baseConfig.bgClippingFactor, defaults.bgClippingFactor);
        appendDoubleDelta(samplingDeltas, "qualitySigma", baseConfig.qualitySigmaMultiplier, defaults.qualitySigmaMultiplier);
        appendDoubleDelta(samplingDeltas, "qualityGrowSigma", baseConfig.qualityGrowSigmaMultiplier, defaults.qualityGrowSigmaMultiplier);
        appendIntDelta(samplingDeltas, "qualityMinPix", baseConfig.qualityMinDetectionPixels, defaults.qualityMinDetectionPixels);
        appendDoubleDelta(samplingDeltas, "qualityMaxElongationForFwhm", baseConfig.qualityMaxElongationForFwhm, defaults.qualityMaxElongationForFwhm);
        appendDoubleDelta(samplingDeltas, "streakMinElongation", baseConfig.streakMinElongation, defaults.streakMinElongation);
        appendIntDelta(samplingDeltas, "streakMinPixels", baseConfig.streakMinPixels, defaults.streakMinPixels);

        appendDoubleDelta(carriedButIgnored, "detectionSigma", baseConfig.detectionSigmaMultiplier, defaults.detectionSigmaMultiplier);
        appendDoubleDelta(carriedButIgnored, "growSigmaMultiplier", baseConfig.growSigmaMultiplier, defaults.growSigmaMultiplier);
        appendIntDelta(carriedButIgnored, "minDetectionPixels", baseConfig.minDetectionPixels, defaults.minDetectionPixels);
        appendDoubleDelta(carriedButIgnored, "maxMaskOverlapFraction", baseConfig.maxMaskOverlapFraction, defaults.maxMaskOverlapFraction);
        appendDoubleDelta(carriedButIgnored, "maxStarJitter", baseConfig.maxStarJitter, defaults.maxStarJitter);

        report.append("\n--- INPUT CONFIG SNAPSHOT ---\n");
        report.append(String.format(
                "Base Config -> Detect Sigma: %.2f | Grow Sigma: %.2f | MinPix: %d | Overlap: %.2f | MaxStarJitter: %.2f%n",
                baseConfig.detectionSigmaMultiplier,
                baseConfig.growSigmaMultiplier,
                baseConfig.minDetectionPixels,
                baseConfig.maxMaskOverlapFraction,
                baseConfig.maxStarJitter));
        report.append(String.format(
                "Frame-Sampling Inputs -> QualitySigma: %.2f | QualityGrowSigma: %.2f | QualityMinPix: %d | QualityMaxElongationForFwhm: %.2f | Edge: %d | VoidFrac: %.2f | VoidRadius: %d | BgClipIter: %d | BgClipFactor: %.2f | StreakElong: %.2f | StreakMinPix: %d%n",
                baseConfig.qualitySigmaMultiplier,
                baseConfig.qualityGrowSigmaMultiplier,
                baseConfig.qualityMinDetectionPixels,
                baseConfig.qualityMaxElongationForFwhm,
                baseConfig.edgeMarginPixels,
                baseConfig.voidThresholdFraction,
                baseConfig.voidProximityRadius,
                baseConfig.bgClippingIterations,
                baseConfig.bgClippingFactor,
                baseConfig.streakMinElongation,
                baseConfig.streakMinPixels));
        report.append("Sampling-impact deltas from defaults: ")
                .append(samplingDeltas.isEmpty() ? "none" : String.join(", ", samplingDeltas))
                .append('\n');
        report.append("Carried-over base fields ignored during frame sampling: ")
                .append(carriedButIgnored.isEmpty() ? "none" : String.join(", ", carriedButIgnored))
                .append('\n');
        report.append("Sampling overrides: none\n");
    }

    /**
     * Records the incoming frame order so repeated runs can reveal caller-side ordering drift.
     */
    private static void appendInputFrameOrderTrace(List<ImageFrame> allFrames, StringBuilder report) {
        boolean isSorted = true;
        for (int i = 1; i < allFrames.size(); i++) {
            if (allFrames.get(i - 1).sequenceIndex > allFrames.get(i).sequenceIndex) {
                isSorted = false;
                break;
            }
        }

        report.append(String.format(
                "Input Frames -> Count: %d | SortedBySequenceIndex: %s | SequenceOrder: %s%n",
                allFrames.size(),
                isSorted ? "yes" : "no",
                summarizeSequenceOrder(allFrames)
        ));
    }

    /**
     * Records the exact effective configuration used by frame-quality evaluation.
     */
    private static void appendSamplingConfigTrace(DetectionConfig baseConfig,
                                                  DetectionConfig samplingConfig,
                                                  StringBuilder report) {
        report.append(String.format(
                "Effective Frame-Sampling Config -> QualitySigma: %.2f | QualityGrowSigma: %.2f | QualityMinPix: %d | QualityMaxElongationForFwhm: %.2f | Edge: %d | VoidFrac: %.2f | VoidRadius: %d | BgClipIter: %d | BgClipFactor: %.2f | StreakElong: %.2f | StreakMinPix: %d%n",
                samplingConfig.qualitySigmaMultiplier,
                samplingConfig.qualityGrowSigmaMultiplier,
                samplingConfig.qualityMinDetectionPixels,
                samplingConfig.qualityMaxElongationForFwhm,
                samplingConfig.edgeMarginPixels,
                samplingConfig.voidThresholdFraction,
                samplingConfig.voidProximityRadius,
                samplingConfig.bgClippingIterations,
                samplingConfig.bgClippingFactor,
                samplingConfig.streakMinElongation,
                samplingConfig.streakMinPixels));
        report.append(String.format(
                "Frame sampling ignores incoming Detect Sigma %.2f, Grow Sigma %.2f, MinPix %d, MaskOverlap %.2f, MaxStarJitter %.2f%n",
                baseConfig.detectionSigmaMultiplier,
                baseConfig.growSigmaMultiplier,
                baseConfig.minDetectionPixels,
                baseConfig.maxMaskOverlapFraction,
                baseConfig.maxStarJitter));
    }

    /**
     * Records the non-grid baseline fields that still affect Phase 1 after the sampled frames are frozen.
     */
    private static void appendSweepBaselineTrace(DetectionConfig baseConfig,
                                                 double measuredMaxStarJitter,
                                                 StringBuilder report) {
        DetectionConfig defaults = new DetectionConfig();
        List<String> deltas = new ArrayList<>();

        appendIntDelta(deltas, "edgeMargin", baseConfig.edgeMarginPixels, defaults.edgeMarginPixels);
        appendDoubleDelta(deltas, "voidThresholdFraction", baseConfig.voidThresholdFraction, defaults.voidThresholdFraction);
        appendIntDelta(deltas, "voidProximityRadius", baseConfig.voidProximityRadius, defaults.voidProximityRadius);
        appendIntDelta(deltas, "bgClippingIterations", baseConfig.bgClippingIterations, defaults.bgClippingIterations);
        appendDoubleDelta(deltas, "bgClippingFactor", baseConfig.bgClippingFactor, defaults.bgClippingFactor);
        appendDoubleDelta(deltas, "streakMinElongation", baseConfig.streakMinElongation, defaults.streakMinElongation);
        appendIntDelta(deltas, "streakMinPixels", baseConfig.streakMinPixels, defaults.streakMinPixels);
        appendDoubleDelta(deltas, "masterSigma", baseConfig.masterSigmaMultiplier, defaults.masterSigmaMultiplier);
        appendIntDelta(deltas, "masterMinPix", baseConfig.masterMinDetectionPixels, defaults.masterMinDetectionPixels);

        report.append(String.format(
                "Phase 1 Baseline -> Edge: %d | VoidFrac: %.2f | VoidRadius: %d | BgClipIter: %d | BgClipFactor: %.2f | StreakElong: %.2f | StreakMinPix: %d | MasterSigma: %.2f | MasterMinPix: %d | CalibratedJitter: %.2f%n",
                baseConfig.edgeMarginPixels,
                baseConfig.voidThresholdFraction,
                baseConfig.voidProximityRadius,
                baseConfig.bgClippingIterations,
                baseConfig.bgClippingFactor,
                baseConfig.streakMinElongation,
                baseConfig.streakMinPixels,
                baseConfig.masterSigmaMultiplier,
                baseConfig.masterMinDetectionPixels,
                measuredMaxStarJitter
        ));
        report.append("Phase 1 inherited deltas from defaults: ")
                .append(deltas.isEmpty() ? "none" : String.join(", ", deltas))
                .append('\n');
        report.append(String.format(
                "Phase 1 per-test overrides -> DetectSigma grid, GrowSigma grid, MinPix grid, Overlap grid, MaxStarJitter=%.2f; quality-side sampling thresholds are fixed from the input config%n",
                measuredMaxStarJitter
        ));
    }

    /**
     * Records the highest-scoring accepted Phase 1 candidates so repeated runs can be compared directly.
     */
    private static void appendSweepLeaderboard(List<SweepCandidateTelemetry> acceptedCandidates,
                                               StringBuilder report) {
        report.append("\n--- PHASE 1 LEADERBOARD ---\n");
        if (acceptedCandidates.isEmpty()) {
            report.append("No accepted Phase 1 candidates survived the hard gates.\n");
            return;
        }

        acceptedCandidates.sort((a, b) -> Double.compare(b.score, a.score));
        int limit = Math.min(5, acceptedCandidates.size());

        for (int i = 0; i < limit; i++) {
            SweepCandidateTelemetry c = acceptedCandidates.get(i);
            report.append(String.format(
                    " #%d | Score: %.2f | Sigma: %.1f | Grow: %.2f | MinPix: %d | Overlap: %.2f | Stable: %d | Transients: %d | Ratio: %.2f%% | AvgTrans/CropFrame: %.3f | Mask: %.2f%% | StableYield: %.3f | Overflow: %.3f | Sweet: %.3f | Variance: %.3f | MaskPenalty: %.3f | Harshness: %.3f | LowSigmaGuard: %.3f%n",
                    i + 1,
                    c.score,
                    c.sigma,
                    c.growSigma,
                    c.minPix,
                    c.overlapFraction,
                    c.totalStableStars,
                    c.totalTransients,
                    c.transientRatio * 100.0,
                    c.avgTransientsPerCropFrame,
                    c.aggregatedMaskCoverage * 100.0,
                    c.stableYieldScore,
                    c.transientOverflowPenalty,
                    c.transientSweetSpotPenalty,
                    c.variancePenalty,
                    c.maskCoveragePenalty,
                    c.harshnessPenalty,
                    c.lowSigmaMinPixGuardPenalty
            ));
        }

        if (acceptedCandidates.size() > 1) {
            double scoreGap = acceptedCandidates.get(0).score - acceptedCandidates.get(1).score;
            int nearTies = 0;
            for (int i = 1; i < acceptedCandidates.size(); i++) {
                if ((acceptedCandidates.get(0).score - acceptedCandidates.get(i).score) <= 1.0) {
                    nearTies++;
                }
            }
            report.append(String.format(
                    "Score gap best->second: %.3f | Additional candidates within 1.0 score point of best: %d%n",
                    scoreGap,
                    nearTies
            ));
        } else {
            report.append("Only one accepted Phase 1 candidate survived the hard gates.\n");
        }
    }

    private static void appendDoubleDelta(List<String> deltas, String label, double actual, double baseline) {
        if (Double.compare(actual, baseline) != 0) {
            deltas.add(String.format("%s=%.2f (default %.2f)", label, actual, baseline));
        }
    }

    private static void appendIntDelta(List<String> deltas, String label, int actual, int baseline) {
        if (actual != baseline) {
            deltas.add(String.format("%s=%d (default %d)", label, actual, baseline));
        }
    }

    /**
     * Replays the final tuned configuration on the already-frozen tuning crops to confirm
     * that the post-measurement config still behaves like the sweep winner.
     */
    private static AutoTunerResult.FinalValidationTelemetry validateFinalConfig(List<CropRegion> cropRegions,
                                                                                List<List<CroppedFrame>> croppedFramesByRegion,
                                                                                DetectionConfig finalConfig,
                                                                                AutoTunePolicy policy) {
        AutoTunerResult.FinalValidationTelemetry telemetry = new AutoTunerResult.FinalValidationTelemetry();
        telemetry.executed = true;

        int totalObjectsExtracted = 0;
        int totalStableStars = 0;
        int totalTransients = 0;
        int totalMaskTrueCount = 0;
        int totalMaskArea = 0;

        List<Integer> stableCountsPerCropFrame = new ArrayList<>();
        List<Integer> transientCountsPerCropFrame = new ArrayList<>();

        for (int cropIndex = 0; cropIndex < cropRegions.size(); cropIndex++) {
            CropRegion cropRegion = cropRegions.get(cropIndex);
            List<CroppedFrame> cropFrames = croppedFramesByRegion.get(cropIndex);

            int cropHeight = cropRegion.height;
            int cropWidth = cropRegion.width;
            totalMaskArea += cropWidth * cropHeight;

            short[][] masterStackData = createMedianMasterStackForCrops(cropFrames);

            DetectionConfig evalConfig = finalConfig.clone();
            double originalGrowSigma = evalConfig.growSigmaMultiplier;
            int originalEdgeMargin = evalConfig.edgeMarginPixels;
            int originalVoidProximity = evalConfig.voidProximityRadius;

            evalConfig.growSigmaMultiplier = evalConfig.masterSigmaMultiplier;
            evalConfig.edgeMarginPixels = 5;
            evalConfig.voidProximityRadius = 5;

            List<SourceExtractor.DetectedObject> masterStars =
                    SourceExtractor.extractSources(
                            masterStackData,
                            evalConfig.masterSigmaMultiplier,
                            evalConfig.masterMinDetectionPixels,
                            evalConfig
                    ).objects;

            evalConfig.growSigmaMultiplier = originalGrowSigma;
            evalConfig.edgeMarginPixels = originalEdgeMargin;
            evalConfig.voidProximityRadius = originalVoidProximity;

            if (masterStars == null || masterStars.isEmpty()) {
                telemetry.completed = false;
                telemetry.failedCropLabel = cropRegion.label;
                telemetry.statusMessage = "Final validation failed: no master stars extracted in crop " + cropRegion.label + ".";
                return telemetry;
            }

            boolean[][] masterMask = new boolean[cropHeight][cropWidth];
            int dilationRadius = (int) Math.ceil(evalConfig.maxStarJitter);
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
                telemetry.completed = false;
                telemetry.failedCropLabel = cropRegion.label;
                telemetry.statusMessage = String.format(
                        "Final validation failed: crop %s mask coverage %.2f%% exceeds %.2f%%.",
                        cropRegion.label,
                        maskCoverageThisCrop * 100.0,
                        HARD_MASK_COVERAGE_REJECT * 100.0
                );
                return telemetry;
            }

            for (CroppedFrame frame : cropFrames) {
                List<SourceExtractor.DetectedObject> objects = SourceExtractor.extractSources(
                        frame.pixelData,
                        evalConfig.detectionSigmaMultiplier,
                        evalConfig.minDetectionPixels,
                        evalConfig
                ).objects;

                totalObjectsExtracted += objects.size();

                int stableThisFrame = 0;
                int transientThisFrame = 0;

                for (SourceExtractor.DetectedObject obj : objects) {
                    if (obj.isStreak) {
                        continue;
                    }

                    double overlap = computeMaskOverlapFraction(obj, masterMask, cropWidth, cropHeight);
                    if (overlap >= evalConfig.maxMaskOverlapFraction) {
                        stableThisFrame++;
                    } else {
                        transientThisFrame++;
                    }
                }

                stableCountsPerCropFrame.add(stableThisFrame);
                transientCountsPerCropFrame.add(transientThisFrame);
                totalStableStars += stableThisFrame;
                totalTransients += transientThisFrame;
            }
        }

        telemetry.completed = true;
        telemetry.totalObjectsExtracted = totalObjectsExtracted;
        telemetry.totalStableStars = totalStableStars;
        telemetry.totalTransients = totalTransients;
        telemetry.objectCountInBounds =
                totalObjectsExtracted <= MAX_TOTAL_EXTRACTED_OBJECTS
                        && totalObjectsExtracted >= MIN_TOTAL_EXTRACTED_OBJECTS;
        telemetry.transientRatio = totalObjectsExtracted == 0
                ? 1.0
                : (double) totalTransients / totalObjectsExtracted;
        telemetry.transientRatioWithinLimit = telemetry.transientRatio <= policy.maxTransientRatio;
        telemetry.stableStarsWithinLimit = totalStableStars >= MIN_STABLE_STARS;
        telemetry.aggregatedMaskCoverage = totalMaskArea == 0
                ? 1.0
                : (double) totalMaskTrueCount / totalMaskArea;

        double cropFrameCount = stableCountsPerCropFrame.size();
        telemetry.avgStableStarsPerCropFrame = cropFrameCount == 0 ? 0.0 : totalStableStars / cropFrameCount;
        telemetry.avgTransientsPerCropFrame = cropFrameCount == 0 ? 0.0 : totalTransients / cropFrameCount;
        telemetry.stableCv = coefficientOfVariation(stableCountsPerCropFrame);
        telemetry.transientCv = coefficientOfVariation(transientCountsPerCropFrame);
        telemetry.passedHardGates =
                telemetry.objectCountInBounds
                        && telemetry.transientRatioWithinLimit
                        && telemetry.stableStarsWithinLimit;

        telemetry.statusMessage = telemetry.passedHardGates
                ? "Final validation passed on the frozen tuning crops."
                : "Final validation warning: post-measurement config drifted outside one or more tuner hard gates.";

        return telemetry;
    }

    /**
     * Computes the fraction of an object's footprint that overlaps a veto mask.
     */
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

    /**
     * Returns the coefficient of variation for a set of integer counts.
     */
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

    /**
     * Clamps an integer into the supplied range.
     */
    private static int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    /**
     * Formats the caller-provided sequence order without exploding the report for large batches.
     */
    private static String summarizeSequenceOrder(List<ImageFrame> allFrames) {
        if (allFrames.isEmpty()) {
            return "[]";
        }

        StringBuilder out = new StringBuilder("[");
        if (allFrames.size() <= 12) {
            for (int i = 0; i < allFrames.size(); i++) {
                if (i > 0) out.append(", ");
                out.append(allFrames.get(i).sequenceIndex);
            }
        } else {
            for (int i = 0; i < 5; i++) {
                if (i > 0) out.append(", ");
                out.append(allFrames.get(i).sequenceIndex);
            }
            out.append(", ... ,");
            for (int i = allFrames.size() - 3; i < allFrames.size(); i++) {
                out.append(' ').append(allFrames.get(i).sequenceIndex);
                if (i < allFrames.size() - 1) {
                    out.append(',');
                }
            }
        }
        out.append(']');
        return out.toString();
    }

    /**
     * Clamps a floating-point value into the {@code [0, 1]} range.
     */
    private static double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    /**
     * Normalizes a value into the {@code [0, 1]} range using the supplied bounds.
     */
    private static double normalize(double value, double min, double max) {
        if (max <= min) return 0.0;
        return clamp01((value - min) / (max - min));
    }

    /**
     * Compares two sweep candidates while allowing the aggressive profile to prefer
     * lower sigma when scores are effectively tied.
     */
    private static boolean isBetterSweepCandidate(double candidateScore,
                                                  double candidateSigma,
                                                  double bestScore,
                                                  DetectionConfig bestConfig,
                                                  AutoTuneProfile profile) {
        if (bestConfig == null) {
            return true;
        }

        if (candidateScore > bestScore) {
            return true;
        }

        if (profile != AutoTuneProfile.AGGRESSIVE) {
            return false;
        }

        if ((bestScore - candidateScore) > AGGRESSIVE_LOWER_SIGMA_SCORE_WINDOW) {
            return false;
        }

        return candidateSigma < bestConfig.detectionSigmaMultiplier;
    }

    /**
     * Returns a percentile from an already sorted list.
     */
    private static double getPercentileFromSorted(List<Double> sortedValues, double percentile) {
        if (sortedValues == null || sortedValues.isEmpty()) return 0.0;
        if (sortedValues.size() == 1) return sortedValues.get(0);

        double p = clamp01(percentile);
        int index = (int) Math.round(p * (sortedValues.size() - 1));
        return sortedValues.get(index);
    }

    /**
     * Penalizes configurations that fall outside the desired transient leakage band.
     */
    private static double computeTransientSweetSpotPenalty(double avgTransientsPerCropFrame,
                                                           double low,
                                                           double target,
                                                           double high) {
        // Best score at 'target'
        // Mild penalty near the edges of the band
        // Stronger penalty outside the band
        if (high <= low) return 0.0;

        // Below the acceptable band
        if (avgTransientsPerCropFrame < low) {
            return clamp01((low - avgTransientsPerCropFrame) / Math.max(low, 1e-9));
        }

        // Above the acceptable band
        if (avgTransientsPerCropFrame > high) {
            return clamp01((avgTransientsPerCropFrame - high) / Math.max(high, 1e-9));
        }

        // Inside the band: prefer the center target, but only mildly
        if (avgTransientsPerCropFrame <= target) {
            return 0.5 * clamp01(
                    (target - avgTransientsPerCropFrame) / Math.max(target - low, 1e-9)
            );
        } else {
            return 0.5 * clamp01(
                    (avgTransientsPerCropFrame - target) / Math.max(high - target, 1e-9)
            );
        }
    }

    /**
     * Returns the scoring policy associated with a named tuning profile.
     */
    private static AutoTunePolicy getPolicy(AutoTuneProfile profile) {
        switch (profile) {
            case CONSERVATIVE:
                return new AutoTunePolicy(
                        0.05,
                        0.20,
                        0.05,
                        0.50,
                        100.0,
                        120.0,
                        20.0,
                        60.0,
                        50.0,
                        22.0,
                        0.40, // harshnessSigmaWeight
                        0.35, // harshnessMinPixWeight
                        0.25, // harshnessGrowDeltaWeight
                        10.0, // lowSigmaMinPixGuardWeight
                        4.0,  // lowSigmaMinPixPivot
                        3.0   // lowSigmaMinPixSlope
                );

            case AGGRESSIVE:
                return new AutoTunePolicy(
                        0.12,
                        1.00,
                        0.40,
                        2.00,
                        100.0,
                        70.0,
                        35.0,
                        50.0,
                        50.0,
                        30.0,
                        0.85, // harshnessSigmaWeight -> aggressively prefer lower sigma
                        0.00, // harshnessMinPixWeight -> do not reward tiny minPixels
                        0.15, // harshnessGrowDeltaWeight
                        30.0, // lowSigmaMinPixGuardWeight -> but protect against sigma low + minPix tiny
                        4.0,  // lowSigmaMinPixPivot
                        4.0   // lowSigmaMinPixSlope
                );

            case BALANCED:
            default:
                return new AutoTunePolicy(
                        0.05,
                        0.35,
                        0.15,
                        0.80,
                        100.0,
                        120.0,
                        25.0,
                        60.0,
                        50.0,
                        20.0,
                        0.55, // harshnessSigmaWeight
                        0.20, // harshnessMinPixWeight
                        0.25, // harshnessGrowDeltaWeight
                        18.0, // lowSigmaMinPixGuardWeight
                        4.0,  // lowSigmaMinPixPivot
                        4.0   // lowSigmaMinPixSlope
                );
        }
    }

    /**
     * Penalizes overly permissive low-sigma and low-minPixels combinations.
     */
    private static double computeLowSigmaMinPixGuardPenalty(double sigma,
                                                            int minPix,
                                                            double sigmaPivot,
                                                            double minPixSlope) {
        // No penalty if sigma is above the pivot.
        if (sigma >= sigmaPivot) {
            return 0.0;
        }

        // As sigma gets lower than the pivot, require more minPixels.
        // Example:
        // sigmaPivot = 4.0, minPixSlope = 4.0
        // sigma = 3.5 -> desired minPix ~= 5
        // sigma = 3.0 -> desired minPix ~= 7
        double desiredMinPix = 3.0 + ((sigmaPivot - sigma) * minPixSlope);

        if (minPix >= desiredMinPix) {
            return 0.0;
        }

        return clamp01((desiredMinPix - minPix) / Math.max(desiredMinPix, 1e-9));
    }

    /**
     * Calibrates maxStarJitter before the sweep by matching each crop's master-stack stars
     * back to the sampled frame stars. This measures the actual residual star displacement
     * relative to the reference stack that the veto mask will later use.
     */
    private static double measureMaxStarJitter(List<List<CroppedFrame>> croppedFramesByRegion,
                                               DetectionConfig baseConfig,
                                               StringBuilder report) {
        List<Double> distances = new ArrayList<>();
        int usableReferenceStars = 0;

        DetectionConfig probeConfig = baseConfig.clone();
        probeConfig.detectionSigmaMultiplier = INITIAL_JITTER_PROBE_SIGMA;
        probeConfig.growSigmaMultiplier = INITIAL_JITTER_PROBE_GROW_SIGMA;
        probeConfig.minDetectionPixels = INITIAL_JITTER_PROBE_MIN_PIXELS;

        // These are not very important for this pre-pass, but keep them small/neutral.
        probeConfig.edgeMarginPixels = 5;
        probeConfig.voidProximityRadius = 5;

        for (int cropIndex = 0; cropIndex < croppedFramesByRegion.size(); cropIndex++) {
            List<CroppedFrame> cropFrames = croppedFramesByRegion.get(cropIndex);
            if (cropFrames.isEmpty()) {
                continue;
            }

            short[][] masterStackData = createMedianMasterStackForCrops(cropFrames);

            DetectionConfig masterConfig = probeConfig.clone();
            masterConfig.growSigmaMultiplier = masterConfig.masterSigmaMultiplier;
            masterConfig.edgeMarginPixels = 5;
            masterConfig.voidProximityRadius = 5;

            List<SourceExtractor.DetectedObject> masterStars = SourceExtractor.extractSources(
                    masterStackData,
                    masterConfig.masterSigmaMultiplier,
                    masterConfig.masterMinDetectionPixels,
                    masterConfig
            ).objects;

            if (masterStars == null || masterStars.isEmpty()) {
                continue;
            }

            List<List<SourceExtractor.DetectedObject>> frameObjectsByCrop = new ArrayList<>(cropFrames.size());
            for (CroppedFrame frame : cropFrames) {
                frameObjectsByCrop.add(SourceExtractor.extractSources(
                        frame.pixelData,
                        probeConfig.detectionSigmaMultiplier,
                        probeConfig.minDetectionPixels,
                        probeConfig
                ).objects);
            }

            for (SourceExtractor.DetectedObject masterStar : masterStars) {
                if (!isUsableForJitterCalibration(masterStar)) {
                    continue;
                }

                usableReferenceStars++;

                for (List<SourceExtractor.DetectedObject> frameObjects : frameObjectsByCrop) {
                    SourceExtractor.DetectedObject frameStar = findClosestUsableJitterMatch(masterStar, frameObjects);
                    if (frameStar == null) {
                        continue;
                    }

                    double dist = distance(masterStar, frameStar);
                    if (dist > SEARCH_RADIUS_PX) {
                        continue;
                    }

                    // Require reciprocal nearest-neighbor matching back to the reference stack.
                    SourceExtractor.DetectedObject reciprocal = findClosestUsableJitterMatch(frameStar, masterStars);
                    if (reciprocal != masterStar) {
                        continue;
                    }

                    distances.add(dist);
                }
            }
        }

        if (distances.size() < MIN_INITIAL_JITTER_MATCHES) {
            double fallback = Math.max(MIN_JITTER_FLOOR, INITIAL_JITTER_FALLBACK_PX);

            if (DEBUG) {
                System.out.printf(
                        "[JITTER] maxStarJitter calibration had too few matches (%d) across %d reference stars. Falling back to %.2f px%n",
                        distances.size(), usableReferenceStars, fallback
                );
            }

            report.append(String.format(
                    "maxStarJitter calibration had too few matches (%d) across %d reference stars. Falling back to %.2f px%n",
                    distances.size(), usableReferenceStars, fallback
            ));

            return fallback;
        }

        Collections.sort(distances);
        double pJitter = getPercentileFromSorted(distances, JITTER_PERCENTILE);

        double estimated = Math.max(MIN_JITTER_FLOOR, pJitter * JITTER_SAFETY_MULTIPLIER);

        if (DEBUG) {
            System.out.printf(
                    "[JITTER] Calibrated maxStarJitter from %d matches across %d reference stars: P%.0f = %.2f px -> %.2f px%n",
                    distances.size(), usableReferenceStars, JITTER_PERCENTILE * 100.0, pJitter, estimated
            );
        }

        report.append(String.format(
                "Calibrated maxStarJitter from %d matches across %d reference stars: P%.0f = %.2f px -> %.2f px%n",
                distances.size(), usableReferenceStars, JITTER_PERCENTILE * 100.0, pJitter, estimated
        ));

        return estimated;
    }

    /**
     * Returns whether an extracted object is reliable enough to use for jitter calibration.
     */
    private static boolean isUsableForJitterCalibration(SourceExtractor.DetectedObject obj) {
        if (obj == null) {
            return false;
        }

        if (obj.rawPixels == null || obj.rawPixels.size() < INITIAL_JITTER_PROBE_MIN_PIXELS) {
            return false;
        }

        return obj.elongation <= INITIAL_JITTER_PROBE_MAX_ELONGATION;
    }

    /**
     * Finds the closest usable jitter-calibration match for a source object.
     */
    private static SourceExtractor.DetectedObject findClosestUsableJitterMatch(SourceExtractor.DetectedObject source,
                                                                               List<SourceExtractor.DetectedObject> candidates) {
        SourceExtractor.DetectedObject best = null;
        double bestDist = Double.MAX_VALUE;

        for (SourceExtractor.DetectedObject candidate : candidates) {
            if (!isUsableForJitterCalibration(candidate)) {
                continue;
            }

            double d = distance(source, candidate);
            if (d < bestDist) {
                bestDist = d;
                best = candidate;
            }
        }

        return best;
    }

    /**
     * Returns Euclidean distance between two detected objects.
     */
    private static double distance(SourceExtractor.DetectedObject a,
                                   SourceExtractor.DetectedObject b) {
        double dx = a.x - b.x;
        double dy = a.y - b.y;
        return Math.sqrt(dx * dx + dy * dy);
    }

}
