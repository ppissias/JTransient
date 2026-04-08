package io.github.ppissias.jtransient.quality;

import io.github.ppissias.jtransient.config.DetectionConfig;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Regression tests for session-level frame rejection.
 */
public class SessionEvaluatorTest {

    /**
     * Verifies that the dedicated bright-star eccentricity gate can reject a frame whose
     * general eccentricity still looks normal because only the brighter stars are trailed.
     */
    @Test
    public void rejectOutlierFramesRejectsBrightStarEccentricitySpike() {
        DetectionConfig config = new DetectionConfig();
        config.minFramesForAnalysis = 3;
        config.eccentricitySigmaDeviation = 3.0;
        config.brightStarEccentricitySigmaDeviation = 3.0;
        config.minEccentricityEnvelope = 0.10;
        config.minBrightStarEccentricityEnvelope = 0.10;
        config.enableBrightStarEccentricityFilter = true;

        List<FrameQualityAnalyzer.FrameMetrics> sessionMetrics = new ArrayList<>();
        sessionMetrics.add(createMetrics("frame-0", 3.0, 1.12, 1.18));
        sessionMetrics.add(createMetrics("frame-1", 3.0, 1.11, 1.20));
        sessionMetrics.add(createMetrics("frame-2", 3.0, 1.13, 1.19));
        sessionMetrics.add(createMetrics("frame-3", 3.0, 1.12, 2.10));
        sessionMetrics.add(createMetrics("frame-4", 3.0, 1.10, 1.17));

        SessionEvaluator.SessionThresholds thresholds = SessionEvaluator.rejectOutlierFrames(sessionMetrics, config);

        assertFalse(sessionMetrics.get(0).isRejected);
        assertFalse(sessionMetrics.get(1).isRejected);
        assertFalse(sessionMetrics.get(2).isRejected);
        assertTrue(sessionMetrics.get(3).isRejected);
        assertEquals("Bright-star eccentricity spiked (Tracking error/Wind)", sessionMetrics.get(3).rejectionReason);
        assertFalse(sessionMetrics.get(4).isRejected);

        assertTrue(thresholds.available);
        assertEquals(99.998, thresholds.minAllowedStarCount, 1.0e-6);
        assertEquals(3.5, thresholds.maxAllowedFwhm, 1.0e-6);
        assertEquals(1.22, thresholds.maxAllowedEccentricity, 1.0e-6);
        assertEquals(1.29, thresholds.maxAllowedBrightStarEccentricity, 1.0e-6);
        assertEquals(1000.0, thresholds.backgroundMedianBaseline, 1.0e-6);
        assertEquals(10.0, thresholds.maxAllowedBackgroundDeviation, 1.0e-6);
        assertEquals(990.0, thresholds.minAllowedBackgroundMedian, 1.0e-6);
        assertEquals(1010.0, thresholds.maxAllowedBackgroundMedian, 1.0e-6);
    }

    private static FrameQualityAnalyzer.FrameMetrics createMetrics(String filename,
                                                                   double fwhm,
                                                                   double eccentricity,
                                                                   double brightStarEccentricity) {
        FrameQualityAnalyzer.FrameMetrics metrics = new FrameQualityAnalyzer.FrameMetrics();
        metrics.filename = filename;
        metrics.backgroundMedian = 1000.0;
        metrics.backgroundNoise = 10.0;
        metrics.medianFWHM = fwhm;
        metrics.medianEccentricity = eccentricity;
        metrics.brightStarMedianEccentricity = brightStarEccentricity;
        metrics.starCount = 100;
        metrics.usableShapeStarCount = 80;
        metrics.brightStarShapeStarCount = 12;
        metrics.fwhmStarCount = 70;
        return metrics;
    }
}
