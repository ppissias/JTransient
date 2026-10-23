package io.github.ppissias.jtransient.engine;

import io.github.ppissias.jtransient.config.DetectionConfig;
import io.github.ppissias.jtransient.core.PixelEncoding;
import io.github.ppissias.jtransient.execution.CancellationScope;
import io.github.ppissias.jtransient.execution.FrameIdentity;
import io.github.ppissias.jtransient.execution.OperationFailedException;
import io.github.ppissias.jtransient.execution.OperationFailure;
import io.github.ppissias.jtransient.execution.StageId;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CancellationException;

import static org.junit.Assert.*;

/** Verifies that engine and tuner failures name their stage and original frame, apart from results and cancellation. */
public class OperationFailureTest {
    /** A sequence processed without findings is an ordinary result, not a failure. */
    @Test
    public void completedEmptyPipelineKeepsItsScientificResult() {
        try (var engine = new JTransientEngine(1)) {
            PipelineResult result = engine.runPipeline(frames(), new DetectionConfig(), null);
            assertTrue(result.tracks.isEmpty());
            assertTrue(result.anomalies.isEmpty());
        }
    }

    /** The final percentage callback can still fail: progress is not a terminal outcome. */
    @Test
    public void oneHundredPercentIsNotATerminalOutcome() {
        var problem = new IllegalStateException("final callback failed");
        try (var engine = new JTransientEngine(1)) {
            OperationFailure failure = assertThrows(OperationFailedException.class, () -> engine.runPipeline(frames(),
                    new DetectionConfig(), (percentage, message) -> {
                        if (percentage == 100) throw problem;
                    })).failure();
            assertEquals(StageId.LISTENER, failure.stage());
            assertSame(problem, failure.cause());
        }
    }

    /** A worker callback failure names the frame's original sequence index, not its position in the list. */
    @Test
    public void pipelineListenerFailureCarriesItsOriginalFrameAndCause() {
        var problem = new IllegalStateException("listener failed");
        try (var engine = new JTransientEngine(1)) {
            OperationFailedException thrown = assertThrows(OperationFailedException.class, () -> engine.runPipeline(frames(),
                    new DetectionConfig(), (percent, message) -> {
                        if (message.startsWith("Extracting features")) throw problem;
                    }));
            assertEquals(StageId.LISTENER, thrown.failure().stage());
            assertEquals(new FrameIdentity(10, "frame-10"), thrown.failure().frame());
            assertSame(problem, thrown.failure().cause());
            assertSame(problem, thrown.getCause());
        }
    }

    /** The master-stack and transient-only entry points label their failures the same way; cancellation stays itself. */
    @Test
    public void otherEngineEntryPointsLabelWorkerFailures() {
        var problem = new IllegalArgumentException("progress callback failed");
        try (var engine = new JTransientEngine(1)) {
            OperationFailure master = assertThrows(OperationFailedException.class, () -> engine.generateMasterStackWithDetails(
                    frames(), new DetectionConfig(), (percent, message) -> {
                        if (message.startsWith("Evaluating frame ")) throw problem;
                    })).failure();
            assertEquals(StageId.LISTENER, master.stage());
            assertEquals(10, master.frame().sequenceIndex());
            assertSame(problem, master.cause());
            OperationFailure transients = assertThrows(OperationFailedException.class, () -> engine.detectTransients(
                    frames(), new DetectionConfig(), (percent, message) -> {
                        if (message.startsWith("Extracting features")) throw problem;
                    })).failure();
            assertEquals(StageId.LISTENER, transients.stage());
            assertEquals(10, transients.frame().sequenceIndex());
            var cancelled = new CancellationException("cancel in listener");
            assertSame(cancelled, assertThrows(CancellationException.class, () -> engine.runPipeline(frames(),
                    new DetectionConfig(), (percent, message) -> { throw cancelled; })));
        }
    }

    /** Invalid settings or input fail at input validation with the original exception as the cause. */
    @Test
    public void validationHasAStableStageAndOriginalException() {
        DetectionConfig config = new DetectionConfig();
        config.detectionSigmaMultiplier = Double.NaN;
        try (var engine = new JTransientEngine()) {
            OperationFailure invalid = assertThrows(OperationFailedException.class,
                    () -> engine.runPipeline(frames(), config, null)).failure();
            assertEquals(StageId.INPUT_VALIDATION, invalid.stage());
            assertTrue(invalid.cause() instanceof IllegalArgumentException);
            assertNull(invalid.frame());
            assertEquals(StageId.INPUT_VALIDATION, assertThrows(OperationFailedException.class,
                    () -> engine.runPipeline(new ArrayList<>(), new DetectionConfig(), null)).failure().stage());
        }
    }

    /** Insufficient tuning evidence is an unsuccessful result; cancellation is a cancellation. */
    @Test
    public void tunerFallbackIsNotAnExecutionFailure() {
        DetectionConfig config = new DetectionConfig();
        AutoTunerResult calibrated = CalibratedAutoTuner.tune(frames().subList(0, 4), config,
                CalibratedAutoTuner.Options.defaults(), AutoTuneProfile.MEDIUM, null);
        assertFalse(calibrated.success);
        try (var scope = CancellationScope.open(() -> true)) {
            assertThrows(CancellationException.class, () -> CalibratedAutoTuner.tune(null, config,
                    CalibratedAutoTuner.Options.defaults(), AutoTuneProfile.MEDIUM, null));
        }
    }

    /** A runtime failure during calibration is a labelled failure, not insufficient evidence. */
    @Test
    public void calibrationRuntimeFailureDoesNotMasqueradeAsInsufficientEvidence() {
        var options = CalibratedAutoTuner.Options.builder();
        options.preferredCropSize = 64;
        options.minCropSize = 32;
        options.minCropBorderMargin = 8;
        options.cropPaddingClearance = 8;
        var problem = new IllegalStateException("measurement callback failed");
        var thrown = assertThrows(OperationFailedException.class,
                () -> CalibratedAutoTuner.calibrate(frames(), new DetectionConfig(), options.build(),
                        (percent, message) -> { throw problem; }));
        assertSame(problem, thrown.getCause());
        assertEquals(StageId.LISTENER, thrown.failure().stage());
        String json = new com.google.gson.Gson().toJson(thrown.failure());
        assertTrue(json.contains("LISTENER"));
        assertFalse(json.contains("cause"));
    }

    /** Builds five valid noisy mono16 frames with original indices intentionally unrelated to list positions. */
    private static List<ImageFrame> frames() {
        Random random = new Random(42);
        List<ImageFrame> frames = new ArrayList<>();
        for (int index : new int[]{70, 40, 10, 155, 110}) {
            short[][] pixels = new short[80][80];
            for (int row = 0; row < 80; row++) {
                for (int column = 0; column < 80; column++) {
                    pixels[row][column] = PixelEncoding.fromShiftedPositiveInt((int) Math.round(1000 + 8 * random.nextGaussian()));
                }
            }
            frames.add(new ImageFrame(index, "frame-" + index, pixels, index * 1000L, 1000));
        }
        return frames;
    }
}
