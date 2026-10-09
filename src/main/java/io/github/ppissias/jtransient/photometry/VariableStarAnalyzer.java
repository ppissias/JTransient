/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 * author: Petros Pissias <petrospis at gmail.com>
 *
 */
package io.github.ppissias.jtransient.photometry;

import io.github.ppissias.jtransient.config.DetectionConfig;
import io.github.ppissias.jtransient.core.MasterReferenceAnalyzer;
import io.github.ppissias.jtransient.core.SourceExtractor;
import io.github.ppissias.jtransient.core.TrackLinker;
import io.github.ppissias.jtransient.engine.ImageFrame;
import io.github.ppissias.jtransient.engine.JTransientEngine;
import io.github.ppissias.jtransient.engine.TransientEngineProgressListener;
import io.github.ppissias.jtransient.quality.FrameQualityAnalyzer;
import io.github.ppissias.jtransient.telemetry.PipelineTelemetry;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs stationary-star photometry and variable-star detection on the quality-filtered frames.
 *
 * <p>Stages: star selection, saturation level, forced aperture photometry, crossing flags,
 * readiness checks A and B, ensemble solve, readiness check D (with a re-solve without failing
 * frames), verdict, and finally variability scoring when the verdict allows it.</p>
 */
public final class VariableStarAnalyzer {

    /** Flags that keep a measurement out of the ensemble and scoring. */
    private static final int EXCLUDING_FLAGS = PhotometryFlags.SATURATED | PhotometryFlags.NONLINEAR
            | PhotometryFlags.EDGE_OR_VOID | PhotometryFlags.CROSSING | PhotometryFlags.BAD_FLUX
            | PhotometryFlags.FRAME_EXCLUDED | PhotometryFlags.CONTAMINATED;
    /** Flags that make a measurement unfit as a reference for the shape check. */
    private static final int SHAPE_REFERENCE_EXCLUDING_FLAGS = PhotometryFlags.SATURATED | PhotometryFlags.NONLINEAR
            | PhotometryFlags.EDGE_OR_VOID | PhotometryFlags.CROSSING | PhotometryFlags.BAD_FLUX;
    /** A measurement whose relative concentration deviates by more than this many robust sigmas is contaminated. */
    private static final double SHAPE_OUTLIER_SIGMA = 5.0;
    /** Lower bound on the per-star relative-concentration spread (fractional), so very stable stars are not over-flagged. */
    private static final double MIN_SHAPE_SPREAD = 0.02;
    private static final int MIN_SHAPE_MEASUREMENTS = 5;
    /** Share of the photometry progress spent measuring the frames. */
    private static final int MEASURING_PROGRESS_SHARE = 80;
    /** Stars are grouped by brightness into about this many groups for the shape tolerance... */
    private static final int SHAPE_GROUPS = 10;
    /** ...with at least this many stars per group. */
    private static final int MIN_SHAPE_GROUP_STARS = 30;
    /** A star flagged saturated or non-linear in more than this share of its measurements is not used at all. */
    private static final double MAX_NONLINEAR_SHARE = 0.5;
    /*
     * Photometry's own inputs, fixed so its results do not depend on the moving-object detection profile:
     * its star list and sky mask come from the median stack at these thresholds, and a frame detection flags a
     * star only when it peaks at CROSSING_PEAK_SIGMA or more. Sigmas are in units of each image's measured noise.
     */
    static final double STAR_LIST_SEED_SIGMA = 3.0;
    static final double STAR_LIST_GROW_SIGMA = 2.0;
    static final int STAR_LIST_MIN_PIXELS = 3;
    static final double CROSSING_PEAK_SIGMA = 5.0;

    /** Limited verdict when the median linear range is below the minimum plus this margin, in mag. */
    private static final double LIMITED_RANGE_MARGIN_MAG = 1.0;

    /** Inputs gathered by the engine. */
    public static final class Input {
        /** Frames that passed the session quality filter, chronological. */
        public List<ImageFrame> frames;
        /** Quality metrics of the same frames, same order. */
        public List<FrameQualityAnalyzer.FrameMetrics> frameMetrics;
        /** All detections of each frame before the stationary-star veto, same order. */
        public List<List<SourceExtractor.DetectedObject>> frameDetections;
        public List<SourceExtractor.DetectedObject> masterStars;
        public short[][] masterStack;
        /** Master-star pixel mask, excluded from sky annuli; may be null. */
        public boolean[][] masterStarMask;
        public List<TrackLinker.Track> tracks = new ArrayList<>();
        public List<SourceExtractor.DetectedObject> slowMoverCandidates = new ArrayList<>();
        /** Optional progress listener, 0 to 100 over the photometry stage. */
        public TransientEngineProgressListener progress;
    }

    private VariableStarAnalyzer() {
    }

