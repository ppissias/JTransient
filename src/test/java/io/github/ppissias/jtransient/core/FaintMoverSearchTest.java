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
import io.github.ppissias.jtransient.engine.ImageFrame;
import io.github.ppissias.jtransient.model.DetectedObject;
import io.github.ppissias.jtransient.model.Track;
import io.github.ppissias.jtransient.quality.FrameQualityAnalyzer;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The faint-mover search on synthetic frames: a mover too faint for the single frames is found with its motion, pure
 * noise gives nothing, and hot pixels moving with the drift, tracked objects, missing times and the time limit are
 * handled as documented.
 */
public class FaintMoverSearchTest {
    private static final int SIZE = 260;
    private static final int FRAMES = 16;
    private static final double SKY = 1000;
    private static final double NOISE = 10;
    private static final double FWHM = 3.0;
    private static final long START = 1_700_000_000_000L;
    private static final long CADENCE = 120_000L;

    /** Star size measurements of a Gaussian of {@link #FWHM} (the extraction measures about sqrt(2) wider). */
    private static List<FrameQualityAnalyzer.FrameMetrics> metrics() {
        List<FrameQualityAnalyzer.FrameMetrics> metrics = new ArrayList<>();
        for (int k = 0; k < FRAMES; k++) {
            FrameQualityAnalyzer.FrameMetrics m = new FrameQualityAnalyzer.FrameMetrics();
            m.medianFWHM = FWHM * Math.sqrt(2);
            m.fwhmStarCount = 50;
            metrics.add(m);
        }
        return metrics;
    }

    /** Noise frames, sky {@link #SKY}, with Gaussian sources: {x0, y0, vx, vy (px/min), peak} each. */
    private static List<ImageFrame> frames(long seed, double[]... sources) {
        Random random = new Random(seed);
        double sigma = FWHM / 2.3548;
        List<ImageFrame> frames = new ArrayList<>();
        for (int k = 0; k < FRAMES; k++) {
            double minutes = k * CADENCE / 60000.0;
            short[][] pixels = new short[SIZE][SIZE];
            for (int y = 0; y < SIZE; y++) {
                for (int x = 0; x < SIZE; x++) {
                    double value = SKY + NOISE * random.nextGaussian();
                    for (double[] s : sources) {
                        double dx = x - (s[0] + s[2] * minutes);
                        double dy = y - (s[1] + s[3] * minutes);
                        value += s[4] * Math.exp(-(dx * dx + dy * dy) / (2 * sigma * sigma));
                    }
                    pixels[y][x] = PixelEncoding.fromShiftedPositiveInt((int) Math.round(value));
                }
            }
            frames.add(new ImageFrame(k, "f" + k, pixels, START + k * CADENCE, 0L));
        }
        return frames;
    }

    private static FaintMoverSearch.Input input(List<ImageFrame> frames) {
        FaintMoverSearch.Input input = new FaintMoverSearch.Input();
        input.frames = frames;
        input.frameMetrics = metrics();
        input.masterStack = MasterMapGenerator.createMedianMasterStack(frames);
        return input;
    }

    private static FaintMoverSearch.Result run(FaintMoverSearch.Input input, DetectionConfig config) throws Exception {
        return FaintMoverSearch.search(FaintMoverSearch.plan(input, config));
    }

    /** Session mid-time, in minutes from the first frame. */
    private static double midMinutes() {
        return (FRAMES - 1) * CADENCE / 60000.0 / 2;
    }

