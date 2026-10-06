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
import io.github.ppissias.jtransient.core.PixelEncoding;
import io.github.ppissias.jtransient.telemetry.PipelineTelemetry;

import java.util.Arrays;
import java.util.Comparator;

/**
 * Readiness checks A, B and D. Each method measures one aspect of linearity; the analyzer combines
 * their outcomes into the session verdict.
 */
final class PhotometricReadinessChecker {

    /** Flags that make a measurement unusable for the shape test of check B. */
    private static final int SHAPE_EXCLUDED_FLAGS =
            PhotometryFlags.EDGE_OR_VOID | PhotometryFlags.CROSSING | PhotometryFlags.BAD_FLUX;
    private static final int MIN_STARS_PER_BIN = 15;
    private static final int TARGET_BINS = 15;
    /** Stars ranked between these brightness fractions define the faint-star reference concentration. */
    private static final double REFERENCE_FROM = 0.5;
    private static final double REFERENCE_TO = 0.9;
    /** A frame's slope must also exceed this many standard errors to fail check D. */
    private static final double SLOPE_SIGNIFICANCE = 3.0;
    /** Check B uses only stars whose main-aperture magnitude error is below this (SNR of about 50 or more). */
    private static final double MAX_SHAPE_MAG_ERROR = 0.02;
    /** A bin may also deviate by this many standard errors of its median concentration. */
    private static final double BIN_NOISE_ALLOWANCE = 2.0;

    /** Per-frame outcome of check B. */
    static final class ShapeResult {
        PhotometricReadiness.CheckStatus status = PhotometricReadiness.CheckStatus.INCONCLUSIVE;
        /** Stars brighter (smaller magnitude) than this are non-linear in the frame; NaN when not determined. */
        double linearLimitMag = Double.NaN;
    }

    /** Session outcome of check D. */
    static final class ResponseResult {
        PhotometricReadiness.CheckStatus status = PhotometricReadiness.CheckStatus.NOT_RUN;
        boolean[] frameFails;
    }

    private PhotometricReadinessChecker() {
    }

    /**
     * Check A: counts distinct pixel levels in one frame.
     */
    static int countDistinctLevels(short[][] pixels) {
        boolean[] seen = new boolean[65536];
        int distinct = 0;
        for (short[] row : pixels) {
            for (short value : row) {
                int v = PixelEncoding.toShiftedPositiveInt(value);
                if (!seen[v]) {
                    seen[v] = true;
                    distinct++;
                }
            }
        }
        return distinct;
    }

