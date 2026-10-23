package io.github.ppissias.jtransient.execution;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/** Per-operation core diagnostic routing, independent of engine/science implementation classes. */
public final class DiagnosticContext {
    private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();
    private static final AtomicReference<BooleanSupplier> LEGACY_DEBUG = new AtomicReference<>();

    /** Prevents construction of this scoped routing utility. */
    private DiagnosticContext() {
    }

    /**
     * Installs the facade's legacy flag adapter once. This compatibility bridge is not a per-request setting;
     * applications should open a scope instead. No reverse dependency on the facade is retained in bytecode.
     */
    public static void registerLegacyDebugFlag(BooleanSupplier flag) {
        if (!LEGACY_DEBUG.compareAndSet(null, Objects.requireNonNull(flag, "flag"))) {
            throw new IllegalStateException("Legacy diagnostic adapter is already installed.");
        }
    }

    /** Captures active options, or the legacy flag/current standard-output stream outside a scope. */
    public static DiagnosticOptions currentOptions() {
        Scope scope = CURRENT.get();
        BooleanSupplier flag = LEGACY_DEBUG.get();
        return scope == null ? DiagnosticOptions.standardOutput(flag != null && flag.getAsBoolean()) : scope.options;
    }

    /** Opens inherited options, capturing compatibility defaults only once at an operation boundary. */
    public static Scope openDefaults() {
        return open(currentOptions());
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
