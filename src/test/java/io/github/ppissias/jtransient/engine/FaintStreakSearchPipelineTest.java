/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 * author: Petros Pissias <petrospis at gmail.com>
 *
 */
package io.github.ppissias.jtransient.engine;

import io.github.ppissias.jtransient.config.DetectionConfig;
import io.github.ppissias.jtransient.core.PixelEncoding;
import io.github.ppissias.jtransient.model.AnomalyDetection;
import io.github.ppissias.jtransient.model.DetectedObject;
import io.github.ppissias.jtransient.model.Track;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The faint-streak search in a whole run: a trail too faint for the normal extraction (1.6 sigma per pixel) is found
 * only with the search, as a streak track over at least three frames, its pieces keep their frames' identities across
 * a rejected frame, and the point detections stay as they are without the search.
 */
public class FaintStreakSearchPipelineTest {
    private static final int WIDTH = 360;
    private static final int HEIGHT = 240;
    private static final double SKY = 1000;
    private static final double NOISE = 20;
    private static final double TRAIL_PEAK = 32;
    private static final double TRAIL_Y = 120;
    private static final double SPEED = 72;
    private static final long START = 1_760_000_000_000L;
    private static final long INTERVAL = 120_000L;
    private static final long EXPOSURE = 115_000L;

    @Test
    public void aTrailTooFaintForTheNormalExtractionIsFoundByTheSearch() throws Exception {
        List<ImageFrame> frames = frames(-1);
        PipelineResult without = run(frames, false);
        PipelineResult with = run(frames, true);

        assertNull(without.telemetry.faintStreakSearchTelemetry);
        assertTrue("found without the search", trailTracks(without).isEmpty());
        for (Track track : without.tracks) {
            for (DetectedObject point : track.points) {
                assertFalse(point.fromFaintStreakSearch);
            }
        }

        assertNotNull(with.telemetry.faintStreakSearchTelemetry);
        assertTrue(with.telemetry.faintStreakSearchTelemetry.widthFromStars);
        List<Track> trail = trailTracks(with);
        int longest = 0;
        for (Track track : trail) {
            Set<Integer> trailFrames = new HashSet<>();
            for (DetectedObject point : track.points) {
                assertTrue(point.fromFaintStreakSearch);
                trailFrames.add(point.sourceFrameIndex);
            }
            longest = Math.max(longest, trailFrames.size());
        }
        assertTrue(describe(with), longest >= 3);

        // The point detections are the normal pass's own; the search adds nothing to them.
        assertEquals(positions(without.allTransients), positions(with.allTransients));
        Set<String> anomaliesWith = new HashSet<>();
        for (AnomalyDetection anomaly : with.anomalies) {
            anomaliesWith.add(position(anomaly.object));
        }
        Set<String> anomaliesWithout = new HashSet<>();
        for (AnomalyDetection anomaly : without.anomalies) {
            anomaliesWithout.add(position(anomaly.object));
        }
        assertTrue(anomaliesWithout.containsAll(anomaliesWith));
    }

    @Test
    public void piecesKeepTheirFramesAcrossARejectedFrame() throws Exception {
        PipelineResult result = run(frames(3), true);
        assertTrue(result.telemetry.totalFramesRejected >= 1);
        List<Track> trail = trailTracks(result);
        assertFalse(describe(result), trail.isEmpty());
        for (Track track : trail) {
            for (DetectedObject point : track.points) {
                assertTrue(point.sourceFrameIndex != 3);
                assertEquals("frame" + point.sourceFrameIndex, point.sourceFilename);
                assertEquals(START + point.sourceFrameIndex * INTERVAL, point.timestamp);
                assertEquals(EXPOSURE, point.exposureDuration);
            }
        }
    }

    private static PipelineResult run(List<ImageFrame> frames, boolean search) throws Exception {
        DetectionConfig config = new DetectionConfig();
        config.enableFaintStreakSearch = search;
        try (JTransientEngine engine = new JTransientEngine()) {
            return engine.runPipeline(new ArrayList<>(frames), config, null);
        }
    }

