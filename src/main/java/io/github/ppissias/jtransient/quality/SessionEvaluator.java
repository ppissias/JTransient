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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class SessionEvaluator {

    // =================================================================
    // CORE EVALUATION LOGIC
    // =================================================================

    public static void rejectOutlierFrames(List<FrameQualityAnalyzer.FrameMetrics> sessionMetrics, DetectionConfig config) {
        // Parameterized minimum frames check
        if (sessionMetrics.size() < config.minFramesForAnalysis) return;

        // 1. Extract the raw numbers into lists
        List<Double> fwhmValues = new ArrayList<>();
        List<Double> bgValues = new ArrayList<>();
        List<Double> starCounts = new ArrayList<>();
        List<Double> eccValues = new ArrayList<>();

        for (FrameQualityAnalyzer.FrameMetrics m : sessionMetrics) {
            fwhmValues.add(m.medianFWHM);
            bgValues.add(m.backgroundMedian);
            starCounts.add((double) m.starCount);
            eccValues.add(m.medianEccentricity);
        }

        // 2. Calculate the Session Medians and Sigma (MAD)
        // We pass the config down to the helper to access the zero fallback value
        double[] fwhmStats = calculateMedianAndSigma(fwhmValues, config);
        double[] bgStats = calculateMedianAndSigma(bgValues, config);
        double[] starStats = calculateMedianAndSigma(starCounts, config);
        double[] eccStats = calculateMedianAndSigma(eccValues, config);

        System.out.println(String.format(
                "Session Baseline - FWHM: %.2f, Background: %.2f, Stars: %.0f, Eccentricity: %.2f",
                fwhmStats[0], bgStats[0], starStats[0], eccStats[0]
        ));

        // 3. Evaluate each frame against the global session baseline
        for (int i = 0; i < sessionMetrics.size(); i++) {
            FrameQualityAnalyzer.FrameMetrics m = sessionMetrics.get(i);

            // Stars: We only care if it drops too low (clouds).
            if (m.starCount < starStats[0] - (config.starCountSigmaDeviation * starStats[1])) {
                reject(m, "Star Count dropped anomalously low");
                continue;
            }

            // FWHM: We only care if it gets too high (blurry/bad focus/wind).
            if (m.medianFWHM > fwhmStats[0] + (config.fwhmSigmaDeviation * fwhmStats[1])) {
                reject(m, "FWHM spiked (Blurry image)");
                continue;
            }

            // Eccentricity: We only care if it gets too high (tracking error, mount bump, wind).
            if (m.medianEccentricity > eccStats[0] + (config.eccentricitySigmaDeviation * eccStats[1])) {
                reject(m, "Eccentricity spiked (Tracking error/Wind)");
                continue;
            }

            // Background: We care if it spikes (car headlights) or drops completely.
            if (Math.abs(m.backgroundMedian - bgStats[0]) > (config.backgroundSigmaDeviation * bgStats[1])) {
                reject(m, "Background deviation (Clouds/Light leak)");
            }
        }
    }

    private static void reject(FrameQualityAnalyzer.FrameMetrics m, String reason) {
        m.isRejected = true;
        m.rejectionReason = reason;
    }

    /**
     * Helper to calculate Robust Statistics (Returns [Median, Sigma])
     */
    private static double[] calculateMedianAndSigma(List<Double> values, DetectionConfig config) {
        Collections.sort(values);
        double median = values.get(values.size() / 2);

        List<Double> deviations = new ArrayList<>();
        for (Double val : values) {
            deviations.add(Math.abs(val - median));
        }
        Collections.sort(deviations);
        double mad = deviations.get(deviations.size() / 2);

        // 1.4826 is the constant to convert Median Absolute Deviation (MAD) to standard deviation (Sigma)
        double sigma = 1.4826 * mad;

        // Prevent sigma from being 0 if all frames are identical using the config fallback
        if (sigma == 0.0) sigma = config.zeroSigmaFallback;

        return new double[]{median, sigma};
    }
}