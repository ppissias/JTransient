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

import io.github.ppissias.jtransient.execution.CancellationScope;
import io.github.ppissias.jtransient.execution.ExecutionPolicy;
import io.github.ppissias.jtransient.execution.ExecutionSession;
import io.github.ppissias.jtransient.config.DetectionConfig;
import io.github.ppissias.jtransient.config.DetectionConfigRules;
import io.github.ppissias.jtransient.core.DataArea;
import io.github.ppissias.jtransient.core.MasterReferenceAnalyzer;
import io.github.ppissias.jtransient.core.MasterVetoMask;
import io.github.ppissias.jtransient.core.PixelEncoding;
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

    /** Enables verbose console diagnostics for the tuning process; off by default, so the library prints nothing. */
    public static boolean DEBUG = false;

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
    public static double MIN_GROW_SIGMA_ABOVE_MASTER_SIGMA = 0.5;

    // Tune the actual binary-veto behavior as well
    public static double[] MASK_OVERLAP_TO_TEST = {0.65, 0.70, 0.75};


    // Penalize oversized veto masks
    public static double SOFT_MASK_COVERAGE_START = 0.08;
    public static double HARD_MASK_COVERAGE_REJECT = 0.25;
    public static double AGGRESSIVE_LOWER_SIGMA_SCORE_WINDOW = 8.0;

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
     * Immutable per-operation tuning policy. Arrays are copied on capture and access; pixel buffers are not owned.
     * Legacy static knobs are read only by the builder, never by a running operation.
     * Diagnostic sinks run synchronously on the tuning thread and are caller-owned; disabled diagnostics stay silent.
     */
    public static final class Options {
        private final ExecutionPolicy executionPolicy;
        private final boolean debug;
        private final int sampleSize;
        private final double searchRadiusPx;
        private final int minStableStars;
        private final double optimalStarCount;
        private final int maxTotalExtractedObjects;
        private final int minTotalExtractedObjects;
        private final double[] sigmasToTest;
        private final int[] minPixelsToTest;
        private final double[] growDeltasToTest;
        private final double minGrowSigmaAboveMasterSigma;
        private final double[] maskOverlapToTest;
        private final double softMaskCoverageStart;
        private final double hardMaskCoverageReject;
        private final double aggressiveLowerSigmaScoreWindow;
        private final double stableStarsCvAnchor;
        private final double transientsCvAnchor;
        private final double jitterPercentile;
        private final double minJitterFloor;
        private final double jitterSafetyMultiplier;
        private final double initialJitterFallbackPx;
        private final double initialJitterProbeSigma;
        private final double initialJitterProbeGrowSigma;
        private final int initialJitterProbeMinPixels;
        private final double initialJitterProbeMaxElongation;
        private final int minInitialJitterMatches;
        private final int cropCount;
        private final int borderMargin;
        private final int preferredCropSizeLarge;
        private final int preferredCropSizeSmall;
        private final int minCropSize;
        private final transient java.util.function.Consumer<String> diagnosticSink;

        /** Copies and validates a builder before progress callbacks or scientific work can start. */
        private Options(Builder builder) {
            executionPolicy = java.util.Objects.requireNonNull(builder.executionPolicy, "executionPolicy");
            debug = builder.debug;
            sampleSize = builder.sampleSize;
            searchRadiusPx = builder.searchRadiusPx;
            minStableStars = builder.minStableStars;
            optimalStarCount = builder.optimalStarCount;
            maxTotalExtractedObjects = builder.maxTotalExtractedObjects;
            minTotalExtractedObjects = builder.minTotalExtractedObjects;
            sigmasToTest = java.util.Objects.requireNonNull(builder.sigmasToTest, "sigmasToTest").clone();
            minPixelsToTest = java.util.Objects.requireNonNull(builder.minPixelsToTest, "minPixelsToTest").clone();
            growDeltasToTest = java.util.Objects.requireNonNull(builder.growDeltasToTest, "growDeltasToTest").clone();
            minGrowSigmaAboveMasterSigma = builder.minGrowSigmaAboveMasterSigma;
            maskOverlapToTest = java.util.Objects.requireNonNull(builder.maskOverlapToTest, "maskOverlapToTest").clone();
            softMaskCoverageStart = builder.softMaskCoverageStart;
            hardMaskCoverageReject = builder.hardMaskCoverageReject;
            aggressiveLowerSigmaScoreWindow = builder.aggressiveLowerSigmaScoreWindow;
            stableStarsCvAnchor = builder.stableStarsCvAnchor;
            transientsCvAnchor = builder.transientsCvAnchor;
            jitterPercentile = builder.jitterPercentile;
            minJitterFloor = builder.minJitterFloor;
            jitterSafetyMultiplier = builder.jitterSafetyMultiplier;
            initialJitterFallbackPx = builder.initialJitterFallbackPx;
            initialJitterProbeSigma = builder.initialJitterProbeSigma;
            initialJitterProbeGrowSigma = builder.initialJitterProbeGrowSigma;
            initialJitterProbeMinPixels = builder.initialJitterProbeMinPixels;
            initialJitterProbeMaxElongation = builder.initialJitterProbeMaxElongation;
            minInitialJitterMatches = builder.minInitialJitterMatches;
            cropCount = builder.cropCount;
            borderMargin = builder.borderMargin;
            preferredCropSizeLarge = builder.preferredCropSizeLarge;
            preferredCropSizeSmall = builder.preferredCropSizeSmall;
            minCropSize = builder.minCropSize;
            diagnosticSink = java.util.Objects.requireNonNull(builder.diagnosticSink, "diagnosticSink");
            require(sampleSize > 0, "sampleSize must be positive");
            require(Double.isFinite(searchRadiusPx) && searchRadiusPx > 0, "searchRadiusPx must be finite and positive");
            require(minStableStars >= 0, "minStableStars must be nonnegative");
            require(Double.isFinite(optimalStarCount) && optimalStarCount > 0, "optimalStarCount must be finite and positive");
            require(maxTotalExtractedObjects > 0, "maxTotalExtractedObjects must be positive");
            require(minTotalExtractedObjects >= 0, "minTotalExtractedObjects must be nonnegative");
            require(sigmasToTest.length > 0, "sigmasToTest must not be empty");
            for (double value : sigmasToTest) {
                require(Double.isFinite(value) && value > 0, "sigmasToTest contains an invalid value");
            }
            require(minPixelsToTest.length > 0, "minPixelsToTest must not be empty");
            for (int value : minPixelsToTest) {
                require(value > 0, "minPixelsToTest contains an invalid value");
            }
            require(growDeltasToTest.length > 0, "growDeltasToTest must not be empty");
            for (double value : growDeltasToTest) {
                require(Double.isFinite(value) && value > 0, "growDeltasToTest contains an invalid value");
            }
            require(Double.isFinite(minGrowSigmaAboveMasterSigma) && minGrowSigmaAboveMasterSigma >= 0, "minGrowSigmaAboveMasterSigma must be finite and nonnegative");
            require(maskOverlapToTest.length > 0, "maskOverlapToTest must not be empty");
            for (double value : maskOverlapToTest) {
                require(Double.isFinite(value) && value > 0 && value <= 1, "maskOverlapToTest contains an invalid value");
            }
            require(Double.isFinite(softMaskCoverageStart) && softMaskCoverageStart >= 0, "softMaskCoverageStart must be finite and nonnegative");
            require(softMaskCoverageStart <= 1, "softMaskCoverageStart must not exceed one");
            require(Double.isFinite(hardMaskCoverageReject) && hardMaskCoverageReject > 0, "hardMaskCoverageReject must be finite and positive");
            require(hardMaskCoverageReject <= 1, "hardMaskCoverageReject must not exceed one");
            require(Double.isFinite(aggressiveLowerSigmaScoreWindow) && aggressiveLowerSigmaScoreWindow >= 0, "aggressiveLowerSigmaScoreWindow must be finite and nonnegative");
            require(Double.isFinite(stableStarsCvAnchor) && stableStarsCvAnchor > 0, "stableStarsCvAnchor must be finite and positive");
            require(Double.isFinite(transientsCvAnchor) && transientsCvAnchor > 0, "transientsCvAnchor must be finite and positive");
            require(Double.isFinite(jitterPercentile) && jitterPercentile > 0, "jitterPercentile must be finite and positive");
            require(jitterPercentile <= 1, "jitterPercentile must not exceed one");
            require(Double.isFinite(minJitterFloor) && minJitterFloor > 0, "minJitterFloor must be finite and positive");
            require(Double.isFinite(jitterSafetyMultiplier) && jitterSafetyMultiplier > 0, "jitterSafetyMultiplier must be finite and positive");
            require(Double.isFinite(initialJitterFallbackPx) && initialJitterFallbackPx > 0, "initialJitterFallbackPx must be finite and positive");
            require(Double.isFinite(initialJitterProbeSigma) && initialJitterProbeSigma > 0, "initialJitterProbeSigma must be finite and positive");
            require(Double.isFinite(initialJitterProbeGrowSigma) && initialJitterProbeGrowSigma > 0, "initialJitterProbeGrowSigma must be finite and positive");
            require(initialJitterProbeMinPixels > 0, "initialJitterProbeMinPixels must be positive");
            require(Double.isFinite(initialJitterProbeMaxElongation) && initialJitterProbeMaxElongation > 0, "initialJitterProbeMaxElongation must be finite and positive");
            require(minInitialJitterMatches > 0, "minInitialJitterMatches must be positive");
            require(cropCount > 0, "cropCount must be positive");
            require(borderMargin >= 0, "borderMargin must be nonnegative");
            require(preferredCropSizeLarge > 0, "preferredCropSizeLarge must be positive");
            require(preferredCropSizeSmall > 0, "preferredCropSizeSmall must be positive");
            require(minCropSize > 0, "minCropSize must be positive");
            require(maxTotalExtractedObjects >= minTotalExtractedObjects, "object-count bounds are reversed");
            require(hardMaskCoverageReject >= softMaskCoverageStart, "mask-coverage bounds are reversed");
        }

        /** Captures compatibility defaults once; callers must not edit static knobs concurrently with this capture. */
        public static Options captureDefaults() {
            return new Builder().build();
        }

        /** Creates an independent mutable builder from the current compatibility defaults. */
        public static Builder builder() {
            return new Builder();
        }

        /** Returns an independent builder, including detached copies of every search-grid array. */
        public Builder toBuilder() {
            return new Builder(this);
        }

        /** Returns the immutable per-operation CPU/scratch-estimation limits, excluding retained image buffers. */
        public ExecutionPolicy executionPolicy() {
            return executionPolicy;
        }

        /** Captures the actual borrowed-session policy in result provenance without changing scientific options. */
        private Options withExecutionPolicy(ExecutionPolicy policy) {
            if (executionPolicy.equals(policy)) return this;
            Builder builder = toBuilder();
            builder.executionPolicy = policy;
            return builder.build();
        }

        /** Returns the captured debug policy value. */
        public boolean debug() {
            return debug;
        }

        /** Returns the captured sampleSize policy value in pixels where applicable. */
        public int sampleSize() {
            return sampleSize;
        }

        /** Returns the captured searchRadiusPx policy value in pixels where applicable. */
        public double searchRadiusPx() {
            return searchRadiusPx;
        }

        /** Returns the captured minStableStars policy value. */
        public int minStableStars() {
            return minStableStars;
        }

        /** Returns the captured optimalStarCount policy value. */
        public double optimalStarCount() {
            return optimalStarCount;
        }

        /** Returns the captured maxTotalExtractedObjects policy value. */
        public int maxTotalExtractedObjects() {
            return maxTotalExtractedObjects;
        }

        /** Returns the captured minTotalExtractedObjects policy value. */
        public int minTotalExtractedObjects() {
            return minTotalExtractedObjects;
        }

        /** Returns a detached copy of the sigmasToTest grid. */
        public double[] sigmasToTest() {
            return sigmasToTest.clone();
        }

        /** Returns a detached copy of the minPixelsToTest grid in pixels where applicable. */
        public int[] minPixelsToTest() {
            return minPixelsToTest.clone();
        }

        /** Returns a detached copy of the growDeltasToTest grid. */
        public double[] growDeltasToTest() {
            return growDeltasToTest.clone();
        }

        /** Returns the captured minGrowSigmaAboveMasterSigma policy value. */
        public double minGrowSigmaAboveMasterSigma() {
            return minGrowSigmaAboveMasterSigma;
        }

        /** Returns a detached copy of the maskOverlapToTest grid. */
        public double[] maskOverlapToTest() {
            return maskOverlapToTest.clone();
        }

        /** Returns the captured softMaskCoverageStart policy value. */
        public double softMaskCoverageStart() {
            return softMaskCoverageStart;
        }

        /** Returns the captured hardMaskCoverageReject policy value. */
        public double hardMaskCoverageReject() {
            return hardMaskCoverageReject;
        }

        /** Returns the captured aggressiveLowerSigmaScoreWindow policy value. */
        public double aggressiveLowerSigmaScoreWindow() {
            return aggressiveLowerSigmaScoreWindow;
        }

        /** Returns the captured stableStarsCvAnchor policy value. */
        public double stableStarsCvAnchor() {
            return stableStarsCvAnchor;
        }

        /** Returns the captured transientsCvAnchor policy value. */
        public double transientsCvAnchor() {
            return transientsCvAnchor;
        }

        /** Returns the captured jitterPercentile policy value. */
        public double jitterPercentile() {
            return jitterPercentile;
        }

        /** Returns the captured minJitterFloor policy value. */
        public double minJitterFloor() {
            return minJitterFloor;
        }

        /** Returns the captured jitterSafetyMultiplier policy value. */
        public double jitterSafetyMultiplier() {
            return jitterSafetyMultiplier;
        }

        /** Returns the captured initialJitterFallbackPx policy value in pixels where applicable. */
        public double initialJitterFallbackPx() {
            return initialJitterFallbackPx;
        }

        /** Returns the captured initialJitterProbeSigma policy value. */
        public double initialJitterProbeSigma() {
            return initialJitterProbeSigma;
        }

        /** Returns the captured initialJitterProbeGrowSigma policy value. */
        public double initialJitterProbeGrowSigma() {
            return initialJitterProbeGrowSigma;
        }

        /** Returns the captured initialJitterProbeMinPixels policy value in pixels where applicable. */
        public int initialJitterProbeMinPixels() {
            return initialJitterProbeMinPixels;
        }

        /** Returns the captured initialJitterProbeMaxElongation policy value. */
        public double initialJitterProbeMaxElongation() {
            return initialJitterProbeMaxElongation;
        }

        /** Returns the captured minInitialJitterMatches policy value. */
        public int minInitialJitterMatches() {
            return minInitialJitterMatches;
        }

        /** Returns the captured cropCount policy value. */
        public int cropCount() {
            return cropCount;
        }

        /** Returns the captured borderMargin policy value in pixels where applicable. */
        public int borderMargin() {
            return borderMargin;
        }

        /** Returns the captured preferredCropSizeLarge policy value in pixels where applicable. */
        public int preferredCropSizeLarge() {
            return preferredCropSizeLarge;
        }

        /** Returns the captured preferredCropSizeSmall policy value in pixels where applicable. */
        public int preferredCropSizeSmall() {
            return preferredCropSizeSmall;
        }

        /** Returns the captured minCropSize policy value in pixels where applicable. */
        public int minCropSize() {
            return minCropSize;
        }

        /** Returns the caller-owned synchronous diagnostic sink. */
        public java.util.function.Consumer<String> diagnosticSink() {
            return diagnosticSink;
        }

        /** Rejects invalid policy values without invoking callbacks or reading image data. */
        private static void require(boolean condition, String message) {
            if (!condition) throw new IllegalArgumentException(message);
        }

        /** Mutable caller-owned construction state; build snapshots arrays and never modifies this builder. */
        public static final class Builder {
            /** Per-operation batch limits; borrowed sessions use their own resource policy instead. */
            public ExecutionPolicy executionPolicy;
            /** debug policy; array values are copied by build(). */
            public boolean debug;
            /** sampleSize policy; array values are copied by build(). */
            public int sampleSize;
            /** searchRadiusPx policy; array values are copied by build(). */
            public double searchRadiusPx;
            /** minStableStars policy; array values are copied by build(). */
            public int minStableStars;
            /** optimalStarCount policy; array values are copied by build(). */
            public double optimalStarCount;
            /** maxTotalExtractedObjects policy; array values are copied by build(). */
            public int maxTotalExtractedObjects;
            /** minTotalExtractedObjects policy; array values are copied by build(). */
            public int minTotalExtractedObjects;
            /** sigmasToTest policy; array values are copied by build(). */
            public double[] sigmasToTest;
            /** minPixelsToTest policy; array values are copied by build(). */
            public int[] minPixelsToTest;
            /** growDeltasToTest policy; array values are copied by build(). */
            public double[] growDeltasToTest;
            /** minGrowSigmaAboveMasterSigma policy; array values are copied by build(). */
            public double minGrowSigmaAboveMasterSigma;
            /** maskOverlapToTest policy; array values are copied by build(). */
            public double[] maskOverlapToTest;
            /** softMaskCoverageStart policy; array values are copied by build(). */
            public double softMaskCoverageStart;
            /** hardMaskCoverageReject policy; array values are copied by build(). */
            public double hardMaskCoverageReject;
            /** aggressiveLowerSigmaScoreWindow policy; array values are copied by build(). */
            public double aggressiveLowerSigmaScoreWindow;
            /** stableStarsCvAnchor policy; array values are copied by build(). */
            public double stableStarsCvAnchor;
            /** transientsCvAnchor policy; array values are copied by build(). */
            public double transientsCvAnchor;
            /** jitterPercentile policy; array values are copied by build(). */
            public double jitterPercentile;
            /** minJitterFloor policy; array values are copied by build(). */
            public double minJitterFloor;
            /** jitterSafetyMultiplier policy; array values are copied by build(). */
            public double jitterSafetyMultiplier;
            /** initialJitterFallbackPx policy; array values are copied by build(). */
            public double initialJitterFallbackPx;
            /** initialJitterProbeSigma policy; array values are copied by build(). */
            public double initialJitterProbeSigma;
            /** initialJitterProbeGrowSigma policy; array values are copied by build(). */
            public double initialJitterProbeGrowSigma;
            /** initialJitterProbeMinPixels policy; array values are copied by build(). */
            public int initialJitterProbeMinPixels;
            /** initialJitterProbeMaxElongation policy; array values are copied by build(). */
            public double initialJitterProbeMaxElongation;
            /** minInitialJitterMatches policy; array values are copied by build(). */
            public int minInitialJitterMatches;
            /** cropCount policy; array values are copied by build(). */
            public int cropCount;
            /** borderMargin policy; array values are copied by build(). */
            public int borderMargin;
            /** preferredCropSizeLarge policy; array values are copied by build(). */
            public int preferredCropSizeLarge;
            /** preferredCropSizeSmall policy; array values are copied by build(). */
            public int preferredCropSizeSmall;
            /** minCropSize policy; array values are copied by build(). */
            public int minCropSize;
            /** Synchronous diagnostic destination, used only when debug is enabled. */
            public java.util.function.Consumer<String> diagnosticSink;

            /** Captures the current legacy defaults, copying arrays immediately. */
            public Builder() {
                executionPolicy = ExecutionPolicy.defaults();
                debug = JTransientAutoTuner.DEBUG;
                sampleSize = JTransientAutoTuner.AUTO_TUNE_SAMPLE_SIZE;
                searchRadiusPx = JTransientAutoTuner.SEARCH_RADIUS_PX;
                minStableStars = JTransientAutoTuner.MIN_STABLE_STARS;
                optimalStarCount = JTransientAutoTuner.OPTIMAL_STAR_COUNT;
                maxTotalExtractedObjects = JTransientAutoTuner.MAX_TOTAL_EXTRACTED_OBJECTS;
                minTotalExtractedObjects = JTransientAutoTuner.MIN_TOTAL_EXTRACTED_OBJECTS;
                sigmasToTest = JTransientAutoTuner.SIGMAS_TO_TEST.clone();
                minPixelsToTest = JTransientAutoTuner.MIN_PIXELS_TO_TEST.clone();
                growDeltasToTest = JTransientAutoTuner.GROW_DELTAS_TO_TEST.clone();
                minGrowSigmaAboveMasterSigma = JTransientAutoTuner.MIN_GROW_SIGMA_ABOVE_MASTER_SIGMA;
                maskOverlapToTest = JTransientAutoTuner.MASK_OVERLAP_TO_TEST.clone();
                softMaskCoverageStart = JTransientAutoTuner.SOFT_MASK_COVERAGE_START;
                hardMaskCoverageReject = JTransientAutoTuner.HARD_MASK_COVERAGE_REJECT;
                aggressiveLowerSigmaScoreWindow = JTransientAutoTuner.AGGRESSIVE_LOWER_SIGMA_SCORE_WINDOW;
                stableStarsCvAnchor = JTransientAutoTuner.STABLE_STARS_CV_ANCHOR;
                transientsCvAnchor = JTransientAutoTuner.TRANSIENTS_CV_ANCHOR;
                jitterPercentile = JTransientAutoTuner.JITTER_PERCENTILE;
                minJitterFloor = JTransientAutoTuner.MIN_JITTER_FLOOR;
                jitterSafetyMultiplier = JTransientAutoTuner.JITTER_SAFETY_MULTIPLIER;
                initialJitterFallbackPx = JTransientAutoTuner.INITIAL_JITTER_FALLBACK_PX;
                initialJitterProbeSigma = JTransientAutoTuner.INITIAL_JITTER_PROBE_SIGMA;
                initialJitterProbeGrowSigma = JTransientAutoTuner.INITIAL_JITTER_PROBE_GROW_SIGMA;
                initialJitterProbeMinPixels = JTransientAutoTuner.INITIAL_JITTER_PROBE_MIN_PIXELS;
                initialJitterProbeMaxElongation = JTransientAutoTuner.INITIAL_JITTER_PROBE_MAX_ELONGATION;
                minInitialJitterMatches = JTransientAutoTuner.MIN_INITIAL_JITTER_MATCHES;
                cropCount = JTransientAutoTuner.AUTO_TUNE_CROP_COUNT;
                borderMargin = JTransientAutoTuner.AUTO_TUNE_BORDER_MARGIN;
                preferredCropSizeLarge = JTransientAutoTuner.AUTO_TUNE_PREFERRED_CROP_SIZE_LARGE;
                preferredCropSizeSmall = JTransientAutoTuner.AUTO_TUNE_PREFERRED_CROP_SIZE_SMALL;
                minCropSize = JTransientAutoTuner.AUTO_TUNE_MIN_CROP_SIZE;
                diagnosticSink = System.out::print;
            }

            /** Copies an immutable policy without consulting process-wide defaults. */
            private Builder(Options options) {
                executionPolicy = options.executionPolicy;
                debug = options.debug;
                sampleSize = options.sampleSize;
                searchRadiusPx = options.searchRadiusPx;
                minStableStars = options.minStableStars;
                optimalStarCount = options.optimalStarCount;
                maxTotalExtractedObjects = options.maxTotalExtractedObjects;
                minTotalExtractedObjects = options.minTotalExtractedObjects;
                sigmasToTest = options.sigmasToTest.clone();
                minPixelsToTest = options.minPixelsToTest.clone();
                growDeltasToTest = options.growDeltasToTest.clone();
                minGrowSigmaAboveMasterSigma = options.minGrowSigmaAboveMasterSigma;
                maskOverlapToTest = options.maskOverlapToTest.clone();
                softMaskCoverageStart = options.softMaskCoverageStart;
                hardMaskCoverageReject = options.hardMaskCoverageReject;
                aggressiveLowerSigmaScoreWindow = options.aggressiveLowerSigmaScoreWindow;
                stableStarsCvAnchor = options.stableStarsCvAnchor;
                transientsCvAnchor = options.transientsCvAnchor;
                jitterPercentile = options.jitterPercentile;
                minJitterFloor = options.minJitterFloor;
                jitterSafetyMultiplier = options.jitterSafetyMultiplier;
                initialJitterFallbackPx = options.initialJitterFallbackPx;
                initialJitterProbeSigma = options.initialJitterProbeSigma;
                initialJitterProbeGrowSigma = options.initialJitterProbeGrowSigma;
                initialJitterProbeMinPixels = options.initialJitterProbeMinPixels;
                initialJitterProbeMaxElongation = options.initialJitterProbeMaxElongation;
                minInitialJitterMatches = options.minInitialJitterMatches;
                cropCount = options.cropCount;
                borderMargin = options.borderMargin;
                preferredCropSizeLarge = options.preferredCropSizeLarge;
                preferredCropSizeSmall = options.preferredCropSizeSmall;
                minCropSize = options.minCropSize;
                diagnosticSink = options.diagnosticSink;
            }

            /** Builds an immutable validated policy whose arrays no longer alias the builder. */
            public Options build() {
                return new Options(this);
            }
        }
    }

    /**
     * Creates an auto-tuner facade.
     *
     * <p>The class is typically used through its static {@code tune(...)} methods.</p>
     */
    public JTransientAutoTuner() {
    }

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

        /** Effective immutable policy used by this legacy operation; null for calibrated or older serialized results. */
        public Options effectiveOptions;
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
        /**
         * Optional short plain-language summary of the chosen settings and their measured effect,
         * set by tuners that measure noise and sensitivity directly; null for this legacy tuner.
         */
        public String summary;
        /**
         * Full measurement of the calibrated tuner, including the chosen settings of every profile, so a
         * caller can switch profiles without re-running; null for this legacy tuner.
         */
        public CalibratedAutoTuner.Calibration calibration;
    }

    /**
     * High-level tuning policy presets.
     */
    public enum AutoTuneProfile {
        /** Low sensitivity: the fewest noise detections. */
        LOW,
        /** Medium sensitivity. */
        MEDIUM,
        /** High sensitivity: close to the noise level. */
        HIGH,
        /**
         * As sensitive as possible: many more candidates to review, for small sensors or targeted searches for
         * faint objects. The legacy tuner treats it like {@link #HIGH}.
         */
        MAXIMUM;

        /** The name for display, for example "Medium". */
        public String displayName() {
            String name = name().toLowerCase(java.util.Locale.ROOT);
            return Character.toUpperCase(name.charAt(0)) + name.substring(1);
        }

        /**
         * Parses a profile name, case-insensitive. The names of earlier versions are still accepted:
         * conservative = low, balanced = medium, aggressive = high.
         */
        public static AutoTuneProfile parse(String value) {
            String name = value == null ? "" : value.trim().toUpperCase(java.util.Locale.ROOT);
            switch (name) {
                case "CONSERVATIVE":
                    return LOW;
                case "BALANCED":
                    return MEDIUM;
                case "AGGRESSIVE":
                    return HIGH;
                default:
                    try {
                        return valueOf(name);
                    } catch (IllegalArgumentException e) {
                        throw new IllegalArgumentException("Unknown auto-tune profile '" + value
                                + "'. Expected low, medium, high or maximum.");
                    }
            }
        }
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
     * @return tuning result using the medium profile
     */
    public static AutoTunerResult tune(List<ImageFrame> allFrames, DetectionConfig baseConfig,
                                         AutoTuneProfile profile, TransientEngineProgressListener listener,
                                         io.github.ppissias.jtransient.execution.CancellationToken cancellation) {
        try (CancellationScope scope = CancellationScope.open(cancellation)) {
            CancellationScope.check();
            AutoTunerResult result = tune(allFrames, baseConfig, profile, listener);
            CancellationScope.check();
            return result;
        }
    }

    public static AutoTunerResult tune(List<ImageFrame> allFrames,
                                       DetectionConfig baseConfig,
                                       TransientEngineProgressListener listener) {
        return tune(allFrames, baseConfig, AutoTuneProfile.MEDIUM, listener);
    }

    /**
     * Automatically determines an optimized configuration using the supplied tuning policy.
     *
     * @param allFrames full frame sequence available for sampling
     * @param baseConfig starting configuration that is cloned and refined
     * @param profile policy preset controlling scoring aggressiveness
     * @param listener optional progress listener
     * @return tuning result containing the best configuration or a fallback to {@code baseConfig}
     * @throws IllegalArgumentException if the frames break the engine's input rules (see {@link JTransientEngine})
     */
    public static AutoTunerResult tune(List<ImageFrame> allFrames,
                                       DetectionConfig baseConfig,
                                       AutoTuneProfile profile,
                                       TransientEngineProgressListener listener) {
        AutoTunerResult result = tuneSnapshot(allFrames, baseConfig, profile, listener);
        if (!result.success) result.optimizedConfig = baseConfig;
        return result;
    }

    /**
     * Tunes a captured configuration under an explicit cancellation scope without aliasing caller fallback settings.
     *
     * @param allFrames frame sequence available for sampling
     * @param baseConfig caller settings, captured before progress and never modified by tuning
     * @param profile scoring policy preset
     * @param listener optional progress listener
     * @param cancellation cancellation request checked before capture, during work and before return
     * @return tuning outcome whose successful or fallback configuration is independent of baseConfig
     * @throws DetectionConfigRules.InvalidConfigurationException if consumed configuration values are invalid
     * @throws java.util.concurrent.CancellationException if cancellation is requested
     */
    public static AutoTunerResult tuneSnapshot(List<ImageFrame> allFrames, DetectionConfig baseConfig,
                                              AutoTuneProfile profile, TransientEngineProgressListener listener,
                                              io.github.ppissias.jtransient.execution.CancellationToken cancellation) {
        try (CancellationScope scope = CancellationScope.open(cancellation)) {
            CancellationScope.check();
            AutoTunerResult result = tuneSnapshot(allFrames, baseConfig, profile, listener);
            CancellationScope.check();
            return result;
        }
    }

    /**
     * Captures legacy-tuner inputs once and returns detached settings even when tuning cannot produce a winner.
     * Inherits an enclosing cancellation scope; unlike {@link #tune}, fallback never returns the caller's base object.
     *
     * @param allFrames frame sequence available for sampling
     * @param baseConfig caller-owned starting settings
     * @param profile scoring policy preset
     * @param listener optional progress listener
     * @return tuning outcome with an independent optimized or fallback configuration
     * @throws DetectionConfigRules.InvalidConfigurationException if consumed configuration values are invalid
     * @throws IllegalArgumentException if frame input rules are violated
     * @throws java.util.concurrent.CancellationException if the inherited scope is cancelled
     */
    public static AutoTunerResult tuneSnapshot(List<ImageFrame> allFrames, DetectionConfig baseConfig,
                                              AutoTuneProfile profile, TransientEngineProgressListener listener) {
        CancellationScope.check();
        return tuneSnapshot(allFrames, baseConfig, Options.captureDefaults(), profile, listener);
    }


    /**
     * Tunes with an immutable caller-supplied policy; neither config nor policy arrays alias running work.
     * Borrows frame pixels until return and inherits the current cancellation scope.
     */
    public static AutoTunerResult tuneSnapshot(List<ImageFrame> allFrames, DetectionConfig baseConfig,
                                               Options options, AutoTuneProfile profile,
                                               TransientEngineProgressListener listener) {
        CancellationScope.check();
        java.util.Objects.requireNonNull(options, "options");
        try (ExecutionSession execution = ExecutionSession.owned(options.executionPolicy)) {
            return tuneSnapshot(allFrames, baseConfig, options, execution, profile, listener);
        }
    }

    /** Tunes using caller-owned workers/session limits; all work is joined but the session is never closed here. */
    public static AutoTunerResult tuneSnapshot(List<ImageFrame> allFrames, DetectionConfig baseConfig,
                                               Options options, ExecutionSession execution, AutoTuneProfile profile,
                                               TransientEngineProgressListener listener) {
        CancellationScope.check();
        java.util.Objects.requireNonNull(options, "options");
        try (ExecutionSession.Scope scope = execution.open()) {
            return tuneCaptured(allFrames, DetectionConfigRules.snapshot(baseConfig, DetectionConfigRules.Operation.AUTO_TUNE),
                    profile, listener, options.withExecutionPolicy(execution.policy()));
        }
    }

    /** Tunes with per-operation options and explicit cancellation, joining all work before returning or throwing. */
    public static AutoTunerResult tuneSnapshot(List<ImageFrame> allFrames, DetectionConfig baseConfig,
                                               Options options, AutoTuneProfile profile,
                                               TransientEngineProgressListener listener,
                                               io.github.ppissias.jtransient.execution.CancellationToken cancellation) {
        try (CancellationScope scope = CancellationScope.open(cancellation)) {
            CancellationScope.check();
            AutoTunerResult result = tuneSnapshot(allFrames, baseConfig, options, profile, listener);
            CancellationScope.check();
            return result;
        }
    }

    /**
     * Tunes with captured defaults while limiting the legacy sample request to the available frame count.
     * This compatibility-friendly entry point replaces temporary edits of AUTO_TUNE_SAMPLE_SIZE in callers.
     * Borrows pixels until return, detaches fallback settings, and uses explicit cooperative cancellation.
     * Introduced in 1.0.6; the ordinary snapshot overload retains its minimum-sample fallback behavior.
     */
    public static AutoTunerResult tuneAvailableSnapshot(List<ImageFrame> allFrames, DetectionConfig baseConfig,
                                                       AutoTuneProfile profile, TransientEngineProgressListener listener,
                                                       io.github.ppissias.jtransient.execution.CancellationToken cancellation) {
        try (CancellationScope scope = CancellationScope.open(cancellation)) {
            CancellationScope.check();
            InputRules.checkFrames(allFrames);
            Options.Builder options = Options.builder();
            options.sampleSize = Math.min(options.sampleSize, allFrames.size());
            AutoTunerResult result = tuneSnapshot(allFrames, baseConfig, options.build(), profile, listener);
            CancellationScope.check();
            return result;
        }
    }

    /** Formats a diagnostic only when called by an enabled operation; the synchronous sink is caller-owned. */
    private static void debug(Options options, String format, Object... arguments) {
        options.diagnosticSink.accept(String.format(format, arguments));
    }

    /** Runs the legacy search using a validated private settings copy, including its detached fallback path. */
    private static AutoTunerResult tuneCaptured(List<ImageFrame> allFrames, DetectionConfig baseConfig,
                                               AutoTuneProfile profile, TransientEngineProgressListener listener, Options options) {
        CancellationScope.check();
        InputRules.checkFrames(allFrames);

        ExecutionSession.current().configureForWork(allFrames.size(),
                (long) allFrames.get(0).pixelData.length * allFrames.get(0).pixelData[0].length);

        if (options.debug) {
            debug(options, "%s%n", "\n==================================================");
            debug(options, "%s%n", "      JTRANSIENT AUTO-TUNER INITIALIZED");
            debug(options, "%s%n", "==================================================");
            debug(options, "%s%n", "Total frames available: " + allFrames.size());
        }

        if (listener != null) {
            listener.onProgressUpdate(0, "Initializing Auto-Tuner...");
        }

        AutoTunePolicy policy = getPolicy(profile);

        AutoTunerResult result = new AutoTunerResult();
        result.effectiveOptions = options;
        StringBuilder report = new StringBuilder("=== JTransient Auto-Tuning Report ===\n");
        report.append("Profile: ").append(profile).append("\n");
        appendInputFrameOrderTrace(allFrames, report);
        appendInputConfigTrace(baseConfig, report);

        if (allFrames.size() < options.sampleSize) {
            report.append("Warning: Not enough frames for Auto-Tuning. Falling back to base config.\n");
            result.optimizedConfig = baseConfig;
            result.success = false;
            result.telemetryReport = report.toString();
            return result;
        }

        if (listener != null) {
            listener.onProgressUpdate(3, "Evaluating frame quality and selecting tuning sample...");
        }

        List<ImageFrame> tuningFrames = getBestSampleFrames(allFrames, baseConfig, report, options);

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

        List<CropRegion> cropRegions = buildTuningCropRegions(sensorWidth, sensorHeight, report, options);
        if (cropRegions.isEmpty()) {
            report.append("Failed to create valid tuning crops. Falling back to base config.\n");
            result.optimizedConfig = baseConfig;
            result.success = false;
            result.telemetryReport = report.toString();
            return result;
        }

        if (options.debug) {
            debug(options, "%s%n", "\n[ROI] Using " + cropRegions.size() + " crops for tuning.");
            for (CropRegion r : cropRegions) {
                CancellationScope.check();
                debug(options, "%s%n", "   -> " + r.label + ": x=" + r.x + ", y=" + r.y + ", w=" + r.width + ", h=" + r.height);
            }
        }

        // Pre-crop the selected tuning frames once, so the sweep does not repeatedly crop.
        List<List<CroppedFrame>> croppedFramesByRegion = new ArrayList<>();
        for (CropRegion region : cropRegions) {
            CancellationScope.check();
            croppedFramesByRegion.add(buildCroppedFramesForRegion(tuningFrames, region));
        }

        if (listener != null) {
            listener.onProgressUpdate(7, "Calibrating maxStarJitter from sampled stars...");
        }

        double measuredMaxStarJitter = measureMaxStarJitter(croppedFramesByRegion, baseConfig, report, options);

        if (options.debug) {
            debug(options, "%n[JITTER] Calibrated maxStarJitter before the sweep: %.2f px%n", measuredMaxStarJitter);
        }

        // =====================================================================
        // PHASE 1: THRESHOLD / GROW / VETO SWEEP
        // =====================================================================
        if (options.debug) debug(options, "%s%n", "\n[START] Phase 1: Detection Threshold Sweep on cropped regions...");
        report.append("\n--- PHASE 1: Detection Threshold Sweep (Cropped ROIs) ---\n");
        appendSweepBaselineTrace(baseConfig, measuredMaxStarJitter, report, options);

        DetectionConfig bestConfig = null;
        List<SweepCandidateTelemetry> acceptedCandidates = new ArrayList<>();

        int bestStableStars = -1;
        double bestTransientRatio = 1.0;
        double bestScore = -Double.MAX_VALUE;

        int totalCombinations =
                options.minPixelsToTest.length
                        * options.sigmasToTest.length
                        * options.growDeltasToTest.length
                        * options.maskOverlapToTest.length;

        int currentCombination = 0;

        for (int minPix : options.minPixelsToTest) {
            CancellationScope.check();
            for (double sigma : options.sigmasToTest) {
                CancellationScope.check();
                for (double growDelta : options.growDeltasToTest) {
                    for (double overlapFraction : options.maskOverlapToTest) {

                        currentCombination++;
                        double growSigma = deriveSweepGrowSigma(sigma, growDelta, baseConfig, options);
                        if (growSigma >= sigma) {
                            if (options.debug) {
                                debug(options, "%s%n", " -> Skipping invalid hysteresis candidate: Sigma " + sigma
                                        + " | GrowSigma floor " + growSigma
                                        + " would meet/exceed the seed threshold.");
                            }
                            continue;
                        }

                        if (listener != null) {
                            int progress = 8 + (int) (((double) currentCombination / totalCombinations) * 82.0);
                            listener.onProgressUpdate(
                                    progress,
                                    String.format("Testing σ=%.1f, grow=%.2f, minPix=%d, overlap=%.2f",
                                            sigma, growSigma, minPix, overlapFraction)
                            );
                        }

                        if (options.debug) {
                            debug(options, "%s%n", "\n--------------------------------------------------");
                            debug(options, "%s%n", " -> Testing Sigma: " + sigma
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
                            testConfig.growSigmaMultiplier = masterSigma;
                            List<SourceExtractor.DetectedObject> masterStars =
                                    extractMasterStars(masterStackData, masterSigma, masterMinPix, testConfig);
                            testConfig.growSigmaMultiplier = originalGrowSigma;

                            if (masterStars == null || masterStars.isEmpty()) {
                                if (options.debug) {
                                    debug(options, "%s%n", "      [REJECTED] Crop " + cropRegion.label + " master extraction found no stars.");
                                }
                                rejected = true;
                                break;
                            }

                            // STEP B: Build veto mask for this crop
                            boolean[][] masterMask = new boolean[cropHeight][cropWidth];
                            // The same growth as the pipeline's star mask (MasterVetoMask).
                            int dilationRadius = MasterVetoMask.dilationRadius(testConfig.maxStarJitter);
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
                            if (maskCoverageThisCrop > options.hardMaskCoverageReject) {
                                if (options.debug) {
                                    debug(options, "      [REJECTED] Crop %s mask coverage too high: %.2f%%%n",
                                            cropRegion.label, maskCoverageThisCrop * 100.0);
                                }
                                rejected = true;
                                break;
                            }

                            // STEP C: Extract each cropped frame and test against this crop mask
                            for (CroppedFrame frame : cropFrames) {
                                List<SourceExtractor.DetectedObject> objects = extractInCrop(
                                        frame.pixelData,
                                        testConfig.detectionSigmaMultiplier,
                                        testConfig.minDetectionPixels,
                                        testConfig
                                );

                                totalObjectsExtracted += objects.size();

                                int stableThisFrame = 0;
                                int transientThisFrame = 0;

                                for (SourceExtractor.DetectedObject obj : objects) {
                                    // Streaks are not counted: a real trail in a sample frame must not count as noise.
                                    // (The pipeline vetoes streaks against the same star mask as point sources.)
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

                        if (totalObjectsExtracted > options.maxTotalExtractedObjects
                                || totalObjectsExtracted < options.minTotalExtractedObjects) {
                            if (options.debug) {
                                debug(options, "%s%n", "      [REJECTED] Extracted " + totalObjectsExtracted + " total objects (Out of bounds).");
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

                        if (options.debug) {
                            debug(options, "%s%n", String.format(
                                    "      [RESULT] StableStars: %d | Transients: %d | Ratio: %.2f%% | AggregatedMaskCoverage: %.2f%%",
                                    totalStableStars, totalTransients, transientRatio * 100.0, aggregatedMaskCoverage * 100.0));
                        }

                        boolean isClean = (transientRatio <= policy.maxTransientRatio)
                                && (totalStableStars >= options.minStableStars);

                        if (!isClean) {
                            if (options.debug) debug(options, "%s%n", "      [REJECTED] Failed noise ratio or stable star requirements.");
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

                        double stableYieldScore = clamp01(avgStableStars / options.optimalStarCount);

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
                                0.6 * clamp01(stableCv / options.stableStarsCvAnchor) +
                                        0.4 * clamp01(transientCv / options.transientsCvAnchor);

                        double maskCoveragePenalty = 0.0;
                        if (aggregatedMaskCoverage > options.softMaskCoverageStart) {
                            maskCoveragePenalty = clamp01(
                                    (aggregatedMaskCoverage - options.softMaskCoverageStart)
                                            / (options.hardMaskCoverageReject - options.softMaskCoverageStart)
                            );
                        }

                        double sigmaHarshness = normalize(sigma, options.sigmasToTest[0], options.sigmasToTest[options.sigmasToTest.length - 1]);
                        double minPixHarshness = normalize(minPix, options.minPixelsToTest[0], options.minPixelsToTest[options.minPixelsToTest.length - 1]);
                        double growHarshness = normalize(growDelta, options.growDeltasToTest[0], options.growDeltasToTest[options.growDeltasToTest.length - 1]);

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

                        if (options.debug) {
                            debug(options,
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

                        if (isBetterSweepCandidate(score, sigma, bestScore, bestConfig, profile, options)) {
                            if (options.debug) debug(options, "%s%n", "      *** NEW BEST CONFIGURATION FOUND ***");

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
                    validateFinalConfig(cropRegions, croppedFramesByRegion, bestConfig, policy, options);
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

            if (options.debug) debug(options, "%s%n", "\n[SUCCESS] Auto-Tuning Complete.");

        } else {
            if (listener != null) {
                listener.onProgressUpdate(100, "Auto-Tuning Failed. Using base config.");
            }
            if (options.debug) {
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
     * Objects of a crop, kept up to {@link DataArea#MARGIN} px from its edge. The crops lie well inside the frame, so
     * no edge band or darkness test is needed.
     */
    private static List<SourceExtractor.DetectedObject> extractInCrop(short[][] image, double sigma, int minPixels,
                                                                      DetectionConfig config) {
        return SourceExtractor.extractSources(image, sigma, minPixels, config, null,
                DataArea.fullFrame(image[0].length, image.length)).objects;
    }

    /** Stars of a crop's master stack, found as the pipeline finds its master stars. */
    private static List<SourceExtractor.DetectedObject> extractMasterStars(short[][] master, double sigma, int minPixels,
                                                                           DetectionConfig config) {
        return MasterReferenceAnalyzer.extractStars(master, sigma, minPixels, config);
    }

    /**
     * Builds the set of representative interior crops used during tuning.
     */
    private static List<CropRegion> buildTuningCropRegions(int sensorWidth, int sensorHeight, StringBuilder report, Options options) {
        List<CropRegion> regions = new ArrayList<>();

        int usableWidth = sensorWidth - (2 * options.borderMargin);
        int usableHeight = sensorHeight - (2 * options.borderMargin);

        if (usableWidth <= 0 || usableHeight <= 0) {
            report.append("Frame is too small to honor the requested crop border margin.\n");
            return regions;
        }

        int cropSize;
        if (usableWidth >= options.preferredCropSizeLarge && usableHeight >= options.preferredCropSizeLarge) {
            cropSize = options.preferredCropSizeLarge;
        } else if (usableWidth >= options.preferredCropSizeSmall && usableHeight >= options.preferredCropSizeSmall) {
            cropSize = options.preferredCropSizeSmall;
        } else {
            cropSize = Math.min(usableWidth, usableHeight);
        }

        if (cropSize < options.minCropSize) {
            report.append(String.format(
                    "Computed crop size %d is too small for reliable auto-tuning (minimum %d).%n",
                    cropSize, options.minCropSize));
            return regions;
        }

        int minX = options.borderMargin;
        int minY = options.borderMargin;
        int maxX = sensorWidth - options.borderMargin - cropSize;
        int maxY = sensorHeight - options.borderMargin - cropSize;

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

        if (options.cropCount < regions.size()) {
            return regions.subList(0, options.cropCount);
        }

        report.append(String.format(
                "Using %d tuning crops of size %dx%d with border margin >= %d px where possible:%n",
                regions.size(), cropSize, cropSize, options.borderMargin));
        for (CropRegion r : regions) {
            CancellationScope.check();
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
            CancellationScope.check();
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
            CancellationScope.check();
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
        int[] buffer = new int[depth];
        int medianIndex = depth / 2;

        for (int y = 0; y < height; y++) {
            CancellationScope.check();
            for (int x = 0; x < width; x++) {
                CancellationScope.check();
                for (int i = 0; i < depth; i++) {
                    buffer[i] = PixelEncoding.toShiftedPositiveInt(frames.get(i).pixelData[y][x]);
                }
                // sample size is tiny (5), so Collections/streams are unnecessary here
                insertionSort(buffer);
                out[y][x] = PixelEncoding.fromShiftedPositiveInt(buffer[medianIndex]);
            }
        }

        return out;
    }

    /**
     * Sorts a tiny short array in place using insertion sort.
     */
    private static void insertionSort(int[] arr) {
        for (int i = 1; i < arr.length; i++) {
            CancellationScope.check();
            int key = arr[i];
            int j = i - 1;
            while (j >= 0 && arr[j] > key) {
                CancellationScope.check();
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
                                                        StringBuilder report, Options options) {
        List<FrameQualityRecord> records = new ArrayList<>();
        int totalFrames = allFrames.size();
        DetectionConfig samplingConfig = config.clone();
        appendSamplingConfigTrace(config, samplingConfig, report);

        for (int i = 0; i < totalFrames; i++) {
            CancellationScope.check();
            ImageFrame frame = allFrames.get(i);

            if (options.debug) {
                debug(options, "%s%n", "   -> Evaluating Frame Quality: " + (i + 1) + " of " + totalFrames
                        + " (Index: " + frame.sequenceIndex + ")...");
            }

            FrameQualityAnalyzer.FrameMetrics metrics = FrameQualityAnalyzer.evaluateFrame(frame.pixelData, samplingConfig,
                    DataArea.ofFrame(frame.pixelData));
            double score = metrics.backgroundNoise * metrics.medianFWHM;
            records.add(new FrameQualityRecord(frame, metrics, score));
        }

        records.sort(Comparator.comparingDouble(r -> r.qualityScore));

        List<ImageFrame> selected = new ArrayList<>();
        List<Integer> usedIndices = new ArrayList<>();

        int target = Math.min(options.sampleSize, records.size());
        int eliteCount = Math.min(3, target);

        report.append(String.format("Selected %d tuning frames (best + representative median quality):%n", target));

        for (int i = 0; i < eliteCount; i++) {
            CancellationScope.check();
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
                CancellationScope.check();
                if (selected.size() >= target) break;
                addSampleFrame(records, idx, usedIndices, selected, report);
            }
        }

        for (int i = 0; i < records.size() && selected.size() < target; i++) {
            CancellationScope.check();
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
                " -> Frame %d: Noise=%.2f, FWHM=%s, Stars=%d, ShapeStars=%d, BrightShapeStars=%d, FwhmStars=%d, Ecc=%s, BrightEcc=%s (Score: %.2f)%n",
                rec.frame.sequenceIndex,
                rec.metrics.backgroundNoise,
                formatMetric(rec.metrics.medianFWHM),
                rec.metrics.starCount,
                rec.metrics.usableShapeStarCount,
                rec.metrics.brightStarShapeStarCount,
                rec.metrics.fwhmStarCount,
                formatMetric(rec.metrics.medianEccentricity),
                formatMetric(rec.metrics.brightStarMedianEccentricity),
                rec.qualityScore));
    }

    /**
     * Formats optional quality metrics for the tuning report.
     */
    private static String formatMetric(double value) {
        return Double.isFinite(value) ? String.format("%.2f", value) : "n/a";
    }

    /**
     * Records the incoming base configuration so repeated tuning runs reveal
     * which fields were carried forward from a previous optimized result.
     */
    private static void appendInputConfigTrace(DetectionConfig baseConfig, StringBuilder report) {
        DetectionConfig defaults = new DetectionConfig();
        List<String> samplingDeltas = new ArrayList<>();
        List<String> carriedButIgnored = new ArrayList<>();

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
                "Frame-Sampling Inputs -> QualitySigma: %.2f | QualityGrowSigma: %.2f | QualityMinPix: %d | QualityMaxElongationForFwhm: %.2f | BgClipIter: %d | BgClipFactor: %.2f | StreakElong: %.2f | StreakMinPix: %d%n",
                baseConfig.qualitySigmaMultiplier,
                baseConfig.qualityGrowSigmaMultiplier,
                baseConfig.qualityMinDetectionPixels,
                baseConfig.qualityMaxElongationForFwhm,
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
            CancellationScope.check();
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
                "Effective Frame-Sampling Config -> QualitySigma: %.2f | QualityGrowSigma: %.2f | QualityMinPix: %d | QualityMaxElongationForFwhm: %.2f | BgClipIter: %d | BgClipFactor: %.2f | StreakElong: %.2f | StreakMinPix: %d%n",
                samplingConfig.qualitySigmaMultiplier,
                samplingConfig.qualityGrowSigmaMultiplier,
                samplingConfig.qualityMinDetectionPixels,
                samplingConfig.qualityMaxElongationForFwhm,
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
                                                 StringBuilder report, Options options) {
        DetectionConfig defaults = new DetectionConfig();
        List<String> deltas = new ArrayList<>();

        appendIntDelta(deltas, "bgClippingIterations", baseConfig.bgClippingIterations, defaults.bgClippingIterations);
        appendDoubleDelta(deltas, "bgClippingFactor", baseConfig.bgClippingFactor, defaults.bgClippingFactor);
        appendDoubleDelta(deltas, "streakMinElongation", baseConfig.streakMinElongation, defaults.streakMinElongation);
        appendIntDelta(deltas, "streakMinPixels", baseConfig.streakMinPixels, defaults.streakMinPixels);
        appendDoubleDelta(deltas, "masterSigma", baseConfig.masterSigmaMultiplier, defaults.masterSigmaMultiplier);
        appendIntDelta(deltas, "masterMinPix", baseConfig.masterMinDetectionPixels, defaults.masterMinDetectionPixels);

        report.append(String.format(
                "Phase 1 Baseline -> BgClipIter: %d | BgClipFactor: %.2f | StreakElong: %.2f | StreakMinPix: %d | MasterSigma: %.2f | MasterMinPix: %d | MinGrowSigmaFloor: %.2f | CalibratedJitter: %.2f%n",
                baseConfig.bgClippingIterations,
                baseConfig.bgClippingFactor,
                baseConfig.streakMinElongation,
                baseConfig.streakMinPixels,
                baseConfig.masterSigmaMultiplier,
                baseConfig.masterMinDetectionPixels,
                getMinimumSweepGrowSigma(baseConfig, options),
                measuredMaxStarJitter
        ));
        report.append("Phase 1 inherited deltas from defaults: ")
                .append(deltas.isEmpty() ? "none" : String.join(", ", deltas))
                .append('\n');
        report.append(String.format(
                "Phase 1 per-test overrides -> DetectSigma grid, GrowSigma grid with floor MasterSigma+%.2f, MinPix grid, Overlap grid, MaxStarJitter=%.2f; quality-side sampling thresholds are fixed from the input config%n",
                options.minGrowSigmaAboveMasterSigma,
                measuredMaxStarJitter
        ));
    }

    /**
     * Derives the effective grow sigma tested during Phase 1 while keeping hysteresis
     * safely above the master-map grow level.
     */
    private static double deriveSweepGrowSigma(double sigma, double growDelta, DetectionConfig baseConfig, Options options) {
        double growSigma = Math.max(1.0, sigma - growDelta);
        return Math.max(growSigma, getMinimumSweepGrowSigma(baseConfig, options));
    }

    /**
     * Returns the minimum grow sigma allowed during the detection sweep.
     */
    private static double getMinimumSweepGrowSigma(DetectionConfig baseConfig, Options options) {
        return Math.max(1.0, baseConfig.masterSigmaMultiplier + options.minGrowSigmaAboveMasterSigma);
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
            CancellationScope.check();
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
                CancellationScope.check();
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
                                                                                AutoTunePolicy policy, Options options) {
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
            CancellationScope.check();
            CropRegion cropRegion = cropRegions.get(cropIndex);
            List<CroppedFrame> cropFrames = croppedFramesByRegion.get(cropIndex);

            int cropHeight = cropRegion.height;
            int cropWidth = cropRegion.width;
            totalMaskArea += cropWidth * cropHeight;

            short[][] masterStackData = createMedianMasterStackForCrops(cropFrames);

            DetectionConfig evalConfig = finalConfig.clone();
            double originalGrowSigma = evalConfig.growSigmaMultiplier;
            evalConfig.growSigmaMultiplier = evalConfig.masterSigmaMultiplier;
            List<SourceExtractor.DetectedObject> masterStars = extractMasterStars(
                    masterStackData,
                    evalConfig.masterSigmaMultiplier,
                    evalConfig.masterMinDetectionPixels,
                    evalConfig);
            evalConfig.growSigmaMultiplier = originalGrowSigma;

            if (masterStars == null || masterStars.isEmpty()) {
                telemetry.completed = false;
                telemetry.failedCropLabel = cropRegion.label;
                telemetry.statusMessage = "Final validation failed: no master stars extracted in crop " + cropRegion.label + ".";
                return telemetry;
            }

            boolean[][] masterMask = new boolean[cropHeight][cropWidth];
            int dilationRadius = MasterVetoMask.dilationRadius(evalConfig.maxStarJitter);
            int maskTrueCountThisCrop = 0;

            for (SourceExtractor.DetectedObject mStar : masterStars) {
                CancellationScope.check();
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
            if (maskCoverageThisCrop > options.hardMaskCoverageReject) {
                telemetry.completed = false;
                telemetry.failedCropLabel = cropRegion.label;
                telemetry.statusMessage = String.format(
                        "Final validation failed: crop %s mask coverage %.2f%% exceeds %.2f%%.",
                        cropRegion.label,
                        maskCoverageThisCrop * 100.0,
                        options.hardMaskCoverageReject * 100.0
                );
                return telemetry;
            }

            for (CroppedFrame frame : cropFrames) {
                CancellationScope.check();
                List<SourceExtractor.DetectedObject> objects = extractInCrop(
                        frame.pixelData,
                        evalConfig.detectionSigmaMultiplier,
                        evalConfig.minDetectionPixels,
                        evalConfig
                );

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
                totalObjectsExtracted <= options.maxTotalExtractedObjects
                        && totalObjectsExtracted >= options.minTotalExtractedObjects;
        telemetry.transientRatio = totalObjectsExtracted == 0
                ? 1.0
                : (double) totalTransients / totalObjectsExtracted;
        telemetry.transientRatioWithinLimit = telemetry.transientRatio <= policy.maxTransientRatio;
        telemetry.stableStarsWithinLimit = totalStableStars >= options.minStableStars;
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
            CancellationScope.check();
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
            CancellationScope.check();
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
                CancellationScope.check();
                if (i > 0) out.append(", ");
                out.append(allFrames.get(i).sequenceIndex);
            }
        } else {
            for (int i = 0; i < 5; i++) {
                CancellationScope.check();
                if (i > 0) out.append(", ");
                out.append(allFrames.get(i).sequenceIndex);
            }
            out.append(", ... ,");
            for (int i = allFrames.size() - 3; i < allFrames.size(); i++) {
                CancellationScope.check();
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
     * Compares two sweep candidates while allowing the high (and maximum) profile to prefer
     * lower sigma when scores are effectively tied.
     */
    private static boolean isBetterSweepCandidate(double candidateScore,
                                                  double candidateSigma,
                                                  double bestScore,
                                                  DetectionConfig bestConfig,
                                                  AutoTuneProfile profile, Options options) {
        if (bestConfig == null) {
            return true;
        }

        if (candidateScore > bestScore) {
            return true;
        }

        if (profile != AutoTuneProfile.HIGH && profile != AutoTuneProfile.MAXIMUM) {
            return false;
        }

        if ((bestScore - candidateScore) > options.aggressiveLowerSigmaScoreWindow) {
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
            case LOW:
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

            case HIGH:
            case MAXIMUM:
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
                        34.0,
                        0.90, // harshnessSigmaWeight -> aggressively prefer lower sigma
                        0.00, // harshnessMinPixWeight -> do not reward tiny minPixels
                        0.15, // harshnessGrowDeltaWeight
                        22.0, // lowSigmaMinPixGuardWeight -> still protect against unsafe sigma/minPix pairs, but less harshly
                        4.0,  // lowSigmaMinPixPivot
                        3.0   // lowSigmaMinPixSlope
                );

            case MEDIUM:
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
                                               StringBuilder report, Options options) {
        List<Double> distances = new ArrayList<>();
        int usableReferenceStars = 0;

        DetectionConfig probeConfig = baseConfig.clone();
        probeConfig.detectionSigmaMultiplier = options.initialJitterProbeSigma;
        probeConfig.growSigmaMultiplier = options.initialJitterProbeGrowSigma;
        probeConfig.minDetectionPixels = options.initialJitterProbeMinPixels;

        for (int cropIndex = 0; cropIndex < croppedFramesByRegion.size(); cropIndex++) {
            CancellationScope.check();
            List<CroppedFrame> cropFrames = croppedFramesByRegion.get(cropIndex);
            if (cropFrames.isEmpty()) {
                continue;
            }

            short[][] masterStackData = createMedianMasterStackForCrops(cropFrames);

            DetectionConfig masterConfig = probeConfig.clone();
            masterConfig.growSigmaMultiplier = masterConfig.masterSigmaMultiplier;

            List<SourceExtractor.DetectedObject> masterStars = extractMasterStars(
                    masterStackData,
                    masterConfig.masterSigmaMultiplier,
                    masterConfig.masterMinDetectionPixels,
                    masterConfig);

            if (masterStars == null || masterStars.isEmpty()) {
                continue;
            }

            List<List<SourceExtractor.DetectedObject>> frameObjectsByCrop = new ArrayList<>(cropFrames.size());
            for (CroppedFrame frame : cropFrames) {
                CancellationScope.check();
                frameObjectsByCrop.add(extractInCrop(
                        frame.pixelData,
                        probeConfig.detectionSigmaMultiplier,
                        probeConfig.minDetectionPixels,
                        probeConfig));
            }

            for (SourceExtractor.DetectedObject masterStar : masterStars) {
                CancellationScope.check();
                if (!isUsableForJitterCalibration(masterStar, options)) {
                    continue;
                }

                usableReferenceStars++;

                for (List<SourceExtractor.DetectedObject> frameObjects : frameObjectsByCrop) {
                    SourceExtractor.DetectedObject frameStar = findClosestUsableJitterMatch(masterStar, frameObjects, options);
                    if (frameStar == null) {
                        continue;
                    }

                    double dist = distance(masterStar, frameStar);
                    if (dist > options.searchRadiusPx) {
                        continue;
                    }

                    // Require reciprocal nearest-neighbor matching back to the reference stack.
                    SourceExtractor.DetectedObject reciprocal = findClosestUsableJitterMatch(frameStar, masterStars, options);
                    if (reciprocal != masterStar) {
                        continue;
                    }

                    distances.add(dist);
                }
            }
        }

        if (distances.size() < options.minInitialJitterMatches) {
            double fallback = Math.max(options.minJitterFloor, options.initialJitterFallbackPx);

            if (options.debug) {
                debug(options,
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
        double pJitter = getPercentileFromSorted(distances, options.jitterPercentile);

        double estimated = Math.max(options.minJitterFloor, pJitter * options.jitterSafetyMultiplier);

        if (options.debug) {
            debug(options,
                    "[JITTER] Calibrated maxStarJitter from %d matches across %d reference stars: P%.0f = %.2f px -> %.2f px%n",
                    distances.size(), usableReferenceStars, options.jitterPercentile * 100.0, pJitter, estimated
            );
        }

        report.append(String.format(
                "Calibrated maxStarJitter from %d matches across %d reference stars: P%.0f = %.2f px -> %.2f px%n",
                distances.size(), usableReferenceStars, options.jitterPercentile * 100.0, pJitter, estimated
        ));

        return estimated;
    }

    /**
     * Returns whether an extracted object is reliable enough to use for jitter calibration.
     */
    private static boolean isUsableForJitterCalibration(SourceExtractor.DetectedObject obj, Options options) {
        if (obj == null) {
            return false;
        }

        if (obj.rawPixels == null || obj.rawPixels.size() < options.initialJitterProbeMinPixels) {
            return false;
        }

        return obj.elongation <= options.initialJitterProbeMaxElongation;
    }

    /**
     * Finds the closest usable jitter-calibration match for a source object.
     */
    private static SourceExtractor.DetectedObject findClosestUsableJitterMatch(SourceExtractor.DetectedObject source,
                                                                               List<SourceExtractor.DetectedObject> candidates, Options options) {
        SourceExtractor.DetectedObject best = null;
        double bestDist = Double.MAX_VALUE;

        for (SourceExtractor.DetectedObject candidate : candidates) {
            CancellationScope.check();
            if (!isUsableForJitterCalibration(candidate, options)) {
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
