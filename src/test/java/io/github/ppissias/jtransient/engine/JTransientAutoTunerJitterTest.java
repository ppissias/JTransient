package io.github.ppissias.jtransient.engine;

import io.github.ppissias.jtransient.config.DetectionConfig;
import org.junit.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Regression tests for the auto-tuner behaviors that recently caused repeatability drift:
 * jitter calibration, frame-sampling isolation, and aggressive-profile tie-breaking.
 */
public class JTransientAutoTunerJitterTest {

    /**
     * Verifies the low-level jitter probe on a controlled synthetic crop sequence.
     * The synthetic stars shift by a known amount, so the measured jitter should land near 4 px.
     */
    @Test
    public void measureMaxStarJitterCalibratesFromReferenceStackMatches() throws Exception {

        DetectionConfig config = new DetectionConfig();
        StringBuilder report = new StringBuilder();

        List<List<?>> croppedFramesByRegion = Collections.singletonList(
                createCroppedSequence(64, 64, 0, 0)
        );

        double measured = measureMaxStarJitter(croppedFramesByRegion, config, report);

        assertEquals(4.0, measured, 0.5);
        assertTrue(report.toString().contains("Calibrated maxStarJitter"));
    }

    /**
     * Verifies the full public tune(...) flow uses the measured jitter instead of trusting the caller's guess.
     * It also checks that the quality-only FWHM elongation field is preserved and no longer auto-tuned.
     */
    @Test
    public void tuneUsesCalibratedJitterInsteadOfCallerGuess() {
        var options = singleSweepOptions();

        DetectionConfig baseConfig = new DetectionConfig();
        baseConfig.maxStarJitter = 0.25;

        List<ImageFrame> frames = createImageSequence(96, 96, 8, 8);

        JTransientAutoTuner.AutoTunerResult result = JTransientAutoTuner.tune(frames, baseConfig, options, JTransientAutoTuner.AutoTuneProfile.MEDIUM, null);

        assertTrue(result.success);
        assertNotNull(result.optimizedConfig);
        assertEquals(4.0, result.optimizedConfig.maxStarJitter, 0.5);
        assertEquals(baseConfig.qualityMaxElongationForFwhm, result.optimizedConfig.qualityMaxElongationForFwhm, 0.0);
        assertTrue(result.optimizedConfig.maxStarJitter > baseConfig.maxStarJitter + 3.0);
        assertTrue(result.telemetryReport.contains("Jitter Calibration"));
        assertTrue(result.telemetryReport.contains("Quality Max Elong FWHM"));
        assertFalse(result.telemetryReport.contains("Measured Median Star Elongation"));
    }

    /**
     * Verifies frame sampling depends only on quality-side thresholds.
     * Changing detection sigma/grow/minPixels/jitter must not change the selected sample frames
     * as long as the quality-side sampling fields remain the same.
     */
    @Test
    public void frameSamplingIgnoresDetectionGrowSigmaWhenQualityGrowSigmaUnchanged() throws Exception {

        List<ImageFrame> frames = createImageSequence(96, 96, 8, 8);

        DetectionConfig baselineConfig = new DetectionConfig();
        StringBuilder baselineReport = new StringBuilder();
        List<ImageFrame> baselineSelection = getBestSampleFrames(frames, baselineConfig, baselineReport);

        DetectionConfig tunedConfig = new DetectionConfig();
        tunedConfig.detectionSigmaMultiplier = 3.5;
        tunedConfig.growSigmaMultiplier = 2.75;
        tunedConfig.minDetectionPixels = 12;
        tunedConfig.maxStarJitter = 1.81;

        StringBuilder tunedReport = new StringBuilder();
        List<ImageFrame> tunedSelection = getBestSampleFrames(frames, tunedConfig, tunedReport);

        assertEquals(sequenceIndices(baselineSelection), sequenceIndices(tunedSelection));
        assertTrue(tunedReport.toString().contains("Effective Frame-Sampling Config -> QualitySigma: 5.00 | QualityGrowSigma: 3.00 | QualityMinPix: 5 | QualityMaxElongationForFwhm: 1.50"));
        assertTrue(tunedReport.toString().contains("Frame sampling ignores incoming Detect Sigma 3.50, Grow Sigma 2.75, MinPix 12, MaskOverlap 0.75, MaxStarJitter 1.81"));
        assertFalse(tunedReport.toString().contains("forced to"));
    }

