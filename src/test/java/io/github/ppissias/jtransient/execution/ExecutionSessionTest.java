package io.github.ppissias.jtransient.execution;

import io.github.ppissias.jtransient.config.DetectionConfig;
import io.github.ppissias.jtransient.core.MasterMapGenerator;
import io.github.ppissias.jtransient.engine.ImageFrame;
import io.github.ppissias.jtransient.engine.JTransientEngine;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/** Checks worker ceilings, nested stack safety, ordered results, ownership and completion-before-return contracts. */
public class ExecutionSessionTest {
    /** Bounds submitted work despite a wider borrowed pool and preserves original task/result association. */
    @Test
    public void borrowedPoolHonorsThePolicyAndSurvivesClose() throws Exception {
        try (var executor = Executors.newFixedThreadPool(8)) {
            AtomicInteger active = new AtomicInteger();
            AtomicInteger peak = new AtomicInteger();
            try (var session = ExecutionSession.borrowed(ExecutionPolicy.bounded(2), executor);
                 var scope = session.open()) {
                session.configureForWork(20, 1);
                List<Callable<Integer>> tasks = new ArrayList<>();
                for (int index = 0; index < 20; index++) {
                    int value = index;
                    tasks.add(() -> {
                        peak.accumulateAndGet(active.incrementAndGet(), Math::max);
                        try {
                            Thread.sleep(2);
                            return value;
                        } finally {
                            active.decrementAndGet();
                        }
                    });
                }
                var results = CancellableTasks.invokeAll(executor, tasks);
                for (int index = 0; index < results.size(); index++) assertEquals(index, results.get(index).get().intValue());
                assertTrue(peak.get() <= 2);
                assertEquals(0, active.get());
                assertThrows(IllegalStateException.class, session::close);
            }
            assertFalse(executor.isShutdown());
            assertEquals(7, executor.submit(() -> 7).get().intValue());
        }
        assertNull(ExecutionSession.current());
    }

    /** Runs nested row batches on a single borrowed worker, where re-submission would otherwise deadlock. */
    @Test(timeout = 10000)
    public void nestedStackBatchesStayOnTheManagedWorker() throws Exception {
        List<ImageFrame> frames = List.of(
                new ImageFrame(0, "first", new short[][]{{10, 20}, {30, 40}}, 0, 1000),
                new ImageFrame(1, "second", new short[][]{{11, 21}, {31, 41}}, 1000, 1000));
        try (var executor = Executors.newSingleThreadExecutor();
             var session = ExecutionSession.borrowed(ExecutionPolicy.bounded(1), executor);
             var scope = session.open()) {
            session.configureForWork(2, 4);
            var results = CancellableTasks.<short[][]>invokeAll(executor, List.of(() -> {
                assertSame(session, ExecutionSession.current());
                var nested = CancellableTasks.invokeAll(executor, List.of(() -> Thread.currentThread()));
                assertSame(Thread.currentThread(), nested.get(0).get());
                assertArrayEquals(new short[]{10, 20}, MasterMapGenerator.createMedianMasterStack(frames)[0]);
                return MasterMapGenerator.createMaximumMasterStack(frames);
            }));
            assertArrayEquals(new short[]{31, 41}, results.get(0).get()[1]);
        }
    }

    /** Ensures failure cancels and joins a running sibling before batch return and leaves a borrowed pool usable. */
    @Test(timeout = 10000)
    public void failuresJoinSiblingsAndKeepOriginalCause() throws Exception {
        try (var executor = Executors.newFixedThreadPool(2);
             var session = ExecutionSession.borrowed(ExecutionPolicy.bounded(2), executor);
             var scope = session.open()) {
            session.configureForWork(2, 1);
            CountDownLatch started = new CountDownLatch(1);
            AtomicBoolean stopped = new AtomicBoolean();
            var problem = new IllegalArgumentException("original scientific failure");
            List<Callable<Void>> tasks = List.of(() -> {
                started.countDown();
                try {
                    while (true) {
                        CancellationScope.check();
                        Thread.sleep(1);
                    }
                } finally {
                    stopped.set(true);
                }
            }, () -> {
                assertTrue(started.await(5, TimeUnit.SECONDS));
                throw problem;
            });
            ExecutionException failure = assertThrows(ExecutionException.class, () -> CancellableTasks.invokeAll(executor, tasks));
            assertSame(problem, failure.getCause());
            assertTrue(stopped.get());
            assertEquals(1, executor.submit(() -> 1).get().intValue());
        }
    }

    /** Closing one engine must neither close its caller-owned session nor another engine's borrowed executor. */
    @Test
    public void enginesDoNotCloseBorrowedResources() throws Exception {
        try (var executor = Executors.newFixedThreadPool(1);
             var session = ExecutionSession.borrowed(ExecutionPolicy.bounded(1), executor)) {
            var borrowingEngine = new JTransientEngine(session);
            borrowingEngine.close();
            assertFalse(session.isClosed());
            assertThrows(IllegalStateException.class, () -> borrowingEngine.runPipeline(new ArrayList<>(), new DetectionConfig(), null));
            try (var engine = new JTransientEngine(ExecutionPolicy.bounded(1), executor)) {
                assertThrows(IllegalArgumentException.class, () -> engine.runPipeline(new ArrayList<>(), new DetectionConfig(), null));
            }
            assertFalse(executor.isShutdown());
            assertEquals(2, executor.submit(() -> 2).get().intValue());
        }
    }

    /** Exercises memory-estimate bounds, integer overflow saturation and invalid policy validation without allocating images. */
    @Test
    public void memoryBudgetUsesExplicitAssumptionsAndSaturates() {
        var policy = ExecutionPolicy.bounded(8);
        assertEquals(8, policy.workerCount(20, 1, 8L << 30, 0));
        assertEquals(2, policy.workerCount(20, 61_000_000, 8L << 30, 0));
        assertEquals(1, policy.workerCount(20, Long.MAX_VALUE, 8L << 30, 0));
        assertEquals(1, policy.workerCount(20, 100, 8L << 30, 8L << 30));
        assertThrows(IllegalArgumentException.class, () -> ExecutionPolicy.bounded(0));
        assertThrows(IllegalArgumentException.class, () -> policy.workerCount(1, 1, -1, 0));
        assertThrows(IllegalArgumentException.class, () -> new ExecutionPolicy(1, 16, 0, Double.NaN, 0.5));
    }

    /** Enforces thread-confined LIFO restoration and proves idle owned closure shuts down the actual resources. */
    @Test
    public void scopeAndOwnedLifecycleAreExplicit() {
        var session = ExecutionSession.owned(ExecutionPolicy.bounded(1));
        var outer = session.open();
        var inner = session.open();
        assertThrows(IllegalStateException.class, outer::close);
        inner.close();
        outer.close();
        assertNull(ExecutionSession.current());
        session.close();
        session.close();
        assertTrue(session.executor().isTerminated());
        assertThrows(IllegalStateException.class, session::open);
    }
}
