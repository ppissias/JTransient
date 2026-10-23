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
import io.github.ppissias.jtransient.model.DetectedObject;
import io.github.ppissias.jtransient.model.Track;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The final streak pass: merges confirmed streak tracks, promoted single streaks and same-frame anomaly groups that
 * belong to one streak, and checks that a streak's pieces move consistently in time.
 */
final class StreakConsolidator {

    private StreakConsolidator() {
    }

    /**
     * One candidate merge between two streak-like tracks.
     */
    private static class StreakMergeCandidate {
        private final Track mergedTrack;
        private final int frameGap;
        private final double maxLineError;

        private StreakMergeCandidate(Track mergedTrack,
                                     int frameGap,
                                     double maxLineError) {
            this.mergedTrack = mergedTrack;
            this.frameGap = frameGap;
            this.maxLineError = maxLineError;
        }
    }

    /**
     * One frame-level centroid sample used to validate streak motion after same-frame fragments are merged.
     */
    private static class StreakFrameSample {
        private final int frameIndex;
        private final long timestamp;
        private final double x;
        private final double y;

        private StreakFrameSample(int frameIndex, long timestamp, double x, double y) {
            this.frameIndex = frameIndex;
            this.timestamp = timestamp;
            this.x = x;
            this.y = y;
        }
    }

    /**
     * Runs one final reconciliation pass over confirmed streaks, promoted single streaks and the groups of
     * same-frame anomalies, so nearby fragments collapse into one streak history.
     */
    static List<Track> consolidateStreakTracks(List<Track> confirmedStreakTracks,
                                                       List<Track> anomalyGroups,
                                                       DetectionConfig config) {
        List<Track> orderedTracks = new ArrayList<>();

        if (confirmedStreakTracks != null) {
            List<Track> orderedConfirmedTracks = new ArrayList<>();
            for (Track track : confirmedStreakTracks) {
                CancellationScope.check();
                if (isStreakLike(track)) {
                    orderedConfirmedTracks.add(copyTrack(track));
                }
            }
            orderedConfirmedTracks.sort(StreakConsolidator::compareStreakConsolidationPriority);
            orderedTracks.addAll(orderedConfirmedTracks);
        }

        if (anomalyGroups != null) {
            List<Track> orderedGroups = new ArrayList<>();
            for (Track track : anomalyGroups) {
                CancellationScope.check();
                if (isStreakLike(track)) {
                    orderedGroups.add(copyTrack(track));
                }
            }
            orderedGroups.sort(StreakConsolidator::compareStreakConsolidationPriority);
            orderedTracks.addAll(orderedGroups);
        }

        if (orderedTracks.isEmpty()) {
            return new ArrayList<>();
        }

        List<Track> consolidatedTracks = new ArrayList<>();
        for (Track track : orderedTracks) {
            CancellationScope.check();
            normalizeStreakTrackOrder(track);

            int bestIndex = -1;
            StreakMergeCandidate bestCandidate = null;
            for (int i = 0; i < consolidatedTracks.size(); i++) {
                CancellationScope.check();
                StreakMergeCandidate currentCandidate = evaluateStreakMerge(consolidatedTracks.get(i), track, config);
                if (isBetterStreakMergeCandidate(currentCandidate, bestCandidate)) {
                    bestCandidate = currentCandidate;
                    bestIndex = i;
                }
            }

            if (bestIndex >= 0) {
                consolidatedTracks.set(bestIndex, bestCandidate.mergedTrack);
            } else {
                consolidatedTracks.add(track);
            }
        }

        consolidatedTracks.sort(StreakConsolidator::compareStreakConsolidationPriority);
        return consolidatedTracks;
    }

    /**
     * Returns whether a track participates in the streak-only consolidation pass.
     */
    private static boolean isStreakLike(Track track) {
        return track != null
                && track.points != null
                && !track.points.isEmpty()
                && (track.isStreakTrack || track instanceof AnomalyGroupTrack);
    }