    /**
     * Check B on one frame: the concentration index of linear stars does not depend on brightness.
     * Walks from the faint-star reference towards brighter stars and stops at the first magnitude bin
     * whose median concentration departs from the reference by more than the allowed drift.
     *
     * @param m frame measurement
     * @param config configuration with the check B thresholds
     * @param stat frame telemetry, receives the profile and the limit
     */
    static ShapeResult analyzeShape(ApertureMeasurer.FrameMeasurement m,
                                    DetectionConfig config,
                                    PipelineTelemetry.PhotometryFrameStat stat) {
        ShapeResult result = new ShapeResult();
        int n = m.flags.length;
        Integer[] usable = new Integer[n];
        int count = 0;
        double[] mags = new double[n];
        double[] cis = new double[n];
        for (int i = 0; i < n; i++) {
            mags[i] = m.mag(ApertureMeasurer.R_MAIN, i);
            cis[i] = m.concentration(i);
            if ((m.flags[i] & SHAPE_EXCLUDED_FLAGS) == 0 && Double.isFinite(mags[i]) && Double.isFinite(cis[i])
                    && m.magError[i] <= MAX_SHAPE_MAG_ERROR) {
                usable[count++] = i;
            }
        }
        if (count < config.linearityMinStars) {
            stat.shapeLinearityStatus = result.status.name();
            return result;
        }
        Integer[] order = Arrays.copyOf(usable, count);
        Arrays.sort(order, Comparator.comparingDouble(i -> mags[i]));

        // Faint-star reference concentration.
        int refFrom = (int) Math.floor(REFERENCE_FROM * count);
        int refTo = Math.max(refFrom + 1, (int) Math.ceil(REFERENCE_TO * count));
        double[] reference = new double[refTo - refFrom];
        for (int k = refFrom; k < refTo; k++) {
            reference[k - refFrom] = cis[order[k]];
        }
        double referenceCi = PhotometryMath.median(reference);
        double faintBound = mags[order[Math.min(count - 1, refTo - 1)]];

        // Magnitude bins, bright to faint, up to the faint bound.
        int binSize = Math.max(MIN_STARS_PER_BIN, count / TARGET_BINS);
        int binCount = (refTo + binSize - 1) / binSize;
        double[] binMag = new double[binCount];
        double[] binCi = new double[binCount];
        double[] binBrightest = new double[binCount];
        double[] binTolerance = new double[binCount];
        for (int b = 0; b < binCount; b++) {
            int from = b * binSize;
            int to = Math.min(refTo, from + binSize);
            double[] bm = new double[to - from];
            double[] bc = new double[to - from];
            for (int k = from; k < to; k++) {
                bm[k - from] = mags[order[k]];
                bc[k - from] = cis[order[k]];
            }
            binMag[b] = PhotometryMath.median(bm);
            binCi[b] = PhotometryMath.median(bc);
            binBrightest[b] = mags[order[from]];
            double binError = PhotometryMath.robustSigma(bc) / Math.sqrt(bc.length);
            binTolerance[b] = config.linearityMaxConcentrationDrift
                    + (Double.isFinite(binError) ? BIN_NOISE_ALLOWANCE * binError : 0.0);
            PipelineTelemetry.PhotometryProfilePoint point = new PipelineTelemetry.PhotometryProfilePoint();
            point.mag = binMag[b];
            point.concentration = binCi[b];
            point.count = to - from;
            stat.concentrationProfile.add(point);
        }

        int firstLinearBin = 0;
        for (int b = binCount - 1; b >= 0; b--) {
            if (Math.abs(binCi[b] - referenceCi) > binTolerance[b]) {
                firstLinearBin = b + 1;
                break;
            }
        }
        double limit = firstLinearBin < binCount ? binBrightest[firstLinearBin] : faintBound;
        double range = faintBound - limit;
        int linearStars = 0;
        for (int k = 0; k < count; k++) {
            double mag = mags[order[k]];
            if (mag >= limit && mag <= faintBound) {
                linearStars++;
            }
        }

        result.linearLimitMag = limit;
        result.status = (range >= config.linearityMinRangeMag && linearStars >= config.linearityMinStars)
                ? PhotometricReadiness.CheckStatus.PASS
                : PhotometricReadiness.CheckStatus.FAIL;

        stat.concentrationReference = referenceCi;
        stat.linearLimitMag = limit;
        stat.linearRangeMag = range;
        stat.linearStars = linearStars;
        stat.shapeLinearityStatus = result.status.name();
        return result;
    }

