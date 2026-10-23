package io.github.ppissias.jtransient.core;

import io.github.ppissias.jtransient.model.DetectedObject;
import io.github.ppissias.jtransient.model.Pixel;
import io.github.ppissias.jtransient.config.DetectionConfig;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

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

        List<List<DetectedObject>> frames = List.of(
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

        List<List<DetectedObject>> frames = List.of(
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

        List<List<DetectedObject>> frames = List.of(
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

    /**
     * Time-based candidate rejection reasons should populate the same telemetry buckets instead of
     * silently disappearing when timestamps are present and geometric linking is disabled.
     */
    @Test
    public void findMovingObjectsPopulatesTimeBasedRejectionTelemetry() {
        DetectionConfig config = new DetectionConfig();
        config.enableGeometricTrackLinking = false;
        config.strictExposureKinematics = false;

        DetectedObject baselineNonPositiveDelta = createPoint(14.0, 10.0, 1, 500L, 500L);
        DetectedObject baselineJitter = createPoint(10.5, 10.0, 1, 1500L, 500L);
        DetectedObject baselineSizeMismatch = createPoint(16.0, 10.0, 1, 1700L, 500L);
        baselineSizeMismatch.fwhm = 10.0;
        DetectedObject validBaseline = createPoint(20.0, 10.0, 1, 2000L, 500L);

        List<List<DetectedObject>> frames = List.of(
                List.of(createPoint(10.0, 10.0, 0, 1000L, 500L)),
                List.of(baselineNonPositiveDelta, baselineJitter, baselineSizeMismatch, validBaseline),
                List.of(
                        createPoint(25.0, 10.0, 2, 1500L, 500L),
                        createPoint(60.0, 10.0, 2, 3000L, 500L)
                )
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
        assertEquals(1, result.telemetry.countBaselineNonPositiveDelta);
        assertEquals(1, result.telemetry.countBaselineJitter);
        assertEquals(1, result.telemetry.countBaselineSize);
        assertEquals(1, result.telemetry.countP3NonPositiveDelta);
        assertEquals(1, result.telemetry.countP3VelocityMismatch);
        assertEquals(1, result.telemetry.countTrackTooShort);
    }

    /** Demonstrates that pruning can enlarge a retained step beyond the raw linking cap without invalidating rhythm. */
    @Test
    public void pruningCanProduceSteadyStepsAboveTheRawGeometricJumpCap() {
        DetectionConfig config = new DetectionConfig();
        config.maxJumpPixels = 1;
        config.maxStarJitter = 0.6;
        config.rhythmStationaryThreshold = 1.2;
        config.rhythmAllowedVariance = 0.8;
        List<List<DetectedObject>> frames = List.of(
                List.of(createPoint(10, 10, 0, -1, -1)),
                List.of(createPoint(10.8, 10, 1, -1, -1)),
                List.of(createPoint(11.3, 10, 2, -1, -1)),
                List.of(createPoint(12.3, 10, 3, -1, -1)));
        var result = TrackLinker.findMovingObjects(frames, new ArrayList<>(), config, null, 64, 64);
        assertEquals(1, result.tracks.size());
        var track = result.tracks.getFirst();
        assertEquals(List.of(0, 1, 3), track.points.stream().map(point -> point.sourceFrameIndex).toList());
        assertTrue(TrackGeometry.hasSteadyRhythm(track, config.rhythmAllowedVariance,
                config.rhythmStationaryThreshold, config.rhythmMinConsistencyRatio));
        assertEquals(1.5, track.points.get(2).x - track.points.get(1).x, 1e-9);
        assertEquals(1.2, config.rhythmStationaryThreshold, 0);
    }

    private static DetectedObject createPoint(
            double x,
            double y,
            int frameIndex,
            long timestamp,
            long exposureDuration
    ) {
        DetectedObject obj = new DetectedObject(x, y, 100.0, 1);
        obj.sourceFrameIndex = frameIndex;
        obj.timestamp = timestamp;
        obj.exposureDuration = exposureDuration;
        obj.pixelArea = 1.0;
        obj.totalFlux = 100.0;
        obj.fwhm = 1.0;
        obj.rawPixels = new ArrayList<>();
        obj.rawPixels.add(new Pixel((int) Math.round(x), (int) Math.round(y), (short) 100));
        return obj;
    }
}
