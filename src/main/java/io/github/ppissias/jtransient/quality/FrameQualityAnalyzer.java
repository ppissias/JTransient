/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 * author: Petros Pissias <petrospis at gmail.com>
 *
 */
package io.github.ppissias.jtransient.quality;

import io.github.ppissias.jtransient.config.DetectionConfig;
import io.github.ppissias.jtransient.core.SourceExtractor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Measures per-frame quality metrics used for session-level outlier rejection.
 */
public class FrameQualityAnalyzer {

    // =================================================================
    // DATA MODELS
    // =================================================================

    /**
     * Summary metrics derived from one frame.
     */
    public static class FrameMetrics {
        /** Median sky background level. */
        public double backgroundMedian;
        /** Estimated background noise sigma. */
        public double backgroundNoise;
        /** Median full width at half maximum of usable stars. */
        public double medianFWHM;
        /** Median elongation of usable stars. */
        public double medianEccentricity;
        /** Number of extracted quality-reference stars. */
        public int starCount;
        /** Number of non-noise, non-streak stars used for shape statistics. */
        public int usableShapeStarCount;
        /** Number of stars that survived the FWHM elongation gate. */
        public int fwhmStarCount;
        /** Whether the frame was later rejected by the session evaluator. */
        public boolean isRejected = false;
        /** Human-readable rejection cause. */
        public String rejectionReason = "OK";
        /** Frame label copied from the source image. */
        public String filename;
    }

    // =================================================================
    // EVALUATION LOGIC
    // =================================================================

    /**
     * Extracts the quality metrics used by {@link SessionEvaluator}.
     *
     * @param imageData frame pixels to analyze
     * @param config pipeline configuration supplying quality thresholds
     * @return measured frame metrics
     */
    public static FrameMetrics evaluateFrame(short[][] imageData, DetectionConfig config) {
        FrameMetrics metrics = new FrameMetrics();
        DetectionConfig qualityConfig = config.clone();
        qualityConfig.growSigmaMultiplier = config.qualityGrowSigmaMultiplier;

        // 1. Extract sources using the strict quality-evaluation parameters from the config
        List<SourceExtractor.DetectedObject> objects =
                SourceExtractor.extractSources(
                        imageData,
                        config.qualitySigmaMultiplier,
                        config.qualityMinDetectionPixels,
                        qualityConfig
                ).objects;

        // 2. Calculate the background using the same strict parameter
        SourceExtractor.BackgroundMetrics bg = SourceExtractor.calculateBackgroundSigmaClipped(
                imageData, imageData[0].length, imageData.length, config.qualitySigmaMultiplier, config);

        metrics.backgroundMedian = bg.median;
        metrics.backgroundNoise = bg.sigma;
        metrics.starCount = objects.size();

        // 3. One-pass extraction for both FWHM and Eccentricity
        List<Double> fwhmValues = new ArrayList<>(objects.size());
        List<Double> eccValues = new ArrayList<>(objects.size());
        int usableShapeStarCount = 0;
        int fwhmStarCount = 0;

        for (SourceExtractor.DetectedObject obj : objects) {
            // Only measure true point sources (ignore obvious noise and massive satellite streaks)
            if (!obj.isStreak && !obj.isNoise) {
                usableShapeStarCount++;
                eccValues.add(obj.elongation);

                // Parameterized check: Ignore heavily distorted/trailed stars when judging pure optical focus
                if (obj.elongation < config.qualityMaxElongationForFwhm) {
                    fwhmStarCount++;
                    fwhmValues.add(obj.fwhm);
                }
            }
        }

        metrics.usableShapeStarCount = usableShapeStarCount;
        metrics.fwhmStarCount = fwhmStarCount;

        // 4. Calculate Medians (THE BUG FIX: Eccentricity is now populated!)
        metrics.medianFWHM = calculateMedian(fwhmValues, config.errorFallbackValue);
        metrics.medianEccentricity = calculateMedian(eccValues, config.errorFallbackValue);

        return metrics;
    }

    /**
     * Calculates the median of the supplied values or a configured fallback when empty.
     */
    private static double calculateMedian(List<Double> values, double fallbackValue) {
        if (values.isEmpty()) {
            return fallbackValue;
        }

        Collections.sort(values);
        int middle = values.size() / 2;

        if (values.size() % 2 == 1) {
            return values.get(middle);
        } else {
            // Average the two middle values for an even-sized list
            return (values.get(middle - 1) + values.get(middle)) / 2.0;
        }
    }
}