    /**
     * Verifies the HIGH profile can prefer a lower sigma candidate when scores are close.
     * This protects the winner selection from loop-order bias toward tiny minPixels.
     */
    @Test
    public void aggressiveProfilePrefersLowerSigmaWhenScoresAreNearTied() throws Exception {
        DetectionConfig currentBest = new DetectionConfig();
        currentBest.detectionSigmaMultiplier = 6.0;

        assertTrue(isBetterSweepCandidate(
                94.0,
                4.0,
                96.0,
                currentBest,
                JTransientAutoTuner.AutoTuneProfile.HIGH
        ));

        assertFalse(isBetterSweepCandidate(
                94.0,
                4.0,
                96.0,
                currentBest,
                JTransientAutoTuner.AutoTuneProfile.MEDIUM
        ));

        assertTrue(isBetterSweepCandidate(
                89.5,
                4.0,
                96.0,
                currentBest,
                JTransientAutoTuner.AutoTuneProfile.HIGH
        ));
    }

    /**
     * Verifies the HIGH profile keeps a stronger sigma preference while softening
     * the low-sigma/minPixels guard enough for moderate minPixels to stay competitive.
     */
    @Test
    public void aggressiveProfileUsesSofterLowSigmaMinPixGuard() throws Exception {
        Object policy = getPolicy(JTransientAutoTuner.AutoTuneProfile.HIGH);

        assertEquals(8.0, JTransientAutoTuner.Options.defaults().aggressiveLowerSigmaScoreWindow(), 0.0001);
        assertEquals(34.0, getDoubleField(policy, "scoreWeightHarshness"), 0.0001);
        assertEquals(0.90, getDoubleField(policy, "harshnessSigmaWeight"), 0.0001);
        assertEquals(22.0, getDoubleField(policy, "lowSigmaMinPixGuardWeight"), 0.0001);
        assertEquals(3.0, getDoubleField(policy, "lowSigmaMinPixSlope"), 0.0001);
    }

    /**
     * Verifies the Phase 1 grow-sigma sweep does not collapse to the master-map sigma.
     * The tested grow sigma must stay at least `masterSigmaMultiplier + 0.5`.
     */
    @Test
    public void deriveSweepGrowSigmaHonorsMasterSigmaFloor() throws Exception {
        DetectionConfig config = new DetectionConfig();
        config.masterSigmaMultiplier = 2.25;

        assertEquals(2.75, deriveSweepGrowSigma(3.5, 1.75, config), 0.0001);
        assertEquals(3.25, deriveSweepGrowSigma(4.0, 0.75, config), 0.0001);
    }

    @SuppressWarnings("unchecked")
    private static double measureMaxStarJitter(List<List<?>> croppedFramesByRegion,
                                               DetectionConfig config,
                                               StringBuilder report) throws Exception {
        Method method = JTransientAutoTuner.class.getDeclaredMethod(
                "measureMaxStarJitter",
                List.class,
                DetectionConfig.class,
                StringBuilder.class,
                JTransientAutoTuner.Options.class
        );
        method.setAccessible(true);
        return (Double) method.invoke(null, croppedFramesByRegion, config, report, JTransientAutoTuner.Options.defaults());
    }

    @SuppressWarnings("unchecked")
    private static List<ImageFrame> getBestSampleFrames(List<ImageFrame> frames,
                                                        DetectionConfig config,
                                                        StringBuilder report) throws Exception {
        Method method = JTransientAutoTuner.class.getDeclaredMethod(
                "getBestSampleFrames",
                List.class,
                DetectionConfig.class,
                StringBuilder.class,
                JTransientAutoTuner.Options.class
        );
        method.setAccessible(true);
        return (List<ImageFrame>) method.invoke(null, frames, config, report, JTransientAutoTuner.Options.defaults());
    }

    private static boolean isBetterSweepCandidate(double candidateScore,
                                                  double candidateSigma,
                                                  double bestScore,
                                                  DetectionConfig bestConfig,
                                                  JTransientAutoTuner.AutoTuneProfile profile) throws Exception {
        Method method = JTransientAutoTuner.class.getDeclaredMethod(
                "isBetterSweepCandidate",
                double.class,
                double.class,
                double.class,
                DetectionConfig.class,
                JTransientAutoTuner.AutoTuneProfile.class,
                JTransientAutoTuner.Options.class
        );
        method.setAccessible(true);
        return (Boolean) method.invoke(null, candidateScore, candidateSigma, bestScore, bestConfig, profile, JTransientAutoTuner.Options.defaults());
    }

