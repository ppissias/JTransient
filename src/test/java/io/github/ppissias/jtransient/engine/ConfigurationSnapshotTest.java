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

    /** Preserves legacy base-reference fallbacks while both snapshot APIs return independent fallback settings. */
    @Test
    public void legacyAndSnapshotTunerFallbackIdentitiesAreDeliberatelyDifferent() {
        DetectionConfig base = new DetectionConfig();
        var frames = frames(2);
        var legacy = JTransientAutoTuner.tune(frames, base, JTransientAutoTuner.AutoTuneProfile.MEDIUM, null);
        var snapshot = JTransientAutoTuner.tuneSnapshot(frames, base, JTransientAutoTuner.AutoTuneProfile.MEDIUM, null);
        assertFalse(legacy.success);
        assertFalse(snapshot.success);
        assertSame(base, legacy.optimizedConfig);
        assertNotSame(base, snapshot.optimizedConfig);
        var calibratedLegacy = CalibratedAutoTuner.tune(frames, base, JTransientAutoTuner.AutoTuneProfile.MEDIUM, null);
        var calibratedSnapshot = CalibratedAutoTuner.tuneSnapshot(frames, base, JTransientAutoTuner.AutoTuneProfile.MEDIUM, null);
        assertSame(base, calibratedLegacy.optimizedConfig);
        assertNotSame(base, calibratedSnapshot.optimizedConfig);
        base.qualitySigmaMultiplier = 13;
        assertEquals(5, snapshot.optimizedConfig.qualitySigmaMultiplier, 0);
        assertEquals(5, calibratedSnapshot.optimizedConfig.qualitySigmaMultiplier, 0);
    }

    /** Shows that edits from a progress callback cannot leak into a previously captured fallback. */
    @Test
    public void tunerProgressCannotChangeTheCapturedFallbackValues() {
        DetectionConfig base = new DetectionConfig();
        var result = JTransientAutoTuner.tuneSnapshot(frames(2), base, JTransientAutoTuner.AutoTuneProfile.MEDIUM,
                (percent, message) -> base.masterSigmaMultiplier = 17);
        assertEquals(17, base.masterSigmaMultiplier, 0);
        assertEquals(2.25, result.optimizedConfig.masterSigmaMultiplier, 0);
    }

    /** Ensures consumed invalid settings are rejected with field IDs before workers or progress callbacks start. */
    @Test
    public void invalidActiveValuesAreRejectedBeforeProgressWithStructuredIssues() throws Exception {
        DetectionConfig config = new DetectionConfig();
        config.qualitySigmaMultiplier = Double.POSITIVE_INFINITY;
        AtomicBoolean progressed = new AtomicBoolean();
        try (JTransientEngine engine = new JTransientEngine()) {
            var failure = assertThrows(DetectionConfigRules.InvalidConfigurationException.class, () ->
                    engine.generateMasterStack(frames(5), config, (percent, message) -> progressed.set(true)));
            assertEquals("qualitySigmaMultiplier", failure.issues().getFirst().fields().getFirst());
            assertFalse(progressed.get());
        }
    }

    /** Distinguishes invalid tuning inputs from inactive or sweep-overridden settings that tuning does not consume. */
    @Test
    public void bothTunersRejectNonFiniteConsumedSettingsButIgnoreOverriddenAndInactiveOnes() {
        DetectionConfig config = new DetectionConfig();
        config.bgClippingFactor = Double.NaN;
        AtomicBoolean progressed = new AtomicBoolean();
        assertThrows(DetectionConfigRules.InvalidConfigurationException.class, () -> JTransientAutoTuner.tuneSnapshot(
                frames(5), config, JTransientAutoTuner.AutoTuneProfile.MEDIUM, (percent, message) -> progressed.set(true)));
        assertThrows(DetectionConfigRules.InvalidConfigurationException.class, () -> CalibratedAutoTuner.tuneSnapshot(
                frames(5), config, JTransientAutoTuner.AutoTuneProfile.MEDIUM, (percent, message) -> progressed.set(true)));
        assertFalse(progressed.get());
        config.bgClippingFactor = 3;
        config.growSigmaMultiplier = Double.NaN;
        config.variableMinAmplitudeMag = Double.NaN;
        assertFalse(JTransientAutoTuner.tuneSnapshot(frames(2), config, JTransientAutoTuner.AutoTuneProfile.MEDIUM, null).success);
        assertFalse(CalibratedAutoTuner.tuneSnapshot(frames(2), config, JTransientAutoTuner.AutoTuneProfile.MEDIUM, null).success);
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
