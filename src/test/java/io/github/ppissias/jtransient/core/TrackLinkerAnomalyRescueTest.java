package io.github.ppissias.jtransient.core;

import io.github.ppissias.jtransient.config.DetectionConfig;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
        SourceExtractor.DetectedObject obj = createAnomaly(20, 9.0, 6.0);

        assertTrue(TrackLinker.qualifiesForAnomalyRescue(obj, config));
    }

    /**
     * A broader flash can be rescued through integrated sigma as long as it still has a modest peak.
     */
    @Test
    public void qualifiesForAnomalyRescueAcceptsDiffuseIntegratedAnomaly() {
        DetectionConfig config = new DetectionConfig();
        SourceExtractor.DetectedObject obj = createAnomaly(30, 4.0, 14.0);

        assertTrue(TrackLinker.qualifiesForAnomalyRescue(obj, config));
    }

    /**
     * The diffuse rescue path still requires some local prominence, otherwise broad mush is rejected.
     */
    @Test
    public void qualifiesForAnomalyRescueRejectsDiffuseLowPeakBlob() {
        DetectionConfig config = new DetectionConfig();
        SourceExtractor.DetectedObject obj = createAnomaly(30, 2.5, 14.0);

        assertFalse(TrackLinker.qualifiesForAnomalyRescue(obj, config));
    }

    /**
     * The diffuse rescue path now requires a larger footprint than the sharp-peak path.
     */
    @Test
    public void qualifiesForAnomalyRescueRejectsIntegratedAnomalyBelowIntegratedPixelFloor() {
        DetectionConfig config = new DetectionConfig();
        SourceExtractor.DetectedObject obj = createAnomaly(20, 4.0, 14.0);

        assertFalse(TrackLinker.qualifiesForAnomalyRescue(obj, config));
    }

    /**
     * Size remains a hard floor for anomaly rescue.
     */
    @Test
    public void qualifiesForAnomalyRescueRejectsTooSmallObject() {
        DetectionConfig config = new DetectionConfig();
        SourceExtractor.DetectedObject obj = createAnomaly(8, 9.0, 20.0);

        assertFalse(TrackLinker.qualifiesForAnomalyRescue(obj, config));
    }

    /**
     * The rescue classifier should distinguish between the sharp-peak path and the
     * broader integrated-sigma path so the UI can render them separately.
     */
    @Test
    public void classifyAnomalyRescueDistinguishesPeakAndIntegratedPaths() {
        DetectionConfig config = new DetectionConfig();
        SourceExtractor.DetectedObject peakAnomaly = createAnomaly(20, 9.0, 6.0);
        SourceExtractor.DetectedObject integratedAnomaly = createAnomaly(30, 4.0, 14.0);
        SourceExtractor.DetectedObject rejectedBlob = createAnomaly(30, 2.5, 14.0);

        assertEquals(TrackLinker.AnomalyType.PEAK_SIGMA, TrackLinker.classifyAnomalyRescue(peakAnomaly, config));
        assertEquals(TrackLinker.AnomalyType.INTEGRATED_SIGMA, TrackLinker.classifyAnomalyRescue(integratedAnomaly, config));
        assertNull(TrackLinker.classifyAnomalyRescue(rejectedBlob, config));
    }

    /**
     * Rescued anomalies should now be returned separately from true motion tracks.
     */
    @Test
    public void findMovingObjectsReturnsAnomaliesOutsideTrackList() {
        DetectionConfig config = new DetectionConfig();
        List<List<SourceExtractor.DetectedObject>> frames = new ArrayList<>();
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
        assertEquals(0, result.suspectedThresholdStreakTracks.size());
        assertEquals(TrackLinker.AnomalyType.PEAK_SIGMA, result.anomalies.get(0).type);
        assertSame(frames.get(0).get(0), result.anomalies.get(0).object);
        assertEquals(1, result.telemetry.anomaliesFound);
        assertEquals(0, result.telemetry.integratedSigmaAnomaliesFound);
        assertEquals(0, result.telemetry.suspectedThresholdStreakTracksFound);
    }

    /**
     * Collinear integrated anomalies in one frame should be promoted into a separate suspected streak bucket.
     */
    @Test
    public void findMovingObjectsPromotesAlignedIntegratedAnomaliesToSuspectedThresholdStreakTrack() {
        DetectionConfig config = new DetectionConfig();
        List<List<SourceExtractor.DetectedObject>> frames = new ArrayList<>();
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
        assertEquals(0, result.anomalies.size());
        assertEquals(1, result.suspectedThresholdStreakTracks.size());
        assertTrue(result.suspectedThresholdStreakTracks.get(0).isSuspectedThresholdStreakTrack);
        assertEquals(3, result.suspectedThresholdStreakTracks.get(0).points.size());
        assertSame(frames.get(0).get(0), result.suspectedThresholdStreakTracks.get(0).points.get(0));
        assertSame(frames.get(0).get(1), result.suspectedThresholdStreakTracks.get(0).points.get(1));
        assertSame(frames.get(0).get(2), result.suspectedThresholdStreakTracks.get(0).points.get(2));
        assertEquals(0, result.telemetry.anomaliesFound);
        assertEquals(0, result.telemetry.integratedSigmaAnomaliesFound);
        assertEquals(1, result.telemetry.suspectedThresholdStreakTracksFound);
    }

    /**
     * Suspected threshold streak grouping uses its own angle tolerance because weak integrated anomalies
     * can have noisier moment-derived angles than the main tracker allows.
     */
    @Test
    public void findMovingObjectsUsesDedicatedAngleToleranceForSuspectedThresholdStreaks() {
        DetectionConfig config = new DetectionConfig();
        config.angleToleranceDegrees = 2.0;
        config.anomalySuspectedStreakAngleToleranceDegrees = 6.0;

        List<List<SourceExtractor.DetectedObject>> frames = new ArrayList<>();
        frames.add(List.of(
                createFrameAnomaly(10, 10, 30, 4.0, 14.0, (short) 120, 0, 4.2, Math.toRadians(4.0)),
                createFrameAnomaly(22, 10, 30, 4.3, 15.0, (short) 120, 0, 4.1, Math.toRadians(4.0)),
                createFrameAnomaly(34, 10, 30, 4.1, 13.8, (short) 120, 0, 4.0, Math.toRadians(4.0))
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

        assertEquals(1, result.suspectedThresholdStreakTracks.size());
        assertEquals(0, result.anomalies.size());
    }

    /**
     * Integrated anomalies that stay isolated should remain in the anomaly bucket.
     */
    @Test
    public void findMovingObjectsKeepsIsolatedIntegratedAnomalyAsStandaloneAnomaly() {
        DetectionConfig config = new DetectionConfig();
        SourceExtractor.DetectedObject integratedAnomaly = createFrameAnomaly(10, 10, 30, 4.0, 14.0, (short) 120, 0, 4.2, 0.0);
        List<List<SourceExtractor.DetectedObject>> frames = new ArrayList<>();
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
        assertEquals(TrackLinker.AnomalyType.INTEGRATED_SIGMA, result.anomalies.get(0).type);
        assertSame(integratedAnomaly, result.anomalies.get(0).object);
        assertTrue(result.suspectedThresholdStreakTracks.isEmpty());
        assertEquals(1, result.telemetry.anomaliesFound);
        assertEquals(1, result.telemetry.integratedSigmaAnomaliesFound);
        assertEquals(0, result.telemetry.suspectedThresholdStreakTracksFound);
    }

    private static SourceExtractor.DetectedObject createAnomaly(int pixelArea, double peakSigma, double integratedSigma) {
        SourceExtractor.DetectedObject obj = new SourceExtractor.DetectedObject(0.0, 0.0, 0.0, pixelArea);
        obj.pixelArea = pixelArea;
        obj.peakSigma = peakSigma;
        obj.integratedSigma = integratedSigma;
        obj.elongation = 1.0;
        obj.angle = 0.0;
        obj.rawPixels = new ArrayList<>();
        return obj;
    }

    private static SourceExtractor.DetectedObject createFrameAnomaly(
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

    private static SourceExtractor.DetectedObject createFrameAnomaly(
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
        SourceExtractor.DetectedObject obj = createAnomaly(pixelArea, peakSigma, integratedSigma);
        obj.x = x;
        obj.y = y;
        obj.elongation = elongation;
        obj.angle = angle;
        obj.sourceFrameIndex = frameIndex;
        obj.rawPixels = new ArrayList<>();
        for (int i = 0; i < pixelArea; i++) {
            obj.rawPixels.add(new SourceExtractor.Pixel(x + i, y, signal));
        }
        return obj;
    }
}
