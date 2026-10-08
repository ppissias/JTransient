/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 * author: Petros Pissias <petrospis at gmail.com>
 *
 */
package io.github.ppissias.jtransient.engine;

import io.github.ppissias.jtransient.config.DetectionConfig;
import io.github.ppissias.jtransient.core.FrameDriftAnalyzer;
import io.github.ppissias.jtransient.core.MasterMapGenerator;
import io.github.ppissias.jtransient.core.PixelEncoding;
import io.github.ppissias.jtransient.core.SourceExtractor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Auto-tuner that measures noise and sensitivity directly instead of scoring proxies.
 *
 * <p>For every combination of per-frame detection settings (sigma, grow sigma, minimum pixels),
 * master-mask settings (master sigma, master grow sigma, master minimum pixels) and veto overlap,
 * it measures on a few frames of the session:</p>
 * <ul>
 *   <li><b>noise false positives</b>: detections that survive the veto in a negative image
 *       {@code 2 x master - frame}, which has the noise of a frame but no real objects;</li>
 *   <li><b>star leakage</b>: surviving detections in the real frames that touch the star mask, repeat at the
 *       same place on the sky or on the sensor (hot pixels follow the drift), or sit next to a star and
 *       reappear beside it in another frame;</li>
 *   <li><b>real-frame excess</b>: surviving real-frame detections above the lowest rate any setting reaches;
 *       pieces of satellite and meteor trails, found by a more sensitive streak pass, are not counted;</li>
 *   <li><b>sensitivity</b>: the fraction of synthetic point sources, shaped like the session's stars and
 *       injected over a range of peak signal-to-noise ratios, that are detected and survive the veto;</li>
 *   <li><b>mask coverage</b>: the share of the sky the veto mask hides.</li>
 * </ul>
 * <p>A profile is a false-positive budget per megapixel per frame (noise plus leakage, or the real-frame excess
 * if larger). Within the budget the tuner picks the settings that recover the most synthetic sources, so a
 * more permissive profile is never less sensitive. The legacy {@link JTransientAutoTuner} is kept for
 * comparison.</p>
 */
public final class CalibratedAutoTuner {

    /**
     * False positives that depend on the settings allowed per megapixel per frame, for the low, medium,
     * high and maximum profiles (indexed by profile ordinal). Because the budget is per megapixel, maximum leaves
     * many candidates on a large sensor; it is meant for small sensors and targeted searches.
     */
    public static double[] FALSE_POSITIVE_BUDGET_PER_MPIX_FRAME = {0.05, 0.4, 1.5, 3.5};
    private static final int PROFILE_COUNT = JTransientAutoTuner.AutoTuneProfile.values().length;

    public static double[] SIGMAS_TO_TEST = {2.5, 2.75, 3.0, 3.25, 3.5, 4.0, 4.5, 5.0, 6.0};
    public static double[] GROW_DELTAS_TO_TEST = {0.75, 1.25};
    /** Minimum-pixel candidates as multiples of the PSF core area (pi x (FWHM/2)^2). */
    public static double[] MIN_PIXEL_AREA_FACTORS = {0.3, 0.5, 0.75, 1.0, 1.5, 2.0, 3.0};
    /**
     * Upper limit of the minimum-pixel candidates. With wide stars the area factors would reach 25 pixels and more,
     * which rejects small faint movers; larger sizes than this are not tried.
     */
    public static int MAX_MIN_PIXELS = 15;
    public static double[] MASTER_SIGMAS_TO_TEST = {1.5, 2.0, 2.5, 3.0, 3.5, 4.0, 5.0};
    public static double[] MASTER_GROW_OFFSETS_TO_TEST = {0.0, 0.5, 1.0};
    /** Master minimum-pixel candidates as multiples of the PSF core area, in addition to 3 pixels. */
    public static double[] MASTER_MIN_PIXEL_AREA_FACTORS = {0.25, 0.5, 1.0};
    public static double[] MASK_OVERLAPS_TO_TEST = {0.5, 0.65, 0.75, 0.85, 0.95};

    /** Injected peak signal-to-noise levels (peak amplitude over background sigma). */
    public static double[] INJECTION_PEAK_SNR = {2.0, 3.0, 4.0, 5.0, 6.0, 8.0, 10.0, 15.0};
    public static int INJECTIONS_PER_LEVEL_PER_CROP_FRAME = 12;

    public static int MEASUREMENT_FRAMES = 10;
    /** Crops on ordinary frames: the centre and the four interior corners. */
    public static int CROP_COUNT = 5;
    /**
     * Large frames get an even grid of crops instead, about one per {@code 2 x crop size} of usable width and
     * height (at most this many per side), so local problems such as a badly registered region are sampled.
     */
    public static int MAX_CROPS_PER_SIDE = 4;
    public static int PREFERRED_CROP_SIZE = 1024;
    public static int MIN_CROP_SIZE = 384;
    /** Largest border kept clear of the frame edges; the actual border is the measured drift padding plus
     * {@link #CROP_PADDING_CLEARANCE}, at least {@link #MIN_CROP_BORDER_MARGIN}. */
    public static int CROP_BORDER_MARGIN = 200;
    public static int MIN_CROP_BORDER_MARGIN = 50;
    public static int CROP_PADDING_CLEARANCE = 50;

    /**
     * Streak pass: a more sensitive extraction of every real frame joins the fragments of faint satellite or
     * meteor trails into one elongated object. Survivors within one FWHM of such a streak are real objects, not
     * false positives. Streaks lying mostly inside the deepest star mask (merged chains of stars) do not count.
     */
    private static final double STREAK_PASS_SIGMA = 2.5;
    private static final double STREAK_PASS_GROW = 1.5;
    private static final double STREAK_MAX_STAR_OVERLAP = 0.5;
    /** Extra radius for comparing positions on the sensor (whole-pixel drift, small field rotation). */
    private static final double SENSOR_RECURRENCE_MARGIN = 2.0;
    /** Small sensors measure on more frames until about this many megapixel-frames are covered... */
    private static final double TARGET_MEASURED_MPIX_FRAMES = 30.0;
    /** ...but on at most this many frames. */
    private static final int MAX_MEASUREMENT_FRAMES = 40;
    private static final double JITTER_PERCENTILE = 0.90;
    private static final double JITTER_SAFETY_MULTIPLIER = 2.0;
    private static final double MIN_JITTER = 1.0;
    /** Recovered fractions closer than this are treated as equal (about the measurement precision). */
    private static final double TIE_EPSILON = 0.01;
    /** Injection levels at or above this peak SNR define the bright-source recovery. */
    private static final double BRIGHT_LEVEL_SNR = 8.0;
    private static final long RANDOM_SEED = 20261006L;
    /** Crops whose background noise is below this (ADU) are blank or sky-clipped and are not measured. */
    private static final double MIN_BACKGROUND_SIGMA = 1.0;
    /**
     * A frame setting is skipped when even the deepest mask leaves more than this factor times the largest
     * budget in noise detections.
     */
    private static final double RAW_NOISE_SKIP_FACTOR = 10.0;

    private CalibratedAutoTuner() {
    }

    // =========================================================================
    // Data holders
    // =========================================================================

    /** One evaluated combination of settings with its measurements. */
    public static final class Candidate {
        public double sigma;
        public double growSigma;
        public int minPixels;
        public double masterSigma;
        public double masterGrowSigma;
        public int masterMinPixels;
        public double maskOverlap;
        /** Noise false positives per megapixel per frame (negative image, after the veto). */
        public double noisePerMpixFrame;
        /** Real-frame survivors that touch the star mask or recur at the same place (star leakage), per megapixel per frame. */
        public double leakagePerMpixFrame;
        /** All real-frame detections that survive the veto, per megapixel per frame (real objects, noise, leakage). */
        public double realSurvivorsPerMpixFrame;
        /** Real-frame survivors that partly overlap the star mask (star-edge fragments), per megapixel per frame. */
        public double starEdgeSurvivorsPerMpixFrame;
        /** Real-frame survivors on satellite or meteor trails (streak pass), not counted as false positives. */
        public double trailFragmentsPerMpixFrame;
        /** Share of the sky covered by the veto mask. */
        public double maskCoverage;
        /** Fraction of injected sources detected and kept, per injection level. */
        public double[] completeness;
        /** False positives caused by the settings: noise plus star leakage. */
        public double falsePositivesPerMpixFrame;
        /** Cautious false-positive estimate used against the budget: (count + 1) / measured area. */
        public double falsePositiveUpperPerMpixFrame;
        /** Mean of {@link #completeness} over all levels. */
        public double recoveredFraction;
        /** Mean completeness for bright sources (peak SNR of 8 and above): losses here come from masking and blending. */
        public double brightRecovery;
        /** Peak SNR at which half of the injected sources are recovered (NaN if never reached). */
        public double snr50;

