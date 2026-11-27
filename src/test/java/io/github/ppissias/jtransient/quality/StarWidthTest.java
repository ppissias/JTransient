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
import io.github.ppissias.jtransient.core.PixelEncoding;
import io.github.ppissias.jtransient.model.DetectedObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The star width shared by the calibrated Auto-Tune and quality control: the core of bright isolated round stars,
 * measured as the tuner always measured it.
 */
public class StarWidthTest {
    private static final double SKY = 1000;
    private static final double NOISE = 10;

    /** Gaussian stars of a known FWHM read close to it; the second moments of the extraction would read wider. */
    @Test
    public void gaussianStarsReadTheirFwhm() {
        for (double fwhm : new double[]{2.0, 3.0, 4.5}) {
            List<double[]> stars = grid(fwhm, 600, new Random(5));
            short[][] image = image(512, 512, stars, fwhm, new Random(7));
            List<Double> widths = new ArrayList<>();
            StarWidth.measure(image, objects(stars, fwhm), SKY, widths, Integer.MAX_VALUE);
            assertEquals(stars.size(), widths.size());
            assertEquals("FWHM " + fwhm, fwhm, StarWidth.frameWidth(widths), 0.12 * fwhm);
        }
    }

    /** Quality control measures every frame the same way. */
    @Test
    public void qualityControlMeasuresEachFrame() {
        double fwhm = 3.0;
        List<double[]> stars = grid(fwhm, 600, new Random(9));
        short[][] image = image(512, 512, stars, fwhm, new Random(11));
        FrameQualityAnalyzer.FrameMetrics metrics = FrameQualityAnalyzer.evaluateFrame(image, new DetectionConfig());
        assertTrue(metrics.starWidthStars >= StarWidth.MIN_FRAME_STARS);
        assertEquals(fwhm, metrics.starWidthFwhm, 0.12 * fwhm);
        assertTrue("second moments read wider", metrics.medianFWHM > metrics.starWidthFwhm);
    }

    /** A star with a detection within twice its window, an elongated one, a faint or a very bright one, or one at the edge, is not measured. */
    @Test
    public void onlyIsolatedRoundUnsaturatedStarsAwayFromTheEdgeCount() {
        double fwhm = 3.0;
        List<double[]> stars = List.of(new double[]{100, 100, 1000}, new double[]{110, 100, 1000},
                new double[]{300, 300, 1000}, new double[]{2, 400, 1000});
        short[][] image = image(512, 512, stars, fwhm, new Random(1));
        List<DetectedObject> objects = objects(stars, fwhm);
        List<Double> widths = new ArrayList<>();
        StarWidth.measure(image, objects, SKY, widths, Integer.MAX_VALUE);
        assertEquals("only the isolated star inside the image", 1, widths.size());
        objects.get(2).elongation = 1.5;
        widths.clear();
        StarWidth.measure(image, objects, SKY, widths, Integer.MAX_VALUE);
        assertEquals(0, widths.size());
        objects.get(2).elongation = 1.0;
        objects.get(2).peakSigma = StarWidth.MAX_PEAK_SIGMA + 1;
        StarWidth.measure(image, objects, SKY, widths, Integer.MAX_VALUE);
        assertEquals(0, widths.size());
        objects.get(2).peakSigma = StarWidth.MIN_PEAK_SIGMA - 1;
        StarWidth.measure(image, objects, SKY, widths, Integer.MAX_VALUE);
        assertEquals(0, widths.size());
        assertTrue(Double.isNaN(StarWidth.frameWidth(List.of(3.0, 3.0, 3.0, 3.0))));
    }

