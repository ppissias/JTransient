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
import io.github.ppissias.jtransient.telemetry.PipelineTelemetry;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Scores every light curve against stars of similar brightness in the same session, then runs the
 * candidate gates.
 *
 * <p>Two scores are used: excess scatter (standard deviation after removing isolated outliers) and
 * Stetson J (consecutive measurements deviating in the same direction). A star is a candidate only
 * when both reach the configured robust z-score. The gates then reject candidates explained by too
 * little data, small amplitude, single-frame events, noise, neighbour leakage, systematics, a shared
 * local pattern or non-linearity.</p>
 */
final class VariabilityScorer {

    static final String GATE_DATA = "DATA";
    static final String GATE_AMPLITUDE = "AMPLITUDE";
    static final String GATE_PERSISTENCE = "PERSISTENCE";
    static final String GATE_SPLIT_HALF = "SPLIT_HALF";
    static final String GATE_APERTURE = "APERTURE";
    static final String GATE_SYSTEMATICS = "SYSTEMATICS";
    static final String GATE_LOCAL = "LOCAL";
    static final String GATE_LINEARITY = "LINEARITY";

    /** Isolated points beyond this many robust sigmas are removed as outliers. */
    private static final double OUTLIER_SIGMA = 4.0;
    /** A neighbouring point beyond this many sigmas on the same side keeps an extreme point (real event). */
    private static final double OUTLIER_SUPPORT_SIGMA = 2.0;
    /** Deviation, in expected sigmas, that counts towards a persistence run. */
    private static final double PERSISTENCE_SIGMA = 1.5;
    /** Lower bounds for the neighbour spreads used in the z-scores. */
    private static final double MIN_RELATIVE_SCATTER_SPREAD = 0.05;
    private static final double MIN_J_SPREAD = 0.02;
    /** Nearby stars used in the local-consistency gate. */
    private static final int LOCAL_MAX_STARS = 10;
    private static final int LOCAL_MIN_STARS = 3;
    /** Comparison stars for the local gate must have a scatter z-score below this. */
    private static final double LOCAL_CONSTANT_Z = 2.0;

    /** Per-frame series used by the systematics gate. */
    static final class FrameSeries {
        double[] zeroPoint;
        double[] fwhm;
        double[] offsetX;
        double[] offsetY;
        /** Julian dates, NaN when timestamps are missing. */
        double[] julianDate;
    }

    private VariabilityScorer() {
    }