    /**
     * Runs the photometry stage.
     *
     * @param input frames and products of the earlier pipeline stages
     * @param config pipeline configuration
     * @param executor optional executor used to measure frames in parallel; null runs sequentially
     * @return analysis result; never null
     */
    public static VariableStarAnalysis analyze(Input input, DetectionConfig config, ExecutorService executor) throws Exception {
        long start = System.currentTimeMillis();
        PipelineTelemetry.PhotometryTelemetry telemetry = new PipelineTelemetry.PhotometryTelemetry();
        PhotometricReadiness readiness = new PhotometricReadiness();
        List<ImageFrame> frames = input.frames;
        int nFrames = frames.size();
        telemetry.framesAnalyzed = nFrames;

        PipelineTelemetry.PhotometryFrameStat[] stats = new PipelineTelemetry.PhotometryFrameStat[nFrames];
        for (int j = 0; j < nFrames; j++) {
            ImageFrame frame = frames.get(j);
            PipelineTelemetry.PhotometryFrameStat stat = new PipelineTelemetry.PhotometryFrameStat();
            stat.frameIndex = frame.sequenceIndex;
            stat.filename = frame.filename;
            stat.timestamp = frame.timestamp;
            stat.julianDate = midExposureJulianDate(frame);
            stats[j] = stat;
            telemetry.frames.add(stat);
        }

        if (nFrames < Math.max(3, config.variableMinFrames) || input.masterStars == null || input.masterStack == null) {
            readiness.verdict = PhotometricReadiness.Verdict.NOT_READY;
            readiness.messages.add(String.format(
                    "Photometry needs at least %d quality-filtered frames; %d are available.",
                    Math.max(3, config.variableMinFrames), nFrames));
            return finish(readiness, new ArrayList<>(), telemetry, start);
        }

        // --- Check A: quantisation ---
        readiness.distinctPixelLevels = PhotometricReadinessChecker.countDistinctLevels(frames.get(nFrames / 2).pixelData);
        boolean quantisationFails = readiness.distinctPixelLevels < config.linearityMinDistinctLevels;

        // --- Star selection and saturation level ---
        double[] frameFwhm = new double[nFrames];
        double[] frameBackground = new double[nFrames];
        for (int j = 0; j < nFrames; j++) {
            FrameQualityAnalyzer.FrameMetrics metrics = input.frameMetrics.get(j);
            frameFwhm[j] = validFwhm(metrics.medianFWHM, config) ? metrics.medianFWHM : Double.NaN;
            frameBackground[j] = metrics.backgroundMedian;
        }
        double sessionFwhm = PhotometryMath.median(frameFwhm);
        if (!Double.isFinite(sessionFwhm)) {
            readiness.verdict = PhotometricReadiness.Verdict.NOT_READY;
            readiness.messages.add("No frame has a usable FWHM measurement.");
            return finish(readiness, new ArrayList<>(), telemetry, start);
        }
        for (int j = 0; j < nFrames; j++) {
            if (!Double.isFinite(frameFwhm[j])) {
                frameFwhm[j] = sessionFwhm;
            }
        }
        telemetry.sessionFwhm = sessionFwhm;

        double saturationLevel = PhotometryStarSelector.estimateSaturationLevel(frames);
        telemetry.saturationLevel = saturationLevel;
        double saturationThreshold = config.photometrySaturationFraction * saturationLevel;

        // Photometry's own star list and sky mask from the median stack, independent of the detection settings.
        DetectionConfig starConfig = config.clone();
        starConfig.masterSigmaMultiplier = STAR_LIST_SEED_SIGMA;
        starConfig.masterGrowSigmaMultiplier = STAR_LIST_GROW_SIGMA;
        starConfig.masterMinDetectionPixels = STAR_LIST_MIN_PIXELS;
        List<SourceExtractor.DetectedObject> photometryStars =
                MasterReferenceAnalyzer.analyzeFromMasterStack(input.masterStack, starConfig).masterStars;
        boolean[][] skyMask = footprintMask(photometryStars, input.masterStack.length, input.masterStack[0].length);
        List<PhotometryStarSelector.Star> selected = PhotometryStarSelector.select(
                photometryStars, input.masterStack, sessionFwhm, saturationThreshold, config, telemetry);
        if (selected.size() < config.linearityMinStars) {
            readiness.verdict = PhotometricReadiness.Verdict.NOT_READY;
            readiness.messages.add(String.format(
                    "Only %d isolated, measurable stars were found; at least %d are needed.",
                    selected.size(), config.linearityMinStars));
            return finish(readiness, new ArrayList<>(), telemetry, start);
        }

        // --- Forced aperture photometry on every frame ---
        report(input, 0, "Photometry: measuring " + selected.size() + " stars in every frame...");
        ApertureMeasurer.FrameMeasurement[] measurements = measureFrames(
                input, selected, skyMask, frameFwhm, frameBackground, saturationThreshold, config, executor);
        report(input, MEASURING_PROGRESS_SHARE, "Photometry: readiness checks and ensemble solution...");
        flagCrossings(input, selected, photometryStars, measurements, config);

        int nStars = selected.size();
        boolean[] frameActive = new boolean[nFrames];
        Arrays.fill(frameActive, true);

        // --- Registration check and frame telemetry ---
        double[] floorFractions = new double[nFrames];
        for (int j = 0; j < nFrames; j++) {
            ApertureMeasurer.FrameMeasurement m = measurements[j];
            PipelineTelemetry.PhotometryFrameStat stat = stats[j];
            stat.fwhm = m.fwhm;
            stat.apertureRadius = m.radii[ApertureMeasurer.R_MAIN];
            stat.skyMedian = m.skyMedian;
            stat.registrationOffsetX = m.offsetX;
            stat.registrationOffsetY = m.offsetY;
            stat.registrationSpread = m.registrationSpread;
            stat.floorClippedFraction = m.floorClippedFraction;
            floorFractions[j] = m.floorClippedFraction;
            if (m.registrationSpread > config.photometryMaxRegistrationSpreadPixels) {
                exclude(j, stat, frameActive, "Registration spread " + format(m.registrationSpread) + " px");
                telemetry.framesExcludedRegistration++;
            }
        }

        // --- Check A: zero-floor clipping ---
        readiness.medianFloorClippedFraction = PhotometryMath.median(floorFractions);
        boolean floorClipped = readiness.medianFloorClippedFraction > config.linearityMaxFloorClippedFraction;
        if (quantisationFails) {
            readiness.quantisationCheck = PhotometricReadiness.CheckStatus.FAIL;
            readiness.messages.add(String.format(
                    "Only %d distinct pixel levels were found (at least %d needed). The data looks 8-bit or heavily quantised; load the original RAW or FITS frames.",
                    readiness.distinctPixelLevels, config.linearityMinDistinctLevels));
        } else if (floorClipped) {
            readiness.quantisationCheck = PhotometricReadiness.CheckStatus.LIMITED;
            readiness.messages.add(String.format(
                    "%.1f%% of sky pixels sit at zero, so negative sky noise was clipped. Faint-star photometry is biased.",
                    100.0 * readiness.medianFloorClippedFraction));
        } else {
            readiness.quantisationCheck = PhotometricReadiness.CheckStatus.PASS;
        }

        // --- Check B on every frame ---
        double[] limits = new double[nFrames];
        double[] ranges = new double[nFrames];
        Arrays.fill(limits, Double.NaN);
        Arrays.fill(ranges, Double.NaN);
        int shapeFailures = 0;
        int shapeShortRange = 0;
        int shapeFewStars = 0;
        int shapeEvaluated = 0;
        for (int j = 0; j < nFrames; j++) {
            ApertureMeasurer.FrameMeasurement m = measurements[j];
            PhotometricReadinessChecker.ShapeResult shape = PhotometricReadinessChecker.analyzeShape(m, config, stats[j]);
            if (shape.status == PhotometricReadiness.CheckStatus.INCONCLUSIVE) {
                if (frameActive[j]) {
                    exclude(j, stats[j], frameActive, "Too few measurable stars for the linearity check");
                    telemetry.framesExcludedTooFewStars++;
                }
                continue;
            }
            shapeEvaluated++;
            limits[j] = shape.linearLimitMag;
            ranges[j] = stats[j].linearRangeMag;
            for (int i = 0; i < nStars; i++) {
                double mag = m.mag(ApertureMeasurer.R_MAIN, i);
                if (Double.isFinite(mag) && mag < shape.linearLimitMag) {
                    m.flags[i] |= PhotometryFlags.NONLINEAR;
                }
            }
            if (shape.status == PhotometricReadiness.CheckStatus.FAIL) {
                shapeFailures++;
                // Without a departing bin the frame failed for lack of range or, with enough range, of stars.
                boolean fewStars = !shape.departureFound && stats[j].linearRangeMag >= config.linearityMinRangeMag;
                if (fewStars) {
                    shapeFewStars++;
                } else if (!shape.departureFound) {
                    shapeShortRange++;
                }
                if (frameActive[j]) {
                    exclude(j, stats[j], frameActive, shape.departureFound
                            ? "Linear range " + format(stats[j].linearRangeMag) + " mag"
                            : fewStars
                            ? "Only " + stats[j].linearStars + " stars in the linear range, " + config.linearityMinStars + " needed"
                            : "High-SNR stars span only " + format(stats[j].linearRangeMag) + " mag");
                    telemetry.framesExcludedShapeLinearity++;
                }
            }
        }
        readiness.medianLinearLimitMag = PhotometryMath.median(limits);
        readiness.medianLinearRangeMag = PhotometryMath.median(ranges);
        readiness.framesFailingShapeLinearity = shapeFailures;
        if (shapeEvaluated == 0) {
            readiness.shapeLinearityCheck = PhotometricReadiness.CheckStatus.INCONCLUSIVE;
            readiness.messages.add("No frame had enough measurable stars for the star-shape linearity check.");
        } else if (shapeFailures > config.linearityMaxFailingFrameFraction * shapeEvaluated) {
            readiness.shapeLinearityCheck = PhotometricReadiness.CheckStatus.FAIL;
            int departing = shapeFailures - shapeShortRange - shapeFewStars;
            if (departing > 0) {
                readiness.messages.add(String.format(
                        "In %d of %d frames bright stars are flatter than faint ones over most of the magnitude range (median linear range %.1f mag). These frames look stretched or non-linear; load the original RAW or FITS frames.",
                        departing, shapeEvaluated, readiness.medianLinearRangeMag));
            }
            if (shapeShortRange > 0) {
                readiness.messages.add(String.format(
                        "In %d of %d frames star shapes stay within tolerance, but the stars bright enough for the check (SNR of about 20 or more) span less than the %.1f mag needed to verify linearity. Longer exposures or a richer star field give a longer range.",
                        shapeShortRange, shapeEvaluated, config.linearityMinRangeMag));
            }
            if (shapeFewStars > 0) {
                readiness.messages.add(String.format(
                        "In %d of %d frames star shapes stay within tolerance, but fewer than %d measurable stars lie in the linear range.",
                        shapeFewStars, shapeEvaluated, config.linearityMinStars));
            }
        } else if (readiness.medianLinearRangeMag < config.linearityMinRangeMag + LIMITED_RANGE_MARGIN_MAG) {
            readiness.shapeLinearityCheck = PhotometricReadiness.CheckStatus.LIMITED;
            readiness.messages.add(String.format(
                    "The linear magnitude range is only %.1f mag.", readiness.medianLinearRangeMag));
        } else {
            readiness.shapeLinearityCheck = PhotometricReadiness.CheckStatus.PASS;
        }

        // --- Per-measurement shape check (hot pixels, cosmic rays, faint passing objects) ---
        flagContaminatedShapes(measurements, nStars, nFrames);

        // --- Ensemble inputs ---
        double[][] mag = new double[nStars][nFrames];
        int[][] flags = new int[nStars][nFrames];
        double[][] sky = new double[nStars][nFrames];
        for (int j = 0; j < nFrames; j++) {
            for (int i = 0; i < nStars; i++) {
                mag[i][j] = measurements[j].mag(ApertureMeasurer.R_MAIN, i);
                flags[i][j] = measurements[j].flags[i];
                sky[i][j] = measurements[j].sky[i];
            }
        }
        boolean[] starExcluded = new boolean[nStars];
        String[] starExclusionReason = new String[nStars];
        double[] snr = new double[nFrames];
        for (int i = 0; i < nStars; i++) {
            int measured = 0;
            int nonlinear = 0;
            for (int j = 0; j < nFrames; j++) {
                double error = measurements[j].magError[i];
                snr[j] = (Double.isFinite(mag[i][j]) && error > 0) ? 1.0857 / error : Double.NaN;
                if (Double.isFinite(mag[i][j])) {
                    measured++;
                    if ((flags[i][j] & (PhotometryFlags.SATURATED | PhotometryFlags.NONLINEAR)) != 0) {
                        nonlinear++;
                    }
                }
            }
            if (measured > 0 && nonlinear > MAX_NONLINEAR_SHARE * measured) {
                starExcluded[i] = true;
                starExclusionReason[i] = "Saturated or non-linear in most frames";
                telemetry.starsExcludedMostlyNonlinear++;
                continue;
            }
            double medianSnr = PhotometryMath.median(snr);
            if (config.photometryMinSnr > 0 && !(medianSnr >= config.photometryMinSnr)) {
                starExcluded[i] = true;
                starExclusionReason[i] = String.format("Median SNR %.1f below the minimum %.1f",
                        medianSnr, config.photometryMinSnr);
                telemetry.starsExcludedLowSnr++;
            }
        }
        double[] xNorm = new double[nStars];
        double[] yNorm = new double[nStars];
        double halfWidth = input.masterStack[0].length / 2.0;
        double halfHeight = input.masterStack.length / 2.0;
        for (int i = 0; i < nStars; i++) {
            xNorm[i] = (selected.get(i).x - halfWidth) / halfWidth;
            yNorm[i] = (selected.get(i).y - halfHeight) / halfHeight;
        }

        boolean[][] usable = usableMatrix(mag, flags, starExcluded, frameActive);
        EnsembleSolver.Solution solution = EnsembleSolver.solve(mag, usable, xNorm, yNorm, frameActive, config.photometryFitPlane);
        excludeUnsolvedFrames(solution, stats, frameActive, telemetry);

        // --- Check D, then re-solve without failing frames ---
        double[] frameSky = new double[nFrames];
        for (int j = 0; j < nFrames; j++) {
            frameSky[j] = measurements[j].skyMedian;
        }
        PhotometricReadinessChecker.ResponseResult response =
                PhotometricReadinessChecker.checkResponse(solution, usable, frameSky, stats, config, readiness);
        readiness.responseCheck = response.status;
        boolean anyResponseExclusion = false;
        for (int j = 0; j < nFrames; j++) {
            if (response.frameFails != null && response.frameFails[j] && frameActive[j]) {
                exclude(j, stats[j], frameActive, "Magnitude-dependent response " + format(stats[j].responseSlope) + " mag/mag");
                telemetry.framesExcludedResponse++;
                anyResponseExclusion = true;
            }
        }
        if (anyResponseExclusion) {
            usable = usableMatrix(mag, flags, starExcluded, frameActive);
            solution = EnsembleSolver.solve(mag, usable, xNorm, yNorm, frameActive, config.photometryFitPlane);
            excludeUnsolvedFrames(solution, stats, frameActive, telemetry);
        }
        for (int j = 0; j < nFrames; j++) {
            if (solution.frameSolved[j]) {
                stats[j].zeroPoint = solution.zeroPoint[j];
                stats[j].planeX = solution.planeX[j];
                stats[j].planeY = solution.planeY[j];
                stats[j].starsInEnsemble = solution.starsPerFrame[j];
            }
        }

        // --- Verdict ---
        decideVerdict(readiness, frameActive, config);

        // --- Light curves ---
        List<StarLightCurve> curves = new ArrayList<>(nStars);
        for (int i = 0; i < nStars; i++) {
            PhotometryStarSelector.Star star = selected.get(i);
            StarLightCurve curve = new StarLightCurve(i, star.x, star.y, star.flux, nFrames);
            curve.meanMag = solution.starMag[i];
            for (int j = 0; j < nFrames; j++) {
                curve.deltaMag[j] = solution.residual[i][j];
                curve.magError[j] = measurements[j].magError[i];
                curve.flags[j] = flags[i][j] | (frameActive[j] ? 0 : PhotometryFlags.FRAME_EXCLUDED);
            }
            if (starExcluded[i]) {
                curve.notScoredReason = starExclusionReason[i];
            }
            curves.add(curve);
        }

        report(input, 90, "Photometry: scoring variability...");
        // --- Variability scoring ---
        if (readiness.allowsScoring()) {
            EnsembleSolver.Solution small = solveForAperture(measurements, ApertureMeasurer.R_SMALL, flags, starExcluded,
                    frameActive, xNorm, yNorm, config);
            EnsembleSolver.Solution large = solveForAperture(measurements, ApertureMeasurer.R_LARGE, flags, starExcluded,
                    frameActive, xNorm, yNorm, config);
            VariabilityScorer.FrameSeries series = new VariabilityScorer.FrameSeries();
            series.zeroPoint = new double[nFrames];
            series.fwhm = new double[nFrames];
            series.offsetX = new double[nFrames];
            series.offsetY = new double[nFrames];
            series.julianDate = new double[nFrames];
            for (int j = 0; j < nFrames; j++) {
                boolean on = frameActive[j] && solution.frameSolved[j];
                series.zeroPoint[j] = on ? solution.zeroPoint[j] : Double.NaN;
                series.fwhm[j] = on ? measurements[j].fwhm : Double.NaN;
                series.offsetX[j] = on ? measurements[j].offsetX : Double.NaN;
                series.offsetY[j] = on ? measurements[j].offsetY : Double.NaN;
                series.julianDate[j] = stats[j].julianDate;
            }
            EnsembleSolver.Solution tiny = solveForAperture(measurements, ApertureMeasurer.R_CI_INNER, flags, starExcluded,
                    frameActive, xNorm, yNorm, config);
            chooseApertures(curves, usable, starExcluded, solution,
                    new EnsembleSolver.Solution[]{tiny, small, solution, large},
                    new double[]{ApertureMeasurer.CI_INNER_FWHM_FACTOR, 1.0, config.photometryApertureFwhmFactor, 2.0},
                    sessionFwhm, telemetry);
            VariabilityScorer.score(curves, usable, small.residual, large.residual, sky, series,
                    readiness.verdict == PhotometricReadiness.Verdict.LIMITED, config, telemetry);
        } else {
            for (StarLightCurve curve : curves) {
                if (curve.notScoredReason == null) {
                    curve.notScoredReason = "Session not ready for photometry";
                }
            }
            telemetry.starsNotScored = curves.size();
        }

        // --- Measurement counters ---
        for (StarLightCurve curve : curves) {
            for (int j = 0; j < nFrames; j++) {
                int f = curve.flags[j];
                PipelineTelemetry.PhotometryFrameStat stat = stats[j];
                telemetry.measurementsTotal++;
                if ((f & (PhotometryFlags.EDGE_OR_VOID | PhotometryFlags.BAD_FLUX)) == 0) {
                    stat.starsMeasured++;
                }
                if ((f & PhotometryFlags.SATURATED) != 0) { telemetry.measurementsSaturated++; stat.saturated++; }
                if ((f & PhotometryFlags.NONLINEAR) != 0) { telemetry.measurementsNonlinear++; stat.nonlinear++; }
                if ((f & PhotometryFlags.EDGE_OR_VOID) != 0) { telemetry.measurementsEdgeOrVoid++; stat.edgeOrVoid++; }
                if ((f & PhotometryFlags.CROSSING) != 0) { telemetry.measurementsCrossing++; stat.crossing++; }
                if ((f & PhotometryFlags.OUTLIER) != 0) { telemetry.measurementsOutlier++; stat.outliers++; }
                if ((f & PhotometryFlags.BAD_FLUX) != 0) { telemetry.measurementsBadFlux++; stat.badFlux++; }
                if ((f & PhotometryFlags.CONTAMINATED) != 0) { telemetry.measurementsContaminated++; stat.contaminated++; }
            }
        }
        for (int j = 0; j < nFrames; j++) {
            if (frameActive[j]) {
                telemetry.framesUsed++;
            }
        }
        return finish(readiness, curves, telemetry, start);
    }

