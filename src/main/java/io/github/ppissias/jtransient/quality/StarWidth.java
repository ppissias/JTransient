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

import io.github.ppissias.jtransient.core.PixelEncoding;
import io.github.ppissias.jtransient.execution.CancellationScope;
import io.github.ppissias.jtransient.model.DetectedObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The stars' width, as a Gaussian FWHM in pixels: the core width of bright, isolated, round stars, from the area above
 * half their peak. Second moments include the stars' wings; the core is what a faint source shows above the noise.
 * The calibrated Auto-Tune measures it on its sample regions, and quality control on every frame
 * ({@link FrameQualityAnalyzer.FrameMetrics#starWidthFwhm}); a session's width for the faint searches and the
 * registration check is the median of its kept frames, unless the settings give one
 * ({@code DetectionConfig.starFwhmPixels}).
 *
 * <p>A star counts when it is not a streak, its elongation is at most {@link #MAX_ELONGATION}, its peak is
 * {@link #MIN_PEAK_SIGMA} to {@link #MAX_PEAK_SIGMA} noise sigmas, no other detection lies within twice its window
 * (twice its second-moment FWHM, at least 4 px), and the window lies inside the image. Its width is
 * {@code 2 sqrt(n / pi)} for the n pixels of the window at or above half the peak over the background.</p>
 */
public final class StarWidth {
    /** Most elongated star measured. */
    public static final double MAX_ELONGATION = 1.3;
    /** Faintest star peak measured, in noise sigmas. */
    public static final double MIN_PEAK_SIGMA = 20.0;
    /** Brightest star peak measured, in noise sigmas; brighter stars may be saturated. */
    public static final double MAX_PEAK_SIGMA = 150.0;
    /** Smallest measuring window radius, in pixels. */
    static final double MIN_WINDOW = 4.0;
    /** A frame's width counts only when measured on at least this many stars. */
    public static final int MIN_FRAME_STARS = 5;
    /** Where a session's star width comes from. */
    public enum Source {
        /** The settings' {@code starFwhmPixels}, which the calibrated Auto-Tune sets. */
        SETTING,
        /** Measured on the kept frames. */
        MEASURED,
        /** No star could be measured; a default width. */
        ASSUMED
    }

    /** Side of the grid cells used to find a star's neighbours, in pixels. */
    private static final int CELL = 16;

    private StarWidth() {
    }

    /**
     * Measures the stars of one image and adds their widths (Gaussian FWHM, pixels) to {@code widths}, in the
     * objects' order. After each star that passes the isolation and edge checks, it stops when {@code widths} holds
     * {@code limit} values or more, so a list shared over images grows by at most one value per further image.
     *
     * @param image the pixels the objects were extracted from
     * @param objects the image's detections; only read, and all of them count as neighbours
     * @param background the sky level, in the encoding of {@link PixelEncoding#toShiftedPositiveInt}
     * @param widths receives the widths
     * @param limit the size at which to stop
     */
    public static void measure(short[][] image, List<DetectedObject> objects, double background, List<Double> widths,
                               int limit) {
        int h = image.length;
        int w = h == 0 ? 0 : image[0].length;
        Map<Long, List<DetectedObject>> grid = grid(objects);
        for (DetectedObject obj : objects) {
            CancellationScope.check();
            if (obj.isStreak || obj.elongation > MAX_ELONGATION || obj.peakSigma < MIN_PEAK_SIGMA
                    || obj.peakSigma > MAX_PEAK_SIGMA) continue;
            double window = Math.max(MIN_WINDOW, 2.0 * obj.fwhm);
            if (!isolated(obj, 2 * window, grid)) continue;
            int r = (int) Math.ceil(window);
            int x0 = (int) Math.round(obj.x);
            int y0 = (int) Math.round(obj.y);
            if (x0 - r < 0 || y0 - r < 0 || x0 + r >= w || y0 + r >= h) continue;
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
            if (fwhm > 0.5) widths.add(fwhm);
            if (widths.size() >= limit) break;
        }
    }

    /**
     * A frame's width: the mean of the middle half of its stars' widths, or NaN when fewer than
     * {@link #MIN_FRAME_STARS} were measured.
     */
    public static double frameWidth(List<Double> widths) {
        return widths.size() < MIN_FRAME_STARS ? Double.NaN : middleHalfMean(widths);
    }

    /**
     * The mean of the middle half of the values (between the quartiles), a robust centre that changes gradually when
     * values take only a few distinct levels, as the stepped per-star widths do. Fewer than four values give their
     * median.
     */
    public static double middleHalfMean(List<Double> values) {
        List<Double> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int n = sorted.size();
        if (n < 4) {
            return sorted.get(n / 2);
        }
        int from = n / 4;
        int to = n - n / 4;
        double sum = 0;
        for (int i = from; i < to; i++) {
            sum += sorted.get(i);
        }
        return sum / (to - from);
    }

    /** Whether no other detection lies closer than {@code radius}; the same result as comparing with every one. */
    private static boolean isolated(DetectedObject obj, double radius, Map<Long, List<DetectedObject>> grid) {
        long fromX = Math.floorDiv((long) Math.floor(obj.x - radius), CELL);
        long toX = Math.floorDiv((long) Math.floor(obj.x + radius), CELL);
        long fromY = Math.floorDiv((long) Math.floor(obj.y - radius), CELL);
        long toY = Math.floorDiv((long) Math.floor(obj.y + radius), CELL);
        for (long cy = fromY; cy <= toY; cy++) {
            for (long cx = fromX; cx <= toX; cx++) {
                List<DetectedObject> cell = grid.get(key(cx, cy));
                if (cell == null) continue;
                for (DetectedObject other : cell) {
                    if (other != obj && Math.hypot(other.x - obj.x, other.y - obj.y) < radius) return false;
                }
            }
        }
        return true;
    }

    private static Map<Long, List<DetectedObject>> grid(List<DetectedObject> objects) {
        Map<Long, List<DetectedObject>> grid = new HashMap<>();
        for (DetectedObject obj : objects) {
            long cx = Math.floorDiv((long) Math.floor(obj.x), CELL);
            long cy = Math.floorDiv((long) Math.floor(obj.y), CELL);
            grid.computeIfAbsent(key(cx, cy), k -> new ArrayList<>()).add(obj);
        }
        return grid;
    }

    private static long key(long cx, long cy) {
        return (cx << 32) ^ (cy & 0xffffffffL);
    }
}
