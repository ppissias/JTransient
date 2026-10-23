package io.github.ppissias.jtransient.engine;

import io.github.ppissias.jtransient.model.DetectedObject;
import io.github.ppissias.jtransient.model.Pixel;
import io.github.ppissias.jtransient.config.DetectionConfig;
import io.github.ppissias.jtransient.core.DataArea;
import io.github.ppissias.jtransient.core.FrameCoverage;
import io.github.ppissias.jtransient.core.PixelEncoding;
import io.github.ppissias.jtransient.core.SourceExtractor;
import io.github.ppissias.jtransient.quality.FrameQualityAnalyzer;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Detection keeps to the session data area: objects must lie inside it, frames are compared on it, and nothing is
 * reported where some kept frame has no data.
 */
public class DetectionAreaTest {

    private static final int SIZE = 220;

    @Test
    public void pointSourcesNeedEveryPixelInsideAndStreaksTheirCentroid() {
        DataArea area = DataArea.fullFrame(50, 50);
        DetectedObject point = new DetectedObject(4, 20, 100, 2);
        point.rawPixels = List.of(new Pixel(3, 20, 100), new Pixel(2, 20, 100));
        assertFalse(SourceExtractor.isInside(point, area));
        point.rawPixels = List.of(new Pixel(3, 20, 100), new Pixel(4, 20, 100));
        assertTrue(SourceExtractor.isInside(point, area));

        DetectedObject streak = new DetectedObject(20, 20, 100, 2);
        streak.isStreak = true;
        streak.rawPixels = List.of(new Pixel(0, 20, 100), new Pixel(40, 20, 100));
        assertTrue(SourceExtractor.isInside(streak, area));
    }

    @Test
    public void starCountsAreComparedOnTheSameSky() {
        List<double[]> stars = stars(new Random(3));
        short[][] noFill = createFrame(new Random(5), stars, 0, null);
        short[][] withFill = createFrame(new Random(6), stars, 40, null);
        DataArea area = DataArea.of(List.of(FrameCoverage.measure(0, noFill), FrameCoverage.measure(1, withFill)));
        DetectionConfig config = new DetectionConfig();
        int plainNoFill = FrameQualityAnalyzer.evaluateFrame(noFill, config).starCount;
        int plainWithFill = FrameQualityAnalyzer.evaluateFrame(withFill, config).starCount;
        int areaNoFill = FrameQualityAnalyzer.evaluateFrame(noFill, config, area).starCount;
        int areaWithFill = FrameQualityAnalyzer.evaluateFrame(withFill, config, area).starCount;
        assertTrue(plainWithFill < plainNoFill);
        assertTrue("inside the area " + areaNoFill + " vs " + areaWithFill, Math.abs(areaNoFill - areaWithFill) <= 2);
    }

    @Test
    public void transientsOutsideTheDataAreaAreNotReported() throws Exception {
        List<double[]> stars = stars(new Random(3));
        List<ImageFrame> frames = new ArrayList<>();
        for (int f = 0; f < 7; f++) {
            // Frame 6 lacks the 9 leftmost columns (4 % of the frame, so it is kept); frame 2 has a flash where frame 6
            // has no data, frame 4 one inside.
            int fill = f == 6 ? 9 : 0;
            double[] flash = f == 2 ? new double[]{8, 110} : f == 4 ? new double[]{120, 110} : null;
            frames.add(new ImageFrame(f, "frame" + f, createFrame(new Random(100 + f), stars, fill, flash),
                    1_760_000_000_000L + f * 120_000L, 60_000L));
        }
        try (JTransientEngine engine = new JTransientEngine()) {
            PipelineResult result = engine.runPipeline(frames, new DetectionConfig(), null);
            assertEquals(7, result.telemetry.totalFramesKept);
            boolean insideFound = false;
            for (List<DetectedObject> frame : result.allTransients) {
                for (DetectedObject object : frame) {
                    assertTrue("transient at " + object.x + "," + object.y, result.dataArea.contains(object.x, object.y));
                    insideFound |= Math.hypot(object.x - 120, object.y - 110) < 3;
                }
            }
            assertTrue(insideFound);
        }
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
