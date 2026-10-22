package io.github.ppissias.jtransient.execution;

import java.util.Objects;
import java.util.concurrent.CancellationException;

/** Additive terminal operation contract; a completed scientific result may still report insufficient evidence or no findings. */
public sealed interface OperationOutcome<Result> {
    /** Completed work, with normal scientific readiness/fallback semantics left in the existing result. */
    record Completed<Result>(Result value) implements OperationOutcome<Result> { }

    /** A runtime failure, distinct from a completed empty or insufficient-evidence scientific analysis. */
    record Failed<Result>(OperationFailure failure) implements OperationOutcome<Result> {
        /** Requires structured failure context while retaining its original cause only in memory. */
        public Failed {
            Objects.requireNonNull(failure, "failure");
        }
    }

    /** Cooperative cancellation after all managed worker tasks and cleanup have ended. */
    record Cancelled<Result>(CancellationException cause) implements OperationOutcome<Result> {
        /** Preserves the original cancellation signal, including its interruption cause where available. */
        public Cancelled {
            Objects.requireNonNull(cause, "cause");
        }
    }
}
