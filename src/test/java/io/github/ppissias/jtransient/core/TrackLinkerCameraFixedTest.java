package io.github.ppissias.jtransient.core;

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
    private static List<List<SourceExtractor.DetectedObject>> hotPixelFrames(Map<Integer, FrameRegistration> registrations) {
        List<List<SourceExtractor.DetectedObject>> frames = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            registrations.put(i, FrameRegistration.of(i, 6 * i, 0, 0));
            List<SourceExtractor.DetectedObject> frame = new ArrayList<>();
            SourceExtractor.DetectedObject obj = new SourceExtractor.DetectedObject(100 + 6 * i, 100, 100.0, 4);
            obj.sourceFrameIndex = i;
            obj.timestamp = -1L;
            obj.exposureDuration = -1L;
            obj.peakSigma = 7.0;
            obj.integratedSigma = 7.0;
            obj.pixelArea = 12.0;
            obj.fwhm = 2.0;
            obj.elongation = 1.1;
            obj.rawPixels = new ArrayList<>();
            obj.rawPixels.add(new SourceExtractor.Pixel(100 + 6 * i, 100, 200));
            frame.add(obj);
            frames.add(frame);
        }
        return frames;
    }

    @Test
    public void aHotPixelMovingWithTheDriftIsNotTracked() {
        Map<Integer, FrameRegistration> registrations = new HashMap<>();
        List<List<SourceExtractor.DetectedObject>> frames = hotPixelFrames(registrations);

        TrackLinker.TrackingResult result = TrackLinker.findMovingObjects(
                frames, new ArrayList<>(), new DetectionConfig(), null, 256, 256, registrations);

        assertEquals(0, result.tracks.size());
        assertEquals(1, result.cameraFixedGroups.size());
        assertEquals(6, result.cameraFixedGroups.get(0).points.size());
        assertEquals(1, result.telemetry.cameraFixedGroups);
        assertEquals(6, result.telemetry.cameraFixedDetections);
        for (List<SourceExtractor.DetectedObject> frame : result.allTransients) {
            assertEquals(0, frame.size());
        }
    }

    @Test
    public void withTheSwitchOffTheHotPixelBecomesATrack() {
        Map<Integer, FrameRegistration> registrations = new HashMap<>();
        List<List<SourceExtractor.DetectedObject>> frames = hotPixelFrames(registrations);
        DetectionConfig config = new DetectionConfig();
        config.enableCameraFixedRejection = false;

        TrackLinker.TrackingResult result = TrackLinker.findMovingObjects(
                frames, new ArrayList<>(), config, null, 256, 256, registrations);

        assertEquals(1, result.tracks.size());
        assertFalse(result.tracks.get(0).isStreakTrack);
        assertEquals(0, result.cameraFixedGroups.size());
    }
}
