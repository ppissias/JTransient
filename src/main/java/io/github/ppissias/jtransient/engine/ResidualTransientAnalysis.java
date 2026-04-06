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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Post-tracking analysis of leftover non-streak point detections.
 */
public final class ResidualTransientAnalysis {
    /** Object-like local rescue candidates mined from leftover detections. */
    public final List<LocalRescueCandidate> localRescueCandidates;
    /** Broader spatial review clusters built from leftover detections not consumed by rescue candidates. */
    public final List<LocalActivityCluster> localActivityClusters;

    /**
     * Creates an immutable residual-analysis result bundle.
     */
    public ResidualTransientAnalysis(List<LocalRescueCandidate> localRescueCandidates,
                                     List<LocalActivityCluster> localActivityClusters) {
        this.localRescueCandidates = Collections.unmodifiableList(new ArrayList<>(localRescueCandidates));
        this.localActivityClusters = Collections.unmodifiableList(new ArrayList<>(localActivityClusters));
    }

    /**
     * Returns an empty residual-analysis result.
     */
    public static ResidualTransientAnalysis empty() {
        return new ResidualTransientAnalysis(Collections.emptyList(), Collections.emptyList());
    }

    /**
     * Category assigned to a weak local rescue candidate.
     */
    public enum LocalRescueKind {
        MICRO_DRIFT,
        SPARSE_LOCAL_DRIFT,
        LOCAL_REPEAT
    }

    /**
     * A weak object-like rescue candidate discovered among leftover transients.
     */
    public static final class LocalRescueCandidate {
        public final LocalRescueKind kind;
        public final List<SourceExtractor.DetectedObject> points;
        public final LocalTransientMetrics metrics;
        public final double score;

        public LocalRescueCandidate(LocalRescueKind kind,
                                    List<SourceExtractor.DetectedObject> points,
                                    LocalTransientMetrics metrics,
                                    double score) {
            this.kind = kind;
            this.points = Collections.unmodifiableList(new ArrayList<>(points));
            this.metrics = metrics;
            this.score = score;
        }
    }

    /**
     * A broad same-area review cluster built from points left after local rescue candidate extraction.
     */
    public static final class LocalActivityCluster {
        public final List<SourceExtractor.DetectedObject> points;
        public final LocalTransientMetrics metrics;
        public final double linkageRadiusPixels;

        public LocalActivityCluster(List<SourceExtractor.DetectedObject> points,
                                    LocalTransientMetrics metrics,
                                    double linkageRadiusPixels) {
            this.points = Collections.unmodifiableList(new ArrayList<>(points));
            this.metrics = metrics;
            this.linkageRadiusPixels = linkageRadiusPixels;
        }
    }

    /**
     * Summary measurements shared by both rescue candidates and broad local activity clusters.
     */
    public static final class LocalTransientMetrics {
        public final int pointCount;
        public final int uniqueFrameCount;
        public final int frameSpan;
        public final int totalGapFrames;
        public final double totalDisplacementPixels;
        public final double averageStepPixels;
        public final double maxStepPixels;
        public final double linearityRmsePixels;
        public final double clusterRadiusPixels;
        public final double averageSignal;
        public final double frameCoverage;
        public final double centroidX;
        public final double centroidY;

        public LocalTransientMetrics(int pointCount,
                                     int uniqueFrameCount,
                                     int frameSpan,
                                     int totalGapFrames,
                                     double totalDisplacementPixels,
                                     double averageStepPixels,
                                     double maxStepPixels,
                                     double linearityRmsePixels,
                                     double clusterRadiusPixels,
                                     double averageSignal,
                                     double frameCoverage,
                                     double centroidX,
                                     double centroidY) {
            this.pointCount = pointCount;
            this.uniqueFrameCount = uniqueFrameCount;
            this.frameSpan = frameSpan;
            this.totalGapFrames = totalGapFrames;
            this.totalDisplacementPixels = totalDisplacementPixels;
            this.averageStepPixels = averageStepPixels;
            this.maxStepPixels = maxStepPixels;
            this.linearityRmsePixels = linearityRmsePixels;
            this.clusterRadiusPixels = clusterRadiusPixels;
            this.averageSignal = averageSignal;
            this.frameCoverage = frameCoverage;
            this.centroidX = centroidX;
            this.centroidY = centroidY;
        }
    }
}
