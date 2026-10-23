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

import io.github.ppissias.jtransient.model.AnomalyDetection;
import io.github.ppissias.jtransient.model.DetectedObject;
import io.github.ppissias.jtransient.model.Pixel;
import io.github.ppissias.jtransient.model.Track;
import io.github.ppissias.jtransient.core.CameraFixedRejector;
import io.github.ppissias.jtransient.core.DataArea;
import io.github.ppissias.jtransient.core.ResidualTransientAnalysis;
import io.github.ppissias.jtransient.core.SlowMoverAnalysis;
import io.github.ppissias.jtransient.photometry.VariableStarAnalysis;
import io.github.ppissias.jtransient.telemetry.PipelineTelemetry;
import java.util.List;

/**
 * Aggregates all exported data products produced by a full pipeline run.
 *
 * <p>This is the primary return type of {@link JTransientEngine#runPipeline(List,
 * io.github.ppissias.jtransient.config.DetectionConfig, TransientEngineProgressListener)}.
 * Confirmed tracks, rescued anomalies, leftover
 * transients, and diagnostic products all live here so callers can decide how much of the
 * pipeline output they want to keep.</p>
 */
public class PipelineResult {
    /** Returned tracks: streak tracks, single streaks and point tracks. */
    public final List<Track> tracks;
    /** Per-phase counters and diagnostics collected during processing. */
    public final PipelineTelemetry telemetry;
    /** Median master stack built from the quality-filtered frames. */
    public final short[][] masterStackData;
    /** Stationary objects extracted from the median master stack. */
    public final List<DetectedObject> masterStars;

    /** Grouped maximum-stack morphology candidates, exact median mask, and diagnostics. */
    public final SlowMoverAnalysis slowMoverAnalysis;

    /** Compatibility alias of {@link #maximumStackData} when slow-mover detection is enabled; otherwise null. */
    public final short[][] slowMoverStackData;
    /** Compatibility alias of {@link SlowMoverAnalysis#medianMask} when enabled; otherwise null. */
    public final boolean[][] slowMoverMedianVetoMask;
    /** Compatibility list of the same candidate objects held by {@link SlowMoverAnalysis#candidates}. */
    public final List<DetectedObject> slowMoverCandidates;
    /** Single-frame anomalies rescued after tracking without being treated as tracks. */
    public final List<AnomalyDetection> anomalies;

    /** A chronological, frame-by-frame list of all non-stationary transient detections carried through tracking. */
    public final List<List<DetectedObject>> allTransients;
    /** A chronological, frame-by-frame list of detections still uncategorized after tracks and anomalies are exported. */
    public final List<List<DetectedObject>> unclassifiedTransients;

    /** Residual-transient analysis of leftover point detections after normal classification. */
    public final ResidualTransientAnalysis residualTransientAnalysis;

    /** The pixel-perfect Boolean veto mask generated from the master star map. */
    public final boolean[][] masterVetoMask;

    /** The diagnostic points representing the relative translation vector of the dither/drift */
    public final List<Pixel> driftPoints;

    /** A stack containing the maximum pixel values across the sequence. */
    public final short[][] maximumStackData;

    /** Stationary-star photometry, readiness verdict and variable-star candidates; empty when disabled. */
    public final VariableStarAnalysis variableStarAnalysis;

    /**
     * Where every kept frame holds image data, {@value DataArea#MARGIN} px in from the registration fill and the image
     * edge; null when not measured.
     */
    public final DataArea dataArea;

    /** Detections removed before tracking because they stay at one camera position (hot pixels, dust). */
    public final List<CameraFixedRejector.Group> cameraFixedGroups;

    /** Starts an empty builder; an unset slow-mover or variable-star analysis is empty, other unset fields stay null. */
    public static Builder builder() {
        return new Builder();
    }

    /** Starts a builder holding this result's fields: the same objects and arrays, not copies. */
    public Builder toBuilder() {
        Builder builder = new Builder();
        builder.tracks = tracks;
        builder.telemetry = telemetry;
        builder.masterStackData = masterStackData;
        builder.masterStars = masterStars;
        builder.slowMoverAnalysis = slowMoverAnalysis;
        builder.slowMoverStackData = slowMoverStackData;
        builder.slowMoverMedianVetoMask = slowMoverMedianVetoMask;
        builder.slowMoverCandidates = slowMoverCandidates;
        builder.anomalies = anomalies;
        builder.allTransients = allTransients;
        builder.unclassifiedTransients = unclassifiedTransients;
        builder.residualTransientAnalysis = residualTransientAnalysis;
        builder.masterVetoMask = masterVetoMask;
        builder.driftPoints = driftPoints;
        builder.maximumStackData = maximumStackData;
        builder.variableStarAnalysis = variableStarAnalysis;
        builder.dataArea = dataArea;
        builder.cameraFixedGroups = cameraFixedGroups;
        return builder;
    }

    private PipelineResult(Builder builder) {
        this.tracks = builder.tracks;
        this.telemetry = builder.telemetry;
        this.masterStackData = builder.masterStackData;
        this.masterStars = builder.masterStars;
        this.slowMoverAnalysis = builder.slowMoverAnalysis != null ? builder.slowMoverAnalysis : SlowMoverAnalysis.empty();
        this.slowMoverStackData = builder.slowMoverStackData;
        this.slowMoverMedianVetoMask = builder.slowMoverMedianVetoMask;
        this.slowMoverCandidates = builder.slowMoverCandidates;
        this.anomalies = builder.anomalies;
        this.allTransients = builder.allTransients;
        this.unclassifiedTransients = builder.unclassifiedTransients;
        this.residualTransientAnalysis = builder.residualTransientAnalysis;
        this.masterVetoMask = builder.masterVetoMask;
        this.driftPoints = builder.driftPoints;
        this.maximumStackData = builder.maximumStackData;
        this.variableStarAnalysis = builder.variableStarAnalysis != null ? builder.variableStarAnalysis : VariableStarAnalysis.empty();
        this.dataArea = builder.dataArea;
        this.cameraFixedGroups = builder.cameraFixedGroups != null ? builder.cameraFixedGroups : List.of();
    }