    // =================================================================
    // Stages
    // =================================================================

    /**
     * Flags measurements whose concentration index (inner over outer aperture flux) departs from the star's
     * own typical value. Each concentration is first divided by the frame's median concentration so that
     * seeing changes, which affect every star alike, are taken out. The allowed departure comes from stars of
     * similar brightness, not from the star itself: a star contaminated in many frames would otherwise widen
     * its own tolerance until nothing is flagged.
     */
    private static void report(Input input, int percent, String message) {
        if (input.progress != null) {
            input.progress.onProgressUpdate(percent, message);
        }
    }

    private static void flagContaminatedShapes(ApertureMeasurer.FrameMeasurement[] measurements, int nStars, int nFrames) {
        double[] frameConcentration = new double[nFrames];
        double[] buffer = new double[Math.max(nStars, nFrames)];
        for (int j = 0; j < nFrames; j++) {
            ApertureMeasurer.FrameMeasurement m = measurements[j];
            int n = 0;
            for (int i = 0; i < nStars; i++) {
                double ci = m.concentration(i);
                if (Double.isFinite(ci) && (m.flags[i] & SHAPE_REFERENCE_EXCLUDING_FLAGS) == 0) {
                    buffer[n++] = ci;
                }
            }
            frameConcentration[j] = n > 0 ? PhotometryMath.median(buffer, n) : Double.NaN;
        }
        // Fractional departure of every measurement from the star's typical relative concentration.
        double[][] departure = new double[nStars][nFrames];
        double[] starMag = new double[nStars];
        double[] relative = new double[nFrames];
        double[] mags = new double[nFrames];
        List<Integer> scored = new ArrayList<>();
        for (int i = 0; i < nStars; i++) {
            Arrays.fill(departure[i], Double.NaN);
            int n = 0;
            for (int j = 0; j < nFrames; j++) {
                ApertureMeasurer.FrameMeasurement m = measurements[j];
                double ci = m.concentration(i);
                boolean ok = Double.isFinite(ci) && frameConcentration[j] > 0
                        && (m.flags[i] & SHAPE_REFERENCE_EXCLUDING_FLAGS) == 0;
                relative[j] = ok ? ci / frameConcentration[j] : Double.NaN;
                mags[j] = ok ? m.mag(ApertureMeasurer.R_MAIN, i) : Double.NaN;
                if (ok) {
                    n++;
                }
            }
            if (n < MIN_SHAPE_MEASUREMENTS) {
                continue;
            }
            double typical = PhotometryMath.median(relative);
            starMag[i] = PhotometryMath.median(mags);
            if (!(typical > 0) || !Double.isFinite(starMag[i])) {
                continue;
            }
            for (int j = 0; j < nFrames; j++) {
                departure[i][j] = relative[j] / typical - 1.0;
            }
            scored.add(i);
        }
        if (scored.isEmpty()) {
            return;
        }
        // Tolerance per brightness group, pooled over all measurements of the group's stars.
        scored.sort((a, b) -> Double.compare(starMag[a], starMag[b]));
        int groupSize = Math.max(MIN_SHAPE_GROUP_STARS, scored.size() / SHAPE_GROUPS);
        double[] pooled = new double[groupSize * 2 * nFrames];
        int start = 0;
        while (start < scored.size()) {
            int end = Math.min(scored.size(), start + groupSize);
            if (scored.size() - end < groupSize / 2) {
                end = scored.size(); // fold a small remainder into the last group
            }
            int n = 0;
            for (int k = start; k < end; k++) {
                for (double d : departure[scored.get(k)]) {
                    if (Double.isFinite(d)) {
                        if (n == pooled.length) {
                            pooled = Arrays.copyOf(pooled, pooled.length * 2);
                        }
                        pooled[n++] = d;
                    }
                }
            }
            double spread = Math.max(PhotometryMath.robustSigma(pooled, n), MIN_SHAPE_SPREAD);
            for (int k = start; k < end; k++) {
                int i = scored.get(k);
                for (int j = 0; j < nFrames; j++) {
                    if (Math.abs(departure[i][j]) > SHAPE_OUTLIER_SIGMA * spread) {
                        measurements[j].flags[i] |= PhotometryFlags.CONTAMINATED;
                    }
                }
            }
            start = end;
        }
    }

