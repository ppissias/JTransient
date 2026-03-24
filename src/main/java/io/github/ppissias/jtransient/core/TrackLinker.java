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
import io.github.ppissias.jtransient.engine.JTransientEngine;
import io.github.ppissias.jtransient.engine.TransientEngineProgressListener;
import io.github.ppissias.jtransient.telemetry.TrackerTelemetry;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.HashSet;

public class TrackLinker {

    // =================================================================
    // DATA MODELS
    // =================================================================

    public static class Track {
        public List<SourceExtractor.DetectedObject> points = new ArrayList<>();
        public boolean isStreakTrack = false;
        public boolean isAnomaly = false; // <--- NEW
        public boolean isTimeBasedTrack = false; 

        public void addPoint(SourceExtractor.DetectedObject obj) {
            points.add(obj);
        }
    }

    public static class TransientsFilterResult {
        public List<List<SourceExtractor.DetectedObject>> pointTransients;
        public List<SourceExtractor.DetectedObject> validMovingStreaks;
        public List<Track> streakTracks;
        public TrackerTelemetry telemetry;
        public int streakTracksFound;
        public List<List<SourceExtractor.DetectedObject>> mergedTransients;
        public boolean[][] masterMask;
    }

    public static class TrackingResult {
        public List<Track> tracks;
        public TrackerTelemetry telemetry;
        public List<List<SourceExtractor.DetectedObject>> allTransients;
        public boolean[][] masterMask;

        public TrackingResult(List<Track> tracks, TrackerTelemetry telemetry, List<List<SourceExtractor.DetectedObject>> allTransients, boolean[][] masterMask) {
            this.tracks = tracks;
            this.telemetry = telemetry;
            this.allTransients = allTransients;
            this.masterMask = masterMask;
        }
    }

    // =================================================================
    // CORE TRACKING ENGINE
    // =================================================================

