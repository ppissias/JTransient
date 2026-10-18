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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

/**
 * The part of the frame where every frame of a set holds image data rather than registration fill, kept
 * {@link #MARGIN} pixels away from the fill and from the image edge. It is stored as one run of columns per row.
 *
 * <p>It is the intersection of the frames' {@link FrameCoverage}s. Each frame's own data area is a shifted (and
 * possibly rotated) rectangle, so the intersection is one run per row and per column. The margin covers the soft
 * edge that interpolation leaves next to the fill (1–2 px on the reference data) plus one pixel.</p>
 */
public final class DataArea {
    /** Pixels at or below this stored value (8 ADU) are registration fill, as in {@link FrameDriftAnalyzer}. */
    public static final int FILL_THRESHOLD = Short.MIN_VALUE + 8;
    /** Distance kept from the fill and from the image edge, in pixels. */
    public static final int MARGIN = 3;
    /** Tolerance of the simplified outline, in pixels. */
    private static final double OUTLINE_TOLERANCE = 1.0;

    private final int width;
    private final int height;
    private final int[] rowStart;
    private final int[] rowEnd;
    private final long pixelCount;

    private DataArea(int width, int height, int[] rowStart, int[] rowEnd) {
        this.width = width;
        this.height = height;
        this.rowStart = rowStart;
        this.rowEnd = rowEnd;
        long count = 0;
        for (int y = 0; y < height; y++) {
            if (rowStart[y] <= rowEnd[y]) {
                count += rowEnd[y] - rowStart[y] + 1;
            }
        }
        this.pixelCount = count;
    }

    /**
     * The area where every one of the frames has data, {@link #MARGIN} pixels in from the fill and the image edge.
     *
     * @param frames coverages of frames of one size; at least one
     */
    public static DataArea of(Collection<FrameCoverage> frames) {
        if (frames == null || frames.isEmpty()) {
            throw new IllegalArgumentException("A data area needs at least one frame.");
        }
        FrameCoverage first = frames.iterator().next();
        int width = first.getWidth();
        int height = first.getHeight();
        int[] rowStart = filled(height, Integer.MIN_VALUE);
        int[] rowEnd = filled(height, Integer.MAX_VALUE);
        int[] columnStart = filled(width, Integer.MIN_VALUE);
        int[] columnEnd = filled(width, Integer.MAX_VALUE);
        for (FrameCoverage frame : frames) {
            if (frame.getWidth() != width || frame.getHeight() != height) {
                throw new IllegalArgumentException("All frames of a data area must have the same size.");
            }
            for (int y = 0; y < height; y++) {
                rowStart[y] = Math.max(rowStart[y], frame.rowStart[y]);
                rowEnd[y] = Math.min(rowEnd[y], frame.rowEnd[y]);
            }
            for (int x = 0; x < width; x++) {
                columnStart[x] = Math.max(columnStart[x], frame.columnStart[x]);
                columnEnd[x] = Math.min(columnEnd[x], frame.columnEnd[x]);
            }
        }
        return fromExtents(width, height, rowStart, rowEnd, columnStart, columnEnd);
    }

    /** The whole frame, {@link #MARGIN} pixels in from the image edge: the area when the frames have no fill. */
    public static DataArea fullFrame(int width, int height) {
        int[] rowStart = filled(height, 0);
        int[] rowEnd = filled(height, width - 1);
        int[] columnStart = filled(width, 0);
        int[] columnEnd = filled(width, height - 1);
        return fromExtents(width, height, rowStart, rowEnd, columnStart, columnEnd);
    }