    @Test
    public void aMoverTooFaintForOneFrameIsFoundWithItsMotion() throws Exception {
        // Peak 1.6 sigma per pixel and frame, 0.4 px/min.
        double[] mover = {100, 120, 0.4, -0.25, 1.6 * NOISE};
        FaintMoverSearch.Result result = run(input(frames(1, mover)), new DetectionConfig());

        assertNull(result.telemetry.skippedReason);
        assertEquals(1, result.candidates.size());
        FaintMoverCandidate c = result.candidates.get(0);
        double t = midMinutes();
        assertEquals(mover[0] + mover[2] * t, c.x, 1.0);
        assertEquals(mover[1] + mover[3] * t, c.y, 1.0);
        assertEquals(mover[2], c.vx, 0.05);
        assertEquals(mover[3], c.vy, 0.05);
        assertTrue(c.score >= 8);
        assertTrue(c.firstHalfScore >= FaintMoverSearch.MIN_HALF_SCORE);
        assertTrue(c.secondHalfScore >= FaintMoverSearch.MIN_HALF_SCORE);
        assertEquals(FaintMoverSearch.CUTOUT, c.cutout.length);
        double[] start = c.positionAt(START);
        assertEquals(mover[0], start[0], 1.0);
        assertEquals(mover[1], start[1], 1.0);
        assertEquals(1, result.telemetry.tiles);
        assertEquals(1, result.telemetry.tilesSearched);
        assertFalse(result.telemetry.truncated);
        assertEquals(FWHM, result.telemetry.fwhmPixels, 0.01);
        assertNotNull(c.diagnostics);
        var d = c.diagnostics;
        assertNull(d.unavailableReason);
        assertEquals(FRAMES, d.frames.size());
        assertTrue(d.width <= 256 && d.height <= 256);
        assertEquals(c.firstHalfScore, centralMaximum(d.firstHalf), 0.00001);
        assertEquals(c.secondHalfScore, centralMaximum(d.secondHalf), 0.00001);
        for (int k = 0; k < FRAMES; k++) {
            var f = d.frames.get(k);
            assertEquals(k, f.sequenceIndex);
            assertEquals(START + k * CADENCE, f.midpointMillis);
            assertEquals(d.width * d.height, f.residual.length);
            int x = f.sampleX - d.x, y = f.sampleY - d.y;
            assertEquals(f.signal, f.residual[y * d.width + x], 0);
            assertEquals(d.scoreX + (int) Math.round(c.vx * (k * CADENCE / 60000.0 - midMinutes())), f.sampleX);
        }
    }

    @Test
    public void aMoverCrossingAStarInOneHalfIsJudgedOnTheOtherHalf() throws Exception {
        // The mover starts on a bright star, whose masked core hides it for most of the first half.
        double[] star = {60, 150, 0, 0, 2000};
        double[] mover = {60, 150, 0.5, 0, 3 * NOISE};
        FaintMoverSearch.Result result = run(input(frames(8, star, mover)), new DetectionConfig());
        assertEquals(1, result.candidates.size());
        FaintMoverCandidate c = result.candidates.get(0);
        assertTrue(Double.isNaN(c.firstHalfScore));
        assertTrue(c.secondHalfScore >= FaintMoverSearch.MIN_HALF_SCORE);
        assertNull(c.diagnostics.firstHalf);
        assertNotNull(c.diagnostics.secondHalf);
        assertTrue(c.diagnostics.frames.stream().anyMatch(f -> !f.unmasked));
        assertTrue(c.diagnostics.frames.stream().anyMatch(f -> f.unmasked));
        assertEquals(c.secondHalfScore, centralMaximum(c.diagnostics.secondHalf), 0.00001);
        // Only the unmasked half of the path constrains the motion.
        assertEquals(mover[2], c.vx, 0.1);
    }

    /** Review samples own their pixels and survive release or reuse of the borrowed source frames. */
    @Test public void reviewPixelsDoNotBorrowInputFrames() throws Exception {
        var original = frames(1, new double[]{100, 120, 0.4, -0.25, 1.6 * NOISE});
        var result = run(input(original), new DetectionConfig());
        assertFalse(result.candidates.isEmpty());
        var d = result.candidates.getFirst().diagnostics;
        assertNull(d.unavailableReason);
        float[] saved = d.frames.getFirst().residual.clone();
        for (var frame : original) for (var row : frame.pixelData) java.util.Arrays.fill(row, Short.MIN_VALUE);
        org.junit.Assert.assertArrayEquals(saved, d.frames.getFirst().residual, 0);
        assertEquals(result.candidates.size(), result.telemetry.diagnosticsAvailable);
        assertTrue(result.telemetry.diagnosticBytes > 0 && result.telemetry.diagnosticBytes <= 32L * 1024 * 1024);
    }