    /** Streak tracks on the trail's line. */
    private static List<Track> trailTracks(PipelineResult result) {
        List<Track> trail = new ArrayList<>();
        for (Track track : result.tracks) {
            if (!track.isStreakTrack) {
                continue;
            }
            boolean onLine = true;
            for (DetectedObject point : track.points) {
                onLine &= Math.abs(point.y - TRAIL_Y) < 4;
            }
            if (onLine) {
                trail.add(track);
            }
        }
        return trail;
    }

    /** The search's counts and the streak tracks, for a failure message. */
    private static String describe(PipelineResult result) {
        StringBuilder text = new StringBuilder();
        io.github.ppissias.jtransient.telemetry.FaintStreakSearchTelemetry f = result.telemetry.faintStreakSearchTelemetry;
        if (f != null) {
            text.append(String.format("sigma %.2f, streak-shaped %d, mask %d, duplicates %d, linked %d, in tracks %d, discarded %d, binary %d%n",
                    f.smoothingSigma, f.extractedStreaks, f.removedByStarMask, f.duplicatesOfNormalStreaks,
                    f.linkedCandidates, f.detectionsInTracks, f.discarded, f.rejectedBinaryStarShape));
        }
        for (Track track : result.tracks) {
            if (!track.isStreakTrack) {
                continue;
            }
            text.append("track:");
            for (DetectedObject point : track.points) {
                text.append(String.format(" [f%d %.0f,%.0f faint=%s]", point.sourceFrameIndex, point.x, point.y,
                        point.fromFaintStreakSearch));
            }
            text.append(System.lineSeparator());
        }
        return text.toString();
    }

    private static List<List<String>> positions(List<List<DetectedObject>> frames) {
        List<List<String>> positions = new ArrayList<>();
        for (List<DetectedObject> frame : frames) {
            List<String> list = new ArrayList<>();
            for (DetectedObject object : frame) {
                list.add(position(object));
            }
            positions.add(list);
        }
        return positions;
    }

    private static String position(DetectedObject object) {
        return object.sourceFrameIndex + ":" + Math.round(object.x * 100) + "," + Math.round(object.y * 100);
    }

    /**
     * Seven frames of stars, noise and a faint trail moving along y = 120 in frames 1 to 5; frame {@code blank} (if
     * not negative) is registration fill only.
     */
    private static List<ImageFrame> frames(int blank) {
        Random starRandom = new Random(11);
        List<double[]> stars = new ArrayList<>();
        for (int i = 0; i < 70; i++) {
            double y = 15 + starRandom.nextDouble() * (HEIGHT - 30);
            if (Math.abs(y - TRAIL_Y) < 10) {
                continue;
            }
            stars.add(new double[]{15 + starRandom.nextDouble() * (WIDTH - 30), y, 3000.0 * Math.pow(30.0, starRandom.nextDouble())});
        }
        List<ImageFrame> frames = new ArrayList<>();
        for (int f = 0; f < 7; f++) {
            Random noise = new Random(100 + f);
            short[][] pixels = new short[HEIGHT][WIDTH];
            double trailStart = 20 + SPEED * (f - 1);
            double trailEnd = trailStart + SPEED * EXPOSURE / INTERVAL;
            boolean trail = f >= 1 && f <= 5 && trailEnd < WIDTH - 20;
            for (int y = 0; y < HEIGHT; y++) {
                for (int x = 0; x < WIDTH; x++) {
                    double value = SKY + noise.nextGaussian() * NOISE;
                    for (double[] star : stars) {
                        double dx = x - star[0];
                        double dy = y - star[1];
                        if (dx * dx + dy * dy < 64) {
                            value += star[2] / (2 * Math.PI * 1.44) * Math.exp(-(dx * dx + dy * dy) / (2 * 1.44));
                        }
                    }
                    if (trail && x >= trailStart && x <= trailEnd) {
                        double dy = y - TRAIL_Y;
                        value += TRAIL_PEAK * Math.exp(-dy * dy / (2 * 1.44));
                    }
                    pixels[y][x] = PixelEncoding.fromShiftedPositiveInt(f == blank ? 0 : (int) Math.min(60000, Math.round(value)));
                }
            }
            frames.add(new ImageFrame(f, "frame" + f, pixels, START + f * INTERVAL, EXPOSURE));
        }
        return frames;
    }
}