    /**
     * Executes the pipeline up to Phase 3. 
     * Separates streaks, purges stationary defects, links fast streaks, and applies the Binary Veto Mask.
     */
    public static TransientsFilterResult filterTransients(
            List<List<SourceExtractor.DetectedObject>> allFrames,
            List<SourceExtractor.DetectedObject> masterStars,
            DetectionConfig config,
            TransientEngineProgressListener listener,
            int sensorWidth,
            int sensorHeight) {

        int numFrames = allFrames.size();
        List<Track> confirmedStreakTracks = new ArrayList<>();
        double angleToleranceRad = Math.toRadians(config.angleToleranceDegrees);
        TrackerTelemetry telemetry = new TrackerTelemetry();

        // =================================================================
        // PHASE 1: Separate Streaks
        // =================================================================
        List<SourceExtractor.DetectedObject> validMovingStreaks = new ArrayList<>();
        List<List<SourceExtractor.DetectedObject>> pointSourcesOnly = new ArrayList<>();

        for (int i = 0; i < allFrames.size(); i++) {
            pointSourcesOnly.add(new ArrayList<>());
            for (SourceExtractor.DetectedObject obj : allFrames.get(i)) {
                if (obj.isStreak) validMovingStreaks.add(obj);
                else pointSourcesOnly.get(i).add(obj);
            }
        }

        if (listener != null) {
            listener.onProgressUpdate(15, "Linking fast-moving streaks...");
        }

        // =================================================================
        // PHASE 2: LINK FAST-MOVING STREAKS
        // =================================================================
        if (JTransientEngine.DEBUG) System.out.println("DEBUG: [PHASE 2] Linking fast-moving streaks... Candidates: " + validMovingStreaks.size());
        boolean[] streakMatched = new boolean[validMovingStreaks.size()];
        int streakTracksFound = 0;

        for (int i = 0; i < validMovingStreaks.size(); i++) {
            if (streakMatched[i]) continue;

            SourceExtractor.DetectedObject baseStreak = validMovingStreaks.get(i);
            Track continuousStreakTrack = new Track();
            continuousStreakTrack.isStreakTrack = true;
            continuousStreakTrack.addPoint(baseStreak);
            streakMatched[i] = true;

            SourceExtractor.DetectedObject currentAnchor = baseStreak;
            boolean directionEstablished = false;
            double forwardAngle = 0;

            while (true) {
                SourceExtractor.DetectedObject bestMatch = null;
                int bestMatchIndex = -1;
                double shortestDistance = Double.MAX_VALUE;
                double bestTrajectoryAngle = 0;

                // Scan all available streaks to find the absolute closest valid link
                for (int j = 0; j < validMovingStreaks.size(); j++) {
                    if (streakMatched[j]) continue;

                    SourceExtractor.DetectedObject candidateStreak = validMovingStreaks.get(j);
                    
                    // Ensure we only link chronologically forward (allowing same-frame fragmented streaks to merge)
                    if (candidateStreak.sourceFrameIndex < currentAnchor.sourceFrameIndex) continue;

                    if (anglesMatch(baseStreak.angle, candidateStreak.angle, angleToleranceRad)) {
                        double dy = candidateStreak.y - currentAnchor.y;
                        double dx = candidateStreak.x - currentAnchor.x;
                        double trajectoryAngle = Math.atan2(dy, dx);

                        boolean isDirectionValid = false;
                        if (!directionEstablished) {
                            // Establish forward trajectory from the first valid jump
                            if (anglesMatch(baseStreak.angle, trajectoryAngle, angleToleranceRad)) {
                                isDirectionValid = true;
                            }
                        } else {
                            // Subsequent jumps must maintain the strictly established forward polarity
                            if (isDirectionConsistent(forwardAngle, trajectoryAngle, angleToleranceRad)) {
                                isDirectionValid = true;
                            }
                        }
                        
                        if (isDirectionValid) {
                            double dist = distance(currentAnchor.x, currentAnchor.y, candidateStreak.x, candidateStreak.y);
                            if (dist < shortestDistance) {
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
            
            if (continuousStreakTrack.points.size() == 1) {
                if (baseStreak.peakSigma >= config.singleStreakMinPeakSigma) {
                    confirmedStreakTracks.add(continuousStreakTrack);
                    streakTracksFound++;
                }
            } else {
                confirmedStreakTracks.add(continuousStreakTrack);
                streakTracksFound++;
            }
        }
        if (JTransientEngine.DEBUG) System.out.println("DEBUG: [PHASE 2] Completed. Found " + streakTracksFound + " streak track(s).");

        if (listener != null) {
            listener.onProgressUpdate(25, "Building Binary Veto Mask...");
        }

        // =================================================================
        // PHASE 3: BINARY MASK MASTER STAR MAP VETO
        // =================================================================
        if (JTransientEngine.DEBUG) System.out.println("DEBUG: [PHASE 3] Building Binary Footprint Mask from Master Map...");
        int totalTransientsFound = 0;

        boolean[][] masterMask = new boolean[sensorHeight][sensorWidth];
        // The Master Map represents the median (center) of the atmospheric wobble cloud.
        // Therefore, we only dilate by half the frame-to-frame maximum jitter to safely cover the seeing disk!
        int dilationRadius = (int) Math.max(1, Math.round(config.maxStarJitter / 2.0));

        for (SourceExtractor.DetectedObject mStar : masterStars) {
            for (SourceExtractor.Pixel p : mStar.rawPixels) {
                for (int dx = -dilationRadius; dx <= dilationRadius; dx++) {
                    for (int dy = -dilationRadius; dy <= dilationRadius; dy++) {
                        if (dx * dx + dy * dy <= dilationRadius * dilationRadius) {
                            int mx = p.x + dx;
                            int my = p.y + dy;
                            if (mx >= 0 && mx < sensorWidth && my >= 0 && my < sensorHeight) {
                                masterMask[my][mx] = true;
                            }
                        }
                    }
                }
            }
        }

        List<List<SourceExtractor.DetectedObject>> transients = new ArrayList<>();

        for (int i = 0; i < numFrames; i++) {
            if (listener != null) {
                int progress = 25 + (int) (((double) i / numFrames) * 25.0);
                listener.onProgressUpdate(progress, "Applying Veto Mask: Frame " + (i + 1) + " of " + numFrames);
            }

            List<SourceExtractor.DetectedObject> currentFrame = pointSourcesOnly.get(i);
            List<SourceExtractor.DetectedObject> frameTransients = new ArrayList<>();
            int purgedCount = 0;

            for (SourceExtractor.DetectedObject candidateObj : currentFrame) {
                boolean isPurged = false;
                int overlapCount = 0;
                for (SourceExtractor.Pixel p : candidateObj.rawPixels) {
                    if (p.x >= 0 && p.x < sensorWidth && p.y >= 0 && p.y < sensorHeight && masterMask[p.y][p.x]) {
                        overlapCount++;
                    }
                }
                double overlapFraction = (double) overlapCount / candidateObj.rawPixels.size();
                if (overlapFraction > config.maxMaskOverlapFraction) isPurged = true;

                if (isPurged) {
                    purgedCount++;
                } else {
                    frameTransients.add(candidateObj);
                }
            }

            transients.add(frameTransients);
            totalTransientsFound += frameTransients.size();
            telemetry.totalStationaryStarsPurged += purgedCount;

            TrackerTelemetry.FrameStarMapStat stat = new TrackerTelemetry.FrameStarMapStat();
            stat.frameIndex = i;
            stat.initialPointSources = currentFrame.size();
            stat.survivingTransients = frameTransients.size();
            stat.purgedStars = purgedCount;
            telemetry.frameStarMapStats.add(stat);
        }

        if (JTransientEngine.DEBUG) System.out.println("DEBUG: [PHASE 3] Completed. Total pure transients across sequence: " + totalTransientsFound);

        // Merge point transients and valid streaks together for final export
        List<List<SourceExtractor.DetectedObject>> mergedTransients = new ArrayList<>();
        for (int i = 0; i < numFrames; i++) {
            List<SourceExtractor.DetectedObject> mergedFrame = new ArrayList<>(transients.get(i));
            for (SourceExtractor.DetectedObject obj : allFrames.get(i)) {
                if (obj.isStreak && validMovingStreaks.contains(obj)) {
                    mergedFrame.add(obj);
                }
            }
            mergedTransients.add(mergedFrame);
        }

        TransientsFilterResult result = new TransientsFilterResult();
        result.pointTransients = transients;
        result.validMovingStreaks = validMovingStreaks;
        result.streakTracks = confirmedStreakTracks;
        result.telemetry = telemetry;
        result.streakTracksFound = streakTracksFound;
        result.mergedTransients = mergedTransients;
        result.masterMask = masterMask;

        return result;
    }

    public static TrackingResult findMovingObjects(
            List<List<SourceExtractor.DetectedObject>> allFrames,
            List<SourceExtractor.DetectedObject> masterStars,
            DetectionConfig config,
            TransientEngineProgressListener listener,
            int sensorWidth,
            int sensorHeight) { 

        int numFrames = allFrames.size();
        if (JTransientEngine.DEBUG) System.out.println("\nDEBUG: [START] findMovingObjects initialized with " + numFrames + " frames.");

        if (listener != null) {
            listener.onProgressUpdate(0, "Initializing tracking engine...");
        }

        if (numFrames < 3) {
            if (JTransientEngine.DEBUG) System.out.println("DEBUG: [ABORT] Less than 3 frames provided. Cannot form point tracks.");
            return new TrackingResult(new ArrayList<>(), new TrackerTelemetry(), new ArrayList<>(), null);
        }

        TransientsFilterResult filterResult = filterTransients(allFrames, masterStars, config, listener, sensorWidth, sensorHeight);
        
        List<Track> confirmedTracks = filterResult.streakTracks;
        TrackerTelemetry telemetry = filterResult.telemetry;
        List<List<SourceExtractor.DetectedObject>> transients = filterResult.pointTransients;
        int streakTracksFound = filterResult.streakTracksFound;
        
        double angleToleranceRad = Math.toRadians(config.angleToleranceDegrees);

        // =================================================================
        // PHASE 4: GEOMETRIC COLLINEAR LINKING
        // =================================================================
        int minPointsRequired = Math.max(3, (int) Math.ceil(numFrames / config.trackMinFrameRatio));
        if (minPointsRequired > config.absoluteMaxPointsRequired) {
            minPointsRequired = config.absoluteMaxPointsRequired;
        }

        List<Track> pointTracks = new ArrayList<>();
        Set<SourceExtractor.DetectedObject> usedPoints = new HashSet<>();
        int loopMax = numFrames - 2;

        // =================================================================
        // PHASE 3.5: TIME-BASED KINEMATIC LINKING (Velocity Vector Matching)
        // =================================================================
        boolean hasTimestamps = false;
        for (List<SourceExtractor.DetectedObject> frame : allFrames) {
            if (!frame.isEmpty() && frame.get(0).timestamp != -1) {
                hasTimestamps = true;
                break;
            }
        }

        if (hasTimestamps) {
            if (JTransientEngine.DEBUG) System.out.println("DEBUG: [PHASE 3.5] Timestamps detected. Running Time-Based Velocity Linking...");
            if (listener != null) listener.onProgressUpdate(50, "Analyzing precise velocity kinematics...");

            int timeTracksFound = 0;

            for (int f1 = 0; f1 < loopMax; f1++) {
                for (SourceExtractor.DetectedObject p1 : transients.get(f1)) {
                    if (usedPoints.contains(p1)) continue;

                    for (int f2 = f1 + 1; f2 < numFrames - 1; f2++) {
                        if (usedPoints.contains(p1)) break;
                        for (SourceExtractor.DetectedObject p2 : transients.get(f2)) {
                            if (usedPoints.contains(p1)) break;
                            if (usedPoints.contains(p2)) continue;

                            double dt12 = p2.timestamp - p1.timestamp;
                            if (dt12 <= 0) continue; // Prevent div by zero

                            double dist12 = distance(p1.x, p1.y, p2.x, p2.y);
                            if (dist12 < config.maxStarJitter) continue;

                            // Morphological profile check (maxJumpPixels explicitly bypassed for extreme speeds)
                            if (!isProfileConsistent(p1, p2, config)) continue;

                            double v12 = dist12 / dt12; // Velocity in pixels per ms
                            double expectedAngle = Math.atan2(p2.y - p1.y, p2.x - p1.x);

                            Track currentTrack = new Track();
                            currentTrack.addPoint(p1);
                            currentTrack.addPoint(p2);

                            SourceExtractor.DetectedObject currentLineAnchor = p2;
                            double currentVelocity = v12;

                            for (int f3 = f2 + 1; f3 < numFrames; f3++) {
                                SourceExtractor.DetectedObject bestMatch = null;
                                double bestVError = Double.MAX_VALUE;

                                for (SourceExtractor.DetectedObject p3 : transients.get(f3)) {
                                    if (usedPoints.contains(p3)) continue;

                                    double dt23 = p3.timestamp - currentLineAnchor.timestamp;
                                    if (dt23 <= 0) continue;

                                    double dist23 = distance(currentLineAnchor.x, currentLineAnchor.y, p3.x, p3.y);
                                    double v23 = dist23 / dt23;

                                    double vDiffRatio = Math.abs(v23 - currentVelocity) / currentVelocity;

                                    // If speed matches within tolerance (e.g. 10%)
                                    if (vDiffRatio <= config.timeBasedVelocityTolerance) {
                                        double actualAngle = Math.atan2(p3.y - currentLineAnchor.y, p3.x - currentLineAnchor.x);
                                        
                                        // If strict forward direction matches
                                        if (isDirectionConsistent(expectedAngle, actualAngle, angleToleranceRad)) {
                                            if (isProfileConsistent(currentLineAnchor, p3, config)) {
                                                
                                                if (vDiffRatio < bestVError) {
                                                    bestVError = vDiffRatio;
                                                    bestMatch = p3;
                                                }
                                            }
                                        }
                                    }
                                }

                                if (bestMatch != null) {
                                    currentTrack.addPoint(bestMatch);
                                    double stepDt = bestMatch.timestamp - currentLineAnchor.timestamp;
                                    double stepDist = distance(currentLineAnchor.x, currentLineAnchor.y, bestMatch.x, bestMatch.y);
                                    currentVelocity = stepDist / stepDt; // Update progressive velocity
                                    expectedAngle = Math.atan2(bestMatch.y - currentLineAnchor.y, bestMatch.x - currentLineAnchor.x);
                                    currentLineAnchor = bestMatch;
                                }
                            }

                            if (currentTrack.points.size() >= minPointsRequired) {
                                if (!isTrackAlreadyFound(pointTracks, currentTrack)) {
                                    currentTrack.isTimeBasedTrack = true;
                                    pointTracks.add(currentTrack);
                                    usedPoints.addAll(currentTrack.points);
                                    timeTracksFound++;
                                }
                            }
                        }
                    }
                }
            }
            if (JTransientEngine.DEBUG) System.out.println("DEBUG: [PHASE 3.5] Completed. Found " + timeTracksFound + " precise time-based track(s).");
        }

        // =================================================================
        // PHASE 4: GEOMETRIC COLLINEAR LINKING (Frame-Agnostic Fallback)
        // =================================================================
        if (JTransientEngine.DEBUG) {
            System.out.println("DEBUG: [PHASE 4] Applying time-agnostic geometric fallback filter...");
            System.out.println("  -> Track confirmation threshold: " + minPointsRequired + " points.");
        }

        for (int f1 = 0; f1 < loopMax; f1++) {

            // --- SMOOTH PROGRESS TRACKING FOR PHASE 4 (50% to 90%) ---
            if (listener != null) {
                int progress = 60 + (int) (((double) f1 / loopMax) * 30.0);
                listener.onProgressUpdate(progress, "Analyzing kinematics: Frame " + (f1 + 1) + " of " + loopMax);
            }

            for (SourceExtractor.DetectedObject p1 : transients.get(f1)) {
                if (usedPoints.contains(p1)) continue;

                for (int f2 = f1 + 1; f2 < numFrames - 1; f2++) {

                    if (usedPoints.contains(p1)) break;

                    for (SourceExtractor.DetectedObject p2 : transients.get(f2)) {

                        if (usedPoints.contains(p1)) break;
                        if (usedPoints.contains(p2)) continue;

                        double dist12 = distance(p1.x, p1.y, p2.x, p2.y);

                        // Baseline Filter Gates
                        if (dist12 < config.maxStarJitter) { telemetry.countBaselineJitter++; continue; }
                        if (dist12 > config.maxJumpPixels) { telemetry.countBaselineJump++; continue; }
                        if (!isProfileConsistent(p1, p2, config)) { telemetry.countBaselineSize++; continue; }

                        Track currentTrack = new Track();
                        currentTrack.addPoint(p1);
                        currentTrack.addPoint(p2);

                        SourceExtractor.DetectedObject lastPoint = p2;
                        double expectedAngle = Math.atan2(p2.y - p1.y, p2.x - p1.x);

                        // PROGRESSIVE VECTOR VARIABLES
                        SourceExtractor.DetectedObject currentLineAnchor = p2;
                        double currentBaselineDist = dist12;

                        // Find subsequent points (p3, p4, etc.)
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

                                double lineError = distanceToLineOptimized(p1, currentLineAnchor, p3, currentBaselineDist);

                                if (lineError <= config.predictionTolerance) {
                                    double actualAngle = Math.atan2(p3.y - lastPoint.y, p3.x - lastPoint.x);
                                    if (isDirectionConsistent(expectedAngle, actualAngle, angleToleranceRad)) {
                                        if (!isProfileConsistent(lastPoint, p3, config)) {
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
                                currentBaselineDist = distance(p1.x, p1.y, currentLineAnchor.x, currentLineAnchor.y);
                                expectedAngle = Math.atan2(currentLineAnchor.y - p1.y, currentLineAnchor.x - p1.x);
                            }
                        }

                        // Final Track Verification
                        if (currentTrack.points.size() >= minPointsRequired) {

                            // --- SMART POINT PRUNING (Anti-Hijack Filter) ---
                            List<SourceExtractor.DetectedObject> prunedPoints = new ArrayList<>();
                            prunedPoints.add(currentTrack.points.get(0));

                            for (int i = 1; i < currentTrack.points.size(); i++) {
                                SourceExtractor.DetectedObject pA = prunedPoints.get(prunedPoints.size() - 1);
                                SourceExtractor.DetectedObject pB = currentTrack.points.get(i);

                                double stepDist = distance(pA.x, pA.y, pB.x, pB.y);

                                if (stepDist > config.maxStarJitter) {
                                    prunedPoints.add(pB);
                                } else {
                                    //if (JTransientEngine.DEBUG) {
                                    //System.out.printf("         *** PRUNING: Dropped hijacked point at frame %d. (Stalled segment: %.2f px) ***%n",
                                    //        pB.sourceFrameIndex, stepDist);
                                    //}
                                }
                            }

                            currentTrack.points = prunedPoints;

                            // --- RE-EVALUATE AFTER PRUNING ---
                            if (currentTrack.points.size() >= minPointsRequired) {

                                if (JTransientEngine.DEBUG) {
                                    SourceExtractor.DetectedObject trackStart = currentTrack.points.get(0);
                                    SourceExtractor.DetectedObject trackEnd = currentTrack.points.get(currentTrack.points.size() - 1);
                                    double totalDistanceMoved = distance(trackStart.x, trackStart.y, trackEnd.x, trackEnd.y);

                                    //System.out.printf("      -> [EVALUATING TRACK] %d clean points formed a line. Total distance moved: %.2f pixels. (Start: %.1f,%.1f)%n",
                                    //        currentTrack.points.size(), totalDistanceMoved, trackStart.x, trackStart.y);
                                }

                                if (hasSteadyRhythm(currentTrack, config.rhythmAllowedVariance, config.rhythmStationaryThreshold, config.rhythmMinConsistencyRatio)) {

                                    if (!isTrackAlreadyFound(pointTracks, currentTrack)) {
                                        pointTracks.add(currentTrack);
                                        usedPoints.addAll(currentTrack.points);

                                        if (JTransientEngine.DEBUG) {
                                            SourceExtractor.DetectedObject trackStart = currentTrack.points.get(0);
                                            SpatialGrid masterGridForDebug = new SpatialGrid(masterStars, 30.0);
                                            double distToMaster = masterGridForDebug.getNearestDistance(trackStart.x, trackStart.y, 30.0);

                                            //System.out.printf("         [SUCCESS] Track formed at X:%.1f, Y:%.1f (Length: %d points)%n",
                                            //        trackStart.x, trackStart.y, currentTrack.points.size());

                                            if (distToMaster > 0) {
                                                //    System.out.printf("            -> DIAGNOSTIC: Nearest Master Centroid is %.2f pixels away. (Veto radius was %.2f)%n",
                                                //            distToMaster, expandedStarJitter);
                                            } else {
                                                //   System.out.println("            -> DIAGNOSTIC: NO Master Centroid found within 30 pixels!");
                                            }
                                        }
                                    } else {
                                        telemetry.countTrackDuplicate++;
                                    }
                                } else {
                                    telemetry.countTrackErraticRhythm++;
                                    //if (JTransientEngine.DEBUG) System.out.println("         [REJECTED] Failed steady rhythm check.");
                                }
                            } else {
                                telemetry.countTrackTooShort++;
                                //if (JTransientEngine.DEBUG) System.out.println("         [REJECTED] Track became too short after pruning hijacked points.");
                            }
                        } else {
                            telemetry.countTrackTooShort++;
                        }
                    }
                }
            }
        }

        if (listener != null) {
            listener.onProgressUpdate(90, "Scanning for high-energy anomalies...");
        }

        // =================================================================
        // PHASE 5: HIGH-ENERGY ANOMALY RESCUE (GLINTS & FLASHES)
        // =================================================================
        int anomaliesRescued = 0;
        if (config.enableAnomalyRescue) {
            if (JTransientEngine.DEBUG) System.out.println("DEBUG: [PHASE 5] Scanning discarded transients for High-Energy Anomalies...");

            for (int i = 0; i < numFrames; i++) {
                for (SourceExtractor.DetectedObject orphan : transients.get(i)) {
                    if (!usedPoints.contains(orphan)) {

                        // Check if it meets the extreme thresholds AND is safely away from the edges!
                        if (orphan.pixelArea >= config.anomalyMinPixels && orphan.peakSigma >= config.anomalyMinPeakSigma) {

                            Track anomalyTrack = new Track();
                            anomalyTrack.addPoint(orphan);
                            anomalyTrack.isAnomaly = true;

                            pointTracks.add(anomalyTrack);
                            usedPoints.add(orphan);
                            anomaliesRescued++;

                            if (JTransientEngine.DEBUG) {
                                //System.out.printf("         [ANOMALY RESCUED] Huge transient found at Frame %d (X:%.1f, Y:%.1f). Area: %.1f px, Peak Sigma: %.1f%n",
                                //       orphan.sourceFrameIndex, orphan.x, orphan.y, orphan.pixelArea, orphan.peakSigma);
                            }
                        }
                    }
                }
            }
        }

        telemetry.streakTracksFound = streakTracksFound;
        telemetry.pointTracksFound = pointTracks.size();

        if (JTransientEngine.DEBUG) {
            System.out.println("\n--------------------------------------------------");
            System.out.println(" PHASE 4 TELEMETRY: FILTER REJECTION STATISTICS   ");
            System.out.println("--------------------------------------------------");
            System.out.println("1. Baseline Generation (p1 -> p2) Rejections:");
            System.out.println("   - Stationary / Jitter           : " + telemetry.countBaselineJitter);
            System.out.println("   - Exceeded Max Jump Velocity    : " + telemetry.countBaselineJump);
            System.out.println("   - Morphological Profile Mismatch: " + telemetry.countBaselineSize);

            System.out.println("\n2. Point 3+ (p3, p4...) Search Rejections:");
            System.out.println("   - Not Collinear (Off-line)      : " + telemetry.countP3NotLine);
            System.out.println("   - Wrong Direction / Angle       : " + telemetry.countP3WrongDirection);
            System.out.println("   - Exceeded Max Jump Velocity    : " + telemetry.countP3Jump);
            System.out.println("   - Morphological Profile Mismatch: " + telemetry.countP3Size);

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
        }

        if (listener != null) {
            listener.onProgressUpdate(100, "Finalizing track telemetry...");
        }

        confirmedTracks.addAll(pointTracks);
        return new TrackingResult(confirmedTracks, telemetry, filterResult.mergedTransients, filterResult.masterMask);
    }

    // =================================================================
    // HELPER METHODS
    // =================================================================


    private static boolean isProfileConsistent(SourceExtractor.DetectedObject obj1, SourceExtractor.DetectedObject obj2, DetectionConfig config) {
        // 1. FWHM Consistency (Optics fingerprint)
        if (config.maxFwhmRatio > 0.0) {
            double fwhm1 = Math.max(obj1.fwhm, 0.1);
            double fwhm2 = Math.max(obj2.fwhm, 0.1);
            double fwhmRatio = Math.max(fwhm1, fwhm2) / Math.min(fwhm1, fwhm2);
            if (fwhmRatio > config.maxFwhmRatio) return false;
        }

        // 2. Surface Brightness Consistency (Density of the light)
        if (config.maxSurfaceBrightnessRatio > 0.0) {
            double sb1 = obj1.totalFlux / Math.max(obj1.pixelArea, 1.0);
            double sb2 = obj2.totalFlux / Math.max(obj2.pixelArea, 1.0);
            double sbRatio = Math.max(sb1, sb2) / Math.min(sb1, sb2);
            if (sbRatio > config.maxSurfaceBrightnessRatio) return false;
        }
        return true;
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

    private static boolean isDirectionConsistent(double expectedAngle, double actualAngle, double tolerance) {
        double diff = Math.abs(expectedAngle - actualAngle);
        if (diff > Math.PI) diff = (2.0 * Math.PI) - diff;
        return diff <= tolerance;
    }

    private static double distanceToLineOptimized(SourceExtractor.DetectedObject p1,
                                                  SourceExtractor.DetectedObject p2,
                                                  SourceExtractor.DetectedObject p3,
                                                  double precalculatedDenominator) {

        if (precalculatedDenominator == 0) return Double.MAX_VALUE;
        double numerator = Math.abs((p2.x - p1.x) * (p1.y - p3.y) - (p1.x - p3.x) * (p2.y - p1.y));
        return numerator / precalculatedDenominator;
    }

    private static boolean isTrackAlreadyFound(List<Track> existingTracks, Track newTrack) {
        for (Track existing : existingTracks) {
            int sharedPoints = 0;
            for (SourceExtractor.DetectedObject pt : newTrack.points) {
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

}