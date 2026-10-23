package io.github.ppissias.jtransient.core;

import io.github.ppissias.jtransient.model.AnomalyType;
import io.github.ppissias.jtransient.model.DetectedObject;
import io.github.ppissias.jtransient.model.Pixel;
import io.github.ppissias.jtransient.model.Track;
import io.github.ppissias.jtransient.config.DetectionConfig;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Regression tests for the high-energy anomaly rescue gate.
 */
public class TrackLinkerAnomalyRescueTest {

    /**
     * A sharp flash should still be rescued purely by its peak sigma.
     */
    @Test
    public void qualifiesForAnomalyRescueAcceptsSharpPeakAnomaly() {
        DetectionConfig config = new DetectionConfig();
        DetectedObject obj = createAnomaly(20, 9.0, 6.0);

        assertTrue(TrackLinker.qualifiesForAnomalyRescue(obj, config));
    }

    /**
     * A broader flash can be rescued through integrated sigma as long as it still has a modest peak.
     */
    @Test
    public void qualifiesForAnomalyRescueAcceptsDiffuseIntegratedAnomaly() {
        DetectionConfig config = new DetectionConfig();
        DetectedObject obj = createAnomaly(30, 4.0, 14.0);

        assertTrue(TrackLinker.qualifiesForAnomalyRescue(obj, config));
    }

    /**
     * The diffuse rescue path still requires some local prominence, otherwise broad mush is rejected.
     */
    @Test
    public void qualifiesForAnomalyRescueRejectsDiffuseLowPeakBlob() {
        DetectionConfig config = new DetectionConfig();
        DetectedObject obj = createAnomaly(30, 2.5, 14.0);

        assertFalse(TrackLinker.qualifiesForAnomalyRescue(obj, config));
    }

    /**
     * The diffuse rescue path now requires a larger footprint than the sharp-peak path.
     */
    @Test
    public void qualifiesForAnomalyRescueRejectsIntegratedAnomalyBelowIntegratedPixelFloor() {
        DetectionConfig config = new DetectionConfig();
        DetectedObject obj = createAnomaly(20, 4.0, 14.0);

        assertFalse(TrackLinker.qualifiesForAnomalyRescue(obj, config));
    }

    /**
     * Size remains a hard floor for anomaly rescue.
     */
    @Test
    public void qualifiesForAnomalyRescueRejectsTooSmallObject() {
        DetectionConfig config = new DetectionConfig();
        DetectedObject obj = createAnomaly(8, 9.0, 20.0);

        assertFalse(TrackLinker.qualifiesForAnomalyRescue(obj, config));
    }

    /**
     * The rescue classifier should distinguish between the sharp-peak path and the
     * broader integrated-sigma path so the UI can render them separately.
     */
    @Test
    public void classifyAnomalyRescueDistinguishesPeakAndIntegratedPaths() {
        DetectionConfig config = new DetectionConfig();
        DetectedObject peakAnomaly = createAnomaly(20, 9.0, 6.0);
        DetectedObject integratedAnomaly = createAnomaly(30, 4.0, 14.0);
        DetectedObject rejectedBlob = createAnomaly(30, 2.5, 14.0);

        assertEquals(AnomalyType.PEAK_SIGMA, TrackLinker.classifyAnomalyRescue(peakAnomaly, config));
        assertEquals(AnomalyType.INTEGRATED_SIGMA, TrackLinker.classifyAnomalyRescue(integratedAnomaly, config));
        assertNull(TrackLinker.classifyAnomalyRescue(rejectedBlob, config));
    }

    /**
     * Rescued anomalies should now be returned separately from true motion tracks.
     */
    @Test
    public void findMovingObjectsReturnsAnomaliesOutsideTrackList() {
        DetectionConfig config = new DetectionConfig();
        List<List<DetectedObject>> frames = new ArrayList<>();
        frames.add(List.of(createFrameAnomaly(10, 10, 20, 9.0, 6.0, (short) 120, 0)));
        frames.add(new ArrayList<>());
        frames.add(new ArrayList<>());

        TrackLinker.TrackingResult result = TrackLinker.findMovingObjects(
                frames,
                new ArrayList<>(),
                config,
                null,
                64,
                64
        );

        assertEquals(0, result.tracks.size());
        assertEquals(1, result.anomalies.size());
        assertEquals(AnomalyType.PEAK_SIGMA, result.anomalies.get(0).type);
        assertSame(frames.get(0).get(0), result.anomalies.get(0).object);
        assertEquals(1, result.telemetry.anomaliesFound);
        assertEquals(0, result.telemetry.integratedSigmaAnomaliesFound);
    }