    /**
     * Scores all stars and assigns tiers.
     *
     * @param stars light curves with residuals and flags filled in
     * @param usable measurements eligible for scoring [star][frame]
     * @param smallResidual residuals from the 1.0 x FWHM aperture solve [star][frame]
     * @param largeResidual residuals from the 2.0 x FWHM aperture solve [star][frame]
     * @param localSky local sky level of each measurement [star][frame]
     * @param series per-frame systematics series
     * @param limited true when the readiness verdict is Limited
     * @param config thresholds
     * @param telemetry receives counters, gate failures and the noise model
     */
    static void score(List<StarLightCurve> stars,
                      boolean[][] usable,
                      double[][] smallResidual,
                      double[][] largeResidual,
                      double[][] localSky,
                      FrameSeries series,
                      boolean limited,
                      DetectionConfig config,
                      PipelineTelemetry.PhotometryTelemetry telemetry) {
        int nStars = stars.size();
        int nFrames = series.zeroPoint.length;
        double[][] curves = new double[nStars][];
        double[] meanMag = new double[nStars];
        double[] scatter = new double[nStars];
        Arrays.fill(scatter, Double.NaN);

        // 1. Usable residuals, isolated-outlier removal, scatter and amplitude.
        for (int i = 0; i < nStars; i++) {
            StarLightCurve star = stars.get(i);
            meanMag[i] = star.meanMag;
            double[] r = new double[nFrames];
            int n = 0;
            for (int j = 0; j < nFrames; j++) {
                r[j] = usable[i][j] ? star.deltaMag[j] : Double.NaN;
                if (Double.isFinite(r[j])) {
                    n++;
                }
            }
            if (n < config.variableMinFrames) {
                star.tier = VariabilityTier.NOT_SCORED;
                if (star.notScoredReason == null) {
                    star.notScoredReason = "Too few usable measurements (" + n + ")";
                }
                star.usableFrames = n;
                continue;
            }
            removeIsolatedOutliers(r, star);
            int usableCount = 0;
            for (double v : r) {
                if (Double.isFinite(v)) {
                    usableCount++;
                }
            }
            star.usableFrames = usableCount;
            star.scatter = PhotometryMath.std(r);
            star.amplitude = PhotometryMath.percentile(r, 0.95) - PhotometryMath.percentile(r, 0.05);
            star.timeSpanMinutes = timeSpanMinutes(r, series.julianDate);
            curves[i] = r;
            scatter[i] = star.scatter;
        }

        // 2. Noise model and scatter z-scores from stars of similar magnitude.
        double[][] scatterStats = PhotometryMath.neighbourStats(meanMag, scatter, config.variableNoiseModelNeighbors);
        double[] stetsonJ = new double[nStars];
        Arrays.fill(stetsonJ, Double.NaN);
        for (int i = 0; i < nStars; i++) {
            if (curves[i] == null) {
                continue;
            }
            StarLightCurve star = stars.get(i);
            star.expectedScatter = scatterStats[0][i];
            if (!(star.expectedScatter > 0)) {
                star.tier = VariabilityTier.NOT_SCORED;
                star.notScoredReason = "No noise model at this magnitude";
                curves[i] = null;
                continue;
            }
            star.excessScatter = star.scatter / star.expectedScatter;
            double spread = Math.max(scatterStats[1][i], MIN_RELATIVE_SCATTER_SPREAD * star.expectedScatter);
            star.scatterZ = (star.scatter - star.expectedScatter) / spread;
            star.stetsonJ = stetsonJ(curves[i], star.expectedScatter);
            stetsonJ[i] = star.stetsonJ;
        }
        double[][] jStats = PhotometryMath.neighbourStats(meanMag, stetsonJ, config.variableNoiseModelNeighbors);

        // 3. Candidate selection.
        for (int i = 0; i < nStars; i++) {
            if (curves[i] == null) {
                continue;
            }
            StarLightCurve star = stars.get(i);
            double spread = Math.max(jStats[1][i], MIN_J_SPREAD);
            star.stetsonJZ = Double.isFinite(jStats[0][i]) ? (star.stetsonJ - jStats[0][i]) / spread : Double.NaN;
            boolean candidate = star.scatterZ >= config.variableScoreSigma
                    && star.stetsonJZ >= config.variableScoreSigma;
            star.tier = candidate ? VariabilityTier.REJECTED : VariabilityTier.CONSTANT;
        }

        // 4. Systematic responses of every scored star, used to judge whether a correlated systematic
        //    could produce a candidate's change at all.
        SystematicsContext systematics = new SystematicsContext(stars, curves, localSky, series);

        // 5. Gates for candidates.
        for (int i = 0; i < nStars; i++) {
            StarLightCurve star = stars.get(i);
            if (curves[i] == null || star.tier == VariabilityTier.CONSTANT) {
                continue;
            }
            runGates(i, star, curves, stars, smallResidual[i], largeResidual[i], localSky[i], series, systematics, limited, config);
            int failures = star.failedGates.size();
            star.tier = failures == 0 ? VariabilityTier.HIGH_CONFIDENCE
                    : failures == 1 ? VariabilityTier.POSSIBLE
                    : VariabilityTier.REJECTED;
            for (String gate : star.failedGates) {
                telemetry.gateFailureCounts.merge(gate, 1, Integer::sum);
            }
        }

        // 5. Telemetry.
        for (StarLightCurve star : stars) {
            switch (star.tier) {
                case NOT_SCORED:
                    telemetry.starsNotScored++;
                    break;
                case HIGH_CONFIDENCE:
                    telemetry.highConfidence++;
                    break;
                case POSSIBLE:
                    telemetry.possible++;
                    break;
                case REJECTED:
                    telemetry.rejectedCandidates++;
                    break;
                default:
                    break;
            }
            if (star.tier != VariabilityTier.NOT_SCORED) {
                telemetry.starsScored++;
                PipelineTelemetry.PhotometryNoisePoint point = new PipelineTelemetry.PhotometryNoisePoint();
                point.mag = star.meanMag;
                point.scatter = star.scatter;
                point.expectedScatter = star.expectedScatter;
                point.tier = star.tier.name();
                telemetry.noiseModel.add(point);
            }
        }
        telemetry.candidates = telemetry.highConfidence + telemetry.possible + telemetry.rejectedCandidates;
        telemetry.noiseModel.sort((a, b) -> Double.compare(a.mag, b.mag));
    }

