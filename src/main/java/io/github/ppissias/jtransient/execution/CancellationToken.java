package io.github.ppissias.jtransient.execution;

/** A thread-safe cancellation request for one operation. */
@FunctionalInterface
public interface CancellationToken {
    CancellationToken NONE = () -> false;

    boolean isCancellationRequested();
}
