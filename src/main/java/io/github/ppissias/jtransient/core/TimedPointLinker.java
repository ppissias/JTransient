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
import io.github.ppissias.jtransient.engine.TransientEngineProgressListener;
import io.github.ppissias.jtransient.execution.CancellationScope;
import io.github.ppissias.jtransient.execution.DiagnosticContext;
import io.github.ppissias.jtransient.model.DetectedObject;
import io.github.ppissias.jtransient.model.Track;
import io.github.ppissias.jtransient.telemetry.TrackerTelemetry;

import java.util.ArrayList;
import java.util.List;

/** The time-based point linker: tracks of detections that move at a constant velocity between timestamped frames. */
final class TimedPointLinker {

    private TimedPointLinker() {
    }

    /**
     * Proposes point tracks whose velocity stays constant in time, ranks them (longest, then best score, then
     * earliest) and accepts each one that shares no detection with a track accepted before it.
     */
    static void link(List<List<DetectedObject>> transients, int minPointsRequired, DetectionConfig config,
                     TrackerTelemetry telemetry, DetectionAssignments assignments, List<Track> pointTracks,
                     TransientEngineProgressListener listener) {
        int numFrames = transients.size();
        int loopMax = numFrames - 2;
        double angleToleranceRad = Math.toRadians(config.angleToleranceDegrees);
        List<RankedTrackCandidate> timeTrackCandidates = new ArrayList<>();

        if (DiagnosticContext.enabled()) DiagnosticContext.println("DEBUG: [PHASE 3.5] Timestamps detected. Running Time-Based Velocity Linking...");
        if (listener != null) listener.onProgressUpdate(50, "Analyzing precise velocity kinematics...");

        int generatedTimeTracks = 0;

        // Calculate total points to process for accurate progress reporting
        int totalP1Candidates = 0;
        for (int i = 0; i < loopMax; i++) {
            CancellationScope.check();
            totalP1Candidates += transients.get(i).size();
        }
        int p1Processed = 0;

        for (int f1 = 0; f1 < loopMax; f1++) {
            CancellationScope.check();
            for (DetectedObject p1 : transients.get(f1)) {
                CancellationScope.check();
                
                p1Processed++;
                if (listener != null && (p1Processed % 50 == 0 || p1Processed == totalP1Candidates)) {
                    int progress = 50 + (int) (((double) p1Processed / Math.max(1, totalP1Candidates)) * 10.0);
                    listener.onProgressUpdate(progress, "Analyzing time-based kinematics: point " + p1Processed + " of " + totalP1Candidates);
                }

                if (assignments.isClaimed(p1)) continue;

                for (int f2 = f1 + 1; f2 < numFrames - 1; f2++) {
                    if (assignments.isClaimed(p1)) break;
                    for (DetectedObject p2 : transients.get(f2)) {
                        CancellationScope.check();
                        if (assignments.isClaimed(p1)) break;
                        if (assignments.isClaimed(p2)) continue;

                        double dt12 = p2.timestamp - p1.timestamp;
                        if (dt12 <= 0) {
                            telemetry.countBaselineNonPositiveDelta++;
                            continue;
                        }

                        double dist12 = TrackGeometry.distance(p1.x, p1.y, p2.x, p2.y);
                        if (dist12 < config.maxStarJitter) {
                            telemetry.countBaselineJitter++;
                            continue;
                        }

                        // Morphological profile check (maxJumpPixels explicitly bypassed for extreme speeds)
                        if (!TrackGeometry.isProfileConsistent(p1, p2, config)) {
                            telemetry.countBaselineSize++;
                            continue;
                        }

                        // --- STRICT EXPOSURE KINEMATICS ---
                        if (config.strictExposureKinematics && p1.timestamp != -1 && p2.timestamp != -1 && p1.exposureDuration > 0) {
                            // If it didn't streak, it moved less than its diameter during the exposure
                            double maxMovementInExposure = Math.sqrt(p1.pixelArea) + config.maxStarJitter;
                            double maxVelocity = maxMovementInExposure / p1.exposureDuration; // px per ms
                            double maxAllowedJump = (maxVelocity * dt12) * 1.5 + config.maxStarJitter; // 50% safety buffer

                            if (dist12 > maxAllowedJump) {
                                telemetry.countBaselineJump++;
                                continue;
                            }
                        }

                        double v12 = dist12 / dt12; // Velocity in pixels per ms
                        double expectedAngle = Math.atan2(p2.y - p1.y, p2.x - p1.x);

                        Track currentTrack = new Track();
                        currentTrack.addPoint(p1);
                        currentTrack.addPoint(p2);

                        DetectedObject currentLineAnchor = p2;
                        double currentVelocity = v12;

                        for (int f3 = f2 + 1; f3 < numFrames; f3++) {
                            DetectedObject bestMatch = null;
                            double bestVError = Double.MAX_VALUE;

                            for (DetectedObject p3 : transients.get(f3)) {
                                if (assignments.isClaimed(p3)) continue;

                                double dt23 = p3.timestamp - currentLineAnchor.timestamp;
                                if (dt23 <= 0) {
                                    telemetry.countP3NonPositiveDelta++;
                                    continue;
                                }

                                double dist23 = TrackGeometry.distance(currentLineAnchor.x, currentLineAnchor.y, p3.x, p3.y);

                                // --- STRICT EXPOSURE KINEMATICS ---
                                if (config.strictExposureKinematics && currentLineAnchor.timestamp != -1 && p3.timestamp != -1 && currentLineAnchor.exposureDuration > 0) {
                                    double maxMovementInExposure = Math.sqrt(currentLineAnchor.pixelArea) + config.maxStarJitter;
                                    double maxVelocity = maxMovementInExposure / currentLineAnchor.exposureDuration;
                                    double maxAllowedJump = (maxVelocity * dt23) * 1.5 + config.maxStarJitter;

                                    if (dist23 > maxAllowedJump) {
                                        telemetry.countP3Jump++;
                                        continue;
                                    }
                                }

                                // --- TRUE KINEMATIC VELOCITY & ANGLE MATCHING ---
                                double v23 = dist23 / dt23;
                                double vDiff = Math.abs(v23 - currentVelocity);

                                // Allowed variance is a combination of the percentage tolerance AND the physical sub-pixel seeing jitter
                                double allowedVDiff = (currentVelocity * config.timeBasedVelocityTolerance) + (config.maxStarJitter / dt23);

                                if (vDiff > allowedVDiff) {
                                    telemetry.countP3VelocityMismatch++;
                                    continue;
                                }

                                // Ensure the point physically lies on the exact trajectory line
                                double currentBaselineDist = TrackGeometry.distance(p1.x, p1.y, currentLineAnchor.x, currentLineAnchor.y);
                                double lineError = TrackGeometry.distanceToLineOptimized(p1, currentLineAnchor, p3, currentBaselineDist);

                                if (lineError > config.predictionTolerance) {
                                    telemetry.countP3NotLine++;
                                    continue;
                                }

                                double actualAngle = Math.atan2(p3.y - currentLineAnchor.y, p3.x - currentLineAnchor.x);
                                // Dynamically relax the angle tolerance for short jumps where sub-pixel jitter causes massive angular swings
                                double dynamicAngleTolerance = Math.max(angleToleranceRad, Math.atan2(config.maxStarJitter + 1.0, dist23));

                                if (!TrackGeometry.isDirectionConsistent(expectedAngle, actualAngle, dynamicAngleTolerance)) {
                                    telemetry.countP3WrongDirection++;
                                    continue;
                                }

                                if (!TrackGeometry.isProfileConsistent(currentLineAnchor, p3, config)) {
                                    telemetry.countP3Size++;
                                    continue;
                                }

                                // Prioritize the point that best matches the expected velocity
                                if (vDiff < bestVError) {
                                    bestVError = vDiff;
                                    bestMatch = p3;
                                }
                            }

                            if (bestMatch != null) {
                                currentTrack.addPoint(bestMatch);

                                // UPDATE PROGRESSIVE VECTOR: Anchor to Point 1 to prevent "drunk" jitter accumulation!
                                double totalDt = bestMatch.timestamp - p1.timestamp;
                                double totalDist = TrackGeometry.distance(p1.x, p1.y, bestMatch.x, bestMatch.y);
                                currentVelocity = totalDist / totalDt;
                                expectedAngle = Math.atan2(bestMatch.y - p1.y, bestMatch.x - p1.x);

                                currentLineAnchor = bestMatch;
                            }
                        }

                        if (currentTrack.points.size() >= minPointsRequired) {
                            currentTrack.isTimeBasedTrack = true;
                            timeTrackCandidates.add(new RankedTrackCandidate(
                                    currentTrack,
                                    scoreTimeBasedTrackCandidate(currentTrack)));
                            generatedTimeTracks++;
                        } else {
                            telemetry.countTrackTooShort++;
                        }
                    }
                }
            }
        }

        timeTrackCandidates.sort(TimedPointLinker::compareRankedTimeTrackCandidates);

        int timeTracksFound = 0;
        for (RankedTrackCandidate candidate : timeTrackCandidates) {
            CancellationScope.check();
            if (assignments.conflictsWith(candidate.track)) {
                telemetry.countTrackDuplicate++;
                continue;
            }

            pointTracks.add(candidate.track);
            assignments.claimAll(candidate.track.points);
            timeTracksFound++;
            if (DiagnosticContext.enabled()) {
                DiagnosticContext.printf(
                        "DEBUG: [PHASE 3.5] Accepted time-based point track -> points=%d startFrame=%d endFrame=%d%n",
                        candidate.track.points.size(),
                        candidate.track.points.get(0).sourceFrameIndex,
                        candidate.track.points.get(candidate.track.points.size() - 1).sourceFrameIndex
                );
            }
        }

        if (DiagnosticContext.enabled()) {
            DiagnosticContext.println("DEBUG: [PHASE 3.5] Completed. Generated " + generatedTimeTracks
                    + " precise time-based track candidate(s), accepted " + timeTracksFound + " after ranking.");
        }
    }

