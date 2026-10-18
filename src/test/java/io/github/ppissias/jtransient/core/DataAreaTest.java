package io.github.ppissias.jtransient.core;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The session data area: where every frame holds image data, 3 px in from the registration fill and the image edge.
 */
public class DataAreaTest {
    private static final short FILL = Short.MIN_VALUE;
    private static final short SKY = (short) (1000 + Short.MIN_VALUE);

    @Test
    public void singleFrameAreaStartsThreePixelsInsideTheFillAndTheImageEdge() {
        short[][] frame = frame(60, 40, 10, 0, 5, 0);
        DataArea area = DataArea.of(List.of(FrameCoverage.measure(0, frame)));
        assertFalse(area.contains(12, 20));
        assertTrue(area.contains(13, 20));
        assertTrue(area.contains(56, 20));
        assertFalse(area.contains(57, 20));
        assertFalse(area.contains(30, 7));
        assertTrue(area.contains(30, 8));
        assertTrue(area.contains(30, 36));
        assertFalse(area.contains(30, 37));
        assertEquals((56 - 13 + 1) * (36 - 8 + 1), area.getPixelCount());
    }

    @Test
    public void strayPixelsInTheFillAndColdPixelsInTheImageAreIgnored() {
        short[][] frame = frame(60, 40, 10, 0, 0, 0);
        frame[20][2] = SKY;
        frame[20][5] = SKY;
        frame[20][6] = SKY;
        frame[21][15] = FILL;
        frame[22][30] = FILL;
        frame[22][31] = FILL;
        DataArea area = DataArea.of(List.of(FrameCoverage.measure(0, frame)));
        assertEquals(13, area.getRowStart(20));
        assertTrue(area.contains(15, 21));
        assertTrue(area.contains(30, 22));
    }

    @Test
    public void areaIsTheIntersectionOfShiftedFrames() {
        FrameCoverage left = FrameCoverage.measure(0, frame(60, 40, 0, 8, 0, 0));
        FrameCoverage right = FrameCoverage.measure(1, frame(60, 40, 6, 0, 0, 4));
        DataArea area = DataArea.of(List.of(left, right));
        assertEquals(6 + 3, area.getRowStart(20));
        assertEquals(60 - 1 - 8 - 3, area.getRowEnd(20));
        assertFalse(area.contains(30, 40 - 1 - 4 - 3 + 1));
        assertTrue(area.contains(30, 40 - 1 - 4 - 3));
    }

    @Test
    public void rotatedFrameLosesTheFillWedgeInTheCorner() {
        int width = 80;
        int height = 60;
        short[][] frame = new short[height][width];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                // Fill below the line x + y = 20 (a wedge in the top left corner).
                frame[y][x] = x + y < 20 ? FILL : SKY;
            }
        }
        DataArea area = DataArea.of(List.of(FrameCoverage.measure(0, frame)));
        assertFalse(area.contains(10, 10));
        assertFalse(area.contains(20, 1));
        assertFalse(area.contains(21, 4));
        assertTrue(area.contains(22, 4));
        assertTrue(area.contains(40, 30));
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (area.contains(x, y)) {
                    // Every pixel of the area is at least 3 px (square distance) from the fill.
                    assertTrue("pixel " + x + "," + y, (x - 3) + (y - 3) >= 20);
                }
            }
        }
    }

    @Test
    public void framesWithoutFillGiveTheWholeFrameLessTheMargin() {
        DataArea area = DataArea.of(List.of(FrameCoverage.measure(0, frame(50, 30, 0, 0, 0, 0))));
        DataArea full = DataArea.fullFrame(50, 30);
        assertEquals((50 - 6) * (30 - 6), area.getPixelCount());
        assertEquals(area.getPixelCount(), full.getPixelCount());
        assertEquals(4, area.outline().size());
        assertTrue(Arrays.equals(new double[]{3, 3}, area.outline().get(0)));
    }

    @Test
    public void limitingFramesReportsWhatLeavingOutAFrameWouldGain() {
        FrameCoverage a = FrameCoverage.measure(0, frame(60, 40, 2, 0, 0, 0));
        FrameCoverage b = FrameCoverage.measure(1, frame(60, 40, 0, 2, 0, 0));
        FrameCoverage shifted = FrameCoverage.measure(2, frame(60, 40, 12, 0, 0, 0));
        List<DataArea.FrameLimit> limits = DataArea.limitingFrames(List.of(a, b, shifted));
        assertEquals(2, limits.get(0).sequenceIndex);
        assertEquals(10L * (40 - 6), limits.get(0).gainedPixels);
        assertEquals(10.0 * (40 - 6) / (60 * 40), limits.get(0).gainedFraction, 1e-12);
        assertEquals(1, limits.get(1).sequenceIndex);
        assertEquals(2L * (40 - 6), limits.get(1).gainedPixels);
        assertEquals(2, limits.size());
    }

    @Test
    public void framesSharingTheTightestEdgeDoNotLimitAlone() {
        FrameCoverage a = FrameCoverage.measure(0, frame(60, 40, 7, 0, 0, 0));
        FrameCoverage b = FrameCoverage.measure(1, frame(60, 40, 7, 0, 0, 0));
        FrameCoverage c = FrameCoverage.measure(2, frame(60, 40, 0, 0, 0, 0));
        assertTrue(DataArea.limitingFrames(List.of(a, b, c)).isEmpty());
    }

    /** A sky frame with fill bands of the given widths on the left, right, top and bottom. */
    private static short[][] frame(int width, int height, int left, int right, int top, int bottom) {
        short[][] frame = new short[height][width];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                boolean fill = x < left || x >= width - right || y < top || y >= height - bottom;
                frame[y][x] = fill ? FILL : (short) (SKY + ((x * 7 + y * 13) % 5));
            }
        }
        return frame;
    }
}