    private static void runGates(int index,
                                 StarLightCurve star,
                                 double[][] curves,
                                 List<StarLightCurve> stars,
                                 double[] small,
                                 double[] large,
                                 double[] sky,
                                 FrameSeries series,
                                 SystematicsContext systematics,
                                 boolean limited,
                                 DetectionConfig config) {
        double[] r = curves[index];
        double sigma = star.expectedScatter;

        // DATA
        boolean hasTime = Double.isFinite(star.timeSpanMinutes);
        if (star.usableFrames < config.variableMinFrames
                || (hasTime && star.timeSpanMinutes < config.variableMinSpanMinutes)) {
            star.failedGates.add(GATE_DATA);
        }

        // AMPLITUDE
        double minAmplitude = limited ? config.variableLimitedMinAmplitudeMag : config.variableMinAmplitudeMag;
        if (!(star.amplitude >= minAmplitude && star.amplitude >= config.variableAmplitudeNoiseFactor * sigma)) {
            star.failedGates.add(GATE_AMPLITUDE);
        }

        // PERSISTENCE
        star.longestDeviatingRun = longestDeviatingRun(r, PERSISTENCE_SIGMA * sigma);
        if (star.longestDeviatingRun < config.variableMinPersistenceFrames) {
            star.failedGates.add(GATE_PERSISTENCE);
        }

        // SPLIT_HALF
        star.splitHalfCorrelation = splitHalfCorrelation(r);
        if (!(star.splitHalfCorrelation >= config.variableMinSplitHalfCorrelation)) {
            star.failedGates.add(GATE_SPLIT_HALF);
        }

        // APERTURE
        double[] smallCurve = new double[r.length];
        double[] largeCurve = new double[r.length];
        for (int j = 0; j < r.length; j++) {
            boolean ok = Double.isFinite(r[j]);
            smallCurve[j] = ok ? small[j] : Double.NaN;
            largeCurve[j] = ok ? large[j] : Double.NaN;
        }
        double smallAmplitude = PhotometryMath.percentile(smallCurve, 0.95) - PhotometryMath.percentile(smallCurve, 0.05);
        double largeAmplitude = PhotometryMath.percentile(largeCurve, 0.95) - PhotometryMath.percentile(largeCurve, 0.05);
        star.apertureAmplitudeDifference = Math.abs(smallAmplitude - largeAmplitude) / star.amplitude;
        if (!(star.apertureAmplitudeDifference <= config.variableMaxApertureAmplitudeDifference)) {
            star.failedGates.add(GATE_APERTURE);
        }

        // SYSTEMATICS, two tests per systematic:
        // 1. Frame to frame: the changes between consecutive frames must not follow the changes of the
        //    systematic. A blend leaking with seeing jumps whenever the FWHM jumps and fails here whatever
        //    the size of the change; a smooth real variable barely changes between frames.
        // 2. Trend: a steady variable correlates with any steady drift in the session, so a raw correlation
        //    only fails the star when constant stars of similar brightness respond to that systematic with
        //    changes of comparable size.
        double[][] drivers = SystematicsContext.drivers(series, sky);
        double worst = 0;
        double worstFrameToFrame = 0;
        int worstDriver = -1;
        boolean explained = false;
        for (int d = 0; d < drivers.length; d++) {
            double c = Math.abs(PhotometryMath.correlation(r, drivers[d]));
            if (!Double.isFinite(c)) {
                continue;
            }
            double frameToFrame = Math.abs(differenceCorrelation(r, drivers[d]));
            if (Double.isFinite(frameToFrame)) {
                worstFrameToFrame = Math.max(worstFrameToFrame, frameToFrame);
                if (frameToFrame > config.variableMaxSystematicsCorrelation) {
                    explained = true;
                }
            }
            if (c > worst) {
                worst = c;
                worstDriver = d;
            }
            if (c > config.variableMaxSystematicsCorrelation) {
                double limit = config.variableSystematicsResponseFactor * systematics.typicalMaxResponse(d, star.meanMag);
                if (!(star.amplitude > limit)) {
                    explained = true;
                }
            }
        }
        star.maxFrameToFrameSystematicsCorrelation = worstFrameToFrame;
        star.maxSystematicsCorrelation = worst;
        star.maxSystematicsSource = worstDriver >= 0 ? SystematicsContext.NAMES[worstDriver] : null;
        if (worstDriver >= 0) {
            star.systematicsLimitMag = config.variableSystematicsResponseFactor
                    * systematics.typicalMaxResponse(worstDriver, star.meanMag);
        }
        if (explained) {
            star.failedGates.add(GATE_SYSTEMATICS);
        }

        // LOCAL
        List<StarLightCurve> nearby = new ArrayList<>();
        for (int k = 0; k < stars.size(); k++) {
            StarLightCurve other = stars.get(k);
            if (k == index || curves[k] == null || other.tier != VariabilityTier.CONSTANT
                    || !(other.scatterZ < LOCAL_CONSTANT_Z)) {
                continue;
            }
            if (Math.hypot(other.x - star.x, other.y - star.y) <= config.variableLocalRadiusPixels) {
                nearby.add(other);
            }
        }
        nearby.sort((a, b) -> Double.compare(Math.hypot(a.x - star.x, a.y - star.y), Math.hypot(b.x - star.x, b.y - star.y)));
        int used = Math.min(LOCAL_MAX_STARS, nearby.size());
        double[] correlations = new double[used];
        for (int k = 0; k < used; k++) {
            correlations[k] = Math.abs(PhotometryMath.correlation(r, curves[nearby.get(k).id]));
        }
        star.localComparisonStars = used;
        star.localCorrelation = PhotometryMath.median(correlations);
        if (used < LOCAL_MIN_STARS || !(star.localCorrelation <= config.variableMaxLocalCorrelation)) {
            star.failedGates.add(GATE_LOCAL);
        }

        // LINEARITY
        for (int flag : star.flags) {
            if ((flag & (PhotometryFlags.SATURATED | PhotometryFlags.NONLINEAR)) != 0) {
                star.failedGates.add(GATE_LINEARITY);
                break;
            }
        }
    }

