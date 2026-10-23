package io.github.ppissias.jtransient.engine;

import io.github.ppissias.jtransient.config.DetectionConfig;
import io.github.ppissias.jtransient.core.MasterMapGenerator;
import io.github.ppissias.jtransient.core.PixelEncoding;
import io.github.ppissias.jtransient.core.TrackLinker;
import io.github.ppissias.jtransient.execution.CancellationScope;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class JTransientCancellationTest {
    @Test
    public void extractionCancellationStopsProgressAndTheEngineCanBeReused() throws Exception {
        AtomicBoolean cancelled = new AtomicBoolean();
        AtomicInteger extracted = new AtomicInteger();
        AtomicBoolean completed = new AtomicBoolean();
        try (JTransientEngine engine = new JTransientEngine()) {
            assertThrows(CancellationException.class, () -> engine.runPipeline(frames(8, 80), new DetectionConfig(),
                    (percent, message) -> {
                        if (message.startsWith("Extracting features")) {
                            extracted.incrementAndGet();
                            cancelled.set(true);
                        }
                        if (percent == 100) completed.set(true);
                    }, null, cancelled::get));
            int progressAtReturn = extracted.get();
            assertTrue(progressAtReturn > 0);
            assertFalse(completed.get());
            assertThrows(io.github.ppissias.jtransient.execution.OperationFailedException.class,
                    () -> engine.runPipeline(new ArrayList<>(), new DetectionConfig(), null));
            engine.generateMasterStackWithDetails(frames(5, 80), new DetectionConfig(), null);
            assertEquals(progressAtReturn, extracted.get());
        }
    }

    @Test
    public void calibratedTuningPropagatesListenerCancellationInsteadOfReturningFallback() {
        assertThrows(CancellationException.class, () -> CalibratedAutoTuner.tune(frames(5, 900), new DetectionConfig(),
                AutoTuneProfile.MEDIUM,
                (percent, message) -> { throw new CancellationException("stop calibration"); }));
    }

    @Test
    public void trackingCancellationDoesNotReturnPartialTracks() {
        AtomicBoolean cancelled = new AtomicBoolean();
        try (CancellationScope scope = CancellationScope.open(cancelled::get)) {
            assertThrows(CancellationException.class, () -> TrackLinker.findMovingObjects(
                    List.of(List.of(), List.of(), List.of(), List.of()), List.of(), new DetectionConfig(),
                    (percent, message) -> {
                        if (message.startsWith("Vetoing stationary sources")) cancelled.set(true);
                    }, 80, 80));
        }
        assertTrue(cancelled.get());
    }

    @Test
    public void runtimeTuningFailureRetainsItsCauseAndRemainsJsonSerializable() {
        IllegalStateException problem = new IllegalStateException("injected calibration failure");
        var failure = assertThrows(io.github.ppissias.jtransient.execution.OperationFailedException.class,
                () -> CalibratedAutoTuner.tune(frames(5, 900), new DetectionConfig(),
                        AutoTuneProfile.MEDIUM, (percent, message) -> { throw problem; }));
        assertSame(problem, failure.getCause());
        assertEquals(io.github.ppissias.jtransient.execution.StageId.LISTENER, failure.failure().stage());
        String saved = new com.google.gson.Gson().toJson(failure.failure());
        assertFalse(saved.contains("cause"));
    }

    @Test
    public void tunerHonorsCancellationBeforeReadingInputs() {
        assertThrows(CancellationException.class, () -> CalibratedAutoTuner.tune(null, new DetectionConfig(),
                AutoTuneProfile.MEDIUM, null, () -> true));
    }

    @Test
    public void masterStackCancellationIsPropagatedToRowWorkers() {
        AtomicInteger checks = new AtomicInteger();
        try (CancellationScope scope = CancellationScope.open(() -> checks.incrementAndGet() > 12)) {
            assertThrows(CancellationException.class, () -> MasterMapGenerator.createMedianMasterStack(frames(5, 80)));
        }
    }

    @Test
    public void overlappingOperationsAndClosingDuringAnOperationAreRejected() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch proceed = new CountDownLatch(1);
        AtomicBoolean cancelled = new AtomicBoolean();
        ExecutorService caller = Executors.newSingleThreadExecutor();
        JTransientEngine engine = new JTransientEngine();
        try {
            Future<?> operation = caller.submit(() -> engine.runPipeline(frames(5, 80), new DetectionConfig(),
                    (percent, message) -> {
                        started.countDown();
                        try {
                            if (!proceed.await(5, TimeUnit.SECONDS)) throw new AssertionError("test did not release operation");
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new CancellationException();
                        }
                    }, null, cancelled::get));
            assertTrue(started.await(5, TimeUnit.SECONDS));
            assertThrows(IllegalStateException.class, () -> engine.runPipeline(frames(5, 80), new DetectionConfig(), null));
            assertThrows(IllegalStateException.class, engine::close);
            cancelled.set(true);
            proceed.countDown();
            assertThrows(java.util.concurrent.ExecutionException.class, () -> operation.get(5, TimeUnit.SECONDS));
        } finally {
            proceed.countDown();
            caller.shutdownNow();
            assertTrue(caller.awaitTermination(5, TimeUnit.SECONDS));
            engine.close();
        }
    }

    private static List<ImageFrame> frames(int count, int size) {
        Random random = new Random(19);
        List<ImageFrame> frames = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            short[][] pixels = new short[size][size];
            for (int row = 0; row < size; row++) {
                for (int column = 0; column < size; column++) {
                    pixels[row][column] = PixelEncoding.fromShiftedPositiveInt(1000 + random.nextInt(61));
                }
            }
            frames.add(new ImageFrame(index, "frame-" + index, pixels, index * 60_000L, 60_000));
        }
        return frames;
    }
}
