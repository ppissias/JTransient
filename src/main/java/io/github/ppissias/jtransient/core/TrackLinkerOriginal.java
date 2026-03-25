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

public class TrackLinkerOriginal {

    private static final int MAX_SEED_CANDIDATES_PER_FRAME = 6;
    private static final int MAX_TRACK_CANDIDATES_PER_FRAME = 8;
    private static final int MAX_PROPOSALS_PER_START_POINT = 3;
    private static final int MAX_TIME_PROPOSALS_PER_START_POINT = 12;
    private static final double TIME_BASED_PROFILE_RATIO_MULTIPLIER = 1.75;
    private static final double MIN_TIME_BASED_VELOCITY_TOLERANCE = 0.35;
    private static final double TIME_BASED_MIN_SEED_DISTANCE_FACTOR = 0.35;

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

    private static class TrackState {
        private final List<SourceExtractor.DetectedObject> points = new ArrayList<>();
        private final Set<SourceExtractor.DetectedObject> pointSet = new HashSet<>();
        private final SourceExtractor.DetectedObject origin;
        private SourceExtractor.DetectedObject anchor;
        private final boolean timeBased;
        private double velocityX;
        private double velocityY;
        private double expectedAngle;
        private double totalLinkScore;
        private int successfulLinks;

        TrackState(SourceExtractor.DetectedObject p1, SourceExtractor.DetectedObject p2, boolean timeBased) {
            this.origin = p1;
            this.anchor = p2;
            this.timeBased = timeBased;
            points.add(p1);
            points.add(p2);
            pointSet.add(p1);
            pointSet.add(p2);
            refreshKinematics(p2);
        }

        double getCurrentSpeed() {
            return Math.hypot(velocityX, velocityY);
        }

        double getExpectedAngle() {
            return expectedAngle;
        }

        SourceExtractor.DetectedObject getOrigin() {
            return origin;
        }

        SourceExtractor.DetectedObject getAnchor() {
            return anchor;
        }

        List<SourceExtractor.DetectedObject> getPoints() {
            return points;
        }

        boolean containsPoint(SourceExtractor.DetectedObject point) {
            return pointSet.contains(point);
        }

        double getAverageLinkScore() {
            return successfulLinks == 0 ? 0.0 : totalLinkScore / successfulLinks;
        }

        boolean isTimeBased() {
            return timeBased;
        }

        double predictX(int frameIndex, long frameTimestamp) {
            return origin.x + (velocityX * getDeltaFromOrigin(frameIndex, frameTimestamp));
        }

        double predictY(int frameIndex, long frameTimestamp) {
            return origin.y + (velocityY * getDeltaFromOrigin(frameIndex, frameTimestamp));
        }

        double getDeltaFromOrigin(SourceExtractor.DetectedObject point) {
            return getDeltaFromOrigin(point.sourceFrameIndex, point.timestamp);
        }

        double getDeltaFromAnchor(SourceExtractor.DetectedObject point) {
            return getDelta(anchor.sourceFrameIndex, anchor.timestamp, point.sourceFrameIndex, point.timestamp);
        }

        void addPoint(SourceExtractor.DetectedObject point, double linkScore) {
            points.add(point);
            pointSet.add(point);
            anchor = point;
            totalLinkScore += linkScore;
            successfulLinks++;
            refreshKinematics(point);
        }

        private void refreshKinematics(SourceExtractor.DetectedObject target) {
            double totalDelta = getDeltaFromOrigin(target);
            if (totalDelta <= 0) {
                return;
            }
            velocityX = (target.x - origin.x) / totalDelta;
            velocityY = (target.y - origin.y) / totalDelta;
            expectedAngle = Math.atan2(velocityY, velocityX);
        }

        private double getDeltaFromOrigin(int frameIndex, long frameTimestamp) {
            return getDelta(origin.sourceFrameIndex, origin.timestamp, frameIndex, frameTimestamp);
        }

        private double getDelta(int fromFrameIndex, long fromTimestamp, int toFrameIndex, long toTimestamp) {
            if (timeBased) {
                return toTimestamp - fromTimestamp;
            }
            return toFrameIndex - fromFrameIndex;
        }
    }

    private static class TrackProposal {
        private final Track track;
        private final boolean timeBased;
        private final double qualityScore;
        private final double averageLinkScore;

