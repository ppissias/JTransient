/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 * author: Petros Pissias <petrospis at gmail.com>
 *
 */
package io.github.ppissias.jtransient.engine;

import io.github.ppissias.jtransient.core.SourceExtractor;

import java.util.List;

/**
 * Pairing of one frame label with the post-veto transient detections exported for that frame.
 */
public final class FrameTransients {
    /** Source frame label. */
    public final String filename;
    /** Post-veto transient objects for the frame. */
    public final List<SourceExtractor.DetectedObject> transients;
    /** Full extraction result for the same frame. */
    public final SourceExtractor.ExtractionResult extractionResult;

    /**
     * Creates a transient-export payload for one frame.
     */
    public FrameTransients(String filename,
                           List<SourceExtractor.DetectedObject> transients,
                           SourceExtractor.ExtractionResult extractionResult) {
        this.filename = filename;
        this.transients = transients;
        this.extractionResult = extractionResult;
    }
}