        String settingsText() {
            return String.format(Locale.US, "sigma %.2f, grow %.2f, minPix %d | master %.2f/%.2f/%d | overlap %.2f",
                    sigma, growSigma, minPixels, masterSigma, masterGrowSigma, masterMinPixels, maskOverlap);
        }

        String measurementText() {
            return String.format(Locale.US, "false positives %.3f | ", falsePositivesPerMpixFrame)
                    + String.format(Locale.US, "real survivors %.3f (star-edge %.3f; trail fragments %.3f not counted) /MPix/frame | ",
                    realSurvivorsPerMpixFrame, starEdgeSurvivorsPerMpixFrame, trailFragmentsPerMpixFrame)
                    + String.format(Locale.US, "noise %.3f /MPix/frame, leakage %.3f /MPix/frame, mask %.1f%%, recovered %.1f%%, bright %.0f%%, SNR50 %s",
                    noisePerMpixFrame, leakagePerMpixFrame, 100 * maskCoverage, 100 * recoveredFraction,
                    100 * brightRecovery, Double.isNaN(snr50) ? "n/a" : String.format(Locale.US, "%.2f", snr50));
        }
    }

    private static final class MasterSetting {
        final double seed;
        final double grow;
        final int minPixels;

        MasterSetting(double seed, double grow, int minPixels) {
            this.seed = seed;
            this.grow = grow;
            this.minPixels = minPixels;
        }
    }

    static final class FrameSetting {
        final double sigma;
        final double grow;
        final int minPixels;

        FrameSetting(double sigma, double grow, int minPixels) {
            this.sigma = sigma;
            this.grow = grow;
            this.minPixels = minPixels;
        }
    }

    /** Compact detection: centroid, streak flag and flat pixel indices inside the crop. */
    private static final class Detection {
        final double x;
        final double y;
        final boolean streak;
        final int[] pixels;

        Detection(SourceExtractor.DetectedObject obj, int width) {
            this.x = obj.x;
            this.y = obj.y;
            this.streak = obj.isStreak;
            if (obj.rawPixels == null) {
                this.pixels = new int[0];
            } else {
                this.pixels = new int[obj.rawPixels.size()];
                for (int i = 0; i < pixels.length; i++) {
                    SourceExtractor.Pixel p = obj.rawPixels.get(i);
                    pixels[i] = p.y * width + p.x;
                }
            }
        }
    }

    /** Per crop-frame images used for the measurements. */
    private static final class CropFrame {
        final int crop;
        final short[][] positive;
        final short[][] negative;
        final short[][] injected;
        final double[] injectX;
        final double[] injectY;
        final int[] injectLevel;
        /** Background noise of the real frame crop; about zero for blank or clipped frames. */
        double backgroundSigma;
        /** Registration shift of the frame (sensor position = registered position - drift). */
        double driftX;
        double driftY;
        /** Pixels within one FWHM of a satellite or meteor trail in this frame. */
        BitSet streakCorridor = new BitSet();

        CropFrame(int crop, short[][] positive, short[][] negative, short[][] injected,
                  double[] injectX, double[] injectY, int[] injectLevel) {
            this.crop = crop;
            this.positive = positive;
            this.negative = negative;
            this.injected = injected;
            this.injectX = injectX;
            this.injectY = injectY;
            this.injectLevel = injectLevel;
        }
    }

    // =========================================================================
    // Entry points
    // =========================================================================

    /** Full measurement of one session: every evaluated candidate and the choice for each profile. */
    public static final class Calibration {
        /** Whether the measurement completed. */
        public boolean success;
        /** Human-readable explanation when {@link #success} is false. */
        public String failureReason;
        public int poolFrames;
        public int measurementFrames;
        public int crops;
        public int cropSize;
        /** Measured star FWHM in pixels. */
        public double fwhm;
        /** Measured residual jitter (veto-mask dilation input) in pixels. */
        public double jitter;
        public double elapsedSeconds;
        /** Area measured for noise and leakage, in megapixel-frames. */
        public double measuredMpixFrames;
        /** Real single-frame events every setting detects (cosmic rays, real objects), per megapixel per frame. */
        public double singleFrameFloorPerMpixFrame;
        /** Measurement crops skipped because their background was blank or clipped. */
        public int skippedCrops;
        /** Pool frames left out as blank or failed registrations. */
        public int excludedBlankFrames;
        /** Per-frame settings skipped because their raw noise could never fit a budget. */
        public int skippedFrameSettings;
        /** Whether the measured area was large enough to confirm each profile's noise budget. */
        public final boolean[] budgetVerifiable = new boolean[PROFILE_COUNT];
        /** Every evaluated combination. */
        public final List<Candidate> candidates = new ArrayList<>();
        /** Chosen candidate per profile, indexed by {@link JTransientAutoTuner.AutoTuneProfile#ordinal()}. */
        public final Candidate[] chosen = new Candidate[PROFILE_COUNT];
        /** Whether the chosen candidate met the profile's budgets (otherwise the cleanest candidate was used). */
        public final boolean[] withinBudget = new boolean[PROFILE_COUNT];
    }

    /**
     * Applies the settings chosen for {@code profile} by a successful calibration to a clone of {@code baseConfig}.
     *
     * @return the tuned configuration, or a plain clone when the calibration failed or chose nothing for the profile
     */
    public static DetectionConfig configFor(Calibration calibration,
                                            DetectionConfig baseConfig,
                                            JTransientAutoTuner.AutoTuneProfile profile) {
        DetectionConfig tuned = baseConfig.clone();
        Candidate best = calibration == null || !calibration.success ? null : calibration.chosen[profile.ordinal()];
        if (best == null) {
            return tuned;
        }
        tuned.detectionSigmaMultiplier = best.sigma;
        tuned.growSigmaMultiplier = best.growSigma;
        tuned.minDetectionPixels = best.minPixels;
        tuned.masterSigmaMultiplier = best.masterSigma;
        tuned.masterGrowSigmaMultiplier = best.masterGrowSigma;
        tuned.masterMinDetectionPixels = best.masterMinPixels;
        tuned.maxMaskOverlapFraction = best.maskOverlap;
        tuned.maxStarJitter = calibration.jitter;
        return tuned;
    }

