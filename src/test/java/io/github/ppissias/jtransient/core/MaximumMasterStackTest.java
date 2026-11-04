package io.github.ppissias.jtransient.core;

import io.github.ppissias.jtransient.engine.ImageFrame;
import io.github.ppissias.jtransient.execution.WorkerPool;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;

/** Checks the complete 16-bit intensity domain and the maximum stack's borrowed-input ownership. */
public class MaximumMasterStackTest {

    /** Every possible maximum, including both storage endpoints, can come from any source frame or row. */
    @Test
    public void preservesAllPixelValuesWithoutChangingSources() {
        List<ImageFrame> frames = new ArrayList<>();
        for (int frameIndex = 0; frameIndex < 3; frameIndex++) {
            short[][] pixels = new short[2][65536];
            for (int y = 0; y < pixels.length; y++) {
                for (int x = 0; x < pixels[y].length; x++) {
                    pixels[y][x] = sourcePixel(frameIndex, x, y);
                }
            }
            frames.add(new ImageFrame(frameIndex, "frame-" + frameIndex, pixels, -1, -1));
        }

        short[][] maximum;
        try (var pool = new WorkerPool(2); var scope = pool.open()) {
            pool.limitWork(2, 65536);
            maximum = MasterMapGenerator.createMaximumMasterStack(frames);
        }

        for (int y = 0; y < maximum.length; y++) {
            for (int x = 0; x < maximum[y].length; x++) {
                int expected = y == 0 ? x : 65535 - x;
                assertEquals(expected, PixelEncoding.toShiftedPositiveInt(maximum[y][x]));
                for (int frameIndex = 0; frameIndex < frames.size(); frameIndex++) {
                    assertEquals(sourcePixel(frameIndex, x, y), frames.get(frameIndex).pixelData[y][x]);
                }
            }
        }
    }

    /** One input frame still produces independent output rows, including all-zero intensity pixels. */
    @Test
    public void singleFrameProducesAnIndependentCopy() {
        short[][] pixels = {{Short.MIN_VALUE, -1, 0, Short.MAX_VALUE}, {3, 2, 1, Short.MIN_VALUE}};
        short[][] maximum = MasterMapGenerator.createMaximumMasterStack(
                List.of(new ImageFrame(0, "single", pixels, -1, -1)));

        assertNotSame(pixels, maximum);
        for (int y = 0; y < pixels.length; y++) {
            assertNotSame(pixels[y], maximum[y]);
            assertArrayEquals(pixels[y], maximum[y]);
        }
        maximum[0][0] = Short.MAX_VALUE;
        assertEquals(Short.MIN_VALUE, pixels[0][0]);
    }

    /** The existing no-input and zero-column results remain unchanged. */
    @Test
    public void preservesEmptyInputResults() {
        assertNull(MasterMapGenerator.createMaximumMasterStack(null));
        assertNull(MasterMapGenerator.createMaximumMasterStack(List.of()));
        short[][] maximum = MasterMapGenerator.createMaximumMasterStack(List.of(
                new ImageFrame(0, "empty", new short[2][0], -1, -1)));
        assertEquals(2, maximum.length);
        assertEquals(0, maximum[0].length);
        assertEquals(0, maximum[1].length);
    }

    /** Rotates which frame holds the maximum, with opposing intensity gradients in the two rows. */
    private static short sourcePixel(int frameIndex, int x, int y) {
        int maximum = y == 0 ? x : 65535 - x;
        int intensity = (x + y) % 3 == frameIndex ? maximum : maximum / 2;
        return PixelEncoding.fromShiftedPositiveInt(intensity);
    }
}