    private static double deriveSweepGrowSigma(double sigma,
                                               double growDelta,
                                               DetectionConfig baseConfig) throws Exception {
        Method method = JTransientAutoTuner.class.getDeclaredMethod(
                "deriveSweepGrowSigma",
                double.class,
                double.class,
                DetectionConfig.class,
                JTransientAutoTuner.Options.class
        );
        method.setAccessible(true);
        return (Double) method.invoke(null, sigma, growDelta, baseConfig, JTransientAutoTuner.Options.defaults());
    }

    private static Object getPolicy(JTransientAutoTuner.AutoTuneProfile profile) throws Exception {
        Method method = JTransientAutoTuner.class.getDeclaredMethod(
                "getPolicy",
                JTransientAutoTuner.AutoTuneProfile.class
        );
        method.setAccessible(true);
        return method.invoke(null, profile);
    }

    private static double getDoubleField(Object target, String fieldName) throws Exception {
        var field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.getDouble(target);
    }

    /** Builds the deterministic small per-run sweep without changing shared defaults. */
    private static JTransientAutoTuner.Options singleSweepOptions() {
        var builder = JTransientAutoTuner.Options.builder();        builder.sampleSize = 5;
        builder.cropCount = 1;
        builder.borderMargin = 8;
        builder.preferredCropSizeLarge = 64;
        builder.preferredCropSizeSmall = 64;
        builder.minCropSize = 32;

        builder.minStableStars = 4;
        builder.minTotalExtractedObjects = 1;
        builder.maxTotalExtractedObjects = 1000;

        builder.sigmasToTest = new double[]{4.0};
        builder.minPixelsToTest = new int[]{5};
        builder.growDeltasToTest = new double[]{1.0};
        builder.maskOverlapToTest = new double[]{0.75};

        return builder.build();
    }

    private static List<ImageFrame> createImageSequence(int width,
                                                        int height,
                                                        int cropOffsetX,
                                                        int cropOffsetY) {
        // Five frames with the same stars shifted by a small known amount between frames.
        int[][] centers = shiftedCenters(cropOffsetX, cropOffsetY);
        int[] shifts = {-2, -1, 0, 1, 2};

        List<ImageFrame> frames = new ArrayList<>(shifts.length);
        for (int i = 0; i < shifts.length; i++) {
            short[][] pixels = createSyntheticFrame(width, height, centers, shifts[i], 0);
            frames.add(new ImageFrame(i, "frame-" + i, pixels, -1L, -1L));
        }
        return frames;
    }

    private static List<Object> createCroppedSequence(int width,
                                                      int height,
                                                      int offsetX,
                                                      int offsetY) throws Exception {
        // Reflection is used because CroppedFrame is a private helper inside the tuner.
        int[][] centers = shiftedCenters(offsetX, offsetY);
        int[] shifts = {-2, -1, 0, 1, 2};

        Class<?> croppedFrameClass = Class.forName("io.github.ppissias.jtransient.engine.JTransientAutoTuner$CroppedFrame");
        Constructor<?> constructor = croppedFrameClass.getDeclaredConstructor(short[][].class, int.class, String.class);
        constructor.setAccessible(true);

        List<Object> frames = new ArrayList<>(shifts.length);
        for (int i = 0; i < shifts.length; i++) {
            short[][] pixels = createSyntheticFrame(width, height, centers, shifts[i], 0);
            frames.add(constructor.newInstance(pixels, i, "frame-" + i));
        }
        return frames;
    }

    private static int[][] shiftedCenters(int offsetX, int offsetY) {
        return new int[][]{
                {offsetX + 20, offsetY + 20},
                {offsetX + 44, offsetY + 20},
                {offsetX + 20, offsetY + 44},
                {offsetX + 44, offsetY + 44}
        };
    }

    private static short[][] createSyntheticFrame(int width,
                                                  int height,
                                                  int[][] starCenters,
                                                  int shiftX,
                                                  int shiftY) {
        // Bright compact stars on a nearly flat background keep the synthetic expectations stable.
        short[][] frame = new short[height][width];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                frame[y][x] = (short) (-32000 + (((x * 17) + (y * 31)) % 5) - 2);
            }
        }

        int[][] kernel = {
                {0, 180, 360, 180, 0},
                {180, 700, 1200, 700, 180},
                {360, 1200, 2200, 1200, 360},
                {180, 700, 1200, 700, 180},
                {0, 180, 360, 180, 0}
        };

        for (int[] center : starCenters) {
            stampKernel(frame, center[0] + shiftX, center[1] + shiftY, kernel);
        }

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

    private static List<Integer> sequenceIndices(List<ImageFrame> frames) {
        List<Integer> indices = new ArrayList<>(frames.size());
        for (ImageFrame frame : frames) {
            indices.add(frame.sequenceIndex);
        }
        return indices;
    }


}