    /** Returns the actual half-score peak in the engine's central refinement area. */
    private static double centralMaximum(float[][] image) {
        double value = Double.NEGATIVE_INFINITY;
        int r = FaintMoverSearch.CUTOUT / 2;
        int radius = FaintMoverSearch.REFINE_RADIUS;
        for (int y = r - radius; y <= r + radius; y++) for (int x = r - radius; x <= r + radius; x++) value = Math.max(value, image[y][x]);
        return value;
    }

    @Test
    public void pureNoiseAndStarsGiveNoCandidate() throws Exception {
        double[] star = {60, 60, 0, 0, 400};
        double[] faintStar = {200, 180, 0, 0, 30};
        FaintMoverSearch.Result result = run(input(frames(2, star, faintStar)), new DetectionConfig());
        assertTrue(result.candidates.isEmpty());
    }

    @Test
    public void aHotPixelMovingWithTheDriftIsNotReported() throws Exception {
        // A defect at camera (90, 90); the frames drift 0.5 px/min in x, so it moves -0.5 px/min on the registered frames.
        double[] defect = {90, 90, -0.5, 0, 3 * NOISE};
        FaintMoverSearch.Input input = input(frames(3, defect));
        Map<Integer, FrameRegistration> registrations = new HashMap<>();
        for (int k = 0; k < FRAMES; k++) {
            registrations.put(k, FrameRegistration.of(k, -0.5 * k * CADENCE / 60000.0, 0, 0));
        }
        input.registrations = registrations;
        FaintMoverSearch.Result result = run(input, new DetectionConfig());
        assertTrue(result.candidates.isEmpty());
        assertEquals(1, result.telemetry.followingCamera);
    }

    @Test
    public void aMoverWithTheAverageDriftOnDitheredFramesIsKept() throws Exception {
        // The frames drift 0.4 px/min and are dithered by up to 6 px, so the mover's camera position only jumps.
        double[] mover = {130, 110, -0.4, 0, 3 * NOISE};
        FaintMoverSearch.Input input = input(frames(7, mover));
        Random dither = new Random(11);
        Map<Integer, FrameRegistration> registrations = new HashMap<>();
        for (int k = 0; k < FRAMES; k++) {
            registrations.put(k, FrameRegistration.of(k, -0.4 * k * CADENCE / 60000.0 + 12 * dither.nextDouble() - 6,
                    12 * dither.nextDouble() - 6, 0));
        }
        input.registrations = registrations;
        FaintMoverSearch.Result result = run(input, new DetectionConfig());
        assertEquals(1, result.candidates.size());
        assertEquals(0, result.telemetry.followingCamera);
    }

    @Test
    public void aTrackedMoverAndItsSideLobesAreNotReported() throws Exception {
        double[] mover = {100, 120, 0.4, -0.25, 3 * NOISE};
        FaintMoverSearch.Input input = input(frames(4, mover));
        Track track = new Track();
        for (int k = 0; k < FRAMES; k += 3) {
            double minutes = k * CADENCE / 60000.0;
            DetectedObject point = new DetectedObject(mover[0] + mover[2] * minutes, mover[1] + mover[3] * minutes, 100, 4);
            point.timestamp = START + k * CADENCE;
            point.exposureDuration = 0;
            point.sourceFrameIndex = k;
            track.addPoint(point);
        }
        input.tracks = List.of(track);
        FaintMoverSearch.Result result = run(input, new DetectionConfig());
        assertTrue(result.candidates.isEmpty());
        assertEquals(1, result.telemetry.matchedTracks);
    }

