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
import io.github.ppissias.jtransient.core.SlowMoverCandidateResult;
import io.github.ppissias.jtransient.model.AnomalyDetection;
import io.github.ppissias.jtransient.model.DetectedObject;
import io.github.ppissias.jtransient.model.Track;
import io.github.ppissias.jtransient.photometry.VariableStarAnalysis;

import java.util.List;

/**
 * One finished stage of a pipeline run and its products, as handed to a {@link PipelineStageObserver}. The stages
 * arrive in this order; an optional stage that is switched off still arrives, with {@code enabled} false and empty
 * products. The products are the same objects the run's {@link PipelineResult} holds.
 */
public sealed interface PipelineStage {

    /** The median reference of the kept frames and the stationary stars found in it. */
    record MasterReference(short[][] medianStack, List<DetectedObject> masterStars) implements PipelineStage {
    }

    /** The maximum stack of the kept frames and the slow-mover candidates found in it (none when switched off). */
    record SlowMovers(boolean enabled, short[][] maximumStack, List<SlowMoverCandidateResult> candidates)
            implements PipelineStage {
    }

    /**
     * Point and streak tracks, single-frame anomalies and the star mask the tracking applied. Common-motion filtering
     * and residual analysis work on the detections no track or anomaly took, so these stay as they are.
     */
    record Tracking(List<Track> tracks, List<AnomalyDetection> anomalies, boolean[][] starMask) implements PipelineStage {
    }

    /** Groups of leftover detections that moved together, removed as instrumental (none when switched off). */
    record CommonMotion(boolean enabled, List<ResidualTransientAnalysis.CommonMotionGroup> groups) implements PipelineStage {
    }

    /** Local rescue candidates and activity clusters among the remaining leftovers (empty when switched off). */
    record ResidualAnalysis(boolean enabled, ResidualTransientAnalysis analysis) implements PipelineStage {
    }

    /** Stationary-star photometry and its variable-star candidates (empty when switched off). */
    record Photometry(boolean enabled, VariableStarAnalysis analysis) implements PipelineStage {
    }
}
