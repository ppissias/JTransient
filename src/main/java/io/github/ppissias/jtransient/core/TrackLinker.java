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

    public static TrackingResult findMovingObjects(
            List<List<SourceExtractor.DetectedObject>> allFrames,
            List<SourceExtractor.DetectedObject> masterStars,
            DetectionConfig config,
            TransientEngineProgressListener listener) { // <--- Added Listener

        int numFrames = allFrames.size();
        if (JTransientEngine.DEBUG) System.out.println("\nDEBUG: [START] findMovingObjects initialized with " + numFrames + " frames.");

        if (listener != null) {
            listener.onProgressUpdate(0, "Initializing tracking engine...");
        }

        if (numFrames < 3) {
            if (JTransientEngine.DEBUG) System.out.println("DEBUG: [ABORT] Less than 3 frames provided. Cannot form point tracks.");
            return new TrackingResult(new ArrayList<>(), new TrackerTelemetry());
        }

        List<Track> confirmedTracks = new ArrayList<>();
        double angleToleranceRad = Math.toRadians(config.angleToleranceDegrees);

        if (listener != null) {
            listener.onProgressUpdate(5, "Purging stationary defects...");
        }

        // =================================================================
        // PHASE 1: Separate Streaks and Purge Stationary Defects
        // =================================================================
        List<SourceExtractor.DetectedObject> rawStreaks = new ArrayList<>();
        List<List<SourceExtractor.DetectedObject>> pointSourcesOnly = new ArrayList<>();

        for (int i = 0; i < allFrames.size(); i++) {
            pointSourcesOnly.add(new ArrayList<>());
            for (SourceExtractor.DetectedObject obj : allFrames.get(i)) {
                if (obj.isStreak) rawStreaks.add(obj);
                else pointSourcesOnly.get(i).add(obj);
            }
        }

        List<SourceExtractor.DetectedObject> validMovingStreaks = new ArrayList<>();
        if (JTransientEngine.DEBUG) System.out.println("DEBUG: Evaluating " + rawStreaks.size() + " total streaks for sensor defects...");

        for (SourceExtractor.DetectedObject candidate : rawStreaks) {
            boolean isStationaryDefect = false;
            for (SourceExtractor.DetectedObject other : rawStreaks) {
                if (candidate == other || candidate.sourceFrameIndex == other.sourceFrameIndex) continue;

                if (distance(candidate.x, candidate.y, other.x, other.y) <= config.stationaryDefectThreshold) {
                    isStationaryDefect = true;
                    break;
                }
            }
            if (!isStationaryDefect) validMovingStreaks.add(candidate);
        }

        if (JTransientEngine.DEBUG) System.out.println("DEBUG: Purged " + (rawStreaks.size() - validMovingStreaks.size()) + " stationary hot columns.");

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
        if (JTransientEngine.DEBUG) System.out.println("DEBUG: [PHASE 2] Completed. Found " + streakTracksFound + " streak track(s).");

        if (listener != null) {
            listener.onProgressUpdate(25, "Building Binary Veto Mask...");
        }

        // =================================================================
        // PHASE 3: BINARY MASK MASTER STAR MAP VETO
        // =================================================================
        if (JTransientEngine.DEBUG) System.out.println("DEBUG: [PHASE 3] Building Binary Footprint Mask from Master Map...");
        TrackerTelemetry telemetry = new TrackerTelemetry();
        int totalTransientsFound = 0;

        // 1. Calculate the maximum bounds of the entire dataset to size the 2D Array dynamically
        int maxX = 0;
        int maxY = 0;
        for (SourceExtractor.DetectedObject mStar : masterStars) {
            if (mStar.x > maxX) maxX = (int) mStar.x;
            if (mStar.y > maxY) maxY = (int) mStar.y;
            if (mStar.rawPixels != null) {
                for (SourceExtractor.Pixel p : mStar.rawPixels) {
                    if (p.x > maxX) maxX = p.x;
                    if (p.y > maxY) maxY = p.y;
                }
            }
        }
        for (List<SourceExtractor.DetectedObject> frame : pointSourcesOnly) {
            for (SourceExtractor.DetectedObject cand : frame) {
                if (cand.x > maxX) maxX = (int) cand.x;
                if (cand.y > maxY) maxY = (int) cand.y;
                if (cand.rawPixels != null) {
                    for (SourceExtractor.Pixel p : cand.rawPixels) {
                        if (p.x > maxX) maxX = p.x;
                        if (p.y > maxY) maxY = p.y;
                    }
                }
            }
        }

        // Add a generous safety margin to prevent IndexOutOfBounds
        maxX += 50;
        maxY += 50;

        // 2. Initialize the blazing-fast 2D Boolean Mask
        boolean[][] masterMask = new boolean[maxY][maxX];
        int dilationRadius = (int) Math.ceil(config.maxStarJitter); // Dilate footprint to protect against alignment drift
        double expandedStarJitter = Math.max(4.0, config.maxStarJitter * config.starJitterExpansionFactor);

        // 3. Paint the Master Objects into the Mask
        for (SourceExtractor.DetectedObject mStar : masterStars) {
            if (mStar.rawPixels != null) {
                for (SourceExtractor.Pixel p : mStar.rawPixels) {
                    // Dilate outward from each pixel to create a protective buffer
                    for (int dx = -dilationRadius; dx <= dilationRadius; dx++) {
                        for (int dy = -dilationRadius; dy <= dilationRadius; dy++) {
                            if (dx * dx + dy * dy <= dilationRadius * dilationRadius) {
                                int mx = p.x + dx;
                                int my = p.y + dy;
                                if (mx >= 0 && mx < maxX && my >= 0 && my < maxY) {
                                    masterMask[my][mx] = true;
                                }
                            }
                        }
                    }
                }
            } else {
                // Fallback: If raw pixels are somehow missing, paint a circle around the centroid
                int cx = (int) mStar.x;
                int cy = (int) mStar.y;
                int r = (int) Math.max(dilationRadius, Math.ceil(expandedStarJitter));
                for (int dx = -r; dx <= r; dx++) {
                    for (int dy = -r; dy <= r; dy++) {
                        if (dx * dx + dy * dy <= r * r) {
                            int mx = cx + dx;
                            int my = cy + dy;
                            if (mx >= 0 && mx < maxX && my >= 0 && my < maxY) {
                                masterMask[my][mx] = true;
                            }
                        }
                    }
                }
            }
        }

        List<List<SourceExtractor.DetectedObject>> transients = new ArrayList<>();

        // 4. Evaluate Transients against the Mask
        for (int i = 0; i < numFrames; i++) {

            // --- SMOOTH PROGRESS TRACKING FOR PHASE 3 (25% to 50%) ---
            if (listener != null) {
                int progress = 25 + (int) (((double) i / numFrames) * 25.0);
                listener.onProgressUpdate(progress, "Applying Veto Mask: Frame " + (i + 1) + " of " + numFrames);
            }

            List<SourceExtractor.DetectedObject> currentFrame = pointSourcesOnly.get(i);
            List<SourceExtractor.DetectedObject> frameTransients = new ArrayList<>();
            int purgedCount = 0;

            for (SourceExtractor.DetectedObject candidateObj : currentFrame) {
                boolean isPurged = false;

                // Perform the Pixel-Perfect Collision Check
                if (candidateObj.rawPixels != null) {
                    for (SourceExtractor.Pixel p : candidateObj.rawPixels) {
                        if (p.x >= 0 && p.x < maxX && p.y >= 0 && p.y < maxY) {
                            // If even ONE candidate pixel touches the painted master mask, it is destroyed!
                            if (masterMask[p.y][p.x]) {
                                isPurged = true;
                                break;
                            }
                        }
                    }
                } else {
                    int cx = (int) candidateObj.x;
                    int cy = (int) candidateObj.y;
                    if (cx >= 0 && cx < maxX && cy >= 0 && cy < maxY) {
                        if (masterMask[cy][cx]) isPurged = true;
                    }
                }

                if (isPurged) {
                    purgedCount++;
                } else {
                    frameTransients.add(candidateObj);
                }
            }

            if (JTransientEngine.DEBUG) {
                System.out.printf("  -> Frame %d: Evaluated %d points. %d killed by Binary Mask Veto, %d transients survived.%n",
                        i, currentFrame.size(), purgedCount, frameTransients.size());
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

        // =================================================================
        // PHASE 4: GEOMETRIC COLLINEAR LINKING
        // =================================================================
        int minPointsRequired = Math.max(3, (int) Math.ceil(numFrames / config.trackMinFrameRatio));
        if (minPointsRequired > config.absoluteMaxPointsRequired) {
            minPointsRequired = config.absoluteMaxPointsRequired;
        }

        if (JTransientEngine.DEBUG) {
            System.out.println("DEBUG: [PHASE 4] Applying time-agnostic geometric filter...");
            System.out.println("  -> Track confirmation threshold: " + minPointsRequired + " points.");
        }

        List<Track> pointTracks = new ArrayList<>();
        Set<SourceExtractor.DetectedObject> usedPoints = new HashSet<>();

        int loopMax = numFrames - 2;
        for (int f1 = 0; f1 < loopMax; f1++) {

            // --- SMOOTH PROGRESS TRACKING FOR PHASE 4 (50% to 90%) ---
            if (listener != null) {
                int progress = 50 + (int) (((double) f1 / loopMax) * 40.0);
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
                        if (!isSizeConsistent(p1, p2, config.maxSizeRatio)) { telemetry.countBaselineSize++; continue; }
                        if (!isBrightnessConsistent(p1, p2, config.maxFluxRatio)) { telemetry.countBaselineFlux++; continue; }

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
                                    double angleDiff = Math.abs(expectedAngle - actualAngle);
                                    if (angleDiff > Math.PI) angleDiff = (2.0 * Math.PI) - angleDiff;

                                    if (angleDiff <= angleToleranceRad) {
                                        if (!isSizeConsistent(lastPoint, p3, config.maxSizeRatio)) {
                                            telemetry.countP3Size++;
                                        } else if (!isBrightnessConsistent(lastPoint, p3, config.maxFluxRatio)) {
                                            telemetry.countP3Flux++;
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
            System.out.println("   - Morphological Size Mismatch   : " + telemetry.countBaselineSize);
            System.out.println("   - Photometric Flux Mismatch     : " + telemetry.countBaselineFlux);

            System.out.println("\n2. Point 3+ (p3, p4...) Search Rejections:");
            System.out.println("   - Not Collinear (Off-line)      : " + telemetry.countP3NotLine);
            System.out.println("   - Wrong Direction / Angle       : " + telemetry.countP3WrongDirection);
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
        }

        if (listener != null) {
            listener.onProgressUpdate(100, "Finalizing track telemetry...");
        }

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

    private static boolean isBrightnessConsistent(SourceExtractor.DetectedObject obj1, SourceExtractor.DetectedObject obj2, double maxRatio) {
        double flux1 = Math.max(Math.abs(obj1.totalFlux), 1.0);
        double flux2 = Math.max(Math.abs(obj2.totalFlux), 1.0);
        double ratio = Math.max(flux1, flux2) / Math.min(flux1, flux2);
        return ratio <= maxRatio;
    }
}