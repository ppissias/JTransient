package io.github.ppissias.jtransient.core;

import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CameraFixedRejectorTest {

    private static SourceExtractor.DetectedObject point(double x, double y, int frame) {
        SourceExtractor.DetectedObject p = new SourceExtractor.DetectedObject(x, y, 8, 4);
        p.pixelArea = 4;
        p.sourceFrameIndex = frame;
        return p;
    }

    /** Six frames dithered by (7, -5) px each and turned slightly. */
    private static Map<Integer, FrameRegistration> dithered() {
        Map<Integer, FrameRegistration> registrations = new HashMap<>();
        for (int i = 0; i < 6; i++) {
            registrations.put(i, FrameRegistration.of(i, 7 * i, -5 * i, 0.001 * i));
        }
        return registrations;
    }

    private static double[] onFrame(FrameRegistration r, double u, double v) {
        double c = Math.cos(r.theta);
        double s = Math.sin(r.theta);
        return new double[]{r.tx + u * c - v * s, r.ty + u * s + v * c};
    }

    private static List<List<SourceExtractor.DetectedObject>> frames(int n) {
        List<List<SourceExtractor.DetectedObject>> frames = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            frames.add(new ArrayList<>());
        }
        return frames;
    }

    @Test
    public void aHotPixelIsRemovedAndSkyObjectsAreKept() {
        Map<Integer, FrameRegistration> registrations = dithered();
        List<List<SourceExtractor.DetectedObject>> frames = frames(6);
        List<SourceExtractor.DetectedObject> hot = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            double[] p = onFrame(registrations.get(i), 1500, 900);
            SourceExtractor.DetectedObject h = point(p[0] + 0.3 * (i % 2), p[1] - 0.2 * (i % 3), i);
            hot.add(h);
            frames.get(i).add(h);
            frames.get(i).add(point(300, 300, i));                  // fixed on the sky (a flare)
            frames.get(i).add(point(400 + 3 * i, 100 + 2 * i, i));  // a mover
        }

        List<CameraFixedRejector.Group> groups = CameraFixedRejector.reject(frames, registrations);

        assertEquals(1, groups.size());
        assertEquals(hot, groups.get(0).points);
        assertEquals(1500, groups.get(0).cameraX, 1.0);
        assertEquals(900, groups.get(0).cameraY, 1.0);
        for (List<SourceExtractor.DetectedObject> frame : frames) {
            assertEquals(2, frame.size());
            for (SourceExtractor.DetectedObject d : frame) {
                assertFalse(hot.contains(d));
            }
        }
    }

    @Test
    public void framesThatDidNotMoveCannotTellACameraPositionFromTheSky() {
        Map<Integer, FrameRegistration> registrations = new HashMap<>();
        List<List<SourceExtractor.DetectedObject>> frames = frames(5);
        for (int i = 0; i < 5; i++) {
            registrations.put(i, FrameRegistration.of(i, 1, 1, 0));
            frames.get(i).add(point(201, 201, i));
        }
        assertTrue(CameraFixedRejector.reject(frames, registrations).isEmpty());
        assertEquals(1, frames.get(0).size());
    }

    @Test
    public void twoFramesAreNotEnough() {
        Map<Integer, FrameRegistration> registrations = dithered();
        List<List<SourceExtractor.DetectedObject>> frames = frames(6);
        for (int i : new int[]{1, 4}) {
            double[] p = onFrame(registrations.get(i), 600, 600);
            frames.get(i).add(point(p[0], p[1], i));
        }
        assertTrue(CameraFixedRejector.reject(frames, registrations).isEmpty());
    }

    @Test
    public void framesWithoutAUsableRegistrationAreLeftOut() {
        Map<Integer, FrameRegistration> registrations = dithered();
        List<List<SourceExtractor.DetectedObject>> frames = frames(6);
        for (int i = 0; i < 6; i++) {
            double[] p = onFrame(registrations.get(i), 700, 500);
            frames.get(i).add(point(p[0], p[1], i));
        }
        Map<Integer, FrameRegistration> onlyTwo = new HashMap<>();
        onlyTwo.put(0, registrations.get(0));
        onlyTwo.put(3, registrations.get(3));
        assertTrue(CameraFixedRejector.reject(frames, onlyTwo).isEmpty());
        assertEquals(1, CameraFixedRejector.reject(frames, registrations).size());
    }
}