    private static ApertureMeasurer.FrameMeasurement[] measureFrames(Input input,
                                                                     List<PhotometryStarSelector.Star> stars,
                                                                     boolean[][] skyMask,
                                                                     double[] frameFwhm,
                                                                     double[] frameBackground,
                                                                     double saturationThreshold,
                                                                     DetectionConfig config,
                                                                     ExecutorService executor) throws Exception {
        int nFrames = input.frames.size();
        ApertureMeasurer.FrameMeasurement[] out = new ApertureMeasurer.FrameMeasurement[nFrames];
        List<Callable<ApertureMeasurer.FrameMeasurement>> tasks = new ArrayList<>(nFrames);
        AtomicInteger measured = new AtomicInteger();
        for (int j = 0; j < nFrames; j++) {
            final int frame = j;
            tasks.add(() -> {
                ApertureMeasurer.FrameMeasurement m = ApertureMeasurer.measure(
                        input.frames.get(frame).pixelData,
                        frameFwhm[frame],
                        stars,
                        skyMask,
                        saturationThreshold,
                        frameBackground[frame],
                        config);
                synchronized (measured) { // keeps the reported progress in order across threads
                    int done = measured.incrementAndGet();
                    report(input, (int) (MEASURING_PROGRESS_SHARE * done / nFrames),
                            "Photometry: measured frame " + done + " of " + nFrames);
                }
                return m;
            });
        }
        if (executor == null) {
            for (int j = 0; j < nFrames; j++) {
                out[j] = tasks.get(j).call();
            }
        } else {
            List<Future<ApertureMeasurer.FrameMeasurement>> futures = executor.invokeAll(tasks);
            for (int j = 0; j < nFrames; j++) {
                out[j] = futures.get(j).get();
            }
        }
        return out;
    }

