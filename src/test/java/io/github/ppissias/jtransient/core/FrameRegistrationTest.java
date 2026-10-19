package io.github.ppissias.jtransient.core;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class FrameRegistrationTest {

    /** A registered frame: the camera's rectangle moved by (tx, ty) and turned by theta, fill elsewhere. */
    private static short[][] registered(int width, int height, double tx, double ty, double theta) {
        short[][] frame = new short[height][width];
        double c = Math.cos(theta);
        double s = Math.sin(theta);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                double dx = x - tx;
                double dy = y - ty;
                double u = c * dx + s * dy;
                double v = -s * dx + c * dy;
                boolean data = u >= -0.5 && u <= width - 0.5 && v >= -0.5 && v <= height - 0.5;
                frame[y][x] = data ? (short) (1000 - 32768) : Short.MIN_VALUE;
            }
        }
        return frame;
    }

    private static FrameRegistration fit(short[][] frame) {
        return FrameRegistration.fit(FrameCoverage.measure(4, frame));
    }

    @Test
    public void aShiftedAndTurnedFrameIsMeasuredFromItsFill() {
        FrameRegistration r = fit(registered(800, 600, 12.3, -7.6, 0.01));
        assertTrue(r.usable);
        assertEquals(4, r.sequenceIndex);
        assertEquals(12.3, r.tx, 0.6);
        assertEquals(-7.6, r.ty, 0.6);
        assertEquals(0.01, r.theta, 0.001);
    }

    @Test
    public void aFrameTurnedByFourDegreesIsMeasured() {
        FrameRegistration r = fit(registered(1200, 800, 60, -50, Math.toRadians(4)));
        assertTrue(r.usable);
        assertEquals(60, r.tx, 0.6);
        assertEquals(-50, r.ty, 0.6);
        assertEquals(Math.toRadians(4), r.theta, 0.001);
    }

    @Test
    public void aFrameWithFillOnOneSideKeepsTheOtherShiftAtZero() {
        FrameRegistration r = fit(registered(800, 600, 25, 0, 0));
        assertTrue(r.usable);
        assertEquals(25, r.tx, 0.6);
        assertEquals(0, r.ty, 0.0);
        assertEquals(0, r.theta, 0.0005);
    }

    @Test
    public void aFrameWithoutFillKeepsTheCameraPosition() {
        FrameRegistration r = fit(registered(400, 300, 0, 0, 0));
        assertTrue(r.usable);
        assertEquals(0, r.tx, 0.0);
        assertEquals(0, r.ty, 0.0);
        assertEquals(0, r.theta, 0.0);
    }

    @Test
    public void fillThatIsNotAMovedRectangleIsNotUsable() {
        short[][] frame = registered(800, 600, 0, 0, 0);
        // A ragged left edge: fill of a different width on every row.
        java.util.Random random = new java.util.Random(3);
        for (int y = 0; y < 600; y++) {
            int fill = 5 + random.nextInt(60);
            for (int x = 0; x < fill; x++) {
                frame[y][x] = Short.MIN_VALUE;
            }
        }
        assertFalse(fit(frame).usable);
    }

    @Test
    public void cameraPositionsInvertTheMove() {
        FrameRegistration r = FrameRegistration.of(0, 10, -4, 0.02);
        double c = Math.cos(0.02);
        double s = Math.sin(0.02);
        double x = 10 + 300 * c - 200 * s;
        double y = -4 + 300 * s + 200 * c;
        double[] camera = r.toCamera(x, y);
        assertEquals(300, camera[0], 1e-9);
        assertEquals(200, camera[1], 1e-9);
    }
}