    /**
     * Creates a shallow copy of a track while preserving the original detection references.
     */
    private static Track copyTrack(Track source) {
        Track copy = source instanceof AnomalyGroupTrack ? new AnomalyGroupTrack() : new Track();
        copy.isStreakTrack = source.isStreakTrack;
        copy.isTimeBasedTrack = source.isTimeBasedTrack;
        copy.points.addAll(source.points);
        return copy;
    }

    /**
     * Orders streak points chronologically and, within each frame, along the best-fit streak axis.
     */
    static void normalizeStreakTrackOrder(Track track) {
        if (track == null || track.points == null || track.points.size() < 2) {
            return;
        }

        DetectedObject[] endpoints = determineStreakLineEndpoints(track.points);
        if (endpoints == null) {
            return;
        }

        double dx = endpoints[1].x - endpoints[0].x;
        double dy = endpoints[1].y - endpoints[0].y;
        track.points.sort((left, right) -> {
            int byFrame = Integer.compare(left.sourceFrameIndex, right.sourceFrameIndex);
            if (byFrame != 0) {
                return byFrame;
            }
            return Double.compare(
                    TrackGeometry.projectionAlongLine(left, endpoints[0].x, endpoints[0].y, dx, dy),
                    TrackGeometry.projectionAlongLine(right, endpoints[0].x, endpoints[0].y, dx, dy)
            );
        });
    }

    /**
     * Ranks confirmed streaks ahead of anomaly groups, then streaks seen in more frames, then with more points, then
     * brighter ones first, so they absorb later inputs.
     */
    private static int compareStreakConsolidationPriority(Track left, Track right) {
        int byConfirmed = Boolean.compare(right.isStreakTrack, left.isStreakTrack);
        if (byConfirmed != 0) {
            return byConfirmed;
        }

        int byFrames = Integer.compare(countUniqueFrames(right), countUniqueFrames(left));
        if (byFrames != 0) {
            return byFrames;
        }

        int byPoints = Integer.compare(right.points.size(), left.points.size());
        if (byPoints != 0) {
            return byPoints;
        }

        return Double.compare(maxTrackPeakSigma(right), maxTrackPeakSigma(left));
    }

    /**
     * Evaluates whether two streak-like tracks can be merged into one physically plausible streak history.
     */
    private static StreakMergeCandidate evaluateStreakMerge(Track existingTrack,
                                                            Track candidateTrack,
                                                            DetectionConfig config) {
        if (!isStreakLike(existingTrack) || !isStreakLike(candidateTrack)) {
            return null;
        }

        int frameGap = computeStreakFrameGap(existingTrack, candidateTrack);
        if (!passesStreakFrameGapGate(existingTrack, candidateTrack, frameGap)) {
            return null;
        }

        List<DetectedObject> combinedPoints = combineTrackPoints(existingTrack, candidateTrack);
        DetectedObject[] endpoints = determineStreakLineEndpoints(combinedPoints);
        if (endpoints == null) {
            return null;
        }

        double baselineDistance = TrackGeometry.distance(endpoints[0].x, endpoints[0].y, endpoints[1].x, endpoints[1].y);
        if (baselineDistance < 1.0) {
            return null;
        }

        double mergedAngle = Math.atan2(endpoints[1].y - endpoints[0].y, endpoints[1].x - endpoints[0].x);
        double angleTolerance = getStreakConsolidationAngleTolerance(existingTrack, candidateTrack, config);

        double existingAngle = estimateTrackOrientation(existingTrack);
        if (!Double.isNaN(existingAngle) && !TrackGeometry.anglesMatch(existingAngle, mergedAngle, angleTolerance)) {
            return null;
        }

        double candidateAngle = estimateTrackOrientation(candidateTrack);
        if (!Double.isNaN(candidateAngle) && !TrackGeometry.anglesMatch(candidateAngle, mergedAngle, angleTolerance)) {
            return null;
        }

        double maxLineError = 0.0;
        double maxAllowedLineError = getStreakConsolidationLineTolerance(config);
        for (DetectedObject point : combinedPoints) {
            CancellationScope.check();
            double lineError = TrackGeometry.distanceToLineOptimized(endpoints[0], endpoints[1], point, baselineDistance);
            maxLineError = Math.max(maxLineError, lineError);
            if (lineError > maxAllowedLineError) {
                return null;
            }
        }

        if (!hasConsistentStreakMotion(combinedPoints, endpoints[0], endpoints[1], config)) {
            return null;
        }

        Track mergedTrack = buildMergedStreakTrack(existingTrack, candidateTrack, combinedPoints);
        return new StreakMergeCandidate(mergedTrack, frameGap, maxLineError);
    }