    /**
     * Tunes the detection and master-mask settings for the supplied candidate frames.
     *
     * @param poolFrames representative frames of the session (the master is built from all of them)
     * @param baseConfig configuration to start from; tuned fields are overwritten in a clone
     * @param profile sensitivity profile (low, medium, high or maximum)
     * @param listener optional progress listener
     * @return result in the same shape as the legacy tuner, with {@code summary} filled in
     */
    public static JTransientAutoTuner.AutoTunerResult tune(List<ImageFrame> poolFrames,
                                                           DetectionConfig baseConfig,
                                                           JTransientAutoTuner.AutoTuneProfile profile,
                                                           TransientEngineProgressListener listener) {
        JTransientAutoTuner.AutoTunerResult result = new JTransientAutoTuner.AutoTunerResult();
        result.optimizedConfig = baseConfig;
        Calibration calibration = calibrate(poolFrames, baseConfig, listener);
        result.calibration = calibration;
        StringBuilder report = new StringBuilder("=== JTransient Calibrated Auto-Tuning Report ===\n");
        report.append("Profile: ").append(profile.displayName()).append("\n");
        if (!calibration.success) {
            report.append(calibration.failureReason).append("\nFalling back to the base configuration.\n");
            result.telemetryReport = report.toString();
            return result;
        }
        int p = profile.ordinal();
        Candidate best = calibration.chosen[p];
        appendCalibrationReport(calibration, p, report);

        result.optimizedConfig = configFor(calibration, baseConfig, profile);
        result.success = true;
        result.bestTransientRatio = best.noisePerMpixFrame;
        result.summary = String.format(Locale.US,
                "%s profile%s%n%n" +
                        "Detection sigma %.2f, grow %.2f, min pixels %d%n" +
                        "Master sigma %.2f, master grow %.2f, master min pixels %d%n" +
                        "Mask overlap %.2f, star jitter %.2f px%n%n" +
                        "Measured on this session:%n" +
                        "- noise detections (false positives) from these settings: %.3f per megapixel per frame (noise %.3f + star leakage %.3f, or the real-frame excess if larger; budget %.2f)%n" +
                        "- all real-frame survivors: %.3f per megapixel per frame (includes cosmic rays and real objects)%n" +
                        "- synthetic test stars found: %.1f%% (bright %.0f%%); detection limit (half found) at peak SNR %s%n" +
                        "- sky hidden by the star mask: %.1f%%%n" +
                        "- star FWHM %.2f px",
                profile.displayName(), calibration.withinBudget[p] ? "" : " (no setting met the budget; the cleanest one was used)",
                best.sigma, best.growSigma, best.minPixels, best.masterSigma, best.masterGrowSigma, best.masterMinPixels,
                best.maskOverlap, calibration.jitter,
                best.falsePositivesPerMpixFrame, best.noisePerMpixFrame, best.leakagePerMpixFrame, FALSE_POSITIVE_BUDGET_PER_MPIX_FRAME[p],
                best.realSurvivorsPerMpixFrame,
                100 * best.recoveredFraction, 100 * best.brightRecovery,
                Double.isNaN(best.snr50) ? "n/a" : String.format(Locale.US, "%.2f", best.snr50),
                100 * best.maskCoverage, calibration.fwhm);
        report.append("\n=== FINAL CALIBRATED CONFIGURATION ===\n").append(result.summary).append("\n");
        result.telemetryReport = report.toString();
        return result;
    }

