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
import io.github.ppissias.jtransient.engine.LiveSnapshot;
import io.github.ppissias.jtransient.engine.PipelineStageObserver;
import io.github.ppissias.jtransient.engine.TransientEngineProgressListener;
import io.github.ppissias.jtransient.execution.CancellationScope;
import io.github.ppissias.jtransient.execution.DiagnosticContext;
import io.github.ppissias.jtransient.model.AnomalyDetection;
import io.github.ppissias.jtransient.model.AnomalyType;
import io.github.ppissias.jtransient.model.DetectedObject;
import io.github.ppissias.jtransient.model.Track;
import io.github.ppissias.jtransient.telemetry.TrackerTelemetry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Links per-frame transient detections into moving-object tracks.
 *
 * <p>This is the tracking stage's entry point; it runs the stages in a fixed order and they share only what is passed
 * between them:</p>
 * <ol>
 *   <li>{@link TransientVeto}: separates streaks from point sources and removes what overlaps the master stars;</li>
 *   <li>{@link StreakLinker}: links fast-moving streaks;</li>
 *   <li>camera-fixed rejection ({@link CameraFixedRejector}), when registrations are given;</li>
 *   <li>{@link TimedPointLinker} when frames carry capture times, and {@link GeometricPointLinker} when they do not or
 *       when geometric linking is enabled;</li>
 *   <li>{@link FaintStreakFragmentGrouper}: faint same-frame pieces of streaks;</li>
 *   <li>{@link AnomalyRescuer}: single-frame anomalies, and anomaly pieces that join a streak through
 *       {@link StreakConsolidator};</li>
 *   <li>{@link TrackingExports}: the per-frame exports.</li>
 * </ol>
 * <p>{@link DetectionAssignments} records which detections a stage has accepted, so each one is assigned at most
 * once, in this order.</p>
 *
 * <p>Input frames must have uniform timestamp availability: either every frame has a capture
 * timestamp in milliseconds, or every frame has no timestamp. Each detection must carry its
 * source frame's timestamp, using {@code -1} when timestamps are unavailable; 0 is a real time.
 * {@link #findMovingObjects} throws {@link IllegalArgumentException} when some detections have a
 * timestamp and others have none. All input exposures are expected to have the same duration.</p>
 */
public class TrackLinker {

    /** A point track needs at least this many points. */
    static final int MIN_TRACK_POINTS = 3;
    private static final double DEFAULT_TRACK_MIN_FRAME_RATIO = new DetectionConfig().trackMinFrameRatio;

    /**
     * Output of the streak-separation and stationary-star veto stages.
     */
    public static class TransientsFilterResult {
        /** Point-like transients that survived vetoing, grouped by frame. */
        public List<List<DetectedObject>> pointTransients;
        /** All streak detections considered mobile enough to keep. */
        public List<DetectedObject> validMovingStreaks;
        /** Confirmed multi-frame or single-frame high-significance streak tracks. */
        public List<Track> streakTracks;
        /** Tracker diagnostics collected so far. */
        public TrackerTelemetry telemetry;
        /** Number of streak tracks accepted during the fast-streak phase. */
        public int streakTracksFound;
        /** Full post-veto transient population, merging point transients with all mobile streak detections per frame. */
        public List<List<DetectedObject>> allTransients;
        /** Pixel-accurate stationary-star veto mask built from the master stack. */
        public boolean[][] masterVetoMask;
    }

    /**
     * Output of the complete tracking stage.
     */
    public static class TrackingResult {
        /** All returned tracks: streak tracks, single streaks and point tracks. */
        public List<Track> tracks;
        /** Rescued single-frame anomalies that did not form tracks. */
        public List<AnomalyDetection> anomalies;
        /** Tracker diagnostics covering all stages. */
        public TrackerTelemetry telemetry;
        /** All non-stationary transient detections carried through tracking, grouped by frame. */
        public List<List<DetectedObject>> allTransients;
        /** Per-frame transient detections that remain uncategorized after tracks and anomalies are exported. */
        public List<List<DetectedObject>> unclassifiedTransients;
        /** Pixel veto mask generated from the master star map. */
        public boolean[][] masterVetoMask;
        /** Detections removed before tracking because they stay at one camera position (hot pixels, dust). */
        public List<CameraFixedRejector.Group> cameraFixedGroups = List.of();

        /**
         * Creates a tracking result bundle.
         */
        public TrackingResult(List<Track> tracks,
                              List<AnomalyDetection> anomalies,
                              TrackerTelemetry telemetry,
                              List<List<DetectedObject>> allTransients,
                              List<List<DetectedObject>> unclassifiedTransients,
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
     * Runs the stationary-star veto and the streak linking, the stages before point linking.
     *
     * @param allFrames extracted objects grouped by frame; timestamps must be available for every frame
     *                  or unavailable ({@code -1}) for every frame
     * @param masterStars stationary objects extracted from the master stack
     * @param config pipeline configuration
     * @param listener optional progress listener
     * @param sensorWidth frame width
     * @param sensorHeight frame height
     * @return filtered transients, streak tracks, telemetry, and the veto mask
     */
    public static TransientsFilterResult filterTransients(
            List<List<DetectedObject>> allFrames,
            List<DetectedObject> masterStars,
            DetectionConfig config,
            TransientEngineProgressListener listener,
            int sensorWidth,
            int sensorHeight) {

        TrackerTelemetry telemetry = new TrackerTelemetry();

        TransientVeto.Result veto = TransientVeto.apply(allFrames, masterStars, config, listener, sensorWidth, sensorHeight,
                telemetry);
        List<List<DetectedObject>> transients = veto.pointTransients;
        List<DetectedObject> validMovingStreaks = veto.validMovingStreaks;
        boolean[][] masterVetoMask = veto.masterVetoMask;

        if (listener != null) {
            listener.onProgressUpdate(25, "Linking fast-moving streaks...");
        }

        List<Track> confirmedStreakTracks = StreakLinker.link(validMovingStreaks, config, telemetry);
        int streakTracksFound = confirmedStreakTracks.size();

        List<List<DetectedObject>> allTransients = TrackingExports.buildAllTransients(
                transients,
                validMovingStreaks,
                allFrames
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
     * @param allFrames extracted objects grouped by frame; timestamps must be available for every frame
     *                  or unavailable ({@code -1}) for every frame
     * @param masterStars stationary objects extracted from the master stack
     * @param config pipeline configuration
     * @param listener optional progress listener
     * @param sensorWidth frame width
     * @param sensorHeight frame height
     * @return final confirmed tracks together with telemetry and exported transients
     */
    public static TrackingResult findMovingObjects(
            List<List<DetectedObject>> allFrames,
            List<DetectedObject> masterStars,
            DetectionConfig config,
            TransientEngineProgressListener listener,
            int sensorWidth,
            int sensorHeight) {
        return findMovingObjects(allFrames, masterStars, config, listener, sensorWidth, sensorHeight, null);
    }

    /**
     * {@link #findMovingObjects(List, List, DetectionConfig, TransientEngineProgressListener, int, int)} that also
     * removes camera-fixed detections (hot pixels, dust) after the star mask when
     * {@code enableCameraFixedRejection} is on ({@link CameraFixedRejector}).
     *
     * @param registrations how registration moved each frame, by sequence index; null or empty to skip the step
     */
    public static TrackingResult findMovingObjects(
            List<List<DetectedObject>> allFrames,
            List<DetectedObject> masterStars,
            DetectionConfig config,
            TransientEngineProgressListener listener,
            int sensorWidth,
            int sensorHeight,
            Map<Integer, FrameRegistration> registrations) {
        return findMovingObjects(allFrames, masterStars, config, listener, sensorWidth, sensorHeight, registrations, null);
    }

    /**
     * {@link #findMovingObjects(List, List, DetectionConfig, TransientEngineProgressListener, int, int, Map)} that
     * may offer one live-view snapshot after a linking step, and only when the observer asks for it.
     */
    public static TrackingResult findMovingObjects(
            List<List<DetectedObject>> allFrames,
            List<DetectedObject> masterStars,
            DetectionConfig config,
            TransientEngineProgressListener listener,
            int sensorWidth,
            int sensorHeight,
            Map<Integer, FrameRegistration> registrations,
            PipelineStageObserver observer) {

        checkUniformTimestamps(allFrames);
        int numFrames = allFrames.size();
        if (DiagnosticContext.enabled()) DiagnosticContext.println("\nDEBUG: [START] findMovingObjects initialized with " + numFrames + " frames.");

        if (listener != null) {
            listener.onProgressUpdate(0, "Initializing tracking engine...");
        }

        if (numFrames < 3) {
            if (DiagnosticContext.enabled()) DiagnosticContext.println("DEBUG: [ABORT] Less than 3 frames provided. Cannot form point tracks.");
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
        offerTrackingPicture(observer, "Tracking · star mask and streaks · provisional", filterResult.streakTracks);

        List<Track> confirmedTracks = new ArrayList<>(filterResult.streakTracks);
        TrackerTelemetry telemetry = filterResult.telemetry;
        List<List<DetectedObject>> transients = filterResult.pointTransients;

        List<CameraFixedRejector.Group> cameraFixedGroups = removeCameraFixed(
                transients, filterResult.allTransients, registrations, config, telemetry);

        int minPointsRequired = requiredTrackPoints(numFrames, config);
        List<Track> pointTracks = new ArrayList<>();
        DetectionAssignments assignments = new DetectionAssignments();

        boolean hasTimestamps = hasTimestamps(allFrames);
        logTimestampProbe(allFrames, hasTimestamps, config);
        if (hasTimestamps) {
            TimedPointLinker.link(transients, minPointsRequired, config, telemetry, assignments, pointTracks, listener);
        }

        boolean runGeometricTrackLinking = config.enableGeometricTrackLinking || !hasTimestamps;
        if (DiagnosticContext.enabled()) {
            DiagnosticContext.printf(
                    "DEBUG: [PHASE 4] runGeometricTrackLinking=%s (enableGeometricTrackLinking=%s, hasTimestamps=%s)%n",
                    runGeometricTrackLinking,
                    config.enableGeometricTrackLinking,
                    hasTimestamps
            );
        }
        if (runGeometricTrackLinking) {
            GeometricPointLinker.link(transients, minPointsRequired, config, telemetry, assignments, pointTracks, listener,
                    hasTimestamps);
        } else if (DiagnosticContext.enabled()) {
            DiagnosticContext.println("DEBUG: [PHASE 4] Skipping geometric linker because timestamps are available and enableGeometricTrackLinking=false.");
        }

        offerTrackingPicture(observer, "Tracking · linked points · provisional", pointTracks);
        if (listener != null) {
            listener.onProgressUpdate(90, "Scanning for high-energy anomalies...");
        }

        // Faint same-frame pieces are absorbed before anomaly rescue so they are not exported as anomalies.
        if (config.enableFaintStreakFragments) {
            long fragmentStartedNanos = System.nanoTime();
            FaintStreakFragmentGrouper.assemble(
                    confirmedTracks,
                    transients,
                    filterResult.validMovingStreaks,
                    assignments.claimed(),
                    config);
            telemetry.faintStreakFragmentTimeMs = (System.nanoTime() - fragmentStartedNanos) / 1_000_000L;
        }

        List<List<DetectedObject>> anomalyCandidates = TrackingExports.buildPhase5AnomalyCandidates(
                filterResult.allTransients,
                confirmedTracks,
                pointTracks
        );
        List<AnomalyDetection> anomalies = AnomalyRescuer.rescue(anomalyCandidates, assignments, config);
        confirmedTracks = AnomalyRescuer.joinStreakPieces(confirmedTracks, anomalies, config);

        recordCounts(confirmedTracks, pointTracks, anomalies, telemetry);
        logRejectionStatistics(telemetry);

        if (listener != null) {
            listener.onProgressUpdate(100, "Finalizing track telemetry...");
        }

        confirmedTracks.addAll(pointTracks);
        logFinalSummary(confirmedTracks);
        List<List<DetectedObject>> allTransients = filterResult.allTransients;
        List<List<DetectedObject>> unclassifiedTransients = TrackingExports.buildUnclassifiedTransients(
                allTransients,
                confirmedTracks,
                anomalies
        );
        TrackingResult result = new TrackingResult(
                confirmedTracks,
                anomalies,
                telemetry,
                allTransients,
                unclassifiedTransients,
                filterResult.masterVetoMask
        );
        result.cameraFixedGroups = cameraFixedGroups;
        return result;
    }

    /** Copies a few positions from the tracks found so far, and only when the observer asks. */
    private static void offerTrackingPicture(PipelineStageObserver observer, String caption, List<Track> tracks) {
        if (observer == null) {
            return;
        }
        boolean wanted;
        try {
            wanted = observer.wantsUpdate();
        } catch (RuntimeException failure) {
            return;
        }
        if (!wanted || tracks == null || tracks.isEmpty() || tracks.get(0).points.isEmpty()) {
            return;
        }
        int shown = Math.min(3, tracks.get(0).points.size());
        double[] xs = new double[shown];
        double[] ys = new double[shown];
        for (int i = 0; i < shown; i++) {
            xs[i] = tracks.get(0).points.get(i).x;
            ys[i] = tracks.get(0).points.get(i).y;
        }
        try {
            observer.liveUpdate(new LiveSnapshot("Tracking", tracks.get(0).points.get(0).sourceFrameIndex, caption, xs, ys));
        } catch (RuntimeException failure) {
            // The picture is optional. Linking continues.
        }
    }

    /**
     * Removes detections fixed on the camera (hot pixels, dust) from the point transients and from the exported
     * transients, so they leave every later stage; skipped when the switch is off or there are no registrations.
     */
    private static List<CameraFixedRejector.Group> removeCameraFixed(List<List<DetectedObject>> transients,
                                                                    List<List<DetectedObject>> allTransients,
                                                                    Map<Integer, FrameRegistration> registrations,
                                                                    DetectionConfig config,
                                                                    TrackerTelemetry telemetry) {
        List<CameraFixedRejector.Group> cameraFixedGroups = List.of();
        if (config.enableCameraFixedRejection && registrations != null && !registrations.isEmpty()) {
            cameraFixedGroups = CameraFixedRejector.reject(transients, registrations);
            if (!cameraFixedGroups.isEmpty()) {
                Set<DetectedObject> removed = Collections.newSetFromMap(new IdentityHashMap<>());
                for (CameraFixedRejector.Group group : cameraFixedGroups) {
                    removed.addAll(group.points);
                }
                for (List<DetectedObject> frame : allTransients) {
                    frame.removeIf(removed::contains);
                }
                telemetry.cameraFixedGroups = cameraFixedGroups.size();
                telemetry.cameraFixedDetections = removed.size();
            }
        }
        return cameraFixedGroups;
    }

    /** Whether the frames carry capture times, judged by the first detection of each non-empty frame. */
    private static boolean hasTimestamps(List<List<DetectedObject>> allFrames) {
        for (List<DetectedObject> frame : allFrames) {
            CancellationScope.check();
            if (!frame.isEmpty() && frame.get(0).timestamp != -1) {
                return true;
            }
        }
        return false;
    }

    private static void logTimestampProbe(List<List<DetectedObject>> allFrames, boolean hasTimestamps, DetectionConfig config) {
        if (!DiagnosticContext.enabled()) {
            return;
        }
        DiagnosticContext.println("DEBUG: [PHASE 3.5] Timestamp probe per frame:");
        for (int i = 0; i < allFrames.size(); i++) {
            CancellationScope.check();
            List<DetectedObject> frame = allFrames.get(i);
            if (frame.isEmpty()) {
                DiagnosticContext.printf("   Frame %d -> empty%n", i);
            } else {
                DetectedObject sample = frame.get(0);
                DiagnosticContext.printf(
                        "   Frame %d -> objects=%d sampleTimestamp=%d sampleExposure=%d%n",
                        i,
                        frame.size(),
                        sample.timestamp,
                        sample.exposureDuration
                );
            }
        }
        DiagnosticContext.printf(
                "DEBUG: [PHASE 3.5] hasTimestamps=%s enableGeometricTrackLinking=%s%n",
                hasTimestamps,
                config.enableGeometricTrackLinking
        );
    }

    /** Records the returned streak tracks, point tracks and anomalies in the tracker telemetry. */
    private static void recordCounts(List<Track> confirmedTracks, List<Track> pointTracks, List<AnomalyDetection> anomalies,
                                     TrackerTelemetry telemetry) {
        int integratedSigmaAnomaliesRescued = 0;
        for (AnomalyDetection anomaly : anomalies) {
            CancellationScope.check();
            if (anomaly.type == AnomalyType.INTEGRATED_SIGMA) {
                integratedSigmaAnomaliesRescued++;
            }
        }

        int consolidatedStreakTracksFound = 0;
        for (Track track : confirmedTracks) {
            CancellationScope.check();
            if (track.isStreakTrack) {
                consolidatedStreakTracksFound++;
            }
        }

        telemetry.streakTracksFound = consolidatedStreakTracksFound;
        telemetry.pointTracksFound = pointTracks.size();
        telemetry.anomaliesFound = anomalies.size();
        telemetry.integratedSigmaAnomaliesFound = integratedSigmaAnomaliesRescued;
    }

    private static void logRejectionStatistics(TrackerTelemetry telemetry) {
        if (!DiagnosticContext.enabled()) {
            return;
        }
        DiagnosticContext.println("\n--------------------------------------------------");
        DiagnosticContext.println(" PHASE 4 TELEMETRY: FILTER REJECTION STATISTICS   ");
        DiagnosticContext.println("--------------------------------------------------");
        DiagnosticContext.println("1. Baseline Generation (p1 -> p2) Rejections:");
        DiagnosticContext.println("   - Stationary / Jitter           : " + telemetry.countBaselineJitter);
        DiagnosticContext.println("   - Non-Positive Time Delta       : " + telemetry.countBaselineNonPositiveDelta);
        DiagnosticContext.println("   - Exceeded Max Jump Velocity    : " + telemetry.countBaselineJump);
        DiagnosticContext.println("   - Morphological Profile Mismatch: " + telemetry.countBaselineSize);

        DiagnosticContext.println("\n2. Point 3+ (p3, p4...) Search Rejections:");
        DiagnosticContext.println("   - Non-Positive Time Delta       : " + telemetry.countP3NonPositiveDelta);
        DiagnosticContext.println("   - Velocity Mismatch             : " + telemetry.countP3VelocityMismatch);
        DiagnosticContext.println("   - Not Collinear (Off-line)      : " + telemetry.countP3NotLine);
        DiagnosticContext.println("   - Wrong Direction / Angle       : " + telemetry.countP3WrongDirection);
        DiagnosticContext.println("   - Exceeded Max Jump Velocity    : " + telemetry.countP3Jump);
        DiagnosticContext.println("   - Morphological Profile Mismatch: " + telemetry.countP3Size);

        DiagnosticContext.println("\n3. Final Track Rejections:");
        DiagnosticContext.println("   - Insufficient track length     : " + telemetry.countTrackTooShort);
        DiagnosticContext.println("   - Erratic kinematic rhythm      : " + telemetry.countTrackErraticRhythm);
        DiagnosticContext.println("   - Duplicate track (Ignored)     : " + telemetry.countTrackDuplicate);
        DiagnosticContext.println("--------------------------------------------------\n");

        DiagnosticContext.println("\n4. Valid Tracks Confirmed:");
        DiagnosticContext.println("   - Returned Confirmed Streaks    : " + telemetry.streakTracksFound);
        DiagnosticContext.println("   - Binary-Star Streak Rejects    : " + telemetry.rejectedBinaryStarStreakShape);
        DiagnosticContext.println("   - Slow Point Tracks (Phase 4)   : " + telemetry.pointTracksFound);
        DiagnosticContext.println("   - TOTAL RETURNED TRACKS         : "
                + (telemetry.streakTracksFound + telemetry.pointTracksFound));
        DiagnosticContext.println("   - Single-Frame Anomalies        : " + telemetry.anomaliesFound);
        DiagnosticContext.println("      -> Integrated-Sigma Rescue   : " + telemetry.integratedSigmaAnomaliesFound);
        DiagnosticContext.println("--------------------------------------------------\n");
    }

    private static void logFinalSummary(List<Track> confirmedTracks) {
        if (!DiagnosticContext.enabled()) {
            return;
        }
        int finalStreakTracks = 0;
        int finalTimeBasedPointTracks = 0;
        int finalGeometricPointTracks = 0;

        for (Track track : confirmedTracks) {
            CancellationScope.check();
            if (track.isStreakTrack) {
                finalStreakTracks++;
            } else if (track.isTimeBasedTrack) {
                finalTimeBasedPointTracks++;
            } else {
                finalGeometricPointTracks++;
            }
        }

        DiagnosticContext.printf(
                "DEBUG: [FINAL TRACK SUMMARY] streak=%d timeBasedPoint=%d geometricPoint=%d%n",
                finalStreakTracks,
                finalTimeBasedPointTracks,
                finalGeometricPointTracks
        );
    }

    /**
     * Throws when some detections carry a capture time and others have none ({@code -1}); see the class description.
     * A timestamp of 0 is a real time.
     */
    static void checkUniformTimestamps(List<List<DetectedObject>> allFrames) {
        Set<Integer> timedFrames = new TreeSet<>();
        Set<Integer> untimedFrames = new TreeSet<>();
        for (int i = 0; i < allFrames.size(); i++) {
            CancellationScope.check();
            for (DetectedObject object : allFrames.get(i)) {
                CancellationScope.check();
                (object.timestamp == -1L ? untimedFrames : timedFrames).add(i);
            }
        }
        if (!timedFrames.isEmpty() && !untimedFrames.isEmpty()) {
            throw new IllegalArgumentException("Either every detection has a capture time or none has. Frames (by "
                    + "position in the list) with detections without one: " + untimedFrames + "; with one: "
                    + timedFrames + ".");
        }
    }

    /**
     * Number of points a point track needs: the frame count divided by {@code trackMinFrameRatio}, capped at
     * {@code absoluteMaxPointsRequired}, and never fewer than three, whatever the profile says. A ratio that is not
     * a positive number counts as the default.
     */
    static int requiredTrackPoints(int frameCount, DetectionConfig config) {
        double ratio = config.trackMinFrameRatio > 0.0 ? config.trackMinFrameRatio : DEFAULT_TRACK_MIN_FRAME_RATIO;
        int required = Math.min((int) Math.ceil(frameCount / ratio), config.absoluteMaxPointsRequired);
        return Math.max(MIN_TRACK_POINTS, required);
    }

}
