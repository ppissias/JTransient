package io.github.ppissias.jtransient.core;

import io.github.ppissias.jtransient.model.DetectedObject;
import io.github.ppissias.jtransient.model.Pixel;
import io.github.ppissias.jtransient.config.DetectionConfig;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class TrackLinkerCameraFixedTest {

    /** A hot pixel at camera (100, 100) in six frames that drifted 6 px a frame: a straight line on the frames. */
    private static List<List<DetectedObject>> hotPixelFrames(Map<Integer, FrameRegistration> registrations) {
        List<List<DetectedObject>> frames = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            registrations.put(i, FrameRegistration.of(i, 6 * i, 0, 0));
            frames.add(new ArrayList<>(List.of(point(100 + 6 * i, 100, i))));
        }
        return frames;
    }

    private static DetectedObject point(double x, double y, int frame) {
        DetectedObject obj = new DetectedObject(x, y, 100.0, 4);
        obj.sourceFrameIndex = frame;
        obj.timestamp = -1L;
        obj.exposureDuration = -1L;
        obj.peakSigma = 7.0;
        obj.integratedSigma = 7.0;
        obj.pixelArea = 12.0;
        obj.fwhm = 2.0;
        obj.elongation = 1.1;
        obj.rawPixels = new ArrayList<>();
        obj.rawPixels.add(new Pixel((int) Math.round(x), (int) Math.round(y), 200));
        return obj;
    }

    @Test
    public void aHotPixelMovingWithTheDriftIsNotTracked() {
        Map<Integer, FrameRegistration> registrations = new HashMap<>();
        List<List<DetectedObject>> frames = hotPixelFrames(registrations);

        TrackLinker.TrackingResult result = TrackLinker.findMovingObjects(
                frames, new ArrayList<>(), new DetectionConfig(), null, 256, 256, registrations);

        assertEquals(0, result.tracks.size());
        assertEquals(1, result.cameraFixedGroups.size());
        assertEquals(6, result.cameraFixedGroups.get(0).points.size());
        assertEquals(1, result.telemetry.cameraFixedGroups);
        assertEquals(6, result.telemetry.cameraFixedDetections);
        for (List<DetectedObject> frame : result.allTransients) {
            assertEquals(0, frame.size());
        }
    }

    @Test
    public void aSlowMoverOnDitheredFramesIsLeftToLinking() {
        Map<Integer, FrameRegistration> registrations = new HashMap<>();
        double[] dither = {0, 0.6, -0.5, 0.5, -0.6, 0.2};
        List<List<DetectedObject>> frames = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            registrations.put(i, FrameRegistration.of(i, 0.8 * i + dither[i], 0, 0));
            frames.add(new ArrayList<>(List.of(point(100 + 1.0 * i, 100, i))));
        }

        TrackLinker.TrackingResult result = TrackLinker.findMovingObjects(
                frames, new ArrayList<>(), new DetectionConfig(), null, 256, 256, registrations);

        assertEquals(0, result.cameraFixedGroups.size());
        assertEquals(0, result.telemetry.cameraFixedGroups);
        assertEquals(1, result.telemetry.cameraFixedGroupsFollowingSky);
        assertEquals(6, result.telemetry.cameraFixedDetectionsFollowingSky);
    }

    @Test
    public void withTheSwitchOffTheHotPixelBecomesATrack() {
        Map<Integer, FrameRegistration> registrations = new HashMap<>();
        List<List<DetectedObject>> frames = hotPixelFrames(registrations);
        DetectionConfig config = new DetectionConfig();
        config.enableCameraFixedRejection = false;

        TrackLinker.TrackingResult result = TrackLinker.findMovingObjects(
                frames, new ArrayList<>(), config, null, 256, 256, registrations);

        assertEquals(1, result.tracks.size());
        assertFalse(result.tracks.get(0).isStreakTrack);
        assertEquals(0, result.cameraFixedGroups.size());
    }
}
