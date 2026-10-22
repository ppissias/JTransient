package io.github.ppissias.jtransient.core;

import io.github.ppissias.jtransient.config.DetectionConfig;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Regression tests for the final streak-track consolidation pass.
 */
public class TrackLinkerStreakConsolidationTest {

    @Test
    public void findMovingObjectsConsolidatesSameFrameSingleStreakIntoConfirmedTrack() {
        DetectionConfig config = new DetectionConfig();

        SourceExtractor.DetectedObject start = createStreak(10.0, 10.0, 0, 0.0, 12.0, 0L);
        SourceExtractor.DetectedObject end = createStreak(30.0, 10.0, 2, 0.0, 12.0, 240_000L);
        SourceExtractor.DetectedObject sameFrameSingle = createStreak(25.0, 10.6, 2, Math.toRadians(3.5), 11.0, 240_000L);

        List<List<SourceExtractor.DetectedObject>> frames = List.of(
                List.of(start),
                new ArrayList<>(),
                List.of(end, sameFrameSingle)
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
        assertEquals(1, result.telemetry.streakTracksFound);

        TrackLinker.Track track = result.tracks.get(0);
        assertTrue(track.isStreakTrack);
        assertEquals(3, track.points.size());
        assertTrue(track.points.contains(start));
        assertTrue(track.points.contains(end));
        assertTrue(track.points.contains(sameFrameSingle));
    }

    @Test
    public void findMovingObjectsAbsorbsSameFrameFragmentsOnTheStreakIntoTheTrack() {
        DetectionConfig config = new DetectionConfig();

        SourceExtractor.DetectedObject start = createStreak(10.0, 10.0, 0, 0.0, 12.0, 0L);
        SourceExtractor.DetectedObject end = createStreak(30.0, 10.0, 2, 0.0, 12.0, 240_000L);

        SourceExtractor.DetectedObject anomaly1 = createIntegratedAnomaly(32.0, 10.1, 2, 240_000L);
        SourceExtractor.DetectedObject anomaly2 = createIntegratedAnomaly(36.0, 10.0, 2, 240_000L);
        SourceExtractor.DetectedObject anomaly3 = createIntegratedAnomaly(40.0, 10.2, 2, 240_000L);

        List<List<SourceExtractor.DetectedObject>> frames = List.of(
                List.of(start),
                new ArrayList<>(),
                List.of(end, anomaly1, anomaly2, anomaly3)
        );

        TrackLinker.TrackingResult result = TrackLinker.findMovingObjects(
                frames,
                new ArrayList<>(),
                config,
                null,
                96,
                96
        );

        assertEquals(1, result.tracks.size());
        assertEquals(1, result.telemetry.streakTracksFound);
        assertTrue(result.anomalies.isEmpty());

        TrackLinker.Track track = result.tracks.get(0);
        assertTrue(track.isStreakTrack);
        assertEquals(5, track.points.size());
        assertTrue(track.points.contains(start));
        assertTrue(track.points.contains(end));
        assertTrue(track.points.contains(anomaly1));
        assertTrue(track.points.contains(anomaly2));
        assertTrue(track.points.contains(anomaly3));
    }

    @Test
    public void findMovingObjectsRejectsBacktrackingStreakMergeEvenWhenGeometryMatches() {
        DetectionConfig config = new DetectionConfig();

        SourceExtractor.DetectedObject start = createStreak(10.0, 10.0, 0, 0.0, 12.0, 0L);
        SourceExtractor.DetectedObject backtrack = createStreak(5.0, 10.0, 1, 0.0, 12.0, 120_000L);
        SourceExtractor.DetectedObject end = createStreak(30.0, 10.0, 2, 0.0, 12.0, 240_000L);

        List<List<SourceExtractor.DetectedObject>> frames = List.of(
                List.of(start),
                List.of(backtrack),
                List.of(end)
        );

        TrackLinker.TrackingResult result = TrackLinker.findMovingObjects(
                frames,
                new ArrayList<>(),
                config,
                null,
                64,
                64
        );

        assertEquals(2, result.tracks.size());
        assertEquals(2, result.telemetry.streakTracksFound);
        assertTrue(result.tracks.stream().anyMatch(track -> track.isStreakTrack && track.points.size() == 2));
        assertTrue(result.tracks.stream().anyMatch(track -> track.isStreakTrack && track.points.size() == 1));
    }

    @Test
    public void findMovingObjectsRejectsStreakTrackWithLargeTimestampSpeedChange() {
        DetectionConfig config = new DetectionConfig();

        SourceExtractor.DetectedObject frame0 = createStreak(0.0, 10.0, 0, 0.0, 12.0, 0L);
        SourceExtractor.DetectedObject frame1 = createStreak(500.0, 10.0, 1, 0.0, 12.0, 120_000L);
        SourceExtractor.DetectedObject frame20 = createStreak(1000.0, 10.0, 20, 0.0, 12.0, 2_400_000L);

        List<List<SourceExtractor.DetectedObject>> frames = emptyFrameList(21);
        frames.get(0).add(frame0);
        frames.get(1).add(frame1);
        frames.get(20).add(frame20);

        TrackLinker.TrackingResult result = TrackLinker.findMovingObjects(
                frames,
                new ArrayList<>(),
                config,
                null,
                1024,
                64
        );

        assertEquals(2, result.tracks.size());
        assertEquals(2, result.telemetry.streakTracksFound);
        assertTrue(result.tracks.stream().anyMatch(track -> track.isStreakTrack && track.points.size() == 2));
        assertTrue(result.tracks.stream().anyMatch(track -> track.isStreakTrack && track.points.size() == 1));
    }

    @Test
    public void findMovingObjectsUsesOneTimeSamplePerFrameForMultiPartStreaks() {
        DetectionConfig config = new DetectionConfig();

        SourceExtractor.DetectedObject frame0Part1 = createStreak(0.0, 10.0, 0, 0.0, 12.0, 0L);
        SourceExtractor.DetectedObject frame0Part2 = createStreak(20.0, 10.0, 0, 0.0, 12.0, 0L);
        SourceExtractor.DetectedObject frame1 = createStreak(100.0, 10.0, 1, 0.0, 12.0, 120_000L);
        SourceExtractor.DetectedObject frame2 = createStreak(190.0, 10.0, 2, 0.0, 12.0, 240_000L);

        List<List<SourceExtractor.DetectedObject>> frames = List.of(
                List.of(frame0Part1, frame0Part2),
                List.of(frame1),
                List.of(frame2)
        );

        TrackLinker.TrackingResult result = TrackLinker.findMovingObjects(
                frames,
                new ArrayList<>(),
                config,
                null,
                256,
                64
        );

        assertEquals(1, result.tracks.size());
        assertEquals(1, result.telemetry.streakTracksFound);
        assertEquals(4, result.tracks.get(0).points.size());
    }

    /**
     * A streak that broke into anomalies in the frames between its ends gets those pieces back; collinear anomalies
     * that join no streak stay anomalies.
     */
    @Test
    public void findMovingObjectsJoinsAnomalyGroupsToAStreakAndKeepsOtherGroupsAsAnomalies() {
        DetectionConfig config = new DetectionConfig();

        SourceExtractor.DetectedObject start = createStreak(10.0, 10.0, 0, 0.0, 12.0, 0L);
        SourceExtractor.DetectedObject end = createStreak(70.0, 10.0, 3, 0.0, 12.0, 360_000L);
        List<SourceExtractor.DetectedObject> frame1Pieces = List.of(
                createIntegratedAnomaly(22.0, 10.0, 1, 120_000L),
                createIntegratedAnomaly(30.0, 10.1, 1, 120_000L),
                createIntegratedAnomaly(38.0, 10.0, 1, 120_000L));
        List<SourceExtractor.DetectedObject> frame2Pieces = List.of(
                createIntegratedAnomaly(42.0, 10.1, 2, 240_000L),
                createIntegratedAnomaly(50.0, 10.0, 2, 240_000L),
                createIntegratedAnomaly(58.0, 10.1, 2, 240_000L));
        List<SourceExtractor.DetectedObject> chanceLine = List.of(
                createIntegratedAnomaly(20.0, 100.0, 1, 120_000L),
                createIntegratedAnomaly(40.0, 120.0, 1, 120_000L),
                createIntegratedAnomaly(60.0, 140.0, 1, 120_000L));

        List<SourceExtractor.DetectedObject> frame1 = new ArrayList<>(frame1Pieces);
        frame1.addAll(chanceLine);
        List<List<SourceExtractor.DetectedObject>> frames = List.of(
                List.of(start), frame1, frame2Pieces, List.of(end));

        TrackLinker.TrackingResult result = TrackLinker.findMovingObjects(
                frames, new ArrayList<>(), config, null, 160, 160);

        assertEquals(1, result.tracks.size());
        TrackLinker.Track track = result.tracks.get(0);
        assertTrue(track.isStreakTrack);
        assertEquals(8, track.points.size());
        assertTrue(track.points.containsAll(frame1Pieces));
        assertTrue(track.points.containsAll(frame2Pieces));
        assertEquals(3, result.anomalies.size());
        for (TrackLinker.AnomalyDetection anomaly : result.anomalies) {
            assertTrue(chanceLine.contains(anomaly.object));
        }
    }

    /**
     * Two fragments of one trail at x = 40 and x = 240 average to x = 140. An unrelated streak at x = 140.1 in the next
     * frame has moved 0.1 px along the line, which must not count as motion (the step used to be multiplied by the
     * 200 px axis).
     */
    @Test
    public void findMovingObjectsDoesNotMergeAStreakThatBarelyMovesAlongTheAxis() {
        DetectionConfig config = new DetectionConfig();
        SourceExtractor.DetectedObject left = createStreak(40.0, 40.0, 0, 0.0, 12.0, 0L);
        SourceExtractor.DetectedObject right = createStreak(240.0, 40.0, 0, 0.0, 12.0, 0L);
        SourceExtractor.DetectedObject unrelated = createStreak(140.1, 40.0, 1, 0.0, 12.0, 120_000L);

        List<List<SourceExtractor.DetectedObject>> frames = List.of(
                List.of(left, right), List.of(unrelated), new ArrayList<>());
        TrackLinker.TrackingResult result = TrackLinker.findMovingObjects(
                frames, new ArrayList<>(), config, null, 300, 100);

        for (TrackLinker.Track track : result.tracks) {
            boolean hasFirstFrame = track.points.contains(left) || track.points.contains(right);
            assertFalse("the streak of frame 1 joined the fragments of frame 0",
                    hasFirstFrame && track.points.contains(unrelated));
        }
    }

    /**
     * An artifact that leaves the same short mark in three frames does not move, so it is not a streak track; the
     * marks are still reported, as single streaks.
     */
    @Test
    public void findMovingObjectsDoesNotLinkAStreakThatDoesNotMove() {
        DetectionConfig config = new DetectionConfig();
        SourceExtractor.DetectedObject first = createStreak(40.0, 40.0, 0, 0.0, 12.0, 0L);
        SourceExtractor.DetectedObject second = createStreak(40.0, 40.0, 1, 0.0, 12.0, 120_000L);
        SourceExtractor.DetectedObject third = createStreak(40.0, 40.0, 2, 0.0, 12.0, 240_000L);

        List<List<SourceExtractor.DetectedObject>> frames = List.of(List.of(first), List.of(second), List.of(third));
        TrackLinker.TrackingResult result = TrackLinker.findMovingObjects(
                frames, new ArrayList<>(), config, null, 100, 100);

        int reported = 0;
        for (TrackLinker.Track track : result.tracks) {
            assertTrue("a multi-frame track was formed from a stationary mark", countFrames(track) <= 1);
            reported += track.points.size();
        }
        assertEquals(3, reported);
    }

    /** A streak that moves 20 px per frame still forms a streak track. */
    @Test
    public void findMovingObjectsLinksAStreakThatMoves() {
        DetectionConfig config = new DetectionConfig();
        config.maxJumpPixels = 1;
        config.rhythmStationaryThreshold = 10;
        for (boolean timed : new boolean[]{true, false}) {
            SourceExtractor.DetectedObject first = createStreak(20.0, 40.0, 0, 0.0, 12.0, timed ? 0 : -1);
            SourceExtractor.DetectedObject second = createStreak(40.0, 40.0, 1, 0.0, 12.0, timed ? 120_000 : -1);
            SourceExtractor.DetectedObject third = createStreak(60.0, 40.0, 2, 0.0, 12.0, timed ? 240_000 : -1);
            List<List<SourceExtractor.DetectedObject>> frames = List.of(List.of(first), List.of(second), List.of(third));
            TrackLinker.TrackingResult result = TrackLinker.findMovingObjects(
                    frames, new ArrayList<>(), config, null, 100, 100);
            assertEquals(1, result.tracks.size());
            assertTrue(result.tracks.get(0).isStreakTrack);
            assertEquals(3, countFrames(result.tracks.get(0)));
        }
    }

    private static int countFrames(TrackLinker.Track track) {
        java.util.Set<Integer> frames = new java.util.HashSet<>();
        for (SourceExtractor.DetectedObject point : track.points) {
            frames.add(point.sourceFrameIndex);
        }
        return frames.size();
    }

    private static SourceExtractor.DetectedObject createStreak(double x,
                                                               double y,
                                                               int frameIndex,
                                                               double angle,
                                                               double peakSigma,
                                                               long timestamp) {
        SourceExtractor.DetectedObject obj = new SourceExtractor.DetectedObject(x, y, 500.0, 20);
        obj.sourceFrameIndex = frameIndex;
        obj.sourceFilename = "frame_" + frameIndex + ".fit";
        obj.timestamp = timestamp;
        obj.exposureDuration = 120_000L;
        obj.isStreak = true;
        obj.angle = angle;
        obj.peakSigma = peakSigma;
        obj.integratedSigma = peakSigma;
        obj.pixelArea = 25.0;
        obj.fwhm = 2.0;
        obj.elongation = 10.0;
        obj.rawPixels = new ArrayList<>();
        obj.rawPixels.add(new SourceExtractor.Pixel((int) Math.round(x), (int) Math.round(y), 200));
        return obj;
    }

    private static SourceExtractor.DetectedObject createIntegratedAnomaly(double x,
                                                                          double y,
                                                                          int frameIndex,
                                                                          long timestamp) {
        SourceExtractor.DetectedObject obj = new SourceExtractor.DetectedObject(x, y, 150.0, 30);
        obj.sourceFrameIndex = frameIndex;
        obj.sourceFilename = "frame_" + frameIndex + ".fit";
        obj.timestamp = timestamp;
        obj.exposureDuration = 120_000L;
        obj.isStreak = false;
        obj.angle = 0.0;
        obj.peakSigma = 4.5;
        obj.integratedSigma = 14.0;
        obj.pixelArea = 30.0;
        obj.fwhm = 2.0;
        obj.elongation = 2.0;
        obj.rawPixels = new ArrayList<>();
        obj.rawPixels.add(new SourceExtractor.Pixel((int) Math.round(x), (int) Math.round(y), 120));
        return obj;
    }

    private static List<List<SourceExtractor.DetectedObject>> emptyFrameList(int frameCount) {
        List<List<SourceExtractor.DetectedObject>> frames = new ArrayList<>();
        for (int i = 0; i < frameCount; i++) {
            frames.add(new ArrayList<>());
        }
        return frames;
    }
}
