/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 * author: Petros Pissias <petrospis at gmail.com>
 *
 */
package io.github.ppissias.jtransient.photometry;

/**
 * Classification of a measured star after variability scoring.
 */
public enum VariabilityTier {
    /** Not scored: too few measurements, mostly non-linear, or the session was not ready. */
    NOT_SCORED,
    /** Scores within what same-brightness stars show. */
    CONSTANT,
    /** Passed scoring but failed two or more gates. */
    REJECTED,
    /** Passed scoring but failed exactly one gate. */
    POSSIBLE,
    /** Passed scoring and every gate. */
    HIGH_CONFIDENCE
}
