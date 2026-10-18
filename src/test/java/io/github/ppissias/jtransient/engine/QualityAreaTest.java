package io.github.ppissias.jtransient.engine;

import io.github.ppissias.jtransient.config.DetectionConfig;
import io.github.ppissias.jtransient.core.DataArea;
import io.github.ppissias.jtransient.core.FrameCoverage;
import io.github.ppissias.jtransient.core.PixelEncoding;
import io.github.ppissias.jtransient.core.SourceExtractor;
import io.github.ppissias.jtransient.telemetry.PipelineTelemetry;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The sky and the quality metrics are measured where every registered frame has image data, and a frame that alone
 * shrinks that area a lot is rejected.
 */
public class QualityAreaTest {

    private static final int SIZE = 220;
    private static final double NOISE = 12;

    @Test
    public void backgroundInsideTheAreaIgnoresTheFill() {
        List<double[]> stars = stars(new Random(3));
        short[][] noFill = createFrame(new Random(5), stars, 0);
        short[][] frame = createFrame(new Random(5), stars, 66);
        DataArea area = DataArea.of(List.of(FrameCoverage.measure(0, frame)));
        DetectionConfig config = new DetectionConfig();
        SourceExtractor.BackgroundMetrics reference = SourceExtractor.calculateBackgroundSigmaClipped(noFill, SIZE, SIZE, 5, config);
        SourceExtractor.BackgroundMetrics whole = SourceExtractor.calculateBackgroundSigmaClipped(frame, SIZE, SIZE, 5, config);
        SourceExtractor.BackgroundMetrics inside = SourceExtractor.calculateBackgroundSigmaClipped(frame, SIZE, SIZE, 5, config, area);
        assertTrue("whole-frame sigma " + whole.sigma + ", without fill " + reference.sigma, whole.sigma > 5 * reference.sigma);
        assertEquals(reference.sigma, inside.sigma, 0.15 * reference.sigma);
        assertEquals(reference.median, inside.median, 3);
    }

    @Test
    public void aFrameShiftedFarFromTheOthersIsRejectedAndNamed() throws Exception {
        List<double[]> stars = stars(new Random(3));
        List<ImageFrame> frames = new ArrayList<>();
        for (int f = 0; f < 8; f++) {
            int fill = f == 3 ? 30 : f % 2; // frame 3 is shifted by 30 px, the others by at most 1 px
            frames.add(new ImageFrame(f, "frame" + f, createFrame(new Random(100 + f), stars, fill),
                    1_760_000_000_000L + f * 120_000L, 60_000L));
        }
        JTransientEngine engine = new JTransientEngine();
        try {
            JTransientEngine.MasterStackResult result = engine.generateMasterStackWithDetails(frames, new DetectionConfig(), null);
            assertFalse(result.keptFrames.contains(3));
            PipelineTelemetry.FrameRejectionStat rejected = result.rejectedFrames.get(0);
            assertEquals(3, rejected.frameIndex);
            assertTrue(rejected.reason, rejected.reason.startsWith("Shifted away from the other frames"));
            assertEquals(7, result.keptFrames.size());
        } finally {
            engine.shutdown();
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

    /** Gaussian stars over a sky of 1000 ADU, with registration fill (0 ADU) in the leftmost columns. */
    private static short[][] createFrame(Random noise, List<double[]> stars, int fillColumns) {
        short[][] pixels = new short[SIZE][SIZE];
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                double value = 1000 + noise.nextGaussian() * NOISE;
                for (double[] star : stars) {
                    double dx = x - star[0];
                    double dy = y - star[1];
                    if (dx * dx + dy * dy < 64) {
                        value += star[2] / (2 * Math.PI * 1.6) * Math.exp(-(dx * dx + dy * dy) / (2 * 1.6));
                    }
                }
                pixels[y][x] = PixelEncoding.fromShiftedPositiveInt(x < fillColumns ? 0 : (int) Math.min(60000, value));
            }
        }
        return pixels;
    }
}
