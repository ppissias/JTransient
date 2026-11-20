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
import io.github.ppissias.jtransient.core.FaintStreakSearch;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Checks how well the input frames are registered on the stars, from the positions of the bright round stars that
 * quality control measured in each frame ({@link FrameQualityAnalyzer.FrameMetrics#registrationStars}).
 *
 * <p>The engine does not register frames: they arrive registered, and the drift analysis only finds how each was moved
 * from its fill. Registration that does not follow the field, such as a lens distortion it does not model, leaves the
 * stars moving between frames, and every residual stage then sees their light past the masks as signal.</p>
 *
 * <p>The reference is each star's median position over the {@link #REFERENCE_FRAMES} kept frames with the smallest
 * stars. Each kept frame is matched to it; its offset is the median over its stars, and each of {@link #ZONES} by
 * {@link #ZONES} zones has a residual offset, its median minus the frame's. Measured in star widths (the kept frames'
 * median FWHM):</p>
 * <ul>
 *   <li><b>jump</b>: the frame's offset, or a zone's residual, against the median of up to {@link #NEIGHBOURS} kept
 *   frames on each side in time. Above {@link DetectionConfig#qualityMaxRegistrationJumpFwhm} the frame's registration
 *   failed and it is rejected.</li>
 *   <li><b>creep</b>: the largest distance between two times of the offset, or of a zone's residual, each smoothed
 *   over {@link #SMOOTHING} frames on each side. A slow change is the session's registration, not one frame's, so it is
 *   reported, flagged above {@link DetectionConfig#qualityMaxRegistrationCreepFwhm}, and no frame is rejected for it.</li>
 * </ul>
 * <p>Frames are taken in the given order, which is time order in the engine. Frames already rejected stay out of the
 * reference, the neighbours and the creep.</p>
 *
 * <p>Variable-star photometry has a related measurement of its own, on purpose: {@code ApertureMeasurer} takes each
 * frame's offset of the photometry stars from their master-stack positions to place the apertures, and
 * {@code VariableStarAnalyzer} leaves frames whose stars scatter around that offset by more than
 * {@link DetectionConfig#photometryMaxRegistrationSpreadPixels} out of photometry only. That gate judges one frame in
 * pixels for aperture placement; this check runs before every stage, compares frames in time and maps the field.</p>
 */
public final class RegistrationCheck {
    /** Zones along each side of the frame. */
    public static final int ZONES = 4;
    /** Kept frames with the smallest stars that make the reference. */
    static final int REFERENCE_FRAMES = 5;
    /** A reference star must be found in this many of the reference frames. */
    static final int MIN_REFERENCE_MATCHES = 3;
    /** Fewest matched stars for a frame's offset. */
    static final int MIN_FRAME_STARS = 10;
    /** Fewest matched stars for a zone's residual. */
    static final int MIN_ZONE_STARS = 5;
    /** Kept frames on each side in time that a frame is compared with. */
    static final int NEIGHBOURS = 3;
    /** Kept frames on each side over which a series is smoothed for the creep. */
    static final int SMOOTHING = 2;
    /** Stars are matched within this many star widths, and at least {@link #MIN_MATCH_RADIUS_PX}. */
    static final double MATCH_RADIUS_FWHM = 1.0;
    static final double MIN_MATCH_RADIUS_PX = 2.0;
    /** At most this many stars per frame are kept for the check, the brightest. */
    public static final int MAX_STARS_PER_FRAME = 3000;

    private RegistrationCheck() {
    }

    /** What the check found; {@link #available} false with a {@link #reason} when it could not measure. */
    public static final class Result {
        /** Whether the check measured the session. */
        public boolean available;
        /** Why the check did not measure, when not available. */
        public String reason;
        /** Star width used as the unit, in pixels. */
        public double fwhmPixels = Double.NaN;
        /** Kept frames with a measured offset. */
        public int framesMeasured;
        /** Reference stars. */
        public int referenceStars;
        /** Frames rejected for a jump. */
        public int framesRejected;
        /** The largest jump of a kept frame, in FWHM. */
        public double largestKeptJumpFwhm = Double.NaN;
        /** Creep of the whole frame's offset, in FWHM. */
        public double overallCreepFwhm = Double.NaN;
        /** Creep of each zone's residual, in FWHM, [row][column] from the top left; NaN with too few stars. */
        public double[][] zoneCreepFwhm = nanGrid();
        /** Reference stars per zone, [row][column]. */
        public int[][] zoneStars = new int[ZONES][ZONES];
        /** Frame size the zones divide. */
        public int width;
        public int height;
        /** Creep above which a zone or the whole frame is flagged, in FWHM. */
        public double creepLimitFwhm = Double.NaN;

        /** Whether the whole frame's offset creeps above the limit. */
        public boolean overallFlagged() {
            return available && overallCreepFwhm > creepLimitFwhm;
        }

        /** Whether the zone at [row][column] creeps above the limit. */
        public boolean zoneFlagged(int row, int column) {
            return available && zoneCreepFwhm[row][column] > creepLimitFwhm;
        }

        /** Pixel rectangle {x, y, width, height} of the zone at [row][column]. */
        public int[] zoneRectangle(int row, int column) {
            return RegistrationCheck.zoneRectangle(width, height, row, column);
        }

        private static double[][] nanGrid() {
            double[][] grid = new double[ZONES][ZONES];
            for (double[] row : grid) {
                Arrays.fill(row, Double.NaN);
            }
            return grid;
        }
    }

    /** Pixel rectangle {x, y, width, height} of the zone at [row][column] of a frame of this size. */
    public static int[] zoneRectangle(int width, int height, int row, int column) {
        int x0 = column * width / ZONES;
        int y0 = row * height / ZONES;
        return new int[]{x0, y0, (column + 1) * width / ZONES - x0, (row + 1) * height / ZONES - y0};
    }

    /**
     * The flagged zones as rectangles {x, y, width, height} to exclude: in each column, vertically adjacent flagged
     * zones become one rectangle, left to right and top to bottom.
     *
     * @param zoneCreepFwhm each zone's creep, row-major from the top left ({@code zones} by {@code zones})
     * @param limitFwhm creep above which a zone is flagged
     */
    public static List<int[]> flaggedAreas(int width, int height, int zones, double[] zoneCreepFwhm, double limitFwhm) {
        List<int[]> areas = new ArrayList<>();
        for (int column = 0; column < zones; column++) {
            int row = 0;
            while (row < zones) {
                if (!(zoneCreepFwhm[row * zones + column] > limitFwhm)) {
                    row++;
                    continue;
                }
                int first = row;
                while (row < zones && zoneCreepFwhm[row * zones + column] > limitFwhm) {
                    row++;
                }
                int[] top = zoneRectangle(width, height, first, column);
                int[] bottom = zoneRectangle(width, height, row - 1, column);
                areas.add(new int[]{top[0], top[1], top[2], bottom[1] + bottom[3] - top[1]});
            }
        }
        return areas;
    }

    /**
     * Runs the check on a session's frame metrics in time order: rejects frames whose registration jumps (setting their
     * rejection reason) and measures the creep. Does nothing but report when {@link DetectionConfig#enableRegistrationCheck}
     * is off.
     *
     * @param frames the session's frames in time order; rejected frames are left out
     * @param width frame width in pixels
     * @param height frame height in pixels
     */
    public static Result evaluate(List<FrameQualityAnalyzer.FrameMetrics> frames, int width, int height,
                                  DetectionConfig config) {
        Result result = new Result();
        result.width = width;
        result.height = height;
        result.creepLimitFwhm = config.qualityMaxRegistrationCreepFwhm;
        if (!config.enableRegistrationCheck) {
            result.reason = "switched off";
            return result;
        }
        List<FrameQualityAnalyzer.FrameMetrics> kept = new ArrayList<>();
        for (FrameQualityAnalyzer.FrameMetrics frame : frames) {
            if (!frame.isRejected && frame.registrationStars != null
                    && frame.registrationStars.length / 2 >= MIN_FRAME_STARS) {
                kept.add(frame);
            }
        }
        if (kept.size() < REFERENCE_FRAMES) {
            result.reason = "fewer than " + REFERENCE_FRAMES + " kept frames with " + MIN_FRAME_STARS + " bright stars";
            return result;
        }
        // The star width of the faint searches: a Gaussian FWHM from the kept frames' measured star sizes.
        FaintStreakSearch.Width starWidth = FaintStreakSearch.width(kept, config);
        if (starWidth.frames == 0) {
            result.reason = "no star size measured";
            return result;
        }
        double fwhm = starWidth.fwhm();
        result.fwhmPixels = fwhm;
        double radius = Math.max(MIN_MATCH_RADIUS_PX, MATCH_RADIUS_FWHM * fwhm);

        // Reference: the best frame's stars, at their median position over the reference frames.
        List<FrameQualityAnalyzer.FrameMetrics> best = new ArrayList<>(kept);
        best.sort(Comparator.comparingDouble(f -> f.medianFWHM));
        best = best.subList(0, REFERENCE_FRAMES);
        StarIndex[] bestIndex = best.stream().map(f -> new StarIndex(f.registrationStars, radius)).toArray(StarIndex[]::new);
        float[] seed = best.get(0).registrationStars;
        List<double[]> reference = new ArrayList<>();
        for (int s = 0; s < seed.length / 2; s++) {
            double[] xs = new double[REFERENCE_FRAMES];
            double[] ys = new double[REFERENCE_FRAMES];
            int found = 0;
            for (StarIndex index : bestIndex) {
                int m = index.nearest(seed[2 * s], seed[2 * s + 1]);
                if (m >= 0) {
                    xs[found] = index.stars[2 * m];
                    ys[found] = index.stars[2 * m + 1];
                    found++;
                }
            }
            if (found >= MIN_REFERENCE_MATCHES) {
                reference.add(new double[]{median(Arrays.copyOf(xs, found)), median(Arrays.copyOf(ys, found))});
            }
        }
        result.referenceStars = reference.size();
        if (reference.size() < MIN_FRAME_STARS) {
            result.reason = "fewer than " + MIN_FRAME_STARS + " stars found in the reference frames";
            return result;
        }
        int[] zone = new int[reference.size()];
        for (int s = 0; s < zone.length; s++) {
            double[] r = reference.get(s);
            int column = Math.min(ZONES - 1, Math.max(0, (int) (r[0] * ZONES / width)));
            int row = Math.min(ZONES - 1, Math.max(0, (int) (r[1] * ZONES / height)));
            zone[s] = row * ZONES + column;
            result.zoneStars[row][column]++;
        }

        // Offsets: per frame the overall one, per zone the residual; NaN where too few stars.
        int n = kept.size();
        double[][] overall = new double[n][];
        double[][][] zones = new double[n][ZONES * ZONES][];
        for (int k = 0; k < n; k++) {
            StarIndex index = new StarIndex(kept.get(k).registrationStars, radius);
            List<List<double[]>> byZone = new ArrayList<>();
            for (int z = 0; z < ZONES * ZONES; z++) {
                byZone.add(new ArrayList<>());
            }
            List<double[]> all = new ArrayList<>();
            for (int s = 0; s < reference.size(); s++) {
                double[] r = reference.get(s);
                int m = index.nearest(r[0], r[1]);
                if (m >= 0) {
                    double[] d = {index.stars[2 * m] - r[0], index.stars[2 * m + 1] - r[1]};
                    all.add(d);
                    byZone.get(zone[s]).add(d);
                }
            }
            if (all.size() < MIN_FRAME_STARS) {
                continue;
            }
            overall[k] = medianVector(all);
            for (int z = 0; z < ZONES * ZONES; z++) {
                if (byZone.get(z).size() >= MIN_ZONE_STARS) {
                    double[] m = medianVector(byZone.get(z));
                    zones[k][z] = new double[]{m[0] - overall[k][0], m[1] - overall[k][1]};
                }
            }
        }

        // Jumps against the neighbouring frames in time.
        double[] jump = new double[n];
        for (int k = 0; k < n; k++) {
            if (overall[k] == null) {
                continue;
            }
            double worst = distanceToNeighbours(overall, k);
            for (int z = 0; z < ZONES * ZONES; z++) {
                worst = Math.max(worst, distanceToNeighbours(zoneSeries(zones, z), k));
            }
            jump[k] = worst / fwhm;
        }
        boolean[] rejected = new boolean[n];
        double largestKept = 0;
        for (int k = 0; k < n; k++) {
            if (overall[k] == null) {
                continue;
            }
            if (jump[k] > config.qualityMaxRegistrationJumpFwhm) {
                rejected[k] = true;
                FrameQualityAnalyzer.FrameMetrics frame = kept.get(k);
                frame.isRejected = true;
                frame.rejectionReason = String.format(Locale.US,
                        "Registration jump: stars moved %.2f FWHM against the neighbouring frames (limit %.2f)",
                        jump[k], config.qualityMaxRegistrationJumpFwhm);
                result.framesRejected++;
            } else {
                largestKept = Math.max(largestKept, jump[k]);
                result.framesMeasured++;
            }
        }
        result.largestKeptJumpFwhm = largestKept;

        // Creep of the smoothed series, without the rejected frames.
        for (int k = 0; k < n; k++) {
            if (rejected[k]) {
                overall[k] = null;
                Arrays.fill(zones[k], null);
            }
        }
        result.overallCreepFwhm = creep(overall) / fwhm;
        for (int z = 0; z < ZONES * ZONES; z++) {
            result.zoneCreepFwhm[z / ZONES][z % ZONES] = creep(zoneSeries(zones, z)) / fwhm;
        }
        result.available = true;
        return result;
    }

    /** Distance of entry k from the median of up to {@link #NEIGHBOURS} measured entries on each side; 0 without any. */
    private static double distanceToNeighbours(double[][] series, int k) {
        if (series[k] == null) {
            return 0;
        }
        List<double[]> around = new ArrayList<>();
        for (int j = k - 1, taken = 0; j >= 0 && taken < NEIGHBOURS; j--) {
            if (series[j] != null) {
                around.add(series[j]);
                taken++;
            }
        }
        for (int j = k + 1, taken = 0; j < series.length && taken < NEIGHBOURS; j++) {
            if (series[j] != null) {
                around.add(series[j]);
                taken++;
            }
        }
        if (around.isEmpty()) {
            return 0;
        }
        double[] m = medianVector(around);
        return Math.hypot(series[k][0] - m[0], series[k][1] - m[1]);
    }

    /** Largest distance between two entries of the series smoothed over {@link #SMOOTHING} entries; NaN with too few. */
    private static double creep(double[][] series) {
        List<double[]> measured = new ArrayList<>();
        for (double[] v : series) {
            if (v != null) {
                measured.add(v);
            }
        }
        if (measured.size() < 2 * SMOOTHING + 1) {
            return Double.NaN;
        }
        List<double[]> smooth = new ArrayList<>();
        for (int k = 0; k < measured.size(); k++) {
            smooth.add(medianVector(measured.subList(Math.max(0, k - SMOOTHING), Math.min(measured.size(), k + SMOOTHING + 1))));
        }
        double largest = 0;
        for (int a = 0; a < smooth.size(); a++) {
            for (int b = a + 1; b < smooth.size(); b++) {
                largest = Math.max(largest, Math.hypot(smooth.get(a)[0] - smooth.get(b)[0], smooth.get(a)[1] - smooth.get(b)[1]));
            }
        }
        return largest;
    }

    private static double[][] zoneSeries(double[][][] zones, int z) {
        double[][] series = new double[zones.length][];
        for (int k = 0; k < zones.length; k++) {
            series[k] = zones[k][z];
        }
        return series;
    }

    private static double[] medianVector(List<double[]> values) {
        double[] xs = new double[values.size()];
        double[] ys = new double[values.size()];
        for (int i = 0; i < xs.length; i++) {
            xs[i] = values.get(i)[0];
            ys[i] = values.get(i)[1];
        }
        return new double[]{median(xs), median(ys)};
    }

    private static double median(double[] values) {
        double[] copy = values.clone();
        Arrays.sort(copy);
        int m = copy.length / 2;
        return copy.length % 2 == 1 ? copy[m] : 0.5 * (copy[m - 1] + copy[m]);
    }

    /** A frame's star positions in a grid of cells one match radius wide, for nearest-star lookups. */
    private static final class StarIndex {
        final float[] stars;
        private final double radius;
        private final java.util.Map<Long, int[]> cells = new java.util.HashMap<>();

        StarIndex(float[] stars, double radius) {
            this.stars = stars;
            this.radius = radius;
            java.util.Map<Long, List<Integer>> lists = new java.util.HashMap<>();
            for (int s = 0; s < stars.length / 2; s++) {
                lists.computeIfAbsent(key(cell(stars[2 * s]), cell(stars[2 * s + 1])), k -> new ArrayList<>()).add(s);
            }
            lists.forEach((k, v) -> cells.put(k, v.stream().mapToInt(Integer::intValue).toArray()));
        }

        /** Index of the nearest star within the radius, or -1. */
        int nearest(double x, double y) {
            long cx = cell(x);
            long cy = cell(y);
            int best = -1;
            double bestDistance = radius;
            for (long dy = -1; dy <= 1; dy++) {
                for (long dx = -1; dx <= 1; dx++) {
                    int[] list = cells.get(key(cx + dx, cy + dy));
                    if (list == null) {
                        continue;
                    }
                    for (int s : list) {
                        double d = Math.hypot(stars[2 * s] - x, stars[2 * s + 1] - y);
                        if (d <= bestDistance) {
                            bestDistance = d;
                            best = s;
                        }
                    }
                }
            }
            return best;
        }

        private long cell(double v) {
            return (long) Math.floor(v / radius);
        }

        private static long key(long cx, long cy) {
            return (cx << 32) ^ (cy & 0xffffffffL);
        }
    }
}
