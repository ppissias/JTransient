package io.github.ppissias.jtransient.engine;

import io.github.ppissias.jtransient.config.DetectionConfig;
import io.github.ppissias.jtransient.core.MasterVetoMask;
import io.github.ppissias.jtransient.core.PixelEncoding;
import io.github.ppissias.jtransient.core.SourceExtractor;
import io.github.ppissias.jtransient.telemetry.PipelineTelemetry;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** The stand-alone master stack must be the one the pipeline builds, including the frame selection. */
public class MasterStackConsistencyTest {

    private static final int SIZE = 220;

    @Test
    public void generateMasterStackMatchesThePipelineAndLeavesOutABlankFrame() throws Exception {
        DetectionConfig config = new DetectionConfig();
        JTransientEngine engine = new JTransientEngine();
        try {
            PipelineResult pipeline = engine.runPipeline(createFrames(), config.clone(), null);
            JTransientEngine.MasterStackResult standalone = engine.generateMasterStackWithDetails(createFrames(), config, null);

            assertEquals(pipeline.masterStackData.length, standalone.masterStack.length);
            for (int row = 0; row < standalone.masterStack.length; row++) {
                assertArrayEquals("row " + row, pipeline.masterStackData[row], standalone.masterStack[row]);
            }
            // The half-blank frame (sequence 6) is left out by both, for the same reason.
            assertFalse(standalone.keptFrames.contains(6));
            boolean found = false;
            for (PipelineTelemetry.FrameRejectionStat rejected : standalone.rejectedFrames) {
                found |= rejected.frameIndex == 6;
            }
            assertTrue(found);
            assertEquals(pipeline.telemetry.totalFramesKept, standalone.keptFrames.size());
        } finally {
            engine.shutdown();
        }
    }

    @Test
    public void vetoMaskWidensEachFootprintByHalfTheJitter() {
        SourceExtractor.DetectedObject star = new SourceExtractor.DetectedObject(10, 10, 100.0, 1);
        star.rawPixels = Collections.singletonList(new SourceExtractor.Pixel(10, 10, 100));
        // Jitter 2 -> radius 1: the pixel and its four neighbours.
        boolean[][] mask = MasterVetoMask.build(Collections.singletonList(star), 20, 20, 2.0);
        assertTrue(mask[10][10] && mask[10][11] && mask[10][9] && mask[11][10] && mask[9][10]);
        assertFalse(mask[11][11]);
        assertEquals(5.0 / 400.0, MasterVetoMask.coverage(mask), 1e-12);
        assertEquals(1, MasterVetoMask.dilationRadius(0.5));
        assertEquals(3, MasterVetoMask.dilationRadius(6.0));
    }

    /** Seven aligned frames of Gaussian stars over noise; frame 6 holds image data in only its right third. */
    private static List<ImageFrame> createFrames() {
        Random random = new Random(11);
        List<double[]> stars = new ArrayList<>();
        for (int i = 0; i < 120; i++) {
            stars.add(new double[]{15 + random.nextDouble() * (SIZE - 30), 15 + random.nextDouble() * (SIZE - 30),
                    3000.0 * Math.pow(30.0, random.nextDouble())});
        }
        List<ImageFrame> frames = new ArrayList<>();
        for (int f = 0; f < 7; f++) {
            Random noise = new Random(100 + f);
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
                    boolean blank = f == 6 && x < SIZE * 2 / 3;
                    pixels[y][x] = PixelEncoding.fromShiftedPositiveInt(blank ? 0 : (int) Math.min(60000, value));
                }
            }
            frames.add(new ImageFrame(f, "frame" + f, pixels, 1_760_000_000_000L + f * 120_000L, 60_000L));
        }
        return frames;
    }
}
