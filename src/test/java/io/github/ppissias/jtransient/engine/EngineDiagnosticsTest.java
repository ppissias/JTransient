package io.github.ppissias.jtransient.engine;

import io.github.ppissias.jtransient.config.DetectionConfig;
import io.github.ppissias.jtransient.core.SourceExtractor;
import io.github.ppissias.jtransient.execution.DiagnosticContext;
import io.github.ppissias.jtransient.execution.DiagnosticOptions;
import io.github.ppissias.jtransient.execution.FrameIdentity;
import io.github.ppissias.jtransient.execution.OperationFailedException;
import io.github.ppissias.jtransient.execution.StageId;
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

/** Checks that each engine operation writes diagnostics only to its own caller's sink. */
public class EngineDiagnosticsTest {

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

    /** Library defaults are silent and explicit per-run policy alone controls core output. */
    @Test
    public void silenceDoesNotDependOnFacadeGlobals() throws Exception {
        PrintStream previousOutput = System.out;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (PrintStream capture = new PrintStream(output)) {
            System.setOut(capture);
            try (JTransientEngine engine = new JTransientEngine(1)) {
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

    /** A sink failure is an ordinary structured failure that keeps its original cause and frame. */
    @Test
    public void sinkFailureIsAnOrdinaryStructuredFailure() throws Exception {
        RuntimeException original = new IllegalStateException("diagnostic failure");
        try (DiagnosticContext.Scope scope = DiagnosticContext.open(new DiagnosticOptions(true, message -> {
            if (message.contains("BACKGROUND STATS")) {
                throw original;
            }
        })); JTransientEngine engine = new JTransientEngine(1)) {
            OperationFailedException failure = assertThrows(OperationFailedException.class,
                    () -> engine.runPipeline(frames(1), new DetectionConfig(), null));
            assertSame(original, failure.failure().cause());
            assertEquals(StageId.FRAME_EXTRACTION, failure.failure().stage());
            assertEquals(new FrameIdentity(10, "frame-10"), failure.failure().frame());
        }
        assertFalse(DiagnosticContext.enabled());
    }

    /** Runs a deterministic single-worker engine with an optional callback barrier to force operation overlap. */
    private static List<String> run(int frameCount, CountDownLatch interleaved) throws Exception {
        List<String> messages = new CopyOnWriteArrayList<>();
        AtomicBoolean firstProgress = new AtomicBoolean(true);
        try (DiagnosticContext.Scope scope = DiagnosticContext.open(new DiagnosticOptions(true, messages::add));
             JTransientEngine engine = new JTransientEngine(1)) {
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
