package io.github.ppissias.jtransient.execution;

import org.junit.Test;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;

import static org.junit.Assert.*;

/** Verifies the stage/frame labels of failures, through worker batches, and the terminal exception contract. */
public class OperationContextTest {
    /** Fatal errors, even carried through a future transport wrapper, stay errors. */
    @Test
    public void fatalErrorsRemainErrors() {
        var fatal = new OutOfMemoryError("injected, no allocation attempted");
        assertSame(fatal, assertThrows(OutOfMemoryError.class, () -> OperationContext.execute(StageId.OPERATION,
                () -> { throw new ExecutionException(fatal); })));
        assertNull(OperationContext.snapshot());
    }

    /** A worker failure keeps the stage and frame where it happened and does not leak into the next operation. */
    @Test
    public void workerFailuresKeepTheirOriginalStageAndDoNotLeakToTheNextOperation() {
        var problem = new IllegalArgumentException("stack computation failed");
        try (var pool = new WorkerPool(1)) {
            var failure = assertThrows(OperationFailedException.class, () -> OperationContext.execute(StageId.OPERATION, () -> {
                try (var scope = pool.open()) {
                    pool.limitWork(1, 1);
                    List<Callable<Void>> tasks = List.of(() -> OperationContext.compute(StageId.MEDIAN_STACK,
                            new FrameIdentity(99, "ninety-nine"), () -> { throw problem; }));
                    WorkerPool.invokeAll(tasks);
                    return null;
                }
            })).failure();
            assertEquals(StageId.MEDIAN_STACK, failure.stage());
            assertEquals(99, failure.frame().sequenceIndex());
            assertSame(problem, failure.cause());
            assertEquals(42, OperationContext.execute(StageId.OPERATION, () -> 42).intValue());
        }
    }

    /** Nested operations own separate failure cells; interruption ends as cancellation with its cause and flag. */
    @Test
    public void nestedOperationsAndInterruptionRestoreTheContext() {
        var outerProblem = new IllegalStateException("outer failure");
        var innerProblem = new IllegalStateException("inner failure");
        var outer = assertThrows(OperationFailedException.class, () -> OperationContext.execute(StageId.TRACKING, () -> {
            var inner = assertThrows(OperationFailedException.class,
                    () -> OperationContext.execute(StageId.CALIBRATION, () -> { throw innerProblem; }));
            assertSame(innerProblem, inner.failure().cause());
            assertEquals(StageId.CALIBRATION, inner.failure().stage());
            throw outerProblem;
        })).failure();
        assertEquals(StageId.TRACKING, outer.stage());
        assertSame(outerProblem, outer.cause());
        var interrupted = new InterruptedException("stop");
        try {
            var cancelled = assertThrows(CancellationException.class,
                    () -> OperationContext.execute(StageId.PHOTOMETRY, () -> { throw interrupted; }));
            assertSame(interrupted, cancelled.getCause());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
        assertNull(OperationContext.snapshot());
    }

    /** The structured failure serializes its stage and message but never the in-memory cause. */
    @Test
    public void failureJsonHasNoCause() {
        var failure = assertThrows(OperationFailedException.class, () -> OperationContext.execute(StageId.LISTENER,
                () -> { throw new IllegalStateException("callback failed"); })).failure();
        String json = new com.google.gson.Gson().toJson(failure);
        assertTrue(json.contains("LISTENER"));
        assertFalse(json.contains("cause"));
    }
}
