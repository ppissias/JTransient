package io.github.ppissias.jtransient.core;

import io.github.ppissias.jtransient.config.DetectionConfig;
import org.junit.Test;

import java.util.Arrays;
import java.util.Random;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class SkyBackgroundTest {
    private static final int SHIFT = 32768;

    /** Sky rising 2,000 ADU from left to right and 500 ADU from top to bottom, with Gaussian noise. */
    private static short[][] gradientSky(int width, int height, double noise, long seed) {
        Random random = new Random(seed);
        short[][] image = new short[height][width];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                double sky = 1000 + 2000.0 * x / (width - 1) + 500.0 * y / (height - 1);
                image[y][x] = (short) (Math.round(sky + noise * random.nextGaussian()) - SHIFT);
            }
        }
        return image;
    }

    private static double patchMedian(short[][] image, int x0, int y0, int size) {
        double[] values = new double[size * size];
        int n = 0;
        for (int y = y0; y < y0 + size; y++) {
            for (int x = x0; x < x0 + size; x++) {
                values[n++] = image[y][x] + SHIFT;
            }
        }
        Arrays.sort(values);
        return values[n / 2];
    }

    @Test
    public void aLinearGradientIsRemovedUpToTheImageEdges() {
        short[][] image = gradientSky(1536, 1024, 20, 1);
        short[][] flat = SkyBackground.flatten(image);

        double centre = patchMedian(flat, 736, 480, 64);
        int[][] patches = {{0, 0}, {1472, 0}, {0, 960}, {1472, 960}, {736, 0}, {0, 480}, {1472, 480}, {736, 960}};
        for (int[] p : patches) {
            assertEquals("patch at " + p[0] + "," + p[1], centre, patchMedian(flat, p[0], p[1], 64), 3.0);
        }

        DetectionConfig config = new DetectionConfig();
        double before = SourceExtractor.calculateBackgroundSigmaClipped(image, 1536, 1024, 3, config).sigma;
        double after = SourceExtractor.calculateBackgroundSigmaClipped(flat, 1536, 1024, 3, config).sigma;
        assertTrue("noise with the gradient " + before, before > 200);
        assertEquals(20, after, 3);
    }

    @Test
    public void aGalaxyAlmostOneBoxWideIsNotTakenAsSky() {
        Random random = new Random(2);
        short[][] image = new short[1536][1536];
        for (int y = 0; y < 1536; y++) {
            for (int x = 0; x < 1536; x++) {
                double r = Math.hypot(x - 768, y - 768);
                double galaxy = r < 240 ? 300 * (1 - r / 240) : 0;
                image[y][x] = (short) (Math.round(1000 + galaxy + 10 * random.nextGaussian()) - SHIFT);
            }
        }
        short[][] flat = SkyBackground.flatten(image);
        double sky = patchMedian(flat, 0, 0, 64);
        assertEquals(1000, sky, 3);
        assertTrue(patchMedian(flat, 763, 763, 10) - sky > 270);
    }

    @Test
    public void fillIsCopiedAndBoxesNextToItFollowTheSky() {
        short[][] image = gradientSky(2048, 1536, 20, 3);
        for (short[] row : image) {
            Arrays.fill(row, 0, 300, Short.MIN_VALUE);
        }
        short[][] flat = SkyBackground.flatten(image);
        for (short[] row : flat) {
            for (int x = 0; x < 300; x++) {
                assertEquals(Short.MIN_VALUE, row[x]);
            }
        }
        double centre = patchMedian(flat, 1152, 736, 64);
        assertEquals(centre, patchMedian(flat, 300, 0, 64), 3.0);
        assertEquals(centre, patchMedian(flat, 300, 1472, 64), 3.0);
        assertEquals(centre, patchMedian(flat, 1984, 736, 64), 3.0);
    }

    @Test
    public void pixelsNeverDropToTheFillLevel() {
        short[][] image = gradientSky(1024, 768, 20, 4);
        image[10][1000] = (short) (DataArea.FILL_LEVEL + 2 - SHIFT);
        short[][] flat = SkyBackground.flatten(image);
        assertTrue(flat[10][1000] > DataArea.FILL_THRESHOLD);
    }

    @Test
    public void anImageWithoutDataIsReturnedAsItIs() {
        short[][] image = new short[300][300];
        for (short[] row : image) {
            Arrays.fill(row, Short.MIN_VALUE);
        }
        assertSame(image, SkyBackground.flatten(image));
    }

    @Test
    public void anImageSmallerThanOneBoxIsUnchanged() {
        short[][] image = gradientSky(60, 40, 5, 5);
        short[][] flat = SkyBackground.flatten(image);
        for (int y = 0; y < 40; y++) {
            assertArrayEquals(image[y], flat[y]);
        }
    }

    @Test
    public void masterStarsAreFoundOnBothSidesOfAGradient() {
        int width = 1024;
        int height = 768;
        short[][] master = gradientSky(width, height, 20, 6);
        int[][] stars = new int[20][];
        for (int i = 0; i < stars.length; i++) {
            stars[i] = new int[]{60 + (i % 10) * 100, 200 + (i / 10) * 300};
            for (int dy = -4; dy <= 4; dy++) {
                for (int dx = -4; dx <= 4; dx++) {
                    double add = 200 * Math.exp(-(dx * dx + dy * dy) / (2 * 1.3 * 1.3));
                    master[stars[i][1] + dy][stars[i][0] + dx] += (short) Math.round(add);
                }
            }
        }
        DetectionConfig config = new DetectionConfig();
        config.masterSigmaMultiplier = 5;
        config.masterMinDetectionPixels = 3;

        java.util.List<SourceExtractor.DetectedObject> found =
                MasterReferenceAnalyzer.analyzeFromMasterStack(master, config).masterStars;

        for (int[] star : stars) {
            boolean hit = found.stream().anyMatch(o -> Math.hypot(o.x - star[0], o.y - star[1]) < 2);
            assertTrue("star at " + star[0] + "," + star[1], hit);
        }
    }
}