    /**
     * Measures noise, leakage, mask coverage and sensitivity for the whole settings grid and chooses the
     * best setting for every profile.
     */
    public static Calibration calibrate(List<ImageFrame> poolFrames,
                                        DetectionConfig baseConfig,
                                        TransientEngineProgressListener listener) {
        long start = System.currentTimeMillis();
        Calibration calibration = new Calibration();
        if (poolFrames == null || poolFrames.size() < 5) {
            calibration.failureReason = "At least 5 frames are needed for calibrated auto-tuning.";
            return calibration;
        }
        ExecutorService executor = Executors.newFixedThreadPool(Math.max(1, Runtime.getRuntime().availableProcessors()));
        try {
            List<ImageFrame> pool = new ArrayList<>();
            for (ImageFrame f : poolFrames) {
                if (FrameDriftAnalyzer.isBlankOrFailedRegistration(f.pixelData)) {
                    calibration.excludedBlankFrames++;
                } else {
                    pool.add(f);
                }
            }
            if (pool.size() < 5) {
                calibration.failureReason = "At least 5 usable frames are needed for calibrated auto-tuning.";
                return calibration;
            }
            pool.sort(Comparator.comparingInt(f -> f.sequenceIndex));
            int height = pool.get(0).pixelData.length;
            int width = pool.get(0).pixelData[0].length;

            // Frame drift: sizes the border kept clear of alignment padding, and lets detections fixed on the
            // sensor (hot pixels) be recognised although they move across the registered frames.
            FrameDriftAnalyzer.DriftAnalysisResult driftAnalysis = FrameDriftAnalyzer.analyze(pool, 0);
            Map<Integer, SourceExtractor.Pixel> driftBySequence = new HashMap<>();
            for (SourceExtractor.Pixel p : driftAnalysis.driftPoints) {
                driftBySequence.put(p.value, p);
            }
            int margin = Math.min(CROP_BORDER_MARGIN,
                    Math.max(MIN_CROP_BORDER_MARGIN, driftAnalysis.maxPaddingPixels + CROP_PADDING_CLEARANCE));
            List<int[]> crops = cropRegions(width, height, margin);
            if (crops.isEmpty()) {
                calibration.failureReason = "Frames are too small for tuning crops.";
                return calibration;
            }
            int cropSize = crops.get(0)[2];
            // On small frames the crops overlap; every pixel is counted in one crop only.
            BitSet[] owned = cropOwnership(crops, cropSize);
            double[] ownedMpix = new double[crops.size()];
            double ownedMpixPerFrame = 0;
            for (int c = 0; c < crops.size(); c++) {
                ownedMpix[c] = owned[c].cardinality() / 1_000_000.0;
                ownedMpixPerFrame += ownedMpix[c];
            }
            // Small sensors measure on more frames, so that the budgets can still be verified.
            int wantedFrames = Math.max(MEASUREMENT_FRAMES,
                    Math.min(MAX_MEASUREMENT_FRAMES, (int) Math.ceil(TARGET_MEASURED_MPIX_FRAMES / ownedMpixPerFrame)));
            List<ImageFrame> measurementFrames = evenlySpaced(pool, Math.min(wantedFrames, pool.size()));
            calibration.poolFrames = pool.size();
            calibration.measurementFrames = measurementFrames.size();
            calibration.crops = crops.size();
            calibration.cropSize = cropSize;
            progress(listener, 5, "Building pool master stacks...");

            // --- Master stacks per crop, from the whole pool, like production ---
            List<short[][]> masters = new ArrayList<>();
            for (int[] crop : crops) {
                List<ImageFrame> cropped = new ArrayList<>();
                for (ImageFrame f : pool) {
                    cropped.add(new ImageFrame(f.sequenceIndex, f.filename, crop(f.pixelData, crop), f.timestamp, f.exposureDuration));
                }
                masters.add(MasterMapGenerator.createMedianMasterStack(cropped));
            }

            // --- PSF and jitter ---
            progress(listener, 12, "Measuring star size and residual jitter...");
            List<short[][]> measurementCrops = new ArrayList<>();
            List<Integer> measurementCropIndex = new ArrayList<>();
            for (int c = 0; c < crops.size(); c++) {
                for (ImageFrame f : measurementFrames) {
                    measurementCrops.add(crop(f.pixelData, crops.get(c)));
                    measurementCropIndex.add(c);
                }
            }
            double psfSigma = measurePsfSigma(measurementCrops, baseConfig);
            calibration.fwhm = 2.355 * psfSigma;
            calibration.jitter = measureJitter(measurementCrops, measurementCropIndex, masters, baseConfig);
            double psfCoreArea = Math.PI * (calibration.fwhm / 2.0) * (calibration.fwhm / 2.0);

            // --- Negative and injected images ---
            progress(listener, 18, "Preparing noise and injection images...");
            Random random = new Random(RANDOM_SEED);
            // Frame drift, so that detections fixed on the sensor (hot pixels) can be recognised although they
            // move across the registered frames.
            List<CropFrame> cropFrames = new ArrayList<>();
            for (int i = 0; i < measurementCrops.size(); i++) {
                int c = measurementCropIndex.get(i);
                CropFrame prepared = prepareCropFrame(c, measurementCrops.get(i), masters.get(c), psfSigma, baseConfig, random);
                SourceExtractor.Pixel drift = driftBySequence.get(measurementFrames.get(i % measurementFrames.size()).sequenceIndex);
                if (drift != null) {
                    prepared.driftX = drift.x;
                    prepared.driftY = drift.y;
                }
                // Blank (failed registration) or sky-clipped crops have no usable noise and would distort every measurement.
                if (prepared.backgroundSigma >= MIN_BACKGROUND_SIGMA) {
                    cropFrames.add(prepared);
                }
            }
            if (cropFrames.size() < Math.max(2, measurementCrops.size() / 2)) {
                calibration.failureReason = String.format(Locale.US,
                        "Only %d of %d measurement crops have a usable sky background (noise above %.0f ADU). "
                                + "The frames look blank or background-subtracted with the sky clipped at zero; calibration is not possible.",
                        cropFrames.size(), measurementCrops.size(), MIN_BACKGROUND_SIGMA);
                return calibration;
            }
            calibration.skippedCrops = measurementCrops.size() - cropFrames.size();

            // --- Masks for every master setting ---
            List<FrameSetting> frameSettings = frameSettings(psfCoreArea);
            List<MasterSetting> masterSettings = masterSettings(psfCoreArea);
            int dilation = (int) Math.max(1, Math.round(calibration.jitter / 2.0)); // production veto-mask rule
            BitSet[][] masks = new BitSet[masterSettings.size()][crops.size()];
            double[] coverage = new double[masterSettings.size()];
            for (int m = 0; m < masterSettings.size(); m++) {
                long masked = 0;
                for (int c = 0; c < crops.size(); c++) {
                    masks[m][c] = buildMask(masters.get(c), masterSettings.get(m), dilation, baseConfig);
                    masked += masks[m][c].cardinality();
                }
                coverage[m] = masked / ((double) crops.size() * cropSize * cropSize);
            }
            int deepestMask = 0;
            for (int m = 1; m < coverage.length; m++) {
                if (coverage[m] > coverage[deepestMask]) deepestMask = m;
            }
            // Pixels next to any star (deepest mask grown by one FWHM): a survivor there that comes back beside
            // the same star in another frame is star leakage, even when a shallower mask leaves it untouched.
            int nearStarRadius = (int) Math.max(2, Math.round(calibration.fwhm));
            BitSet[] nearStar = new BitSet[crops.size()];
            for (int c = 0; c < crops.size(); c++) {
                nearStar[c] = dilate(masks[deepestMask][c], cropSize, nearStarRadius);
            }
            // Satellite and meteor trails, found once per frame by a more sensitive streak pass.
            List<Callable<BitSet>> streakTasks = new ArrayList<>();
            for (CropFrame cf : cropFrames) {
                BitSet starMask = masks[deepestMask][cf.crop];
                streakTasks.add(() -> streakCorridor(cf.positive, starMask, nearStarRadius, baseConfig));
            }
            List<Future<BitSet>> corridors = executor.invokeAll(streakTasks);
            for (int i = 0; i < cropFrames.size(); i++) {
                cropFrames.get(i).streakCorridor = corridors.get(i).get();
            }

            double measuredMpixFrames = 0;
            for (CropFrame cf : cropFrames) {
                measuredMpixFrames += ownedMpix[cf.crop];
            }
            calibration.measuredMpixFrames = measuredMpixFrames;
            double recurrenceRadius = Math.max(1.5, calibration.jitter);
            double matchRadius = Math.max(2.0, 1.5 * psfSigma);
            double nearStarRecurrenceRadius = Math.max(recurrenceRadius, calibration.fwhm);

            // --- Grid ---
            for (int s = 0; s < frameSettings.size(); s++) {
                FrameSetting fs = frameSettings.get(s);
                progress(listener, 20 + (int) (75.0 * s / frameSettings.size()),
                        String.format(Locale.US, "Measuring sigma %.2f, grow %.2f, minPix %d", fs.sigma, fs.grow, fs.minPixels));
                List<Detection[][]> detections = extractAll(cropFrames, fs, baseConfig, executor, cropSize);

                // Settings that already drown in noise before any veto can never meet a budget; skip the costly
                // per-mask evaluation for them (noisy data at low thresholds produces huge detection counts).
                // Lowest noise any master could leave: negative detections surviving the deepest mask at the most
                // permissive overlap value.
                long minimumNoise = 0;
                double maxOverlap = Arrays.stream(MASK_OVERLAPS_TO_TEST).max().orElse(1.0);
                for (int i = 0; i < cropFrames.size(); i++) {
                    BitSet deepest = masks[deepestMask][cropFrames.get(i).crop];
                    BitSet own = owned[cropFrames.get(i).crop];
                    for (Detection d : detections.get(i)[1]) {
                        if (!d.streak && isOwned(d, own, cropSize) && overlap(d, deepest) <= maxOverlap) minimumNoise++;
                    }
                }
                double maxBudget = Arrays.stream(FALSE_POSITIVE_BUDGET_PER_MPIX_FRAME).max().orElse(1.0);
                if (minimumNoise / measuredMpixFrames > RAW_NOISE_SKIP_FACTOR * maxBudget) {
                    calibration.skippedFrameSettings++;
                    continue;
                }

                // Every master setting is tried with every per-frame setting: a master shallower than the
                // per-frame grow threshold hides less sky but lets star wings leak, which is measured.
                List<Callable<List<Candidate>>> masterTasks = new ArrayList<>();
                for (int m = 0; m < masterSettings.size(); m++) {
                    final int mi = m;
                    masterTasks.add(() -> {
                        MasterSetting ms = masterSettings.get(mi);
                        // Overlap of every detection with this master's mask, computed once for all overlap values.
                        List<double[][]> overlaps = new ArrayList<>();
                        for (int i = 0; i < cropFrames.size(); i++) {
                            BitSet mask = masks[mi][cropFrames.get(i).crop];
                            Detection[][] d = detections.get(i);
                            double[][] o = new double[3][];
                            for (int t = 0; t < 3; t++) {
                                o[t] = new double[d[t].length];
                                for (int k = 0; k < d[t].length; k++) {
                                    o[t][k] = overlap(d[t][k], mask);
                                }
                            }
                            overlaps.add(o);
                        }
                        List<Candidate> out = new ArrayList<>();
                        for (double ov : MASK_OVERLAPS_TO_TEST) {
                            out.add(evaluate(fs, ms, ov, coverage[mi], cropFrames, detections, overlaps,
                                    owned, ownedMpix, cropSize, crops.size(), nearStar, recurrenceRadius, nearStarRecurrenceRadius,
                                    matchRadius));
                        }
                        return out;
                    });
                }
                for (Future<List<Candidate>> f : executor.invokeAll(masterTasks)) {
                    calibration.candidates.addAll(f.get());
                }
            }

            // --- Settings-dependent false positives ---
            // Every setting detects some real single-frame events (cosmic rays, real objects). The lowest
            // real-survivor rate over all settings is that floor; anything above it in real frames (trail pieces
            // already removed by the streak pass), or the noise + leakage measured directly if larger, is caused by
            // the settings.
            double floor = Double.MAX_VALUE;
            for (Candidate c : calibration.candidates) {
                floor = Math.min(floor, c.realSurvivorsPerMpixFrame);
            }
            calibration.singleFrameFloorPerMpixFrame = floor;
            for (Candidate c : calibration.candidates) {
                double excess = Math.max(0.0, c.realSurvivorsPerMpixFrame - floor);
                c.falsePositivesPerMpixFrame = Math.max(c.noisePerMpixFrame + c.leakagePerMpixFrame, excess);
                c.falsePositiveUpperPerMpixFrame = (c.falsePositivesPerMpixFrame * calibration.measuredMpixFrames + 1.0)
                        / calibration.measuredMpixFrames;
            }

            // --- Selection per profile ---
            progress(listener, 96, "Selecting settings per profile...");
            for (int p = 0; p < PROFILE_COUNT; p++) {
                calibration.budgetVerifiable[p] = FALSE_POSITIVE_BUDGET_PER_MPIX_FRAME[p] * calibration.measuredMpixFrames >= 1.0;
                Candidate chosen = select(calibration.candidates,
                        effectiveBudget(FALSE_POSITIVE_BUDGET_PER_MPIX_FRAME[p], calibration.measuredMpixFrames));
                calibration.withinBudget[p] = chosen != null;
                calibration.chosen[p] = chosen != null ? chosen : cleanest(calibration.candidates);
            }
            calibration.success = calibration.chosen[0] != null;
            if (!calibration.success) {
                calibration.failureReason = "No combination could be evaluated.";
            }
            calibration.elapsedSeconds = (System.currentTimeMillis() - start) / 1000.0;
            progress(listener, 100, "Calibrated auto-tuning complete.");
            return calibration;
        } catch (Exception e) {
            calibration.success = false;
            calibration.failureReason = "Calibrated auto-tuning failed: " + e;
            return calibration;
        } finally {
            executor.shutdownNow();
        }
    }

