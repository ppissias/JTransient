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
import io.github.ppissias.jtransient.telemetry.TrackerTelemetry;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.HashSet;

public class TrackLinker {

    // =================================================================
    // DATA MODELS
    // =================================================================

    // --- The Data Model for a Confirmed Target ---
    public static class Track {
        public List<SourceExtractor.DetectedObject> points = new ArrayList<>();
        public boolean isStreakTrack = false; // Tells the UI how to draw it

        public void addPoint(SourceExtractor.DetectedObject obj) {
            points.add(obj);
        }
    }

    public static class TrackingResult {
        public List<Track> tracks;
        public TrackerTelemetry telemetry;

        public TrackingResult(List<Track> tracks, TrackerTelemetry telemetry) {
            this.tracks = tracks;
            this.telemetry = telemetry;
        }
    }

    // =================================================================
    // CORE TRACKING ENGINE
    // =================================================================

    /**
     * Master method to find all moving objects (both fast streaks and slow dots).
     */
    public static TrackingResult findMovingObjects(
            List<List<SourceExtractor.DetectedObject>> allFrames,
            DetectionConfig config) { // Pass the config object instead of individual args

        int numFrames = allFrames.size();
        System.out.println("\nDEBUG: [START] findMovingObjects initialized with " + numFrames + " frames.");

        if (numFrames < 3) {
            System.out.println("DEBUG: [ABORT] Less than 3 frames provided. Cannot form point tracks.");
            return new TrackingResult(new ArrayList<>(), new TrackerTelemetry());
        }

        List<Track> confirmedTracks = new ArrayList<>();

        // Convert UI degrees back to radians for internal math
        double angleToleranceRad = Math.toRadians(config.angleToleranceDegrees);

        // =================================================================
        // PHASE 1: Separate Streaks and Purge Stationary Defects
        // =================================================================
        List<SourceExtractor.DetectedObject> rawStreaks = new ArrayList<>();
        List<List<SourceExtractor.DetectedObject>> pointSourcesOnly = new ArrayList<>();

        for (int i = 0; i < allFrames.size(); i++) {
            pointSourcesOnly.add(new ArrayList<>());
            for (SourceExtractor.DetectedObject obj : allFrames.get(i)) {
                if (obj.isStreak) {
                    rawStreaks.add(obj);
                } else {
                    pointSourcesOnly.get(i).add(obj);
                }
            }
        }

        // The Hot Column Killer
        List<SourceExtractor.DetectedObject> validMovingStreaks = new ArrayList<>();
        System.out.println("DEBUG: Evaluating " + rawStreaks.size() + " total streaks for sensor defects...");

        for (SourceExtractor.DetectedObject candidate : rawStreaks) {
            boolean isStationaryDefect = false;

            for (SourceExtractor.DetectedObject other : rawStreaks) {
                if (candidate == other || candidate.sourceFrameIndex == other.sourceFrameIndex) {
                    continue;
                }

                if (distance(candidate.x, candidate.y, other.x, other.y) <= config.stationaryDefectThreshold) {
                    isStationaryDefect = true;
                    break;
                }
            }

            if (!isStationaryDefect) {
                validMovingStreaks.add(candidate);
            }
        }

        System.out.println("DEBUG: Purged " + (rawStreaks.size() - validMovingStreaks.size()) + " stationary hot columns.");

        // =================================================================
        // PHASE 2: LINK FAST-MOVING STREAKS (Angle & Trajectory Matching)
        // =================================================================
        System.out.println("DEBUG: [PHASE 2] Linking fast-moving streaks... Total candidate streaks: " + validMovingStreaks.size());
        boolean[] streakMatched = new boolean[validMovingStreaks.size()];
        int streakTracksFound = 0;

        for (int i = 0; i < validMovingStreaks.size(); i++) {
            if (streakMatched[i]) continue;

            SourceExtractor.DetectedObject baseStreak = validMovingStreaks.get(i);
            Track continuousStreakTrack = new Track();
            continuousStreakTrack.isStreakTrack = true;
            continuousStreakTrack.addPoint(baseStreak);
            streakMatched[i] = true;

            for (int j = i + 1; j < validMovingStreaks.size(); j++) {
                if (streakMatched[j]) continue;

                SourceExtractor.DetectedObject candidateStreak = validMovingStreaks.get(j);

                if (anglesMatch(baseStreak.angle, candidateStreak.angle, angleToleranceRad)) {

                    double dy = candidateStreak.y - baseStreak.y;
                    double dx = candidateStreak.x - baseStreak.x;
                    double trajectoryAngle = Math.atan2(dy, dx);

                    if (anglesMatch(baseStreak.angle, trajectoryAngle, angleToleranceRad)) {
                        continuousStreakTrack.addPoint(candidateStreak);
                        streakMatched[j] = true;
                    }
                }
            }
            confirmedTracks.add(continuousStreakTrack);
            streakTracksFound++;
        }
        System.out.println("DEBUG: [PHASE 2] Completed. Found " + streakTracksFound + " streak track(s).");

        // =================================================================
        // PHASE 3: MASTER STAR MAP (Catalog Stacking)
        // =================================================================
        List<List<SourceExtractor.DetectedObject>> transients = new ArrayList<>();
        for (int i = 0; i < numFrames; i++) {
            transients.add(new ArrayList<>());
        }

        System.out.println("DEBUG: [PHASE 3] Building Master Star Map...");
        TrackerTelemetry telemetry = new TrackerTelemetry();

        double expandedStarJitter = config.maxStarJitter * config.starJitterExpansionFactor;
        int totalTransientsFound = 0;

        for (int i = 0; i < numFrames; i++) {
            List<SourceExtractor.DetectedObject> currentFrame = pointSourcesOnly.get(i);
            int purgedCount = 0;

            for (SourceExtractor.DetectedObject candidateObj : currentFrame) {
                int spatialMatchCount = 1;

                for (int j = 0; j < numFrames; j++) {
                    if (i == j) continue;

                    List<SourceExtractor.DetectedObject> otherFrame = pointSourcesOnly.get(j);
                    for (SourceExtractor.DetectedObject otherObj : otherFrame) {
                        if (distance(candidateObj.x, candidateObj.y, otherObj.x, otherObj.y) <= expandedStarJitter) {
                            spatialMatchCount++;
                            break;
                        }
                    }

                    if (spatialMatchCount >= config.requiredDetectionsToBeStar) {
                        break;
                    }
                }

                if (spatialMatchCount < config.requiredDetectionsToBeStar) {
                    transients.get(i).add(candidateObj);
                } else {
                    purgedCount++;
                }
            }

            totalTransientsFound += transients.get(i).size();
            telemetry.totalStationaryStarsPurged += purgedCount;

            TrackerTelemetry.FrameStarMapStat stat = new TrackerTelemetry.FrameStarMapStat();
            stat.frameIndex = i;
            stat.initialPointSources = currentFrame.size();
            stat.survivingTransients = transients.get(i).size();
            stat.purgedStars = purgedCount;
            telemetry.frameStarMapStats.add(stat);
        }
        System.out.println("DEBUG: [PHASE 3] Completed. Total pure transients across sequence: " + totalTransientsFound);

        // =================================================================
        // PHASE 4: GEOMETRIC COLLINEAR LINKING (Time-Agnostic)
        // =================================================================
        int minPointsRequired = Math.max(3, (int) Math.ceil(numFrames / config.trackMinFrameRatio));
        if (minPointsRequired > config.absoluteMaxPointsRequired) {
            minPointsRequired = config.absoluteMaxPointsRequired;
        }

        System.out.println("DEBUG: [PHASE 4] Applying time-agnostic geometric filter...");
        System.out.println("  -> Track confirmation threshold: " + minPointsRequired + " points.");

        List<Track> pointTracks = new ArrayList<>();
        Set<SourceExtractor.DetectedObject> usedPoints = new HashSet<>();

        for (int f1 = 0; f1 < numFrames - 2; f1++) {
            for (SourceExtractor.DetectedObject p1 : transients.get(f1)) {

                if (usedPoints.contains(p1)) continue;

                for (int f2 = f1 + 1; f2 < numFrames - 1; f2++) {
                    for (SourceExtractor.DetectedObject p2 : transients.get(f2)) {

                        if (usedPoints.contains(p2)) continue;

                        double dist12 = distance(p1.x, p1.y, p2.x, p2.y);

                        if (dist12 < config.maxStarJitter) {
                            telemetry.countBaselineJitter++;
                            continue;
                        }

                        if (dist12 > config.maxJumpPixels) {
                            telemetry.countBaselineJump++;
                            continue;
                        }

                        if (!isSizeConsistent(p1, p2, config.maxSizeRatio)) {
                            telemetry.countBaselineSize++;
                            continue;
                        }

                        if (!isBrightnessConsistent(p1, p2, config.maxFluxRatio)) {
                            telemetry.countBaselineFlux++;
                            continue;
                        }

                        Track currentTrack = new Track();
                        currentTrack.addPoint(p1);
                        currentTrack.addPoint(p2);

                        SourceExtractor.DetectedObject lastPoint = p2;
                        double expectedAngle = Math.atan2(p2.y - p1.y, p2.x - p1.x);

                        for (int f3 = f2 + 1; f3 < numFrames; f3++) {
                            SourceExtractor.DetectedObject bestMatch = null;
                            double bestError = Double.MAX_VALUE;

                            for (SourceExtractor.DetectedObject p3 : transients.get(f3)) {

                                if (usedPoints.contains(p3)) continue;

                                double jumpDist = distance(lastPoint.x, lastPoint.y, p3.x, p3.y);
                                if (jumpDist > config.maxJumpPixels) {
                                    telemetry.countP3Jump++;
                                    continue;
                                }

                                double lineError = distanceToLine(p1, p2, p3);

                                if (lineError <= config.predictionTolerance) {
                                    double actualAngle = Math.atan2(p3.y - lastPoint.y, p3.x - lastPoint.x);

                                    double angleDiff = Math.abs(expectedAngle - actualAngle);
                                    if (angleDiff > Math.PI) {
                                        angleDiff = (2.0 * Math.PI) - angleDiff;
                                    }

                                    if (angleDiff <= angleToleranceRad) {
                                        if (isSizeConsistent(lastPoint, p3, config.maxSizeRatio)) {

                                            if (isBrightnessConsistent(lastPoint, p3, config.maxFluxRatio)) {
                                                if (lineError < bestError) {
                                                    bestError = lineError;
                                                    bestMatch = p3;
                                                }
                                            } else {
                                                telemetry.countP3Flux++;
                                            }

                                        } else {
                                            telemetry.countP3Size++;
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
                            }
                        }

                        if (currentTrack.points.size() >= minPointsRequired) {
                            if (hasSteadyRhythm(currentTrack, config.rhythmAllowedVariance, config.rhythmStationaryThreshold, config.rhythmMinConsistencyRatio)) {
                                if (!isTrackAlreadyFound(pointTracks, currentTrack)) {
                                    pointTracks.add(currentTrack);
                                    usedPoints.addAll(currentTrack.points);
                                } else {
                                    telemetry.countTrackDuplicate++;
                                }
                            } else {
                                telemetry.countTrackErraticRhythm++;
                            }
                        } else {
                            telemetry.countTrackTooShort++;
                        }
                    }
                }
            }
        }

        // --- FINALIZE METRICS ---
        telemetry.streakTracksFound = streakTracksFound;
        telemetry.pointTracksFound = pointTracks.size();

        // --- PRINT TELEMETRY REPORT ---
        System.out.println("\n--------------------------------------------------");
        System.out.println(" PHASE 4 TELEMETRY: FILTER REJECTION STATISTICS   ");
        System.out.println("--------------------------------------------------");
        System.out.println("1. Baseline Generation (p1 -> p2) Rejections:");
        System.out.println("   - Stationary / Jitter           : " + telemetry.countBaselineJitter);
        System.out.println("   - Exceeded Max Jump Velocity    : " + telemetry.countBaselineJump);
        System.out.println("   - Morphological Size Mismatch   : " + telemetry.countBaselineSize);
        System.out.println("   - Photometric Flux Mismatch     : " + telemetry.countBaselineFlux);

        System.out.println("\n2. Track Search (p3) Point Rejections:");
        System.out.println("   - Off predicted trajectory line : " + telemetry.countP3NotLine);
        System.out.println("   - Wrong direction / angle       : " + telemetry.countP3WrongDirection);
        System.out.println("   - Exceeded Max Jump Velocity    : " + telemetry.countP3Jump);
        System.out.println("   - Morphological Size Mismatch   : " + telemetry.countP3Size);
        System.out.println("   - Photometric Flux Mismatch     : " + telemetry.countP3Flux);

        System.out.println("\n3. Final Track Rejections:");
        System.out.println("   - Insufficient track length     : " + telemetry.countTrackTooShort);
        System.out.println("   - Erratic kinematic rhythm      : " + telemetry.countTrackErraticRhythm);
        System.out.println("   - Duplicate track (Ignored)     : " + telemetry.countTrackDuplicate);
        System.out.println("--------------------------------------------------\n");

        System.out.println("\n4. Valid Tracks Confirmed:");
        System.out.println("   - Fast Streak Tracks (Phase 2)  : " + telemetry.streakTracksFound);
        System.out.println("   - Slow Point Tracks (Phase 4)   : " + telemetry.pointTracksFound);
        System.out.println("   - TOTAL MOVING TARGETS FOUND    : " + (telemetry.streakTracksFound + telemetry.pointTracksFound));
        System.out.println("--------------------------------------------------\n");

        confirmedTracks.addAll(pointTracks);
        return new TrackingResult(confirmedTracks, telemetry);
    }

    // =================================================================
    // HELPER METHODS
    // =================================================================

    private static boolean isSizeConsistent(SourceExtractor.DetectedObject obj1, SourceExtractor.DetectedObject obj2, double maxRatio) {
        double size1 = Math.max(obj1.pixelArea, 1.0);
        double size2 = Math.max(obj2.pixelArea, 1.0);
        double ratio = Math.max(size1, size2) / Math.min(size1, size2);
        return ratio <= maxRatio;
    }

    private static double distance(double x1, double y1, double x2, double y2) {
        double dx = x2 - x1;
        double dy = y2 - y1;
        return Math.sqrt(dx * dx + dy * dy);
    }

    private static boolean anglesMatch(double a1, double a2, double tolerance) {
        double diff = Math.abs(a1 - a2) % Math.PI;
        return diff <= tolerance || Math.PI - diff <= tolerance;
    }

    private static double distanceToLine(SourceExtractor.DetectedObject p1,
                                         SourceExtractor.DetectedObject p2,
                                         SourceExtractor.DetectedObject p3) {

        double numerator = Math.abs((p2.x - p1.x) * (p1.y - p3.y) - (p1.x - p3.x) * (p2.y - p1.y));
        double denominator = distance(p1.x, p1.y, p2.x, p2.y);

        if (denominator == 0) return Double.MAX_VALUE;
        return numerator / denominator;
    }

    private static boolean isTrackAlreadyFound(List<Track> existingTracks, Track newTrack) {
        for (Track existing : existingTracks) {
            if (existing.points.containsAll(newTrack.points)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Evaluates if a track maintains a consistent speed, ignoring missing frames.
     */
    public static boolean hasSteadyRhythm(Track track, double rhythmAllowedVariance, double rhythmStationaryThreshold, double rhythmMinConsistencyRatio) {
        if (track.points.size() < 3) return true;

        List<Double> jumps = new ArrayList<>();
        for (int i = 0; i < track.points.size() - 1; i++) {
            SourceExtractor.DetectedObject p1 = track.points.get(i);
            SourceExtractor.DetectedObject p2 = track.points.get(i + 1);
            jumps.add(distance(p1.x, p1.y, p2.x, p2.y));
        }

        List<Double> sortedJumps = new ArrayList<>(jumps);
        sortedJumps.sort(Double::compareTo);
        double medianJump = sortedJumps.get(sortedJumps.size() / 2);

        if (medianJump < rhythmStationaryThreshold) return false;

        int consistentJumps = 0;

        for (double jump : jumps) {
            long multiplier = Math.round(jump / medianJump);
            if (multiplier == 0) continue;

            double expectedJump = multiplier * medianJump;

            if (Math.abs(jump - expectedJump) <= (rhythmAllowedVariance * multiplier)) {
                consistentJumps++;
            }
        }

        double consistencyRatio = (double) consistentJumps / jumps.size();
        return consistencyRatio >= rhythmMinConsistencyRatio;
    }

    private static boolean isBrightnessConsistent(SourceExtractor.DetectedObject obj1, SourceExtractor.DetectedObject obj2, double maxRatio) {
        double flux1 = Math.max(Math.abs(obj1.totalFlux), 1.0);
        double flux2 = Math.max(Math.abs(obj2.totalFlux), 1.0);
        double ratio = Math.max(flux1, flux2) / Math.min(flux1, flux2);
        return ratio <= maxRatio;
    }
}