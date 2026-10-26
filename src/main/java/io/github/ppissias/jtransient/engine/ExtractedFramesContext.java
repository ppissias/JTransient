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

import io.github.ppissias.jtransient.model.Pixel;
import io.github.ppissias.jtransient.core.DataArea;
import io.github.ppissias.jtransient.core.FrameRegistration;
import io.github.ppissias.jtransient.core.SourceExtractor;
import io.github.ppissias.jtransient.quality.FrameQualityAnalyzer;
import io.github.ppissias.jtransient.telemetry.PipelineTelemetry;
import io.github.ppissias.jtransient.telemetry.TrackerTelemetry;
import io.github.ppissias.jtransient.execution.FrameIdentity;

import java.util.List;
import java.util.Map;

/**
 * Internal engine context shared between extraction and the downstream orchestration stages.
 */
final class ExtractedFramesContext {
    /** Kept associations in retained-position order, including empty extraction results. */
    final List<PreparedFrame> retainedFrames;
    /** Source-extraction results for frames that passed quality control. */
    final List<SourceExtractor.ExtractionResult> cleanFramesData;
    /** Raw image frames that survived session-level rejection. */
    final List<ImageFrame> cleanFrames;
    /** Quality metrics of the clean frames, same order as {@link #cleanFrames}. */
    final List<FrameQualityAnalyzer.FrameMetrics> cleanFrameMetrics;
    /** Telemetry accumulated during the early pipeline phases. */
    final PipelineTelemetry telemetry;
    /** Monotonic System.nanoTime() value at pipeline start. */
    final long startNanos;
    /** Relative per-frame drift diagnostics derived from the valid image footprint. */
    final List<Pixel> driftPoints;
    /** Where every clean frame has image data, or null when no frame was kept. */
    final DataArea dataArea;
    /** Sky area used while extracting the kept frames. */
    final DataArea backgroundArea;
    /** Search area used while extracting the kept frames. */
    final DataArea searchArea;
    /** How registration moved each frame, measured from its fill, by sequence index. */
    final Map<Integer, FrameRegistration> frameRegistrations;

    /** Freezes small aligned containers while borrowing pixels, detections and quality products. */
    ExtractedFramesContext(List<PreparedFrame> retainedFrames,
                           PipelineTelemetry telemetry,
                           long startNanos,
                           List<Pixel> driftPoints,
                           DataArea dataArea,
                           DataArea backgroundArea,
                           DataArea searchArea,
                           Map<Integer, FrameRegistration> frameRegistrations) {
        this.retainedFrames = List.copyOf(retainedFrames);
        this.cleanFramesData = this.retainedFrames.stream()
                .map(prepared -> java.util.Objects.requireNonNull(prepared.extractionResult(), "extractionResult")).toList();
        this.cleanFrames = this.retainedFrames.stream().map(PreparedFrame::frame).toList();
        this.cleanFrameMetrics = this.retainedFrames.stream().map(PreparedFrame::metrics).toList();
        this.telemetry = telemetry;
        this.startNanos = startNanos;
        this.driftPoints = driftPoints;
        this.dataArea = dataArea;
        this.backgroundArea = backgroundArea;
        this.searchArea = searchArea;
        this.frameRegistrations = frameRegistrations;
    }

    /** Labels legacy retained-position counters with original identity, including empty detection slots. */
    void labelTrackerFrames(TrackerTelemetry trackerTelemetry) {
        for (TrackerTelemetry.FrameStarMapStat stat : trackerTelemetry.frameStarMapStats) {
            ImageFrame frame = retainedFrames.get(stat.retainedPosition()).frame();
            stat.originalFrame = new FrameIdentity(frame.sequenceIndex, frame.filename);
        }
    }
}
