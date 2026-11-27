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
import io.github.ppissias.jtransient.quality.FrameQualityAnalyzer;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The automatic fastest motion: 2 star widths per exposure, lowered for the motion limit of the compute path, for
 * memory and for the time limit, never below the configured value for time, and the configured value without
 * exposure times or with the automatic speed off.
 */
public class FaintMoverAutoSpeedTest {
    private static final int SIZE = 200;
    private static final int FRAMES = 12;
    private static final double FWHM = 3.0;
    private static final long START = 1_700_000_000_000L;
    /** 5 minutes apart over 55 minutes, 1-minute exposures: 2 star widths per exposure is 6 px/min. */
    private static final long CADENCE = 300_000L;
    private static final long EXPOSURE = 60_000L;
    private static final double POINT_SPEED = 2 * FWHM;

    @Test
    public void offOrWithoutExposureTimesTheSettingIsSearched() {
        DetectionConfig off = new DetectionConfig();
        off.faintMoverAutoMaxSpeed = false;
        off.faintMoverMaxSpeedPixelsPerMinute = 1.5;
        FaintMoverSearch.Plan plan = FaintMoverSearch.plan(input(EXPOSURE), off);
        assertEquals(1.5, plan.maxSpeed, 0.0);
        assertFalse(plan.telemetry.autoMaxSpeed);
        assertEquals("SETTING", plan.telemetry.maxSpeedLimit);
        assertTrue(Double.isNaN(plan.telemetry.estimatedSeconds));

        DetectionConfig on = new DetectionConfig();
        on.faintMoverMaxSpeedPixelsPerMinute = 1.5;
        FaintMoverSearch.Plan noExposure = FaintMoverSearch.plan(input(0L), on);
        assertEquals(1.5, noExposure.maxSpeed, 0.0);
        assertTrue(noExposure.telemetry.autoMaxSpeed);
        assertEquals("NO_EXPOSURE_TIMES", noExposure.telemetry.maxSpeedLimit);
    }

    @Test
    public void twoStarWidthsPerExposureWhenNothingLimitsIt() {
        DetectionConfig config = new DetectionConfig();
        FaintMoverSearch.Plan plan = FaintMoverSearch.plan(input(EXPOSURE), config);
        assertEquals(POINT_SPEED, plan.maxSpeed, 1e-9);
        assertTrue(plan.telemetry.autoMaxSpeed);
        assertEquals("POINT_LIKE", plan.telemetry.maxSpeedLimit);
        assertEquals(POINT_SPEED, plan.telemetry.pointLikeMaxSpeedPixelsPerMinute, 1e-9);
        assertTrue("timed sample", Double.isFinite(plan.telemetry.estimatedSeconds));
        assertTrue(plan.telemetry.estimatedSeconds < config.faintMoverMaxSeconds);
    }

    /** With several workers and tiles, the sample also runs on all workers at once. */
    @Test
    public void theSampleRunsOnEveryWorkerAtOnce() {
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(4);
        try {
            FaintMoverSearch.Input input = input(EXPOSURE, 1100, 300);
            int[] calls = new int[1];
            input.workers = new FaintMoverSearch.Workers() {
                @Override public int workers(int tiles, long bytesPerTile) { return Math.min(4, tiles); }
                @Override public int maxWorkers() { return 4; }
                @Override public <T> List<T> invokeAll(List<java.util.concurrent.Callable<T>> tasks)
                        throws java.util.concurrent.ExecutionException {
                    calls[0]++;
                    List<T> results = new ArrayList<>();
                    try {
                        for (var future : pool.invokeAll(tasks)) results.add(future.get());
                    } catch (InterruptedException e) {
                        throw new IllegalStateException(e);
                    }
                    return results;
                }
            };
            FaintMoverSearch.Plan plan = FaintMoverSearch.plan(input, new DetectionConfig());
            assertEquals("the sample alone, then on every worker at once", 1, calls[0]);
            assertEquals(POINT_SPEED, plan.maxSpeed, 1e-9);
            assertTrue(Double.isFinite(plan.telemetry.estimatedSeconds));
        } finally {
            pool.shutdownNow();
        }
    }

    /** A speed range between the slowest motion and the first grid step has no trial motion: skipped, not a failure. */
    @Test
    public void aRangeWithoutAnyTrialMotionIsSkipped() throws Exception {
        DetectionConfig config = new DetectionConfig();
        config.faintMoverAutoMaxSpeed = false;
        // Grid step: FWHM over half the 55-minute session; slowest: 1.5 FWHM over the session, 0.75 steps.
        double step = FWHM / 27.5;
        config.faintMoverMinDisplacementFwhm = 1.5;
        config.faintMoverMaxSpeedPixelsPerMinute = 0.9 * step;
        FaintMoverSearch.Plan plan = FaintMoverSearch.plan(input(EXPOSURE), config);
        assertTrue(plan.skippedReason, plan.skippedReason != null && plan.skippedReason.startsWith("no trial motion"));
        assertEquals(0, FaintMoverSearch.search(plan).candidates.size());
    }

