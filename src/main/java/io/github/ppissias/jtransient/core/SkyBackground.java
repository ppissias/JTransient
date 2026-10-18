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

/**
 * The smooth sky level across an image, so that sources can be found against the sky where they are instead of one
 * level for the whole image. With a sky gradient, one level makes the threshold too low on the bright side (noise
 * passes) and too high on the dark side (faint stars are missed), and the gradient also inflates the noise estimate.
 *
 * <p>The image is cut into boxes of about {@link #BOX_SIZE} pixels. A box's sky is the median of its pixels after
 * clipping the stars away (3 sigma, repeated). A 3 x 3 median filter over the boxes then removes boxes raised by a
 * bright star or a small galaxy, and the level at each pixel is interpolated between the box centres. Beyond the
 * outer box centres, and in the filter at the edges of the grid, the boxes are continued linearly, so a straight
 * gradient is followed up to the image edge. Registration fill ({@link DataArea#FILL_LEVEL} or below) is left out.
 * A box with more than a tenth of fill would measure the sky of its data part away from its centre, so it takes its
 * level from its neighbours instead, continued linearly.</p>
 */
public final class SkyBackground {
    /**
     * Box size in pixels. Large enough that the 3 x 3 filter keeps galaxies and nebulae of up to about a box across
     * (256 px took part of the IC 5070 nebula into the sky), small enough to follow a sky gradient.
     */
    public static final int BOX_SIZE = 512;
    private static final double CLIP_SIGMA = 3.0;
    private static final int CLIP_ITERATIONS = 5;
    /** A box needs at least this share of image data (not fill) to measure the sky. */
    private static final double MIN_DATA_SHARE = 0.9;
    private static final int SHIFT = 32768;

    private SkyBackground() {
    }

    /**
     * A copy of the image with the smooth sky subtracted and the image's typical sky level added back, so the sky is
     * flat at that level. Fill pixels are copied unchanged, and image pixels never drop to the fill level.
     *
     * @param image stored (signed-short) pixels, indexed {@code [y][x]}
     * @return the flattened copy, or the image itself when no box holds enough image data
     */
    public static short[][] flatten(short[][] image) {
        return flatten(image, BOX_SIZE);
    }

    /**
     * {@link #flatten(short[][])} with another box size.
     *
     * @param boxSize box size in pixels, at least 16
     */
    public static short[][] flatten(short[][] image, int boxSize) {
        if (boxSize < 16) {
            throw new IllegalArgumentException("boxSize must be at least 16");
        }
        int height = image.length;
        int width = image[0].length;
        int cols = Math.max(1, (int) Math.round(width / (double) boxSize));
        int rows = Math.max(1, (int) Math.round(height / (double) boxSize));

        double[][] levels = boxLevels(image, cols, rows);
        double typical = median(levels);
        if (Double.isNaN(typical)) {
            return image;
        }
        fillMissing(levels);
        double[][] sky = medianFilter(levels);

        // Interpolation weights between box centres, per column and per row.
        int[] col0 = new int[width];
        double[] colT = new double[width];
        weights(width, cols, col0, colT);
        int[] row0 = new int[height];
        double[] rowT = new double[height];
        weights(height, rows, row0, rowT);

        short[][] out = new short[height][width];
        double[] rowSky = new double[cols];
        for (int y = 0; y < height; y++) {
            int r0 = row0[y];
            int r1 = Math.min(rows - 1, r0 + 1);
            double ty = rowT[y];
            for (int c = 0; c < cols; c++) {
                rowSky[c] = sky[r0][c] + ty * (sky[r1][c] - sky[r0][c]);
            }
            short[] in = image[y];
            short[] dst = out[y];
            for (int x = 0; x < width; x++) {
                if (in[x] <= DataArea.FILL_THRESHOLD) {
                    dst[x] = in[x];
                    continue;
                }
                int c0 = col0[x];
                int c1 = Math.min(cols - 1, c0 + 1);
                double level = rowSky[c0] + colT[x] * (rowSky[c1] - rowSky[c0]);
                long value = Math.round(in[x] + SHIFT - level + typical);
                value = Math.max(DataArea.FILL_LEVEL + 1, Math.min(65535, value));
                dst[x] = (short) (value - SHIFT);
            }
        }
        return out;
    }

    /** Clipped median of each box, in shifted ADU; NaN for boxes with too much fill. */
    private static double[][] boxLevels(short[][] image, int cols, int rows) {
        int height = image.length;
        int width = image[0].length;
        double[][] levels = new double[rows][cols];
        int[] histogram = new int[65536];
        for (int r = 0; r < rows; r++) {
            int y0 = r * height / rows;
            int y1 = (r + 1) * height / rows;
            for (int c = 0; c < cols; c++) {
                int x0 = c * width / cols;
                int x1 = (c + 1) * width / cols;
                int min = Integer.MAX_VALUE;
                int max = Integer.MIN_VALUE;
                int count = 0;
                for (int y = y0; y < y1; y++) {
                    short[] row = image[y];
                    for (int x = x0; x < x1; x++) {
                        if (row[x] <= DataArea.FILL_THRESHOLD) {
                            continue;
                        }
                        int v = row[x] + SHIFT;
                        histogram[v]++;
                        if (v < min) min = v;
                        if (v > max) max = v;
                        count++;
                    }
                }
                long boxArea = (long) (x1 - x0) * (y1 - y0);
                if (count == 0 || count < MIN_DATA_SHARE * boxArea) {
                    levels[r][c] = Double.NaN;
                } else {
                    levels[r][c] = clippedMedian(histogram, min, max);
                }
                if (count > 0) {
                    java.util.Arrays.fill(histogram, min, max + 1, 0);
                }
            }
        }
        return levels;
    }

