package io.github.ppissias.jtransient.engine;

import io.github.ppissias.jtransient.core.FrameRegistration;
import io.github.ppissias.jtransient.core.SourceExtractor;
import io.github.ppissias.jtransient.quality.FrameQualityAnalyzer;

import java.util.List;
import java.util.Objects;

/**
 * Internal association of a borrowed frame and its operation-owned preparation products.
 * The descriptor's sequence index is original identity, never a retained-list position.
 * Pixels and mutable science products are shared, not cloned or made deeply immutable.
 */
record PreparedFrame(ImageFrame frame, SourceExtractor.ExtractionResult extractionResult,
                     FrameQualityAnalyzer.FrameMetrics metrics, FrameRegistration registration) {

    /** Associates quality and optional extraction/registration without allocating image buffers. */
    PreparedFrame {
        Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(metrics, "metrics");
        if (registration != null && registration.sequenceIndex != frame.sequenceIndex) {
            throw new IllegalArgumentException("Registration belongs to a different frame.");
        }
    }

    /** Returns a stable, ordered container of kept associations, including frames with no detections. */
    static List<PreparedFrame> retained(List<PreparedFrame> preparedFrames) {
        return preparedFrames.stream().filter(prepared -> !prepared.metrics.isRejected).toList();
    }
}