    /**
     * Prefers same-frame or adjacent-frame reconciliations, then the tighter common line fit.
     */
    private static boolean isBetterStreakMergeCandidate(StreakMergeCandidate currentCandidate,
                                                        StreakMergeCandidate bestCandidate) {
        if (currentCandidate == null) {
            return false;
        }
        if (bestCandidate == null) {
            return true;
        }
        if (currentCandidate.frameGap != bestCandidate.frameGap) {
            return currentCandidate.frameGap < bestCandidate.frameGap;
        }
        if (Math.abs(currentCandidate.maxLineError - bestCandidate.maxLineError) > 1e-6) {
            return currentCandidate.maxLineError < bestCandidate.maxLineError;
        }
        return currentCandidate.mergedTrack.points.size() > bestCandidate.mergedTrack.points.size();
    }

    /**
     * Builds the merged track and promotes it to a confirmed streak once a real streak or cross-frame motion exists.
     */
    private static Track buildMergedStreakTrack(Track left,
                                                Track right,
                                                List<DetectedObject> combinedPoints) {
        Track mergedTrack = left instanceof AnomalyGroupTrack && right instanceof AnomalyGroupTrack
                ? new AnomalyGroupTrack() : new Track();
        boolean spansMultipleFrames = countUniqueFrames(combinedPoints) > 1;
        mergedTrack.isStreakTrack = left.isStreakTrack || right.isStreakTrack || spansMultipleFrames;
        mergedTrack.points.addAll(combinedPoints);
        normalizeStreakTrackOrder(mergedTrack);
        return mergedTrack;
    }

    /**
     * Collects one centroid sample per frame so streak motion can be checked without treating same-frame fragments
     * as separate time steps.
     */
    private static List<StreakFrameSample> buildStreakFrameSamples(List<DetectedObject> points) {
        List<DetectedObject> orderedPoints = new ArrayList<>(points);
        orderedPoints.sort((left, right) -> {
            int byFrame = Integer.compare(left.sourceFrameIndex, right.sourceFrameIndex);
            if (byFrame != 0) {
                return byFrame;
            }
            return Long.compare(left.timestamp, right.timestamp);
        });

        List<StreakFrameSample> frameSamples = new ArrayList<>();
        int currentFrame = Integer.MIN_VALUE;
        double sumX = 0.0;
        double sumY = 0.0;
        int count = 0;
        long timestamp = -1L;

        for (DetectedObject point : orderedPoints) {
            CancellationScope.check();
            if (count > 0 && point.sourceFrameIndex != currentFrame) {
                frameSamples.add(new StreakFrameSample(currentFrame, timestamp, sumX / count, sumY / count));
                sumX = 0.0;
                sumY = 0.0;
                count = 0;
                timestamp = -1L;
            }

            currentFrame = point.sourceFrameIndex;
            sumX += point.x;
            sumY += point.y;
            count++;
            if (point.timestamp != -1L && point.exposureDuration > 0L) {
                timestamp = timestamp == -1L ? point.timestamp : Math.min(timestamp, point.timestamp);
            }
        }

        if (count > 0) {
            frameSamples.add(new StreakFrameSample(currentFrame, timestamp, sumX / count, sumY / count));
        }

        return frameSamples;
    }