        TrackProposal(Track track, boolean timeBased, double qualityScore, double averageLinkScore) {
            this.track = track;
            this.timeBased = timeBased;
            this.qualityScore = qualityScore;
            this.averageLinkScore = averageLinkScore;
        }
    }

    private static class CandidateMatch {
        private final SourceExtractor.DetectedObject point;
        private final double linkScore;

        CandidateMatch(SourceExtractor.DetectedObject point, double linkScore) {
            this.point = point;
            this.linkScore = linkScore;
        }
    }

    private static class TrackAcceptanceResult {
        private final List<Track> acceptedTracks = new ArrayList<>();
        private final Set<SourceExtractor.DetectedObject> usedPoints = new HashSet<>();
        private int timeTracksFound;
        private int geometricTracksFound;
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

        double gridCellSize = Math.max(8.0, Math.min(64.0,
                Math.max(config.predictionTolerance * 2.0, config.maxStarJitter * 4.0)));
        List<SpatialGrid> frameGrids = buildFrameGrids(transients, gridCellSize);
        long[] frameTimestamps = extractFrameTimestamps(allFrames);
        double fullFrameRadius = Math.hypot(sensorWidth, sensorHeight);
        int loopMax = numFrames - 2;
        int maxSeedFrameGap = computeMaxSeedFrameGap(numFrames, minPointsRequired);
        Set<SourceExtractor.DetectedObject> noBlockedPoints = new HashSet<>();
        List<TrackProposal> timeTrackProposals = new ArrayList<>();
        int generatedTimeTracks = 0;

        // =================================================================
        // PHASE 3.5: TIME-BASED KINEMATIC LINKING (Velocity Vector Matching)
        // =================================================================
        boolean hasTimestamps = hasValidTimestamps(allFrames);

        if (hasTimestamps) {
            if (JTransientEngine.DEBUG) System.out.println("DEBUG: [PHASE 3.5] Timestamps detected. Running Time-Based Velocity Linking...");
            if (listener != null) listener.onProgressUpdate(50, "Analyzing precise velocity kinematics...");

            for (int f1 = 0; f1 < loopMax; f1++) {
                for (SourceExtractor.DetectedObject p1 : transients.get(f1)) {
                    List<TrackProposal> proposalsForStartPoint = new ArrayList<>();
                    for (int f2 = f1 + 1; f2 < numFrames - 1; f2++) {
                        if (frameTimestamps[f2] == -1) continue;

                        double seedRadius = computeSeedSearchRadius(
                                p1, f2, frameTimestamps[f2], config, fullFrameRadius, true);
                        for (SourceExtractor.DetectedObject p2 : frameGrids.get(f2)
                                .getCandidates(p1.x, p1.y, seedRadius)) {
                            TrackProposal proposal = buildTrackProposal(
                                    p1, p2, f2, transients, frameGrids, frameTimestamps, config,
                                    telemetry, angleToleranceRad, minPointsRequired, true, noBlockedPoints);
                            if (proposal != null) {
                                proposalsForStartPoint.add(proposal);
                                generatedTimeTracks++;
                            }
                        }
                    }
                    appendBestTrackProposals(timeTrackProposals, proposalsForStartPoint, MAX_TIME_PROPOSALS_PER_START_POINT);
                }
            }
            if (JTransientEngine.DEBUG) {
                System.out.println("DEBUG: [PHASE 3.5] Completed. Generated " + generatedTimeTracks + " precise time-based track proposal(s).");
            }
        }

        TrackAcceptanceResult timeAcceptanceResult = acceptTrackProposals(timeTrackProposals, telemetry, masterStars);
        List<Track> pointTracks = new ArrayList<>(timeAcceptanceResult.acceptedTracks);
        Set<SourceExtractor.DetectedObject> usedPoints = new HashSet<>(timeAcceptanceResult.usedPoints);
        int timeTracksFound = timeAcceptanceResult.timeTracksFound;

        if (JTransientEngine.DEBUG && hasTimestamps) {
            System.out.println("DEBUG: [PHASE 3.5] Accepted " + timeTracksFound + " time-based track(s) before geometric fallback.");
        }

        // =================================================================
        // PHASE 4: GEOMETRIC COLLINEAR LINKING (Frame-Agnostic Fallback)
        // =================================================================
        if (JTransientEngine.DEBUG) {
            System.out.println("DEBUG: [PHASE 4] Applying time-agnostic geometric fallback filter...");
            System.out.println("  -> Track confirmation threshold: " + minPointsRequired + " points.");
        }