    /**
     * Flags measurements contaminated by something that moved: pre-veto detections that do not sit on
     * a master star, interpolated track positions in frames where the object merged with a star, and
     * slow-mover footprints (in every frame). Frame positions are moved into the master system with the
     * frame's measured registration offset, the same correction the apertures use.
     */
    private static void flagCrossings(Input input,
                                      List<PhotometryStarSelector.Star> stars,
                                      List<SourceExtractor.DetectedObject> photometryStars,
                                      ApertureMeasurer.FrameMeasurement[] measurements,
                                      DetectionConfig config) {
        int nFrames = measurements.length;
        PointGrid starGrid = new PointGrid(16.0);
        for (int i = 0; i < stars.size(); i++) {
            starGrid.add(stars.get(i).x, stars.get(i).y, i);
        }
        PointGrid masterGrid = new PointGrid(16.0);
        for (int k = 0; k < input.masterStars.size(); k++) {
            masterGrid.add(input.masterStars.get(k).x, input.masterStars.get(k).y, k);
        }
        if (photometryStars != input.masterStars) {
            for (int k = 0; k < photometryStars.size(); k++) {
                masterGrid.add(photometryStars.get(k).x, photometryStars.get(k).y, -1 - k);
            }
        }

        for (int j = 0; j < nFrames; j++) {
            ApertureMeasurer.FrameMeasurement m = measurements[j];
            // A detection flags a star only when it reaches the aperture or the gap before the sky ring; the clipped
            // sky median already ignores faint blobs in the ring itself.
            double radius = config.photometryAnnulusInnerFwhmFactor * m.fwhm;
            double stationaryRadius = Math.max(config.maxStarJitter, 0.5 * m.fwhm);
            double ox = finiteOrZero(m.offsetX);
            double oy = finiteOrZero(m.offsetY);

            if (input.frameDetections != null && j < input.frameDetections.size()) {
                for (SourceExtractor.DetectedObject d : input.frameDetections.get(j)) {
                    if (d.peakSigma < CROSSING_PEAK_SIGMA && !d.isStreak) {
                        continue;
                    }
                    double dx = d.x - ox;
                    double dy = d.y - oy;
                    if (masterGrid.anyWithin(dx, dy, stationaryRadius)) {
                        continue;
                    }
                    double extent = Math.max(d.fwhm, Math.max(d.majorExtent, 0.0));
                    if (!starGrid.anyWithin(dx, dy, radius + extent + 1.0)) {
                        continue;
                    }
                    if (d.rawPixels != null && !d.rawPixels.isEmpty()) {
                        for (SourceExtractor.Pixel p : d.rawPixels) {
                            for (int i : starGrid.within(p.x - ox, p.y - oy, radius)) {
                                m.flags[i] |= PhotometryFlags.CROSSING;
                            }
                        }
                    } else {
                        for (int i : starGrid.within(dx, dy, radius + extent)) {
                            m.flags[i] |= PhotometryFlags.CROSSING;
                        }
                    }
                }
            }
        }

        if (input.tracks != null) {
            for (TrackLinker.Track track : input.tracks) {
                flagTrack(track, input.frames, stars, starGrid, measurements, config);
            }
        }

        if (input.slowMoverCandidates != null) {
            for (SourceExtractor.DetectedObject candidate : input.slowMoverCandidates) {
                for (int j = 0; j < nFrames; j++) {
                    double radius = config.photometryAnnulusOuterFwhmFactor * measurements[j].fwhm;
                    if (candidate.rawPixels != null && !candidate.rawPixels.isEmpty()) {
                        for (SourceExtractor.Pixel p : candidate.rawPixels) {
                            for (int i : starGrid.within(p.x, p.y, radius)) {
                                measurements[j].flags[i] |= PhotometryFlags.CROSSING;
                            }
                        }
                    } else {
                        for (int i : starGrid.within(candidate.x, candidate.y, radius + candidate.majorExtent)) {
                            measurements[j].flags[i] |= PhotometryFlags.CROSSING;
                        }
                    }
                }
            }
        }
    }

