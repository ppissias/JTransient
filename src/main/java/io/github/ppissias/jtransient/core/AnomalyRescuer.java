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

import io.github.ppissias.jtransient.config.DetectionConfig;
import io.github.ppissias.jtransient.execution.CancellationScope;
import io.github.ppissias.jtransient.execution.DiagnosticContext;
import io.github.ppissias.jtransient.model.AnomalyDetection;
import io.github.ppissias.jtransient.model.AnomalyType;
import io.github.ppissias.jtransient.model.DetectedObject;
import io.github.ppissias.jtransient.model.Track;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Single-frame anomaly rescue: detections no track claimed that are bright enough on their own, and the joining of
 * rescued pieces that belong to a confirmed streak.
 */
final class AnomalyRescuer {

    /** Rescued anomalies of one frame within this distance of a line are offered to a streak as one group, in pixels. */
    static final double ANOMALY_GROUP_LINE_TOLERANCE = 6.0;

    private AnomalyRescuer() {
    }

    /**
     * Rescues the unclaimed candidates that are a sharp glint or a broad high-energy flash as single-frame anomalies,
     * and claims them. Returns no anomalies when {@code enableAnomalyRescue} is off.
     */
    static List<AnomalyDetection> rescue(List<List<DetectedObject>> anomalyCandidates, DetectionAssignments assignments,
                                         DetectionConfig config) {
        List<AnomalyDetection> anomalies = new ArrayList<>();
        if (config.enableAnomalyRescue) {
            if (DiagnosticContext.enabled()) {
                DiagnosticContext.println("DEBUG: [PHASE 5] Scanning merged transients for High-Energy Anomalies...");
            }

            for (int i = 0; i < anomalyCandidates.size(); i++) {
                CancellationScope.check();
                for (DetectedObject orphan : anomalyCandidates.get(i)) {
                    if (!assignments.isClaimed(orphan)) {

                        // Rescue either a sharp glint or a broader high-energy flash.
                        AnomalyType anomalyType = classifyAnomalyRescue(orphan, config);
                        if (anomalyType != null) {
                            anomalies.add(new AnomalyDetection(orphan, anomalyType));
                            assignments.claim(orphan);
                        }
                    }
                }
            }
        }
        return anomalies;
    }

    /**
     * Moves rescued anomalies that are pieces of a streak into it: same-frame groups that merge with a confirmed
     * streak track, and anomalies on a confirmed streak's path. Removes them from {@code anomalies}; returns the
     * consolidated streak tracks.
     */
    static List<Track> joinStreakPieces(List<Track> confirmedTracks, List<AnomalyDetection> anomalies, DetectionConfig config) {
        // A streak can break into pieces that are rescued as anomalies. Collinear anomalies of one frame are offered
        // to the streak consolidation as a group; a group that joins no confirmed streak track stays as anomalies.
        List<Track> anomalyGroups = groupSameFrameAnomalies(anomalies);
        confirmedTracks = StreakConsolidator.consolidateStreakTracks(confirmedTracks, anomalyGroups, config);
        confirmedTracks.removeIf(track -> track instanceof AnomalyGroupTrack);
        if (!anomalyGroups.isEmpty()) {
            Set<DetectedObject> joinedStreaks = new HashSet<>();
            for (Track track : confirmedTracks) {
                CancellationScope.check();
                joinedStreaks.addAll(track.points);
            }
            anomalies.removeIf(anomaly -> joinedStreaks.contains(anomaly.object));
        }

        // A streak through a star, or a satellite's flash, is rescued as an anomaly. On the path of a confirmed streak,
        // in its own frame or where the streak's motion puts it in another frame, it is a piece of that streak.
        List<DetectedObject> rescued = new ArrayList<>();
        for (AnomalyDetection anomaly : anomalies) {
            CancellationScope.check();
            rescued.add(anomaly.object);
        }
        Set<DetectedObject> onStreakPaths = FaintStreakFragmentGrouper.joinAnomaliesOnStreakPaths(confirmedTracks, rescued);
        if (!onStreakPaths.isEmpty()) {
            anomalies.removeIf(anomaly -> onStreakPaths.contains(anomaly.object));
            for (Track track : confirmedTracks) {
                CancellationScope.check();
                StreakConsolidator.normalizeStreakTrackOrder(track);
            }
        }
        return confirmedTracks;
    }

    /**
     * One line through rescued anomalies of one frame, before it becomes a group.
     */
    private static class AnomalyLineCandidate {
        private final List<DetectedObject> objects;
        private final double span;