    @Test
    public void theMotionLimitOfTheComputePathLowersIt() {
        DetectionConfig config = new DetectionConfig();
        FaintMoverSearch.Input input = input(EXPOSURE);
        input.compute = selection(new JavaCoarseSearch(), 2000, config.faintMoverMaxSeconds);
        FaintMoverSearch.Plan plan = FaintMoverSearch.plan(input, config);
        assertEquals("MOTION_LIMIT", plan.telemetry.maxSpeedLimit);
        assertTrue(plan.maxSpeed < POINT_SPEED);
        assertTrue(plan.motions.length <= 0.9 * 2000);
        assertTrue("close to the limit", plan.motions.length > 0.8 * 2000);
        assertEquals(plan.motions.length, FaintMoverSearch.motionCount(plan.telemetry.minSpeedPixelsPerMinute,
                plan.maxSpeed, plan.coarseStep));
    }

    @Test
    public void memoryLowersItSoThatOneTileFits() {
        DetectionConfig config = new DetectionConfig();
        FaintMoverSearch.Input input = input(EXPOSURE);
        long started = System.nanoTime();
        FaintMoverSearch.SpeedChoice roomy = FaintMoverSearch.chooseSpeed(input, config, started,
                FaintMoverSearch.MAX_MOTIONS, 1L << 40);
        assertEquals("POINT_LIKE", roomy.limit());
        long heap = 120L << 20;
        FaintMoverSearch.SpeedChoice tight = FaintMoverSearch.chooseSpeed(input, config, started,
                FaintMoverSearch.MAX_MOTIONS, heap);
        assertEquals("MEMORY", tight.limit());
        assertTrue(tight.speed() < POINT_SPEED && tight.speed() > 1.0);
        assertTrue(tight.automatic());
        // Just faster would not fit: the speed is the largest that does, to 0.001 px/min.
        FaintMoverSearch.SpeedChoice faster = FaintMoverSearch.chooseSpeed(input, config, started,
                FaintMoverSearch.MAX_MOTIONS, (long) (heap * 1.05));
        assertTrue(faster.speed() > tight.speed());
    }

    @Test
    public void theTimeLimitLowersItButNotBelowTheSetting() {
        DetectionConfig config = new DetectionConfig();
        config.faintMoverMaxSeconds = 3.0;
        FaintMoverSearch.Input input = input(EXPOSURE);
        input.compute = selection(new SlowBackend(), FaintMoverSearch.MAX_MOTIONS, config.faintMoverMaxSeconds);
        FaintMoverSearch.Plan plan = FaintMoverSearch.plan(input, config);
        assertEquals("TIME_LIMIT", plan.telemetry.maxSpeedLimit);
        assertEquals(POINT_SPEED, plan.telemetry.speedBeforeTimeLimitPixelsPerMinute, 1e-9);
        assertTrue(plan.maxSpeed < POINT_SPEED);
        assertTrue(plan.maxSpeed >= config.faintMoverMaxSpeedPixelsPerMinute);
        assertTrue(plan.telemetry.estimatedSeconds <= config.faintMoverMaxSeconds);
        assertTrue(plan.telemetry.sampleMs > 0);

        config.faintMoverMaxSpeedPixelsPerMinute = 5.0;
        input.compute = selection(new SlowBackend(), FaintMoverSearch.MAX_MOTIONS, config.faintMoverMaxSeconds);
        FaintMoverSearch.Plan floor = FaintMoverSearch.plan(input, config);
        assertEquals(5.0, floor.maxSpeed, 0.0);
        assertEquals("TIME_LIMIT", floor.telemetry.maxSpeedLimit);
    }

    /** A first pass that takes half a millisecond per motion. */
    private static final class SlowBackend implements CoarseSearchBackend {
        private int motions;

        @Override public String description() { return "Slow test backend"; }
        @Override public void beginRun(int[][] coarseShiftX, int[][] coarseShiftY) { motions = coarseShiftX.length; }
        @Override public boolean search(CoarseTileJob job, CoarseControl control) {
            long end = System.nanoTime() + motions * 500_000L;
            while (System.nanoTime() < end) {
                if (control.pastDeadline()) return false;
                control.checkCancelled();
            }
            return true;
        }
        @Override public void endRun() { }
        @Override public void close() { }
    }

    private static CoarseBackendSelection selection(CoarseSearchBackend backend, int maxMotions, double seconds) {
        return new CoarseBackendSelection(backend, false, maxMotions, seconds, "Java", "", "", "", "Test", Double.NaN,
                "not run");
    }

    private static FaintMoverSearch.Input input(long exposure) {
        return input(exposure, SIZE, SIZE);
    }

    private static FaintMoverSearch.Input input(long exposure, int width, int height) {
        Random random = new Random(3);
        List<ImageFrame> frames = new ArrayList<>();
        for (int k = 0; k < FRAMES; k++) {
            short[][] pixels = new short[height][width];
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    pixels[y][x] = PixelEncoding.fromShiftedPositiveInt((int) Math.round(1000 + 10 * random.nextGaussian()));
                }
            }
            frames.add(new ImageFrame(k, "f" + k, pixels, START + k * CADENCE, exposure));
        }
        List<FrameQualityAnalyzer.FrameMetrics> metrics = new ArrayList<>();
        for (int k = 0; k < FRAMES; k++) {
            FrameQualityAnalyzer.FrameMetrics m = new FrameQualityAnalyzer.FrameMetrics();
            m.starWidthFwhm = FWHM;
            m.starWidthStars = 50;
            metrics.add(m);
        }
        FaintMoverSearch.Input input = new FaintMoverSearch.Input();
        input.frames = frames;
        input.frameMetrics = metrics;
        input.masterStack = MasterMapGenerator.createMedianMasterStack(frames);
        return input;
    }
}