    /**
     * Enforces monotonic forward motion and a loose timestamp-aware speed check before two streak fragments merge.
     */
    private static boolean hasConsistentStreakMotion(List<DetectedObject> points,
                                                     DetectedObject lineStart,
                                                     DetectedObject lineEnd,
                                                     DetectionConfig config) {
        List<StreakFrameSample> frameSamples = buildStreakFrameSamples(points);
        if (frameSamples.size() <= 1) {
            return true;
        }

        // A unit axis, so the projected steps are in pixels like the minimum step.
        double axisLength = Math.hypot(lineEnd.x - lineStart.x, lineEnd.y - lineStart.y);
        if (!(axisLength > 0.0)) {
            return false;
        }
        double dx = (lineEnd.x - lineStart.x) / axisLength;
        double dy = (lineEnd.y - lineStart.y) / axisLength;
        double minimumProjectedStep = Math.max(0.5, config.maxStarJitter * 0.5);
        double directionSign = 0.0;
        List<Double> projectedSpeeds = new ArrayList<>();
        boolean hasTimestamps = false;

        double previousProjection = TrackGeometry.projectionAlongLine(
                frameSamples.get(0).x,
                frameSamples.get(0).y,
                lineStart.x,
                lineStart.y,
                dx,
                dy
        );

        for (int i = 1; i < frameSamples.size(); i++) {
            CancellationScope.check();
            StreakFrameSample previous = frameSamples.get(i - 1);
            StreakFrameSample current = frameSamples.get(i);

            double currentProjection = TrackGeometry.projectionAlongLine(current.x, current.y, lineStart.x, lineStart.y, dx, dy);
            double projectedStep = currentProjection - previousProjection;

            if (Math.abs(projectedStep) < minimumProjectedStep) {
                return false;
            }

            double currentDirection = Math.signum(projectedStep);
            if (directionSign == 0.0) {
                directionSign = currentDirection;
            } else if (currentDirection != directionSign) {
                return false;
            }

            if (previous.timestamp != -1L && current.timestamp != -1L) {
                long dt = current.timestamp - previous.timestamp;
                if (dt <= 0L) {
                    return false;
                }
                projectedSpeeds.add(Math.abs(projectedStep) / dt);
                hasTimestamps = true;
            }

            previousProjection = currentProjection;
        }

        if (hasTimestamps && projectedSpeeds.size() >= 2) {
            List<Double> sortedSpeeds = new ArrayList<>(projectedSpeeds);
            sortedSpeeds.sort(Double::compareTo);
            double medianSpeed = sortedSpeeds.get(sortedSpeeds.size() / 2);
            if (medianSpeed <= 0.0) {
                return false;
            }

            int consistentSegments = 0;
            for (double speed : projectedSpeeds) {
                CancellationScope.check();
                if (Math.abs(speed - medianSpeed) <= (medianSpeed * getStreakTimeConsistencyTolerance(config))) {
                    consistentSegments++;
                }
            }
            double consistencyRatio = (double) consistentSegments / projectedSpeeds.size();
            return consistencyRatio >= config.rhythmMinConsistencyRatio;
        }

        if (!hasTimestamps && frameSamples.size() >= 3) {
            Track representativeTrack = new Track();
            for (StreakFrameSample sample : frameSamples) {
                CancellationScope.check();
                DetectedObject point = createSyntheticPoint(sample.x, sample.y);
                point.sourceFrameIndex = sample.frameIndex;
                representativeTrack.addPoint(point);
            }
            return TrackGeometry.hasSteadyRhythm(
                    representativeTrack,
                    config.rhythmAllowedVariance,
                    config.rhythmStationaryThreshold,
                    config.rhythmMinConsistencyRatio
            );
        }

        return true;
    }

    /**
     * Validates timestamp consistency for a streak candidate using one aggregated sample per frame.
     */
    static boolean passesStreakTimeConsistency(List<DetectedObject> points,
                                                       DetectionConfig config) {
        if (points == null || points.size() < 2) {
            return true;
        }

        DetectedObject[] endpoints = determineStreakLineEndpoints(points);
        if (endpoints == null) {
            // No line can be measured: same-frame fragments are fine, but streaks in several frames that did not
            // move are not a moving object.
            return countUniqueFrames(points) <= 1;
        }

        return hasConsistentStreakMotion(points, endpoints[0], endpoints[1], config);
    }

