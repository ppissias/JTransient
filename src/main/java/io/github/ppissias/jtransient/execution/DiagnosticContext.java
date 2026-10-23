package io.github.ppissias.jtransient.execution;

import java.util.Objects;

/** Per-operation core diagnostic routing, independent of engine/science implementation classes. */
public final class DiagnosticContext {
    private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();
    private static final DiagnosticOptions SILENT = new DiagnosticOptions(false, message -> { });

    /** Prevents construction of this scoped routing utility. */
    private DiagnosticContext() {
    }

    /** Returns active immutable options, or silent defaults outside a scope. */
    public static DiagnosticOptions currentOptions() {
        Scope scope = CURRENT.get();
        return scope == null ? SILENT : scope.options;
    }

    /** Opens inherited options, remaining silent when the caller supplied no diagnostic scope. */
    public static Scope openDefaults() {
        return open(currentOptions());
    }

    /** Inherits an explicit caller policy, otherwise captures the application's supplied default policy. */
    public static Scope openIfAbsent(DiagnosticOptions defaults) {
        return open(CURRENT.get() == null ? defaults : currentOptions());
    }

    /** Opens explicit options until close; nested scopes restore prior options rather than editing globals. */
    public static Scope open(DiagnosticOptions options) {
        return new Scope(Objects.requireNonNull(options, "options"));
    }

    /** Reports active debug policy without consulting the engine facade. */
    public static boolean enabled() {
        return currentOptions().enabled();
    }

    /** Emits a line with the platform line separator through the active synchronous sink. */
    public static void println(String message) {
        currentOptions().write(message + System.lineSeparator());
    }

    /** Formats with the default locale, matching the legacy standard-output printf contract. */
    public static void printf(String format, Object... arguments) {
        DiagnosticOptions options = currentOptions();
        if (options.enabled()) {
            options.write(String.format(format, arguments));
        }
    }

    /** Thread-confined, LIFO scope restoring the prior policy even after failure/cancellation. */
    public static final class Scope implements AutoCloseable {
        private final Thread owner = Thread.currentThread();
        private final Scope previous;
        private final DiagnosticOptions options;
        private boolean closed;

        /** Installs options without copying or owning the supplied sink. */
        private Scope(DiagnosticOptions options) {
            this.previous = CURRENT.get();
            this.options = options;
            CURRENT.set(this);
        }

        /** Restores the enclosing policy; invalid cross-thread/out-of-order close leaves state unchanged. */
        @Override
        public void close() {
            if (Thread.currentThread() != owner || (!closed && CURRENT.get() != this)) {
                throw new IllegalStateException("Diagnostic scopes must close on their owner thread in LIFO order.");
            }
            if (!closed) {
                closed = true;
                if (previous == null) {
                    CURRENT.remove();
                } else {
                    CURRENT.set(previous);
                }
            }
        }
    }
}
