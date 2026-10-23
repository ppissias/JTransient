package io.github.ppissias.jtransient.execution;

import java.util.Objects;
import java.util.function.Consumer;

/** Immutable runtime diagnostic policy; the synchronous caller-owned sink must tolerate concurrent workers. */
public final class DiagnosticOptions {
    private final boolean enabled;
    private final transient Consumer<String> sink;

    /** Captures a runtime sink without taking ownership; sink exceptions propagate as operation failures. */
    public DiagnosticOptions(boolean enabled, Consumer<String> sink) {
        this.enabled = enabled;
        this.sink = Objects.requireNonNull(sink, "sink");
    }

    /** Captures the current standard-output stream for the compatibility debug adapter. */
    public static DiagnosticOptions standardOutput(boolean enabled) {
        return new DiagnosticOptions(enabled, System.out::print);
    }

    /** Reports whether this operation emits diagnostic text. */
    public boolean enabled() {
        return enabled;
    }

    /** Emits one already-formatted message synchronously, or nothing when disabled. */
    void write(String message) {
        if (enabled) {
            sink.accept(message);
        }
    }
}