    /**
     * Internal wrapper used to rank time-based track proposals before conflict resolution.
     */
    private static class RankedTrackCandidate {
        private final Track track;
        private final double score;

        private RankedTrackCandidate(Track track, double score) {
            this.track = track;
            this.score = score;
        }
    }

    /**
     * Scores time-based track candidates so that longer, straighter, and more uniformly paced tracks rank first.
     */
    private static double scoreTimeBasedTrackCandidate(Track track) {
        if (track.points.size() < 2) {
            return Double.NEGATIVE_INFINITY;
        }

        DetectedObject start = track.points.get(0);
        DetectedObject end = track.points.get(track.points.size() - 1);

        double frameSpan = Math.max(1, end.sourceFrameIndex - start.sourceFrameIndex);
        double coverageRatio = (double) track.points.size() / (frameSpan + 1.0);
        double totalDistance = TrackGeometry.distance(start.x, start.y, end.x, end.y);
        double baselineDistance = Math.max(totalDistance, 0.0001);
        double expectedAngle = Math.atan2(end.y - start.y, end.x - start.x);
        double expectedDelta = Math.max(TrackGeometry.getTrackDelta(start, end), 0.0001);
        double expectedSpeed = totalDistance / expectedDelta;

        double totalLineError = 0.0;
        int lineSamples = 0;
        for (int i = 1; i < track.points.size() - 1; i++) {
            CancellationScope.check();
            totalLineError += TrackGeometry.distanceToLineOptimized(start, end, track.points.get(i), baselineDistance);
            lineSamples++;
        }

        double totalAngleError = 0.0;
        double totalSpeedError = 0.0;
        int segmentSamples = 0;
        for (int i = 0; i < track.points.size() - 1; i++) {
            CancellationScope.check();
            DetectedObject p1 = track.points.get(i);
            DetectedObject p2 = track.points.get(i + 1);

            double segmentDelta = TrackGeometry.getTrackDelta(p1, p2);
            if (segmentDelta <= 0) {
                continue;
            }

            double segmentDistance = TrackGeometry.distance(p1.x, p1.y, p2.x, p2.y);
            double segmentSpeed = segmentDistance / segmentDelta;
            double segmentAngle = Math.atan2(p2.y - p1.y, p2.x - p1.x);

            totalAngleError += TrackGeometry.angularDifference(expectedAngle, segmentAngle);
            if (expectedSpeed > 0.0) {
                totalSpeedError += Math.abs(segmentSpeed - expectedSpeed) / expectedSpeed;
            }
            segmentSamples++;
        }

        double averageLineError = lineSamples == 0 ? 0.0 : totalLineError / lineSamples;
        double averageAngleError = segmentSamples == 0 ? 0.0 : totalAngleError / segmentSamples;
        double averageSpeedError = segmentSamples == 0 ? 0.0 : totalSpeedError / segmentSamples;

        return (track.points.size() * 1000.0)
                + (coverageRatio * 250.0)
                + (frameSpan * 50.0)
                + (totalDistance * 5.0)
                - (averageLineError * 150.0)
                - (averageAngleError * 250.0)
                - (averageSpeedError * 400.0);
    }

    /**
     * Orders ranked time-based candidates by track length, then by score, then by start frame.
     */
    private static int compareRankedTimeTrackCandidates(RankedTrackCandidate left, RankedTrackCandidate right) {
        int byLength = Integer.compare(right.track.points.size(), left.track.points.size());
        if (byLength != 0) {
            return byLength;
        }

        int byScore = Double.compare(right.score, left.score);
        if (byScore != 0) {
            return byScore;
        }

        DetectedObject leftStart = left.track.points.get(0);
        DetectedObject rightStart = right.track.points.get(0);
        return Integer.compare(leftStart.sourceFrameIndex, rightStart.sourceFrameIndex);
    }
}
