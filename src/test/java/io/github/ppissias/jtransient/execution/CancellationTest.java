package io.github.ppissias.jtransient.execution;

import org.junit.Test;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class CancellationTest {
    @Test
    public void scopesRestoreThePreviousTokenAndDoNotLeakAcrossThreads() throws Exception {
        CancellationToken outer = () -> false;
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (CancellationScope scope = CancellationScope.open(outer)) {
            try (CancellationScope inner = CancellationScope.open(() -> true)) {
                assertThrows(CancellationException.class, CancellationScope::check);
                assertSame(CancellationToken.NONE, executor.submit(CancellationScope::current).get());
            }
            assertSame(outer, CancellationScope.current());
        } finally {
            executor.shutdownNow();
        }
        assertSame(CancellationToken.NONE, CancellationScope.current());
    }

    @Test
    public void interruptionCancelsWithoutClearingTheInterruptStatus() {
        Thread.currentThread().interrupt();
        try {
            assertThrows(CancellationException.class, CancellationScope::check);
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    public void successfulBatchesPreserveInputOrderAndRestoreWorkerScopes() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CancellationToken token = () -> false;
        try (CancellationScope scope = CancellationScope.open(token)) {
            List<Future<Integer>> results = CancellableTasks.invokeAll(executor, List.of(
                    () -> { assertTrue(CancellationScope.current() != CancellationToken.NONE); return 11; },
                    () -> 22));
            assertEquals(Integer.valueOf(11), results.get(0).get());
            assertEquals(Integer.valueOf(22), results.get(1).get());
            assertSame(CancellationToken.NONE, executor.submit(CancellationScope::current).get());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void workerFailurePreservesItsCauseAndSkipsQueuedWork() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        IllegalArgumentException problem = new IllegalArgumentException("frame failed");
        AtomicInteger laterWork = new AtomicInteger();
        try {
            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> CancellableTasks.invokeAll(executor, List.<Callable<Void>>of(
                            () -> { throw problem; },
                            () -> { laterWork.incrementAndGet(); return null; })));
            assertSame(problem, failure.getCause());
            assertEquals(0, laterWork.get());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void cancellationWaitsForRunningWorkersToReleaseTheirResources() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        ExecutorService caller = Executors.newSingleThreadExecutor();
        AtomicBoolean cancelled = new AtomicBoolean();
        AtomicBoolean released = new AtomicBoolean();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch cleaning = new CountDownLatch(1);
        CountDownLatch allowCleanup = new CountDownLatch(1);
        try {
            Future<?> operation = caller.submit(() -> {
                try (CancellationScope scope = CancellationScope.open(cancelled::get)) {
                    return CancellableTasks.invokeAll(executor, List.<Callable<Void>>of(() -> {
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
            assertSame(CancellationToken.NONE, executor.submit(CancellationScope::current).get());
        } finally {
            allowCleanup.countDown();
            cancelled.set(true);
            caller.shutdownNow();
            executor.shutdownNow();
        }
    }

    @Test
    public void listenerCancellationIsNotWrappedAsAWorkerFailure() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CancellationException cancelled = new CancellationException("listener cancelled");
        try {
            assertSame(cancelled, assertThrows(CancellationException.class,
                    () -> CancellableTasks.invokeAll(executor, List.<Callable<Void>>of(() -> { throw cancelled; }))));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void outOfMemoryDuringCleanupIsNotHiddenByCancellation() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        ExecutorService caller = Executors.newSingleThreadExecutor();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean cancelled = new AtomicBoolean();
        OutOfMemoryError problem = new OutOfMemoryError("injected worker failure");
        try {
            Future<?> operation = caller.submit(() -> {
                try (CancellationScope scope = CancellationScope.open(cancelled::get)) {
                    return CancellableTasks.invokeAll(executor, List.<Callable<Void>>of(() -> {
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
            executor.shutdownNow();
        }
    }

    @Test
    public void interruptedWorkersCancelTheBatchAndRetainTheCause() {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        InterruptedException interrupted = new InterruptedException("worker wait interrupted");
        try {
            CancellationException cancelled = assertThrows(CancellationException.class,
                    () -> CancellableTasks.invokeAll(executor, List.<Callable<Void>>of(() -> { throw interrupted; })));
            assertSame(interrupted, cancelled.getCause());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void rejectedSubmissionDoesNotLeaveEarlierWorkRunning() {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.shutdown();
        assertThrows(java.util.concurrent.RejectedExecutionException.class,
                () -> CancellableTasks.invokeAll(executor, List.of(() -> 1, () -> 2)));
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
