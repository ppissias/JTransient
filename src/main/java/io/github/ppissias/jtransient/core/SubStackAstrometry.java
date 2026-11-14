/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 * author: Petros Pissias <petrospis at gmail.com>
 *
 */
package io.github.ppissias.jtransient.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Measures a faint mover's positions in independent sub-stacks, for astrometry of an object too faint for single
 * frames. The frames are split in time order into groups, each frame used once; each group is shifted along the
 * fitted motion so the object lands at its place at the group's mean time, and averaged. A per-pixel median over all
 * frames is subtracted first, so the fixed stars cancel. In each sub-stack a Gaussian-weighted (windowed) centroid
 * gives the position, a ring around it the local background and noise, and an aperture of 1.5 FWHM the
 * signal-to-noise. A sub-stack is clear when that is at least {@value #CLEAR_SNR}; below it the detection is marginal,
 * which the Minor Planet Center asks not to report.
 * <p>
 * The caller passes cut-outs of the aligned frames, all with the same origin and size; nothing else is kept. The
 * positions are measured centroids on the registered image grid, not points of the fitted path.
 */
public final class SubStackAstrometry {
    /** Signal-to-noise at which a sub-stack shows the object clearly. */
    public static final double CLEAR_SNR = 5.0;
    /** How far the centroid may move from the fitted path, in FWHM, before the measurement is not trusted. */
    static final double MAX_OFFSET_FWHM = 2.0;

    /**
     * One aligned frame's cut-out: its registered-image origin, its stored (signed) 16-bit pixels, and its exposure
     * midpoint and length in UTC milliseconds.
     */
    public record Cutout(int originX, int originY, short[][] pixels, long midpointMillis, long exposureMillis) { }

    /**
     * One sub-stack's measurement: the indexes of its first and last cut-out, how many it holds, its mean exposure
     * midpoint and total exposure, the centroid in registered pixels, its signal-to-noise, and why it is not clear, or
     * null when it is.
     */
    public record Measurement(int firstCutout, int lastCutout, int frames, long midpointMillis, long exposureMillis,
                              double x, double y, double snr, String problem) {
        /** Whether the object stands out clearly enough to report. */
        public boolean clear() { return problem == null; }
    }

    private SubStackAstrometry() { }

    /**
     * Measures the object in {@code groups} sub-stacks.
     *
     * @param cutouts      the frames' cut-outs in time order, all with the same origin and size
     * @param x0           fitted position at {@code referenceMillis}, registered pixels
     * @param vxPerMinute  fitted motion, pixels per minute
     * @param fwhm         the stars' FWHM in pixels
     */
    public static List<Measurement> measure(List<Cutout> cutouts, double x0, double y0, long referenceMillis,
                                            double vxPerMinute, double vyPerMinute, int groups, double fwhm) {
        if (cutouts.isEmpty() || groups < 1 || !(fwhm > 0)) throw new IllegalArgumentException("nothing to measure");
        Cutout first = cutouts.getFirst();
        int height = first.pixels().length, width = first.pixels()[0].length;
        for (Cutout c : cutouts) {
            if (c.originX() != first.originX() || c.originY() != first.originY() || c.pixels().length != height || c.pixels()[0].length != width) {
                throw new IllegalArgumentException("cut-outs must share one origin and size");
            }
        }
        float[][] reference = median(cutouts, width, height);
        var measurements = new ArrayList<Measurement>();
        int n = cutouts.size();
        for (int g = 0; g < groups; g++) {
            int from = (int) Math.round(g * n / (double) groups), to = (int) Math.round((g + 1) * n / (double) groups);
            if (to <= from) continue;
            long midpoint = 0, exposure = 0;
            for (int k = from; k < to; k++) { midpoint += cutouts.get(k).midpointMillis(); exposure += cutouts.get(k).exposureMillis(); }
            midpoint /= (to - from);
            double minutes = (midpoint - referenceMillis) / 60000.0;
            double px = x0 + vxPerMinute * minutes - first.originX(), py = y0 + vyPerMinute * minutes - first.originY();
            measurements.add(measureGroup(cutouts, reference, from, to, midpoint, exposure, px, py, vxPerMinute, vyPerMinute,
                    fwhm, first.originX(), first.originY()));
        }
        return List.copyOf(measurements);
    }

    /** The per-pixel median of all cut-outs, NaN where every frame is fill. */
    private static float[][] median(List<Cutout> cutouts, int width, int height) {
        float[][] reference = new float[height][width];
        float[] values = new float[cutouts.size()];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int count = 0;
                for (Cutout c : cutouts) {
                    short stored = c.pixels()[y][x];
                    if (stored > DataArea.FILL_THRESHOLD) values[count++] = PixelEncoding.toShiftedPositiveInt(stored);
                }
                if (count == 0) { reference[y][x] = Float.NaN; continue; }
                Arrays.sort(values, 0, count);
                reference[y][x] = count % 2 == 1 ? values[count / 2] : (values[count / 2 - 1] + values[count / 2]) / 2;
            }
        }
        return reference;
    }

    /** Stacks one group around the predicted place and measures the centroid there. */
    private static Measurement measureGroup(List<Cutout> cutouts, float[][] reference, int from, int to, long midpoint, long exposure,
                                            double px, double py, double vx, double vy, double fwhm, int originX, int originY) {
        int radius = (int) Math.ceil(Math.max(10, 5 * fwhm));
        int size = 2 * radius + 1;
        double[][] sum = new double[size][size];
        int[][] count = new int[size][size];
        int cx = (int) Math.round(px), cy = (int) Math.round(py);
        for (int k = from; k < to; k++) {
            Cutout c = cutouts.get(k);
            double minutes = (midpoint - c.midpointMillis()) / 60000.0;
            double dx = vx * minutes, dy = vy * minutes;
            for (int v = 0; v < size; v++) {
                for (int u = 0; u < size; u++) {
                    double value = residual(c, reference, cx - radius + u - dx, cy - radius + v - dy);
                    if (!Double.isNaN(value)) { sum[v][u] += value; count[v][u]++; }
                }
            }
        }
        double[][] stack = new double[size][size];
        for (int v = 0; v < size; v++) for (int u = 0; u < size; u++) stack[v][u] = count[v][u] == 0 ? Double.NaN : sum[v][u] / count[v][u];
        double lx = px - (cx - radius), ly = py - (cy - radius);
        // Background and noise from a ring that leaves out the object.
        var ring = new ArrayList<Double>();
        for (int v = 0; v < size; v++) for (int u = 0; u < size; u++) {
            double r = Math.hypot(u - lx, v - ly);
            if (r >= 2.5 * fwhm && r <= radius && !Double.isNaN(stack[v][u])) ring.add(stack[v][u]);
        }
        int frames = to - from;
        if (ring.size() < 20) {
            return new Measurement(from, to - 1, frames, midpoint, exposure, px + originX, py + originY, Double.NaN,
                    "too little sky around the path to measure");
        }
        double background = median(ring);
        var deviations = ring.stream().map(value -> Math.abs(value - background)).toList();
        double noise = 1.4826 * median(new ArrayList<>(deviations));
        // Windowed centroid, iterated from the predicted place.
        double sigma = fwhm / 2.3548, x = lx, y = ly;
        for (int iteration = 0; iteration < 30; iteration++) {
            double wx = 0, wy = 0, w = 0;
            for (int v = 0; v < size; v++) for (int u = 0; u < size; u++) {
                double value = stack[v][u] - background;
                if (Double.isNaN(value) || value <= 0) continue;
                double r2 = (u - x) * (u - x) + (v - y) * (v - y);
                if (r2 > 9 * sigma * sigma) continue;
                double weight = value * Math.exp(-r2 / (2 * sigma * sigma));
                wx += weight * u; wy += weight * v; w += weight;
            }
            if (w <= 0) break;
            double nx = wx / w, ny = wy / w, step = Math.hypot(nx - x, ny - y);
            x = nx; y = ny;
            if (step < 1e-3) break;
        }
        double flux = 0;
        int pixels = 0;
        for (int v = 0; v < size; v++) for (int u = 0; u < size; u++) {
            if (Math.hypot(u - x, v - y) <= 1.5 * fwhm && !Double.isNaN(stack[v][u])) { flux += stack[v][u] - background; pixels++; }
        }
        double snr = noise > 0 && pixels > 0 ? flux / (noise * Math.sqrt(pixels)) : Double.NaN;
        String problem = null;
        if (Math.hypot(x - lx, y - ly) > MAX_OFFSET_FWHM * fwhm) {
            problem = "the centroid is more than " + MAX_OFFSET_FWHM + " FWHM from the fitted path";
        } else if (!(snr >= CLEAR_SNR)) {
            problem = String.format(java.util.Locale.US, "signal-to-noise %.1f is below %.0f: a marginal detection", snr, CLEAR_SNR);
        }
        return new Measurement(from, to - 1, frames, midpoint, exposure, x + cx - radius + originX, y + cy - radius + originY, snr, problem);
    }

    /** A frame's value above the reference at a fractional pixel, bilinear; NaN off the cut-out or on fill. */
    private static double residual(Cutout c, float[][] reference, double x, double y) {
        int x0 = (int) Math.floor(x), y0 = (int) Math.floor(y);
        short[][] p = c.pixels();
        if (x0 < 0 || y0 < 0 || y0 + 1 >= p.length || x0 + 1 >= p[0].length) return Double.NaN;
        double fx = x - x0, fy = y - y0, value = 0;
        for (int j = 0; j < 2; j++) for (int i = 0; i < 2; i++) {
            short stored = p[y0 + j][x0 + i];
            float ref = reference[y0 + j][x0 + i];
            if (stored <= DataArea.FILL_THRESHOLD || Float.isNaN(ref)) return Double.NaN;
            value += (i == 0 ? 1 - fx : fx) * (j == 0 ? 1 - fy : fy) * (PixelEncoding.toShiftedPositiveInt(stored) - ref);
        }
        return value;
    }

    private static double median(List<Double> values) {
        var sorted = new ArrayList<>(values);
        sorted.sort(Double::compare);
        int n = sorted.size();
        return n % 2 == 1 ? sorted.get(n / 2) : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2;
    }
}
