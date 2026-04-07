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
import io.github.ppissias.jtransient.telemetry.PipelineTelemetry;

import java.util.List;

/**
 * Internal engine context shared between extraction and the downstream orchestration stages.
 */
final class ExtractedFramesContext {
    /** Source-extraction results for frames that passed quality control. */
    final List<SourceExtractor.ExtractionResult> cleanFramesData;
    /** Raw image frames that survived session-level rejection. */
    final List<ImageFrame> cleanFrames;
    /** Telemetry accumulated during the early pipeline phases. */
    final PipelineTelemetry telemetry;
    /** Pipeline start timestamp used to calculate total runtime. */
    final long startTime;
    /** Relative per-frame drift diagnostics derived from the valid image footprint. */
    final List<SourceExtractor.Pixel> driftPoints;

    ExtractedFramesContext(List<SourceExtractor.ExtractionResult> cleanFramesData,
                           List<ImageFrame> cleanFrames,
                           PipelineTelemetry telemetry,
                           long startTime,
                           List<SourceExtractor.Pixel> driftPoints) {
        this.cleanFramesData = cleanFramesData;
        this.cleanFrames = cleanFrames;
        this.telemetry = telemetry;
        this.startTime = startTime;
        this.driftPoints = driftPoints;
    }
}
