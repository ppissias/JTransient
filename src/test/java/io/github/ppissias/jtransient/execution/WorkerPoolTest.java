package io.github.ppissias.jtransient.execution;

import io.github.ppissias.jtransient.core.MasterMapGenerator;
import io.github.ppissias.jtransient.engine.ImageFrame;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/** Checks worker limits, nested batches, task order, joined failure and cancellation, and scope restoration. */
public class WorkerPoolTest {
    private static final long GIBIBYTE = 1024L * 1024L * 1024L;

    /** Never runs more tasks at once than the operation's limit and returns results in task order. */
    @Test
    public void honoursTheWorkerLimitAndKeepsTaskOrder() throws Exception {
        AtomicInteger active = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        try (var pool = new WorkerPool(2)) {
            try (var scope = pool.open()) {
                assertEquals(2, pool.limitWork(20, 1));
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
                List<Integer> results = WorkerPool.invokeAll(tasks);
                for (int index = 0; index < results.size(); index++) assertEquals(index, results.get(index).intValue());
                assertTrue(peak.get() <= 2);
                assertEquals(0, active.get());
                assertThrows(IllegalStateException.class, pool::close);
            }
        }
    }

    /** A batch started on a worker runs inline on that worker, so a one-thread pool cannot deadlock. */
    @Test(timeout = 10000)
    public void nestedBatchesStayOnTheWorker() throws Exception {
        List<ImageFrame> frames = List.of(
                new ImageFrame(0, "first", new short[][]{{10, 20}, {30, 40}}, 0, 1000),
                new ImageFrame(1, "second", new short[][]{{11, 21}, {31, 41}}, 1000, 1000));
        try (var pool = new WorkerPool(1); var scope = pool.open()) {
            pool.limitWork(2, 4);
            List<short[][]> results = WorkerPool.<short[][]>invokeAll(List.of(() -> {
                List<Thread> nested = WorkerPool.invokeAll(List.of(Thread::currentThread));
                assertSame(Thread.currentThread(), nested.get(0));
                assertArrayEquals(new short[]{10, 20}, MasterMapGenerator.createMedianMasterStack(frames)[0]);
                return MasterMapGenerator.createMaximumMasterStack(frames);
            }));
            assertArrayEquals(new short[]{31, 41}, results.get(0)[1]);
        }
    }

    /** Outside an operation a batch runs on the calling thread. */
    @Test
    public void outsideAnOperationBatchesRunOnTheCaller() throws Exception {
        assertEquals(List.of(Thread.currentThread(), Thread.currentThread()),
                WorkerPool.invokeAll(List.of(Thread::currentThread, Thread::currentThread)));
    }

    /** A failure stops and joins a running sibling before the batch returns, and keeps the original cause. */
    @Test(timeout = 10000)
    public void failuresJoinSiblingsAndKeepTheOriginalCause() throws Exception {
        try (var pool = new WorkerPool(2); var scope = pool.open()) {
            pool.limitWork(2, 1);
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
            ExecutionException failure = assertThrows(ExecutionException.class, () -> WorkerPool.invokeAll(tasks));
            assertSame(problem, failure.getCause());
            assertTrue(stopped.get());
            assertEquals(List.of(1), WorkerPool.invokeAll(List.of(() -> 1)));
        }
    }

