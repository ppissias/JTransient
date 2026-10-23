package io.github.ppissias.jtransient.execution;

import io.github.ppissias.jtransient.config.DetectionConfig;
import io.github.ppissias.jtransient.core.PixelEncoding;
import io.github.ppissias.jtransient.engine.CalibratedAutoTuner;
import io.github.ppissias.jtransient.engine.ImageFrame;
import io.github.ppissias.jtransient.engine.JTransientAutoTuner;
import io.github.ppissias.jtransient.engine.JTransientEngine;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;

import static org.junit.Assert.*;

/** Verifies additive failure context without changing legacy exceptions, scientific fallback or joined cancellation. */
public class OperationOutcomeTest {
    /** A successfully processed sequence with no accepted findings remains completed, not failed or cancelled. */
    @Test
    public void completedEmptyPipelineKeepsItsScientificResult() {
        try (var engine = new JTransientEngine(ExecutionPolicy.bounded(1))) {
            var outcome = OperationContext.capture(StageId.OPERATION, () -> engine.runPipeline(frames(), new DetectionConfig(), null));
            assertTrue(outcome instanceof OperationOutcome.Completed<?>);
            var result = ((OperationOutcome.Completed<io.github.ppissias.jtransient.engine.PipelineResult>) outcome).value();
            assertTrue(result.tracks.isEmpty());
            assertTrue(result.anomalies.isEmpty());
        }
    }

    /** A final percentage callback can still fail; terminal completion is only published after the operation actually returns. */
    @Test
    public void oneHundredPercentIsNotATerminalOutcome() {
        var problem = new IllegalStateException("final callback failed");
        try (var engine = new JTransientEngine(ExecutionPolicy.bounded(1))) {
            var result = OperationContext.capture(StageId.OPERATION, () -> engine.runPipeline(frames(), new DetectionConfig(), (percentage, message) -> {
                if (percentage == 100) throw problem;
            }));
            assertEquals(StageId.LISTENER, failure(result).stage());
            assertSame(problem, failure(result).cause());
        }
    }

    /** Fatal errors, even carried through a future transport wrapper, must not become ordinary failed outcomes. */
    @Test
    public void fatalErrorsRemainErrors() {
        var fatal = new OutOfMemoryError("injected, no allocation attempted");
        assertSame(fatal, assertThrows(OutOfMemoryError.class, () -> OperationContext.capture(StageId.OPERATION,
                () -> { throw new ExecutionException(fatal); })));
        assertNull(OperationContext.snapshot());
    }

    /** Worker callback failures identify the original non-contiguous sequence ID, not completed/retained-list position. */
    @Test
    public void pipelineListenerFailureCarriesItsOriginalFrameAndCause() throws Exception {
        var problem = new IllegalStateException("listener failed");
        try (var engine = new JTransientEngine(ExecutionPolicy.bounded(1))) {
            var outcome = OperationContext.capture(StageId.OPERATION, () -> engine.runPipeline(frames(), new DetectionConfig(), (percent, message) -> {
                if (message.startsWith("Extracting features")) throw problem;
            }));
            OperationFailure failure = failure(outcome);
            assertEquals(StageId.LISTENER, failure.stage());
            assertEquals(new FrameIdentity(10, "frame-10"), failure.frame());
            assertSame(problem, failure.cause());
            OperationFailedException failureException = assertThrows(OperationFailedException.class, () -> engine.runPipeline(frames(),
                    new DetectionConfig(), (percent, message) -> {
                        if (message.startsWith("Extracting features")) throw problem;
                    }));
            assertSame(problem, failureException.getCause());
        }
    }

    /** Master-only and transient-only wrappers preserve stage/frame labels at their different public boundaries. */
    @Test
    public void otherEngineEntryPointsCaptureWorkerContext() {
        var problem = new IllegalArgumentException("progress callback failed");
        try (var engine = new JTransientEngine(ExecutionPolicy.bounded(1))) {
            var master = OperationContext.capture(StageId.OPERATION, () -> engine.generateMasterStackWithDetails(frames(), new DetectionConfig(), (percent, message) -> {
                if (message.startsWith("Evaluating frame ")) throw problem;
            }, CancellationToken.NONE));
            assertEquals(StageId.LISTENER, failure(master).stage());
            assertEquals(10, failure(master).frame().sequenceIndex());
            assertSame(problem, failure(master).cause());
            var transients = OperationContext.capture(StageId.OPERATION, () -> engine.detectTransients(frames(), new DetectionConfig(), (percent, message) -> {
                if (message.startsWith("Extracting features")) throw problem;
            }, null, CancellationToken.NONE));
            assertEquals(StageId.LISTENER, failure(transients).stage());
            assertEquals(10, failure(transients).frame().sequenceIndex());
            var cancelled = new CancellationException("cancel in listener");
            var stopped = OperationContext.capture(StageId.OPERATION, () -> engine.runPipeline(frames(), new DetectionConfig(), (percent, message) -> { throw cancelled; }));
            assertTrue(stopped instanceof OperationOutcome.Cancelled<?>);
            assertSame(cancelled, ((OperationOutcome.Cancelled<?>) stopped).cause());
        }
    }

