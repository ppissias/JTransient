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
import io.github.ppissias.jtransient.core.SourceExtractor;
import io.github.ppissias.jtransient.engine.ImageFrame;
import io.github.ppissias.jtransient.telemetry.PipelineTelemetry;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Picks the master stars that can be measured reliably and estimates the session saturation level.
 *
 * <p>Bright stars are deliberately kept: readiness check B needs them to find where linearity ends,
 * and the measurements of stars brighter than that limit are flagged per frame instead.</p>
 */
final class PhotometryStarSelector {

    /** A master star chosen for measurement. */
    static final class Star {
        final double x;
        final double y;
        final double flux;

        Star(double x, double y, double flux) {
            this.x = x;
            this.y = y;
            this.flux = flux;
        }
    }

    /** Spatial regions per axis used to spread the selected stars across the field. */
    private static final int SELECTION_GRID = 8;
    /** A neighbour at least this fraction of a star's flux, inside the inner annulus radius, makes the star crowded. */
    private static final double BRIGHT_NEIGHBOUR_FLUX_RATIO = 0.1;
    /** Extra margin, in pixels, for residual registration offsets. */
    private static final double OFFSET_ALLOWANCE_PIXELS = 2.0;
    /** Pixels within this fraction of a frame's maximum count towards a saturation pile-up. */
    private static final double PILE_UP_TOLERANCE = 0.001;
    /** Fewest pixels at the top of the histogram that make a pile-up. */
    private static final int PILE_UP_MIN_PIXELS = 5;
    /** Saturation level, as a fraction of the 16-bit maximum, when no frame shows a pile-up. */
    private static final double NO_PILE_UP_FRACTION = 0.95;

    private PhotometryStarSelector() {
    }

    /**
     * Selects isolated, point-like master stars away from edges and void padding.
     *
     * @param masterStars stationary sources extracted from the master stack
     * @param masterStack median master stack
     * @param sessionFwhm median FWHM of the clean frames, in pixels
     * @param saturationThreshold peak level (shifted domain) at or above which a master star is treated as saturated
     * @param config pipeline configuration
     * @param telemetry receives the selection counters
     * @return selected stars, brightest first
     */
    static List<Star> select(List<SourceExtractor.DetectedObject> masterStars,
                             short[][] masterStack,
                             double sessionFwhm,
                             double saturationThreshold,
                             DetectionConfig config,
                             PipelineTelemetry.PhotometryTelemetry telemetry) {
        int height = masterStack.length;
        int width = masterStack[0].length;
        double outerRadius = config.photometryAnnulusOuterFwhmFactor * sessionFwhm + OFFSET_ALLOWANCE_PIXELS;
        double innerRadius = config.photometryAnnulusInnerFwhmFactor * sessionFwhm;
        double closeRadius = (config.photometryApertureFwhmFactor + 1.0) * sessionFwhm;
        double margin = Math.max(config.edgeMarginPixels, Math.ceil(outerRadius) + 1);

        double masterSky = sampleMedian(masterStack);
        double voidThreshold = masterSky * config.voidThresholdFraction;

        NeighbourIndex neighbours = new NeighbourIndex(masterStars, Math.max(4.0, innerRadius));
        telemetry.masterStarsConsidered = masterStars.size();

        List<Star> survivors = new ArrayList<>();
        for (SourceExtractor.DetectedObject star : masterStars) {
            if (star.isStreak) {
                telemetry.starsRejectedStreak++;
                continue;
            }
            if (star.elongation > config.photometryMaxElongation) {
                telemetry.starsRejectedElongated++;
                continue;
            }
            if (star.x < margin || star.y < margin || star.x > width - 1 - margin || star.y > height - 1 - margin
                    || touchesVoid(masterStack, star.x, star.y, outerRadius, voidThreshold)) {
                telemetry.starsRejectedEdgeOrVoid++;
                continue;
            }
            if (neighbours.isCrowded(star, closeRadius, innerRadius)) {
                telemetry.starsRejectedCrowded++;
                continue;
            }
            if (masterPeak(masterStack, star.x, star.y) >= saturationThreshold) {
                telemetry.starsRejectedSaturated++;
                continue;
            }
            survivors.add(new Star(star.x, star.y, star.totalFlux));
        }

        List<Star> selected = spreadAcrossField(survivors, width, height, config.photometryMaxStars);
        telemetry.starsRejectedByCap = survivors.size() - selected.size();
        telemetry.starsSelected = selected.size();
        selected.sort(Comparator.comparingDouble((Star s) -> s.flux).reversed());
        return selected;
    }

    /**
     * Estimates the saturation level from the pile-up of clipped pixels at the top of each frame's
     * histogram. Frames without a pile-up do not vote; when no frame has one, 95% of the 16-bit
     * encoding maximum is used and check B remains the guard against roll-off below full well.
     *
     * @return saturation level in the shifted non-negative pixel domain
     */
    static double estimateSaturationLevel(List<ImageFrame> frames) {
        double[] pileUps = new double[frames.size()];
        int found = 0;
        for (ImageFrame frame : frames) {
            int[] histogram = new int[65536];
            int max = 0;
            for (short[] row : frame.pixelData) {
                for (short value : row) {
                    int v = PixelEncoding.toShiftedPositiveInt(value);
                    histogram[v]++;
                    if (v > max) {
                        max = v;
                    }
                }
            }
            int tolerance = Math.max(1, (int) Math.round(PILE_UP_TOLERANCE * max));
            int atTop = 0;
            for (int v = Math.max(0, max - tolerance); v <= max; v++) {
                atTop += histogram[v];
            }
            if (atTop >= PILE_UP_MIN_PIXELS) {
                pileUps[found++] = max;
            }
        }
        if (found == 0) {
            return NO_PILE_UP_FRACTION * 65535.0;
        }
        return PhotometryMath.median(pileUps, found);
    }

