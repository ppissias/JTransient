package io.github.ppissias.jtransient.execution;

import java.util.Objects;

/** Terminal execution failure with original scientific/listener cause and stable stage/frame context. */
public final class OperationFailedException extends RuntimeException {
    private final OperationFailure failure;

    /** Captures small diagnostic metadata without retaining image buffers or replacing the original cause. */
    public OperationFailedException(OperationFailure failure) {
        super(describe(Objects.requireNonNull(failure, "failure")), failure.cause());
        this.failure = failure;
    }

    /** Returns structured metadata for API, job and GUI failure presentation. */
    public OperationFailure failure() {
        return failure;
    }

    /** Formats a useful message while preserving structured original identity independently. */
    private static String describe(OperationFailure failure) {
        FrameIdentity frame = failure.frame();
        return "JTransient failed at " + failure.stage()
                + (frame == null ? "" : " for frame " + frame.sequenceIndex() + " [" + frame.filename() + "]")
                + ": " + failure.message();
    }
}