    /**
     * How much larger the area of the frames would be without each one of them, for the frames that limit it.
     *
     * @param frames coverages of frames of one size
     * @return frames whose absence would enlarge the area, the largest gain first
     */
    public static List<FrameLimit> limitingFrames(List<FrameCoverage> frames) {
        if (frames == null || frames.size() < 2) {
            return Collections.emptyList();
        }
        int width = frames.get(0).getWidth();
        int height = frames.get(0).getHeight();
        Extremes rowStart = new Extremes(height, true);
        Extremes rowEnd = new Extremes(height, false);
        Extremes columnStart = new Extremes(width, true);
        Extremes columnEnd = new Extremes(width, false);
        for (int i = 0; i < frames.size(); i++) {
            FrameCoverage frame = frames.get(i);
            rowStart.add(i, frame.rowStart);
            rowEnd.add(i, frame.rowEnd);
            columnStart.add(i, frame.columnStart);
            columnEnd.add(i, frame.columnEnd);
        }
        long all = fromExtents(width, height, rowStart.best, rowEnd.best, columnStart.best, columnEnd.best).pixelCount;
        boolean[] limiting = new boolean[frames.size()];
        rowStart.markLimiting(limiting);
        rowEnd.markLimiting(limiting);
        columnStart.markLimiting(limiting);
        columnEnd.markLimiting(limiting);
        List<FrameLimit> limits = new ArrayList<>();
        for (int i = 0; i < frames.size(); i++) {
            if (!limiting[i]) {
                continue;
            }
            long without = fromExtents(width, height, rowStart.without(i), rowEnd.without(i),
                    columnStart.without(i), columnEnd.without(i)).pixelCount;
            if (without > all) {
                limits.add(new FrameLimit(frames.get(i).getSequenceIndex(), without - all,
                        (without - all) / ((double) width * height)));
            }
        }
        limits.sort((left, right) -> Long.compare(right.gainedPixels, left.gainedPixels));
        return limits;
    }

    /**
     * Builds the area from the intersected row and column extents: a pixel must lie inside both its row's and its
     * column's extent, and then {@link #MARGIN} pixels inside that region (a square erosion).
     */
    private static DataArea fromExtents(int width, int height, int[] rowStart, int[] rowEnd,
                                        int[] columnStart, int[] columnEnd) {
        int[] start = new int[height];
        int[] end = new int[height];
        for (int y = 0; y < height; y++) {
            int s = Math.max(0, rowStart[y]);
            int e = Math.min(width - 1, rowEnd[y]);
            while (s <= e && (columnStart[s] > y || columnEnd[s] < y)) {
                s++;
            }
            while (e >= s && (columnStart[e] > y || columnEnd[e] < y)) {
                e--;
            }
            start[y] = s;
            end[y] = e;
        }
        int[] erodedStart = new int[height];
        int[] erodedEnd = new int[height];
        for (int y = 0; y < height; y++) {
            int s = Integer.MIN_VALUE;
            int e = Integer.MAX_VALUE;
            for (int d = -MARGIN; d <= MARGIN; d++) {
                int row = y + d;
                if (row < 0 || row >= height || start[row] > end[row]) {
                    s = width;
                    e = -1;
                    break;
                }
                s = Math.max(s, start[row]);
                e = Math.min(e, end[row]);
            }
            s += MARGIN;
            e -= MARGIN;
            erodedStart[y] = s <= e ? s : width;
            erodedEnd[y] = s <= e ? e : -1;
        }
        return new DataArea(width, height, erodedStart, erodedEnd);
    }

    private static int[] filled(int length, int value) {
        int[] values = new int[length];
        java.util.Arrays.fill(values, value);
        return values;
    }

    /** Whether the pixel lies inside the area. */
    public boolean contains(int x, int y) {
        return y >= 0 && y < height && x >= rowStart[y] && x <= rowEnd[y];
    }

    /** Whether the pixel nearest to the position lies inside the area. */
    public boolean contains(double x, double y) {
        return contains((int) Math.floor(x + 0.5), (int) Math.floor(y + 0.5));
    }

    /** First column of the area in the row, larger than {@link #getRowEnd} when the row is outside it. */
    public int getRowStart(int y) {
        return rowStart[y];
    }

    /** Last column of the area in the row. */
    public int getRowEnd(int y) {
        return rowEnd[y];
    }

    /** Frame width in pixels. */
    public int getWidth() {
        return width;
    }

    /** Frame height in pixels. */
    public int getHeight() {
        return height;
    }

    /** Number of pixels in the area. */
    public long getPixelCount() {
        return pixelCount;
    }

    /** Share of the frame inside the area, from 0 to 1. */
    public double getFraction() {
        return pixelCount / ((double) width * height);
    }

