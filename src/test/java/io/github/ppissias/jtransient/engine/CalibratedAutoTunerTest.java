package io.github.ppissias.jtransient.engine;

import io.github.ppissias.jtransient.config.DetectionConfig;
import io.github.ppissias.jtransient.core.PixelEncoding;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CalibratedAutoTunerTest {

    private static final int SIZE = 900;
    private static final int FRAMES = 6;
    private static final double SKY = 1000.0;
    private static final double FWHM = 3.0;

    private static CalibratedAutoTuner.Calibration calibration;

    @BeforeClass
    public static void calibrateSyntheticSession() {
        calibration = CalibratedAutoTuner.calibrate(createFrames(), new DetectionConfig(), null);
    }

    @Test
    public void measuresTheSessionStarSize() {
        assertTrue(calibration.failureReason, calibration.success);
        assertEquals(FWHM, calibration.fwhm, 0.5);
        assertTrue("jitter floor", calibration.jitter >= 1.0);
    }

    @Test
    public void everyProfileStaysWithinItsBudgets() {
        for (int p = 0; p < JTransientAutoTuner.AutoTuneProfile.values().length; p++) {
            CalibratedAutoTuner.Candidate c = calibration.chosen[p];
            assertTrue("profile " + p + " within budget", calibration.withinBudget[p]);
            assertTrue(c.falsePositivesPerMpixFrame <= CalibratedAutoTuner.FALSE_POSITIVE_BUDGET_PER_MPIX_FRAME[p]
                    || !calibration.budgetVerifiable[p]);

        }
    }

    @Test
    public void moreAggressiveProfilesAreNeverLessSensitive() {
        CalibratedAutoTuner.Candidate conservative = calibration.chosen[0];
        CalibratedAutoTuner.Candidate balanced = calibration.chosen[1];
        CalibratedAutoTuner.Candidate aggressive = calibration.chosen[2];
        CalibratedAutoTuner.Candidate maximum = calibration.chosen[3];
        assertTrue(balanced.recoveredFraction >= conservative.recoveredFraction);
        assertTrue(aggressive.recoveredFraction >= balanced.recoveredFraction);
        assertTrue(maximum.recoveredFraction >= aggressive.recoveredFraction);
    }

    @Test
    public void brightSyntheticSourcesAreRecovered() {
        CalibratedAutoTuner.Candidate balanced = calibration.chosen[1];
        double[] completeness = balanced.completeness;
        assertTrue("SNR 15 recovery " + completeness[completeness.length - 1], completeness[completeness.length - 1] > 0.8);
        assertTrue("half recovered by SNR 6: " + balanced.snr50, balanced.snr50 <= 6.0);
    }

    @Test
    public void faintSatelliteTrailBecomesAStreakCorridorButStarsDoNot() {
        int size = 400;
        double sigma = 30.0;
        double[][] image = new double[size][size];
        for (double[] row : image) {
            Arrays.fill(row, SKY);
        }
        // A faint trail (peak about 3 sigma, 3 px FWHM) from (40, 60) to (360, 140): at ordinary thresholds it
        // breaks into round fragments, which the streak pass joins.
        double psf = FWHM / 2.355;
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                double t = Math.max(0, Math.min(1, ((x - 40) * 320.0 + (y - 60) * 80.0) / (320.0 * 320 + 80.0 * 80)));
                double px = 40 + 320 * t;
                double py = 60 + 80 * t;
                double d2 = (x - px) * (x - px) + (y - py) * (y - py);
                image[y][x] += 3.0 * sigma * Math.exp(-d2 / (2 * psf * psf));
            }
        }
        // A bright star, covered by the star mask.
        BitSet starMask = new BitSet(size * size);
        for (int y = 290; y <= 310; y++) {
            for (int x = 190; x <= 210; x++) {
                image[y][x] += 20000.0 * Math.exp(-((x - 200) * (x - 200) + (y - 300) * (y - 300)) / (2 * psf * psf));
                starMask.set(y * size + x);
            }
        }
        Random random = new Random(3);
        short[][] pixels = new short[size][size];
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                int v = (int) Math.round(image[y][x] + random.nextGaussian() * sigma);
                pixels[y][x] = PixelEncoding.fromShiftedPositiveInt(Math.max(0, Math.min(65535, v)));
            }
        }

        BitSet corridor = CalibratedAutoTuner.streakCorridor(pixels, starMask, 3, new DetectionConfig());

        int onTrail = 0;
        for (int i = 1; i < 10; i++) {
            int x = 40 + 32 * i;
            int y = 60 + 8 * i;
            if (corridor.get(y * size + x)) onTrail++;
        }
        assertTrue("trail points inside the corridor: " + onTrail, onTrail >= 8);
        assertFalse("the star is not a streak", corridor.get(300 * size + 200));
        assertFalse("empty sky is not a streak", corridor.get(250 * size + 300));
    }

    @Test
    public void tuneReturnsTheChosenSettings() {
        JTransientAutoTuner.AutoTunerResult result = CalibratedAutoTuner.tune(createFrames(), new DetectionConfig(),
                JTransientAutoTuner.AutoTuneProfile.BALANCED, null);
        assertTrue(result.telemetryReport, result.success);
        assertTrue(result.summary.contains("BALANCED"));
        assertTrue(result.optimizedConfig.growSigmaMultiplier < result.optimizedConfig.detectionSigmaMultiplier);
        assertTrue("the result carries the measurement of every profile", result.calibration != null && result.calibration.success);
    }

    @Test
    public void minimumPixelCandidatesAreCappedForWideStars() {
        // FWHM 6 px: core area about 28 px, so the largest factor (3x) would ask for 85 px.
        double coreArea = Math.PI * 3.0 * 3.0;
        int largest = 0;
        for (CalibratedAutoTuner.FrameSetting setting : CalibratedAutoTuner.frameSettings(coreArea)) {
            largest = Math.max(largest, setting.minPixels);
        }
        assertEquals(CalibratedAutoTuner.MAX_MIN_PIXELS, largest);
        // Sharp stars keep their area-based sizes (FWHM 2 px: largest 3x core area = 9 px).
        int sharpLargest = 0;
        for (CalibratedAutoTuner.FrameSetting setting : CalibratedAutoTuner.frameSettings(Math.PI)) {
            sharpLargest = Math.max(sharpLargest, setting.minPixels);
        }
        assertEquals(9, sharpLargest);
    }

    @Test
    public void configForAppliesEachProfilesChoice() {
        DetectionConfig base = new DetectionConfig();
        for (JTransientAutoTuner.AutoTuneProfile profile : JTransientAutoTuner.AutoTuneProfile.values()) {
            CalibratedAutoTuner.Candidate chosen = calibration.chosen[profile.ordinal()];
            DetectionConfig tuned = CalibratedAutoTuner.configFor(calibration, base, profile);
            assertEquals(chosen.sigma, tuned.detectionSigmaMultiplier, 1e-12);
            assertEquals(chosen.minPixels, tuned.minDetectionPixels);
            assertEquals(chosen.masterSigma, tuned.masterSigmaMultiplier, 1e-12);
            assertEquals(calibration.jitter, tuned.maxStarJitter, 1e-12);
        }
        assertEquals("the base configuration is not modified", new DetectionConfig().detectionSigmaMultiplier, base.detectionSigmaMultiplier, 1e-12);
        CalibratedAutoTuner.Calibration failed = new CalibratedAutoTuner.Calibration();
        assertEquals("a failed calibration leaves the settings unchanged", base.detectionSigmaMultiplier,
                CalibratedAutoTuner.configFor(failed, base, JTransientAutoTuner.AutoTuneProfile.BALANCED).detectionSigmaMultiplier, 1e-12);
    }

    /** Six aligned frames: isolated Gaussian stars of 3 px FWHM over a flat sky with Poisson-like noise. */
    private static List<ImageFrame> createFrames() {
        Random random = new Random(3);
        List<double[]> stars = new ArrayList<>();
        for (int i = 0; i < 400; i++) {
            stars.add(new double[]{20 + random.nextDouble() * (SIZE - 40), 20 + random.nextDouble() * (SIZE - 40),
                    2000.0 * Math.pow(100.0, random.nextDouble())});
        }
        double sigma = FWHM / 2.355;
        List<ImageFrame> frames = new ArrayList<>();
        for (int f = 0; f < FRAMES; f++) {
            double[][] image = new double[SIZE][SIZE];
            for (double[] row : image) Arrays.fill(row, SKY);
            for (double[] star : stars) {
                int r = (int) Math.ceil(5 * sigma);
                int x0 = (int) Math.round(star[0]);
                int y0 = (int) Math.round(star[1]);
                double norm = star[2] / (2 * Math.PI * sigma * sigma);
                for (int dy = -r; dy <= r; dy++) {
                    for (int dx = -r; dx <= r; dx++) {
                        int x = x0 + dx;
                        int y = y0 + dy;
                        if (x < 0 || y < 0 || x >= SIZE || y >= SIZE) continue;
                        double px = x - star[0];
                        double py = y - star[1];
                        image[y][x] += norm * Math.exp(-(px * px + py * py) / (2 * sigma * sigma));
                    }
                }
            }
            short[][] pixels = new short[SIZE][SIZE];
            for (int y = 0; y < SIZE; y++) {
                for (int x = 0; x < SIZE; x++) {
                    double v = image[y][x] + random.nextGaussian() * Math.sqrt(image[y][x] + 25);
                    pixels[y][x] = PixelEncoding.fromShiftedPositiveInt((int) Math.max(0, Math.min(65535, Math.round(v))));
                }
            }
            frames.add(new ImageFrame(f, "frame" + f, pixels, 1_760_000_000_000L + f * 120_000L, 60_000L));
        }
        return frames;
    }
}