    /** Bad captured settings/input are runtime validation failures, while legacy calls retain their exception type. */
    @Test
    public void validationHasAStableStageAndOriginalException() throws Exception {
        DetectionConfig config = new DetectionConfig();
        config.detectionSigmaMultiplier = Double.NaN;
        try (var engine = new JTransientEngine()) {
            var invalid = failure(OperationContext.capture(StageId.OPERATION, () -> engine.runPipeline(frames(), config, null)));
            assertEquals(StageId.INPUT_VALIDATION, invalid.stage());
            assertTrue(invalid.cause() instanceof IllegalArgumentException);
            assertNull(invalid.frame());
            assertThrows(OperationFailedException.class, () -> engine.runPipeline(frames(), config, null));
            assertEquals(StageId.INPUT_VALIDATION,
                    failure(OperationContext.capture(StageId.OPERATION, () -> engine.runPipeline(new ArrayList<>(), new DetectionConfig(), null))).stage());
        }
    }

    /** Insufficient tuning evidence completes normally; execution failures and cancellation remain separate outcomes. */
    @Test
    public void tunerFallbackIsNotAnExecutionFailure() {
        DetectionConfig config = new DetectionConfig();
        var legacy = OperationContext.capture(StageId.OPERATION, () -> JTransientAutoTuner.tune(frames().subList(0, 4), config,
                JTransientAutoTuner.Options.defaults(), JTransientAutoTuner.AutoTuneProfile.MEDIUM, null, CancellationToken.NONE));
        assertTrue(legacy instanceof OperationOutcome.Completed<?>);
        assertFalse(((OperationOutcome.Completed<JTransientAutoTuner.AutoTunerResult>) legacy).value().success);
        var calibrated = OperationContext.capture(StageId.OPERATION, () -> CalibratedAutoTuner.tune(frames().subList(0, 4), config,
                CalibratedAutoTuner.Options.defaults(), JTransientAutoTuner.AutoTuneProfile.MEDIUM, null, CancellationToken.NONE));
        assertTrue(calibrated instanceof OperationOutcome.Completed<?>);
        assertFalse(((OperationOutcome.Completed<JTransientAutoTuner.AutoTunerResult>) calibrated).value().success);
        var cancelled = OperationContext.capture(StageId.OPERATION, () -> JTransientAutoTuner.tune(null, config, JTransientAutoTuner.Options.defaults(),
                JTransientAutoTuner.AutoTuneProfile.MEDIUM, null, () -> true));
        assertTrue(cancelled instanceof OperationOutcome.Cancelled<?>);
    }

    /** Calibrated result APIs keep their original failureCause, while the additive API exposes structured failure instead. */
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
        var outcome = OperationContext.capture(StageId.OPERATION, () -> CalibratedAutoTuner.calibrate(frames(), new DetectionConfig(), options.build(),
                (percent, message) -> { throw problem; }));
        assertEquals(StageId.LISTENER, failure(outcome).stage());
        assertSame(problem, failure(outcome).cause());
        String json = new com.google.gson.Gson().toJson(failure(outcome));
        assertTrue(json.contains("LISTENER"));
        assertFalse(json.contains("cause"));
    }

    /** Managed nested batches propagate context and keep the actual failing stage instead of their future transport wrapper. */
    @Test
    public void workerFailuresKeepTheirOriginalStageAndDoNotLeakToTheNextOperation() {
        var problem = new IllegalArgumentException("stack computation failed");
        try (var session = ExecutionSession.owned(ExecutionPolicy.bounded(1))) {
            var outcome = OperationContext.capture(StageId.OPERATION, () -> {
                try (var scope = session.open()) {
                    session.configureForWork(1, 1);
                    List<Callable<Void>> tasks = List.of(() -> OperationContext.compute(StageId.MEDIAN_STACK,
                            new FrameIdentity(99, "ninety-nine"), () -> { throw problem; }));
                    CancellableTasks.invokeAll(session.executor(), tasks);
                    return null;
                }
            });
            assertEquals(StageId.MEDIAN_STACK, failure(outcome).stage());
            assertEquals(99, failure(outcome).frame().sequenceIndex());
            assertSame(problem, failure(outcome).cause());
            var later = OperationContext.capture(StageId.OPERATION, () -> 42);
            assertEquals(42, ((OperationOutcome.Completed<Integer>) later).value().intValue());
        }
    }

    /** Nested capture scopes own separate failure cells; interruption stays cancelled with its original cause and flag. */
    @Test
    public void nestedCaptureAndInterruptionRestoreContext() {
        var outerProblem = new IllegalStateException("outer failure");
        var innerProblem = new IllegalStateException("inner failure");
        var outer = OperationContext.capture(StageId.TRACKING, () -> {
            var inner = OperationContext.capture(StageId.CALIBRATION, () -> { throw innerProblem; });
            assertSame(innerProblem, failure(inner).cause());
            throw outerProblem;
        });
        assertEquals(StageId.TRACKING, failure(outer).stage());
        assertSame(outerProblem, failure(outer).cause());
        var interrupted = new InterruptedException("stop");
        try {
            var result = OperationContext.capture(StageId.PHOTOMETRY, () -> { throw interrupted; });
            assertTrue(result instanceof OperationOutcome.Cancelled<?>);
            assertSame(interrupted, ((OperationOutcome.Cancelled<?>) result).cause().getCause());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
        assertNull(OperationContext.snapshot());
    }

    /** Extracts structured failure for assertions, failing clearly when work completed or cancelled instead. */
    private static OperationFailure failure(OperationOutcome<?> outcome) {
        assertTrue("Expected failure, got " + outcome, outcome instanceof OperationOutcome.Failed<?>);
        return ((OperationOutcome.Failed<?>) outcome).failure();
    }

    /** Builds five valid noisy mono16 frames with original indices intentionally unrelated to retained-list positions. */
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
