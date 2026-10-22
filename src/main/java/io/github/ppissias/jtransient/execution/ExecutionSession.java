package io.github.ppissias.jtransient.execution;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * Owns or borrows workers and scopes parallel batches across extraction, stacks, tuning and photometry.
 * Nested batches on session workers execute inline, preventing submit-and-wait deadlock on bounded pools.
 * A shared bounded borrowed executor supplies an aggregate ceiling; independent sessions do not reserve JVM-wide heap.
 * Public orchestration must not be called from an unmarked task on the same borrowed executor.
 */
public final class ExecutionSession implements AutoCloseable {
    private static final ThreadLocal<Context> CURRENT = new ThreadLocal<>();
    private final ExecutionPolicy policy;
    private final ExecutorService executor;
    private final boolean ownsExecutor;
    private boolean closed;
    private int activeScopes;

    /** Captures ownership without changing or shutting down a borrowed executor. */
    private ExecutionSession(ExecutionPolicy policy, ExecutorService executor, boolean ownsExecutor) {
        this.policy = Objects.requireNonNull(policy, "policy");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.ownsExecutor = ownsExecutor;
    }

    /** Creates a lazily populated owned fixed pool whose threads are joined when this idle session closes. */
    public static ExecutionSession owned(ExecutionPolicy policy) {
        Objects.requireNonNull(policy, "policy");
        return new ExecutionSession(policy, Executors.newFixedThreadPool(policy.maxWorkers()), true);
    }

    /** Borrows caller-owned workers; closing this idle wrapper never shuts down the supplied executor. */
    public static ExecutionSession borrowed(ExecutionPolicy policy, ExecutorService executor) {
        return new ExecutionSession(policy, executor, false);
    }

    /** Returns the immutable resource-estimation policy. */
    public ExecutionPolicy policy() {
        return policy;
    }

    /** Returns the backing executor for compatibility stage APIs; callers must not close or submit orchestration to it. */
    public ExecutorService executor() {
        return executor;
    }

    /** Reports wrapper closure or premature shutdown of borrowed resources. */
    public synchronized boolean isClosed() {
        return closed || executor.isShutdown();
    }

    /** Opens a caller-thread operation scope with a conservative one-worker budget until work dimensions are supplied. */
    public synchronized Scope open() {
        if (isClosed()) throw new IllegalStateException("ExecutionSession has been closed.");
        Context previous = CURRENT.get();
        if (previous != null && previous.worker && previous.session != this && previous.session.executor == executor) {
            throw new IllegalStateException("Do not start independent orchestration on the same borrowed executor's worker.");
        }
        boolean inherited = previous != null && previous.session == this;
        Context context = new Context(this, inherited ? previous.workers : 1, inherited && previous.worker);
        activeScopes++;
        CURRENT.set(context);
        return new Scope(previous, context, true);
    }

    /** Sets only this caller scope's batch budget; image pixels are counted, never retained or copied. */
    public int configureForWork(int workCount, long pixelsPerWork) {
        Context context = CURRENT.get();
        if (context == null || context.session != this || context.worker) {
            throw new IllegalStateException("Configure work on this session's orchestration scope, not a worker.");
        }
        context.workers = policy.workerCount(workCount, pixelsPerWork);
        return context.workers;
    }

    /** Returns the active session, or null for a standalone operation using its compatibility executor. */
    public static ExecutionSession current() {
        Context context = CURRENT.get();
        return context == null ? null : context.session;
    }

    /** Returns the configured limit in this session's current scope, primarily for diagnostics. */
    public int currentWorkerLimit() {
        Context context = CURRENT.get();
        if (context == null || context.session != this) throw new IllegalStateException("No active session scope.");
        return context.workers;
    }

