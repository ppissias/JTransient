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
        // Note: Once we refactor SourceExtractor next, we might update this signature slightly!
        List<SourceExtractor.DetectedObject> objects =
                SourceExtractor.extractSources(imageData, config.qualitySigmaMultiplier, config.qualityMinDetectionPixels, config);

        // Calculate the background using the same strict parameter
        SourceExtractor.BackgroundMetrics bg = SourceExtractor.calculateBackgroundSigmaClipped(
                imageData, imageData[0].length, imageData.length, config.qualitySigmaMultiplier, config);

        metrics.backgroundMedian = bg.median;
        metrics.backgroundNoise = bg.sigma;
        metrics.starCount = objects.size();

        // 2. Calculate Median FWHM from ROUND stars only
        List<Double> fwhmValues = new ArrayList<>();
        for (SourceExtractor.DetectedObject obj : objects) {

            // Parameterized check: Ignore streaks and heavily distorted stars when judging focus
            if (!obj.isStreak && obj.elongation < config.maxElongationForFwhm) {
                fwhmValues.add(obj.fwhm);
            }
        }

        if (!fwhmValues.isEmpty()) {
            Collections.sort(fwhmValues);
            metrics.medianFWHM = fwhmValues.get(fwhmValues.size() / 2);
        } else {
            // Parameterized fallback: Terrible score if no round stars exist
            metrics.medianFWHM = config.errorFallbackValue;
        }

        return metrics;
    }

    /**
     * Calculates the median elongation of all valid point sources in a frame.
     * A perfect frame is ~1.0. A bumped/trailed frame will be > 1.5.
     */
    public static double calculateFrameEccentricity(List<SourceExtractor.DetectedObject> objectsInFrame, DetectionConfig config) {
        List<Double> elongations = new ArrayList<>();

        for (SourceExtractor.DetectedObject obj : objectsInFrame) {
            // Only measure true point sources (ignore obvious noise and massive satellite streaks)
            if (!obj.isNoise && !obj.isStreak) {
                elongations.add(obj.elongation);
            }
        }

        // Parameterized fallback: If the frame has no stars, it's definitely a bad frame (clouds!)
        if (elongations.isEmpty()) {
            return config.errorFallbackValue;
        }

        // Sort to find the median
        elongations.sort(Double::compareTo);

        int middle = elongations.size() / 2;
        if (elongations.size() % 2 == 1) {
            return elongations.get(middle);
        } else {
            return (elongations.get(middle - 1) + elongations.get(middle)) / 2.0;
        }
    }
}