    /**
     * Phase 5 should never even consider detections that were already consumed by accepted tracks.
     */
    @Test
    public void buildPhase5AnomalyCandidatesExcludesObjectsAlreadyConsumedByTracks() {
        DetectedObject trackedStreak = createFrameStreak(10, 10, 20, 9.0, 6.0, (short) 120, 0, 4.2, 0.0);
        DetectedObject trackedPoint = createFrameAnomaly(20, 20, 20, 9.0, 6.0, (short) 120, 0);
        DetectedObject leftover = createFrameAnomaly(30, 30, 20, 9.0, 6.0, (short) 120, 0);

        Track streakTrack = new Track();
        streakTrack.isStreakTrack = true;
        streakTrack.addPoint(trackedStreak);

        Track pointTrack = new Track();
        pointTrack.addPoint(trackedPoint);

        List<List<DetectedObject>> remainingTransients = new ArrayList<>();
        remainingTransients.add(List.of(trackedStreak, trackedPoint, leftover));

        List<List<DetectedObject>> result = TrackLinker.buildPhase5AnomalyCandidates(
                remainingTransients,
                List.of(streakTrack),
                List.of(pointTrack)
        );

        assertEquals(1, result.size());
        assertEquals(1, result.get(0).size());
        assertSame(leftover, result.get(0).get(0));
    }

    /**
     * Preserved standalone streaks should also participate in anomaly rescue once they survive the streak phase
     * without being promoted to one-point streak tracks.
     */
    @Test
    public void findMovingObjectsRescuesPreservedStandaloneSingleStreakAsAnomaly() {
        DetectionConfig config = new DetectionConfig();
        config.singleStreakMinPeakSigma = 10.0;

        DetectedObject preservedStreak = createFrameStreak(10, 10, 20, 9.0, 6.0, (short) 120, 0, 4.2, 0.0);
        List<List<DetectedObject>> frames = new ArrayList<>();
        frames.add(List.of(preservedStreak));
        frames.add(new ArrayList<>());
        frames.add(new ArrayList<>());

        TrackLinker.TrackingResult result = TrackLinker.findMovingObjects(
                frames,
                new ArrayList<>(),
                config,
                null,
                64,
                64
        );

        assertEquals(0, result.tracks.size());
        assertEquals(1, result.anomalies.size());
        assertEquals(AnomalyType.PEAK_SIGMA, result.anomalies.get(0).type);
        assertSame(preservedStreak, result.anomalies.get(0).object);
    }

    /**
     * Collinear anomalies in one frame stay separate anomalies: they are not grouped into a streak.
     */
    @Test
    public void findMovingObjectsKeepsAlignedSameFrameAnomaliesAsAnomalies() {
        DetectionConfig config = new DetectionConfig();
        List<List<DetectedObject>> frames = new ArrayList<>();
        frames.add(List.of(
                createFrameAnomaly(10, 10, 30, 4.0, 14.0, (short) 120, 0, 4.2, 0.0),
                createFrameAnomaly(22, 10, 30, 4.3, 15.0, (short) 120, 0, 4.4, 0.0),
                createFrameAnomaly(34, 10, 30, 4.1, 13.8, (short) 120, 0, 4.1, 0.0)
        ));
        frames.add(new ArrayList<>());
        frames.add(new ArrayList<>());

        TrackLinker.TrackingResult result = TrackLinker.findMovingObjects(
                frames,
                new ArrayList<>(),
                config,
                null,
                64,
                64
        );

        assertEquals(0, result.tracks.size());
        assertEquals(3, result.anomalies.size());
        assertSame(frames.get(0).get(0), result.anomalies.get(0).object);
        assertSame(frames.get(0).get(1), result.anomalies.get(1).object);
        assertSame(frames.get(0).get(2), result.anomalies.get(2).object);
        assertEquals(3, result.telemetry.anomaliesFound);
        assertEquals(3, result.telemetry.integratedSigmaAnomaliesFound);
    }