        List<TrackProposal> geometricTrackProposals = new ArrayList<>();
        for (int f1 = 0; f1 < loopMax; f1++) {

            // --- SMOOTH PROGRESS TRACKING FOR PHASE 4 (50% to 90%) ---
            if (listener != null) {
                int progress = 60 + (int) (((double) f1 / loopMax) * 30.0);
                listener.onProgressUpdate(progress, "Analyzing kinematics: Frame " + (f1 + 1) + " of " + loopMax);
            }

            for (SourceExtractor.DetectedObject p1 : transients.get(f1)) {
                if (usedPoints.contains(p1)) {
                    continue;
                }
                List<TrackProposal> proposalsForStartPoint = new ArrayList<>();
                int maxF2 = Math.min(numFrames - 1, f1 + maxSeedFrameGap + 1);
                for (int f2 = f1 + 1; f2 < maxF2; f2++) {
                    double seedRadius = Math.min(config.maxJumpPixels, config.maxJumpPixels * Math.max(1, f2 - f1));
                    for (SourceExtractor.DetectedObject p2 : frameGrids.get(f2)
                            .getNearestCandidates(p1.x, p1.y, seedRadius, MAX_SEED_CANDIDATES_PER_FRAME)) {
                        if (usedPoints.contains(p2)) {
                            continue;
                        }
                        TrackProposal proposal = buildTrackProposal(
                                p1, p2, f2, transients, frameGrids, frameTimestamps, config,
                                telemetry, angleToleranceRad, minPointsRequired, false, usedPoints);
                        if (proposal != null) {
                            proposalsForStartPoint.add(proposal);
                        }
                    }
                }
                appendBestTrackProposals(geometricTrackProposals, proposalsForStartPoint, MAX_PROPOSALS_PER_START_POINT);
            }
        }

        TrackAcceptanceResult geometricAcceptanceResult = acceptTrackProposals(geometricTrackProposals, telemetry, masterStars);
        pointTracks.addAll(geometricAcceptanceResult.acceptedTracks);
        usedPoints.addAll(geometricAcceptanceResult.usedPoints);