    /**
     * The area's outline as a closed polygon in frame pixel coordinates (pixel edges, so a pixel at column x spans
     * x to x + 1), simplified to within a pixel. Empty when the area is empty.
     *
     * @return polygon vertices as {x, y} pairs, without repeating the first vertex
     */
    public List<double[]> outline() {
        int bestTop = -1;
        int bestBottom = -2;
        for (int y = 0; y < height; ) {
            if (rowStart[y] > rowEnd[y]) {
                y++;
                continue;
            }
            int top = y;
            while (y < height && rowStart[y] <= rowEnd[y]) {
                y++;
            }
            if (y - 1 - top > bestBottom - bestTop) {
                bestTop = top;
                bestBottom = y - 1;
            }
        }
        if (bestTop < 0) {
            return Collections.emptyList();
        }
        List<double[]> left = new ArrayList<>();
        List<double[]> right = new ArrayList<>();
        for (int y = bestTop; y <= bestBottom; y++) {
            left.add(new double[]{rowStart[y], y});
            left.add(new double[]{rowStart[y], y + 1});
        }
        for (int y = bestBottom; y >= bestTop; y--) {
            right.add(new double[]{rowEnd[y] + 1, y + 1});
            right.add(new double[]{rowEnd[y] + 1, y});
        }
        List<double[]> polygon = new ArrayList<>(simplify(left));
        polygon.addAll(simplify(right));
        return polygon;
    }

    /** Douglas–Peucker simplification of an open polyline, keeping both ends. */
    private static List<double[]> simplify(List<double[]> points) {
        int n = points.size();
        if (n < 3) {
            return points;
        }
        boolean[] keep = new boolean[n];
        keep[0] = true;
        keep[n - 1] = true;
        Deque<int[]> ranges = new ArrayDeque<>();
        ranges.push(new int[]{0, n - 1});
        while (!ranges.isEmpty()) {
            int[] range = ranges.pop();
            double[] a = points.get(range[0]);
            double[] b = points.get(range[1]);
            double dx = b[0] - a[0];
            double dy = b[1] - a[1];
            double length = Math.hypot(dx, dy);
            int farthest = -1;
            double farthestDistance = OUTLINE_TOLERANCE;
            for (int i = range[0] + 1; i < range[1]; i++) {
                double[] p = points.get(i);
                double distance = length == 0
                        ? Math.hypot(p[0] - a[0], p[1] - a[1])
                        : Math.abs(dx * (a[1] - p[1]) - dy * (a[0] - p[0])) / length;
                if (distance > farthestDistance) {
                    farthestDistance = distance;
                    farthest = i;
                }
            }
            if (farthest >= 0) {
                keep[farthest] = true;
                ranges.push(new int[]{range[0], farthest});
                ranges.push(new int[]{farthest, range[1]});
            }
        }
        List<double[]> kept = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (keep[i]) {
                kept.add(points.get(i));
            }
        }
        return kept;
    }

    /** How much one frame limits the area of a set of frames. */
    public static final class FrameLimit {
        /** The frame's sequence index. */
        public final int sequenceIndex;
        /** Pixels the area would gain without this frame. */
        public final long gainedPixels;
        /** The same gain as a share of the frame, from 0 to 1. */
        public final double gainedFraction;

        FrameLimit(int sequenceIndex, long gainedPixels, double gainedFraction) {
            this.sequenceIndex = sequenceIndex;
            this.gainedPixels = gainedPixels;
            this.gainedFraction = gainedFraction;
        }
    }

    /**
     * Per position, the tightest extent over a set of frames, the next tightest, and the one frame holding the
     * tightest when no other frame shares it.
     */
    private static final class Extremes {
        private final boolean takeMaximum;
        private final int[] best;
        private final int[] second;
        private final int[] holder;

        private Extremes(int length, boolean takeMaximum) {
            this.takeMaximum = takeMaximum;
            int worst = takeMaximum ? Integer.MIN_VALUE : Integer.MAX_VALUE;
            best = filled(length, worst);
            second = filled(length, worst);
            holder = filled(length, -1);
        }

        private void add(int frame, int[] values) {
            for (int p = 0; p < values.length; p++) {
                int value = values[p];
                if (tighter(value, best[p])) {
                    second[p] = best[p];
                    best[p] = value;
                    holder[p] = frame;
                } else if (value == best[p]) {
                    second[p] = value;
                    holder[p] = -1;
                } else if (tighter(value, second[p])) {
                    second[p] = value;
                }
            }
        }

        private boolean tighter(int value, int than) {
            return takeMaximum ? value > than : value < than;
        }

        private void markLimiting(boolean[] limiting) {
            for (int frame : holder) {
                if (frame >= 0) {
                    limiting[frame] = true;
                }
            }
        }

        private int[] without(int frame) {
            int[] values = best.clone();
            for (int p = 0; p < values.length; p++) {
                if (holder[p] == frame) {
                    values[p] = second[p];
                }
            }
            return values;
        }
    }
}