    /**
     * Uses a slightly looser angular tolerance than the initial linker so near-identical same-frame fragments can merge.
     */
    private static double getStreakConsolidationAngleTolerance(Track left,
                                                               Track right,
                                                               DetectionConfig config) {
        double baseTolerance = Math.toRadians(config.angleToleranceDegrees);
        double minimumDegrees = tracksShareFrame(left, right) ? 5.0 : 4.0;
        return Math.max(baseTolerance, Math.toRadians(minimumDegrees));
    }

    /**
     * Line tolerance of the final streak consolidation: twice the prediction tolerance, and at least the tolerance of
     * the anomaly groups.
     */
    private static double getStreakConsolidationLineTolerance(DetectionConfig config) {
        return Math.max(AnomalyRescuer.ANOMALY_GROUP_LINE_TOLERANCE, config.predictionTolerance * 2.0);
    }

    /**
     * Returns the generous relative speed tolerance used for timestamp-aware streak validation.
     */
    private static double getStreakTimeConsistencyTolerance(DetectionConfig config) {
        return Math.max(0.0, config.streakTimeConsistencyTolerance);
    }

    /**
     * Returns the minimum frame separation between two tracks, or zero if they overlap in time.
     */
    private static int computeStreakFrameGap(Track left, Track right) {
        int leftStart = getMinFrameIndex(left.points);
        int leftEnd = getMaxFrameIndex(left.points);
        int rightStart = getMinFrameIndex(right.points);
        int rightEnd = getMaxFrameIndex(right.points);

        if (leftEnd < rightStart) {
            return rightStart - leftEnd;
        }
        if (rightEnd < leftStart) {
            return leftStart - rightEnd;
        }
        return 0;
    }

    /**
     * Prevents the consolidation pass from bridging large unsupported gaps with too little evidence.
     */
    private static boolean passesStreakFrameGapGate(Track left,
                                                    Track right,
                                                    int frameGap) {
        if (frameGap == 0) {
            return true;
        }

        if (countUniqueFrames(left) == 1 && countUniqueFrames(right) == 1) {
            return frameGap <= 1;
        }

        return frameGap <= 2;
    }

    /**
     * Returns the dominant orientation of a track using its frame endpoints when available.
     */
    private static double estimateTrackOrientation(Track track) {
        if (track == null || track.points == null || track.points.isEmpty()) {
            return Double.NaN;
        }
        if (track.points.size() == 1) {
            return track.points.get(0).angle;
        }

        DetectedObject[] endpoints = determineStreakLineEndpoints(track.points);
        if (endpoints == null) {
            return track.points.get(0).angle;
        }
        return Math.atan2(endpoints[1].y - endpoints[0].y, endpoints[1].x - endpoints[0].x);
    }

    /**
     * Chooses a stable line definition for ordering and consolidation from either frame endpoints or the longest span.
     */
    private static DetectedObject[] determineStreakLineEndpoints(List<DetectedObject> points) {
        List<StreakFrameSample> frameSamples = buildStreakFrameSamples(points);
        if (frameSamples.size() >= 2) {
            StreakFrameSample startSample = frameSamples.get(0);
            StreakFrameSample endSample = frameSamples.get(frameSamples.size() - 1);
            if (TrackGeometry.distance(startSample.x, startSample.y, endSample.x, endSample.y) >= 1.0) {
                return new DetectedObject[]{
                        createSyntheticPoint(startSample.x, startSample.y),
                        createSyntheticPoint(endSample.x, endSample.y)
                };
            }
        }

        DetectedObject[] farthestPair = findFarthestPair(points);
        if (farthestPair == null) {
            return null;
        }

        if (TrackGeometry.distance(farthestPair[0].x, farthestPair[0].y, farthestPair[1].x, farthestPair[1].y) < 1.0) {
            return null;
        }
        return farthestPair;
    }