        if (JTransientEngine.DEBUG) {
            System.out.println("DEBUG: [PHASE 4] Accepted " + geometricAcceptanceResult.geometricTracksFound + " geometric fallback track(s).");
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

    private static List<SpatialGrid> buildFrameGrids(List<List<SourceExtractor.DetectedObject>> frames, double cellSize) {
        List<SpatialGrid> frameGrids = new ArrayList<>(frames.size());
        for (List<SourceExtractor.DetectedObject> frame : frames) {
            frameGrids.add(new SpatialGrid(frame, cellSize));
        }
        return frameGrids;
    }

    private static void appendBestTrackProposals(List<TrackProposal> accumulator,
                                                 List<TrackProposal> proposals,
                                                 int maxToKeep) {
        if (proposals.isEmpty() || maxToKeep <= 0) {
            return;
        }

        proposals.sort(TrackLinkerOriginal::compareTrackProposals);
        int limit = Math.min(maxToKeep, proposals.size());
        for (int i = 0; i < limit; i++) {
            accumulator.add(proposals.get(i));
        }
    }

    private static long[] extractFrameTimestamps(List<List<SourceExtractor.DetectedObject>> allFrames) {
        long[] frameTimestamps = new long[allFrames.size()];
        for (int i = 0; i < allFrames.size(); i++) {
            frameTimestamps[i] = -1;
            for (SourceExtractor.DetectedObject obj : allFrames.get(i)) {
                if (obj.timestamp != -1) {
                    frameTimestamps[i] = obj.timestamp;
                    break;
                }
            }
        }
        return frameTimestamps;
    }

    private static boolean hasValidTimestamps(List<List<SourceExtractor.DetectedObject>> allFrames) {
        for (List<SourceExtractor.DetectedObject> frame : allFrames) {
            for (SourceExtractor.DetectedObject obj : frame) {
                if (obj.timestamp != -1) {
                    return true;
                }
            }
        }
        return false;
    }

    private static int computeMaxSeedFrameGap(int numFrames, int minPointsRequired) {
        if (minPointsRequired <= 0) {
            return 2;
        }
        return Math.max(2, (int) Math.ceil((double) numFrames / minPointsRequired));
    }

    private static double computeSeedSearchRadius(SourceExtractor.DetectedObject seed,
                                                  int targetFrameIndex,
                                                  long targetTimestamp,
                                                  DetectionConfig config,
                                                  double fullFrameRadius,
                                                  boolean timeBased) {
        int frameGap = Math.max(1, targetFrameIndex - seed.sourceFrameIndex);
        if (!timeBased || seed.timestamp == -1 || targetTimestamp == -1) {
            return Math.min(fullFrameRadius, config.maxJumpPixels * frameGap);
        }
        double dt = targetTimestamp - seed.timestamp;
        if (dt <= 0) {
            return 0.0;
        }
        if (config.strictExposureKinematics && seed.exposureDuration > 0) {
            return Math.min(fullFrameRadius, computeStrictMaxAllowedJump(seed, dt, config));
        }
        return fullFrameRadius;
    }

    private static TrackProposal buildTrackProposal(SourceExtractor.DetectedObject p1,
                                                    SourceExtractor.DetectedObject p2,
                                                    int secondFrameIndex,
                                                    List<List<SourceExtractor.DetectedObject>> transients,
                                                    List<SpatialGrid> frameGrids,
                                                    long[] frameTimestamps,
                                                    DetectionConfig config,
                                                    TrackerTelemetry telemetry,
                                                    double angleToleranceRad,
                                                    int minPointsRequired,
                                                    boolean timeBased,
                                                    Set<SourceExtractor.DetectedObject> blockedPoints) {

        if (p1 == p2) {
            return null;
        }
        if (blockedPoints.contains(p1) || blockedPoints.contains(p2)) {
            return null;
        }

        double dist12 = distance(p1.x, p1.y, p2.x, p2.y);
        double minSeedDistance = timeBased
                ? Math.max(0.5, config.maxStarJitter * TIME_BASED_MIN_SEED_DISTANCE_FACTOR)
                : config.maxStarJitter;
        if (dist12 < minSeedDistance) {
            telemetry.countBaselineJitter++;
            return null;
        }
        if (!timeBased && dist12 > config.maxJumpPixels) {
            telemetry.countBaselineJump++;
            return null;
        }
        if (!isLinkProfileConsistent(p1, p2, config, timeBased)) {
            telemetry.countBaselineSize++;
            return null;
        }
        if (!passesStrictExposureKinematics(p1, p2, dist12, config)) {
            telemetry.countBaselineJump++;
            return null;
        }
        if (timeBased && (p1.timestamp == -1 || p2.timestamp == -1 || p2.timestamp <= p1.timestamp)) {
            return null;
        }

        TrackState state = new TrackState(p1, p2, timeBased);

        for (int f3 = secondFrameIndex + 1; f3 < transients.size(); f3++) {
            long frameTimestamp = frameTimestamps[f3];
            if (timeBased && frameTimestamp == -1) {
                continue;
            }

            double searchRadius = computeTrackSearchRadius(state, f3, frameTimestamp, config);
            if (searchRadius <= 0) {
                continue;
            }

            double predictedX = state.predictX(f3, frameTimestamp);
            double predictedY = state.predictY(f3, frameTimestamp);
            CandidateMatch bestMatch = null;

            List<SourceExtractor.DetectedObject> candidatePool = timeBased
                    ? frameGrids.get(f3).getCandidates(predictedX, predictedY, searchRadius)
                    : frameGrids.get(f3).getNearestCandidates(predictedX, predictedY, searchRadius, MAX_TRACK_CANDIDATES_PER_FRAME);

            for (SourceExtractor.DetectedObject candidate : candidatePool) {
                if (blockedPoints.contains(candidate) || state.containsPoint(candidate)) {
                    continue;
                }

                CandidateMatch scoredCandidate = scoreCandidate(
                        state, candidate, f3, frameTimestamp, config, telemetry, angleToleranceRad);

                if (scoredCandidate != null && (bestMatch == null || scoredCandidate.linkScore < bestMatch.linkScore)) {
                    bestMatch = scoredCandidate;
                }
            }

            if (bestMatch != null) {
                state.addPoint(bestMatch.point, bestMatch.linkScore);
            }
        }

        return finalizeTrackProposal(state, config, telemetry, minPointsRequired);
    }

    private static double computeTrackSearchRadius(TrackState state,
                                                   int frameIndex,
                                                   long frameTimestamp,
                                                   DetectionConfig config) {
        double deltaToFrame = state.isTimeBased()
                ? frameTimestamp - state.getAnchor().timestamp
                : frameIndex - state.getAnchor().sourceFrameIndex;

        if (deltaToFrame <= 0) {
            return 0.0;
        }

        double currentSpeed = Math.max(state.getCurrentSpeed(), config.maxStarJitter);
        double radius = config.predictionTolerance + config.maxStarJitter;

        if (state.isTimeBased()) {
            double relaxedVelocityTolerance = Math.max(
                    config.timeBasedVelocityTolerance * 2.0,
                    MIN_TIME_BASED_VELOCITY_TOLERANCE);
            radius += currentSpeed * relaxedVelocityTolerance * deltaToFrame;
            radius += config.maxStarJitter;
            return Math.max(config.predictionTolerance + config.maxStarJitter, radius);
        }

        radius += Math.min(config.maxJumpPixels, currentSpeed * 0.35 * deltaToFrame);
        return Math.max(config.maxStarJitter * 2.0, Math.min(config.maxJumpPixels, radius));
    }

    private static CandidateMatch scoreCandidate(TrackState state,
                                                 SourceExtractor.DetectedObject candidate,
                                                 int frameIndex,
                                                 long frameTimestamp,
                                                 DetectionConfig config,
                                                 TrackerTelemetry telemetry,
                                                 double angleToleranceRad) {

        SourceExtractor.DetectedObject anchor = state.getAnchor();
        double jumpDist = distance(anchor.x, anchor.y, candidate.x, candidate.y);
        if (!state.isTimeBased() && jumpDist > config.maxJumpPixels) {
            telemetry.countP3Jump++;
            return null;
        }
        if (!passesStrictExposureKinematics(anchor, candidate, jumpDist, config)) {
            telemetry.countP3Jump++;
            return null;
        }
        if (!isLinkProfileConsistent(anchor, candidate, config, state.isTimeBased())) {
            telemetry.countP3Size++;
            return null;
        }

        double deltaFromAnchor = state.getDeltaFromAnchor(candidate);
        if (deltaFromAnchor <= 0) {
            return null;
        }

        double baselineDistance = distance(state.getOrigin().x, state.getOrigin().y, anchor.x, anchor.y);
        double lineError = distanceToLineOptimized(state.getOrigin(), anchor, candidate, baselineDistance);
        double allowedLineError = getAllowedLineError(state.isTimeBased(), config);
        if (lineError > allowedLineError) {
            telemetry.countP3NotLine++;
            return null;
        }

        double predictedX = state.predictX(frameIndex, frameTimestamp);
        double predictedY = state.predictY(frameIndex, frameTimestamp);
        double residual = distance(predictedX, predictedY, candidate.x, candidate.y);
        double searchRadius = computeTrackSearchRadius(state, frameIndex, frameTimestamp, config);
        if (residual > searchRadius) {
            telemetry.countP3NotLine++;
            return null;
        }

        double segmentVx = (candidate.x - anchor.x) / deltaFromAnchor;
        double segmentVy = (candidate.y - anchor.y) / deltaFromAnchor;
        double actualAngle = Math.atan2(segmentVy, segmentVx);
        double dynamicAngleTolerance = getAllowedAngleTolerance(
                state.isTimeBased(), angleToleranceRad, jumpDist, config);
        double angleError = angularDifference(state.getExpectedAngle(), actualAngle);

        if (angleError > dynamicAngleTolerance) {
            telemetry.countP3WrongDirection++;
            return null;
        }

        double currentSpeed = Math.max(state.getCurrentSpeed(), 0.0001);
        double candidateSpeed = Math.hypot(segmentVx, segmentVy);
        double allowedSpeedDiff = getAllowedSpeedDifference(
                state.isTimeBased(), currentSpeed, deltaFromAnchor, config);
        double speedDiff = Math.abs(candidateSpeed - currentSpeed);

        if (speedDiff > allowedSpeedDiff) {
            telemetry.countP3Jump++;
            return null;
        }

        double normalizedLine = lineError / Math.max(allowedLineError, 1.0);
        double normalizedSpeed = speedDiff / Math.max(allowedSpeedDiff, 0.1);
        double normalizedAngle = angleError / Math.max(dynamicAngleTolerance, 0.0001);
        double linkScore;
        if (state.isTimeBased()) {
            double normalizedResidual = residual / Math.max(searchRadius, 1.0);
            linkScore = (normalizedResidual * 1.5) + normalizedSpeed + (normalizedLine * 0.75) + (normalizedAngle * 0.5);
        } else {
            double normalizedResidual = residual / Math.max(searchRadius, 1.0);
            linkScore = (normalizedResidual * 2.0) + (normalizedLine * 1.5) + normalizedSpeed + normalizedAngle;
        }

        return new CandidateMatch(candidate, linkScore);
    }

    private static TrackProposal finalizeTrackProposal(TrackState state,
                                                       DetectionConfig config,
                                                       TrackerTelemetry telemetry,
                                                       int minPointsRequired) {
        Track track = new Track();
        double minStepDistance = state.isTimeBased()
                ? Math.max(0.0, config.maxStarJitter * 0.25)
                : config.maxStarJitter;
        track.points = pruneTrackPoints(state.getPoints(), minStepDistance);
        track.isTimeBasedTrack = state.isTimeBased();

        if (track.points.size() < minPointsRequired) {
            telemetry.countTrackTooShort++;
            return null;
        }

        if (!state.isTimeBased() &&
                !hasSteadyRhythm(track, config.rhythmAllowedVariance, config.rhythmStationaryThreshold, config.rhythmMinConsistencyRatio)) {
            telemetry.countTrackErraticRhythm++;
            return null;
        }

        double frameSpan = track.points.get(track.points.size() - 1).sourceFrameIndex - track.points.get(0).sourceFrameIndex;
        double qualityScore = (track.points.size() * 100.0)
                + (frameSpan * 2.0)
                + (state.isTimeBased() ? 15.0 : 0.0)
                - (state.getAverageLinkScore() * 20.0);

        return new TrackProposal(track, state.isTimeBased(), qualityScore, state.getAverageLinkScore());
    }

    private static List<SourceExtractor.DetectedObject> pruneTrackPoints(List<SourceExtractor.DetectedObject> points,
                                                                         double minStepDistance) {
        List<SourceExtractor.DetectedObject> prunedPoints = new ArrayList<>();
        if (points.isEmpty()) {
            return prunedPoints;
        }

        prunedPoints.add(points.get(0));
        for (int i = 1; i < points.size(); i++) {
            SourceExtractor.DetectedObject previous = prunedPoints.get(prunedPoints.size() - 1);
            SourceExtractor.DetectedObject current = points.get(i);
            double stepDist = distance(previous.x, previous.y, current.x, current.y);

            if (stepDist > minStepDistance) {
                prunedPoints.add(current);
            }
        }
        return prunedPoints;
    }

    private static TrackAcceptanceResult acceptTrackProposals(List<TrackProposal> proposals,
                                                              TrackerTelemetry telemetry,
                                                              List<SourceExtractor.DetectedObject> masterStars) {
        TrackAcceptanceResult result = new TrackAcceptanceResult();
        proposals.sort(TrackLinkerOriginal::compareTrackProposals);

        SpatialGrid masterGridForDebug = JTransientEngine.DEBUG ? new SpatialGrid(masterStars, 30.0) : null;

        for (TrackProposal proposal : proposals) {
            if (conflictsWithUsedPoints(result.usedPoints, proposal.track)) {
                telemetry.countTrackDuplicate++;
                continue;
            }

            result.acceptedTracks.add(proposal.track);
            result.usedPoints.addAll(proposal.track.points);

            if (proposal.timeBased) {
                result.timeTracksFound++;
            } else {
                result.geometricTracksFound++;
            }

            if (JTransientEngine.DEBUG) {
                SourceExtractor.DetectedObject trackStart = proposal.track.points.get(0);
                double distToMaster = masterGridForDebug.getNearestDistance(trackStart.x, trackStart.y, 30.0);
                if (distToMaster > 0) {
                    // reserved for future diagnostics
                }
            }
        }

        return result;
    }

    private static int compareTrackProposals(TrackProposal left, TrackProposal right) {
        int byLength = Integer.compare(right.track.points.size(), left.track.points.size());
        if (byLength != 0) {
            return byLength;
        }

        int byMode = Boolean.compare(right.timeBased, left.timeBased);
        if (byMode != 0) {
            return byMode;
        }

        int byQuality = Double.compare(right.qualityScore, left.qualityScore);
        if (byQuality != 0) {
            return byQuality;
        }

        return Double.compare(left.averageLinkScore, right.averageLinkScore);
    }

    private static boolean conflictsWithUsedPoints(Set<SourceExtractor.DetectedObject> usedPoints, Track track) {
        for (SourceExtractor.DetectedObject point : track.points) {
            if (usedPoints.contains(point)) {
                return true;
            }
        }
        return false;
    }

    private static boolean passesStrictExposureKinematics(SourceExtractor.DetectedObject from,
                                                          SourceExtractor.DetectedObject to,
                                                          double distance,
                                                          DetectionConfig config) {
        if (!config.strictExposureKinematics || from.timestamp == -1 || to.timestamp == -1 || from.exposureDuration <= 0) {
            return true;
        }

        double dt = to.timestamp - from.timestamp;
        if (dt <= 0) {
            return false;
        }

        return distance <= computeStrictMaxAllowedJump(from, dt, config);
    }

    private static double computeStrictMaxAllowedJump(SourceExtractor.DetectedObject anchor,
                                                      double deltaTime,
                                                      DetectionConfig config) {
        double maxMovementInExposure = Math.sqrt(anchor.pixelArea) + config.maxStarJitter;
        double maxVelocity = maxMovementInExposure / anchor.exposureDuration;
        return (maxVelocity * deltaTime) * 1.5 + config.maxStarJitter;
    }

    private static double angularDifference(double a1, double a2) {
        double diff = Math.abs(a1 - a2);
        if (diff > Math.PI) {
            diff = (2.0 * Math.PI) - diff;
        }
        return diff;
    }

    private static double getAllowedLineError(boolean timeBased, DetectionConfig config) {
        return config.predictionTolerance;
    }

    private static double getAllowedAngleTolerance(boolean timeBased,
                                                   double angleToleranceRad,
                                                   double jumpDistance,
                                                   DetectionConfig config) {
        double baseTolerance = Math.max(angleToleranceRad,
                Math.atan2(config.maxStarJitter + 1.0, Math.max(jumpDistance, 1.0)));
        return baseTolerance;
    }

    private static double getAllowedSpeedDifference(boolean timeBased,
                                                    double currentSpeed,
                                                    double deltaFromAnchor,
                                                    DetectionConfig config) {
        double normalizedDelta = Math.max(deltaFromAnchor, 1.0);
        if (!timeBased) {
            return Math.max(config.maxStarJitter,
                    (currentSpeed * 0.35) + (config.predictionTolerance / normalizedDelta));
        }

        double relaxedVelocityTolerance = Math.max(
                config.timeBasedVelocityTolerance * 3.0,
                MIN_TIME_BASED_VELOCITY_TOLERANCE);
        return (currentSpeed * relaxedVelocityTolerance)
                + ((config.predictionTolerance + (config.maxStarJitter * 2.0)) / normalizedDelta);
    }

    private static boolean isLinkProfileConsistent(SourceExtractor.DetectedObject obj1,
                                                   SourceExtractor.DetectedObject obj2,
                                                   DetectionConfig config,
                                                   boolean timeBased) {
        double ratioMultiplier = timeBased ? TIME_BASED_PROFILE_RATIO_MULTIPLIER : 1.0;
        return isProfileConsistent(obj1, obj2, config, ratioMultiplier);
    }

    private static boolean isProfileConsistent(SourceExtractor.DetectedObject obj1,
                                               SourceExtractor.DetectedObject obj2,
                                               DetectionConfig config,
                                               double ratioMultiplier) {
        if (config.maxFwhmRatio > 0.0) {
            double fwhm1 = Math.max(obj1.fwhm, 0.1);
            double fwhm2 = Math.max(obj2.fwhm, 0.1);
            double fwhmRatio = Math.max(fwhm1, fwhm2) / Math.min(fwhm1, fwhm2);
            if (fwhmRatio > (config.maxFwhmRatio * ratioMultiplier)) return false;
        }

        if (config.maxSurfaceBrightnessRatio > 0.0) {
            double sb1 = obj1.totalFlux / Math.max(obj1.pixelArea, 1.0);
            double sb2 = obj2.totalFlux / Math.max(obj2.pixelArea, 1.0);
            double sbRatio = Math.max(sb1, sb2) / Math.min(sb1, sb2);
            if (sbRatio > (config.maxSurfaceBrightnessRatio * ratioMultiplier)) return false;
        }
        return true;
    }


    private static boolean isProfileConsistent(SourceExtractor.DetectedObject obj1, SourceExtractor.DetectedObject obj2, DetectionConfig config) {
        return isProfileConsistent(obj1, obj2, config, 1.0);
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