    /**
     * Assembles a {@link PipelineResult} by field name. Lists and arrays are kept as given, not copied, so one
     * detection stays the same object in every list that holds it.
     */
    public static final class Builder {
        private List<Track> tracks;
        private PipelineTelemetry telemetry;
        private short[][] masterStackData;
        private List<DetectedObject> masterStars;
        private SlowMoverAnalysis slowMoverAnalysis;
        private short[][] slowMoverStackData;
        private boolean[][] slowMoverMedianVetoMask;
        private List<DetectedObject> slowMoverCandidates;
        private List<AnomalyDetection> anomalies;
        private List<List<DetectedObject>> allTransients;
        private List<List<DetectedObject>> unclassifiedTransients;
        private ResidualTransientAnalysis residualTransientAnalysis;
        private boolean[][] masterVetoMask;
        private List<Pixel> driftPoints;
        private short[][] maximumStackData;
        private VariableStarAnalysis variableStarAnalysis;
        private DataArea dataArea;
        private List<CameraFixedRejector.Group> cameraFixedGroups;

        private Builder() {
        }

        /** Sets {@link PipelineResult#tracks}. */
        public Builder tracks(List<Track> tracks) {
            this.tracks = tracks;
            return this;
        }

        /** Sets {@link PipelineResult#telemetry}. */
        public Builder telemetry(PipelineTelemetry telemetry) {
            this.telemetry = telemetry;
            return this;
        }

        /** Sets {@link PipelineResult#masterStackData}. */
        public Builder masterStackData(short[][] masterStackData) {
            this.masterStackData = masterStackData;
            return this;
        }

        /** Sets {@link PipelineResult#masterStars}. */
        public Builder masterStars(List<DetectedObject> masterStars) {
            this.masterStars = masterStars;
            return this;
        }

        /** Sets {@link PipelineResult#slowMoverAnalysis}. */
        public Builder slowMoverAnalysis(SlowMoverAnalysis slowMoverAnalysis) {
            this.slowMoverAnalysis = slowMoverAnalysis;
            return this;
        }

        /** Sets {@link PipelineResult#slowMoverStackData}. */
        public Builder slowMoverStackData(short[][] slowMoverStackData) {
            this.slowMoverStackData = slowMoverStackData;
            return this;
        }

        /** Sets {@link PipelineResult#slowMoverMedianVetoMask}. */
        public Builder slowMoverMedianVetoMask(boolean[][] slowMoverMedianVetoMask) {
            this.slowMoverMedianVetoMask = slowMoverMedianVetoMask;
            return this;
        }

        /** Sets {@link PipelineResult#slowMoverCandidates}. */
        public Builder slowMoverCandidates(List<DetectedObject> slowMoverCandidates) {
            this.slowMoverCandidates = slowMoverCandidates;
            return this;
        }

        /** Sets {@link PipelineResult#anomalies}. */
        public Builder anomalies(List<AnomalyDetection> anomalies) {
            this.anomalies = anomalies;
            return this;
        }

        /** Sets {@link PipelineResult#allTransients}. */
        public Builder allTransients(List<List<DetectedObject>> allTransients) {
            this.allTransients = allTransients;
            return this;
        }

        /** Sets {@link PipelineResult#unclassifiedTransients}. */
        public Builder unclassifiedTransients(List<List<DetectedObject>> unclassifiedTransients) {
            this.unclassifiedTransients = unclassifiedTransients;
            return this;
        }

        /** Sets {@link PipelineResult#residualTransientAnalysis}. */
        public Builder residualTransientAnalysis(ResidualTransientAnalysis residualTransientAnalysis) {
            this.residualTransientAnalysis = residualTransientAnalysis;
            return this;
        }

        /** Sets {@link PipelineResult#masterVetoMask}. */
        public Builder masterVetoMask(boolean[][] masterVetoMask) {
            this.masterVetoMask = masterVetoMask;
            return this;
        }

        /** Sets {@link PipelineResult#driftPoints}. */
        public Builder driftPoints(List<Pixel> driftPoints) {
            this.driftPoints = driftPoints;
            return this;
        }

        /** Sets {@link PipelineResult#maximumStackData}. */
        public Builder maximumStackData(short[][] maximumStackData) {
            this.maximumStackData = maximumStackData;
            return this;
        }

        /** Sets {@link PipelineResult#variableStarAnalysis}. */
        public Builder variableStarAnalysis(VariableStarAnalysis variableStarAnalysis) {
            this.variableStarAnalysis = variableStarAnalysis;
            return this;
        }

        /** Sets {@link PipelineResult#dataArea}. */
        public Builder dataArea(DataArea dataArea) {
            this.dataArea = dataArea;
            return this;
        }

        /** Sets {@link PipelineResult#cameraFixedGroups}. */
        public Builder cameraFixedGroups(List<CameraFixedRejector.Group> cameraFixedGroups) {
            this.cameraFixedGroups = cameraFixedGroups;
            return this;
        }

        /** Creates the result. */
        public PipelineResult build() {
            return new PipelineResult(this);
        }
    }
}
