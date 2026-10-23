package io.github.ppissias.jtransient.execution;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * The worker threads of one engine, or of one tuner call, and the parallel batches that run on them. This is a
 * library-internal class; it is public only so that the engine, stack, tuner and photometry packages can share it.
 *
 * <p>An operation binds the pool to its calling thread with {@link #open()}. {@link #invokeAll} then runs a batch on
 * at most the operation's worker limit, returns the results in task order, and carries the caller's cancellation,
 * failure labels and diagnostics into every worker. A batch started on one of the pool's own workers runs inline, so
 * nested batches never wait for a full pool. Outside an operation, a batch runs on the calling thread.</p>
 *
 * <p>The worker limit is the smaller of the pool size, the work count and a scratch-memory estimate: 16 bytes per
 * image pixel plus 1 MiB per worker, within a quarter of the maximum heap and half of the heap now free. It is an
 * estimate, not a reservation.</p>
 */
public final class WorkerPool implements AutoCloseable {
    private static final long SCRATCH_BYTES_PER_PIXEL = 16;
    private static final long SCRATCH_OVERHEAD_BYTES = 1024 * 1024;
    private static final double MAXIMUM_HEAP_SHARE = 0.25;
    private static final double FREE_HEAP_SHARE = 0.5;
    private static final ThreadLocal<Binding> CURRENT = new ThreadLocal<>();

    private final int maxWorkers;
    private final ExecutorService executor;
    private int openScopes;
    private boolean closed;

    /** Creates a pool of at most {@code maxWorkers} threads, started when a batch first needs them. */
    public WorkerPool(int maxWorkers) {
        this(maxWorkers, Executors.newFixedThreadPool(Math.max(1, maxWorkers)));
    }

    /** Uses the given executor, which the pool then owns and shuts down; tests use it to inspect worker threads. */
    WorkerPool(int maxWorkers, ExecutorService executor) {
        if (maxWorkers < 1) throw new IllegalArgumentException("A worker pool needs at least one worker.");
        this.maxWorkers = maxWorkers;
        this.executor = executor;
    }

    /** Creates a pool with one worker per available processor. */
    public static WorkerPool forAvailableProcessors() {
        return new WorkerPool(Runtime.getRuntime().availableProcessors());
    }

    /**
     * Binds this pool to the calling thread for one operation, with a limit of one worker until {@link #limitWork}
     * sets it. Close the scope on the same thread when the operation ends.
     */
    public synchronized Scope open() {
        if (closed) throw new IllegalStateException("The worker pool has been closed.");
        Binding previous = CURRENT.get();
        boolean nested = previous != null && previous.pool == this;
        Binding binding = new Binding(this, nested ? previous.workers : 1, nested && previous.worker);
        openScopes++;
        CURRENT.set(binding);
        return new Scope(previous, binding);
    }

    /**
     * Sets the worker limit of the operation bound to this thread from its work count and the pixels one task
     * handles; image pixels are counted, never copied.
     *
     * @return the limit now in force
     */
    public int limitWork(int workCount, long pixelsPerWork) {
        Binding binding = CURRENT.get();
        if (binding == null || binding.pool != this || binding.worker) {
            throw new IllegalStateException("Set the worker limit on the thread that runs the operation.");
        }
        Runtime runtime = Runtime.getRuntime();
        binding.workers = workerCount(maxWorkers, workCount, pixelsPerWork,
                runtime.maxMemory(), runtime.totalMemory() - runtime.freeMemory());
        return binding.workers;
    }

    /** The worker limit for these CPU, work and heap figures; at least one, and saturating oversized estimates. */
    static int workerCount(int maxWorkers, int workCount, long pixelsPerWork, long maximumHeap, long usedHeap) {
        if (maximumHeap < 0 || usedHeap < 0) throw new IllegalArgumentException("Heap byte counts must be nonnegative.");
        long pixels = Math.max(1, pixelsPerWork);
        long scratch = pixels > (Long.MAX_VALUE - SCRATCH_OVERHEAD_BYTES) / SCRATCH_BYTES_PER_PIXEL
                ? Long.MAX_VALUE : pixels * SCRATCH_BYTES_PER_PIXEL + SCRATCH_OVERHEAD_BYTES;
        long available = Math.max(0, maximumHeap - usedHeap);
        long budget = (long) Math.min(maximumHeap * MAXIMUM_HEAP_SHARE, available * FREE_HEAP_SHARE);
        long memoryWorkers = Math.max(1, budget / scratch);
        return (int) Math.max(1, Math.min(memoryWorkers, Math.min(Math.max(1, workCount), maxWorkers)));
    }

    /**
     * Runs the tasks and returns their results in task order. On a failure or cancellation the batch stops queued
     * tasks, asks the running ones to stop and returns only after all of them have ended. The first failure is the
     * cause of the {@link ExecutionException}; cancellation stays a {@link CancellationException}, and interruption
     * becomes one while keeping the thread's interrupt status.
     */
    public static <Result> List<Result> invokeAll(List<? extends Callable<Result>> tasks) throws ExecutionException {
        Binding binding = CURRENT.get();
        if (binding == null || binding.worker) return invokeInline(tasks);
        return binding.pool.invokeOnWorkers(tasks, binding.workers);
    }

    /** Spreads the tasks over at most {@code limit} workers that each take the next task until none is left. */
    private <Result> List<Result> invokeOnWorkers(List<? extends Callable<Result>> tasks, int limit) throws ExecutionException {
        int workers = Math.min(limit, tasks.size());
        AtomicInteger next = new AtomicInteger();
        AtomicReferenceArray<Result> results = new AtomicReferenceArray<>(tasks.size());
        List<Callable<Void>> runners = new ArrayList<>(workers);
        for (int workerIndex = 0; workerIndex < workers; workerIndex++) {
            runners.add(() -> {
                Binding previous = CURRENT.get();
                CURRENT.set(new Binding(this, limit, true));
                try {
                    int taskIndex;
                    while ((taskIndex = next.getAndIncrement()) < tasks.size()) {
                        CancellationScope.check();
                        results.set(taskIndex, tasks.get(taskIndex).call());
                        CancellationScope.check();
                    }
                    return null;
                } finally {
                    if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
                }
            });
        }
        runJoined(runners);
        List<Result> ordered = new ArrayList<>(tasks.size());
        for (int taskIndex = 0; taskIndex < tasks.size(); taskIndex++) {
            ordered.add(results.get(taskIndex));
        }
        return ordered;
    }

    /** Runs the tasks one after another on this thread, with the same exception contract as a pooled batch. */
    private static <Result> List<Result> invokeInline(List<? extends Callable<Result>> tasks) throws ExecutionException {
        List<Result> results = new ArrayList<>(tasks.size());
        for (Callable<Result> task : tasks) {
            CancellationScope.check();
            try {
                results.add(task.call());
                CancellationScope.check();
            } catch (CancellationException | Error failure) {
                throw failure;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                CancellationException cancelled = new CancellationException("JTransient work interrupted.");
                cancelled.initCause(interrupted);
                throw cancelled;
            } catch (Exception failure) {
                throw new ExecutionException(failure);
            }
        }
        return results;
    }

    /**
     * Submits the runners with the caller's cancellation, failure labels and diagnostics, and waits until every
     * runner has ended, cancelling the others as soon as one fails or the operation is cancelled.
     */
    private void runJoined(List<Callable<Void>> runners) throws ExecutionException {
        CancellationScope.check();
        CancellationToken parent = CancellationScope.current();
        OperationContext.Snapshot labels = OperationContext.snapshot();
        DiagnosticOptions diagnostics = DiagnosticContext.currentOptions();
        AtomicBoolean stopping = new AtomicBoolean();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch finished = new CountDownLatch(runners.size());
        CancellationToken cancellation = () -> stopping.get() || parent.isCancellationRequested();
        List<TrackedTask> submitted = new ArrayList<>(runners.size());
        boolean completed = false;
        try {
            for (Callable<Void> runner : runners) {
                CancellationScope.check();
                TrackedTask tracked = new TrackedTask(() -> {
                    try (CancellationScope scope = CancellationScope.open(cancellation);
                         OperationContext.Scope labelScope = OperationContext.restore(labels);
                         DiagnosticContext.Scope diagnosticScope = DiagnosticContext.open(diagnostics)) {
                        try {
                            return runner.call();
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            CancellationException cancelled = new CancellationException("JTransient worker interrupted.");
                            cancelled.initCause(interrupted);
                            recordFailure(failure, cancelled);
                            stopping.set(true);
                            throw cancelled;
                        } catch (Throwable problem) {
                            recordFailure(failure, problem);
                            stopping.set(true);
                            throw problem;
                        }
                    }
                }, finished);
                submitted.add(tracked);
                executor.execute(tracked);
            }
            while (finished.getCount() > 0) {
                throwFailure(failure.get());
                CancellationScope.check();
                try {
                    finished.await(20, TimeUnit.MILLISECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    CancellationException cancelled = new CancellationException("Interrupted while waiting for JTransient workers.");
                    cancelled.initCause(interrupted);
                    throw cancelled;
                }
            }
            throwFailure(failure.get());
            CancellationScope.check();
            completed = true;
        } finally {
            if (!completed) {
                stopping.set(true);
                for (TrackedTask task : submitted) {
                    task.cancel(true);
                }
                for (int unsubmitted = submitted.size(); unsubmitted < runners.size(); unsubmitted++) {
                    finished.countDown();
                }
                boolean interrupted = Thread.interrupted();
                while (finished.getCount() > 0) {
                    try {
                        finished.await();
                    } catch (InterruptedException ignored) {
                        interrupted = true;
                    }
                }
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
                if (failure.get() instanceof Error error) {
                    throw error;
                }
            }
        }
    }

    /** Keeps the first failure, except that a fatal error replaces an ordinary one. */
    private static void recordFailure(AtomicReference<Throwable> failure, Throwable problem) {
        failure.getAndUpdate(previous -> previous instanceof Error ? previous
                : problem instanceof Error || previous == null ? problem : previous);
    }

    private static void throwFailure(Throwable failure) throws ExecutionException {
        if (failure instanceof CancellationException cancelled) {
            throw cancelled;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        if (failure != null) {
            throw new ExecutionException(failure);
        }
    }

    /** Stops the idle pool and waits for its threads to end; rejected while an operation is bound to it. */
    @Override
    public void close() {
        synchronized (this) {
            if (openScopes != 0) throw new IllegalStateException("Await the running operation before closing the worker pool.");
            if (closed) return;
            closed = true;
            executor.shutdown();
        }
        boolean interrupted = Thread.interrupted();
        while (!executor.isTerminated()) {
            try {
                executor.awaitTermination(1, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                interrupted = true;
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    /** Whether {@link #close()} has stopped the pool's threads; for tests. */
    boolean isTerminated() {
        return executor.isTerminated();
    }

    /** The operation's pool and worker limit on one thread; worker threads run nested batches inline. */
    private static final class Binding {
        private final WorkerPool pool;
        private int workers;
        private final boolean worker;

        private Binding(WorkerPool pool, int workers, boolean worker) {
            this.pool = pool;
            this.workers = workers;
            this.worker = worker;
        }
    }

    /** One operation's binding of the pool to its thread; closing it restores the previous binding. */
    public final class Scope implements AutoCloseable {
        private final Binding previous;
        private final Binding binding;
        private final Thread owner = Thread.currentThread();
        private boolean ended;

        private Scope(Binding previous, Binding binding) {
            this.previous = previous;
            this.binding = binding;
        }

        /** Restores the previous binding on the opening thread; closing again does nothing. */
        @Override
        public void close() {
            if (Thread.currentThread() != owner) throw new IllegalStateException("Close the scope on the thread that opened it.");
            if (ended) return;
            if (CURRENT.get() != binding) throw new IllegalStateException("Close worker-pool scopes in reverse opening order.");
            ended = true;
            if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
            synchronized (WorkerPool.this) {
                openScopes--;
            }
        }
    }

    /** A submitted runner that counts down once, whether it ran, failed or was cancelled before it started. */
    private static final class TrackedTask extends FutureTask<Void> {
        private final AtomicInteger state = new AtomicInteger();
        private final CountDownLatch finished;

        private TrackedTask(Callable<Void> task, CountDownLatch finished) {
            super(task);
            this.finished = finished;
        }

        @Override
        public void run() {
            if (!state.compareAndSet(0, 1)) {
                return;
            }
            try {
                super.run();
            } finally {
                state.set(2);
                finished.countDown();
            }
        }

        @Override
        public boolean cancel(boolean interrupt) {
            boolean cancelled = super.cancel(interrupt);
            if (state.compareAndSet(0, 2)) {
                finished.countDown();
            }
            return cancelled;
        }
    }
}