    /**
     * Correlation between the changes of two series from one usable frame to the next. Smooth trends
     * contribute little; quantities that jump together from frame to frame correlate strongly.
     */
    static double differenceCorrelation(double[] a, double[] b) {
        double[] da = new double[a.length];
        double[] db = new double[a.length];
        int n = 0;
        int previous = -1;
        for (int j = 0; j < a.length; j++) {
            if (!Double.isFinite(a[j]) || !Double.isFinite(b[j])) {
                continue;
            }
            if (previous >= 0) {
                da[n] = a[j] - a[previous];
                db[n] = b[j] - b[previous];
                n++;
            }
            previous = j;
        }
        return PhotometryMath.correlation(Arrays.copyOf(da, n), Arrays.copyOf(db, n));
    }

    /**
     * Response of every scored star to each systematic driver: |slope of its light curve against the
     * driver| x the driver's 5th-95th percentile range, i.e. the change in mag the driver accounts for.
     * Constant stars give the typical size of such responses at each brightness.
     */
    static final class SystematicsContext {
        static final String[] NAMES = {"zero point", "FWHM", "sky", "x offset", "y offset"};
        /** Constant stars of similar brightness used for the response distribution. */
        private static final int NEIGHBOURS = 200;
        private static final double RESPONSE_PERCENTILE = 0.99;