    private static void appendCalibrationReport(Calibration calibration, int profileIndex, StringBuilder report) {
        report.append(String.format(Locale.US, "Pool frames for the master: %d | measurement frames: %d | crops: %d x %dx%d px%n",
                calibration.poolFrames, calibration.measurementFrames, calibration.crops, calibration.cropSize, calibration.cropSize));
        report.append(String.format(Locale.US, "Measured star FWHM: %.2f px | residual jitter: %.2f px%n", calibration.fwhm, calibration.jitter));
        report.append(String.format(Locale.US, "Evaluated %d combinations in %.1f s.%n%n", calibration.candidates.size(), calibration.elapsedSeconds));
        JTransientAutoTuner.AutoTuneProfile[] names = JTransientAutoTuner.AutoTuneProfile.values();
        report.append(String.format(Locale.US, "Noise and leakage measured over %.1f megapixel-frames.%n", calibration.measuredMpixFrames));
        if (calibration.skippedFrameSettings > 0) {
            report.append(String.format(Locale.US, "Skipped %d detection settings that were far too noisy for any budget.%n", calibration.skippedFrameSettings));
        }
        if (calibration.excludedBlankFrames > 0) {
            report.append(String.format(Locale.US, "Left out %d blank or failed-registration frames.%n", calibration.excludedBlankFrames));
        }
        if (calibration.skippedCrops > 0) {
            report.append(String.format(Locale.US, "Skipped %d measurement crops with a blank or clipped background.%n", calibration.skippedCrops));
        }
        report.append(String.format(Locale.US, "Single-frame events every setting detects (cosmic rays, real objects): %.3f per MPix per frame; not counted against the budget.%n",
                calibration.singleFrameFloorPerMpixFrame));
        report.append("--- CHOICE PER PROFILE (budget: noise detections per MPix per frame) ---\n");
        for (int p = 0; p < PROFILE_COUNT; p++) {
            Candidate c = calibration.chosen[p];
            report.append(String.format(Locale.US, "%s%s (budget %.2f)%s%n   %s%n   %s%n",
                    p == profileIndex ? "* " : "  ", names[p].displayName(), FALSE_POSITIVE_BUDGET_PER_MPIX_FRAME[p],
                    (calibration.withinBudget[p] ? "" : " [NO SETTING WITHIN BUDGET - cleanest setting used]")
                            + (calibration.budgetVerifiable[p] ? "" : " [area too small to confirm this budget - zero false positives accepted]"),
                    c.settingsText(), c.measurementText()));
        }
        appendSensitivityLadder(calibration.candidates,
                effectiveBudget(FALSE_POSITIVE_BUDGET_PER_MPIX_FRAME[profileIndex], calibration.measuredMpixFrames), report);
        appendCompletenessCurve(calibration.chosen[profileIndex], report);
    }

    // =========================================================================
    // Measurements
    // =========================================================================

    /**
     * Pixels within {@code radius} of a satellite or meteor trail in one real frame crop. The crop is extracted
     * once more at {@link #STREAK_PASS_SIGMA} / {@link #STREAK_PASS_GROW}, which joins the fragments of a faint
     * trail into one elongated object, and the pipeline's own streak test decides what is a streak. Streaks that
     * lie mostly inside the star mask are merged chains of stars and are ignored.
     */
    static BitSet streakCorridor(short[][] image, BitSet starMask, int radius, DetectionConfig base) {
        int size = image.length;
        DetectionConfig cfg = base.clone();
        cfg.detectionSigmaMultiplier = STREAK_PASS_SIGMA;
        cfg.growSigmaMultiplier = STREAK_PASS_GROW;
        BitSet streaks = new BitSet(size * size);
        for (SourceExtractor.DetectedObject obj
                : SourceExtractor.extractSources(image, STREAK_PASS_SIGMA, cfg.minDetectionPixels, cfg).objects) {
            if (!obj.isStreak || obj.rawPixels == null || obj.rawPixels.isEmpty()) continue;
            int inStars = 0;
            for (SourceExtractor.Pixel p : obj.rawPixels) {
                if (starMask.get(p.y * size + p.x)) inStars++;
            }
            if (inStars > STREAK_MAX_STAR_OVERLAP * obj.rawPixels.size()) continue;
            for (SourceExtractor.Pixel p : obj.rawPixels) {
                streaks.set(p.y * size + p.x);
            }
        }
        return dilate(streaks, size, radius);
    }

    /** Pixels of every crop that no earlier crop covers. */
    private static BitSet[] cropOwnership(List<int[]> crops, int cropSize) {
        BitSet[] owned = new BitSet[crops.size()];
        for (int c = 0; c < crops.size(); c++) {
            int[] crop = crops.get(c);
            owned[c] = new BitSet(cropSize * cropSize);
            for (int y = 0; y < cropSize; y++) {
                for (int x = 0; x < cropSize; x++) {
                    int gx = crop[0] + x;
                    int gy = crop[1] + y;
                    boolean earlier = false;
                    for (int e = 0; e < c && !earlier; e++) {
                        int[] other = crops.get(e);
                        earlier = gx >= other[0] && gx < other[0] + cropSize && gy >= other[1] && gy < other[1] + cropSize;
                    }
                    if (!earlier) owned[c].set(y * cropSize + x);
                }
            }
        }
        return owned;
    }

    private static boolean isOwned(Detection d, BitSet owned, int cropSize) {
        int x = Math.min(cropSize - 1, Math.max(0, (int) Math.round(d.x)));
        int y = Math.min(cropSize - 1, Math.max(0, (int) Math.round(d.y)));
        return owned.get(y * cropSize + x);
    }

    private static Candidate evaluate(FrameSetting fs, MasterSetting ms, double ov, double coverage,
                                      List<CropFrame> cropFrames, List<Detection[][]> detections, List<double[][]> overlaps,
                                      BitSet[] owned, double[] ownedMpix, int cropSize, int cropCount,
                                      BitSet[] nearStar, double recurrenceRadius,
                                      double nearStarRecurrenceRadius, double matchRadius) {
        int levels = INJECTION_PEAK_SNR.length;
        long noise = 0;
        long realSurvivors = 0;
        long starEdgeSurvivors = 0;
        long trailFragments = 0;
        long recurring = 0;
        long[] injected = new long[levels];
        long[] recovered = new long[levels];

        // Real-frame survivors per crop and frame, for the recurrence (leakage) test.
        List<List<double[]>>[] survivorsByCrop = new List[cropCount];
        for (int c = 0; c < cropCount; c++) {
            survivorsByCrop[c] = new ArrayList<>();
        }

        for (int i = 0; i < cropFrames.size(); i++) {
            CropFrame cf = cropFrames.get(i);
            Detection[][] d = detections.get(i);
            double[][] o = overlaps.get(i);

            BitSet own = owned[cf.crop];
            // Noise: negative-image detections that survive the veto.
            for (int k = 0; k < d[1].length; k++) {
                if (!d[1][k].streak && o[1][k] <= ov && isOwned(d[1][k], own, cropSize)) noise++;
            }
            // Real-frame survivors. Pieces of satellite or meteor trails (found by the streak pass) are real
            // objects, not false positives; several trails crossing the same place would otherwise also count as
            // recurring leakage.
            List<double[]> survivors = new ArrayList<>();
            for (int k = 0; k < d[0].length; k++) {
                if (d[0][k].streak || o[0][k] > ov || !isOwned(d[0][k], own, cropSize)) continue;
                if (isOwned(d[0][k], cf.streakCorridor, cropSize)) {
                    trailFragments++;
                    continue;
                }
                survivors.add(new double[]{d[0][k].x, d[0][k].y, o[0][k],
                        d[0][k].x - cf.driftX, d[0][k].y - cf.driftY,
                        isOwned(d[0][k], nearStar[cf.crop], cropSize) ? 1 : 0});
                realSurvivors++;
                if (o[0][k] > 0) starEdgeSurvivors++;
            }
            survivorsByCrop[cf.crop].add(survivors);
            // Injection recovery.
            for (int s = 0; s < cf.injectX.length; s++) {
                injected[cf.injectLevel[s]]++;
                double r2 = matchRadius * matchRadius;
                for (int k = 0; k < d[2].length; k++) {
                    if (d[2][k].streak || o[2][k] > ov) continue;
                    double dx = d[2][k].x - cf.injectX[s];
                    double dy = d[2][k].y - cf.injectY[s];
                    if (dx * dx + dy * dy <= r2) {
                        recovered[cf.injectLevel[s]]++;
                        break;
                    }
                }
            }
        }
        // Leakage: survivors that touch the mask, reappear within the recurrence radius in another frame of the
        // same crop (on the sky, or on the sensor for hot pixels), or lie next to a star and reappear beside it
        // (within one FWHM) in another frame. A moving object passing near a star moves on by more than that and
        // is not charged.
        double rr = recurrenceRadius * recurrenceRadius;
        // The drift is measured in whole pixels and ignores small rotations: allow a little more on the sensor.
        double sensorRadius = recurrenceRadius + SENSOR_RECURRENCE_MARGIN;
        double sensorRr = sensorRadius * sensorRadius;
        double nearRr = nearStarRecurrenceRadius * nearStarRecurrenceRadius;
        for (int c = 0; c < cropCount; c++) {
            List<List<double[]>> frames = survivorsByCrop[c];
            for (int a = 0; a < frames.size(); a++) {
                for (double[] p : frames.get(a)) {
                    // Same place on the sky (star leakage) or on the sensor (hot pixels that follow the drift).
                    boolean repeats = false;
                    boolean repeatsBesideStar = false;
                    for (int b = 0; b < frames.size() && !repeats; b++) {
                        if (b == a) continue;
                        for (double[] q : frames.get(b)) {
                            double dx = p[0] - q[0];
                            double dy = p[1] - q[1];
                            double sx = p[3] - q[3];
                            double sy = p[4] - q[4];
                            double d2 = dx * dx + dy * dy;
                            // The sensor test only adds something between frames whose drift differs (it then
                            // allows for whole-pixel drift and rotation); otherwise it would catch slow movers.
                            boolean driftDiffers = Math.abs(dx - sx) + Math.abs(dy - sy) > 0.5;
                            if (d2 <= rr || (driftDiffers && sx * sx + sy * sy <= sensorRr)) {
                                repeats = true;
                                break;
                            }
                            if (p[5] > 0 && d2 <= nearRr) repeatsBesideStar = true;
                        }
                    }
                    if (repeats || repeatsBesideStar || p[2] > 0) recurring++;
                }
            }
        }

        Candidate cand = new Candidate();
        cand.sigma = fs.sigma;
        cand.growSigma = fs.grow;
        cand.minPixels = fs.minPixels;
        cand.masterSigma = ms.seed;
        cand.masterGrowSigma = ms.grow;
        cand.masterMinPixels = ms.minPixels;
        cand.maskOverlap = ov;
        double frameMpix = 0;
        for (CropFrame cf : cropFrames) {
            frameMpix += ownedMpix[cf.crop];
        }
        cand.noisePerMpixFrame = noise / frameMpix;
        cand.leakagePerMpixFrame = recurring / frameMpix;
        cand.realSurvivorsPerMpixFrame = realSurvivors / frameMpix;
        cand.starEdgeSurvivorsPerMpixFrame = starEdgeSurvivors / frameMpix;
        cand.trailFragmentsPerMpixFrame = trailFragments / frameMpix;
        cand.falsePositivesPerMpixFrame = (noise + recurring) / frameMpix;
        cand.falsePositiveUpperPerMpixFrame = (noise + recurring + 1) / frameMpix;
        cand.maskCoverage = coverage;
        cand.completeness = new double[levels];
        double sum = 0;
        for (int l = 0; l < levels; l++) {
            cand.completeness[l] = injected[l] > 0 ? recovered[l] / (double) injected[l] : 0;
            sum += cand.completeness[l];
        }
        cand.recoveredFraction = sum / levels;
        double bright = 0;
        int brightLevels = 0;
        for (int l = 0; l < levels; l++) {
            if (INJECTION_PEAK_SNR[l] >= BRIGHT_LEVEL_SNR) {
                bright += cand.completeness[l];
                brightLevels++;
            }
        }
        cand.brightRecovery = brightLevels > 0 ? bright / brightLevels : 0;
        cand.snr50 = halfRecoveryLevel(cand.completeness);
        return cand;
    }

