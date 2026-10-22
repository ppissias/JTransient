package io.github.ppissias.jtransient.execution;

/**
 * Small diagnostic frame identity, borrowing no image data and never substituting retained-list position for sequence ID.
 *
 * @param sequenceIndex caller's original sequence identifier, which need not be contiguous
 * @param filename caller's label, possibly null when unavailable
 */
public record FrameIdentity(int sequenceIndex, String filename) {
}
