/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 * author: Petros Pissias <petrospis at gmail.com>
 *
 */
package io.github.ppissias.jtransient.core;

/**
 * Typed failure thrown by an accelerated {@link CoarseSearchBackend} so callers fall back to Java with a clear reason.
 */
public final class CoarseBackendException extends Exception {

    /** Broad failure classification used for fallback messaging and diagnostics. */
    public enum Kind {
        UNAVAILABLE,
        BUILD,
        SELF_TEST,
        OUT_OF_MEMORY,
        DEVICE_LOST,
        LAUNCH
    }

    /** Classification of this backend failure. */
    public final Kind kind;

    /**
     * Creates a backend exception without an underlying cause.
     *
     * @param kind failure category
     * @param message human-readable explanation
     */
    public CoarseBackendException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    /**
     * Creates a backend exception wrapping an underlying driver or native failure.
     *
     * @param kind failure category
     * @param message human-readable explanation
     * @param cause underlying failure
     */
    public CoarseBackendException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }
}
