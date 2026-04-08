package io.github.ppissias.jtransient.quality;

import io.github.ppissias.jtransient.config.DetectionConfig;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Regression tests for the quality-analysis path, especially the split between
 * detection-side grow sigma and quality-side grow sigma.
 */
public class FrameQualityAnalyzerTest {

    /**
     * Verifies that FrameQualityAnalyzer ignores detection grow sigma and only reacts to
     * qualityGrowSigmaMultiplier. The synthetic star has a halo near the grow threshold so
     * the measured FWHM changes only when the quality-side grow setting changes.
     */
    @Test
    public void evaluateFrameUsesQualityGrowSigmaInsteadOfDetectionGrowSigma() {
        short[][] frame = createQualityProbeFrame(48, 48, 24, 24);

        DetectionConfig baselineConfig = new DetectionConfig();
        baselineConfig.qualitySigmaMultiplier = 5.0;
        baselineConfig.qualityGrowSigmaMultiplier = 3.0;
        baselineConfig.qualityMinDetectionPixels = 3;
        baselineConfig.qualityMaxElongationForFwhm = 2.0;
        baselineConfig.growSigmaMultiplier = 1.5;

        FrameQualityAnalyzer.FrameMetrics baseline =
                FrameQualityAnalyzer.evaluateFrame(frame, baselineConfig);

        assertTrue(baseline.medianFWHM < baselineConfig.errorFallbackValue);
        assertTrue(baseline.fwhmStarCount > 0);

        DetectionConfig detectionGrowVariant = baselineConfig.clone();
        detectionGrowVariant.growSigmaMultiplier = 8.0;

        FrameQualityAnalyzer.FrameMetrics detectionGrowMetrics =
                FrameQualityAnalyzer.evaluateFrame(frame, detectionGrowVariant);

        assertEquals(baseline.starCount, detectionGrowMetrics.starCount);
        assertEquals(baseline.medianFWHM, detectionGrowMetrics.medianFWHM, 1.0e-9);
        assertEquals(baseline.fwhmStarCount, detectionGrowMetrics.fwhmStarCount);

        DetectionConfig qualityGrowVariant = baselineConfig.clone();
        qualityGrowVariant.qualityGrowSigmaMultiplier = 8.0;

        FrameQualityAnalyzer.FrameMetrics qualityGrowMetrics =
                FrameQualityAnalyzer.evaluateFrame(frame, qualityGrowVariant);

        assertTrue(Math.abs(baseline.medianFWHM - qualityGrowMetrics.medianFWHM) > 0.05);
    }

    /**
     * Verifies that the analyzer exposes a separate eccentricity metric for the brighter stars so
     * partial tracking slips can stand out even when the full star population still looks mostly normal.
     */
    @Test
    public void evaluateFrameMeasuresBrightStarEccentricitySeparately() {
        short[][] frame = createBrightStarEccentricityProbeFrame(96, 96);

        DetectionConfig config = new DetectionConfig();
        config.qualitySigmaMultiplier = 3.0;
        config.qualityGrowSigmaMultiplier = 2.0;
        config.qualityMinDetectionPixels = 3;
        config.qualityMaxElongationForFwhm = 3.0;
        config.qualityBrightStarPeakSigmaOffset = 20.0;
        config.qualityBrightStarMinStars = 1;

        FrameQualityAnalyzer.FrameMetrics metrics =
                FrameQualityAnalyzer.evaluateFrame(frame, config);

        assertTrue(metrics.usableShapeStarCount >= 6);
        assertTrue(metrics.brightStarShapeStarCount >= 1);
        assertTrue(Double.isFinite(metrics.brightStarMedianEccentricity));
        assertTrue(metrics.brightStarMedianEccentricity > metrics.medianEccentricity);
    }

    private static short[][] createQualityProbeFrame(int width, int height, int centerX, int centerY) {
        // Low-amplitude structured background plus one compact star with a threshold-sensitive halo.
        short[][] frame = new short[height][width];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                frame[y][x] = (short) (1000 + (((x * 13) + (y * 7)) % 21) - 10);
            }
        }

        int[][] kernel = {
                {0, 8, 20, 8, 0},
                {8, 26, 55, 26, 8},
                {20, 55, 95, 55, 20},
                {8, 26, 55, 26, 8},
                {0, 8, 20, 8, 0}
        };

        stampKernel(frame, centerX, centerY, kernel);
        return frame;
    }

    private static short[][] createBrightStarEccentricityProbeFrame(int width, int height) {
        short[][] frame = new short[height][width];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                frame[y][x] = (short) (1000 + (((x * 11) + (y * 5)) % 9) - 4);
            }
        }

        int[][] dimRoundKernel = {
                {0, 2, 5, 2, 0},
                {2, 8, 16, 8, 2},
                {5, 16, 28, 16, 5},
                {2, 8, 16, 8, 2},
                {0, 2, 5, 2, 0}
        };
        int[][] brightElongatedKernel = {
                {0, 0, 6, 14, 22, 14, 6, 0, 0},
                {0, 8, 20, 40, 60, 40, 20, 8, 0},
                {8, 22, 48, 92, 140, 92, 48, 22, 8},
                {0, 8, 20, 40, 60, 40, 20, 8, 0},
                {0, 0, 6, 14, 22, 14, 6, 0, 0}
        };

        int[][] dimStarCenters = {
                {16, 16},
                {30, 20},
                {22, 44},
                {42, 32},
                {58, 20},
                {70, 42},
                {20, 70},
                {48, 66}
        };
        for (int[] center : dimStarCenters) {
            stampKernel(frame, center[0], center[1], dimRoundKernel);
        }

        stampKernel(frame, 72, 68, brightElongatedKernel);
        stampKernel(frame, 74, 28, brightElongatedKernel);
        return frame;
    }

    private static void stampKernel(short[][] frame, int centerX, int centerY, int[][] kernel) {
        int kernelHalf = kernel.length / 2;
        for (int ky = 0; ky < kernel.length; ky++) {
            for (int kx = 0; kx < kernel[ky].length; kx++) {
                int x = centerX + kx - kernelHalf;
                int y = centerY + ky - kernelHalf;
                if (x < 0 || x >= frame[0].length || y < 0 || y >= frame.length) {
                    continue;
                }
                frame[y][x] = (short) (frame[y][x] + kernel[ky][kx]);
            }
        }
    }
}
