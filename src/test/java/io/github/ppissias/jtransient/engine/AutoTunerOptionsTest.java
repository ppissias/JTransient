package io.github.ppissias.jtransient.engine;

import com.google.gson.Gson;
import io.github.ppissias.jtransient.config.DetectionConfig;
import io.github.ppissias.jtransient.core.PixelEncoding;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/** Verifies per-operation grid, sample, budget and diagnostics ownership without changing process-wide tuning state. */
public class AutoTunerOptionsTest {
    /** The tuner propagates caller core diagnostics through its scientific workers. */
    @Test
    public void tunerInheritsCoreDiagnostics() {
        List<String> messages = new java.util.concurrent.CopyOnWriteArrayList<>();
        var policy = io.github.ppissias.jtransient.execution.ExecutionPolicy.bounded(1);
        try (var scope = io.github.ppissias.jtransient.execution.DiagnosticContext.open(
                new io.github.ppissias.jtransient.execution.DiagnosticOptions(true, messages::add));
             var session = io.github.ppissias.jtransient.execution.ExecutionSession.owned(policy)) {
            var calibrated = CalibratedAutoTuner.calibrate(frames(), new DetectionConfig(), calibratedOptions(4, 1), session, null);
            assertTrue(calibrated.failureReason, calibrated.success);
            assertTrue(messages.stream().anyMatch(message -> message.contains("BACKGROUND STATS")));
            assertFalse(session.isClosed());
        }
    }

    /** Verifies the tuner borrows a reusable session and records its actual resource policy in its result. */
    @Test
    public void borrowedSessionPolicyAndOwnershipArePreserved() {
        var policy = io.github.ppissias.jtransient.execution.ExecutionPolicy.bounded(1);
        try (var session = io.github.ppissias.jtransient.execution.ExecutionSession.owned(policy)) {
            var measured = CalibratedAutoTuner.calibrate(frames(), new DetectionConfig(), calibratedOptions(4, 1), session, null);
            assertTrue(measured.failureReason, measured.success);
            assertEquals(policy, measured.options.executionPolicy());
            assertFalse(session.isClosed());
            assertFalse(session.executor().isShutdown());
        }
    }

    /** Mutates builder arrays and accessor copies and verifies neither changes the immutable snapshot. */
    @Test
    public void defensivelyCopiesAllSearchGrids() throws Exception {
        assertArrayOwnership(CalibratedAutoTuner.Options.builder());
    }

