/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 * author: Petros Pissias <petrospis at gmail.com>
 *
 */
package io.github.ppissias.jtransient.core;

import io.github.ppissias.jtransient.config.DetectionConfig;
import io.github.ppissias.jtransient.model.AnomalyDetection;
import io.github.ppissias.jtransient.model.AnomalyType;
import io.github.ppissias.jtransient.model.DetectedObject;
import io.github.ppissias.jtransient.model.Pixel;
import io.github.ppissias.jtransient.model.Track;
import io.github.ppissias.jtransient.telemetry.FaintStreakSearchTelemetry;
import org.junit.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * How the faint-streak search's candidates join the streak tracks: normal priority, duplicates of normal streaks,
 * no peak gate for faint-only singles, and nothing of the search reaching any other result.
 */
public class FaintStreakLinkingTest {
    private static final long START = 1_760_000_000_000L;
    private static final long INTERVAL = 120_000L;
    private static final long EXPOSURE = 115_000L;

    @Test
    public void aDuplicateOfANormalStreakIsDroppedButACrossingStreakStays() {
        DetectionConfig config = new DetectionConfig();
        DetectedObject normal = streak(100, 100, 60, 0.0, 0, 9.0, false);
        DetectedObject duplicate = streak(110, 100, 60, 0.0, 0, 4.0, true);
        DetectedObject crossing = streak(130, 100, 60, Math.PI / 2, 0, 4.0, true);
        FaintStreakSearchTelemetry telemetry = new FaintStreakSearchTelemetry();
        List<Track> tracks = FaintStreakLinking.add(new ArrayList<>(), List.of(normal),
                frames(List.of(duplicate, crossing), List.of(), List.of()), new ArrayList<>(), config, telemetry);
        assertEquals(1, telemetry.duplicatesOfNormalStreaks);
        assertEquals(1, telemetry.linkedCandidates);
        assertEquals(1, tracks.size());
        assertTrue(tracks.get(0).points.contains(crossing));
    }

    @Test
    public void aFaintOnlySingleStreakNeedsNoPeakGate() {
        DetectionConfig config = new DetectionConfig();
        DetectedObject faint = streak(100, 100, 60, 0.3, 1, 1.5, true);
        FaintStreakSearchTelemetry telemetry = new FaintStreakSearchTelemetry();
        List<Track> tracks = FaintStreakLinking.add(new ArrayList<>(), List.of(),
                frames(List.of(), List.of(faint), List.of()), new ArrayList<>(), config, telemetry);
        assertEquals(1, tracks.size());
        assertEquals(1, telemetry.faintOnlyTracks);
        assertEquals(0, telemetry.discarded);
    }

    @Test
    public void normalTracksKeepTheirPointsAndGainFaintPieces() {
        DetectionConfig config = new DetectionConfig();
        // A trail moving 80 px per frame along x, seen normally in frames 0 and 1 and only by the search in frame 2;
        // and a second normal track elsewhere.
        DetectedObject n0 = streak(100, 200, 76, 0.0, 0, 9.0, false);
        DetectedObject n1 = streak(180, 200, 76, 0.0, 1, 9.0, false);
        DetectedObject f2 = streak(260, 200, 76, 0.0, 2, 3.0, true);
        DetectedObject m0 = streak(100, 50, 76, 0.0, 0, 9.0, false);
        DetectedObject m1 = streak(180, 50, 76, 0.0, 1, 9.0, false);
        List<Track> normal = new ArrayList<>(List.of(track(n0, n1), track(m0, m1)));
        FaintStreakSearchTelemetry telemetry = new FaintStreakSearchTelemetry();
        List<Track> tracks = FaintStreakLinking.add(normal, List.of(n0, n1, m0, m1),
                frames(List.of(), List.of(), List.of(f2)), new ArrayList<>(), config, telemetry);
        assertEquals(2, tracks.size());
        Track extended = trackWith(tracks, n0);
        assertNotNull(extended);
        assertTrue(extended.points.contains(n1) && extended.points.contains(f2));
        Track other = trackWith(tracks, m0);
        assertEquals(List.of(m0, m1), other.points);
        assertEquals(1, telemetry.tracksWithFaintDetections);
        assertEquals(0, telemetry.faintOnlyTracks);
        assertEquals(1, telemetry.detectionsInTracks);
    }

    @Test
    public void anAnomalyOnAFaintStreaksPathJoinsIt() {
        DetectionConfig config = new DetectionConfig();
        DetectedObject f0 = streak(100, 200, 76, 0.0, 0, 3.0, true);
        DetectedObject f1 = streak(180, 200, 76, 0.0, 1, 3.0, true);
        DetectedObject flash = new DetectedObject(260, 200, 400, 12);
        flash.sourceFrameIndex = 2;
        flash.timestamp = START + 2 * INTERVAL;
        flash.exposureDuration = EXPOSURE;
        flash.rawPixels = new ArrayList<>(List.of(new Pixel(260, 200, 300)));
        List<AnomalyDetection> anomalies = new ArrayList<>(List.of(new AnomalyDetection(flash, AnomalyType.PEAK_SIGMA)));
        FaintStreakSearchTelemetry telemetry = new FaintStreakSearchTelemetry();
        List<Track> tracks = FaintStreakLinking.add(new ArrayList<>(), List.of(),
                frames(List.of(f0), List.of(f1), List.of()), anomalies, config, telemetry);
        assertEquals(1, tracks.size());
        assertTrue(tracks.get(0).points.contains(flash));
        assertTrue(anomalies.isEmpty());
        assertEquals(1, telemetry.anomaliesJoined);
    }

