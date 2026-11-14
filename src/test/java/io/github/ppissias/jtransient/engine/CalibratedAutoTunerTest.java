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
        for (int p = 0; p < AutoTuneProfile.values().length; p++) {
            CalibratedAutoTuner.Candidate c = calibration.chosen[p];
            assertTrue("profile " + p + " within budget", calibration.withinBudget[p]);
            assertTrue(c.falsePositivesPerMpixFrame <= CalibratedAutoTuner.Options.defaults().falsePositiveBudgetPerMpixFrame()[p]
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
        DetectionConfig base = new DetectionConfig();
        base.enableFaintMoverSearch = true;
        base.faintMoverMinDisplacementFwhm = 3;
        base.faintMoverMaxSpeedPixelsPerMinute = 0.75;
        base.faintMoverMinScore = 9;
        base.faintMoverMaxSeconds = 47;
        DetectionConfig original = base.clone();
        AutoTunerResult result = CalibratedAutoTuner.tune(createFrames(), base,
                AutoTuneProfile.MEDIUM, (percent, message) -> base.qualitySigmaMultiplier = 17);
        assertTrue(result.telemetryReport, result.success);
        assertTrue(result.summary.contains("Medium profile"));
        assertTrue(result.optimizedConfig.growSigmaMultiplier < result.optimizedConfig.detectionSigmaMultiplier);
        assertTrue("the result carries the measurement of every profile", result.calibration != null && result.calibration.success);
        assertEquals(original.qualitySigmaMultiplier, result.optimizedConfig.qualitySigmaMultiplier, 0);
        assertTrue(result.optimizedConfig.enableFaintMoverSearch);
        assertEquals(original.faintMoverMinDisplacementFwhm, result.optimizedConfig.faintMoverMinDisplacementFwhm, 0);
        assertEquals(original.faintMoverMaxSpeedPixelsPerMinute, result.optimizedConfig.faintMoverMaxSpeedPixelsPerMinute, 0);
        assertEquals(original.faintMoverMinScore, result.optimizedConfig.faintMoverMinScore, 0);
        assertEquals(original.faintMoverMaxSeconds, result.optimizedConfig.faintMoverMaxSeconds, 0);
        assertEquals(original.detectionSigmaMultiplier, base.detectionSigmaMultiplier, 0);
        assertEquals(original.growSigmaMultiplier, base.growSigmaMultiplier, 0);
        assertEquals(original.masterSigmaMultiplier, base.masterSigmaMultiplier, 0);
        assertEquals(17, base.qualitySigmaMultiplier, 0);
        org.junit.Assert.assertNotSame(base, result.optimizedConfig);
    }

    @Test
    public void profileNamesParseIncludingTheNamesOfEarlierVersions() {
        assertEquals(AutoTuneProfile.HIGH, AutoTuneProfile.parse("high"));
        assertEquals(AutoTuneProfile.MAXIMUM, AutoTuneProfile.parse(" Maximum "));
        assertEquals(AutoTuneProfile.LOW, AutoTuneProfile.parse("conservative"));
        assertEquals(AutoTuneProfile.MEDIUM, AutoTuneProfile.parse("BALANCED"));
        assertEquals(AutoTuneProfile.HIGH, AutoTuneProfile.parse("aggressive"));
        assertEquals("Medium", AutoTuneProfile.MEDIUM.displayName());
        assertEquals(0.4, CalibratedAutoTuner.Options.defaults().falsePositiveBudgetPerMpixFrame()[AutoTuneProfile.MEDIUM.ordinal()], 1e-12);
        assertEquals(1.5, CalibratedAutoTuner.Options.defaults().falsePositiveBudgetPerMpixFrame()[AutoTuneProfile.HIGH.ordinal()], 1e-12);
    }

    @Test
    public void minimumPixelCandidatesAreCappedForWideStars() {
        // FWHM 6 px: core area about 28 px, so the largest factor (3x) would ask for 85 px.
        double coreArea = Math.PI * 3.0 * 3.0;
        int largest = 0;
        for (CalibratedAutoTuner.FrameSetting setting : CalibratedAutoTuner.frameSettings(coreArea)) {
            largest = Math.max(largest, setting.minPixels);
        }
        assertEquals(CalibratedAutoTuner.Options.defaults().maxMinPixels(), largest);
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
        for (AutoTuneProfile profile : AutoTuneProfile.values()) {
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
                CalibratedAutoTuner.configFor(failed, base, AutoTuneProfile.MEDIUM).detectionSigmaMultiplier, 1e-12);
    }

    /**
     * A crop's sky leaves out the excluded areas inside it: their pixels become fill, they are holes in its data area,
     * and no object is counted in or next to them. A crop without areas is measured whole.
     */
    @Test
    public void cropSkyLeavesTheAreasInsideTheCropOut() {
        int[] crop = {100, 200, 400};
        List<ExcludedArea> areas = List.of(new ExcludedArea(150, 0, 20, 2000), new ExcludedArea(0, 590, 1000, 50));
        CalibratedAutoTuner.CropSky sky = CalibratedAutoTuner.CropSky.of(crop, areas);
        assertEquals("a 20 x 400 column and a 400 x 10 band, overlapping in 20 x 10", 20 * 400 + 400 * 10 - 200, sky.excludedPixels);
        assertEquals(sky.excludedPixels / (400.0 * 400), CalibratedAutoTuner.excludedShare(crop, areas), 1e-12);
        short[][] image = new short[400][400];
        sky.fill(image);
        assertEquals(Short.MIN_VALUE, image[0][50]);
        assertEquals(Short.MIN_VALUE, image[395][300]);
        assertEquals(0, image[100][100]);
        assertFalse(sky.area.contains(55, 100));
        assertTrue(sky.area.contains(100, 100));
        java.util.BitSet owned = new java.util.BitSet(400 * 400);
        owned.set(0, 400 * 400);
        sky.keepOwnedOutside(owned, 400);
        assertFalse(owned.get(100 * 400 + 50));
        assertTrue(owned.get(100 * 400 + 100));
        CalibratedAutoTuner.CropSky clear = CalibratedAutoTuner.CropSky.of(new int[]{400, 0, 100}, areas);
        assertEquals(null, clear.area);
        assertEquals(0, clear.excludedPixels);
    }

    /**
     * The tuner measures how much of a detection lies on a star mask as the pipeline's veto does: its bit-set overlap
     * equals {@code MasterVetoMask.overlapFraction} for footprints inside, across and outside the mask.
     */
    @Test
    public void theTunersMaskOverlapIsThePipelines() {
        int width = 40, height = 30;
        Random random = new Random(11);
        boolean[][] mask = new boolean[height][width];
        java.util.BitSet bits = new java.util.BitSet(width * height);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                mask[y][x] = random.nextInt(3) == 0;
                if (mask[y][x]) bits.set(y * width + x);
            }
        }
        for (int trial = 0; trial < 50; trial++) {
            io.github.ppissias.jtransient.model.DetectedObject object = new io.github.ppissias.jtransient.model.DetectedObject(0, 0, 0, 0);
            object.rawPixels = new ArrayList<>();
            int count = random.nextInt(12);
            for (int k = 0; k < count; k++) {
                object.rawPixels.add(new io.github.ppissias.jtransient.model.Pixel(random.nextInt(width), random.nextInt(height), 100));
            }
            assertEquals(io.github.ppissias.jtransient.core.MasterVetoMask.overlapFraction(object, mask),
                    CalibratedAutoTuner.overlapOf(object, width, bits), 0.0);
        }
    }

    /**
     * The tuner measures on the frames a detection run keeps: a frame that quality control rejects (here a much
     * brighter sky) is left out, and the choice is the one made without it.
     */
    @Test
    public void framesThatQualityControlRejectsAreLeftOut() {
        List<ImageFrame> frames = new ArrayList<>(createFrames(0));
        // Copied from the second frame, whose star count keeps the session's counts spread; these synthetic frames
        // are so alike that the star-count rule would otherwise reject a frame for one star less.
        ImageFrame last = frames.get(1);
        short[][] bright = new short[SIZE][SIZE];
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                int value = PixelEncoding.toShiftedPositiveInt(last.pixelData[y][x]) + 6000;
                bright[y][x] = PixelEncoding.fromShiftedPositiveInt(Math.min(65535, value));
            }
        }
        frames.add(new ImageFrame(FRAMES, "bright", bright, frames.get(FRAMES - 1).timestamp + 120_000L, last.exposureDuration));
        CalibratedAutoTuner.Calibration withBright = CalibratedAutoTuner.calibrate(frames, new DetectionConfig(), null);
        assertTrue(withBright.failureReason, withBright.success);
        assertEquals(calibration.rejectedPoolFrames + 1, withBright.rejectedPoolFrames);
        assertEquals(calibration.poolFrames, withBright.poolFrames);
        int medium = AutoTuneProfile.MEDIUM.ordinal();
        assertEquals(calibration.chosen[medium].sigma, withBright.chosen[medium].sigma, 1e-12);
        assertEquals(calibration.chosen[medium].minPixels, withBright.chosen[medium].minPixels);
        assertEquals(calibration.chosen[medium].recoveredFraction, withBright.chosen[medium].recoveredFraction, 1e-12);

        // A caller that chose its pool from a whole session's quality control turns the tuner's own selection off.
        var chosen = CalibratedAutoTuner.Options.builder();
        chosen.selectFrames = false;
        CalibratedAutoTuner.Calibration unselected = CalibratedAutoTuner.calibrate(frames, new DetectionConfig(),
                chosen.build(), null);
        assertEquals(0, unselected.rejectedPoolFrames);
        assertEquals(frames.size(), unselected.poolFrames);
        assertTrue(CalibratedAutoTuner.Options.defaults().selectFrames());
        assertFalse(chosen.build().toBuilder().build().selectFrames());
    }

    /** With excluded areas over more than half of every crop, calibration says so instead of measuring them. */
    @Test
    public void calibrationNamesExcludedAreasThatLeaveNoCrop() {
        var builder = CalibratedAutoTuner.Options.builder();
        builder.excludedAreas = List.of(new ExcludedArea(0, 0, SIZE, 600));
        CalibratedAutoTuner.Calibration blocked = CalibratedAutoTuner.calibrate(createFrames(0), new DetectionConfig(),
                builder.build(), null);
        assertFalse(blocked.success);
        assertEquals("No tuning crop fits outside the excluded areas.", blocked.failureReason);
        assertEquals(List.of(new ExcludedArea(0, 0, SIZE, 600)), builder.build().excludedAreas());
        assertTrue(CalibratedAutoTuner.Options.defaults().excludedAreas().isEmpty());
    }

    /**
     * Spurious single-frame sources along a defective column count as false detections and raise the chosen
     * thresholds; with the column excluded they no longer count. An excluded area over clean sky leaves the choice as it
     * was.
     */
    @Test
    public void anExcludedDefectNoLongerCountsAndCleanSkyStaysTheSame() {
        List<ImageFrame> defective = createFrames(80);
        CalibratedAutoTuner.Calibration withDefect = CalibratedAutoTuner.calibrate(defective, new DetectionConfig(), null);
        var builder = CalibratedAutoTuner.Options.builder();
        builder.excludedAreas = List.of(new ExcludedArea(DEFECT_X - 6, 0, DEFECT_WIDTH + 12, SIZE));
        CalibratedAutoTuner.Calibration excluded = CalibratedAutoTuner.calibrate(defective, new DetectionConfig(),
                builder.build(), null);
        var clean = CalibratedAutoTuner.Options.builder();
        clean.excludedAreas = List.of(new ExcludedArea(200, 0, 30, SIZE));
        CalibratedAutoTuner.Calibration cleanArea = CalibratedAutoTuner.calibrate(createFrames(0), new DetectionConfig(),
                clean.build(), null);
        int medium = AutoTuneProfile.MEDIUM.ordinal();
        CalibratedAutoTuner.Candidate cleanChoice = calibration.chosen[medium];
        assertTrue("the defect raises the threshold: " + withDefect.chosen[medium].sigma,
                withDefect.chosen[medium].sigma > cleanChoice.sigma);
        assertTrue(excluded.success);
        assertEquals("every crop holds the column", excluded.crops, excluded.cropsWithExcludedAreas);
        assertTrue(excluded.measuredMpixFrames < withDefect.measuredMpixFrames);
        for (CalibratedAutoTuner.Calibration same : new CalibratedAutoTuner.Calibration[]{excluded, cleanArea}) {
            CalibratedAutoTuner.Candidate choice = same.chosen[medium];
            assertEquals(cleanChoice.sigma, choice.sigma, 1e-12);
            assertEquals(cleanChoice.growSigma, choice.growSigma, 1e-12);
            assertEquals(cleanChoice.minPixels, choice.minPixels);
            assertEquals(cleanChoice.recoveredFraction, choice.recoveredFraction, 0.02);
        }
    }

    private static final int DEFECT_X = 600;
    private static final int DEFECT_WIDTH = 16;

    private static List<ImageFrame> createFrames() {
        return createFrames(0);
    }

    /**
     * Six aligned frames: isolated Gaussian stars of 3 px FWHM over a flat sky with Poisson-like noise, and in each
     * frame {@code defectSources} faint star-like sources at random places along a defective column.
     */
    private static List<ImageFrame> createFrames(int defectSources) {
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
            Random defect = new Random(1000 + f);
            for (int d = 0; d < defectSources; d++) {
                double dx0 = DEFECT_X + defect.nextDouble() * DEFECT_WIDTH;
                double dy0 = 60 + defect.nextDouble() * (SIZE - 120);
                double peak = (3 + 9 * defect.nextDouble()) * Math.sqrt(SKY + 25);
                for (int y = (int) dy0 - 6; y <= (int) dy0 + 6; y++) {
                    for (int x = (int) dx0 - 6; x <= (int) dx0 + 6; x++) {
                        double px = x - dx0, py = y - dy0;
                        image[y][x] += peak * Math.exp(-(px * px + py * py) / (2 * sigma * sigma));
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
