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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Links per-frame transient detections into moving-object tracks.
 *
 * <p>This is the primary tracker implementation used by the engine. It combines
 * streak chaining, stationary-star vetoing, time-aware candidate ranking, and an
 * optional geometric linker that remains the fallback for sequences without usable timestamps.</p>
 */
public class TrackLinker {

    // =================================================================
    // DATA MODELS
    // =================================================================

    /**
     * One confirmed moving-object track.
     */
    public static class Track {
        /** Chronological points that make up the track. */
        public List<SourceExtractor.DetectedObject> points = new ArrayList<>();
        /** Whether the track is composed of streak detections. */
        public boolean isStreakTrack = false;
        /** Whether the track groups same-frame rescued anomalies into a suspected faint streak. */
        public boolean isSuspectedStreakTrack = false;
        /** Whether the track was accepted by the time-aware linker. */
        public boolean isTimeBasedTrack = false;

        /**
         * Appends one detection to the track.
         */
        public void addPoint(SourceExtractor.DetectedObject obj) {
            points.add(obj);
        }
    }

    /**
     * Category assigned to rescued single-frame anomalies.
     */
    public enum AnomalyType {
        PEAK_SIGMA,
        INTEGRATED_SIGMA
    }

    /**
     * One rescued single-frame anomaly that did not become a multi-frame track.
     */
    public static class AnomalyDetection {
        /** The underlying extracted object. */
        public final SourceExtractor.DetectedObject object;
        /** The rescue path that accepted this anomaly. */
        public final AnomalyType type;

        private AnomalyDetection(SourceExtractor.DetectedObject object, AnomalyType type) {
            this.object = object;
            this.type = type;
        }
    }

    /**
     * Output of the streak-separation and stationary-star veto stages.
     */
    public static class TransientsFilterResult {
        /** Point-like transients that survived vetoing, grouped by frame. */
        public List<List<SourceExtractor.DetectedObject>> pointTransients;
        /** All streak detections considered mobile enough to keep. */
        public List<SourceExtractor.DetectedObject> validMovingStreaks;
        /** Confirmed multi-frame or single-frame high-significance streak tracks. */
        public List<Track> streakTracks;
        /** Tracker diagnostics collected so far. */
        public TrackerTelemetry telemetry;
        /** Number of streak tracks accepted during the fast-streak phase. */
        public int streakTracksFound;
        /** Full post-veto transient population, merging point transients with all mobile streak detections per frame. */
        public List<List<SourceExtractor.DetectedObject>> allTransients;
        /** Pixel-accurate stationary-star veto mask built from the master stack. */
        public boolean[][] masterVetoMask;
    }

    /**
     * Output of the complete tracking stage.
     */
    public static class TrackingResult {
        /** All returned track-like detections, including suspected same-frame streak groupings. */
        public List<Track> tracks;
        /** Rescued single-frame anomalies that did not form tracks. */
        public List<AnomalyDetection> anomalies;
        /** Tracker diagnostics covering all stages. */
        public TrackerTelemetry telemetry;
        /** All non-stationary transient detections carried through tracking, grouped by frame. */
        public List<List<SourceExtractor.DetectedObject>> allTransients;
        /** Per-frame transient detections that remain uncategorized after tracks and anomalies are exported. */
        public List<List<SourceExtractor.DetectedObject>> unclassifiedTransients;
        /** Pixel veto mask generated from the master star map. */
        public boolean[][] masterVetoMask;

