package io.github.ppissias.jtransient.engine;

import io.github.ppissias.jtransient.config.DetectionConfig;
import io.github.ppissias.jtransient.config.DetectionConfigRules;
import io.github.ppissias.jtransient.core.PixelEncoding;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.*;

/** Exercises operation-boundary capture, structured rejection and legacy versus detached tuner fallback ownership. */
public class ConfigurationSnapshotTest {
    /** Pauses after capture, mutates caller settings, then verifies the active run retains its original stage choices. */
    @Test(timeout = 20_000)
    public void aPausedRunUsesTheCapturedConfigurationAfterItsCallerEditsTheOriginal() throws Exception {
        DetectionConfig config = new DetectionConfig();
        config.enableSlowMoverDetection = false;
        config.enableVariableStarDetection = false;
        CountDownLatch captured = new CountDownLatch(1);
        CountDownLatch proceed = new CountDownLatch(1);
        AtomicBoolean first = new AtomicBoolean(true);
        var caller = Executors.newSingleThreadExecutor();
        try (JTransientEngine engine = new JTransientEngine()) {
            var running = caller.submit(() -> engine.runPipeline(frames(5), config, (percent, message) -> {
                if (first.compareAndSet(true, false)) {
                    captured.countDown();
                    try { assertTrue(proceed.await(5, TimeUnit.SECONDS)); }
                    catch (InterruptedException failure) { throw new IllegalStateException(failure); }
                }
            }));
            assertTrue(captured.await(5, TimeUnit.SECONDS));
            config.enableSlowMoverDetection = true;
            config.enableVariableStarDetection = true;
            config.growSigmaMultiplier = Double.NaN;
            config.variableScoreSigma = Double.NaN;
            proceed.countDown();
            PipelineResult result = running.get(10, TimeUnit.SECONDS);
            assertNull(result.slowMoverStackData);
            assertTrue(result.variableStarAnalysis.candidates.isEmpty());
            assertTrue(result.variableStarAnalysis.frames.isEmpty());
            assertTrue(Double.isNaN(config.growSigmaMultiplier));
            assertTrue(config.enableSlowMoverDetection);
        } finally {
            proceed.countDown();
            caller.shutdownNow();
            assertTrue(caller.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    /** The tuner returns independent fallback settings without aliases to caller state. */
    @Test
    public void tunerFallbackIsDetached() {
        DetectionConfig base = new DetectionConfig();
        var frames = frames(2);
        var first = CalibratedAutoTuner.tune(frames, base, AutoTuneProfile.MEDIUM, null);
        var snapshot = CalibratedAutoTuner.tune(frames, base, AutoTuneProfile.MEDIUM, null);
        assertFalse(first.success);
        assertFalse(snapshot.success);
        assertNotSame(base, first.optimizedConfig);
        assertNotSame(base, snapshot.optimizedConfig);
        assertNotSame(first.optimizedConfig, snapshot.optimizedConfig);
        base.qualitySigmaMultiplier = 13;
        assertEquals(5, snapshot.optimizedConfig.qualitySigmaMultiplier, 0);
    }

    /** Ensures consumed invalid settings are rejected with field IDs before workers or progress callbacks start. */
    @Test
    public void invalidActiveValuesAreRejectedBeforeProgressWithStructuredIssues() throws Exception {
        DetectionConfig config = new DetectionConfig();
        config.qualitySigmaMultiplier = Double.POSITIVE_INFINITY;
        AtomicBoolean progressed = new AtomicBoolean();
        try (JTransientEngine engine = new JTransientEngine()) {
            var failure = assertThrows(io.github.ppissias.jtransient.execution.OperationFailedException.class, () ->
                    engine.generateMasterStack(frames(5), config, (percent, message) -> progressed.set(true)));
            assertEquals("qualitySigmaMultiplier", ((DetectionConfigRules.InvalidConfigurationException) failure.getCause()).issues().getFirst().fields().getFirst());
            assertFalse(progressed.get());
        }
    }

    /** Distinguishes invalid tuning inputs from inactive or sweep-overridden settings that tuning does not consume. */
    @Test
    public void tunerRejectsNonFiniteConsumedSettingsButIgnoresOverriddenAndInactiveOnes() {
        DetectionConfig config = new DetectionConfig();
        config.bgClippingFactor = Double.NaN;
        AtomicBoolean progressed = new AtomicBoolean();
        assertThrows(io.github.ppissias.jtransient.execution.OperationFailedException.class, () -> CalibratedAutoTuner.tune(
                frames(5), config, AutoTuneProfile.MEDIUM, (percent, message) -> progressed.set(true)));
        assertFalse(progressed.get());
        config.bgClippingFactor = 3;
        config.growSigmaMultiplier = Double.NaN;
        config.variableMinAmplitudeMag = Double.NaN;
        assertFalse(CalibratedAutoTuner.tune(frames(2), config, AutoTuneProfile.MEDIUM, null).success);
    }

    /** Creates deterministic, independent noisy frames with consistent timestamps/exposures for boundary tests. */
    private static List<ImageFrame> frames(int count) {
        Random random = new Random(19);
        List<ImageFrame> frames = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            short[][] pixels = new short[80][80];
            for (short[] row : pixels) {
                for (int column = 0; column < row.length; column++) {
                    row[column] = PixelEncoding.fromShiftedPositiveInt(1000 + random.nextInt(61));
                }
            }
            frames.add(new ImageFrame(index, "frame-" + index, pixels, index * 60_000L, 60_000));
        }
        return frames;
    }

    /** Protects the -1-only missing-time sentinel so valid pre-epoch timestamps do not activate geometric validation. */
    @Test
    public void negativeEpochTimestampsDoNotActivateGeometricFallbackValidation() throws Exception {
        DetectionConfig config = new DetectionConfig();
        config.enableGeometricTrackLinking = false;
        config.maxJumpPixels = Double.NaN;
        List<ImageFrame> beforeEpoch = frames(5).stream().map(frame -> new ImageFrame(frame.sequenceIndex,
                frame.filename, frame.pixelData, frame.timestamp - 1_000_000, frame.exposureDuration)).toList();
        try (JTransientEngine engine = new JTransientEngine()) {
            assertNotNull(engine.runPipeline(new ArrayList<>(beforeEpoch), config, null));
        }
    }
}
