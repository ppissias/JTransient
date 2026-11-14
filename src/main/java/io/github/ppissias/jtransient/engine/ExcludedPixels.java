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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Writes registration fill (0 ADU) into the excluded areas of a run's borrowed images, so every stage treats them as
 * having no data, and writes the original values back when the run ends. It keeps a copy of the excluded pixels only,
 * not of the images.
 */
final class ExcludedPixels {
    /** The stored value of 0 ADU, the level registration fill has. */
    private static final short FILL = Short.MIN_VALUE;

    /** No areas: every image stays as it is. */
    static final ExcludedPixels NONE = new ExcludedPixels(List.of());

    private final List<ExcludedArea> areas;
    private final List<short[][]> images = new ArrayList<>();
    private final List<short[][]> saved = new ArrayList<>();

    /** Holds the run's areas; null or empty leaves every image untouched. */
    ExcludedPixels(List<ExcludedArea> areas) {
        this.areas = areas == null ? List.of() : List.copyOf(areas);
    }

    /** The areas, as given. */
    List<ExcludedArea> areas() {
        return areas;
    }

    /** Whether there is nothing to exclude. */
    boolean isEmpty() {
        return areas.isEmpty();
    }

    /** The areas as {first column, first row, width, height}, for the data areas' holes. */
    List<int[]> rectangles() {
        List<int[]> rectangles = new ArrayList<>(areas.size());
        for (ExcludedArea area : areas) {
            rectangles.add(new int[]{area.x(), area.y(), area.width(), area.height()});
        }
        return rectangles;
    }

    /** Saves the image's pixels inside the areas, then writes the fill level into them. */
    synchronized void paint(short[][] image) {
        if (areas.isEmpty() || image == null || image.length == 0) {
            return;
        }
        List<short[]> rows = new ArrayList<>();
        for (ExcludedArea area : areas) {
            int[] r = clip(area, image);
            for (int y = r[2]; y < r[3]; y++) {
                rows.add(Arrays.copyOfRange(image[y], r[0], r[1]));
                Arrays.fill(image[y], r[0], r[1], FILL);
            }
        }
        images.add(image);
        saved.add(rows.toArray(new short[0][]));
    }

    /**
     * A copy of the image with the fill level in the areas, leaving the image itself untouched; the image itself when
     * there is nothing to exclude. For stages that may only read the images they borrow.
     */
    short[][] paintedCopy(short[][] image) {
        if (areas.isEmpty() || image == null || image.length == 0) {
            return image;
        }
        short[][] copy = new short[image.length][];
        for (int y = 0; y < image.length; y++) {
            copy[y] = image[y].clone();
        }
        fill(copy);
        return copy;
    }

    /** Writes the fill level into the areas of an image the caller owns, keeping nothing to restore. */
    void fill(short[][] image) {
        if (areas.isEmpty() || image == null || image.length == 0) {
            return;
        }
        for (ExcludedArea area : areas) {
            int[] r = clip(area, image);
            for (int y = r[2]; y < r[3]; y++) {
                Arrays.fill(image[y], r[0], r[1], FILL);
            }
        }
    }

    /**
     * Writes back every saved value, the last painted image and area first, so images and overlapping areas end as they
     * began. Safe to call more than once.
     */
    synchronized void restore() {
        for (int i = images.size() - 1; i >= 0; i--) {
            short[][] image = images.get(i);
            short[][] rows = saved.get(i);
            int k = rows.length - 1;
            for (int a = areas.size() - 1; a >= 0; a--) {
                int[] r = clip(areas.get(a), image);
                for (int y = r[3] - 1; y >= r[2]; y--) {
                    short[] row = rows[k--];
                    System.arraycopy(row, 0, image[y], r[0], row.length);
                }
            }
        }
        images.clear();
        saved.clear();
    }

    /** The area within the image: {first column, end column, first row, end row}; empty when outside it. */
    private static int[] clip(ExcludedArea area, short[][] image) {
        int height = image.length;
        int width = image[0].length;
        int x0 = (int) Math.max(0, Math.min(width, (long) area.x()));
        int x1 = (int) Math.max(x0, Math.min(width, (long) area.x() + area.width()));
        int y0 = (int) Math.max(0, Math.min(height, (long) area.y()));
        int y1 = (int) Math.max(y0, Math.min(height, (long) area.y() + area.height()));
        if (x1 == x0) {
            y1 = y0;
        }
        return new int[]{x0, x1, y0, y1};
    }
}
