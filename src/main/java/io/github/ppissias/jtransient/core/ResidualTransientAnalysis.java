/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 * author: Petros Pissias <petrospis at gmail.com>
 *
 */
package io.github.ppissias.jtransient.core;

import io.github.ppissias.jtransient.model.DetectedObject;
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
    /** Compact sources removed because they share one displacement. Empty when none were found. */
    public final List<CommonMotionGroup> commonMotionGroups;

    /**
     * Creates an immutable residual-analysis result bundle.
     */
    public ResidualTransientAnalysis(List<LocalRescueCandidate> localRescueCandidates,
                                     List<LocalActivityCluster> localActivityClusters) {
        this(localRescueCandidates, localActivityClusters, Collections.emptyList());
    }

    /**
     * Creates an immutable residual-analysis result bundle, including common-motion groups.
     */
    public ResidualTransientAnalysis(List<LocalRescueCandidate> localRescueCandidates,
                                     List<LocalActivityCluster> localActivityClusters,
                                     List<CommonMotionGroup> commonMotionGroups) {
        this.localRescueCandidates = Collections.unmodifiableList(new ArrayList<>(localRescueCandidates));
        this.localActivityClusters = Collections.unmodifiableList(new ArrayList<>(localActivityClusters));
        this.commonMotionGroups = Collections.unmodifiableList(new ArrayList<>(
                commonMotionGroups == null ? Collections.emptyList() : commonMotionGroups));
    }

    /**
     * Returns an empty residual-analysis result.
     */
    public static ResidualTransientAnalysis empty() {
        return new ResidualTransientAnalysis(Collections.emptyList(), Collections.emptyList());
    }

    /**
     * Separated compact sources that share one displacement over at least three frames.
     */
    public static final class CommonMotionGroup {
        /** One entry per source, ordered by frame. */
        public final List<List<DetectedObject>> sources;
        /** Number of frames shared by every source in the group. */
        public final int sharedFrameCount;

        /**
         * Creates one common-motion group.
         *
         * @param sources detections of each separated source
         * @param sharedFrameCount number of frames shared by every source
         */
        public CommonMotionGroup(List<List<DetectedObject>> sources, int sharedFrameCount) {
            List<List<DetectedObject>> copied = new ArrayList<>();
            if (sources != null) {
                for (List<DetectedObject> source : sources) {
                    copied.add(List.copyOf(source));
                }
            }
            this.sources = List.copyOf(copied);
            this.sharedFrameCount = sharedFrameCount;
        }
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
        /** Category assigned to the weak rescue candidate. */
        public final LocalRescueKind kind;
        /** Ordered detections that make up the weak local candidate. */
        public final List<DetectedObject> points;
        /** Shared summary metrics for this candidate. */
        public final LocalTransientMetrics metrics;
        /** Ranking score used by the residual-analysis stage. */
        public final double score;

        /**
         * Creates one local rescue candidate.
         *
         * @param kind candidate category
         * @param points detections assigned to the candidate
         * @param metrics summary measurements for the candidate
         * @param score ranking score assigned by the analyzer
         */
        public LocalRescueCandidate(LocalRescueKind kind,
                                    List<DetectedObject> points,
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
        /** Detections grouped into the broad review cluster. */
        public final List<DetectedObject> points;
        /** Shared summary metrics for the cluster. */
        public final LocalTransientMetrics metrics;
        /** Radius used to link detections into this cluster. */
        public final double linkageRadiusPixels;

        /**
         * Creates one broad local activity cluster.
         *
         * @param points detections grouped into the cluster
         * @param metrics summary measurements for the cluster
         * @param linkageRadiusPixels clustering radius used to build the cluster
         */
        public LocalActivityCluster(List<DetectedObject> points,
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
        /** Number of detections represented by the metric bundle. */
        public final int pointCount;
        /** Number of unique frames represented by the detections. */
        public final int uniqueFrameCount;
        /** Inclusive frame span from earliest to latest detection. */
        public final int frameSpan;
        /** Number of skipped frames inside the observed span. */
        public final int totalGapFrames;
        /** Straight-line displacement between the earliest and latest detections. */
        public final double totalDisplacementPixels;
        /** Mean step size between consecutive detections. */
        public final double averageStepPixels;
        /** Largest step size between consecutive detections. */
        public final double maxStepPixels;
        /** Root-mean-square line-fit error for the grouped detections. */
        public final double linearityRmsePixels;
        /** Spatial radius of the grouped detections around their centroid. */
        public final double clusterRadiusPixels;
        /** Mean peak signal proxy across the grouped detections. */
        public final double averageSignal;
        /** Fraction of the frame span that actually contains detections. */
        public final double frameCoverage;
        /** Centroid X position of the grouped detections. */
        public final double centroidX;
        /** Centroid Y position of the grouped detections. */
        public final double centroidY;

        /**
         * Creates the shared metric bundle for a local rescue candidate or activity cluster.
         *
         * @param pointCount number of detections represented
         * @param uniqueFrameCount number of unique frames represented
         * @param frameSpan inclusive frame span
         * @param totalGapFrames skipped frames inside the span
         * @param totalDisplacementPixels end-to-end displacement
         * @param averageStepPixels average step size between detections
         * @param maxStepPixels maximum step size between detections
         * @param linearityRmsePixels line-fit root-mean-square error
         * @param clusterRadiusPixels spatial cluster radius
         * @param averageSignal average signal proxy
         * @param frameCoverage fraction of the span containing detections
         * @param centroidX centroid X coordinate
         * @param centroidY centroid Y coordinate
         */
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
