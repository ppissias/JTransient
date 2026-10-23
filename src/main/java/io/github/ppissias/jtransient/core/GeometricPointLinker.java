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

/** The geometric point linker: collinear tracks with a steady rhythm, for sequences without usable timestamps. */
final class GeometricPointLinker {

    private GeometricPointLinker() {
    }

    /**
     * Proposes collinear point tracks without timestamps, prunes stalled points, and accepts a track with a steady
     * rhythm that is not a duplicate. Runs when timestamps are missing or {@code enableGeometricTrackLinking} is on.
     */
    static void link(List<List<DetectedObject>> transients, int minPointsRequired, DetectionConfig config,
                     TrackerTelemetry telemetry, DetectionAssignments assignments, List<Track> pointTracks,
                     TransientEngineProgressListener listener, boolean hasTimestamps) {
        int numFrames = transients.size();
        int loopMax = numFrames - 2;
        double angleToleranceRad = Math.toRadians(config.angleToleranceDegrees);

        if (DiagnosticContext.enabled()) {
            String mode = hasTimestamps
                    ? "optional geometric fallback enabled"
                    : "forced geometric fallback because timestamps are unavailable";
            DiagnosticContext.println("DEBUG: [PHASE 4] Applying time-agnostic geometric linker (" + mode + ")...");
            DiagnosticContext.println("  -> Track confirmation threshold: " + minPointsRequired + " points.");
        }

        for (int f1 = 0; f1 < loopMax; f1++) {
            CancellationScope.check();

            // --- SMOOTH PROGRESS TRACKING FOR PHASE 4 (50% to 90%) ---
            if (listener != null) {
                int progress = 60 + (int) (((double) f1 / loopMax) * 30.0);
                listener.onProgressUpdate(progress, "Analyzing kinematics: Frame " + (f1 + 1) + " of " + loopMax);
            }

            for (DetectedObject p1 : transients.get(f1)) {
                CancellationScope.check();
                if (assignments.isClaimed(p1)) continue;

                for (int f2 = f1 + 1; f2 < numFrames - 1; f2++) {

                    if (assignments.isClaimed(p1)) break;

                    for (DetectedObject p2 : transients.get(f2)) {
                        CancellationScope.check();

                        if (assignments.isClaimed(p1)) break;
                        if (assignments.isClaimed(p2)) continue;

                        double dist12 = TrackGeometry.distance(p1.x, p1.y, p2.x, p2.y);

                        // Baseline Filter Gates
                        if (dist12 < config.maxStarJitter) { telemetry.countBaselineJitter++; continue; }
                        if (dist12 > config.maxJumpPixels) { telemetry.countBaselineJump++; continue; }
                        if (!TrackGeometry.isProfileConsistent(p1, p2, config)) { telemetry.countBaselineSize++; continue; }

                        Track currentTrack = new Track();
                        currentTrack.addPoint(p1);
                        currentTrack.addPoint(p2);

                        DetectedObject lastPoint = p2;
                        double expectedAngle = Math.atan2(p2.y - p1.y, p2.x - p1.x);

                        // PROGRESSIVE VECTOR VARIABLES
                        DetectedObject currentLineAnchor = p2;
                        double currentBaselineDist = dist12;

                        // Find subsequent points (p3, p4, etc.)
                        for (int f3 = f2 + 1; f3 < numFrames; f3++) {
                            DetectedObject bestMatch = null;
                            double bestError = Double.MAX_VALUE;

                            for (DetectedObject p3 : transients.get(f3)) {
                                if (assignments.isClaimed(p3)) continue;

                                double jumpDist = TrackGeometry.distance(lastPoint.x, lastPoint.y, p3.x, p3.y);

                                if (jumpDist > config.maxJumpPixels) {
                                    telemetry.countP3Jump++;
                                    continue;
                                }

                                double lineError = TrackGeometry.distanceToLineOptimized(p1, currentLineAnchor, p3, currentBaselineDist);

                                if (lineError <= config.predictionTolerance) {
                                    double actualAngle = Math.atan2(p3.y - lastPoint.y, p3.x - lastPoint.x);
                                    if (TrackGeometry.isDirectionConsistent(expectedAngle, actualAngle, angleToleranceRad)) {
                                        if (!TrackGeometry.isProfileConsistent(lastPoint, p3, config)) {
                                            telemetry.countP3Size++;
                                        } else {
                                            if (lineError < bestError) {
                                                bestError = lineError;
                                                bestMatch = p3;
                                            }
                                        }
                                    } else {
                                        telemetry.countP3WrongDirection++;
                                    }
                                } else {
                                    telemetry.countP3NotLine++;
                                }
                            }

                            if (bestMatch != null) {
                                currentTrack.addPoint(bestMatch);
                                lastPoint = bestMatch;

                                // UPDATE THE PROGRESSIVE VECTOR
                                currentLineAnchor = bestMatch;
                                currentBaselineDist = TrackGeometry.distance(p1.x, p1.y, currentLineAnchor.x, currentLineAnchor.y);
                                expectedAngle = Math.atan2(currentLineAnchor.y - p1.y, currentLineAnchor.x - p1.x);
                            }
                        }

                        // Final Track Verification
                        if (currentTrack.points.size() >= minPointsRequired) {

                            // --- SMART POINT PRUNING (Anti-Hijack Filter) ---
                            List<DetectedObject> prunedPoints = new ArrayList<>();
                            prunedPoints.add(currentTrack.points.get(0));

                            for (int i = 1; i < currentTrack.points.size(); i++) {
                                DetectedObject pA = prunedPoints.get(prunedPoints.size() - 1);
                                DetectedObject pB = currentTrack.points.get(i);

                                double stepDist = TrackGeometry.distance(pA.x, pA.y, pB.x, pB.y);

                                if (stepDist > config.maxStarJitter) {
                                    prunedPoints.add(pB);
                                }
                            }

                            currentTrack.points = prunedPoints;

                            // --- RE-EVALUATE AFTER PRUNING ---
                            if (currentTrack.points.size() >= minPointsRequired) {

                                if (TrackGeometry.hasSteadyRhythm(currentTrack, config.rhythmAllowedVariance, config.rhythmStationaryThreshold, config.rhythmMinConsistencyRatio)) {

                                    if (!isTrackAlreadyFound(pointTracks, currentTrack)) {
                                        pointTracks.add(currentTrack);
                                        assignments.claimAll(currentTrack.points);
                                        if (DiagnosticContext.enabled()) {
                                            DiagnosticContext.printf(
                                                    "DEBUG: [PHASE 4] Accepted geometric point track -> points=%d startFrame=%d endFrame=%d enableGeometricTrackLinking=%s hasTimestamps=%s%n",
                                                    currentTrack.points.size(),
                                                    currentTrack.points.get(0).sourceFrameIndex,
                                                    currentTrack.points.get(currentTrack.points.size() - 1).sourceFrameIndex,
                                                    config.enableGeometricTrackLinking,
                                                    hasTimestamps
                                            );
                                            if (hasTimestamps && !config.enableGeometricTrackLinking) {
                                                DiagnosticContext.println("DEBUG: [PHASE 4] INVARIANT WARNING: geometric point track accepted even though timestamps are available and geometric linking is disabled.");
                                            }
                                        }

                                    } else {
                                        telemetry.countTrackDuplicate++;
                                    }
                                } else {
                                    telemetry.countTrackErraticRhythm++;
                                }
                            } else {
                                telemetry.countTrackTooShort++;
                            }
                        } else {
                            telemetry.countTrackTooShort++;
                        }
                    }
                }
            }
        }
    }

    /**
     * Treats tracks that share two or more detections as duplicates.
     */
    private static boolean isTrackAlreadyFound(List<Track> existingTracks, Track newTrack) {
        for (Track existing : existingTracks) {
            CancellationScope.check();
            int sharedPoints = 0;
            for (DetectedObject pt : newTrack.points) {
                CancellationScope.check();
                if (existing.points.contains(pt)) {
                    sharedPoints++;
                }
            }
            if (sharedPoints >= 2) {
                return true;
            }
        }
        return false;
    }
}