        private final double[][] response;
        private final double[] constantMags;
        private final int[] constantIndex;

        SystematicsContext(List<StarLightCurve> stars, double[][] curves, double[][] localSky, FrameSeries series) {
            int n = stars.size();
            response = new double[NAMES.length][n];
            List<Integer> constants = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                double[][] drivers = drivers(series, localSky[i]);
                for (int d = 0; d < NAMES.length; d++) {
                    response[d][i] = curves[i] == null ? Double.NaN : responseAmplitude(curves[i], drivers[d]);
                }
                if (curves[i] != null && stars.get(i).tier == VariabilityTier.CONSTANT && Double.isFinite(stars.get(i).meanMag)) {
                    constants.add(i);
                }
            }
            constants.sort((a, b) -> Double.compare(stars.get(a).meanMag, stars.get(b).meanMag));
            constantIndex = new int[constants.size()];
            constantMags = new double[constants.size()];
            for (int k = 0; k < constants.size(); k++) {
                constantIndex[k] = constants.get(k);
                constantMags[k] = stars.get(constants.get(k)).meanMag;
            }
        }

        static double[][] drivers(FrameSeries series, double[] sky) {
            return new double[][]{series.zeroPoint, series.fwhm, sky, series.offsetX, series.offsetY};
        }

        /**
         * 99th percentile of the response to driver {@code d} among the constant stars nearest in magnitude.
         */
        double typicalMaxResponse(int d, double mag) {
            int count = constantMags.length;
            if (count == 0 || !Double.isFinite(mag)) {
                return Double.NaN;
            }
            int position = Arrays.binarySearch(constantMags, mag);
            if (position < 0) {
                position = -position - 1;
            }
            int window = Math.min(NEIGHBOURS, count);
            int from = Math.max(0, Math.min(position - window / 2, count - window));
            double[] values = new double[window];
            for (int k = 0; k < window; k++) {
                values[k] = response[d][constantIndex[from + k]];
            }
            return PhotometryMath.percentile(values, RESPONSE_PERCENTILE);
        }