    @Test
    public void framesWithoutCaptureTimesAreNotSearched() throws Exception {
        List<ImageFrame> frames = new ArrayList<>(frames(5));
        ImageFrame first = frames.get(0);
        frames.set(0, new ImageFrame(first.sequenceIndex, first.filename, first.pixelData, -1L, 0L));
        FaintMoverSearch.Plan plan = FaintMoverSearch.plan(input(frames), new DetectionConfig());
        assertNotNull(plan.skippedReason);
        FaintMoverSearch.Result result = FaintMoverSearch.search(plan);
        assertTrue(result.candidates.isEmpty());
        assertEquals(plan.skippedReason, result.telemetry.skippedReason);
    }

    @Test
    public void framesOfAnotherNightAreLeftOut() throws Exception {
        // Two frames a month later must not stretch the motion grid over a month.
        double[] mover = {100, 120, 0.4, -0.25, 3 * NOISE};
        List<ImageFrame> frames = new ArrayList<>(frames(9, mover));
        long month = 30L * 24 * 3600 * 1000;
        for (int k = FRAMES - 2; k < FRAMES; k++) {
            ImageFrame f = frames.get(k);
            frames.set(k, new ImageFrame(f.sequenceIndex, f.filename, f.pixelData, f.timestamp + month, 0L));
        }
        FaintMoverSearch.Result result = run(input(frames), new DetectionConfig());
        assertNull(result.telemetry.skippedReason);
        assertEquals(FRAMES - 2, result.telemetry.frames);
        assertEquals(2, result.telemetry.framesOutsideSession);
        assertEquals(result.telemetry.frames, result.telemetry.searchedFrameIndices.size());
        assertFalse(result.telemetry.searchedFrameIndices.contains(frames.getLast().sequenceIndex));
        assertEquals(1, result.candidates.size());
        assertEquals(mover[2], result.candidates.get(0).vx, 0.08);
    }

    @Test
    public void aSpeedRangeTooWideForTheSessionIsNotSearched() {
        DetectionConfig config = new DetectionConfig();
        config.faintMoverMaxSpeedPixelsPerMinute = 1000;
        FaintMoverSearch.Plan plan = FaintMoverSearch.plan(input(frames(10)), config);
        assertNotNull(plan.skippedReason);
        assertTrue(plan.skippedReason.contains("trial motions"));
    }

    @Test
    public void largestSessionKeepsTheBlockWithTheMostFrames() {
        double hour = 3600_000;
        int[] block = FaintMoverSearch.largestSession(new double[]{0, 1, 2, 5 * hour, 5 * hour + 1, 5 * hour + 2,
                5 * hour + 3, 10 * hour});
        assertEquals(List.of(3, 4, 5, 6), java.util.Arrays.stream(block).boxed().toList());
    }

    @Test
    public void theTimeLimitStopsTheSearchAndSaysSo() throws Exception {
        DetectionConfig config = new DetectionConfig();
        config.faintMoverMaxSeconds = 1e-9;
        FaintMoverSearch.Result result = run(input(frames(6)), config);
        assertTrue(result.telemetry.truncated);
        assertEquals(0, result.telemetry.tilesSearched);
        assertTrue(result.candidates.isEmpty());
    }

    @Test
    public void theMotionGridCoversTheSpeedRange() {
        double step = 0.08;
        double[][] grid = FaintMoverSearch.motionGrid(0.1, 0.6, step);
        for (double[] v : grid) {
            double speed = Math.hypot(v[0], v[1]);
            assertTrue(speed >= 0.1 - 1e-9 && speed <= 0.6 + 1e-9);
        }
        // Every motion well inside the range is within half a grid diagonal of a grid motion.
        Random random = new Random(7);
        for (int i = 0; i < 1000; i++) {
            double speed = 0.1 + step + random.nextDouble() * (0.6 - 0.1 - 2 * step);
            double angle = random.nextDouble() * 2 * Math.PI;
            double best = Double.MAX_VALUE;
            for (double[] v : grid) {
                best = Math.min(best, Math.hypot(v[0] - speed * Math.cos(angle), v[1] - speed * Math.sin(angle)));
            }
            assertTrue(best <= step * Math.sqrt(0.5) + 1e-9);
        }
    }
}