    private static List<Detection[][]> extractAll(List<CropFrame> cropFrames, FrameSetting fs, DetectionConfig base,
                                                  ExecutorService executor, int width) throws Exception {
        DetectionConfig cfg = base.clone();
        cfg.detectionSigmaMultiplier = fs.sigma;
        cfg.growSigmaMultiplier = fs.grow;
        cfg.minDetectionPixels = fs.minPixels;
        List<Callable<Detection[][]>> tasks = new ArrayList<>();
        for (CropFrame cf : cropFrames) {
            tasks.add(() -> new Detection[][]{
                    extract(cf.positive, fs, cfg, width),
                    extract(cf.negative, fs, cfg, width),
                    extract(cf.injected, fs, cfg, width)});
        }
        List<Detection[][]> out = new ArrayList<>();
        for (Future<Detection[][]> f : executor.invokeAll(tasks)) {
            out.add(f.get());
        }
        return out;
    }

    private static Detection[] extract(short[][] image, FrameSetting fs, DetectionConfig cfg, int width) {
        List<SourceExtractor.DetectedObject> objects = SourceExtractor.extractSources(image, fs.sigma, fs.minPixels, cfg).objects;
        Detection[] out = new Detection[objects.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = new Detection(objects.get(i), width);
        }
        return out;
    }

    private static double overlap(Detection d, BitSet mask) {
        if (d.pixels.length == 0) {
            return 0;
        }
        int inside = 0;
        for (int idx : d.pixels) {
            if (idx >= 0 && mask.get(idx)) inside++;
        }
        return inside / (double) d.pixels.length;
    }

    private static BitSet buildMask(short[][] master, MasterSetting ms, int dilation, DetectionConfig base) {
        DetectionConfig cfg = base.clone();
        cfg.masterSigmaMultiplier = ms.seed;
        cfg.growSigmaMultiplier = ms.grow;
        cfg.edgeMarginPixels = 5;
        cfg.voidProximityRadius = 5;
        int h = master.length;
        int w = master[0].length;
        BitSet mask = new BitSet(w * h);
        List<SourceExtractor.DetectedObject> stars = SourceExtractor.extractSources(master, ms.seed, ms.minPixels, cfg).objects;
        for (SourceExtractor.DetectedObject star : stars) {
            if (star.rawPixels == null) continue;
            for (SourceExtractor.Pixel p : star.rawPixels) {
                for (int dy = -dilation; dy <= dilation; dy++) {
                    for (int dx = -dilation; dx <= dilation; dx++) {
                        if (dx * dx + dy * dy > dilation * dilation) continue;
                        int x = p.x + dx;
                        int y = p.y + dy;
                        if (x >= 0 && y >= 0 && x < w && y < h) mask.set(y * w + x);
                    }
                }
            }
        }
        return mask;
    }

    /** Grows a square mask of side {@code size} by {@code radius} pixels (square neighbourhood, separable). */
    private static BitSet dilate(BitSet mask, int size, int radius) {
        BitSet rows = new BitSet(size * size);
        for (int i = mask.nextSetBit(0); i >= 0; i = mask.nextSetBit(i + 1)) {
            int y = i / size;
            int x = i % size;
            rows.set(y * size + Math.max(0, x - radius), y * size + Math.min(size - 1, x + radius) + 1);
        }
        BitSet out = new BitSet(size * size);
        for (int i = rows.nextSetBit(0); i >= 0; i = rows.nextSetBit(i + 1)) {
            int y = i / size;
            int x = i % size;
            for (int yy = Math.max(0, y - radius); yy <= Math.min(size - 1, y + radius); yy++) {
                out.set(yy * size + x);
            }
        }
        return out;
    }