        private AnomalyLineCandidate(List<DetectedObject> objects, double span) {
            this.objects = objects;
            this.span = span;
        }
    }

    /**
     * Accepts either a sharp single-frame glint or a broader anomaly with enough integrated energy.
     */
    static boolean qualifiesForAnomalyRescue(DetectedObject orphan, DetectionConfig config) {
        return classifyAnomalyRescue(orphan, config) != null;
    }

    /**
     * Classifies anomaly rescue so single-frame anomalies can be exported separately from tracks.
     */
    static AnomalyType classifyAnomalyRescue(DetectedObject orphan, DetectionConfig config) {
        if (orphan.pixelArea < config.anomalyMinPixels) {
            return null;
        }

        if (orphan.peakSigma >= config.anomalyMinPeakSigma) {
            return AnomalyType.PEAK_SIGMA;
        }

        if (orphan.pixelArea >= config.anomalyMinIntegratedPixels
                && orphan.integratedSigma >= config.anomalyMinIntegratedSigma
                && orphan.peakSigma >= config.anomalyMinPeakSigmaFloor) {
            return AnomalyType.INTEGRATED_SIGMA;
        }

        return null;
    }

    /**
     * Groups the rescued anomalies of each frame that lie on a line (at least three within
     * {@link #ANOMALY_GROUP_LINE_TOLERANCE}). A group never links across frames, and is only a candidate piece of a
     * streak: it is kept when the consolidation merges it into a confirmed streak track.
     */
    private static List<Track> groupSameFrameAnomalies(List<AnomalyDetection> anomalies) {
        Map<Integer, List<DetectedObject>> byFrame = new java.util.TreeMap<>();
        for (AnomalyDetection anomaly : anomalies) {
            CancellationScope.check();
            if (anomaly.object.sourceFrameIndex >= 0) {
                byFrame.computeIfAbsent(anomaly.object.sourceFrameIndex, frame -> new ArrayList<>()).add(anomaly.object);
            }
        }
        List<Track> groups = new ArrayList<>();
        for (List<DetectedObject> frameObjects : byFrame.values()) {
            CancellationScope.check();
            List<DetectedObject> remainingObjects = new ArrayList<>(frameObjects);
            while (remainingObjects.size() >= 3) {
                CancellationScope.check();
                AnomalyLineCandidate bestLine = bestAnomalyLine(remainingObjects);
                if (bestLine == null) {
                    break;
                }
                Track group = new AnomalyGroupTrack();
                group.points.addAll(bestLine.objects);
                groups.add(group);
                remainingObjects.removeAll(bestLine.objects);
            }
        }
        return groups;
    }

    /**
     * The line through the most anomalies of one frame, the longest one among equals; null when no line holds three.
     */
    private static AnomalyLineCandidate bestAnomalyLine(List<DetectedObject> frameObjects) {
        AnomalyLineCandidate bestLine = null;
        for (int i = 0; i < frameObjects.size() - 1; i++) {
            CancellationScope.check();
            for (int j = i + 1; j < frameObjects.size(); j++) {
                CancellationScope.check();
                DetectedObject start = frameObjects.get(i);
                DetectedObject end = frameObjects.get(j);
                double baselineDistance = TrackGeometry.distance(start.x, start.y, end.x, end.y);
                if (baselineDistance < 1.0) {
                    continue;
                }
                List<DetectedObject> collinearObjects = new ArrayList<>();
                for (DetectedObject candidate : frameObjects) {
                    if (TrackGeometry.distanceToLineOptimized(start, end, candidate, baselineDistance) <= ANOMALY_GROUP_LINE_TOLERANCE) {
                        collinearObjects.add(candidate);
                    }
                }
                if (collinearObjects.size() < 3) {
                    continue;
                }
                double dx = end.x - start.x;
                double dy = end.y - start.y;
                collinearObjects.sort((left, right) -> Double.compare(
                        TrackGeometry.projectionAlongLine(left, start.x, start.y, dx, dy),
                        TrackGeometry.projectionAlongLine(right, start.x, start.y, dx, dy)));
                DetectedObject first = collinearObjects.get(0);
                DetectedObject last = collinearObjects.get(collinearObjects.size() - 1);
                AnomalyLineCandidate currentLine = new AnomalyLineCandidate(collinearObjects,
                        TrackGeometry.distance(first.x, first.y, last.x, last.y));
                if (bestLine == null || currentLine.objects.size() > bestLine.objects.size()
                        || (currentLine.objects.size() == bestLine.objects.size() && currentLine.span > bestLine.span + 1e-6)) {
                    bestLine = currentLine;
                }
            }
        }
        return bestLine;
    }
}