        private static double responseAmplitude(double[] curve, double[] driver) {
            double[] w = new double[curve.length];
            double[] used = new double[curve.length];
            for (int j = 0; j < curve.length; j++) {
                boolean ok = Double.isFinite(curve[j]) && Double.isFinite(driver[j]);
                w[j] = ok ? 1.0 : 0.0;
                used[j] = ok ? driver[j] : Double.NaN;
            }
            double slope = PhotometryMath.weightedLine(driver, curve, w)[1];
            double range = PhotometryMath.percentile(used, 0.95) - PhotometryMath.percentile(used, 0.05);
            return Double.isFinite(slope) && Double.isFinite(range) ? Math.abs(slope) * range : Double.NaN;
        }
    }

    /**
     * Removes points beyond {@link #OUTLIER_SIGMA} unless a neighbouring point deviates on the same side,
     * so single-frame events are dropped while real dips or flares spanning several frames survive.
     */
    static void removeIsolatedOutliers(double[] r, StarLightCurve star) {
        double median = PhotometryMath.median(r);
        double sigma = PhotometryMath.robustSigma(r);
        if (!(sigma > 0)) {
            return;
        }
        boolean[] remove = new boolean[r.length];
        for (int j = 0; j < r.length; j++) {
            if (!Double.isFinite(r[j])) {
                continue;
            }
            double dev = r[j] - median;
            if (Math.abs(dev) <= OUTLIER_SIGMA * sigma) {
                continue;
            }
            boolean supported = false;
            int prev = previousFinite(r, j);
            int next = nextFinite(r, j);
            for (int k : new int[]{prev, next}) {
                if (k >= 0) {
                    double neighbourDev = r[k] - median;
                    if (Math.signum(neighbourDev) == Math.signum(dev)
                            && Math.abs(neighbourDev) > OUTLIER_SUPPORT_SIGMA * sigma) {
                        supported = true;
                    }
                }
            }
            remove[j] = !supported;
        }
        for (int j = 0; j < r.length; j++) {
            if (remove[j]) {
                r[j] = Double.NaN;
                star.flags[j] |= PhotometryFlags.OUTLIER;
            }
        }
    }

    /**
     * Stetson (1996) J index over consecutive usable points, using the expected scatter as the error.
     */
    static double stetsonJ(double[] r, double sigma) {
        int n = 0;
        double sum = 0;
        for (double v : r) {
            if (Double.isFinite(v)) {
                sum += v;
                n++;
            }
        }
        if (n < 3) {
            return Double.NaN;
        }
        double mean = sum / n;
        double scale = Math.sqrt(n / (double) (n - 1)) / sigma;
        double total = 0;
        int pairs = 0;
        int previous = -1;
        for (int j = 0; j < r.length; j++) {
            if (!Double.isFinite(r[j])) {
                continue;
            }
            if (previous >= 0) {
                double p = scale * (r[previous] - mean) * scale * (r[j] - mean);
                total += Math.signum(p) * Math.sqrt(Math.abs(p));
                pairs++;
            }
            previous = j;
        }
        return pairs > 0 ? total / pairs : Double.NaN;
    }

    /**
     * Longest run of consecutive usable points deviating from the median by more than {@code threshold}
     * on the same side.
     */
    static int longestDeviatingRun(double[] r, double threshold) {
        double median = PhotometryMath.median(r);
        int longest = 0;
        int run = 0;
        int side = 0;
        for (double v : r) {
            if (!Double.isFinite(v)) {
                continue;
            }
            double dev = v - median;
            int s = dev > threshold ? 1 : dev < -threshold ? -1 : 0;
            if (s != 0 && s == side) {
                run++;
            } else {
                run = s != 0 ? 1 : 0;
                side = s;
            }
            longest = Math.max(longest, run);
        }
        return longest;
    }

    /**
     * Correlation between the first and second point of consecutive, non-overlapping pairs of usable points.
     * White noise gives about zero; a real change spanning several frames gives a high value.
     */
    static double splitHalfCorrelation(double[] r) {
        List<Double> first = new ArrayList<>();
        List<Double> second = new ArrayList<>();
        int pending = -1;
        for (int j = 0; j < r.length; j++) {
            if (!Double.isFinite(r[j])) {
                continue;
            }
            if (pending < 0) {
                pending = j;
            } else {
                first.add(r[pending]);
                second.add(r[j]);
                pending = -1;
            }
        }
        double[] a = new double[first.size()];
        double[] b = new double[second.size()];
        for (int k = 0; k < a.length; k++) {
            a[k] = first.get(k);
            b[k] = second.get(k);
        }
        return PhotometryMath.correlation(a, b);
    }

    private static double timeSpanMinutes(double[] r, double[] julianDate) {
        double first = Double.NaN;
        double last = Double.NaN;
        for (int j = 0; j < r.length; j++) {
            if (!Double.isFinite(r[j])) {
                continue;
            }
            if (!Double.isFinite(julianDate[j])) {
                return Double.NaN;
            }
            if (!Double.isFinite(first)) {
                first = julianDate[j];
            }
            last = julianDate[j];
        }
        return Double.isFinite(first) ? (last - first) * 24.0 * 60.0 : Double.NaN;
    }

    private static int previousFinite(double[] r, int j) {
        for (int k = j - 1; k >= 0; k--) {
            if (Double.isFinite(r[k])) {
                return k;
            }
        }
        return -1;
    }

    private static int nextFinite(double[] r, int j) {
        for (int k = j + 1; k < r.length; k++) {
            if (Double.isFinite(r[k])) {
                return k;
            }
        }
        return -1;
    }
}