    /**
     * The shared measurement gives exactly what the tuner's own loop gave, which compared every detection with every
     * other, on crowded random fields, including the limit's stop after each passing star.
     */
    @Test
    public void matchesTheTunersOriginalLoopExactly() {
        Random random = new Random(21);
        for (int trial = 0; trial < 20; trial++) {
            double fwhm = 1.5 + 3 * random.nextDouble();
            List<double[]> stars = new ArrayList<>();
            for (int i = 0; i < 150 + random.nextInt(250); i++) {
                stars.add(new double[]{random.nextDouble() * 300, random.nextDouble() * 200, 200 + 2000 * random.nextDouble()});
            }
            short[][] image = image(300, 200, stars, fwhm, random);
            List<DetectedObject> objects = objects(stars, fwhm);
            for (DetectedObject obj : objects) {
                obj.peakSigma = 10 + 160 * random.nextDouble();
                obj.elongation = 1 + 0.4 * random.nextDouble();
                obj.fwhm = fwhm * (1 + random.nextDouble());
                obj.isStreak = random.nextDouble() < 0.05;
            }
            for (int limit : new int[]{Integer.MAX_VALUE, 7}) {
                List<Double> shared = new ArrayList<>(List.of(1.0, 2.0));
                List<Double> original = new ArrayList<>(shared);
                StarWidth.measure(image, objects, SKY, shared, limit);
                originalLoop(image, objects, SKY, original, limit);
                assertEquals(original, shared);
            }
        }
    }

    /** The tuner's loop before it moved here, comparing each detection with every other. */
    private static void originalLoop(short[][] image, List<DetectedObject> objects, double background,
                                     List<Double> fwhms, int limit) {
        int h = image.length;
        int w = image[0].length;
        for (DetectedObject obj : objects) {
            if (obj.isStreak || obj.elongation > 1.3 || obj.peakSigma < 20 || obj.peakSigma > 150) continue;
            double window = Math.max(4.0, 2.0 * obj.fwhm);
            boolean isolated = true;
            for (DetectedObject other : objects) {
                if (other != obj && Math.hypot(other.x - obj.x, other.y - obj.y) < 2 * window) {
                    isolated = false;
                    break;
                }
            }
            if (!isolated) continue;
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
            if (fwhm > 0.5) fwhms.add(fwhm);
            if (fwhms.size() >= limit) break;
        }
    }

    /** Stars {x, y, peak} on a jittered grid, far enough apart to be isolated. */
    private static List<double[]> grid(double fwhm, double peak, Random random) {
        List<double[]> stars = new ArrayList<>();
        int step = (int) Math.ceil(10 * fwhm);
        for (int y = step; y < 512 - step; y += step) {
            for (int x = step; x < 512 - step; x += step) {
                stars.add(new double[]{x + random.nextDouble() - 0.5, y + random.nextDouble() - 0.5, peak});
            }
        }
        return stars;
    }

    /** Detections at the stars, as the extraction would give them: round, 50 sigma, second-moment FWHM. */
    private static List<DetectedObject> objects(List<double[]> stars, double fwhm) {
        List<DetectedObject> objects = new ArrayList<>();
        for (double[] star : stars) {
            DetectedObject obj = new DetectedObject(star[0], star[1], star[2], 10);
            obj.fwhm = fwhm * Math.sqrt(2);
            obj.elongation = 1.0;
            obj.peakSigma = 50;
            objects.add(obj);
        }
        return objects;
    }

    private static short[][] image(int width, int height, List<double[]> stars, double fwhm, Random random) {
        double sigma = fwhm / 2.3548;
        double[][] values = new double[height][width];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                values[y][x] = SKY + NOISE * random.nextGaussian();
            }
        }
        int radius = (int) Math.ceil(5 * sigma);
        for (double[] star : stars) {
            for (int y = Math.max(0, (int) star[1] - radius); y <= Math.min(height - 1, (int) star[1] + radius); y++) {
                for (int x = Math.max(0, (int) star[0] - radius); x <= Math.min(width - 1, (int) star[0] + radius); x++) {
                    double dx = x - star[0];
                    double dy = y - star[1];
                    values[y][x] += star[2] * Math.exp(-(dx * dx + dy * dy) / (2 * sigma * sigma));
                }
            }
        }
        short[][] image = new short[height][width];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image[y][x] = PixelEncoding.fromShiftedPositiveInt((int) Math.max(0, Math.min(65535, Math.round(values[y][x]))));
            }
        }
        return image;
    }
}