    /**
     * Check D: in every solved frame the ensemble residual must not slope against magnitude, and the
     * slopes must not follow the zero point or the sky level across the session.
     *
     * @param solution ensemble solution
     * @param usable measurements that constrained the solve [star][frame]
     * @param frameSky median sky level per frame
     * @param stats frame telemetry, receives slope, zero point and plane terms
     * @param config check D thresholds
     * @param readiness receives the session measurements
     */
    static ResponseResult checkResponse(EnsembleSolver.Solution solution,
                                        boolean[][] usable,
                                        double[] frameSky,
                                        PipelineTelemetry.PhotometryFrameStat[] stats,
                                        DetectionConfig config,
                                        PhotometricReadiness readiness) {
        int nStars = solution.starMag.length;
        int nFrames = solution.zeroPoint.length;
        ResponseResult result = new ResponseResult();
        result.frameFails = new boolean[nFrames];

        double medianMag = PhotometryMath.median(solution.starMag);
        double[] slopes = new double[nFrames];
        double[] zeroPoints = new double[nFrames];
        double[] skies = new double[nFrames];
        Arrays.fill(slopes, Double.NaN);
        Arrays.fill(zeroPoints, Double.NaN);
        Arrays.fill(skies, Double.NaN);

        int solved = 0;
        int failing = 0;
        double[] x = new double[nStars];
        double[] y = new double[nStars];
        double[] w = new double[nStars];
        for (int j = 0; j < nFrames; j++) {
            if (!solution.frameSolved[j]) {
                continue;
            }
            solved++;
            for (int i = 0; i < nStars; i++) {
                boolean ok = usable[i][j] && Double.isFinite(solution.residual[i][j])
                        && Double.isFinite(solution.starSigma[i]);
                x[i] = solution.starMag[i] - medianMag;
                y[i] = ok ? solution.residual[i][j] : Double.NaN;
                w[i] = ok ? 1.0 / (solution.starSigma[i] * solution.starSigma[i]) : 0.0;
            }
            double[] line = PhotometryMath.weightedLine(x, y, w);
            double slope = line[1];
            double slopeError = line[2];
            slopes[j] = slope;
            zeroPoints[j] = solution.zeroPoint[j];
            skies[j] = frameSky[j];

            PipelineTelemetry.PhotometryFrameStat stat = stats[j];
            stat.responseSlope = slope;
            stat.responseSlopeError = slopeError;
            boolean fails = Double.isFinite(slope) && Math.abs(slope) > config.linearityMaxFrameSlope
                    && (!Double.isFinite(slopeError) || Math.abs(slope) > SLOPE_SIGNIFICANCE * slopeError);
            result.frameFails[j] = fails;
            stat.responseStatus = fails ? PhotometricReadiness.CheckStatus.FAIL.name()
                    : PhotometricReadiness.CheckStatus.PASS.name();
            if (fails) {
                failing++;
            }
        }

        if (solved < 3) {
            result.status = PhotometricReadiness.CheckStatus.INCONCLUSIVE;
            readiness.messages.add("Check D could not run: fewer than 3 frames could be solved.");
            return result;
        }

        double zeroPointRange = PhotometryMath.percentile(zeroPoints, 0.95) - PhotometryMath.percentile(zeroPoints, 0.05);
        double skyRange = PhotometryMath.percentile(skies, 0.95) - PhotometryMath.percentile(skies, 0.05);
        double zeroCorrelation = PhotometryMath.correlation(slopes, zeroPoints);
        double skyCorrelation = PhotometryMath.correlation(slopes, skies);
        readiness.zeroPointRangeMag = zeroPointRange;
        readiness.slopeZeroPointCorrelation = zeroCorrelation;
        readiness.slopeSkyCorrelation = skyCorrelation;
        readiness.framesFailingResponse = failing;

        boolean conclusive = zeroPointRange >= config.linearityMinZeroPointRangeMag;
        boolean tracksZeroPoint = conclusive
                && tracks(slopes, zeroPoints, zeroCorrelation, zeroPointRange, config);
        boolean tracksSky = tracks(slopes, skies, skyCorrelation, skyRange, config);
        double failingFraction = failing / (double) solved;

        if (tracksZeroPoint) {
            readiness.messages.add(String.format(
                    "Bright and faint stars respond differently when transparency changes (slope follows zero point, r = %.2f). The frames do not look linear.",
                    zeroCorrelation));
        }
        if (tracksSky) {
            readiness.messages.add(String.format(
                    "The magnitude-dependent response follows the sky level (r = %.2f). This suggests per-frame stretching or sky-subtraction problems.",
                    skyCorrelation));
        }
        if (failingFraction > config.linearityMaxFailingFrameFraction) {
            readiness.messages.add(String.format(
                    "%d of %d frames show a magnitude-dependent response above %.3f mag/mag.",
                    failing, solved, config.linearityMaxFrameSlope));
        }

        if (tracksZeroPoint || tracksSky || failingFraction > config.linearityMaxFailingFrameFraction) {
            result.status = PhotometricReadiness.CheckStatus.FAIL;
        } else if (!conclusive) {
            result.status = PhotometricReadiness.CheckStatus.INCONCLUSIVE;
            readiness.messages.add(String.format(
                    "Transparency barely changed (zero-point range %.3f mag), so check D cannot confirm the response is linear.",
                    zeroPointRange));
        } else {
            result.status = PhotometricReadiness.CheckStatus.PASS;
        }
        return result;
    }

    /**
     * True when the per-frame slopes follow {@code driver} strongly enough to matter: the correlation
     * reaches the configured level and the slope change implied over the driver's range exceeds the
     * per-frame slope limit.
     */
    private static boolean tracks(double[] slopes, double[] driver, double correlation, double driverRange,
                                  DetectionConfig config) {
        if (!Double.isFinite(correlation) || Math.abs(correlation) < config.linearityMaxSlopeTrackingCorrelation
                || !(driverRange > 0)) {
            return false;
        }
        double[] weights = new double[slopes.length];
        for (int j = 0; j < slopes.length; j++) {
            weights[j] = (Double.isFinite(slopes[j]) && Double.isFinite(driver[j])) ? 1.0 : 0.0;
        }
        double[] line = PhotometryMath.weightedLine(driver, slopes, weights);
        return Double.isFinite(line[1]) && Math.abs(line[1]) * driverRange >= config.linearityMaxFrameSlope;
    }
}