        /**
         * Creates a tracking result bundle.
         */
        public TrackingResult(List<Track> tracks,
                              List<AnomalyDetection> anomalies,
                              TrackerTelemetry telemetry,
                              List<List<SourceExtractor.DetectedObject>> allTransients,
                              List<List<SourceExtractor.DetectedObject>> unclassifiedTransients,
                              boolean[][] masterVetoMask) {
            this.tracks = tracks;
            this.anomalies = anomalies;
            this.telemetry = telemetry;
            this.allTransients = allTransients;
            this.unclassifiedTransients = unclassifiedTransients;
            this.masterVetoMask = masterVetoMask;
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
     * One same-frame suspected streak proposal before it is materialized as a returned track.
     */
    private static class SuspectedStreakLineCandidate {
        private final List<SourceExtractor.DetectedObject> objects;
        private final double span;

        private SuspectedStreakLineCandidate(List<SourceExtractor.DetectedObject> objects,
                                             double span) {
            this.objects = objects;
            this.span = span;
        }
    }

    // =================================================================
    // CORE TRACKING ENGINE
    // =================================================================

    /**
     * Executes the pipeline up to Phase 3.
     * Separates streaks, purges stationary defects, links fast streaks, and applies the Binary Veto Mask.
     *
     * @param allFrames extracted objects grouped by frame
     * @param masterStars stationary objects extracted from the master stack
     * @param config pipeline configuration
     * @param listener optional progress listener
     * @param sensorWidth frame width
     * @param sensorHeight frame height
     * @return filtered transients, streak tracks, telemetry, and the veto mask
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
        List<SourceExtractor.DetectedObject> allStreaks = new ArrayList<>();
        List<List<SourceExtractor.DetectedObject>> pointSourcesOnly = new ArrayList<>();

        for (int i = 0; i < allFrames.size(); i++) {
            pointSourcesOnly.add(new ArrayList<>());
            for (SourceExtractor.DetectedObject obj : allFrames.get(i)) {
                if (obj.isStreak) allStreaks.add(obj);
                else pointSourcesOnly.get(i).add(obj);
            }
        }

        if (listener != null) {
            listener.onProgressUpdate(10, "Building Veto Mask...");
        }

        // =================================================================
        // PHASE 2: BINARY MASK MASTER STAR MAP VETO
        // =================================================================
        if (JTransientEngine.DEBUG) System.out.println("DEBUG: [PHASE 2] Building Binary Footprint Mask from Master Map...");

        boolean[][] masterVetoMask = new boolean[sensorHeight][sensorWidth];
        int dilationRadius = (int) Math.max(1, Math.round(config.maxStarJitter / 2.0));

        for (SourceExtractor.DetectedObject mStar : masterStars) {
            for (SourceExtractor.Pixel p : mStar.rawPixels) {
                for (int dx = -dilationRadius; dx <= dilationRadius; dx++) {
                    for (int dy = -dilationRadius; dy <= dilationRadius; dy++) {
                        if (dx * dx + dy * dy <= dilationRadius * dilationRadius) {
                            int mx = p.x + dx;
                            int my = p.y + dy;
                            if (mx >= 0 && mx < sensorWidth && my >= 0 && my < sensorHeight) {
                                masterVetoMask[my][mx] = true;
                            }
                        }
                    }
                }
            }
        }

        // --- PHASE 2A: Veto Point Sources ---
        List<List<SourceExtractor.DetectedObject>> transients = new ArrayList<>();
        int totalTransientsFound = 0;

        for (int i = 0; i < numFrames; i++) {
            if (listener != null) {
                int progress = 15 + (int) (((double) i / numFrames) * 10.0);
                listener.onProgressUpdate(progress, "Vetoing stationary sources: Frame " + (i + 1) + " of " + numFrames);
            }

            List<SourceExtractor.DetectedObject> currentFrame = pointSourcesOnly.get(i);
            List<SourceExtractor.DetectedObject> frameTransients = new ArrayList<>();
            int purgedCount = 0;

            for (SourceExtractor.DetectedObject candidateObj : currentFrame) {
                double overlapFraction = computeMaskOverlapFraction(candidateObj, masterVetoMask);
                if (overlapFraction > config.maxMaskOverlapFraction) {
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

        // --- PHASE 2B: Veto Streaks ---
        List<SourceExtractor.DetectedObject> validMovingStreaks = new ArrayList<>();
        int purgedStreakCount = 0;
        for (SourceExtractor.DetectedObject streak : allStreaks) {
            double overlapFraction = computeMaskOverlapFraction(streak, masterVetoMask);
            if (overlapFraction > config.maxMaskOverlapFraction) {
                purgedStreakCount++;
            } else {
                validMovingStreaks.add(streak);
            }
        }
        telemetry.totalStationaryStreaksPurged = purgedStreakCount;

        if (JTransientEngine.DEBUG) {
            System.out.println("DEBUG: [PHASE 2] Completed. Total pure point transients: " + totalTransientsFound);
            System.out.println("DEBUG: [PHASE 2] Purged " + purgedStreakCount + " stationary streaks. " + validMovingStreaks.size() + " remain.");
        }

        if (listener != null) {
            listener.onProgressUpdate(25, "Linking fast-moving streaks...");
        }

        // =================================================================
        // PHASE 3: LINK FAST-MOVING STREAKS
        // =================================================================
        if (JTransientEngine.DEBUG) System.out.println("DEBUG: [PHASE 3] Linking fast-moving streaks... Candidates: " + validMovingStreaks.size());
        boolean[] streakMatched = new boolean[validMovingStreaks.size()];
        List<SourceExtractor.DetectedObject> unmatchedSingleStreaks = new ArrayList<>();
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

            if (continuousStreakTrack.points.size() > 1) {
                confirmedStreakTracks.add(continuousStreakTrack);
                streakTracksFound++;
            } else {
                unmatchedSingleStreaks.add(baseStreak);
            }
        }

        for (SourceExtractor.DetectedObject singleStreak : unmatchedSingleStreaks) {
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
        if (JTransientEngine.DEBUG) System.out.println("DEBUG: [PHASE 3] Completed. Found " + streakTracksFound + " streak track(s).");

        List<List<SourceExtractor.DetectedObject>> allTransients = buildAllTransients(
                transients,
                validMovingStreaks,
                numFrames
        );

        TransientsFilterResult result = new TransientsFilterResult();
        result.pointTransients = transients;
        result.validMovingStreaks = validMovingStreaks;
        result.streakTracks = confirmedStreakTracks;
        result.telemetry = telemetry;
        result.streakTracksFound = streakTracksFound;
        result.allTransients = allTransients;
        result.masterVetoMask = masterVetoMask;

        return result;
    }

    /**
     * Runs the full tracking pipeline, starting from extracted objects and master-stack stars.
     *
     * @param allFrames extracted objects grouped by frame
     * @param masterStars stationary objects extracted from the master stack
     * @param config pipeline configuration
     * @param listener optional progress listener
     * @param sensorWidth frame width
     * @param sensorHeight frame height
     * @return final confirmed tracks together with telemetry and exported transients
     */
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
            return new TrackingResult(
                    new ArrayList<>(),
                    new ArrayList<>(),
                    new TrackerTelemetry(),
                    new ArrayList<>(),
                    new ArrayList<>(),
                    null
            );
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
        List<RankedTrackCandidate> timeTrackCandidates = new ArrayList<>();

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

        if (JTransientEngine.DEBUG) {
            System.out.println("DEBUG: [PHASE 3.5] Timestamp probe per frame:");
            for (int i = 0; i < allFrames.size(); i++) {
                List<SourceExtractor.DetectedObject> frame = allFrames.get(i);
                if (frame.isEmpty()) {
                    System.out.printf("   Frame %d -> empty%n", i);
                } else {
                    SourceExtractor.DetectedObject sample = frame.get(0);
                    System.out.printf(
                            "   Frame %d -> objects=%d sampleTimestamp=%d sampleExposure=%d%n",
                            i,
                            frame.size(),
                            sample.timestamp,
                            sample.exposureDuration
                    );
                }
            }
            System.out.printf(
                    "DEBUG: [PHASE 3.5] hasTimestamps=%s enableGeometricTrackLinking=%s%n",
                    hasTimestamps,
                    config.enableGeometricTrackLinking
            );
        }

        if (hasTimestamps) {
            if (JTransientEngine.DEBUG) System.out.println("DEBUG: [PHASE 3.5] Timestamps detected. Running Time-Based Velocity Linking...");
            if (listener != null) listener.onProgressUpdate(50, "Analyzing precise velocity kinematics...");

            int generatedTimeTracks = 0;

            // Calculate total points to process for accurate progress reporting
            int totalP1Candidates = 0;
            for (int i = 0; i < loopMax; i++) {
                totalP1Candidates += transients.get(i).size();
            }
            int p1Processed = 0;

            for (int f1 = 0; f1 < loopMax; f1++) {
                for (SourceExtractor.DetectedObject p1 : transients.get(f1)) {
                    
                    p1Processed++;
                    if (listener != null && (p1Processed % 50 == 0 || p1Processed == totalP1Candidates)) {
                        int progress = 50 + (int) (((double) p1Processed / Math.max(1, totalP1Candidates)) * 10.0);
                        listener.onProgressUpdate(progress, "Analyzing time-based kinematics: point " + p1Processed + " of " + totalP1Candidates);
                    }

                    if (usedPoints.contains(p1)) continue;

                    for (int f2 = f1 + 1; f2 < numFrames - 1; f2++) {
                        if (usedPoints.contains(p1)) break;
                        for (SourceExtractor.DetectedObject p2 : transients.get(f2)) {
                            if (usedPoints.contains(p1)) break;
                            if (usedPoints.contains(p2)) continue;

                            double dt12 = p2.timestamp - p1.timestamp;
                            if (dt12 <= 0) {
                                telemetry.countBaselineNonPositiveDelta++;
                                continue;
                            }

                            double dist12 = distance(p1.x, p1.y, p2.x, p2.y);
                            if (dist12 < config.maxStarJitter) {
                                telemetry.countBaselineJitter++;
                                continue;
                            }

                            // Morphological profile check (maxJumpPixels explicitly bypassed for extreme speeds)
                            if (!isProfileConsistent(p1, p2, config)) {
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

                            SourceExtractor.DetectedObject currentLineAnchor = p2;
                            double currentVelocity = v12;

                            for (int f3 = f2 + 1; f3 < numFrames; f3++) {
                                SourceExtractor.DetectedObject bestMatch = null;
                                double bestVError = Double.MAX_VALUE;

                                for (SourceExtractor.DetectedObject p3 : transients.get(f3)) {
                                    if (usedPoints.contains(p3)) continue;

                                    double dt23 = p3.timestamp - currentLineAnchor.timestamp;
                                    if (dt23 <= 0) {
                                        telemetry.countP3NonPositiveDelta++;
                                        continue;
                                    }

                                    double dist23 = distance(currentLineAnchor.x, currentLineAnchor.y, p3.x, p3.y);

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
                                    double currentBaselineDist = distance(p1.x, p1.y, currentLineAnchor.x, currentLineAnchor.y);
                                    double lineError = distanceToLineOptimized(p1, currentLineAnchor, p3, currentBaselineDist);

                                    if (lineError > config.predictionTolerance) {
                                        telemetry.countP3NotLine++;
                                        continue;
                                    }

                                    double actualAngle = Math.atan2(p3.y - currentLineAnchor.y, p3.x - currentLineAnchor.x);
                                    // Dynamically relax the angle tolerance for short jumps where sub-pixel jitter causes massive angular swings
                                    double dynamicAngleTolerance = Math.max(angleToleranceRad, Math.atan2(config.maxStarJitter + 1.0, dist23));

                                    if (!isDirectionConsistent(expectedAngle, actualAngle, dynamicAngleTolerance)) {
                                        telemetry.countP3WrongDirection++;
                                        continue;
                                    }

                                    if (!isProfileConsistent(currentLineAnchor, p3, config)) {
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
                                    double totalDist = distance(p1.x, p1.y, bestMatch.x, bestMatch.y);
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

            timeTrackCandidates.sort(TrackLinker::compareRankedTimeTrackCandidates);

            int timeTracksFound = 0;
            for (RankedTrackCandidate candidate : timeTrackCandidates) {
                if (conflictsWithUsedPoints(usedPoints, candidate.track)) {
                    telemetry.countTrackDuplicate++;
                    continue;
                }

                pointTracks.add(candidate.track);
                usedPoints.addAll(candidate.track.points);
                timeTracksFound++;
                if (JTransientEngine.DEBUG) {
                    System.out.printf(
                            "DEBUG: [PHASE 3.5] Accepted time-based point track -> points=%d startFrame=%d endFrame=%d%n",
                            candidate.track.points.size(),
                            candidate.track.points.get(0).sourceFrameIndex,
                            candidate.track.points.get(candidate.track.points.size() - 1).sourceFrameIndex
                    );
                }
            }

            if (JTransientEngine.DEBUG) {
                System.out.println("DEBUG: [PHASE 3.5] Completed. Generated " + generatedTimeTracks
                        + " precise time-based track candidate(s), accepted " + timeTracksFound + " after ranking.");
            }
        }

        boolean runGeometricTrackLinking = config.enableGeometricTrackLinking || !hasTimestamps;
        if (JTransientEngine.DEBUG) {
            System.out.printf(
                    "DEBUG: [PHASE 4] runGeometricTrackLinking=%s (enableGeometricTrackLinking=%s, hasTimestamps=%s)%n",
                    runGeometricTrackLinking,
                    config.enableGeometricTrackLinking,
                    hasTimestamps
            );
        }

        // =================================================================
        // PHASE 4: GEOMETRIC COLLINEAR LINKING (Optional / Timestamp Fallback)
        // =================================================================
        if (runGeometricTrackLinking) {
            if (JTransientEngine.DEBUG) {
                String mode = hasTimestamps
                        ? "optional geometric fallback enabled"
                        : "forced geometric fallback because timestamps are unavailable";
                System.out.println("DEBUG: [PHASE 4] Applying time-agnostic geometric linker (" + mode + ")...");
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
                                                System.out.printf(
                                                        "DEBUG: [PHASE 4] Accepted geometric point track -> points=%d startFrame=%d endFrame=%d enableGeometricTrackLinking=%s hasTimestamps=%s%n",
                                                        currentTrack.points.size(),
                                                        currentTrack.points.get(0).sourceFrameIndex,
                                                        currentTrack.points.get(currentTrack.points.size() - 1).sourceFrameIndex,
                                                        config.enableGeometricTrackLinking,
                                                        hasTimestamps
                                                );
                                                if (hasTimestamps && !config.enableGeometricTrackLinking) {
                                                    System.out.println("DEBUG: [PHASE 4] INVARIANT WARNING: geometric point track accepted even though timestamps are available and geometric linking is disabled.");
                                                }
                                            }

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
        } else if (JTransientEngine.DEBUG) {
            System.out.println("DEBUG: [PHASE 4] Skipping geometric linker because timestamps are available and enableGeometricTrackLinking=false.");
        }

        if (listener != null) {
            listener.onProgressUpdate(90, "Scanning for high-energy anomalies...");
        }

        // =================================================================
        // PHASE 5: HIGH-ENERGY ANOMALY RESCUE (GLINTS & FLASHES)
        // =================================================================
        List<AnomalyDetection> anomalies = new ArrayList<>();
        List<Track> suspectedStreakTracks = new ArrayList<>();
        List<List<SourceExtractor.DetectedObject>> anomalyCandidates = buildPhase5AnomalyCandidates(
                filterResult.allTransients,
                confirmedTracks,
                pointTracks
        );
        if (config.enableAnomalyRescue) {
            if (JTransientEngine.DEBUG) {
                System.out.println("DEBUG: [PHASE 5] Scanning merged transients for High-Energy Anomalies...");
            }

            for (int i = 0; i < numFrames; i++) {
                for (SourceExtractor.DetectedObject orphan : anomalyCandidates.get(i)) {
                    if (!usedPoints.contains(orphan)) {

                        // Rescue either a sharp glint or a broader high-energy flash.
                        AnomalyType anomalyType = classifyAnomalyRescue(orphan, config);
                        if (anomalyType != null) {
                            anomalies.add(new AnomalyDetection(orphan, anomalyType));
                            usedPoints.add(orphan);
                        }
                    }
                }
            }
        }

        //now search for suspected streak tracks
        if (!anomalies.isEmpty()) {
            suspectedStreakTracks = groupSuspectedStreakTracks(anomalies, config);
            if (!suspectedStreakTracks.isEmpty()) {
                Set<SourceExtractor.DetectedObject> groupedObjects = new HashSet<>();
                for (Track track : suspectedStreakTracks) {
                    groupedObjects.addAll(track.points);
                }
                anomalies.removeIf(anomaly -> groupedObjects.contains(anomaly.object));
            }
        }

        int integratedSigmaAnomaliesRescued = 0;
        for (AnomalyDetection anomaly : anomalies) {
            if (anomaly.type == AnomalyType.INTEGRATED_SIGMA) {
                integratedSigmaAnomaliesRescued++;
            }
        }

        telemetry.streakTracksFound = streakTracksFound;
        telemetry.pointTracksFound = pointTracks.size();
        telemetry.anomaliesFound = anomalies.size();
        telemetry.integratedSigmaAnomaliesFound = integratedSigmaAnomaliesRescued;
        telemetry.suspectedStreakTracksFound = suspectedStreakTracks.size();

        if (JTransientEngine.DEBUG) {
            System.out.println("\n--------------------------------------------------");
            System.out.println(" PHASE 4 TELEMETRY: FILTER REJECTION STATISTICS   ");
            System.out.println("--------------------------------------------------");
            System.out.println("1. Baseline Generation (p1 -> p2) Rejections:");
            System.out.println("   - Stationary / Jitter           : " + telemetry.countBaselineJitter);
            System.out.println("   - Non-Positive Time Delta       : " + telemetry.countBaselineNonPositiveDelta);
            System.out.println("   - Exceeded Max Jump Velocity    : " + telemetry.countBaselineJump);
            System.out.println("   - Morphological Profile Mismatch: " + telemetry.countBaselineSize);

            System.out.println("\n2. Point 3+ (p3, p4...) Search Rejections:");
            System.out.println("   - Non-Positive Time Delta       : " + telemetry.countP3NonPositiveDelta);
            System.out.println("   - Velocity Mismatch             : " + telemetry.countP3VelocityMismatch);
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
            System.out.println("   - Binary-Star Streak Rejects    : " + telemetry.rejectedBinaryStarStreakShape);
            System.out.println("   - Slow Point Tracks (Phase 4)   : " + telemetry.pointTracksFound);
            System.out.println("   - Suspected Streak Tracks       : " + telemetry.suspectedStreakTracksFound);
            System.out.println("   - TOTAL RETURNED TRACKS         : "
                    + (telemetry.streakTracksFound + telemetry.pointTracksFound + telemetry.suspectedStreakTracksFound));
            System.out.println("   - Single-Frame Anomalies        : " + telemetry.anomaliesFound);
            System.out.println("      -> Integrated-Sigma Rescue   : " + telemetry.integratedSigmaAnomaliesFound);
            System.out.println("--------------------------------------------------\n");
        }

        if (listener != null) {
            listener.onProgressUpdate(100, "Finalizing track telemetry...");
        }

        confirmedTracks.addAll(pointTracks);
        confirmedTracks.addAll(suspectedStreakTracks);
        if (JTransientEngine.DEBUG) {
            int finalStreakTracks = 0;
            int finalSuspectedTracks = 0;
            int finalTimeBasedPointTracks = 0;
            int finalGeometricPointTracks = 0;

            for (Track track : confirmedTracks) {
                if (track.isSuspectedStreakTrack) {
                    finalSuspectedTracks++;
                } else if (track.isStreakTrack) {
                    finalStreakTracks++;
                } else if (track.isTimeBasedTrack) {
                    finalTimeBasedPointTracks++;
                } else {
                    finalGeometricPointTracks++;
                }
            }

            System.out.printf(
                    "DEBUG: [FINAL TRACK SUMMARY] streak=%d suspected=%d timeBasedPoint=%d geometricPoint=%d%n",
                    finalStreakTracks,
                    finalSuspectedTracks,
                    finalTimeBasedPointTracks,
                    finalGeometricPointTracks
            );
        }
        List<List<SourceExtractor.DetectedObject>> allTransients = filterResult.allTransients;
        List<List<SourceExtractor.DetectedObject>> unclassifiedTransients = buildUnclassifiedTransients(
                allTransients,
                confirmedTracks,
                anomalies
        );
        return new TrackingResult(
                confirmedTracks,
                anomalies,
                telemetry,
                allTransients,
                unclassifiedTransients,
                filterResult.masterVetoMask
        );
    }

    /**
     * Accepts either a sharp single-frame glint or a broader anomaly with enough integrated energy.
     */
    static boolean qualifiesForAnomalyRescue(SourceExtractor.DetectedObject orphan, DetectionConfig config) {
        return classifyAnomalyRescue(orphan, config) != null;
    }

    /**
     * Classifies anomaly rescue so single-frame anomalies can be exported separately from tracks.
     */
    static AnomalyType classifyAnomalyRescue(SourceExtractor.DetectedObject orphan, DetectionConfig config) {
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
     * Groups rescued anomalies into one or more same-frame suspected streak tracks per frame.
     *
     * <p>All rescued anomalies from the same frame are evaluated for collinearity. Any accepted
     * grouping stays frame-local and never links across frames.</p>
     */
    private static List<Track> groupSuspectedStreakTracks(List<AnomalyDetection> anomalies, DetectionConfig config) {
        List<Track> suspectedTracks = new ArrayList<>();

        int maxFrameIndex = -1;
        for (AnomalyDetection anomaly : anomalies) {
            if (anomaly.object.sourceFrameIndex >= 0) {
                maxFrameIndex = Math.max(maxFrameIndex, anomaly.object.sourceFrameIndex);
            }
        }

        if (maxFrameIndex < 0) {
            return suspectedTracks;
        }

        List<List<SourceExtractor.DetectedObject>> frameObjects = new ArrayList<>();
        for (int i = 0; i <= maxFrameIndex; i++) {
            frameObjects.add(new ArrayList<>());
        }

        for (AnomalyDetection anomaly : anomalies) {
            if (anomaly.object.sourceFrameIndex >= 0) {
                frameObjects.get(anomaly.object.sourceFrameIndex).add(anomaly.object);
            }
        }

        for (List<SourceExtractor.DetectedObject> currentFrameObjects : frameObjects) {
            suspectedTracks.addAll(buildSuspectedStreakTracksForFrame(currentFrameObjects, config));
        }

        return suspectedTracks;
    }

    /**
     * Repeatedly extracts disjoint same-frame suspected streaks so parallel fragments can all be returned.
     */
    private static List<Track> buildSuspectedStreakTracksForFrame(List<SourceExtractor.DetectedObject> frameObjects,
                                                                  DetectionConfig config) {
        if (frameObjects.size() < 3) {
            return new ArrayList<>();
        }

        List<Track> suspectedTracks = new ArrayList<>();
        List<SourceExtractor.DetectedObject> remainingObjects = new ArrayList<>(frameObjects);
        while (remainingObjects.size() >= 3) {
            SuspectedStreakLineCandidate bestLine = buildBestSuspectedStreakTrack(remainingObjects, config);
            if (bestLine == null) {
                break;
            }

            Track suspectedTrack = new Track();
            suspectedTrack.isSuspectedStreakTrack = true;
            suspectedTrack.points.addAll(bestLine.objects);
            suspectedTracks.add(suspectedTrack);
            remainingObjects.removeAll(bestLine.objects);
        }

        return suspectedTracks;
    }

    /**
     * Finds the strongest same-frame collinear grouping among rescued anomalies from one frame.
     */
    private static SuspectedStreakLineCandidate buildBestSuspectedStreakTrack(List<SourceExtractor.DetectedObject> frameObjects,
                                                                              DetectionConfig config) {
        if (frameObjects.size() < 3) {
            return null;
        }

        SuspectedStreakLineCandidate bestLine = null;
        double maxLineError = getSuspectedStreakLineTolerance(config);

        for (int i = 0; i < frameObjects.size() - 1; i++) {
            for (int j = i + 1; j < frameObjects.size(); j++) {
                SourceExtractor.DetectedObject start = frameObjects.get(i);
                SourceExtractor.DetectedObject end = frameObjects.get(j);
                double baselineDistance = distance(start.x, start.y, end.x, end.y);
                if (baselineDistance < 1.0) {
                    continue;
                }

                List<SourceExtractor.DetectedObject> collinearObjects = new ArrayList<>();
                for (SourceExtractor.DetectedObject candidate : frameObjects) {
                    if (distanceToLineOptimized(start, end, candidate, baselineDistance) <= maxLineError) {
                        collinearObjects.add(candidate);
                    }
                }

                if (collinearObjects.size() < 3) {
                    continue;
                }

                orderObjectsAlongLine(collinearObjects, start, end);
                double span = distance(
                        collinearObjects.get(0).x,
                        collinearObjects.get(0).y,
                        collinearObjects.get(collinearObjects.size() - 1).x,
                        collinearObjects.get(collinearObjects.size() - 1).y
                );

                SuspectedStreakLineCandidate currentLine = new SuspectedStreakLineCandidate(
                        collinearObjects,
                        span
                );
                if (isBetterSuspectedStreakLineCandidate(currentLine, bestLine)) {
                    bestLine = currentLine;
                }
            }
        }

        return bestLine;
    }

    /**
     * Prefers lines with denser total support, then larger span.
     */
    private static boolean isBetterSuspectedStreakLineCandidate(SuspectedStreakLineCandidate currentLine,
                                                                SuspectedStreakLineCandidate bestLine) {
        if (bestLine == null) {
            return true;
        }
        if (currentLine.objects.size() != bestLine.objects.size()) {
            return currentLine.objects.size() > bestLine.objects.size();
        }
        return currentLine.span > bestLine.span + 1e-6;
    }

    /**
     * Returns the dedicated same-frame line tolerance used by suspected streak grouping.
     */
    private static double getSuspectedStreakLineTolerance(DetectionConfig config) {
        return Math.max(0.0, config.suspectedStreakLineTolerance);
    }

    /**
     * Orders same-frame collinear anomalies along the candidate line so the exported track is stable.
     */
    private static void orderObjectsAlongLine(List<SourceExtractor.DetectedObject> objects,
                                              SourceExtractor.DetectedObject start,
                                              SourceExtractor.DetectedObject end) {
        double dx = end.x - start.x;
        double dy = end.y - start.y;
        objects.sort((left, right) -> Double.compare(
                projectionAlongLine(left, start.x, start.y, dx, dy),
                projectionAlongLine(right, start.x, start.y, dx, dy)
        ));
    }

    /**
     * Removes any detection already consumed by an accepted track before Phase 5 anomaly rescue.
     */
    static List<List<SourceExtractor.DetectedObject>> buildPhase5AnomalyCandidates(
            List<List<SourceExtractor.DetectedObject>> allTransients,
            List<Track> streakTracks,
            List<Track> pointTracks) {
        Set<SourceExtractor.DetectedObject> trackedObjects = new HashSet<>();
        for (Track track : streakTracks) {
            trackedObjects.addAll(track.points);
        }
        for (Track track : pointTracks) {
            trackedObjects.addAll(track.points);
        }

        List<List<SourceExtractor.DetectedObject>> anomalyCandidates = new ArrayList<>(allTransients.size());
        for (List<SourceExtractor.DetectedObject> frameObjects : allTransients) {
            List<SourceExtractor.DetectedObject> filteredFrame = new ArrayList<>();
            for (SourceExtractor.DetectedObject object : frameObjects) {
                if (!trackedObjects.contains(object)) {
                    filteredFrame.add(object);
                }
            }
            anomalyCandidates.add(filteredFrame);
        }
        return anomalyCandidates;
    }

    /**
     * Builds the full post-veto transient population per frame from point transients and mobile streaks.
     */
    static List<List<SourceExtractor.DetectedObject>> buildAllTransients(
            List<List<SourceExtractor.DetectedObject>> pointTransients,
            List<SourceExtractor.DetectedObject> validMovingStreaks,
            int numFrames) {
        List<List<SourceExtractor.DetectedObject>> allTransients = new ArrayList<>(numFrames);
        for (int i = 0; i < numFrames; i++) {
            List<SourceExtractor.DetectedObject> frameObjects = new ArrayList<>();
            if (pointTransients != null && i < pointTransients.size() && pointTransients.get(i) != null) {
                frameObjects.addAll(pointTransients.get(i));
            }
            allTransients.add(frameObjects);
        }

        if (validMovingStreaks != null) {
            for (SourceExtractor.DetectedObject streak : validMovingStreaks) {
                if (streak == null || streak.sourceFrameIndex < 0 || streak.sourceFrameIndex >= numFrames) {
                    continue;
                }
                allTransients.get(streak.sourceFrameIndex).add(streak);
            }
        }
        return allTransients;
    }

    /**
     * Removes all detections that were consumed by accepted tracks or surviving standalone anomalies.
     */
    static List<List<SourceExtractor.DetectedObject>> buildUnclassifiedTransients(
            List<List<SourceExtractor.DetectedObject>> allTransients,
            List<Track> tracks,
            List<AnomalyDetection> anomalies) {
        Set<SourceExtractor.DetectedObject> classifiedObjects = new HashSet<>();
        if (tracks != null) {
            for (Track track : tracks) {
                if (track != null && track.points != null) {
                    classifiedObjects.addAll(track.points);
                }
            }
        }
        if (anomalies != null) {
            for (AnomalyDetection anomaly : anomalies) {
                if (anomaly != null && anomaly.object != null) {
                    classifiedObjects.add(anomaly.object);
                }
            }
        }

        List<List<SourceExtractor.DetectedObject>> unclassifiedTransients = new ArrayList<>(allTransients.size());
        for (List<SourceExtractor.DetectedObject> frameObjects : allTransients) {
            List<SourceExtractor.DetectedObject> frameUnclassified = new ArrayList<>();
            if (frameObjects != null) {
                for (SourceExtractor.DetectedObject object : frameObjects) {
                    if (!classifiedObjects.contains(object)) {
                        frameUnclassified.add(object);
                    }
                }
            }
            unclassifiedTransients.add(frameUnclassified);
        }
        return unclassifiedTransients;
    }

    // =================================================================
    // HELPER METHODS
    // =================================================================

    /**
     * Measures what fraction of an object's footprint overlaps a precomputed boolean mask.
     */
    private static double computeMaskOverlapFraction(SourceExtractor.DetectedObject obj, boolean[][] mask) {
        if (obj.rawPixels == null || obj.rawPixels.isEmpty()) {
            return 0.0;
        }

        int overlapCount = 0;
        int sensorHeight = mask.length;
        int sensorWidth = sensorHeight > 0 ? mask[0].length : 0;
        for (SourceExtractor.Pixel p : obj.rawPixels) {
            if (p.x >= 0 && p.x < sensorWidth && p.y >= 0 && p.y < sensorHeight && mask[p.y][p.x]) {
                overlapCount++;
            }
        }
        return (double) overlapCount / obj.rawPixels.size();
    }

    /**
     * Scores time-based track candidates so that longer, straighter, and more uniformly paced tracks rank first.
     */
    private static double scoreTimeBasedTrackCandidate(Track track) {
        if (track.points.size() < 2) {
            return Double.NEGATIVE_INFINITY;
        }

        SourceExtractor.DetectedObject start = track.points.get(0);
        SourceExtractor.DetectedObject end = track.points.get(track.points.size() - 1);

        double frameSpan = Math.max(1, end.sourceFrameIndex - start.sourceFrameIndex);
        double coverageRatio = (double) track.points.size() / (frameSpan + 1.0);
        double totalDistance = distance(start.x, start.y, end.x, end.y);
        double baselineDistance = Math.max(totalDistance, 0.0001);
        double expectedAngle = Math.atan2(end.y - start.y, end.x - start.x);
        double expectedDelta = Math.max(getTrackDelta(start, end), 0.0001);
        double expectedSpeed = totalDistance / expectedDelta;

        double totalLineError = 0.0;
        int lineSamples = 0;
        for (int i = 1; i < track.points.size() - 1; i++) {
            totalLineError += distanceToLineOptimized(start, end, track.points.get(i), baselineDistance);
            lineSamples++;
        }

        double totalAngleError = 0.0;
        double totalSpeedError = 0.0;
        int segmentSamples = 0;
        for (int i = 0; i < track.points.size() - 1; i++) {
            SourceExtractor.DetectedObject p1 = track.points.get(i);
            SourceExtractor.DetectedObject p2 = track.points.get(i + 1);

            double segmentDelta = getTrackDelta(p1, p2);
            if (segmentDelta <= 0) {
                continue;
            }

            double segmentDistance = distance(p1.x, p1.y, p2.x, p2.y);
            double segmentSpeed = segmentDistance / segmentDelta;
            double segmentAngle = Math.atan2(p2.y - p1.y, p2.x - p1.x);

            totalAngleError += angularDifference(expectedAngle, segmentAngle);
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

        SourceExtractor.DetectedObject leftStart = left.track.points.get(0);
        SourceExtractor.DetectedObject rightStart = right.track.points.get(0);
        return Integer.compare(leftStart.sourceFrameIndex, rightStart.sourceFrameIndex);
    }

    /**
     * Checks whether any point in the candidate track has already been consumed by a higher-ranked track.
     */
    private static boolean conflictsWithUsedPoints(Set<SourceExtractor.DetectedObject> usedPoints, Track track) {
        for (SourceExtractor.DetectedObject point : track.points) {
            if (usedPoints.contains(point)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns the time delta when timestamps are available, otherwise the frame-index delta.
     */
    private static double getTrackDelta(SourceExtractor.DetectedObject from, SourceExtractor.DetectedObject to) {
        if (from.timestamp != -1 && to.timestamp != -1) {
            double delta = to.timestamp - from.timestamp;
            if (delta > 0) {
                return delta;
            }
        }
        return Math.max(1, to.sourceFrameIndex - from.sourceFrameIndex);
    }


    /**
     * Applies morphology consistency checks between two candidate points.
     */
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

    /**
     * Returns Euclidean distance between two points.
     */
    private static double distance(double x1, double y1, double x2, double y2) {
        double dx = x2 - x1;
        double dy = y2 - y1;
        return Math.sqrt(dx * dx + dy * dy);
    }

    /**
     * Compares two orientation angles while treating 180-degree flips as equivalent streak directions.
     */
    private static boolean anglesMatch(double a1, double a2, double tolerance) {
        double diff = Math.abs(a1 - a2) % Math.PI;
        return diff <= tolerance || Math.PI - diff <= tolerance;
    }

    /**
     * Returns whether an observed motion vector stays within the expected directional cone.
     */
    private static boolean isDirectionConsistent(double expectedAngle, double actualAngle, double tolerance) {
        double diff = Math.abs(expectedAngle - actualAngle);
        if (diff > Math.PI) diff = (2.0 * Math.PI) - diff;
        return diff <= tolerance;
    }

    /**
     * Computes the smallest unsigned angular distance between two directions.
     */
    private static double angularDifference(double a1, double a2) {
        double diff = Math.abs(a1 - a2);
        if (diff > Math.PI) {
            diff = (2.0 * Math.PI) - diff;
        }
        return diff;
    }

    /**
     * Projects one object onto a candidate same-frame line for stable ordering.
     */
    private static double projectionAlongLine(SourceExtractor.DetectedObject object,
                                              double originX,
                                              double originY,
                                              double dx,
                                              double dy) {
        return ((object.x - originX) * dx) + ((object.y - originY) * dy);
    }

    /**
     * Computes perpendicular distance from {@code p3} to the line defined by {@code p1 -> p2}.
     */
    private static double distanceToLineOptimized(SourceExtractor.DetectedObject p1,
                                                  SourceExtractor.DetectedObject p2,
                                                  SourceExtractor.DetectedObject p3,
                                                  double precalculatedDenominator) {

        if (precalculatedDenominator == 0) return Double.MAX_VALUE;
        double numerator = Math.abs((p2.x - p1.x) * (p1.y - p3.y) - (p1.x - p3.x) * (p2.y - p1.y));
        return numerator / precalculatedDenominator;
    }

    /**
     * Treats tracks that share two or more detections as duplicates.
     */
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

    /**
     * Validates that the step sizes in a track follow a mostly steady rhythm after allowing for skipped frames.
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

}
