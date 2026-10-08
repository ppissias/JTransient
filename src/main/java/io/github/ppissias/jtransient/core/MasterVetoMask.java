/*
 * JTransient
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 * author: Petros Pissias <petrospis at gmail.com>
 *
 */
package io.github.ppissias.jtransient.core;

import java.util.List;

/**
 * The stationary-star veto mask: the footprints of the master stars, widened by half the star jitter radius (at
 * least one pixel). The pipeline and tools that preview the mask build it here, so both always agree.
 */
public final class MasterVetoMask {

    private MasterVetoMask() {
    }

    /** Radius in pixels by which each master-star footprint is widened. */
    public static int dilationRadius(double maxStarJitter) {
        return (int) Math.max(1, Math.round(maxStarJitter / 2.0));
    }

    /**
     * Builds the veto mask, indexed {@code [y][x]}.
     *
     * @param masterStars stars extracted from the master stack
     * @param sensorWidth image width
     * @param sensorHeight image height
     * @param maxStarJitter the configured star jitter radius
     */
    public static boolean[][] build(List<SourceExtractor.DetectedObject> masterStars,
                                    int sensorWidth,
                                    int sensorHeight,
                                    double maxStarJitter) {
        boolean[][] mask = new boolean[sensorHeight][sensorWidth];
        int radius = dilationRadius(maxStarJitter);
        for (SourceExtractor.DetectedObject star : masterStars) {
            if (star.rawPixels == null) {
                continue;
            }
            for (SourceExtractor.Pixel p : star.rawPixels) {
                for (int dx = -radius; dx <= radius; dx++) {
                    for (int dy = -radius; dy <= radius; dy++) {
                        if (dx * dx + dy * dy <= radius * radius) {
                            int x = p.x + dx;
                            int y = p.y + dy;
                            if (x >= 0 && x < sensorWidth && y >= 0 && y < sensorHeight) {
                                mask[y][x] = true;
                            }
                        }
                    }
                }
            }
        }
        return mask;
    }

    /** Share of the image covered by the mask, from 0 to 1. */
    public static double coverage(boolean[][] mask) {
        long masked = 0;
        long total = 0;
        for (boolean[] row : mask) {
            for (boolean pixel : row) {
                if (pixel) {
                    masked++;
                }
            }
            total += row.length;
        }
        return total == 0 ? 0 : masked / (double) total;
    }
}