    /**
     * Flags the stars a track passes in every frame of its time span. Frames where the track was observed
     * use every observed point (a fragmented streak can have several); other frames use the position
     * interpolated in capture time, or in sequence index when timestamps are missing. Each point is treated
     * as a segment along its footprint (a point source is a segment of length zero), so a long streak flags
     * the stars along its line rather than every star within its length.
     */
    static void flagTrack(TrackLinker.Track track,
                                  List<ImageFrame> frames,
                                  List<PhotometryStarSelector.Star> stars,
                                  PointGrid starGrid,
                                  ApertureMeasurer.FrameMeasurement[] measurements,
                                  DetectionConfig config) {
        if (track.points.isEmpty()) {
            return;
        }
        // Track points are in their own frame's coordinates; move each into the master system.
        Map<Integer, Integer> frameBySequence = new HashMap<>();
        for (int j = 0; j < frames.size(); j++) {
            frameBySequence.put(frames.get(j).sequenceIndex, j);
        }
        // {sequence, x, y, angle, half length along the angle, half width across it, capture time or NaN}
        List<double[]> points = new ArrayList<>(track.points.size());
        for (SourceExtractor.DetectedObject p : track.points) {
            Integer j = frameBySequence.get(p.sourceFrameIndex);
            double ox = j == null ? 0.0 : finiteOrZero(measurements[j].offsetX);
            double oy = j == null ? 0.0 : finiteOrZero(measurements[j].offsetY);
            double major = finiteOrZero(p.majorExtent);
            double minor = finiteOrZero(p.minorExtent);
            double halfLength = Math.max(0.0, major - minor) / 2.0;
            // A streak's moment FWHM spans its length, so its width is the footprint's minor extent.
            double halfWidth = p.isStreak ? minor : Math.max(finiteOrZero(p.fwhm), minor);
            double time = j == null ? Double.NaN : captureTime(frames.get(j));
            points.add(new double[]{p.sourceFrameIndex, p.x - ox, p.y - oy, finiteOrZero(p.angle), halfLength, halfWidth, time});
        }
        points.sort((a, b) -> Double.compare(a[0], b[0]));
        int firstSequence = (int) points.get(0)[0];
        int lastSequence = (int) points.get(points.size() - 1)[0];
        for (int j = 0; j < frames.size(); j++) {
            int sequence = frames.get(j).sequenceIndex;
            if (sequence < firstSequence || sequence > lastSequence) {
                continue;
            }
            double annulus = config.photometryAnnulusOuterFwhmFactor * measurements[j].fwhm;
            boolean observed = false;
            for (double[] p : points) {
                if (p[0] == sequence) {
                    observed = true;
                    flagSegment(p[1], p[2], p, annulus, stars, starGrid, measurements[j].flags);
                }
            }
            if (!observed) {
                double[] position = interpolate(points, sequence, captureTime(frames.get(j)));
                flagSegment(position[0], position[1], nearestPoint(points, sequence), annulus,
                        stars, starGrid, measurements[j].flags);
            }
        }
    }

    /** Flags the stars within {@code annulus} plus the shape's half width of a segment centred on (x, y). */
    private static void flagSegment(double x, double y, double[] shape, double annulus,
                                    List<PhotometryStarSelector.Star> stars, PointGrid starGrid, int[] flags) {
        double reach = annulus + shape[5];
        double ux = Math.cos(shape[3]);
        double uy = Math.sin(shape[3]);
        for (int i : starGrid.within(x, y, reach + shape[4])) {
            double dx = stars.get(i).x - x;
            double dy = stars.get(i).y - y;
            double along = Math.max(-shape[4], Math.min(shape[4], dx * ux + dy * uy));
            if (Math.hypot(dx - along * ux, dy - along * uy) <= reach) {
                flags[i] |= PhotometryFlags.CROSSING;
            }
        }
    }

    /** Capture time in milliseconds, or NaN when the frame has no timestamp. */
    private static double captureTime(ImageFrame frame) {
        return frame.timestamp > 0 ? frame.timestamp : Double.NaN;
    }

    /** The track point closest in sequence to {@code sequence}, the earlier one on a tie. */
    private static double[] nearestPoint(List<double[]> points, int sequence) {
        double[] best = points.get(0);
        for (double[] p : points) {
            if (Math.abs(p[0] - sequence) < Math.abs(best[0] - sequence)) {
                best = p;
            }
        }
        return best;
    }

    /**
     * Position at {@code sequence} between the surrounding track points, sorted by sequence. The fraction
     * comes from capture times when the frame and both points have one (pauses make the cadence uneven),
     * otherwise from sequence indices.
     */
    static double[] interpolate(List<double[]> points, int sequence, double time) {
        double[] before = points.get(0);
        double[] after = points.get(points.size() - 1);
        for (double[] p : points) {
            if (p[0] <= sequence) {
                before = p;
            }
            if (p[0] >= sequence) {
                after = p;
                break;
            }
        }
        if (after[0] == before[0]) {
            return new double[]{before[1], before[2]};
        }
        double t = Double.isFinite(time) && Double.isFinite(before[6]) && Double.isFinite(after[6]) && after[6] > before[6]
                ? (time - before[6]) / (after[6] - before[6])
                : (sequence - before[0]) / (after[0] - before[0]);
        return new double[]{before[1] + t * (after[1] - before[1]), before[2] + t * (after[2] - before[2])};
    }