    /**
     * Same-frame anomaly grouping must remain frame-local even if rescued anomalies would line up
     * across different frames.
     */
    @Test
    public void findMovingObjectsDoesNotGroupRescuedAnomaliesAcrossFrames() {
        DetectionConfig config = new DetectionConfig();

        DetectedObject frame0a = createFrameAnomaly(10, 10, 30, 4.0, 14.0, (short) 120, 0, 1.3, 0.0);
        DetectedObject frame0b = createFrameAnomaly(22, 10, 30, 4.1, 14.1, (short) 120, 0, 1.2, 0.0);
        DetectedObject frame1 = createFrameAnomaly(34, 10, 30, 4.2, 14.2, (short) 120, 1, 1.1, 0.0);

        List<List<DetectedObject>> frames = new ArrayList<>();
        frames.add(List.of(frame0a, frame0b));
        frames.add(List.of(frame1));
        frames.add(new ArrayList<>());

        TrackLinker.TrackingResult result = TrackLinker.findMovingObjects(
                frames,
                new ArrayList<>(),
                config,
                null,
                64,
                64
        );

        assertEquals(3, result.anomalies.size());
        assertSame(frame0a, result.anomalies.get(0).object);
        assertSame(frame0b, result.anomalies.get(1).object);
        assertSame(frame1, result.anomalies.get(2).object);
    }

    /**
     * Integrated anomalies that stay isolated should remain in the anomaly bucket.
     */
    @Test
    public void findMovingObjectsKeepsIsolatedIntegratedAnomalyAsStandaloneAnomaly() {
        DetectionConfig config = new DetectionConfig();
        DetectedObject integratedAnomaly = createFrameAnomaly(10, 10, 30, 4.0, 14.0, (short) 120, 0, 4.2, 0.0);
        List<List<DetectedObject>> frames = new ArrayList<>();
        frames.add(List.of(integratedAnomaly));
        frames.add(new ArrayList<>());
        frames.add(new ArrayList<>());

        TrackLinker.TrackingResult result = TrackLinker.findMovingObjects(
                frames,
                new ArrayList<>(),
                config,
                null,
                64,
                64
        );

        assertEquals(0, result.tracks.size());
        assertEquals(1, result.anomalies.size());
        assertEquals(AnomalyType.INTEGRATED_SIGMA, result.anomalies.get(0).type);
        assertSame(integratedAnomaly, result.anomalies.get(0).object);
        assertEquals(1, result.telemetry.anomaliesFound);
        assertEquals(1, result.telemetry.integratedSigmaAnomaliesFound);
    }

    private static DetectedObject createAnomaly(int pixelArea, double peakSigma, double integratedSigma) {
        DetectedObject obj = new DetectedObject(0.0, 0.0, 0.0, pixelArea);
        obj.pixelArea = pixelArea;
        obj.peakSigma = peakSigma;
        obj.integratedSigma = integratedSigma;
        obj.elongation = 1.0;
        obj.angle = 0.0;
        obj.rawPixels = new ArrayList<>();
        return obj;
    }

    private static DetectedObject createFrameAnomaly(
            int x,
            int y,
            int pixelArea,
            double peakSigma,
            double integratedSigma,
            short signal,
            int frameIndex
    ) {
        return createFrameAnomaly(x, y, pixelArea, peakSigma, integratedSigma, signal, frameIndex, 1.0, 0.0);
    }

    private static DetectedObject createFrameAnomaly(
            int x,
            int y,
            int pixelArea,
            double peakSigma,
            double integratedSigma,
            short signal,
            int frameIndex,
            double elongation,
            double angle
    ) {
        DetectedObject obj = createAnomaly(pixelArea, peakSigma, integratedSigma);
        obj.x = x;
        obj.y = y;
        obj.elongation = elongation;
        obj.angle = angle;
        obj.sourceFrameIndex = frameIndex;
        obj.rawPixels = new ArrayList<>();
        for (int i = 0; i < pixelArea; i++) {
            obj.rawPixels.add(new Pixel(x + i, y, signal));
        }
        return obj;
    }

    private static DetectedObject createFrameStreak(
            int x,
            int y,
            int pixelArea,
            double peakSigma,
            double integratedSigma,
            short signal,
            int frameIndex,
            double elongation,
            double angle
    ) {
        DetectedObject obj = createFrameAnomaly(
                x,
                y,
                pixelArea,
                peakSigma,
                integratedSigma,
                signal,
                frameIndex,
                elongation,
                angle
        );
        obj.isStreak = true;
        return obj;
    }
}