    /** Runs independent calibrated grids and profile budgets concurrently, including replay after measurement. */
    @Test
    public void concurrentCalibrationsMatchIsolatedRunsAndKeepTheirBudgets() throws Exception {
        List<ImageFrame> frames = frames();
        var first = calibratedOptions(3.5, 0.1);
        var second = calibratedOptions(5, 100);
        var isolatedFirst = calibrated(frames, first, null);
        var isolatedSecond = calibrated(frames, second, null);
        var barrier = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var firstRun = executor.submit(() -> calibrated(frames, first, barrier));
            var secondRun = executor.submit(() -> calibrated(frames, second, barrier));
            assertCalibration(isolatedFirst, firstRun.get(30, TimeUnit.SECONDS));
            assertCalibration(isolatedSecond, secondRun.get(30, TimeUnit.SECONDS));
        }
        assertTrue(isolatedFirst.failureReason, isolatedFirst.success);
        assertTrue(isolatedSecond.failureReason, isolatedSecond.success);
        assertEquals(0.1, CalibratedAutoTuner.buildTradeoff(isolatedFirst).profiles[1].nominalBudgetPerMpixFrame, 0);
        assertEquals(100, CalibratedAutoTuner.buildTradeoff(isolatedSecond).profiles[1].nominalBudgetPerMpixFrame, 0);
        assertEquals(3.5, isolatedFirst.options.sigmasToTest()[0], 0);
        assertEquals(5, isolatedSecond.options.sigmasToTest()[0], 0);
    }

    /** Routes verbose diagnostics only to the enabled operation's caller-owned sink. */
    @Test
    public void diagnosticsArePerRunAndSilentByDefault() {
        List<String> messages = new java.util.concurrent.CopyOnWriteArrayList<>();
        try (var scope = io.github.ppissias.jtransient.execution.DiagnosticContext.open(
                new io.github.ppissias.jtransient.execution.DiagnosticOptions(false, messages::add))) {
            calibrated(frames(), calibratedOptions(4, 1), null);
        }
        assertTrue(messages.isEmpty());
        try (var scope = io.github.ppissias.jtransient.execution.DiagnosticContext.open(
                new io.github.ppissias.jtransient.execution.DiagnosticOptions(true, messages::add))) {
            calibrated(frames(), calibratedOptions(4, 1), null);
        }
        assertFalse(messages.isEmpty());
    }

    /** Rejects malformed budgets and empty search grids before any images or listeners are consumed. */
    @Test
    public void validatesPoliciesAtConstruction() {
        var calibrated = CalibratedAutoTuner.Options.builder();
        calibrated.sigmasToTest = new double[0];
        assertThrows(IllegalArgumentException.class, calibrated::build);
        calibrated = CalibratedAutoTuner.Options.builder();
        calibrated.falsePositiveBudgetPerMpixFrame = new double[]{1};
        assertThrows(IllegalArgumentException.class, calibrated::build);
        calibrated.falsePositiveBudgetPerMpixFrame = new double[]{1, 1, Double.NaN, 1};
        assertThrows(IllegalArgumentException.class, calibrated::build);
    }

    /** Checks every array field in each builder so newly added grid dimensions inherit the ownership contract. */
    private static void assertArrayOwnership(Object builder) throws Exception {
        Object options = builder.getClass().getMethod("build").invoke(builder);
        for (var field : builder.getClass().getFields()) {
            if (!field.getType().isArray()) continue;
            Object supplied = field.get(builder);
            Object captured = options.getClass().getMethod(field.getName()).invoke(options);
            assertNotSame(field.getName(), supplied, captured);
            Object original = java.lang.reflect.Array.get(captured, 0);
            if (field.getType() == int[].class) {
                java.lang.reflect.Array.setInt(supplied, 0, 999);
                java.lang.reflect.Array.setInt(captured, 0, 998);
            } else {
                java.lang.reflect.Array.setDouble(supplied, 0, 999);
                java.lang.reflect.Array.setDouble(captured, 0, 998);
            }
            Object fresh = options.getClass().getMethod(field.getName()).invoke(options);
            assertEquals(field.getName(), original, java.lang.reflect.Array.get(fresh, 0));
        }
    }

    /** Captures a small calibrated sweep with independently chosen noise budgets in detections per megapixel-frame. */
    private static CalibratedAutoTuner.Options calibratedOptions(double sigma, double budget) {
        var builder = CalibratedAutoTuner.Options.builder();
        builder.sigmasToTest = new double[]{sigma};
        builder.growDeltasToTest = new double[]{1.25};
        builder.minPixelAreaFactors = new double[]{0.5};
        builder.masterSigmasToTest = new double[]{3};
        builder.masterGrowOffsetsToTest = new double[]{0.5};
        builder.masterMinPixelAreaFactors = new double[]{0.5};
        builder.maskOverlapsToTest = new double[]{0.7};
        builder.falsePositiveBudgetPerMpixFrame = new double[]{budget, budget, budget, budget};
        builder.cropCount = 1;
        builder.maxCropsPerSide = 1;
        builder.preferredCropSize = 112;
        builder.minCropSize = 64;
        builder.minCropBorderMargin = 20;
        builder.cropPaddingClearance = 20;
        return builder.build();
    }

    /** Measures borrowed synthetic frames under one immutable calibrated policy. */
    private static CalibratedAutoTuner.Calibration calibrated(List<ImageFrame> frames, CalibratedAutoTuner.Options options,
                                                             CyclicBarrier barrier) {
        return CalibratedAutoTuner.calibrate(frames, new DetectionConfig(), options, listener(barrier));
    }

    /** Returns a one-shot bounded rendezvous listener, or no callback when testing an isolated operation. */
    private static TransientEngineProgressListener listener(CyclicBarrier barrier) {
        if (barrier == null) return null;
        boolean[] awaited = {false};
        return (percent, message) -> {
            if (awaited[0]) return;
            awaited[0] = true;
            try {
                barrier.await(10, TimeUnit.SECONDS);
            } catch (Exception failure) {
                throw new AssertionError(failure);
            }
        };
    }

    /** Compares full candidate measurements, selection and tradeoff data, allowing runtime to vary. */
    private static void assertCalibration(CalibratedAutoTuner.Calibration expected, CalibratedAutoTuner.Calibration actual) {
        Gson gson = new com.google.gson.GsonBuilder().serializeSpecialFloatingPointValues().create();
        assertEquals(expected.success, actual.success);
        assertEquals(expected.failureReason, actual.failureReason);
        assertEquals(gson.toJson(expected.candidates), gson.toJson(actual.candidates));
        assertEquals(gson.toJson(expected.chosen), gson.toJson(actual.chosen));
        assertEquals(gson.toJson(expected.tradeoff), gson.toJson(actual.tradeoff));
    }

    /** Builds deterministic registered mono16 noise and stars; all callers borrow the returned read-only pixel arrays. */
    private static List<ImageFrame> frames() {
        List<ImageFrame> frames = new ArrayList<>();
        Random random = new Random(42);
        for (int frameIndex = 0; frameIndex < 6; frameIndex++) {
            short[][] pixels = new short[160][160];
            for (int row = 0; row < 160; row++) {
                for (int column = 0; column < 160; column++) {
                    double value = 1000 + 6 * random.nextGaussian();
                    for (int starRow = 25; starRow <= 135; starRow += 22) {
                        for (int starColumn = 25; starColumn <= 135; starColumn += 22) {
                            double distanceSquared = Math.pow(column - starColumn, 2) + Math.pow(row - starRow, 2);
                            value += 600 * Math.exp(-distanceSquared / (2 * 1.3 * 1.3));
                        }
                    }
                    pixels[row][column] = PixelEncoding.fromShiftedPositiveInt((int) Math.round(value));
                }
            }
            frames.add(new ImageFrame(frameIndex, "frame-" + frameIndex, pixels, frameIndex * 60_000L, 30_000));
        }
        return frames;
    }
}