    /** Median of the histogram values, repeatedly clipped to CLIP_SIGMA robust sigmas around it. */
    private static double clippedMedian(int[] histogram, int min, int max) {
        int lo = min;
        int hi = max;
        double median = quantile(histogram, lo, hi, 0.5);
        for (int i = 0; i < CLIP_ITERATIONS; i++) {
            double sigma = (quantile(histogram, lo, hi, 0.8413) - quantile(histogram, lo, hi, 0.1587)) / 2.0;
            int newLo = Math.max(min, (int) Math.floor(median - CLIP_SIGMA * sigma));
            int newHi = Math.min(max, (int) Math.ceil(median + CLIP_SIGMA * sigma));
            if (newLo > newHi || (newLo == lo && newHi == hi)) {
                break;
            }
            lo = newLo;
            hi = newHi;
            median = quantile(histogram, lo, hi, 0.5);
        }
        return median;
    }

    /** Value below which the given share of the histogram counts between lo and hi lies. */
    private static double quantile(int[] histogram, int lo, int hi, double share) {
        long total = 0;
        for (int v = lo; v <= hi; v++) {
            total += histogram[v];
        }
        if (total == 0) {
            return (lo + hi) / 2.0;
        }
        double target = share * total;
        long cumulative = 0;
        for (int v = lo; v <= hi; v++) {
            cumulative += histogram[v];
            if (cumulative >= target) {
                return v;
            }
        }
        return hi;
    }

    /**
     * Gives each box without a level one from its neighbours, growing outwards from the boxes that have one: the
     * average over the directions in which the next two boxes have a level of the line through them continued one
     * box, or the mean of the neighbours where no direction has two.
     */
    private static void fillMissing(double[][] levels) {
        int rows = levels.length;
        int cols = levels[0].length;
        boolean missing = true;
        while (missing) {
            missing = false;
            double[][] next = new double[rows][];
            for (int r = 0; r < rows; r++) {
                next[r] = levels[r].clone();
            }
            for (int r = 0; r < rows; r++) {
                for (int c = 0; c < cols; c++) {
                    if (!Double.isNaN(levels[r][c])) {
                        continue;
                    }
                    double lineSum = 0;
                    int lines = 0;
                    double sum = 0;
                    int n = 0;
                    for (int dr = -1; dr <= 1; dr++) {
                        for (int dc = -1; dc <= 1; dc++) {
                            if (dr == 0 && dc == 0) {
                                continue;
                            }
                            double near = levelAt(levels, r + dr, c + dc);
                            if (Double.isNaN(near)) {
                                continue;
                            }
                            sum += near;
                            n++;
                            double far = levelAt(levels, r + 2 * dr, c + 2 * dc);
                            if (!Double.isNaN(far)) {
                                lineSum += 2 * near - far;
                                lines++;
                            }
                        }
                    }
                    if (lines > 0) {
                        next[r][c] = lineSum / lines;
                    } else if (n > 0) {
                        next[r][c] = sum / n;
                    } else {
                        missing = true;
                    }
                }
            }
            for (int r = 0; r < rows; r++) {
                levels[r] = next[r];
            }
        }
    }

    private static double levelAt(double[][] levels, int r, int c) {
        return r >= 0 && r < levels.length && c >= 0 && c < levels[0].length ? levels[r][c] : Double.NaN;
    }

    /** 3 x 3 median filter; outside the grid the boxes are continued linearly (reflected through the edge box). */
    private static double[][] medianFilter(double[][] levels) {
        int rows = levels.length;
        int cols = levels[0].length;
        double[][] out = new double[rows][cols];
        double[] window = new double[9];
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                int n = 0;
                for (int dr = -1; dr <= 1; dr++) {
                    for (int dc = -1; dc <= 1; dc++) {
                        window[n++] = extended(levels, r + dr, c + dc);
                    }
                }
                java.util.Arrays.sort(window);
                out[r][c] = window[4];
            }
        }
        return out;
    }

    private static double extended(double[][] levels, int r, int c) {
        int rows = levels.length;
        int cols = levels[0].length;
        if (r < 0 || r >= rows) {
            int edge = r < 0 ? 0 : rows - 1;
            if (rows == 1) {
                return extended(levels, 0, c);
            }
            return 2 * extended(levels, edge, c) - extended(levels, 2 * edge - r, c);
        }
        if (c < 0 || c >= cols) {
            int edge = c < 0 ? 0 : cols - 1;
            if (cols == 1) {
                return levels[r][0];
            }
            return 2 * levels[r][edge] - levels[r][2 * edge - c];
        }
        return levels[r][c];
    }

    /**
     * For each pixel along one axis: the box centre at or before it and the fraction of the way to the next one. Before
     * the first and after the last centre the fraction runs below 0 or above 1, which continues the outer pair linearly.
     */
    private static void weights(int size, int boxes, int[] first, double[] fraction) {
        double boxSize = size / (double) boxes;
        for (int i = 0; i < size; i++) {
            if (boxes == 1) {
                first[i] = 0;
                fraction[i] = 0;
                continue;
            }
            double position = (i + 0.5) / boxSize - 0.5;
            int b = Math.max(0, Math.min(boxes - 2, (int) Math.floor(position)));
            first[i] = b;
            fraction[i] = position - b;
        }
    }

    private static double median(double[][] levels) {
        int n = 0;
        double[] values = new double[levels.length * levels[0].length];
        for (double[] row : levels) {
            for (double v : row) {
                if (!Double.isNaN(v)) {
                    values[n++] = v;
                }
            }
        }
        if (n == 0) {
            return Double.NaN;
        }
        java.util.Arrays.sort(values, 0, n);
        return values[n / 2];
    }
}