    /**
     * Median of a regular pixel sample, in the shifted domain.
     */
    static double sampleMedian(short[][] image) {
        int height = image.length;
        int width = image[0].length;
        int step = Math.max(1, (int) Math.sqrt((double) width * height / 200_000.0));
        double[] sample = new double[((height + step - 1) / step) * ((width + step - 1) / step)];
        int n = 0;
        for (int y = 0; y < height; y += step) {
            for (int x = 0; x < width; x += step) {
                sample[n++] = PixelEncoding.toShiftedPositiveInt(image[y][x]);
            }
        }
        return PhotometryMath.median(sample, n);
    }

    private static boolean touchesVoid(short[][] image, double cx, double cy, double radius, double voidThreshold) {
        if (voidThreshold <= 0) {
            return false;
        }
        int r = (int) Math.ceil(radius);
        int x0 = (int) Math.round(cx);
        int y0 = (int) Math.round(cy);
        double r2 = radius * radius;
        for (int dy = -r; dy <= r; dy++) {
            int y = y0 + dy;
            if (y < 0 || y >= image.length) {
                return true;
            }
            for (int dx = -r; dx <= r; dx++) {
                if (dx * dx + dy * dy > r2) {
                    continue;
                }
                int x = x0 + dx;
                if (x < 0 || x >= image[0].length) {
                    return true;
                }
                if (PixelEncoding.toShiftedPositiveInt(image[y][x]) <= voidThreshold) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Keeps at most {@code maxStars}, spread over a grid of field regions and, inside each region,
     * over the whole brightness range: each region contributes its share of the cap, taken at evenly
     * spaced brightness ranks. Taking the brightest first would fill a rich field with stars close to
     * saturation and leave too few in the linear range.
     */
    private static List<Star> spreadAcrossField(List<Star> stars, int width, int height, int maxStars) {
        if (maxStars <= 0 || stars.size() <= maxStars) {
            return new ArrayList<>(stars);
        }
        List<List<Star>> regions = new ArrayList<>();
        for (int i = 0; i < SELECTION_GRID * SELECTION_GRID; i++) {
            regions.add(new ArrayList<>());
        }
        for (Star star : stars) {
            int gx = Math.min(SELECTION_GRID - 1, (int) (star.x * SELECTION_GRID / width));
            int gy = Math.min(SELECTION_GRID - 1, (int) (star.y * SELECTION_GRID / height));
            regions.get(gy * SELECTION_GRID + gx).add(star);
        }
        double fraction = maxStars / (double) stars.size();
        List<Star> selected = new ArrayList<>(maxStars);
        for (List<Star> region : regions) {
            region.sort(Comparator.comparingDouble((Star s) -> s.flux).reversed());
            int quota = (int) Math.round(region.size() * fraction);
            for (int k = 0; k < quota && selected.size() < maxStars; k++) {
                // Centre of the k-th of 'quota' equal rank intervals.
                int rank = (int) Math.floor((k + 0.5) * region.size() / quota);
                selected.add(region.get(Math.min(region.size() - 1, rank)));
            }
        }
        return selected;
    }

    /**
     * Highest master-stack value within one pixel of the star centre, in the shifted domain.
     */
    private static int masterPeak(short[][] image, double cx, double cy) {
        int x0 = (int) Math.round(cx);
        int y0 = (int) Math.round(cy);
        int peak = 0;
        for (int y = Math.max(0, y0 - 1); y <= Math.min(image.length - 1, y0 + 1); y++) {
            for (int x = Math.max(0, x0 - 1); x <= Math.min(image[0].length - 1, x0 + 1); x++) {
                peak = Math.max(peak, PixelEncoding.toShiftedPositiveInt(image[y][x]));
            }
        }
        return peak;
    }

    /**
     * Grid lookup of master stars for the crowding test.
     */
    private static final class NeighbourIndex {
        private final double cellSize;
        private final Map<Long, List<SourceExtractor.DetectedObject>> cells = new HashMap<>();

        NeighbourIndex(List<SourceExtractor.DetectedObject> stars, double cellSize) {
            this.cellSize = cellSize;
            for (SourceExtractor.DetectedObject star : stars) {
                cells.computeIfAbsent(key((int) Math.floor(star.x / cellSize), (int) Math.floor(star.y / cellSize)),
                        k -> new ArrayList<>()).add(star);
            }
        }

        boolean isCrowded(SourceExtractor.DetectedObject star, double closeRadius, double innerRadius) {
            double searchRadius = Math.max(closeRadius, innerRadius);
            int span = (int) Math.ceil(searchRadius / cellSize);
            int cx = (int) Math.floor(star.x / cellSize);
            int cy = (int) Math.floor(star.y / cellSize);
            for (int gy = cy - span; gy <= cy + span; gy++) {
                for (int gx = cx - span; gx <= cx + span; gx++) {
                    List<SourceExtractor.DetectedObject> cell = cells.get(key(gx, gy));
                    if (cell == null) {
                        continue;
                    }
                    for (SourceExtractor.DetectedObject other : cell) {
                        if (other == star) {
                            continue;
                        }
                        double d = Math.hypot(other.x - star.x, other.y - star.y);
                        if (d < closeRadius) {
                            return true;
                        }
                        if (d < innerRadius && other.totalFlux >= BRIGHT_NEIGHBOUR_FLUX_RATIO * star.totalFlux) {
                            return true;
                        }
                    }
                }
            }
            return false;
        }

        private static long key(int gx, int gy) {
            return ((long) gx << 32) ^ (gy & 0xffffffffL);
        }
    }
}
