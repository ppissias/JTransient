package io.github.ppissias.jtransient.core;

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
        assertTrue(suspectedStreakTracks(result).isEmpty());
        assertEquals(TrackLinker.AnomalyType.PEAK_SIGMA, result.anomalies.get(0).type);
        assertSame(frames.get(0).get(0), result.anomalies.get(0).object);
        assertEquals(1, result.telemetry.anomaliesFound);
        assertEquals(0, result.telemetry.integratedSigmaAnomaliesFound);
        assertEquals(0, result.telemetry.suspectedStreakTracksFound);
    }

    /**
     * Phase 5 should never even consider detections that were already consumed by accepted tracks.
     */
    @Test
    public void buildPhase5AnomalyCandidatesExcludesObjectsAlreadyConsumedByTracks() {
        SourceExtractor.DetectedObject trackedStreak = createFrameStreak(10, 10, 20, 9.0, 6.0, (short) 120, 0, 4.2, 0.0);
        SourceExtractor.DetectedObject trackedPoint = createFrameAnomaly(20, 20, 20, 9.0, 6.0, (short) 120, 0);
        SourceExtractor.DetectedObject leftover = createFrameAnomaly(30, 30, 20, 9.0, 6.0, (short) 120, 0);

        TrackLinker.Track streakTrack = new TrackLinker.Track();
        streakTrack.isStreakTrack = true;
        streakTrack.addPoint(trackedStreak);

        TrackLinker.Track pointTrack = new TrackLinker.Track();
        pointTrack.addPoint(trackedPoint);

        List<List<SourceExtractor.DetectedObject>> remainingTransients = new ArrayList<>();
        remainingTransients.add(List.of(trackedStreak, trackedPoint, leftover));

        List<List<SourceExtractor.DetectedObject>> result = TrackLinker.buildPhase5AnomalyCandidates(
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

        SourceExtractor.DetectedObject preservedStreak = createFrameStreak(10, 10, 20, 9.0, 6.0, (short) 120, 0, 4.2, 0.0);
        List<List<SourceExtractor.DetectedObject>> frames = new ArrayList<>();
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
        assertEquals(TrackLinker.AnomalyType.PEAK_SIGMA, result.anomalies.get(0).type);
        assertSame(preservedStreak, result.anomalies.get(0).object);
        assertTrue(suspectedStreakTracks(result).isEmpty());
    }

    /**
     * Collinear integrated anomalies in one frame should be promoted into a separate suspected streak bucket.
     */
    @Test
    public void findMovingObjectsPromotesAlignedIntegratedAnomaliesToSuspectedStreakTrack() {
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

        assertEquals(1, result.tracks.size());
        assertEquals(0, result.anomalies.size());
        assertEquals(1, suspectedStreakTracks(result).size());
        assertTrue(suspectedStreakTracks(result).get(0).isSuspectedStreakTrack);
        assertEquals(3, suspectedStreakTracks(result).get(0).points.size());
        assertSame(frames.get(0).get(0), suspectedStreakTracks(result).get(0).points.get(0));
        assertSame(frames.get(0).get(1), suspectedStreakTracks(result).get(0).points.get(1));
        assertSame(frames.get(0).get(2), suspectedStreakTracks(result).get(0).points.get(2));
        assertEquals(0, result.telemetry.anomaliesFound);
        assertEquals(0, result.telemetry.integratedSigmaAnomaliesFound);
        assertEquals(1, result.telemetry.suspectedStreakTracksFound);
    }

    /**
     * Same-frame grouping should be seeded by elongated anomalies but may absorb aligned rescued
     * anomalies whose elongation stays below the seeding threshold.
     */
    @Test
    public void findMovingObjectsAbsorbsAlignedLowElongationAnomaliesIntoSuspectedStreakTrack() {
        DetectionConfig config = new DetectionConfig();
        SourceExtractor.DetectedObject seed1 = createFrameAnomaly(10, 10, 30, 4.0, 14.0, (short) 120, 0, 4.2, 0.0);
        SourceExtractor.DetectedObject absorbed = createFrameAnomaly(22, 10, 30, 4.3, 15.0, (short) 120, 0, 3.2, 0.0);
        SourceExtractor.DetectedObject seed2 = createFrameAnomaly(34, 10, 30, 4.1, 13.8, (short) 120, 0, 4.1, 0.0);

        List<List<SourceExtractor.DetectedObject>> frames = new ArrayList<>();
        frames.add(List.of(seed1, absorbed, seed2));
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

        assertEquals(1, suspectedStreakTracks(result).size());
        assertEquals(0, result.anomalies.size());
        assertEquals(3, suspectedStreakTracks(result).get(0).points.size());
        assertSame(seed1, suspectedStreakTracks(result).get(0).points.get(0));
        assertSame(absorbed, suspectedStreakTracks(result).get(0).points.get(1));
        assertSame(seed2, suspectedStreakTracks(result).get(0).points.get(2));
    }

    /**
     * Preserved standalone streak fragments rescued as anomalies should also be groupable into one
     * suspected same-frame streak when they remain roughly collinear.
     */
    @Test
    public void findMovingObjectsPromotesAlignedPreservedStreakAnomaliesToSuspectedStreakTrack() {
        DetectionConfig config = new DetectionConfig();
        config.singleStreakMinPeakSigma = 10.0;

        List<List<SourceExtractor.DetectedObject>> frames = new ArrayList<>();
        frames.add(List.of(
                createFrameStreak(10, 10, 20, 9.0, 6.0, (short) 120, 0, 4.2, 0.0),
                createFrameStreak(22, 11, 20, 8.8, 6.0, (short) 120, 0, 4.4, Math.toRadians(3.0)),
                createFrameStreak(34, 12, 20, 8.9, 6.0, (short) 120, 0, 4.1, Math.toRadians(6.0))
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

        assertEquals(1, result.tracks.size());
        assertEquals(0, result.anomalies.size());
        assertEquals(1, suspectedStreakTracks(result).size());
        assertEquals(3, suspectedStreakTracks(result).get(0).points.size());
        assertTrue(suspectedStreakTracks(result).get(0).isSuspectedStreakTrack);
    }

    /**
     * Same-frame suspected streak grouping should ignore each anomaly's measured angle and rely
     * only on elongation plus line membership.
     */
    @Test
    public void findMovingObjectsIgnoresAnomalyAnglesWhenGroupingSuspectedStreaks() {
        DetectionConfig config = new DetectionConfig();

        List<List<SourceExtractor.DetectedObject>> frames = new ArrayList<>();
        frames.add(List.of(
                createFrameAnomaly(10, 10, 30, 4.0, 14.0, (short) 120, 0, 4.2, Math.toRadians(5.0)),
                createFrameAnomaly(22, 10, 30, 4.3, 15.0, (short) 120, 0, 4.1, Math.toRadians(65.0)),
                createFrameAnomaly(34, 10, 30, 4.1, 13.8, (short) 120, 0, 4.0, Math.toRadians(120.0))
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

        assertEquals(1, suspectedStreakTracks(result).size());
        assertEquals(0, result.anomalies.size());
    }

    /**
     * Same-frame suspected streak grouping should use its dedicated line tolerance rather than the
     * stricter multi-frame prediction tolerance.
     */
    @Test
    public void findMovingObjectsUsesDedicatedSuspectedStreakLineTolerance() {
        DetectionConfig config = new DetectionConfig();
        config.predictionTolerance = 3.0;
        config.maxStarJitter = 1.5;
        config.suspectedStreakLineTolerance = 6.0;

        List<List<SourceExtractor.DetectedObject>> frames = new ArrayList<>();
        frames.add(List.of(
                createFrameAnomaly(10, 10, 30, 4.0, 14.0, (short) 120, 0, 4.2, 0.0),
                createFrameAnomaly(22, 14, 30, 4.1, 14.1, (short) 120, 0, 4.3, Math.toRadians(40.0)),
                createFrameAnomaly(34, 10, 30, 4.2, 14.2, (short) 120, 0, 4.1, Math.toRadians(90.0))
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

        assertEquals(1, suspectedStreakTracks(result).size());
        assertEquals(0, result.anomalies.size());
        assertEquals(3, suspectedStreakTracks(result).get(0).points.size());
    }

    /**
     * When multiple same-frame collinear groups exist, each disjoint seeded line should be exported
     * as its own suspected streak track.
     */
    @Test
    public void findMovingObjectsReturnsMultipleSameFrameSuspectedStreakLines() {
        DetectionConfig config = new DetectionConfig();
        config.suspectedStreakLineTolerance = 3.0;

        SourceExtractor.DetectedObject longLine1 = createFrameAnomaly(10, 10, 30, 4.0, 14.0, (short) 120, 0, 4.2, 0.0);
        SourceExtractor.DetectedObject longLine2 = createFrameAnomaly(40, 10, 30, 4.1, 14.2, (short) 120, 0, 4.3, Math.toRadians(80.0));
        SourceExtractor.DetectedObject longLine3 = createFrameAnomaly(70, 10, 30, 4.2, 14.4, (short) 120, 0, 4.4, Math.toRadians(135.0));

        SourceExtractor.DetectedObject shortLine1 = createFrameAnomaly(10, 30, 30, 4.0, 14.0, (short) 120, 0, 4.2, Math.toRadians(20.0));
        SourceExtractor.DetectedObject shortLine2 = createFrameAnomaly(20, 30, 30, 4.0, 14.1, (short) 120, 0, 4.2, Math.toRadians(45.0));
        SourceExtractor.DetectedObject shortLine3 = createFrameAnomaly(30, 30, 30, 4.1, 14.2, (short) 120, 0, 4.1, Math.toRadians(70.0));
        SourceExtractor.DetectedObject shortLine4 = createFrameAnomaly(40, 30, 30, 4.2, 14.3, (short) 120, 0, 4.3, Math.toRadians(110.0));

        List<List<SourceExtractor.DetectedObject>> frames = new ArrayList<>();
        frames.add(List.of(longLine1, longLine2, longLine3, shortLine1, shortLine2, shortLine3, shortLine4));
        frames.add(new ArrayList<>());
        frames.add(new ArrayList<>());

        TrackLinker.TrackingResult result = TrackLinker.findMovingObjects(
                frames,
                new ArrayList<>(),
                config,
                null,
                128,
                64
        );

        assertEquals(2, suspectedStreakTracks(result).size());
        assertEquals(0, result.anomalies.size());

        TrackLinker.Track longTrack = suspectedStreakTrackContaining(result, longLine1);
        TrackLinker.Track shortTrack = suspectedStreakTrackContaining(result, shortLine1);

        assertNotNull(longTrack);
        assertNotNull(shortTrack);

        assertEquals(3, longTrack.points.size());
        assertSame(longLine1, longTrack.points.get(0));
        assertSame(longLine2, longTrack.points.get(1));
        assertSame(longLine3, longTrack.points.get(2));

        assertEquals(4, shortTrack.points.size());
        assertSame(shortLine1, shortTrack.points.get(0));
        assertSame(shortLine2, shortTrack.points.get(1));
        assertSame(shortLine3, shortTrack.points.get(2));
        assertSame(shortLine4, shortTrack.points.get(3));
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
        assertTrue(suspectedStreakTracks(result).isEmpty());
        assertEquals(1, result.telemetry.anomaliesFound);
        assertEquals(1, result.telemetry.integratedSigmaAnomaliesFound);
        assertEquals(0, result.telemetry.suspectedStreakTracksFound);
    }

    private static List<TrackLinker.Track> suspectedStreakTracks(TrackLinker.TrackingResult result) {
        List<TrackLinker.Track> suspectedTracks = new ArrayList<>();
        for (TrackLinker.Track track : result.tracks) {
            if (track.isSuspectedStreakTrack) {
                suspectedTracks.add(track);
            }
        }
        return suspectedTracks;
    }

    private static TrackLinker.Track suspectedStreakTrackContaining(TrackLinker.TrackingResult result,
                                                                    SourceExtractor.DetectedObject object) {
        for (TrackLinker.Track track : suspectedStreakTracks(result)) {
            if (track.points.contains(object)) {
                return track;
            }
        }
        return null;
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

    private static SourceExtractor.DetectedObject createFrameStreak(
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
        SourceExtractor.DetectedObject obj = createFrameAnomaly(
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
