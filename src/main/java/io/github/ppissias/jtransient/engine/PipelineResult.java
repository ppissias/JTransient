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
import io.github.ppissias.jtransient.core.TrackLinker;
import io.github.ppissias.jtransient.telemetry.PipelineTelemetry;
import java.util.List;

/**
 * Aggregates all exported data products produced by a full pipeline run.
 */
public class PipelineResult {
    /** Returned track-like detections, including confirmed tracks and suspected same-frame streak groupings. */
    public final List<TrackLinker.Track> tracks;
    /** Per-phase counters and diagnostics collected during processing. */
    public final PipelineTelemetry telemetry;
    /** Median master stack built from the quality-filtered frames. */
    public final short[][] masterStackData;
    /** Stationary objects extracted from the median master stack. */
    public final List<SourceExtractor.DetectedObject> masterStars;

    /** Slow-mover stack produced from the configured middle band of sorted pixels. */
    public final short[][] slowMoverStackData;
    /** Pixel veto mask built from objects extracted on the median stack with the slow-mover extraction thresholds. */
    public final boolean[][] slowMoverMedianVetoMask;
    /** Elongated candidates that survived the slow-mover artifact filters. */
    public final List<SourceExtractor.DetectedObject> slowMoverCandidates;
    /** Single-frame anomalies rescued after tracking without being treated as tracks. */
    public final List<TrackLinker.AnomalyDetection> anomalies;

    /** A chronological, frame-by-frame list of the remaining transient detections exported after tracking. */
    public final List<List<SourceExtractor.DetectedObject>> allRemainingTransients;

    /** The pixel-perfect Boolean veto mask generated from the master star map. */
    public final boolean[][] masterVetoMask;

    /** The diagnostic points representing the relative translation vector of the dither/drift */
    public final List<SourceExtractor.Pixel> driftPoints;

    /** A stack containing the maximum pixel values across the sequence. */
    public final short[][] maximumStackData;

    /**
     * Creates the unified pipeline result payload.
     */
    public PipelineResult(List<TrackLinker.Track> tracks,
                          PipelineTelemetry telemetry,
                          short[][] masterStackData,
                          List<SourceExtractor.DetectedObject> masterStars,
                          short[][] slowMoverStackData,
                          boolean[][] slowMoverMedianVetoMask,
                          List<SourceExtractor.DetectedObject> slowMoverCandidates,
                          List<TrackLinker.AnomalyDetection> anomalies,
                          List<List<SourceExtractor.DetectedObject>> allRemainingTransients,
                          boolean[][] masterVetoMask,
                          List<SourceExtractor.Pixel> driftPoints,
                          short[][] maximumStackData) {
        this.tracks = tracks;
        this.telemetry = telemetry;
        this.masterStackData = masterStackData;
        this.masterStars = masterStars;
        this.slowMoverStackData = slowMoverStackData;
        this.slowMoverMedianVetoMask = slowMoverMedianVetoMask;
        this.slowMoverCandidates = slowMoverCandidates;
        this.anomalies = anomalies;
        this.allRemainingTransients = allRemainingTransients;
        this.masterVetoMask = masterVetoMask;
        this.driftPoints = driftPoints;
        this.maximumStackData = maximumStackData;
    }
}
