package io.github.ppissias.jtransient.execution;

import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/**
 * Thread-scoped additive failure capture, propagated explicitly into managed batches.
 * Legacy calls rethrow the same exceptions; only capture callers receive terminal outcome values.
 * Concurrent failures retain the first recorded ordinary failure, not a deterministic choice among racing failures.
 */
public final class OperationContext {
    private static final ThreadLocal<Snapshot> CURRENT = new ThreadLocal<>();

    /** Prevents construction of the scoped operation utility. */
    private OperationContext() { }

    /** Captures completed/failed/cancelled work without changing scientific result semantics or catching fatal VM errors. */
    public static <Result> OperationOutcome<Result> capture(StageId defaultStage, Callable<Result> operation) {
        Snapshot context = new Snapshot(defaultStage, null, new AtomicReference<>());
        try (Scope scope = restore(context)) {
            try {
                return new OperationOutcome.Completed<>(operation.call());
            } catch (Exception failure) {
                Throwable original = unwrap(failure);
                if (original instanceof Error error) throw error;
                if (original instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                    CancellationException cancelled = new CancellationException("JTransient operation interrupted.");
                    cancelled.initCause(original);
                    return new OperationOutcome.Cancelled<>(cancelled);
                }
                if (original instanceof CancellationException cancelled) return new OperationOutcome.Cancelled<>(cancelled);
                context.record(original);
                return new OperationOutcome.Failed<>(context.failure.get());
            }
        }
    }

    /** Runs checked stage work, inheriting frame identity when null, and records/rethrows the same original exception. */
    public static <Result> Result call(StageId stage, FrameIdentity frame, Callable<Result> operation) throws Exception {
        Snapshot parent = CURRENT.get();
        if (parent == null) return operation.call();
        Snapshot context = new Snapshot(stage, frame == null ? parent.frame : frame, parent.failure);
        try (Scope scope = restore(context)) {
            try {
                return operation.call();
            } catch (Exception failure) {
                context.record(unwrap(failure));
                throw failure;
            }
        }
    }

    /** Runs unchecked stage work with unchanged exceptions; a null frame inherits the enclosing diagnostic identity. */
    public static <Result> Result compute(StageId stage, FrameIdentity frame, Supplier<Result> operation) {
        Snapshot parent = CURRENT.get();
        if (parent == null) return operation.get();
        Snapshot context = new Snapshot(stage, frame == null ? parent.frame : frame, parent.failure);
        try (Scope scope = restore(context)) {
            try {
                return operation.get();
            } catch (RuntimeException failure) {
                context.record(unwrap(failure));
                throw failure;
            }
        }
    }

    /** Invokes an existing synchronous callback, retaining current frame identity and all old percentage/message values. */
    public static void notify(BiConsumer<Integer, String> listener, int percentage, String message) {
        Snapshot parent = CURRENT.get();
        compute(StageId.LISTENER, parent == null ? null : parent.frame, () -> {
            listener.accept(percentage, message);
            return null;
        });
    }

    /** Runs a void unchecked stage while preserving the legacy exception object and optional frame diagnostics. */
    public static void run(StageId stage, FrameIdentity frame, Runnable operation) {
        compute(stage, frame, () -> {
            operation.run();
            return null;
        });
    }

    /** Captures small diagnostic state for a worker; it contains no input buffers or operation callables. */
    static Snapshot snapshot() { return CURRENT.get(); }

    /** Restores captured diagnostic state on a worker, or clears unrelated state when the captured context is absent. */
    static Scope restore(Snapshot context) {
        Snapshot previous = CURRENT.get();
        if (context == null) CURRENT.remove(); else CURRENT.set(context);
        return new Scope(previous);
    }

    /** Unwraps transport futures without replacing a scientific/listener exception or walking unrelated cause chains. */
    private static Throwable unwrap(Throwable failure) {
        while ((failure instanceof ExecutionException || failure instanceof CompletionException) && failure.getCause() != null) {
            failure = failure.getCause();
        }
        return failure;
    }

    /** Immutable stage/frame labels plus a shared first-failure cell for one captured operation and its workers. */
    static final class Snapshot {
        private final StageId stage;
        private final FrameIdentity frame;
        private final AtomicReference<OperationFailure> failure;

        /** Captures labels and shared failure ownership without retaining mutable frame descriptors. */
        private Snapshot(StageId stage, FrameIdentity frame, AtomicReference<OperationFailure> failure) {
            this.stage = Objects.requireNonNull(stage, "stage");
            this.frame = frame;
            this.failure = failure;
        }

        /** Retains the first ordinary failure; cooperative cancellation/interruption is a different terminal outcome. */
        private void record(Throwable cause) {
            if (!(cause instanceof CancellationException) && !(cause instanceof InterruptedException) && !(cause instanceof Error)) {
                failure.compareAndSet(null, new OperationFailure(stage, frame, cause));
            }
        }
    }

    /** Thread-confined restoration state for caller or worker diagnostic scopes. */
    static final class Scope implements AutoCloseable {
        private final Snapshot previous;

        /** Captures the prior context; scopes are used synchronously in try-with-resources on their creating thread. */
        private Scope(Snapshot previous) { this.previous = previous; }

        /** Restores or removes prior diagnostic state so pooled threads do not retain completed operations. */
        @Override
        public void close() {
            if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
        }
    }
}
