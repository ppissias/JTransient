package io.github.ppissias.jtransient.core;

import io.github.ppissias.jtransient.engine.ImageFrame;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class FrameDriftAnalyzerTest {

    private static final int WIDTH = 200;
    private static final int HEIGHT = 150;

    @Test
    public void ordinaryDriftSetsVoidRadius() {
        List<ImageFrame> frames = Arrays.asList(
                frame(0, 0, 0),
                frame(1, 12, 0),
                frame(2, 0, 7));

        FrameDriftAnalyzer.DriftAnalysisResult result = FrameDriftAnalyzer.analyze(frames, 5);

        assertEquals(12, result.maxPaddingPixels);
        assertEquals(22, result.recommendedVoidProximityRadius);
        assertEquals(Collections.emptyList(), result.failedRegistrationFrames);
        assertEquals(3, result.driftPoints.size());
        assertEquals(12, result.driftPoints.get(1).x);
        assertEquals(7, result.driftPoints.get(2).y);
    }

    @Test
    public void failedRegistrationIsLeftOutOfTheDriftAnalysis() {
        // Frame 2 is shifted by 70% of its width: a failed registration, not dither.
        List<ImageFrame> frames = Arrays.asList(
                frame(0, 0, 0),
                frame(1, 12, 0),
                frame(2, 140, 0),
                frame(3, 0, 7));

        FrameDriftAnalyzer.DriftAnalysisResult result = FrameDriftAnalyzer.analyze(frames, 5);

        assertEquals(12, result.maxPaddingPixels);
        assertEquals(22, result.recommendedVoidProximityRadius);
        assertEquals(Collections.singletonList(2), result.failedRegistrationFrames);
        assertEquals(3, result.driftPoints.size());
        assertEquals(3, result.driftPoints.get(2).value);
    }

    @Test
    public void blankFrameWithStrayPixelsIsLeftOut() {
        short[][] blank = new short[HEIGHT][WIDTH];
        for (short[] row : blank) {
            Arrays.fill(row, Short.MIN_VALUE);
        }
        for (int i = 0; i < 40; i++) {
            blank[(i * 37) % HEIGHT][(i * 53) % WIDTH] = 500; // a few stray valid pixels
        }
        List<ImageFrame> frames = Arrays.asList(
                frame(0, 0, 0),
                new ImageFrame(1, "blank", blank, 1000L, 1000L),
                frame(2, 6, 0));

        FrameDriftAnalyzer.DriftAnalysisResult result = FrameDriftAnalyzer.analyze(frames, 5);

        assertEquals(Collections.singletonList(1), result.failedRegistrationFrames);
        assertEquals(2, result.driftPoints.size());
        assertEquals(6, result.driftPoints.get(1).x);
        assertEquals(6, result.maxPaddingPixels);
    }

    @Test
    public void rotatedFrameMeasuresTranslationAtEdgeMiddles() {
        // Registration shifted and slightly rotated the frame: the top padding grows from 20 to 40 px across
        // the width and the right padding from 0 to 10 px down the height (wedges, no full-width band).
        short[][] pixels = new short[HEIGHT][WIDTH];
        for (int y = 0; y < HEIGHT; y++) {
            int rightDepth = (int) Math.round(10.0 * y / (HEIGHT - 1));
            for (int x = 0; x < WIDTH; x++) {
                int topDepth = 20 + (int) Math.round(20.0 * x / (WIDTH - 1));
                boolean padded = y < topDepth || x >= WIDTH - rightDepth;
                pixels[y][x] = padded ? Short.MIN_VALUE : 0;
            }
        }
        List<ImageFrame> frames = Arrays.asList(frame(0, 0, 0),
                new ImageFrame(1, "rotated", pixels, 1000L, 1000L));

        FrameDriftAnalyzer.DriftAnalysisResult result = FrameDriftAnalyzer.analyze(frames, 5);

        SourceExtractor.Pixel drift = result.driftPoints.get(1);
        assertTrue("dy " + drift.y, Math.abs(drift.y - 30) <= 1);
        // The old first-valid-column rule reported no x drift at all for this frame.
        assertTrue("dx " + drift.x, Math.abs(drift.x + 6) <= 2);
        assertTrue("extent " + result.maxPaddingPixels, result.maxPaddingPixels >= 33 && result.maxPaddingPixels <= 36);
    }

    /** Builds a frame whose left {@code padLeft} columns and top {@code padTop} rows are alignment padding. */
    private static ImageFrame frame(int index, int padLeft, int padTop) {
        short[][] pixels = new short[HEIGHT][WIDTH];
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                pixels[y][x] = (x < padLeft || y < padTop) ? Short.MIN_VALUE : 0;
            }
        }
        return new ImageFrame(index, "f" + index, pixels, index * 1000L, 1000L);
    }
}