    /** Brightness ranges, of equal star counts, that each get their own measuring aperture. */
    private static final int APERTURE_BINS = 8;
    /** Fewest scored stars per range for the choice; with fewer, the main aperture is kept. */
    private static final int APERTURE_MIN_STARS_PER_BIN = 20;
    /** Another aperture replaces the main one only when it lowers the median scatter by more than this fraction. */
    private static final double APERTURE_MIN_GAIN = 0.03;
    /** Smallest aperture radius allowed, in pixels: smaller ones turn centring errors of undersampled stars into noise. */
    private static final double APERTURE_MIN_RADIUS_PIXELS = 2.0;

    /**
     * Chooses the measuring aperture per brightness range: the one whose ensemble solution gives the stars of that
     * range (nearly all constant) the smallest median scatter. Faint stars are limited by sky noise and do better in a
     * small aperture, bright stars in a larger one. Each star's light curve is replaced by the chosen aperture's
     * residuals; the main aperture stays unless another lowers the scatter by more than {@link #APERTURE_MIN_GAIN}.
     *
     * @param options ensemble solutions, one per aperture
     * @param fwhmFactors aperture radius of each option, in units of the FWHM
     */
    private static void chooseApertures(List<StarLightCurve> curves, boolean[][] usable, boolean[] starExcluded,
                                        EnsembleSolver.Solution main, EnsembleSolver.Solution[] options, double[] fwhmFactors,
                                        double sessionFwhm, PipelineTelemetry.PhotometryTelemetry telemetry) {
        int mainIndex = -1;
        for (int a = 0; a < options.length; a++) {
            if (options[a] == main) {
                mainIndex = a;
            }
        }
        int nStars = curves.size();
        List<double[]> rows = new ArrayList<>();
        double[][] sigma = new double[nStars][options.length];
        double[] residuals = new double[usable.length > 0 ? usable[0].length : 0];
        for (int i = 0; i < nStars; i++) {
            if (starExcluded[i] || !Double.isFinite(main.starMag[i])) {
                continue;
            }
            boolean ok = true;
            for (int a = 0; a < options.length && ok; a++) {
                for (int j = 0; j < residuals.length; j++) {
                    residuals[j] = usable[i][j] ? options[a].residual[i][j] : Double.NaN;
                }
                sigma[i][a] = PhotometryMath.robustSigma(residuals);
                ok = Double.isFinite(sigma[i][a]);
            }
            if (ok) {
                rows.add(new double[]{main.starMag[i], i});
            }
        }
        if (mainIndex < 0 || rows.size() < APERTURE_BINS * APERTURE_MIN_STARS_PER_BIN) {
            return;
        }
        rows.sort((p, q) -> Double.compare(p[0], q[0]));
        double[] binUpper = new double[APERTURE_BINS];
        int[] binChoice = new int[APERTURE_BINS];
        for (int b = 0; b < APERTURE_BINS; b++) {
            int from = b * rows.size() / APERTURE_BINS;
            int to = (b + 1) * rows.size() / APERTURE_BINS;
            binUpper[b] = rows.get(to - 1)[0];
            double[] median = new double[options.length];
            double[] column = new double[to - from];
            for (int a = 0; a < options.length; a++) {
                for (int k = from; k < to; k++) {
                    column[k - from] = sigma[(int) rows.get(k)[1]][a];
                }
                median[a] = PhotometryMath.median(column);
            }
            int best = mainIndex;
            for (int a = 0; a < options.length; a++) {
                if (fwhmFactors[a] * sessionFwhm >= APERTURE_MIN_RADIUS_PIXELS && median[a] < median[best]) {
                    best = a;
                }
            }
            binChoice[b] = median[best] < (1 - APERTURE_MIN_GAIN) * median[mainIndex] ? best : mainIndex;
            PipelineTelemetry.PhotometryApertureChoice choice = new PipelineTelemetry.PhotometryApertureChoice();
            choice.magFrom = rows.get(from)[0];
            choice.magTo = binUpper[b];
            choice.fwhmFactor = fwhmFactors[binChoice[b]];
            choice.radiusPixels = fwhmFactors[binChoice[b]] * sessionFwhm;
            choice.scatterChange = (median[binChoice[b]] - median[mainIndex]) / median[mainIndex];
            telemetry.apertureChoices.add(choice);
        }
        for (int i = 0; i < nStars; i++) {
            double mag = main.starMag[i];
            if (!Double.isFinite(mag)) {
                continue;
            }
            int b = 0;
            while (b < APERTURE_BINS - 1 && mag > binUpper[b]) {
                b++;
            }
            EnsembleSolver.Solution chosen = options[binChoice[b]];
            if (chosen != main) {
                System.arraycopy(chosen.residual[i], 0, curves.get(i).deltaMag, 0, curves.get(i).deltaMag.length);
            }
        }
    }

    /** Star mask for the sky annulus from the footprints of the photometry star list, grown by one pixel. */
    static boolean[][] footprintMask(List<SourceExtractor.DetectedObject> stars, int height, int width) {
        boolean[][] mask = new boolean[height][width];
        for (SourceExtractor.DetectedObject star : stars) {
            if (star.rawPixels == null) {
                continue;
            }
            for (SourceExtractor.Pixel p : star.rawPixels) {
                for (int y = Math.max(0, p.y - 1); y <= Math.min(height - 1, p.y + 1); y++) {
                    for (int x = Math.max(0, p.x - 1); x <= Math.min(width - 1, p.x + 1); x++) {
                        mask[y][x] = true;
                    }
                }
            }
        }
        return mask;
    }

    private static double finiteOrZero(double value) {
        return Double.isFinite(value) ? value : 0.0;
    }

    private static EnsembleSolver.Solution solveForAperture(ApertureMeasurer.FrameMeasurement[] measurements,
                                                            int radiusIndex,
                                                            int[][] flags,
                                                            boolean[] starExcluded,
                                                            boolean[] frameActive,
                                                            double[] xNorm,
                                                            double[] yNorm,
                                                            DetectionConfig config) {
        int nStars = flags.length;
        int nFrames = frameActive.length;
        double[][] mag = new double[nStars][nFrames];
        for (int j = 0; j < nFrames; j++) {
            for (int i = 0; i < nStars; i++) {
                mag[i][j] = measurements[j].mag(radiusIndex, i);
            }
        }
        boolean[][] usable = usableMatrix(mag, flags, starExcluded, frameActive);
        return EnsembleSolver.solve(mag, usable, xNorm, yNorm, frameActive, config.photometryFitPlane);
    }

    private static boolean[][] usableMatrix(double[][] mag, int[][] flags, boolean[] starExcluded, boolean[] frameActive) {
        boolean[][] usable = new boolean[mag.length][frameActive.length];
        for (int i = 0; i < mag.length; i++) {
            if (starExcluded[i]) {
                continue;
            }
            for (int j = 0; j < frameActive.length; j++) {
                usable[i][j] = frameActive[j] && (flags[i][j] & EXCLUDING_FLAGS) == 0 && Double.isFinite(mag[i][j]);
            }
        }
        return usable;
    }

