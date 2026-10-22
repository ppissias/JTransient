package io.github.ppissias.jtransient.execution;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Executes a batch with cooperative cancellation and waits for running siblings before returning. */
public final class CancellableTasks {
    private CancellableTasks() {
    }

    /** Uses the current session's bounded workers, or the supplied compatibility executor outside a session. */
    public static <Result> List<Future<Result>> invokeAll(Executor executor,
                                                         List<? extends Callable<Result>> tasks) throws ExecutionException {
        ExecutionSession session = ExecutionSession.current();
        return session == null ? invokeDirect(executor, tasks) : session.invokeAll(tasks);
    }

    /** Runs an executor batch without session redirection; session-managed runners establish nested-worker scopes. */
    static <Result> List<Future<Result>> invokeDirect(Executor executor,
                                                    List<? extends Callable<Result>> tasks) throws ExecutionException {
        CancellationScope.check();
        CancellationToken parent = CancellationScope.current();
        AtomicBoolean stopping = new AtomicBoolean();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch finished = new CountDownLatch(tasks.size());
        CancellationToken cancellation = () -> stopping.get() || parent.isCancellationRequested();
        List<TrackedTask<Result>> submitted = new ArrayList<>(tasks.size());
        boolean completed = false;
        try {
            for (Callable<Result> task : tasks) {
                CancellationScope.check();
                TrackedTask<Result> tracked = new TrackedTask<>(() -> {
                    try (CancellationScope scope = CancellationScope.open(cancellation)) {
                        try {
                            CancellationScope.check();
                            Result result = task.call();
                            CancellationScope.check();
                            return result;
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
            return new ArrayList<>(submitted);
        } finally {
            if (!completed) {
                stopping.set(true);
                for (TrackedTask<Result> task : submitted) {
                    task.cancel(true);
                }
                for (int unsubmitted = submitted.size(); unsubmitted < tasks.size(); unsubmitted++) {
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

    private static final class TrackedTask<Result> extends FutureTask<Result> {
        private final AtomicInteger state = new AtomicInteger();
        private final CountDownLatch finished;

        private TrackedTask(Callable<Result> task, CountDownLatch finished) {
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