    @Test
    public void aDiscardedCandidateReachesNoOtherResult() throws Exception {
        DetectionConfig config = new DetectionConfig();
        DetectedObject binary = binaryStarLikeStreak();
        binary.fromFaintStreakSearch = true;
        binary.sourceFrameIndex = 1;
        binary.timestamp = START + INTERVAL;
        binary.exposureDuration = EXPOSURE;
        List<List<DetectedObject>> normalFrames = frames(List.of(), List.of(), List.of());
        FaintStreakSearch.Result faint = new FaintStreakSearch.Result(frames(List.of(), List.of(binary), List.of()),
                new FaintStreakSearchTelemetry());
        TrackLinker.TrackingResult result = TrackLinker.findMovingObjects(normalFrames, List.of(), config, null, 400, 300,
                null, null, faint);
        assertTrue(result.tracks.isEmpty());
        assertTrue(result.anomalies.isEmpty());
        for (List<DetectedObject> frame : result.allTransients) {
            assertFalse(frame.contains(binary));
        }
        for (List<DetectedObject> frame : result.unclassifiedTransients) {
            assertFalse(frame.contains(binary));
        }
        assertEquals(1, faint.telemetry.discarded);
        assertEquals(1, faint.telemetry.rejectedBinaryStarShape);
    }

    private static Track track(DetectedObject... points) {
        Track track = new Track();
        track.isStreakTrack = true;
        for (DetectedObject point : points) {
            track.addPoint(point);
        }
        return track;
    }

    private static Track trackWith(List<Track> tracks, DetectedObject point) {
        for (Track track : tracks) {
            if (track.points.contains(point)) {
                return track;
            }
        }
        return null;
    }

    @SafeVarargs
    private static List<List<DetectedObject>> frames(List<DetectedObject>... frames) {
        List<List<DetectedObject>> list = new ArrayList<>();
        for (List<DetectedObject> frame : frames) {
            list.add(new ArrayList<>(frame));
        }
        return list;
    }

    /** A straight streak three pixels wide, centred at (x, y). */
    private static DetectedObject streak(double x, double y, int length, double angle, int frame, double peakSigma,
                                         boolean faint) {
        List<Pixel> pixels = new ArrayList<>();
        double dx = Math.cos(angle);
        double dy = Math.sin(angle);
        for (int i = -length / 2; i <= length / 2; i++) {
            for (int w = -1; w <= 1; w++) {
                pixels.add(new Pixel((int) Math.round(x + i * dx - w * dy), (int) Math.round(y + i * dy + w * dx), 200));
            }
        }
        DetectedObject object = analyze(pixels);
        object.isStreak = true;
        object.peakSigma = peakSigma;
        object.sourceFrameIndex = frame;
        object.sourceFilename = "frame_" + frame + ".fit";
        object.timestamp = START + frame * INTERVAL;
        object.exposureDuration = EXPOSURE;
        object.fromFaintStreakSearch = faint;
        return object;
    }

    private static DetectedObject analyze(List<Pixel> pixels) {
        SourceExtractor.BackgroundMetrics sky = new SourceExtractor.BackgroundMetrics();
        sky.median = 0.0;
        sky.sigma = 1.0;
        sky.threshold = 1.0;
        DetectedObject object = SourceExtractor.analyzeShape(pixels, sky, new DetectionConfig());
        object.rawPixels = pixels;
        object.pixelArea = pixels.size();
        return object;
    }

    /** The real double-star footprint of {@code TrackLinkerBinaryStarStreakFilterTest}. */
    private static DetectedObject binaryStarLikeStreak() throws Exception {
        try (InputStream input = FaintStreakLinkingTest.class.getClassLoader()
                .getResourceAsStream("streak-shapes/binary-streak-shape-01.png")) {
            BufferedImage image = ImageIO.read(input);
            List<Pixel> pixels = new ArrayList<>();
            for (int y = 0; y < image.getHeight(); y++) {
                for (int x = 0; x < image.getWidth(); x++) {
                    int rgb = image.getRGB(x, y) & 0xFFFFFF;
                    if (rgb != 0) {
                        pixels.add(new Pixel(x + 100, y + 100, Math.max((rgb >> 16) & 0xFF, Math.max((rgb >> 8) & 0xFF, rgb & 0xFF))));
                    }
                }
            }
            DetectedObject object = analyze(pixels);
            object.isStreak = true;
            object.peakSigma = 4.0;
            return object;
        }
    }
}