    /** A failing task skips the queued ones on a one-worker pool. */
    @Test
    public void workerFailureSkipsQueuedWork() throws Exception {
        IllegalArgumentException problem = new IllegalArgumentException("frame failed");
        AtomicInteger laterWork = new AtomicInteger();
        try (var pool = new WorkerPool(1); var scope = pool.open()) {
            pool.limitWork(2, 1);
            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> WorkerPool.invokeAll(List.<Callable<Void>>of(
                            () -> { throw problem; },
                            () -> { laterWork.incrementAndGet(); return null; })));
            assertSame(problem, failure.getCause());
            assertEquals(0, laterWork.get());
        }
    }

    /** Workers see the caller's cancellation, labels and diagnostics, and the pooled threads forget them afterwards. */
    @Test
    public void workersInheritTheCallerScopesAndRestoreTheirThreads() throws Exception {
        ExecutorService threads = Executors.newSingleThreadExecutor();
        List<String> messages = new CopyOnWriteArrayList<>();
        DiagnosticOptions options = new DiagnosticOptions(true, messages::add);
        CancellationToken token = () -> false;
        try (var pool = new WorkerPool(1, threads)) {
            try (var cancellation = CancellationScope.open(token);
                 var diagnostics = DiagnosticContext.open(options);
                 var scope = pool.open()) {
                WorkerPool.invokeAll(List.of(() -> {
                    assertNotSame(CancellationToken.NONE, CancellationScope.current());
                    assertSame(options, DiagnosticContext.currentOptions());
                    DiagnosticContext.println("outer");
                    try (DiagnosticContext.Scope nested = DiagnosticContext.open(new DiagnosticOptions(false, messages::add))) {
                        DiagnosticContext.printf("hidden %d", 1);
                    }
                    DiagnosticContext.printf("inner %d", 2);
                    return null;
                }));
            }
            assertSame(CancellationToken.NONE, threads.submit(CancellationScope::current).get());
            assertFalse(threads.submit(DiagnosticContext::enabled).get());
            assertNull(threads.submit(OperationContext::snapshot).get());
            assertEquals(List.of("outer" + System.lineSeparator(), "inner 2"), messages);
        }
    }

    /** Cancellation waits for a running worker to finish its cleanup before the batch reports it. */
    @Test
    public void cancellationWaitsForRunningWorkersToReleaseTheirResources() throws Exception {
        ExecutorService caller = Executors.newSingleThreadExecutor();
        AtomicBoolean cancelled = new AtomicBoolean();
        AtomicBoolean released = new AtomicBoolean();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch cleaning = new CountDownLatch(1);
        CountDownLatch allowCleanup = new CountDownLatch(1);
        try (var pool = new WorkerPool(1)) {
            Future<?> operation = caller.submit(() -> {
                try (CancellationScope scope = CancellationScope.open(cancelled::get); var workers = pool.open()) {
                    return WorkerPool.invokeAll(List.<Callable<Void>>of(() -> {
                        started.countDown();
                        try {
                            while (true) {
                                CancellationScope.check();
                                Thread.yield();
                            }
                        } finally {
                            cleaning.countDown();
                            awaitUninterruptibly(allowCleanup);
                            released.set(true);
                        }
                    }));
                }
            });
            assertTrue(started.await(5, TimeUnit.SECONDS));
            cancelled.set(true);
            assertTrue(cleaning.await(5, TimeUnit.SECONDS));
            assertFalse(released.get());
            assertThrows(TimeoutException.class, () -> operation.get(50, TimeUnit.MILLISECONDS));
            allowCleanup.countDown();
            ExecutionException failure = assertThrows(ExecutionException.class, () -> operation.get(5, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof CancellationException);
            assertTrue(released.get());
        } finally {
            allowCleanup.countDown();
            cancelled.set(true);
            caller.shutdownNow();
        }
    }

    /** A cancellation thrown by a task stays a cancellation, not a worker failure. */
    @Test
    public void taskCancellationIsNotWrappedAsAWorkerFailure() throws Exception {
        CancellationException cancelled = new CancellationException("listener cancelled");
        try (var pool = new WorkerPool(2); var scope = pool.open()) {
            assertSame(cancelled, assertThrows(CancellationException.class,
                    () -> WorkerPool.invokeAll(List.<Callable<Void>>of(() -> { throw cancelled; }))));
        }
    }

    /** A fatal error during cancellation cleanup is reported, not hidden by the cancellation. */
    @Test
    public void outOfMemoryDuringCleanupIsNotHiddenByCancellation() throws Exception {
        ExecutorService caller = Executors.newSingleThreadExecutor();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean cancelled = new AtomicBoolean();
        OutOfMemoryError problem = new OutOfMemoryError("injected worker failure");
        try (var pool = new WorkerPool(1)) {
            Future<?> operation = caller.submit(() -> {
                try (CancellationScope scope = CancellationScope.open(cancelled::get); var workers = pool.open()) {
                    return WorkerPool.invokeAll(List.<Callable<Void>>of(() -> {
                        started.countDown();
                        awaitUninterruptibly(release);
                        throw problem;
                    }));
                }
            });
            assertTrue(started.await(5, TimeUnit.SECONDS));
            cancelled.set(true);
            release.countDown();
            ExecutionException failure = assertThrows(ExecutionException.class, () -> operation.get(5, TimeUnit.SECONDS));
            assertSame(problem, failure.getCause());
        } finally {
            release.countDown();
            caller.shutdownNow();
        }
    }

    /** An interrupted task cancels the batch and keeps the interruption as the cause. */
    @Test
    public void interruptedWorkersCancelTheBatchAndRetainTheCause() throws Exception {
        InterruptedException interrupted = new InterruptedException("worker wait interrupted");
        try (var pool = new WorkerPool(1); var scope = pool.open()) {
            CancellationException cancelled = assertThrows(CancellationException.class,
                    () -> WorkerPool.invokeAll(List.<Callable<Void>>of(() -> { throw interrupted; })));
            assertSame(interrupted, cancelled.getCause());
        }
    }

    /** The memory estimate limits workers for large frames, saturates huge estimates and allows at least one. */
    @Test
    public void workerCountFollowsCpuWorkAndHeap() {
        assertEquals(4, WorkerPool.workerCount(4, 100, 100, 8 * GIBIBYTE, 0));
        assertEquals(2, WorkerPool.workerCount(16, 2, 100, 8 * GIBIBYTE, 0));
        assertEquals(8, WorkerPool.workerCount(8, 20, 1, 8 * GIBIBYTE, 0));
        assertEquals(2, WorkerPool.workerCount(16, 100, 61_000_000, 8 * GIBIBYTE, 0));
        assertEquals(1, WorkerPool.workerCount(16, 100, 61_000_000, 8 * GIBIBYTE, 7 * GIBIBYTE));
        assertEquals(1, WorkerPool.workerCount(16, 100, 61_000_000, GIBIBYTE, GIBIBYTE));
        assertEquals(1, WorkerPool.workerCount(8, 20, Long.MAX_VALUE, 8 * GIBIBYTE, 0));
        assertThrows(IllegalArgumentException.class, () -> WorkerPool.workerCount(1, 1, 1, -1, 0));
        assertThrows(IllegalArgumentException.class, () -> new WorkerPool(0));
    }

    /** Scopes close on their own thread in reverse order; close stops the threads and rejects new operations. */
    @Test
    public void scopesAndCloseAreExplicit() throws Exception {
        var pool = new WorkerPool(1);
        var outer = pool.open();
        var inner = pool.open();
        assertThrows(IllegalStateException.class, outer::close);
        ExecutorService otherThread = Executors.newSingleThreadExecutor();
        try {
            assertTrue(otherThread.submit(() -> {
                try {
                    inner.close();
                    return false;
                } catch (IllegalStateException expected) {
                    return true;
                }
            }).get());
        } finally {
            otherThread.shutdownNow();
        }
        inner.close();
        outer.close();
        pool.close();
        pool.close();
        assertTrue(pool.isTerminated());
        assertThrows(IllegalStateException.class, pool::open);
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        boolean interrupted = Thread.interrupted();
        while (true) {
            try {
                latch.await();
                break;
            } catch (InterruptedException ignored) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
