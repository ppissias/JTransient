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
import io.github.ppissias.jtransient.model.DetectedObject;
import io.github.ppissias.jtransient.model.Track;
import io.github.ppissias.jtransient.telemetry.TrackerTelemetry;

import java.util.ArrayList;
import java.util.List;

/**
 * Links fast-moving streak detections into streak tracks, then keeps unlinked single streaks that are significant
 * enough and not shaped like a binary star.
 */
final class StreakLinker {

    private StreakLinker() {
    }

    /** Returns the streak tracks; each one-streak track counts too. */
    static List<Track> link(List<DetectedObject> validMovingStreaks, DetectionConfig config, TrackerTelemetry telemetry) {
        List<Track> confirmedStreakTracks = new ArrayList<>();
        double angleToleranceRad = Math.toRadians(config.angleToleranceDegrees);

        // =================================================================
        // PHASE 3: LINK FAST-MOVING STREAKS
        // =================================================================
        if (DiagnosticContext.enabled()) DiagnosticContext.println("DEBUG: [PHASE 3] Linking fast-moving streaks... Candidates: " + validMovingStreaks.size());
        boolean[] streakMatched = new boolean[validMovingStreaks.size()];
        List<DetectedObject> unmatchedSingleStreaks = new ArrayList<>();
        int streakTracksFound = 0;

        for (int i = 0; i < validMovingStreaks.size(); i++) {
            CancellationScope.check();
            if (streakMatched[i]) continue;

            DetectedObject baseStreak = validMovingStreaks.get(i);
            Track continuousStreakTrack = new Track();
            continuousStreakTrack.isStreakTrack = true;
            continuousStreakTrack.addPoint(baseStreak);
            streakMatched[i] = true;

            DetectedObject currentAnchor = baseStreak;
            boolean directionEstablished = false;
            double forwardAngle = 0;

            while (true) {
                CancellationScope.check();
                DetectedObject bestMatch = null;
                int bestMatchIndex = -1;
                int bestFrameGap = Integer.MAX_VALUE;
                double shortestDistance = Double.MAX_VALUE;
                double bestTrajectoryAngle = 0;

                // Scan all available streaks and prefer nearby frames before larger chronological jumps.
                for (int j = 0; j < validMovingStreaks.size(); j++) {
                    if (streakMatched[j]) continue;

                    DetectedObject candidateStreak = validMovingStreaks.get(j);

                    // Ensure we only link chronologically forward (allowing same-frame fragmented streaks to merge)
                    if (candidateStreak.sourceFrameIndex < currentAnchor.sourceFrameIndex) continue;

                    if (TrackGeometry.anglesMatch(baseStreak.angle, candidateStreak.angle, angleToleranceRad)) {
                        double dy = candidateStreak.y - currentAnchor.y;
                        double dx = candidateStreak.x - currentAnchor.x;
                        double trajectoryAngle = Math.atan2(dy, dx);

                        boolean isDirectionValid = false;
                        if (!directionEstablished) {
                            // Establish forward trajectory from the first valid jump
                            if (TrackGeometry.anglesMatch(baseStreak.angle, trajectoryAngle, angleToleranceRad)) {
                                isDirectionValid = true;
                            }
                        } else {
                            // Subsequent jumps must maintain the strictly established forward polarity
                            if (TrackGeometry.isDirectionConsistent(forwardAngle, trajectoryAngle, angleToleranceRad)) {
                                isDirectionValid = true;
                            }
                        }

                        if (isDirectionValid) {
                            List<DetectedObject> previewPoints = new ArrayList<>(continuousStreakTrack.points);
                            previewPoints.add(candidateStreak);
                            if (!StreakConsolidator.passesStreakTimeConsistency(previewPoints, config)) {
                                continue;
                            }

                            int frameGap = Math.max(0, candidateStreak.sourceFrameIndex - currentAnchor.sourceFrameIndex);
                            double dist = TrackGeometry.distance(currentAnchor.x, currentAnchor.y, candidateStreak.x, candidateStreak.y);
                            if (frameGap < bestFrameGap || (frameGap == bestFrameGap && dist < shortestDistance)) {
                                bestFrameGap = frameGap;
                                shortestDistance = dist;
                                bestMatch = candidateStreak;
                                bestMatchIndex = j;
                                bestTrajectoryAngle = trajectoryAngle;
                            }
                        }
                    }
                }

                if (bestMatch != null) {
                    continuousStreakTrack.addPoint(bestMatch);
                    streakMatched[bestMatchIndex] = true;
                    currentAnchor = bestMatch;

                    if (!directionEstablished) {
                        forwardAngle = bestTrajectoryAngle;
                        directionEstablished = true;
                    }
                } else {
                    // No more valid streaks can be linked to this track
                    break;
                }
            }

            if (continuousStreakTrack.points.size() > 1 && StreakConsolidator.passesStreakTimeConsistency(continuousStreakTrack.points, config)) {
                confirmedStreakTracks.add(continuousStreakTrack);
                streakTracksFound++;
            } else {
                unmatchedSingleStreaks.add(baseStreak);
            }
        }

        for (DetectedObject singleStreak : unmatchedSingleStreaks) {
            CancellationScope.check();
            boolean rejectedByBinaryStarVeto = config.enableBinaryStarLikeStreakShapeVeto
                    && SourceExtractor.isBinaryStarLikeStreakShape(singleStreak);

            if (rejectedByBinaryStarVeto) {
                telemetry.rejectedBinaryStarStreakShape++;
            }

            if (!rejectedByBinaryStarVeto && singleStreak.peakSigma >= config.singleStreakMinPeakSigma) {
                Track singleStreakTrack = new Track();
                singleStreakTrack.isStreakTrack = true;
                singleStreakTrack.addPoint(singleStreak);
                confirmedStreakTracks.add(singleStreakTrack);
                streakTracksFound++;
            }
        }
        if (DiagnosticContext.enabled()) DiagnosticContext.println("DEBUG: [PHASE 3] Completed. Found " + streakTracksFound + " streak track(s).");

        return confirmedStreakTracks;
    }
}
