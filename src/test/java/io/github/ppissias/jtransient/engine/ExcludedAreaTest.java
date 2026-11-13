package io.github.ppissias.jtransient.engine;

import io.github.ppissias.jtransient.config.DetectionConfig;
import io.github.ppissias.jtransient.core.PixelEncoding;
import io.github.ppissias.jtransient.model.DetectedObject;
import io.github.ppissias.jtransient.model.Pixel;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * Excluded areas: detection leaves them out in every frame, the frames' registration is still measured from their own
 * borders, and the caller's frames are unchanged after the run.
 */
public class ExcludedAreaTest {
    private static final int SIZE = 220;

    /** Painting writes fill into the areas, clipped to the image; restoring brings back every value, overlaps included. */
    @Test
    public void paintedPixelsAreRestoredExactly() {
        short[][] image = new short[40][50];
        Random random = new Random(1);
        for (short[] row : image) for (int x = 0; x < row.length; x++) row[x] = (short) random.nextInt();
        short[][] before = copy(image);
        ExcludedPixels pixels = new ExcludedPixels(List.of(new ExcludedArea(10, 5, 8, 20), new ExcludedArea(14, 0, 10, 12),
                new ExcludedArea(-5, 35, 20, 30), new ExcludedArea(100, 0, 5, 5)));
        pixels.paint(image);
        assertEquals(Short.MIN_VALUE, image[5][10]);
        assertEquals(Short.MIN_VALUE, image[0][23]);
        assertEquals(Short.MIN_VALUE, image[39][0]);
        assertEquals(before[4][10], image[4][10]);
        assertEquals(before[0][24], image[0][24]);
        pixels.restore();
        assertArrayEquals(before, image);
        pixels.restore();
        assertArrayEquals(before, image);
        assertThrows(IllegalArgumentException.class, () -> new ExcludedArea(0, 0, 0, 5));
        assertTrue(new ExcludedArea(2, 3, 4, 5).contains(5, 7));
        assertFalse(new ExcludedArea(2, 3, 4, 5).contains(6, 7));
    }

    /**
     * A flash inside an excluded area is not reported and one outside is; the registration measured from frame 6's
     * fill and the caller's pixels are the same as without the areas, though one area covers that fill.
     */
    @Test
    public void detectionLeavesExcludedAreasOutAndKeepsFramesAndRegistration() throws Exception {
        List<double[]> stars = stars(new Random(3));
        List<ImageFrame> frames = new ArrayList<>();
        for (int f = 0; f < 7; f++) {
            int fill = f == 6 ? 9 : 0;
            double[] flash = f == 2 ? new double[]{65, 110} : f == 4 ? new double[]{120, 110} : null;
            frames.add(new ImageFrame(f, "frame" + f, createFrame(new Random(100 + f), stars, fill, flash),
                    1_760_000_000_000L + f * 120_000L, 60_000L));
        }
        List<short[][]> before = new ArrayList<>();
        for (ImageFrame frame : frames) before.add(copy(frame.pixelData));
        List<ExcludedArea> areas = List.of(new ExcludedArea(50, 0, 30, SIZE), new ExcludedArea(0, 0, 20, SIZE));
        try (JTransientEngine engine = new JTransientEngine()) {
            PipelineResult plain = engine.runPipeline(frames, new DetectionConfig(), null);
            PipelineResult excluded = engine.runPipeline(frames, new DetectionConfig(), null, null, null, areas);
            // A full-height band holds fill in the median stack too; its sky is measured outside it, so the stack keeps
            // the stars elsewhere (the fill counted with the sky had raised the threshold above many of them).
            PipelineResult banded = engine.runPipeline(frames, new DetectionConfig(), null, null, null,
                    List.of(new ExcludedArea(50, 0, 30, SIZE)));
            assertTrue(banded.masterStars.size() + " of " + plain.masterStars.size(),
                    banded.masterStars.size() >= 0.75 * plain.masterStars.size());
            for (int f = 0; f < frames.size(); f++) {
                assertArrayEquals("frame " + f + " restored", before.get(f), frames.get(f).pixelData);
            }
            assertTrue(foundNear(plain, 65, 110));
            assertFalse(foundNear(excluded, 65, 110));
            assertTrue(foundNear(excluded, 120, 110));
            for (List<DetectedObject> frame : excluded.allTransients) {
                for (DetectedObject object : frame) {
                    for (ExcludedArea area : areas) {
                        assertFalse("transient at " + object.x + "," + object.y,
                                area.contains((int) Math.round(object.x), (int) Math.round(object.y)));
                    }
                }
            }
            assertEquals(points(plain.driftPoints), points(excluded.driftPoints));
        }
    }

    private static boolean foundNear(PipelineResult result, double x, double y) {
        for (List<DetectedObject> frame : result.allTransients) {
            for (DetectedObject object : frame) {
                if (Math.hypot(object.x - x, object.y - y) < 3) return true;
            }
        }
        return false;
    }

    private static List<String> points(List<Pixel> points) {
        List<String> text = new ArrayList<>();
        for (Pixel p : points) text.add(p.x + "," + p.y + "," + p.value);
        return text;
    }

    private static short[][] copy(short[][] image) {
        short[][] copy = new short[image.length][];
        for (int y = 0; y < image.length; y++) copy[y] = Arrays.copyOf(image[y], image[y].length);
        return copy;
    }

    private static List<double[]> stars(Random random) {
        List<double[]> stars = new ArrayList<>();
        for (int i = 0; i < 120; i++) {
            stars.add(new double[]{15 + random.nextDouble() * (SIZE - 30), 15 + random.nextDouble() * (SIZE - 30),
                    3000.0 * Math.pow(30.0, random.nextDouble())});
        }
        return stars;
    }

    /** Gaussian stars over a sky of 1000 ADU, fill (0 ADU) in the leftmost columns, and an optional bright flash. */
    private static short[][] createFrame(Random noise, List<double[]> stars, int fillColumns, double[] flash) {
        short[][] pixels = new short[SIZE][SIZE];
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                double value = 1000 + noise.nextGaussian() * 12;
                for (double[] star : stars) {
                    double dx = x - star[0];
                    double dy = y - star[1];
                    if (dx * dx + dy * dy < 64) {
                        value += star[2] / (2 * Math.PI * 1.6) * Math.exp(-(dx * dx + dy * dy) / (2 * 1.6));
                    }
                }
                if (flash != null) {
                    double dx = x - flash[0];
                    double dy = y - flash[1];
                    if (dx * dx + dy * dy < 64) {
                        value += 40000 / (2 * Math.PI * 1.6) * Math.exp(-(dx * dx + dy * dy) / (2 * 1.6));
                    }
                }
                pixels[y][x] = PixelEncoding.fromShiftedPositiveInt(x < fillColumns ? 0 : (int) Math.min(60000, value));
            }
        }
        return pixels;
    }
}