    /** Runs an ordered batch within the current budget, joining all siblings on failure or cancellation. */
    <Result> List<Future<Result>> invokeAll(List<? extends Callable<Result>> tasks) throws ExecutionException {
        Context context = CURRENT.get();
        if (context == null || context.session != this) throw new IllegalStateException("No active session scope.");
        if (context.worker) return invokeInline(tasks);
        int workers = Math.min(context.workers, tasks.size());
        AtomicInteger next = new AtomicInteger();
        AtomicReferenceArray<Result> results = new AtomicReferenceArray<>(tasks.size());
        List<Callable<Void>> runners = new ArrayList<>();
        int budget = context.workers;
        for (int workerIndex = 0; workerIndex < workers; workerIndex++) {
            runners.add(() -> {
                try (Scope scope = enterWorker(budget)) {
                    int taskIndex;
                    while ((taskIndex = next.getAndIncrement()) < tasks.size()) {
                        CancellationScope.check();
                        results.set(taskIndex, tasks.get(taskIndex).call());
                        CancellationScope.check();
                    }
                    return null;
                }
            });
        }
        CancellableTasks.invokeDirect(executor, runners);
        List<Future<Result>> ordered = new ArrayList<>(tasks.size());
        for (int taskIndex = 0; taskIndex < tasks.size(); taskIndex++) {
            ordered.add(CompletableFuture.completedFuture(results.get(taskIndex)));
        }
        return ordered;
    }

    /** Executes nested work inline, retaining cancellation type and interruption while wrapping ordinary checked failures. */
    private static <Result> List<Future<Result>> invokeInline(List<? extends Callable<Result>> tasks) throws ExecutionException {
        List<Future<Result>> results = new ArrayList<>(tasks.size());
        for (Callable<Result> task : tasks) {
            CancellationScope.check();
            try {
                results.add(CompletableFuture.completedFuture(task.call()));
                CancellationScope.check();
            } catch (CancellationException | Error failure) {
                throw failure;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                CancellationException cancelled = new CancellationException("Nested JTransient work interrupted.");
                cancelled.initCause(interrupted);
                throw cancelled;
            } catch (Exception failure) {
                throw new ExecutionException(failure);
            }
        }
        return results;
    }

    /** Marks a managed worker so all deeper batches use its thread instead of waiting for the same pool. */
    private Scope enterWorker(int budget) {
        Context previous = CURRENT.get();
        Context context = new Context(this, budget, true);
        CURRENT.set(context);
        return new Scope(previous, context, false);
    }

    /** Rejects active close; idle owned resources are stopped/joined and borrowed executors are left running. */
    @Override
    public void close() {
        synchronized (this) {
            if (activeScopes != 0) throw new IllegalStateException("Await active operations before closing ExecutionSession.");
            if (!closed) {
                closed = true;
                if (ownsExecutor) executor.shutdown();
            }
            if (!ownsExecutor) return;
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

    /** Thread-confined operation identity and budget; worker contexts are separate from caller contexts. */
    private static final class Context {
        private final ExecutionSession session;
        private int workers;
        private final boolean worker;

        /** Captures a session and current worker role without retaining scientific inputs. */
        private Context(ExecutionSession session, int workers, boolean worker) {
            this.session = session;
            this.workers = workers;
            this.worker = worker;
        }
    }

    /** Thread-confined scope that restores its predecessor and releases the caller's active-operation lease. */
    public final class Scope implements AutoCloseable {
        private final Context previous;
        private final Context context;
        private final boolean callerLease;
        private final Thread owner = Thread.currentThread();
        private boolean ended;

        /** Captures restoration state; worker scopes are joined by their caller and do not acquire a second lease. */
        private Scope(Context previous, Context context, boolean callerLease) {
            this.previous = previous;
            this.context = context;
            this.callerLease = callerLease;
        }

        /** Restores the previous execution scope on the same thread; repeated closure is harmless. */
        @Override
        public void close() {
            if (Thread.currentThread() != owner) throw new IllegalStateException("Close execution scope on its owner thread.");
            if (ended) return;
            if (CURRENT.get() != context) throw new IllegalStateException("Close execution scopes in reverse opening order.");
            ended = true;
            if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
            if (callerLease) {
                synchronized (ExecutionSession.this) {
                    activeScopes--;
                }
            }
        }
    }
}
