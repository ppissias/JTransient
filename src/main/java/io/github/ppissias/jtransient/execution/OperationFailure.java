package io.github.ppissias.jtransient.execution;

import java.util.Objects;

/** Structured runtime failure context; retains the original cause in memory, not in serialized diagnostics. */
public final class OperationFailure {
    private final StageId stage;
    private final FrameIdentity frame;
    private final String message;
    private final transient Throwable cause;

    /** Captures the failing stage, optional original frame identity and original exception without wrapping or mutating it. */
    public OperationFailure(StageId stage, FrameIdentity frame, Throwable cause) {
        this.stage = Objects.requireNonNull(stage, "stage");
        this.frame = frame;
        this.cause = Objects.requireNonNull(cause, "cause");
        this.message = cause.getMessage();
    }

    /** Returns a stable stage ID, not a parsed progress message. */
    public StageId stage() { return stage; }

    /** Returns the original sequence identity, or null for session-level work. */
    public FrameIdentity frame() { return frame; }

    /** Returns the original exception's message, which may be null. */
    public String message() { return message; }

    /** Returns the same original cause object; this runtime-only reference is never serialized. */
    public Throwable cause() { return cause; }
}
