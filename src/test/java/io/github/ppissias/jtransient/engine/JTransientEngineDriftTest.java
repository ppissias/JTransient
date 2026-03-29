package io.github.ppissias.jtransient.engine;

import io.github.ppissias.jtransient.config.DetectionConfig;
import io.github.ppissias.jtransient.core.SourceExtractor;
import org.junit.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;

/**
 * Regression tests for the private drift-analysis pre-pass inside JTransientEngine.
 * These tests lock down how registration padding is measured and reported.
 */
public class JTransientEngineDriftTest {

    /**
     * Verifies drift analysis uses the true valid-image bounds, not a center-cross heuristic.
     * The synthetic frame has its center burned out, but the method should still recover the
     * correct left/top padding from the outer valid area.
     */
    @Test
    public void analyzeDitherAndDriftUsesWholeFrameBoundsInsteadOfCenterCross() throws Exception {
        JTransientEngine engine = new JTransientEngine();
        try {
            DetectionConfig config = new DetectionConfig();
            short[][] frame = createFrame(40, 40, 4, 2, 3, 1, (short) -32600);
            burnCenterCross(frame);

            List<SourceExtractor.Pixel> driftPoints = analyze(engine, Arrays.asList(
                    new ImageFrame(0, "frame-0", frame, -1L, -1L)
            ), config);

            assertEquals(1, driftPoints.size());
            assertEquals(2, driftPoints.get(0).x);
            assertEquals(2, driftPoints.get(0).y);
            assertEquals(0, driftPoints.get(0).value);
        } finally {
            engine.shutdown();
        }
    }

    /**
     * Verifies the engine raises voidProximityRadius to cover the largest measured padding extent.
     * This protects later extraction passes from underestimating real registration voids.
     */
    @Test
    public void analyzeDitherAndDriftRaisesVoidRadiusFromLargestPadding() throws Exception {
        JTransientEngine engine = new JTransientEngine();
        try {
            DetectionConfig config = new DetectionConfig();
            config.voidProximityRadius = 5;

            short[][] frame = createFrame(60, 60, 12, 1, 2, 7, (short) -32500);
            List<SourceExtractor.Pixel> driftPoints = analyze(engine, Arrays.asList(
                    new ImageFrame(0, "frame-0", frame, -1L, -1L)
            ), config);

            assertEquals(1, driftPoints.size());
            assertEquals(11, driftPoints.get(0).x);
            assertEquals(-5, driftPoints.get(0).y);
            assertEquals(22, config.voidProximityRadius);
        } finally {
            engine.shutdown();
        }
    }

    /**
     * Verifies the reported drift path is ordered by frame sequenceIndex, not by input list order.
     * The helper receives frames out of order and should still return a time-ordered path.
     */
    @Test
    public void analyzeDitherAndDriftReturnsSequenceOrderedPath() throws Exception {
        JTransientEngine engine = new JTransientEngine();
        try {
            DetectionConfig config = new DetectionConfig();
            short[][] earlyFrame = createFrame(30, 30, 5, 1, 0, 0, (short) -32550);
            short[][] lateFrame = createFrame(30, 30, 1, 3, 0, 0, (short) -32550);

            List<SourceExtractor.Pixel> driftPoints = analyze(engine, Arrays.asList(
                    new ImageFrame(5, "late", lateFrame, -1L, -1L),
                    new ImageFrame(1, "early", earlyFrame, -1L, -1L)
            ), config);

            assertEquals(2, driftPoints.size());
            assertEquals(1, driftPoints.get(0).value);
            assertEquals(4, driftPoints.get(0).x);
            assertEquals(0, driftPoints.get(0).y);
            assertEquals(5, driftPoints.get(1).value);
            assertEquals(-2, driftPoints.get(1).x);
            assertEquals(0, driftPoints.get(1).y);
        } finally {
            engine.shutdown();
        }
    }

    @SuppressWarnings("unchecked")
    private static List<SourceExtractor.Pixel> analyze(JTransientEngine engine,
                                                       List<ImageFrame> frames,
                                                       DetectionConfig config) throws Exception {
        Method method = JTransientEngine.class.getDeclaredMethod(
                "analyzeDitherAndDrift",
                List.class,
                DetectionConfig.class,
                TransientEngineProgressListener.class
        );
        method.setAccessible(true);
        return (List<SourceExtractor.Pixel>) method.invoke(engine, frames, config, null);
    }

    private static short[][] createFrame(int width,
                                         int height,
                                         int leftPadding,
                                         int rightPadding,
                                         int topPadding,
                                         int bottomPadding,
                                         short validValue) {
        // Valid image area is filled with a constant while the padded border stays at Short.MIN_VALUE.
        short[][] frame = new short[height][width];
        for (int y = 0; y < height; y++) {
            Arrays.fill(frame[y], Short.MIN_VALUE);
        }

        int minX = leftPadding;
        int maxX = width - rightPadding;
        int minY = topPadding;
        int maxY = height - bottomPadding;
        for (int y = minY; y < maxY; y++) {
            for (int x = minX; x < maxX; x++) {
                frame[y][x] = validValue;
            }
        }
        return frame;
    }

    private static void burnCenterCross(short[][] frame) {
        // Simulates a bad center-cross heuristic by destroying the frame center after valid bounds are created.
        int centerX = frame[0].length / 2;
        int centerY = frame.length / 2;
        for (int x = 0; x < frame[0].length; x++) {
            frame[centerY][x] = Short.MIN_VALUE;
        }
        for (int y = 0; y < frame.length; y++) {
            frame[y][centerX] = Short.MIN_VALUE;
        }
    }
}