    /**
     * Finds the two detections with the largest separation inside one track.
     */
    private static DetectedObject[] findFarthestPair(List<DetectedObject> points) {
        if (points == null || points.size() < 2) {
            return null;
        }

        DetectedObject bestStart = null;
        DetectedObject bestEnd = null;
        double maxDistance = -1.0;
        for (int i = 0; i < points.size() - 1; i++) {
            CancellationScope.check();
            for (int j = i + 1; j < points.size(); j++) {
                CancellationScope.check();
                DetectedObject left = points.get(i);
                DetectedObject right = points.get(j);
                double span = TrackGeometry.distance(left.x, left.y, right.x, right.y);
                if (span > maxDistance) {
                    maxDistance = span;
                    bestStart = left;
                    bestEnd = right;
                }
            }
        }

        if (bestStart == null || bestEnd == null) {
            return null;
        }
        return new DetectedObject[]{bestStart, bestEnd};
    }

    /**
     * Merges the unique detections from two tracks while preserving the original detection identities.
     */
    private static List<DetectedObject> combineTrackPoints(Track left, Track right) {
        List<DetectedObject> combinedPoints = new ArrayList<>();
        Set<DetectedObject> seenPoints = new HashSet<>();
        for (DetectedObject point : left.points) {
            CancellationScope.check();
            if (seenPoints.add(point)) {
                combinedPoints.add(point);
            }
        }
        for (DetectedObject point : right.points) {
            CancellationScope.check();
            if (seenPoints.add(point)) {
                combinedPoints.add(point);
            }
        }
        return combinedPoints;
    }

    /**
     * Returns whether two tracks share at least one frame.
     */
    private static boolean tracksShareFrame(Track left, Track right) {
        Set<Integer> frames = new HashSet<>();
        for (DetectedObject point : left.points) {
            CancellationScope.check();
            frames.add(point.sourceFrameIndex);
        }
        for (DetectedObject point : right.points) {
            CancellationScope.check();
            if (frames.contains(point.sourceFrameIndex)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Counts the unique frames represented by a track.
     */
    private static int countUniqueFrames(Track track) {
        return countUniqueFrames(track.points);
    }

    /**
     * Counts the unique frames represented by a list of detections.
     */
    private static int countUniqueFrames(List<DetectedObject> points) {
        Set<Integer> frames = new HashSet<>();
        for (DetectedObject point : points) {
            CancellationScope.check();
            frames.add(point.sourceFrameIndex);
        }
        return frames.size();
    }

    /**
     * Returns the strongest peak sigma inside a track so stable tracks sort ahead of weak fragments.
     */
    private static double maxTrackPeakSigma(Track track) {
        double maxPeakSigma = Double.NEGATIVE_INFINITY;
        for (DetectedObject point : track.points) {
            CancellationScope.check();
            maxPeakSigma = Math.max(maxPeakSigma, point.peakSigma);
        }
        return maxPeakSigma;
    }

    /**
     * Returns the earliest frame index represented by the supplied detections.
     */
    private static int getMinFrameIndex(List<DetectedObject> points) {
        int minFrameIndex = Integer.MAX_VALUE;
        for (DetectedObject point : points) {
            CancellationScope.check();
            minFrameIndex = Math.min(minFrameIndex, point.sourceFrameIndex);
        }
        return minFrameIndex == Integer.MAX_VALUE ? -1 : minFrameIndex;
    }

    /**
     * Returns the latest frame index represented by the supplied detections.
     */
    private static int getMaxFrameIndex(List<DetectedObject> points) {
        int maxFrameIndex = Integer.MIN_VALUE;
        for (DetectedObject point : points) {
            CancellationScope.check();
            maxFrameIndex = Math.max(maxFrameIndex, point.sourceFrameIndex);
        }
        return maxFrameIndex == Integer.MIN_VALUE ? -1 : maxFrameIndex;
    }

    /**
     * Creates a lightweight synthetic point for frame-level motion checks and ordering.
     */
    private static DetectedObject createSyntheticPoint(double x, double y) {
        return new DetectedObject(x, y, 0.0, 1);
    }
}
