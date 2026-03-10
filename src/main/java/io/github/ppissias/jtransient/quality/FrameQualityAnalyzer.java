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

public class FrameQualityAnalyzer {

    // =================================================================
    // DATA MODELS
    // =================================================================

    public static class FrameMetrics {
        public double backgroundMedian;
        public double backgroundNoise;
        public double medianFWHM;
        public double medianEccentricity;
        public int starCount;
        public boolean isRejected = false;
        public String rejectionReason = "OK";
        public String filename;
    }

    // =================================================================
    // EVALUATION LOGIC
    // =================================================================

    public static FrameMetrics evaluateFrame(short[][] imageData, DetectionConfig config) {
        FrameMetrics metrics = new FrameMetrics();

        // 1. Extract sources using the strict quality-evaluation parameters from the config
        List<SourceExtractor.DetectedObject> objects =
                SourceExtractor.extractSources(imageData, config.qualitySigmaMultiplier, config.qualityMinDetectionPixels, config);

        // 2. Calculate the background using the same strict parameter
        SourceExtractor.BackgroundMetrics bg = SourceExtractor.calculateBackgroundSigmaClipped(
                imageData, imageData[0].length, imageData.length, config.qualitySigmaMultiplier, config);

        metrics.backgroundMedian = bg.median;
        metrics.backgroundNoise = bg.sigma;
        metrics.starCount = objects.size();

        // 3. One-pass extraction for both FWHM and Eccentricity
        List<Double> fwhmValues = new ArrayList<>(objects.size());
        List<Double> eccValues = new ArrayList<>(objects.size());

        for (SourceExtractor.DetectedObject obj : objects) {
            // Only measure true point sources (ignore obvious noise and massive satellite streaks)
            if (!obj.isStreak && !obj.isNoise) {
                eccValues.add(obj.elongation);

                // Parameterized check: Ignore heavily distorted/trailed stars when judging pure optical focus
                if (obj.elongation < config.maxElongationForFwhm) {
                    fwhmValues.add(obj.fwhm);
                }
            }
        }

        // 4. Calculate Medians (THE BUG FIX: Eccentricity is now populated!)
        metrics.medianFWHM = calculateMedian(fwhmValues, config.errorFallbackValue);
        metrics.medianEccentricity = calculateMedian(eccValues, config.errorFallbackValue);

        return metrics;
    }

    /**
     * Helper to calculate a mathematically precise median.
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