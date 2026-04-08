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

    /**
     * Session-wide rejection thresholds derived from the robust baseline statistics.
     */
    public static class SessionThresholds {
        public boolean available;
        public double minAllowedStarCount = Double.NaN;
        public double maxAllowedFwhm = Double.NaN;
        public double maxAllowedEccentricity = Double.NaN;
        public double maxAllowedBrightStarEccentricity = Double.NaN;
        public double backgroundMedianBaseline = Double.NaN;
        public double maxAllowedBackgroundDeviation = Double.NaN;
        public double minAllowedBackgroundMedian = Double.NaN;
        public double maxAllowedBackgroundMedian = Double.NaN;
    }

    // =================================================================
    // CORE EVALUATION LOGIC
    // =================================================================

    /**
     * Flags frames whose star count, focus, elongation, or background deviate too far from the session baseline.
     *
     * @param sessionMetrics per-frame metrics in chronological order
     * @param config configuration containing the rejection thresholds
     * @return session-wide threshold values applied during rejection
     */
    public static SessionThresholds rejectOutlierFrames(List<FrameQualityAnalyzer.FrameMetrics> sessionMetrics, DetectionConfig config) {
        SessionThresholds thresholds = new SessionThresholds();

        // Parameterized minimum frames check
        if (sessionMetrics.size() < config.minFramesForAnalysis) return thresholds;

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

        thresholds.available = true;
        thresholds.minAllowedStarCount = starStats[0] - (config.starCountSigmaDeviation * starStats[1]);
        thresholds.maxAllowedFwhm = fwhmStats[0] + (config.fwhmSigmaDeviation * fwhmStats[1]);
        thresholds.maxAllowedEccentricity = eccStats[0] + (config.eccentricitySigmaDeviation * eccStats[1]);
        thresholds.backgroundMedianBaseline = bgStats[0];
        thresholds.maxAllowedBackgroundDeviation = config.backgroundSigmaDeviation * bgStats[1];
        thresholds.maxAllowedBrightStarEccentricity = config.enableBrightStarEccentricityFilter && brightEccStats != null
                ? brightEccStats[0] + (config.brightStarEccentricitySigmaDeviation * brightEccStats[1])
                : Double.NaN;

        // Don't let the background threshold drop below the configured minimum ADU.
        if (thresholds.maxAllowedBackgroundDeviation < config.minBackgroundDeviationADU) {
            thresholds.maxAllowedBackgroundDeviation = config.minBackgroundDeviationADU;
        }

        // Don't let the eccentricity envelope shrink tighter than the configured minimum.
        if (thresholds.maxAllowedEccentricity - eccStats[0] < config.minEccentricityEnvelope) {
            thresholds.maxAllowedEccentricity = eccStats[0] + config.minEccentricityEnvelope;
        }

        if (Double.isFinite(thresholds.maxAllowedBrightStarEccentricity)
                && thresholds.maxAllowedBrightStarEccentricity - brightEccStats[0] < config.minBrightStarEccentricityEnvelope) {
            thresholds.maxAllowedBrightStarEccentricity = brightEccStats[0] + config.minBrightStarEccentricityEnvelope;
        }

        // Don't let FWHM envelope shrink tighter than the configured minimum.
        if (thresholds.maxAllowedFwhm - fwhmStats[0] < config.minFwhmEnvelope) {
            thresholds.maxAllowedFwhm = fwhmStats[0] + config.minFwhmEnvelope;
        }

        thresholds.minAllowedBackgroundMedian = thresholds.backgroundMedianBaseline - thresholds.maxAllowedBackgroundDeviation;
        thresholds.maxAllowedBackgroundMedian = thresholds.backgroundMedianBaseline + thresholds.maxAllowedBackgroundDeviation;

        // 3. Evaluate each frame against the global session baseline
        for (int i = 0; i < sessionMetrics.size(); i++) {
            FrameQualityAnalyzer.FrameMetrics m = sessionMetrics.get(i);

            // Stars: We only care if it drops too low (clouds).
            if (m.starCount < thresholds.minAllowedStarCount) {
                if (JTransientEngine.DEBUG) {
                    System.out.printf("DEBUG REJECT [Frame %d]: Stars %.0f < Min Threshold %.2f (Median: %.2f, Sigma: %.4f)%n",
                            i, (double)m.starCount, thresholds.minAllowedStarCount, starStats[0], starStats[1]);
                }
                reject(m, "Star Count dropped anomalously low");
                continue;
            }

            // FWHM: We only care if it gets too high (blurry/bad focus/wind).
            if (m.medianFWHM > thresholds.maxAllowedFwhm) {
                if (JTransientEngine.DEBUG) {
                    System.out.printf("DEBUG REJECT [Frame %d]: FWHM %.3f > Max Threshold %.3f (Median: %.3f, Sigma: %.4f)%n",
                            i, m.medianFWHM, thresholds.maxAllowedFwhm, fwhmStats[0], fwhmStats[1]);
                }
                reject(m, "FWHM spiked (Blurry image)");
                continue;
            }

            // Eccentricity: We only care if it gets too high (tracking error, mount bump, wind).
            if (m.medianEccentricity > thresholds.maxAllowedEccentricity) {
                if (JTransientEngine.DEBUG) {
                    System.out.printf("DEBUG REJECT [Frame %d]: Eccentricity %.3f > Max Threshold %.3f (Median: %.3f, Sigma: %.4f)%n",
                            i, m.medianEccentricity, thresholds.maxAllowedEccentricity, eccStats[0], eccStats[1]);
                }
                reject(m, "Eccentricity spiked (Tracking error/Wind)");
                continue;
            }

            if (config.enableBrightStarEccentricityFilter
                    && Double.isFinite(thresholds.maxAllowedBrightStarEccentricity)
                    && Double.isFinite(m.brightStarMedianEccentricity)
                    && m.brightStarMedianEccentricity > thresholds.maxAllowedBrightStarEccentricity) {
                if (JTransientEngine.DEBUG) {
                    System.out.printf(
                            "DEBUG REJECT [Frame %d]: Bright-Star Eccentricity %.3f > Max Threshold %.3f (Median: %.3f, Sigma: %.4f)%n",
                            i,
                            m.brightStarMedianEccentricity,
                            thresholds.maxAllowedBrightStarEccentricity,
                            brightEccStats[0],
                            brightEccStats[1]
                    );
                }
                reject(m, "Bright-star eccentricity spiked (Tracking error/Wind)");
                continue;
            }

            // Background: We care if it spikes (car headlights) or drops completely.
            double currentBgDev = Math.abs(m.backgroundMedian - bgStats[0]);
            if (currentBgDev > thresholds.maxAllowedBackgroundDeviation) {
                if (JTransientEngine.DEBUG) {
                    System.out.printf("DEBUG REJECT [Frame %d]: BG Deviation %.3f > Max Allowed %.3f (Median: %.3f, Sigma: %.4f)%n",
                            i, currentBgDev, thresholds.maxAllowedBackgroundDeviation, bgStats[0], bgStats[1]);
                }
                reject(m, "Background deviation (Clouds/Light leak)");
            }
        }

        return thresholds;
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
