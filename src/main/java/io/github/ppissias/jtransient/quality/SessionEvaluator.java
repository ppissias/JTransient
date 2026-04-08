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
import io.github.ppissias.jtransient.engine.JTransientEngine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Applies session-wide robust statistics to reject frames that are clear outliers.
 */
public class SessionEvaluator {

    // =================================================================
    // CORE EVALUATION LOGIC
    // =================================================================

    /**
     * Flags frames whose star count, focus, elongation, or background deviate too far from the session baseline.
     *
     * @param sessionMetrics per-frame metrics in chronological order
     * @param config configuration containing the rejection thresholds
     */
    public static void rejectOutlierFrames(List<FrameQualityAnalyzer.FrameMetrics> sessionMetrics, DetectionConfig config) {
        // Parameterized minimum frames check
        if (sessionMetrics.size() < config.minFramesForAnalysis) return;

        // 1. Extract the raw numbers into lists
        int count = sessionMetrics.size();
        List<Double> fwhmValues = new ArrayList<>(count);
        List<Double> bgValues = new ArrayList<>(count);
        List<Double> starCounts = new ArrayList<>(count);
        List<Double> eccValues = new ArrayList<>(count);
        List<Double> brightEccValues = new ArrayList<>(count);

        for (FrameQualityAnalyzer.FrameMetrics m : sessionMetrics) {
            fwhmValues.add(m.medianFWHM);
            bgValues.add(m.backgroundMedian);
            starCounts.add((double) m.starCount);
            eccValues.add(m.medianEccentricity);
            if (Double.isFinite(m.brightStarMedianEccentricity)) {
                brightEccValues.add(m.brightStarMedianEccentricity);
            }
        }

        // 2. Calculate the Session Medians and Sigma (MAD)
        // We pass the config down to the helper to access the zero fallback value
        double[] fwhmStats = calculateMedianAndSigma(fwhmValues, config);
        double[] bgStats = calculateMedianAndSigma(bgValues, config);
        double[] starStats = calculateMedianAndSigma(starCounts, config);
        double[] eccStats = calculateMedianAndSigma(eccValues, config);
        double[] brightEccStats = brightEccValues.size() >= config.minFramesForAnalysis
                ? calculateMedianAndSigma(brightEccValues, config)
                : null;

        if (JTransientEngine.DEBUG) {
            System.out.println(String.format(
                    "Session Baseline - FWHM: %.2f, Background: %.2f, Stars: %.0f, Eccentricity: %.2f, Bright-Star Eccentricity: %s",
                    fwhmStats[0],
                    bgStats[0],
                    starStats[0],
                    eccStats[0],
                    formatMetric(brightEccStats != null ? brightEccStats[0] : Double.NaN)
            ));
        }

        // 3. Evaluate each frame against the global session baseline
        for (int i = 0; i < sessionMetrics.size(); i++) {
            FrameQualityAnalyzer.FrameMetrics m = sessionMetrics.get(i);

            // Pre-calculate thresholds for cleaner logic and debugging
            double minStars = starStats[0] - (config.starCountSigmaDeviation * starStats[1]);
            double maxFwhm = fwhmStats[0] + (config.fwhmSigmaDeviation * fwhmStats[1]);
            double maxEcc = eccStats[0] + (config.eccentricitySigmaDeviation * eccStats[1]);
            double maxBgDev = config.backgroundSigmaDeviation * bgStats[1];
            double maxBrightEcc = brightEccStats != null
                    ? brightEccStats[0] + (config.brightStarEccentricitySigmaDeviation * brightEccStats[1])
                    : Double.POSITIVE_INFINITY;

            // --- THE FIX: ENFORCE ABSOLUTE MINIMUM DEVIATIONS ---
            // Don't let the background threshold drop below the configured minimum ADU
            if (maxBgDev < config.minBackgroundDeviationADU) {
                maxBgDev = config.minBackgroundDeviationADU;
            }

            // Don't let the eccentricity envelope shrink tighter than the configured minimum
            if (maxEcc - eccStats[0] < config.minEccentricityEnvelope) {
                maxEcc = eccStats[0] + config.minEccentricityEnvelope;
            }

            if (brightEccStats != null && maxBrightEcc - brightEccStats[0] < config.minBrightStarEccentricityEnvelope) {
                maxBrightEcc = brightEccStats[0] + config.minBrightStarEccentricityEnvelope;
            }

            // Don't let FWHM envelope shrink tighter than the configured minimum
            if (maxFwhm - fwhmStats[0] < config.minFwhmEnvelope) {
                maxFwhm = fwhmStats[0] + config.minFwhmEnvelope;
            }
            // ----------------------------------------------------

            // Stars: We only care if it drops too low (clouds).
            if (m.starCount < minStars) {
                if (JTransientEngine.DEBUG) {
                    System.out.printf("DEBUG REJECT [Frame %d]: Stars %.0f < Min Threshold %.2f (Median: %.2f, Sigma: %.4f)%n",
                            i, (double)m.starCount, minStars, starStats[0], starStats[1]);
                }
                reject(m, "Star Count dropped anomalously low");
                continue;
            }

            // FWHM: We only care if it gets too high (blurry/bad focus/wind).
            if (m.medianFWHM > maxFwhm) {
                if (JTransientEngine.DEBUG) {
                    System.out.printf("DEBUG REJECT [Frame %d]: FWHM %.3f > Max Threshold %.3f (Median: %.3f, Sigma: %.4f)%n",
                            i, m.medianFWHM, maxFwhm, fwhmStats[0], fwhmStats[1]);
                }
                reject(m, "FWHM spiked (Blurry image)");
                continue;
            }

            // Eccentricity: We only care if it gets too high (tracking error, mount bump, wind).
            if (m.medianEccentricity > maxEcc) {
                if (JTransientEngine.DEBUG) {
                    System.out.printf("DEBUG REJECT [Frame %d]: Eccentricity %.3f > Max Threshold %.3f (Median: %.3f, Sigma: %.4f)%n",
                            i, m.medianEccentricity, maxEcc, eccStats[0], eccStats[1]);
                }
                reject(m, "Eccentricity spiked (Tracking error/Wind)");
                continue;
            }

            if (config.enableBrightStarEccentricityFilter
                    && brightEccStats != null
                    && Double.isFinite(m.brightStarMedianEccentricity)
                    && m.brightStarMedianEccentricity > maxBrightEcc) {
                if (JTransientEngine.DEBUG) {
                    System.out.printf(
                            "DEBUG REJECT [Frame %d]: Bright-Star Eccentricity %.3f > Max Threshold %.3f (Median: %.3f, Sigma: %.4f)%n",
                            i,
                            m.brightStarMedianEccentricity,
                            maxBrightEcc,
                            brightEccStats[0],
                            brightEccStats[1]
                    );
                }
                reject(m, "Bright-star eccentricity spiked (Tracking error/Wind)");
                continue;
            }

            // Background: We care if it spikes (car headlights) or drops completely.
            double currentBgDev = Math.abs(m.backgroundMedian - bgStats[0]);
            if (currentBgDev > maxBgDev) {
                if (JTransientEngine.DEBUG) {
                    System.out.printf("DEBUG REJECT [Frame %d]: BG Deviation %.3f > Max Allowed %.3f (Median: %.3f, Sigma: %.4f)%n",
                            i, currentBgDev, maxBgDev, bgStats[0], bgStats[1]);
                }
                reject(m, "Background deviation (Clouds/Light leak)");
            }
        }
    }

    /**
     * Marks a frame metric record as rejected with the supplied reason.
     */
    private static void reject(FrameQualityAnalyzer.FrameMetrics m, String reason) {
        m.isRejected = true;
        m.rejectionReason = reason;
    }

    /**
     * Formats optional metrics for debug output.
     */
    private static String formatMetric(double value) {
        return Double.isFinite(value) ? String.format("%.2f", value) : "n/a";
    }

    /**
     * Calculates a robust median and sigma estimate using MAD scaling.
     *
     * @return a two-element array containing {@code [median, sigma]}
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
