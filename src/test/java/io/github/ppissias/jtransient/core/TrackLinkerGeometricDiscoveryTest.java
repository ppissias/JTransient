package io.github.ppissias.jtransient.core;

import io.github.ppissias.jtransient.config.DetectionConfig;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

/**
 * Regression tests for the optional geometric point-track linker.
 */
public class TrackLinkerGeometricDiscoveryTest {

    /**
     * When timestamps exist, geometric point-track discovery can be disabled so only the
     * time-based linker is allowed to accept point tracks.
     */
    @Test
    public void findMovingObjectsCanDisableGeometricPointTrackDiscoveryWhenTimestampsExist() {
        DetectionConfig config = new DetectionConfig();
        config.enableGeometricTrackLinking = false;

        List<List<SourceExtractor.DetectedObject>> frames = List.of(
                List.of(createPoint(10.0, 10.0, 0, 0L, 500L)),
                List.of(createPoint(20.0, 10.0, 1, 1000L, 500L)),
                List.of(createPoint(30.0, 10.0, 2, 5000L, 500L))
        );

        TrackLinker.TrackingResult result = TrackLinker.findMovingObjects(
                frames,
                new ArrayList<>(),
                config,
                null,
                64,
                64
        );

        assertEquals(0, result.tracks.size());
        assertEquals(0, result.telemetry.pointTracksFound);
    }

    /**
     * If timestamps are absent, the geometric linker must still run even when explicitly disabled,
     * otherwise the engine would have no point-track discovery path.
     */
    @Test
    public void findMovingObjectsForcesGeometricPointTrackDiscoveryWhenTimestampsAreMissing() {
        DetectionConfig config = new DetectionConfig();
        config.enableGeometricTrackLinking = false;

        List<List<SourceExtractor.DetectedObject>> frames = List.of(
                List.of(createPoint(10.0, 10.0, 0, -1L, -1L)),
                List.of(createPoint(20.0, 10.0, 1, -1L, -1L)),
                List.of(createPoint(30.0, 10.0, 2, -1L, -1L))
        );

        TrackLinker.TrackingResult result = TrackLinker.findMovingObjects(
                frames,
                new ArrayList<>(),
                config,
                null,
                64,
                64
        );

        assertEquals(1, result.tracks.size());
        assertFalse(result.tracks.get(0).isTimeBasedTrack);
        assertEquals(1, result.telemetry.pointTracksFound);
    }

    /**
     * Strict exposure kinematics still constrains the time-based linker, but it no longer
     * blocks the geometric linker when timestamps are present and geometric discovery is enabled.
     */
    @Test
    public void findMovingObjectsDoesNotApplyStrictExposureKinematicsToGeometricLinking() {
        DetectionConfig config = new DetectionConfig();
        config.strictExposureKinematics = true;
        config.enableGeometricTrackLinking = true;

        List<List<SourceExtractor.DetectedObject>> frames = List.of(
                List.of(createPoint(10.0, 10.0, 0, 0L, 1000L)),
                List.of(createPoint(20.0, 10.0, 1, 1000L, 1000L)),
                List.of(createPoint(30.0, 10.0, 2, 2000L, 1000L))
        );

        TrackLinker.TrackingResult result = TrackLinker.findMovingObjects(
                frames,
                new ArrayList<>(),
                config,
                null,
                64,
                64
        );

        assertEquals(1, result.tracks.size());
        assertFalse(result.tracks.get(0).isTimeBasedTrack);
        assertEquals(1, result.telemetry.pointTracksFound);
    }

    private static SourceExtractor.DetectedObject createPoint(
            double x,
            double y,
            int frameIndex,
            long timestamp,
            long exposureDuration
    ) {
        SourceExtractor.DetectedObject obj = new SourceExtractor.DetectedObject(x, y, 100.0, 1);
        obj.sourceFrameIndex = frameIndex;
        obj.timestamp = timestamp;
        obj.exposureDuration = exposureDuration;
        obj.pixelArea = 1.0;
        obj.totalFlux = 100.0;
        obj.fwhm = 1.0;
        obj.rawPixels = new ArrayList<>();
        obj.rawPixels.add(new SourceExtractor.Pixel((int) Math.round(x), (int) Math.round(y), (short) 100));
        return obj;
    }
}
