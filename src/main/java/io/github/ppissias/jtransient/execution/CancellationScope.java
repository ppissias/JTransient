package io.github.ppissias.jtransient.execution;

import java.util.Objects;
import java.util.concurrent.CancellationException;

/**
 * Binds an operation's cancellation to its calling thread. JTransient propagates it to its worker tasks.
 * Cancellation throws {@link CancellationException}; an interrupted thread keeps its interrupt status.
 */
public final class CancellationScope implements AutoCloseable {
    private static final ThreadLocal<CancellationToken> CURRENT = new ThreadLocal<>();

    private final Thread owner = Thread.currentThread();
    private final CancellationToken previous;

    private CancellationScope(CancellationToken cancellation) {
        previous = CURRENT.get();
        CURRENT.set(Objects.requireNonNull(cancellation, "cancellation"));
    }

    public static CancellationScope open(CancellationToken cancellation) {
        return new CancellationScope(cancellation);
    }

    public static CancellationToken current() {
        CancellationToken cancellation = CURRENT.get();
        return cancellation == null ? CancellationToken.NONE : cancellation;
    }

    public static void check() {
        if (Thread.currentThread().isInterrupted() || current().isCancellationRequested()) {
            throw new CancellationException("JTransient operation cancelled.");
        }
    }

    @Override
    public void close() {
        if (Thread.currentThread() != owner) {
            throw new IllegalStateException("A cancellation scope must close on its opening thread.");
        }
        if (previous == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(previous);
        }
    }
}