    /**
     * Builds the negative image (2 x master - frame) and a copy of the frame with synthetic point sources
     * spread over the injection levels, placed on a jittered grid away from the crop edges.
     */
    private static CropFrame prepareCropFrame(int crop, short[][] positive, short[][] master, double psfSigma,
                                              DetectionConfig base, Random random) {
        int h = positive.length;
        int w = positive[0].length;
        short[][] negative = new short[h][w];
        short[][] injected = new short[h][w];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int f = PixelEncoding.toShiftedPositiveInt(positive[y][x]);
                int m = PixelEncoding.toShiftedPositiveInt(master[y][x]);
                negative[y][x] = PixelEncoding.fromShiftedPositiveInt(clamp(2 * m - f));
                injected[y][x] = positive[y][x];
            }
        }
        double backgroundSigma = SourceExtractor.calculateBackgroundSigmaClipped(positive, w, h, 3.0, base).sigma;

        int levels = INJECTION_PEAK_SNR.length;
        int count = levels * INJECTIONS_PER_LEVEL_PER_CROP_FRAME;
        int margin = Math.max(30, base.edgeMarginPixels + 10);
        int cols = (int) Math.ceil(Math.sqrt(count * (w - 2.0 * margin) / Math.max(1.0, h - 2.0 * margin)));
        int rows = (int) Math.ceil(count / (double) cols);
        double cellW = (w - 2.0 * margin) / cols;
        double cellH = (h - 2.0 * margin) / rows;
        double[] xs = new double[count];
        double[] ys = new double[count];
        int[] lv = new int[count];
        int radius = (int) Math.ceil(4 * psfSigma);
        for (int s = 0; s < count; s++) {
            int col = s % cols;
            int row = s / cols;
            xs[s] = margin + (col + 0.2 + 0.6 * random.nextDouble()) * cellW;
            ys[s] = margin + (row + 0.2 + 0.6 * random.nextDouble()) * cellH;
            lv[s] = s % levels;
            double amplitude = INJECTION_PEAK_SNR[lv[s]] * backgroundSigma;
            int cx = (int) Math.round(xs[s]);
            int cy = (int) Math.round(ys[s]);
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dx = -radius; dx <= radius; dx++) {
                    int x = cx + dx;
                    int y = cy + dy;
                    if (x < 0 || y < 0 || x >= w || y >= h) continue;
                    double px = x - xs[s];
                    double py = y - ys[s];
                    double v = amplitude * Math.exp(-(px * px + py * py) / (2 * psfSigma * psfSigma));
                    int value = PixelEncoding.toShiftedPositiveInt(injected[y][x]) + (int) Math.round(v);
                    injected[y][x] = PixelEncoding.fromShiftedPositiveInt(clamp(value));
                }
            }
        }
        CropFrame frame = new CropFrame(crop, positive, negative, injected, xs, ys, lv);
        frame.backgroundSigma = backgroundSigma;
        return frame;
    }

    /**
     * PSF Gaussian sigma from second moments of bright, isolated, unsaturated stars.
     */
    private static double measurePsfSigma(List<short[][]> images, DetectionConfig base) {
        DetectionConfig probe = base.clone();
        probe.growSigmaMultiplier = 3.0;
        List<Double> sigmas = new ArrayList<>();
        for (short[][] image : images) {
            int h = image.length;
            int w = image[0].length;
            double background = SourceExtractor.calculateBackgroundSigmaClipped(image, w, h, 3.0, base).median;
            List<SourceExtractor.DetectedObject> objects = SourceExtractor.extractSources(image, 5.0, 5, probe).objects;
            for (SourceExtractor.DetectedObject obj : objects) {
                if (obj.isStreak || obj.elongation > 1.3 || obj.peakSigma < 20 || obj.peakSigma > 150) continue;
                double window = Math.max(4.0, 2.0 * obj.fwhm);
                boolean isolated = true;
                for (SourceExtractor.DetectedObject other : objects) {
                    if (other != obj && Math.hypot(other.x - obj.x, other.y - obj.y) < 2 * window) {
                        isolated = false;
                        break;
                    }
                }
                if (!isolated) continue;
                int r = (int) Math.ceil(window);
                int x0 = (int) Math.round(obj.x);
                int y0 = (int) Math.round(obj.y);
                if (x0 - r < 0 || y0 - r < 0 || x0 + r >= w || y0 + r >= h) continue;
                // FWHM from the area above half the peak: the core width, which is what a faint source shows
                // above the detection threshold. (Second moments include the PSF wings and overstate it.)
                double peak = 0;
                for (int y = y0 - r; y <= y0 + r; y++) {
                    for (int x = x0 - r; x <= x0 + r; x++) {
                        if (Math.hypot(x - obj.x, y - obj.y) > window) continue;
                        peak = Math.max(peak, PixelEncoding.toShiftedPositiveInt(image[y][x]) - background);
                    }
                }
                if (peak <= 0) continue;
                int aboveHalf = 0;
                for (int y = y0 - r; y <= y0 + r; y++) {
                    for (int x = x0 - r; x <= x0 + r; x++) {
                        if (Math.hypot(x - obj.x, y - obj.y) > window) continue;
                        if (PixelEncoding.toShiftedPositiveInt(image[y][x]) - background >= 0.5 * peak) aboveHalf++;
                    }
                }
                double fwhm = 2.0 * Math.sqrt(aboveHalf / Math.PI);
                if (fwhm > 0.5) sigmas.add(fwhm / 2.355);
                if (sigmas.size() >= 400) break;
            }
        }
        if (sigmas.isEmpty()) {
            return 1.5;
        }
        Collections.sort(sigmas);
        return sigmas.get(sigmas.size() / 2);
    }

    /**
     * Residual registration jitter: 2 x the 90th percentile of reciprocal nearest-neighbour distances
     * between master-stack stars and frame stars (same rule as the legacy tuner), at least 1 px.
     */
    private static double measureJitter(List<short[][]> images, List<Integer> cropOfImage, List<short[][]> masters,
                                        DetectionConfig base) {
        DetectionConfig probe = base.clone();
        probe.growSigmaMultiplier = 3.0;
        probe.edgeMarginPixels = 5;
        probe.voidProximityRadius = 5;
        List<List<SourceExtractor.DetectedObject>> masterStars = new ArrayList<>();
        for (short[][] master : masters) {
            DetectionConfig mc = probe.clone();
            mc.growSigmaMultiplier = base.masterSigmaMultiplier;
            masterStars.add(usable(SourceExtractor.extractSources(master, base.masterSigmaMultiplier, base.masterMinDetectionPixels, mc).objects));
        }
        List<Double> distances = new ArrayList<>();
        for (int i = 0; i < images.size(); i++) {
            List<SourceExtractor.DetectedObject> frameStars = usable(SourceExtractor.extractSources(images.get(i), 4.0, 5, probe).objects);
            List<SourceExtractor.DetectedObject> reference = masterStars.get(cropOfImage.get(i));
            for (SourceExtractor.DetectedObject star : reference) {
                SourceExtractor.DetectedObject match = nearest(star, frameStars);
                if (match == null) continue;
                double d = Math.hypot(match.x - star.x, match.y - star.y);
                if (d > 4.0 || nearest(match, reference) != star) continue;
                distances.add(d);
            }
        }
        if (distances.size() < 8) {
            return 1.5;
        }
        Collections.sort(distances);
        double p = distances.get((int) Math.round(JITTER_PERCENTILE * (distances.size() - 1)));
        return Math.max(MIN_JITTER, JITTER_SAFETY_MULTIPLIER * p);
    }

    private static List<SourceExtractor.DetectedObject> usable(List<SourceExtractor.DetectedObject> objects) {
        List<SourceExtractor.DetectedObject> out = new ArrayList<>();
        for (SourceExtractor.DetectedObject o : objects) {
            if (o.rawPixels != null && o.rawPixels.size() >= 5 && o.elongation <= 2.5) out.add(o);
        }
        // Sort by x so nearest() can scan a narrow band.
        out.sort(Comparator.comparingDouble(o -> o.x));
        return out;
    }

    /** Nearest object by centroid; the list must be sorted by x. */
    private static SourceExtractor.DetectedObject nearest(SourceExtractor.DetectedObject source,
                                                          List<SourceExtractor.DetectedObject> sortedByX) {
        int lo = 0;
        int hi = sortedByX.size();
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (sortedByX.get(mid).x < source.x) lo = mid + 1; else hi = mid;
        }
        SourceExtractor.DetectedObject best = null;
        double bestDist = Double.MAX_VALUE;
        for (int dir = -1; dir <= 1; dir += 2) {
            for (int i = dir < 0 ? lo - 1 : lo; i >= 0 && i < sortedByX.size(); i += dir) {
                SourceExtractor.DetectedObject c = sortedByX.get(i);
                if (Math.abs(c.x - source.x) > Math.min(bestDist, 10.0)) break;
                double d = Math.hypot(c.x - source.x, c.y - source.y);
                if (d < bestDist) {
                    bestDist = d;
                    best = c;
                }
            }
        }
        return best;
    }

    // =========================================================================
    // Selection and reporting
    // =========================================================================

    /**
     * Within the budgets (checked against the cautious estimates), the candidate recovering the most
     * synthetic sources. Candidates within {@link #TIE_EPSILON} of the best recovery are treated as equal;
     * among them the one keeping the most bright sources wins, then fewer noise detections, then the
     * stricter detection sigma.
     */
    private static Candidate select(List<Candidate> candidates, double budget) {
        double bestRecovery = -1;
        for (Candidate c : candidates) {
            if (withinBudget(c, budget)) {
                bestRecovery = Math.max(bestRecovery, c.recoveredFraction);
            }
        }
        if (bestRecovery < 0) {
            return null;
        }
        Candidate best = null;
        for (Candidate c : candidates) {
            if (!withinBudget(c, budget) || c.recoveredFraction < bestRecovery - TIE_EPSILON) {
                continue;
            }
            if (best == null || isPreferred(c, best)) {
                best = c;
            }
        }
        return best;
    }

    /**
     * A budget smaller than one event over the measured area cannot be confirmed; it is relaxed just enough
     * to accept candidates with no events at all.
     */
    private static double effectiveBudget(double budget, double measuredMpixFrames) {
        return measuredMpixFrames > 0 ? Math.max(budget, (1.0 + 1e-9) / measuredMpixFrames) : budget;
    }

    private static boolean withinBudget(Candidate c, double budget) {
        return c.falsePositiveUpperPerMpixFrame <= budget;
    }

    private static boolean isPreferred(Candidate c, Candidate best) {
        if (Math.abs(c.brightRecovery - best.brightRecovery) > TIE_EPSILON) {
            return c.brightRecovery > best.brightRecovery;
        }
        double cNoise = c.falsePositivesPerMpixFrame;
        double bNoise = best.falsePositivesPerMpixFrame;
        if (Math.abs(cNoise - bNoise) > 1e-9) {
            return cNoise < bNoise;
        }
        if (c.sigma != best.sigma) {
            return c.sigma > best.sigma;
        }
        return c.recoveredFraction > best.recoveredFraction;
    }

    private static Candidate cleanest(List<Candidate> candidates) {
        Candidate best = null;
        for (Candidate c : candidates) {
            if (best == null || c.falsePositivesPerMpixFrame < best.falsePositivesPerMpixFrame) best = c;
        }
        return best;
    }

    /** Best candidate within the budget at each detection sigma, to show the sensitivity-noise trade-off. */
    private static void appendSensitivityLadder(List<Candidate> candidates, double budget,
                                                StringBuilder report) {
        report.append("\n--- BEST SETTING PER DETECTION SIGMA (within the selected profile's budget) ---\n");
        for (double sigma : SIGMAS_TO_TEST) {
            List<Candidate> atSigma = new ArrayList<>();
            for (Candidate c : candidates) {
                if (c.sigma == sigma) atSigma.add(c);
            }
            Candidate best = select(atSigma, budget);
            if (best == null) {
                Candidate clean = cleanest(atSigma);
                report.append(String.format(Locale.US, "sigma %.2f: none within budget (cleanest: noise %.3f, leakage %.3f)%n",
                        sigma, clean == null ? Double.NaN : clean.noisePerMpixFrame, clean == null ? Double.NaN : clean.leakagePerMpixFrame));
            } else {
                report.append(String.format(Locale.US, "sigma %.2f: %s | %s%n", sigma, best.settingsText(), best.measurementText()));
            }
        }
    }

    private static void appendCompletenessCurve(Candidate best, StringBuilder report) {
        report.append("\n--- RECOVERY OF SYNTHETIC SOURCES (chosen setting) ---\n");
        for (int l = 0; l < INJECTION_PEAK_SNR.length; l++) {
            report.append(String.format(Locale.US, "peak SNR %5.1f: %5.1f%%%n", INJECTION_PEAK_SNR[l], 100 * best.completeness[l]));
        }
    }

    private static double halfRecoveryLevel(double[] completeness) {
        for (int l = 0; l < completeness.length; l++) {
            if (completeness[l] >= 0.5) {
                if (l == 0) return INJECTION_PEAK_SNR[0];
                double c0 = completeness[l - 1];
                double c1 = completeness[l];
                double t = (0.5 - c0) / Math.max(1e-9, c1 - c0);
                return INJECTION_PEAK_SNR[l - 1] + t * (INJECTION_PEAK_SNR[l] - INJECTION_PEAK_SNR[l - 1]);
            }
        }
        return Double.NaN;
    }

    // =========================================================================
    // Grid and helpers
    // =========================================================================

    static List<FrameSetting> frameSettings(double psfCoreArea) {
        List<Integer> minPixels = new ArrayList<>();
        for (double f : MIN_PIXEL_AREA_FACTORS) {
            int mp = Math.min(MAX_MIN_PIXELS, Math.max(3, (int) Math.round(f * psfCoreArea)));
            if (!minPixels.contains(mp)) minPixels.add(mp);
        }
        List<FrameSetting> out = new ArrayList<>();
        for (double sigma : SIGMAS_TO_TEST) {
            for (double delta : GROW_DELTAS_TO_TEST) {
                double grow = Math.max(1.0, sigma - delta);
                for (int mp : minPixels) {
                    out.add(new FrameSetting(sigma, grow, mp));
                }
            }
        }
        return out;
    }

    private static List<MasterSetting> masterSettings(double psfCoreArea) {
        List<Integer> minPixels = new ArrayList<>();
        minPixels.add(3);
        for (double f : MASTER_MIN_PIXEL_AREA_FACTORS) {
            int mp = Math.max(3, (int) Math.round(f * psfCoreArea));
            if (!minPixels.contains(mp)) minPixels.add(mp);
        }
        List<MasterSetting> out = new ArrayList<>();
        for (double seed : MASTER_SIGMAS_TO_TEST) {
            for (double offset : MASTER_GROW_OFFSETS_TO_TEST) {
                double grow = Math.max(1.0, seed - offset);
                for (int mp : minPixels) {
                    out.add(new MasterSetting(seed, grow, mp));
                }
            }
        }
        return out;
    }

    /** Up to three square crops: upper-left interior, centre, lower-right interior. */
    private static List<int[]> cropRegions(int width, int height, int margin) {
        List<int[]> out = new ArrayList<>();
        int usableW = width - 2 * margin;
        int usableH = height - 2 * margin;
        int size = Math.min(PREFERRED_CROP_SIZE, Math.min(usableW, usableH));
        if (size < MIN_CROP_SIZE) {
            int fallback = Math.min(width, height);
            if (fallback < MIN_CROP_SIZE) {
                return out;
            }
            out.add(new int[]{(width - fallback) / 2, (height - fallback) / 2, fallback});
            return out;
        }
        int minX = margin;
        int minY = margin;
        int maxX = width - margin - size;
        int maxY = height - margin - size;
        int cols = Math.max(1, Math.min(MAX_CROPS_PER_SIDE, (int) Math.ceil(usableW / (2.0 * size))));
        int rows = Math.max(1, Math.min(MAX_CROPS_PER_SIDE, (int) Math.ceil(usableH / (2.0 * size))));
        if (cols >= 2 && rows >= 2 && cols * rows > CROP_COUNT) {
            for (int r = 0; r < rows; r++) {
                for (int c = 0; c < cols; c++) {
                    out.add(new int[]{minX + (int) Math.round(c * (maxX - minX) / (double) (cols - 1)),
                            minY + (int) Math.round(r * (maxY - minY) / (double) (rows - 1)), size});
                }
            }
            return out;
        }
        // Centre first, then the four interior corners.
        out.add(new int[]{(width - size) / 2, (height - size) / 2, size});
        out.add(new int[]{minX, minY, size});
        out.add(new int[]{maxX, maxY, size});
        if (maxX - minX >= size && maxY - minY >= size) {
            out.add(new int[]{maxX, minY, size});
            out.add(new int[]{minX, maxY, size});
        }
        return out.subList(0, Math.min(CROP_COUNT, out.size()));
    }

    private static short[][] crop(short[][] image, int[] region) {
        int size = region[2];
        short[][] out = new short[size][size];
        for (int y = 0; y < size; y++) {
            System.arraycopy(image[region[1] + y], region[0], out[y], 0, size);
        }
        return out;
    }

    private static List<ImageFrame> evenlySpaced(List<ImageFrame> frames, int count) {
        List<ImageFrame> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int index = (int) Math.round(i * (frames.size() - 1) / (double) Math.max(1, count - 1));
            ImageFrame f = frames.get(index);
            if (!out.contains(f)) out.add(f);
        }
        return out;
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(65535, value));
    }

    private static void progress(TransientEngineProgressListener listener, int percent, String message) {
        if (listener != null) {
            listener.onProgressUpdate(percent, message);
        }
    }
}
