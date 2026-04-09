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

import io.github.ppissias.jtransient.core.ResidualTransientAnalysis;
import io.github.ppissias.jtransient.core.SlowMoverAnalysis;
import io.github.ppissias.jtransient.core.SourceExtractor;
import io.github.ppissias.jtransient.core.TrackLinker;
import io.github.ppissias.jtransient.telemetry.PipelineTelemetry;
import java.util.List;

/**
 * Aggregates all exported data products produced by a full pipeline run.
 *
 * <p>This is the primary return type of {@link JTransientEngine#runPipeline(List,
 * io.github.ppissias.jtransient.config.DetectionConfig, TransientEngineProgressListener)}.
 * Confirmed tracks, suspected same-frame streak groupings, rescued anomalies, leftover
 * transients, and diagnostic products all live here so callers can decide how much of the
 * pipeline output they want to keep.</p>
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

    /** Grouped slow-mover stage output with per-candidate diagnostics. */
    public final SlowMoverAnalysis slowMoverAnalysis;

    /** Legacy slow-mover stack export kept temporarily for compatibility. */
    public final short[][] slowMoverStackData;
    /** Legacy slow-mover median veto mask export kept temporarily for compatibility. */
    public final boolean[][] slowMoverMedianVetoMask;
    /** Legacy accepted slow-mover object export kept temporarily for compatibility. */
    public final List<SourceExtractor.DetectedObject> slowMoverCandidates;
    /** Single-frame anomalies rescued after tracking without being treated as tracks. */
    public final List<TrackLinker.AnomalyDetection> anomalies;

    /** A chronological, frame-by-frame list of all non-stationary transient detections carried through tracking. */
    public final List<List<SourceExtractor.DetectedObject>> allTransients;
    /** A chronological, frame-by-frame list of detections still uncategorized after tracks and anomalies are exported. */
    public final List<List<SourceExtractor.DetectedObject>> unclassifiedTransients;

    /** Residual-transient analysis of leftover point detections after normal classification. */
    public final ResidualTransientAnalysis residualTransientAnalysis;

    /** The pixel-perfect Boolean veto mask generated from the master star map. */
    public final boolean[][] masterVetoMask;

    /** The diagnostic points representing the relative translation vector of the dither/drift */
    public final List<SourceExtractor.Pixel> driftPoints;

    /** A stack containing the maximum pixel values across the sequence. */
    public final short[][] maximumStackData;

    /**
     * Creates the unified pipeline result payload using the legacy slow-mover export fields.
     *
     * @param tracks returned tracks and suspected streak groupings
     * @param telemetry pipeline counters and diagnostics
     * @param masterStackData median master stack used for vetoing
     * @param masterStars stationary objects extracted from the master stack
     * @param slowMoverStackData compatibility export of the slow-mover stack
     * @param slowMoverMedianVetoMask compatibility export of the slow-mover median veto mask
     * @param slowMoverCandidates compatibility export of accepted slow-mover detections
     * @param anomalies rescued single-frame anomalies that did not become tracks
     * @param allTransients full post-veto transient population grouped by frame
     * @param unclassifiedTransients leftover detections still unclassified after tracking and anomaly rescue
     * @param residualTransientAnalysis post-processing of the leftover detections
     * @param masterVetoMask veto mask generated from the master star map
     * @param driftPoints per-frame drift diagnostics
     * @param maximumStackData maximum-value stack built from the retained frames
     */
    public PipelineResult(List<TrackLinker.Track> tracks,
                          PipelineTelemetry telemetry,
                          short[][] masterStackData,
                          List<SourceExtractor.DetectedObject> masterStars,
                          short[][] slowMoverStackData,
                          boolean[][] slowMoverMedianVetoMask,
                          List<SourceExtractor.DetectedObject> slowMoverCandidates,
                          List<TrackLinker.AnomalyDetection> anomalies,
                          List<List<SourceExtractor.DetectedObject>> allTransients,
                          List<List<SourceExtractor.DetectedObject>> unclassifiedTransients,
                          ResidualTransientAnalysis residualTransientAnalysis,
                          boolean[][] masterVetoMask,
                          List<SourceExtractor.Pixel> driftPoints,
                          short[][] maximumStackData) {
        this(tracks, telemetry, masterStackData, masterStars, SlowMoverAnalysis.empty(),
                slowMoverStackData, slowMoverMedianVetoMask, slowMoverCandidates, anomalies,
                allTransients, unclassifiedTransients, residualTransientAnalysis,
                masterVetoMask, driftPoints, maximumStackData);
    }

    /**
     * Creates the unified pipeline result payload.
     *
     * @param tracks returned tracks and suspected streak groupings
     * @param telemetry pipeline counters and diagnostics
     * @param masterStackData median master stack used for vetoing
     * @param masterStars stationary objects extracted from the master stack
     * @param slowMoverAnalysis grouped slow-mover diagnostics and candidates
     * @param slowMoverStackData compatibility export of the slow-mover stack
     * @param slowMoverMedianVetoMask compatibility export of the slow-mover median veto mask
     * @param slowMoverCandidates compatibility export of accepted slow-mover detections
     * @param anomalies rescued single-frame anomalies that did not become tracks
     * @param allTransients full post-veto transient population grouped by frame
     * @param unclassifiedTransients leftover detections still unclassified after tracking and anomaly rescue
     * @param residualTransientAnalysis post-processing of the leftover detections
     * @param masterVetoMask veto mask generated from the master star map
     * @param driftPoints per-frame drift diagnostics
     * @param maximumStackData maximum-value stack built from the retained frames
     */
    public PipelineResult(List<TrackLinker.Track> tracks,
                          PipelineTelemetry telemetry,
                          short[][] masterStackData,
                          List<SourceExtractor.DetectedObject> masterStars,
                          SlowMoverAnalysis slowMoverAnalysis,
                          short[][] slowMoverStackData,
                          boolean[][] slowMoverMedianVetoMask,
                          List<SourceExtractor.DetectedObject> slowMoverCandidates,
                          List<TrackLinker.AnomalyDetection> anomalies,
                          List<List<SourceExtractor.DetectedObject>> allTransients,
                          List<List<SourceExtractor.DetectedObject>> unclassifiedTransients,
                          ResidualTransientAnalysis residualTransientAnalysis,
                          boolean[][] masterVetoMask,
                          List<SourceExtractor.Pixel> driftPoints,
                          short[][] maximumStackData) {
        this.tracks = tracks;
        this.telemetry = telemetry;
        this.masterStackData = masterStackData;
        this.masterStars = masterStars;
        this.slowMoverAnalysis = slowMoverAnalysis != null ? slowMoverAnalysis : SlowMoverAnalysis.empty();
        this.slowMoverStackData = slowMoverStackData;
        this.slowMoverMedianVetoMask = slowMoverMedianVetoMask;
        this.slowMoverCandidates = slowMoverCandidates;
        this.anomalies = anomalies;
        this.allTransients = allTransients;
        this.unclassifiedTransients = unclassifiedTransients;
        this.residualTransientAnalysis = residualTransientAnalysis;
        this.masterVetoMask = masterVetoMask;
        this.driftPoints = driftPoints;
        this.maximumStackData = maximumStackData;
    }
}
