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

import io.github.ppissias.jtransient.model.DetectedObject;
import io.github.ppissias.jtransient.execution.CancellationScope;
import io.github.ppissias.jtransient.config.DetectionConfig;
import io.github.ppissias.jtransient.core.DataArea;
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
        /** Median elongation of brighter usable stars, or NaN when too few are present. */
        public double brightStarMedianEccentricity = Double.NaN;
        /** Number of extracted quality-reference stars. */
        public int starCount;
        /** Number of non-noise, non-streak stars used for shape statistics. */
        public int usableShapeStarCount;
        /** Number of bright non-noise, non-streak stars used for the bright-star eccentricity metric. */
        public int brightStarShapeStarCount;
        /** Number of stars that survived the FWHM elongation gate. */
        public int fwhmStarCount;
        /** Whether the frame was later rejected by the session evaluator. */
        public boolean isRejected = false;
        /** Human-readable rejection cause. */
        public String rejectionReason = "OK";
        /** Frame label copied from the source image. */
        public String filename;
        /**
         * Positions {x0, y0, x1, y1, ...} of the frame's bright round stars (those of the bright-star shape metric
         * whose elongation passes the FWHM gate), brightest first and at most
         * {@link RegistrationCheck#MAX_STARS_PER_FRAME}, for the {@link RegistrationCheck}.
         */
        public float[] registrationStars = new float[0];
        /**
         * The frame's star width (Gaussian FWHM) in pixels, as {@link StarWidth} measures it on the frame's bright,
         * isolated, round stars, or NaN when fewer than {@link StarWidth#MIN_FRAME_STARS} could be measured. Unlike
         * {@link #medianFWHM}, it is the stars' core, not their second moments.
         */
        public double starWidthFwhm = Double.NaN;
        /** Stars {@link #starWidthFwhm} was measured on. */
        public int starWidthStars;
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
        return evaluateFrame(imageData, config, null);
    }

    /**
     * Extracts the quality metrics inside an area: the sky is measured there and only stars inside it are counted and
     * measured, so frames with different registration fill are compared on the same part of the sky.
     *
     * @param imageData frame pixels to analyze
     * @param config pipeline configuration supplying quality thresholds
     * @param area where every frame of the session has image data, or null for the whole frame
     * @return measured frame metrics
     */
    public static FrameMetrics evaluateFrame(short[][] imageData, DetectionConfig config, DataArea area) {
        FrameMetrics metrics = new FrameMetrics();
        DetectionConfig qualityConfig = config.clone();
        qualityConfig.growSigmaMultiplier = config.qualityGrowSigmaMultiplier;

        // 1. Extract sources using the strict quality-evaluation parameters from the config
        List<DetectedObject> objects =
                SourceExtractor.extractSources(
                        imageData,
                        config.qualitySigmaMultiplier,
                        config.qualityMinDetectionPixels,
                        qualityConfig,
                        area,
                        area != null && area.getPixelCount() > 0 ? area : null
                ).objects;

        // 2. Calculate the background using the same strict parameter
        SourceExtractor.BackgroundMetrics bg = SourceExtractor.calculateBackgroundSigmaClipped(
                imageData, imageData[0].length, imageData.length, config.qualitySigmaMultiplier, config, area);

        metrics.backgroundMedian = bg.median;
        metrics.backgroundNoise = bg.sigma;
        metrics.starCount = objects.size();

        // 3. One-pass extraction for both FWHM and Eccentricity
        List<Double> fwhmValues = new ArrayList<>(objects.size());
        List<Double> eccValues = new ArrayList<>(objects.size());
        List<Double> brightStarEccValues = new ArrayList<>(objects.size());
        int usableShapeStarCount = 0;
        int brightStarShapeStarCount = 0;
        int fwhmStarCount = 0;
        double brightStarPeakSigmaThreshold = Math.max(
                config.qualitySigmaMultiplier,
                config.qualitySigmaMultiplier + config.qualityBrightStarPeakSigmaOffset
        );

        List<DetectedObject> registrationStars = new ArrayList<>();
        for (DetectedObject obj : objects) {
            CancellationScope.check();
            // Only measure true point sources (ignore obvious noise and massive satellite streaks)
            if (!obj.isStreak && !obj.isNoise) {
                usableShapeStarCount++;
                eccValues.add(obj.elongation);
                if (obj.peakSigma >= brightStarPeakSigmaThreshold) {
                    brightStarShapeStarCount++;
                    brightStarEccValues.add(obj.elongation);
                    if (obj.elongation < config.qualityMaxElongationForFwhm) {
                        registrationStars.add(obj);
                    }
                }

                // Parameterized check: Ignore heavily distorted/trailed stars when judging pure optical focus
                if (obj.elongation < config.qualityMaxElongationForFwhm) {
                    fwhmStarCount++;
                    fwhmValues.add(obj.fwhm);
                }
            }
        }

        registrationStars.sort((a, b) -> Double.compare(b.peakSigma, a.peakSigma));
        int kept = Math.min(registrationStars.size(), RegistrationCheck.MAX_STARS_PER_FRAME);
        metrics.registrationStars = new float[2 * kept];
        for (int i = 0; i < kept; i++) {
            metrics.registrationStars[2 * i] = (float) registrationStars.get(i).x;
            metrics.registrationStars[2 * i + 1] = (float) registrationStars.get(i).y;
        }
        metrics.usableShapeStarCount = usableShapeStarCount;
        metrics.brightStarShapeStarCount = brightStarShapeStarCount;
        metrics.fwhmStarCount = fwhmStarCount;
        // The core width of the frame's bright isolated stars, as the calibrated Auto-Tune measures it; the sky level
        // is quality control's, which moves the half-peak level by a small fraction of these stars' peaks.
        List<Double> widths = new ArrayList<>();
        StarWidth.measure(imageData, objects, bg.median, widths, Integer.MAX_VALUE);
        metrics.starWidthStars = widths.size();
        metrics.starWidthFwhm = StarWidth.frameWidth(widths);

        // 4. Calculate Medians (THE BUG FIX: Eccentricity is now populated!)
        metrics.medianFWHM = calculateMedian(fwhmValues, config.errorFallbackValue);
        metrics.medianEccentricity = calculateMedian(eccValues, config.errorFallbackValue);
        if (brightStarShapeStarCount >= config.qualityBrightStarMinStars) {
            metrics.brightStarMedianEccentricity = calculateMedian(brightStarEccValues, config.errorFallbackValue);
        }

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