    private static void excludeUnsolvedFrames(EnsembleSolver.Solution solution,
                                              PipelineTelemetry.PhotometryFrameStat[] stats,
                                              boolean[] frameActive,
                                              PipelineTelemetry.PhotometryTelemetry telemetry) {
        for (int j = 0; j < frameActive.length; j++) {
            if (frameActive[j] && !solution.frameSolved[j]) {
                exclude(j, stats[j], frameActive, "Too few usable stars for the ensemble");
                telemetry.framesExcludedTooFewStars++;
            }
        }
    }

    private static void decideVerdict(PhotometricReadiness readiness, boolean[] frameActive, DetectionConfig config) {
        int active = 0;
        for (boolean on : frameActive) {
            if (on) {
                active++;
            }
        }
        boolean fail = readiness.quantisationCheck == PhotometricReadiness.CheckStatus.FAIL
                || readiness.shapeLinearityCheck == PhotometricReadiness.CheckStatus.FAIL
                || readiness.shapeLinearityCheck == PhotometricReadiness.CheckStatus.INCONCLUSIVE
                || readiness.responseCheck == PhotometricReadiness.CheckStatus.FAIL;
        if (active < config.variableMinFrames) {
            fail = true;
            readiness.messages.add(String.format(
                    "Only %d frames remain after the photometry checks; at least %d are needed.",
                    active, config.variableMinFrames));
        }
        boolean limited = readiness.quantisationCheck == PhotometricReadiness.CheckStatus.LIMITED
                || readiness.shapeLinearityCheck == PhotometricReadiness.CheckStatus.LIMITED
                || readiness.responseCheck == PhotometricReadiness.CheckStatus.INCONCLUSIVE;
        readiness.verdict = fail ? PhotometricReadiness.Verdict.NOT_READY
                : limited ? PhotometricReadiness.Verdict.LIMITED
                : PhotometricReadiness.Verdict.READY;
    }

    private static VariableStarAnalysis finish(PhotometricReadiness readiness,
                                               List<StarLightCurve> curves,
                                               PipelineTelemetry.PhotometryTelemetry telemetry,
                                               long start) {
        telemetry.verdict = readiness.verdict.name();
        telemetry.readinessMessages.addAll(readiness.messages);
        telemetry.quantisationCheck = readiness.quantisationCheck.name();
        telemetry.distinctPixelLevels = readiness.distinctPixelLevels;
        telemetry.medianFloorClippedFraction = readiness.medianFloorClippedFraction;
        telemetry.shapeLinearityCheck = readiness.shapeLinearityCheck.name();
        telemetry.medianLinearLimitMag = readiness.medianLinearLimitMag;
        telemetry.medianLinearRangeMag = readiness.medianLinearRangeMag;
        telemetry.framesFailingShapeLinearity = readiness.framesFailingShapeLinearity;
        telemetry.responseCheck = readiness.responseCheck.name();
        telemetry.zeroPointRangeMag = readiness.zeroPointRangeMag;
        telemetry.slopeZeroPointCorrelation = readiness.slopeZeroPointCorrelation;
        telemetry.slopeSkyCorrelation = readiness.slopeSkyCorrelation;
        telemetry.framesFailingResponse = readiness.framesFailingResponse;
        telemetry.processingTimeMs = System.currentTimeMillis() - start;
        if (JTransientEngine.DEBUG) {
            System.out.println("DEBUG: Photometry verdict " + readiness.verdict + " " + readiness.messages);
        }
        return new VariableStarAnalysis(readiness, curves, telemetry);
    }

    private static void exclude(int frame, PipelineTelemetry.PhotometryFrameStat stat, boolean[] frameActive, String reason) {
        frameActive[frame] = false;
        stat.used = false;
        stat.exclusionReason = reason;
    }

    private static boolean validFwhm(double fwhm, DetectionConfig config) {
        return Double.isFinite(fwhm) && fwhm > 0.5 && fwhm < config.errorFallbackValue;
    }

    /**
     * Mid-exposure Julian date, or NaN when the frame has no timestamp.
     */
    static double midExposureJulianDate(ImageFrame frame) {
        if (frame.timestamp <= 0) {
            return Double.NaN;
        }
        double mid = frame.timestamp + (frame.exposureDuration > 0 ? frame.exposureDuration / 2.0 : 0.0);
        return mid / 86_400_000.0 + 2_440_587.5;
    }

    private static String format(double value) {
        return Double.isFinite(value) ? String.format("%.3f", value) : "n/a";
    }

    /**
     * Uniform grid of indexed points for radius queries.
     */
    static final class PointGrid {
        private final double cellSize;
        private final Map<Long, List<double[]>> cells = new HashMap<>();

        PointGrid(double cellSize) {
            this.cellSize = cellSize;
        }

        void add(double x, double y, int index) {
            cells.computeIfAbsent(key(cell(x), cell(y)), k -> new ArrayList<>()).add(new double[]{x, y, index});
        }

        boolean anyWithin(double x, double y, double radius) {
            int span = (int) Math.ceil(radius / cellSize);
            int cx = cell(x);
            int cy = cell(y);
            double r2 = radius * radius;
            for (int gy = cy - span; gy <= cy + span; gy++) {
                for (int gx = cx - span; gx <= cx + span; gx++) {
                    List<double[]> list = cells.get(key(gx, gy));
                    if (list == null) {
                        continue;
                    }
                    for (double[] p : list) {
                        double dx = p[0] - x;
                        double dy = p[1] - y;
                        if (dx * dx + dy * dy <= r2) {
                            return true;
                        }
                    }
                }
            }
            return false;
        }

        List<Integer> within(double x, double y, double radius) {
            List<Integer> out = new ArrayList<>();
            int span = (int) Math.ceil(radius / cellSize);
            int cx = cell(x);
            int cy = cell(y);
            double r2 = radius * radius;
            for (int gy = cy - span; gy <= cy + span; gy++) {
                for (int gx = cx - span; gx <= cx + span; gx++) {
                    List<double[]> list = cells.get(key(gx, gy));
                    if (list == null) {
                        continue;
                    }
                    for (double[] p : list) {
                        double dx = p[0] - x;
                        double dy = p[1] - y;
                        if (dx * dx + dy * dy <= r2) {
                            out.add((int) p[2]);
                        }
                    }
                }
            }
            return out;
        }

        private int cell(double v) {
            return (int) Math.floor(v / cellSize);
        }

        private static long key(int gx, int gy) {
            return ((long) gx << 32) ^ (gy & 0xffffffffL);
        }
    }
}
