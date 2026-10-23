package io.github.ppissias.jtransient.execution;

import io.github.ppissias.jtransient.config.DetectionConfig;
import io.github.ppissias.jtransient.core.SourceExtractor;
import io.github.ppissias.jtransient.engine.ImageFrame;
import io.github.ppissias.jtransient.engine.JTransientEngine;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.*;

/** Exercises operation-owned diagnostics, legacy adapters and propagation/restoration on reusable workers. */
public class DiagnosticContextTest {

    /** Runtime core sinks cannot accidentally become serialized callback provenance. */
    @Test
    public void runtimeSinkIsExcludedFromJson() {
        String json = new com.google.gson.GsonBuilder().serializeNulls()
                .create().toJson(new DiagnosticOptions(true, message -> { }));
        assertTrue(json.contains("enabled"));
        assertFalse(json.contains("sink"));
    }

    /** Concurrent engines reproduce isolated text without crossing sinks, including extraction on owned workers. */
    @Test
    public void independentEnginesMatchTheirIsolatedDiagnosticStreams() throws Exception {
        List<String> firstControl = run(1, null);
        List<String> secondControl = run(2, null);
        CountDownLatch interleaved = new CountDownLatch(2);
        ExecutorService callers = Executors.newFixedThreadPool(2);
        try {
            Future<List<String>> first = callers.submit(() -> run(1, interleaved));
            Future<List<String>> second = callers.submit(() -> run(2, interleaved));
            assertEquals(firstControl, first.get(20, TimeUnit.SECONDS));
            assertEquals(secondControl, second.get(20, TimeUnit.SECONDS));
            assertTrue(firstControl.stream().anyMatch(message -> message.contains("BACKGROUND STATS")));
        } finally {
            callers.shutdownNow();
            assertTrue(callers.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    /** Nested worker batches inherit policy and pooled threads restore their prior unscoped state. */
    @Test
    public void workerPropagationAndNestedRestorationDoNotLeak() throws Exception {
        List<String> messages = new CopyOnWriteArrayList<>();
        ExecutorService workers = Executors.newSingleThreadExecutor();
        DiagnosticOptions options = new DiagnosticOptions(true, messages::add);
        try {
            try (DiagnosticContext.Scope scope = DiagnosticContext.open(options)) {
                CancellableTasks.invokeAll(workers, List.of(() -> {
                    assertSame(options, DiagnosticContext.currentOptions());
                    DiagnosticContext.println("outer");
                    try (DiagnosticContext.Scope nested = DiagnosticContext.open(new DiagnosticOptions(false, messages::add))) {
                        DiagnosticContext.printf("hidden %d", 1);
                    }
                    DiagnosticContext.printf("inner %d", 2);
                    return null;
                }));
            }
            assertFalse(workers.submit(DiagnosticContext::enabled).get());
            assertEquals(List.of("outer" + System.lineSeparator(), "inner 2"), messages);
        } finally {
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    /** Library defaults are silent and explicit per-run policy alone controls core output. */
    @Test
    public void silenceDoesNotDependOnFacadeGlobals() throws Exception {
        PrintStream previousOutput = System.out;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (PrintStream capture = new PrintStream(output)) {
            System.setOut(capture);
            try (JTransientEngine engine = new JTransientEngine(ExecutionPolicy.bounded(1))) {
                engine.runPipeline(frames(1), new DetectionConfig(), null);
            }
            assertEquals(0, output.size());
            try (var diagnostics = DiagnosticContext.open(DiagnosticOptions.standardOutput(true))) {
                SourceExtractor.extractSources(frames(1).getFirst().pixelData, 5, 5, new DetectionConfig());
            }
            assertTrue(output.size() > 0);
            output.reset();
            try (var diagnostics = DiagnosticContext.open(new DiagnosticOptions(false, message -> fail("Disabled sink called")))) {
                SourceExtractor.extractSources(frames(1).getFirst().pixelData, 5, 5, new DetectionConfig());
            }
            assertEquals(0, output.size());
        } finally {
            System.setOut(previousOutput);
        }
    }

    /** Sink failures retain their original cause/frame and leave a borrowing executor usable after joined cleanup. */
    @Test
    public void sinkFailureIsAnOrdinaryStructuredFailureWithRestoredWorkers() throws Exception {
        RuntimeException original = new IllegalStateException("diagnostic failure");
        ExecutorService workers = Executors.newSingleThreadExecutor();
        try {
            try (DiagnosticContext.Scope scope = DiagnosticContext.open(new DiagnosticOptions(true, message -> {
                if (message.contains("BACKGROUND STATS")) {
                    throw original;
                }
            })); JTransientEngine engine = new JTransientEngine(ExecutionPolicy.bounded(1), workers)) {
                OperationOutcome<?> outcome = engine.runPipelineOutcome(frames(1), new DetectionConfig(), null);
                OperationOutcome.Failed<?> failure = (OperationOutcome.Failed<?>) outcome;
                assertSame(original, failure.failure().cause());
                assertEquals(StageId.FRAME_EXTRACTION, failure.failure().stage());
                assertEquals(new FrameIdentity(10, "frame-10"), failure.failure().frame());
            }
            assertFalse(workers.submit(DiagnosticContext::enabled).get());
        } finally {
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    /** Invalid scope closure cannot corrupt the current policy, and valid repeated close is harmless. */
    @Test
    public void scopesAreThreadConfinedAndLifo() throws Exception {
        DiagnosticOptions options = new DiagnosticOptions(true, message -> { });
        DiagnosticContext.Scope outer = DiagnosticContext.open(options);
        DiagnosticContext.Scope inner = DiagnosticContext.open(new DiagnosticOptions(false, message -> { }));
        ExecutorService otherThread = Executors.newSingleThreadExecutor();
        try {
            assertThrows(IllegalStateException.class, outer::close);
            assertTrue(otherThread.submit(() -> {
                try {
                    inner.close();
                    return false;
                } catch (IllegalStateException expected) {
                    return true;
                }
            }).get());
            assertFalse(DiagnosticContext.enabled());
            inner.close();
            assertSame(options, DiagnosticContext.currentOptions());
            outer.close();
            outer.close();
        } finally {
            inner.close();
            outer.close();
            otherThread.shutdownNow();
            assertTrue(otherThread.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    /** Runs a deterministic single-worker engine with an optional callback barrier to force operation overlap. */
    private static List<String> run(int frameCount, CountDownLatch interleaved) throws Exception {
        List<String> messages = new CopyOnWriteArrayList<>();
        AtomicBoolean firstProgress = new AtomicBoolean(true);
        try (DiagnosticContext.Scope scope = DiagnosticContext.open(new DiagnosticOptions(true, messages::add));
             JTransientEngine engine = new JTransientEngine(ExecutionPolicy.bounded(1))) {
            engine.runPipeline(frames(frameCount), new DetectionConfig(), (percentage, message) -> {
                if (interleaved != null && firstProgress.compareAndSet(true, false)) {
                    interleaved.countDown();
                    try {
                        assertTrue(interleaved.await(10, TimeUnit.SECONDS));
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(interrupted);
                    }
                }
            });
        }
        return messages;
    }

    /** Creates tiny borrowed noisy images with non-contiguous original sequence identities. */
    private static List<ImageFrame> frames(int frameCount) {
        List<ImageFrame> frames = new ArrayList<>();
        for (int position = 0; position < frameCount; position++) {
            short[][] pixels = new short[32][32];
            Random random = new Random(100 + position);
            for (short[] row : pixels) {
                for (int column = 0; column < row.length; column++) {
                    row[column] = (short) (1000 + random.nextInt(20));
                }
            }
            int sequenceIndex = 10 + position * 10;
            frames.add(new ImageFrame(sequenceIndex, "frame-" + sequenceIndex, pixels, -1, -1));
        }
        return frames;
    }
}
