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
import io.github.ppissias.jtransient.model.Pixel;
import io.github.ppissias.jtransient.telemetry.TrackerTelemetry;

import java.util.ArrayList;
import java.util.List;

/** The stationary-star veto: point transients and streaks that do not overlap the master stars' mask. */
final class TransientVeto {

    /** What survives the stationary-star veto. */
    static final class Result {
        /** Point transients that survived the veto, by frame. */
        final List<List<DetectedObject>> pointTransients;
        /** Streak detections that survived the veto. */
        final List<DetectedObject> validMovingStreaks;
        /** The veto mask built from the master stars. */
        final boolean[][] masterVetoMask;

        private Result(List<List<DetectedObject>> pointTransients, List<DetectedObject> validMovingStreaks,
                       boolean[][] masterVetoMask) {
            this.pointTransients = pointTransients;
            this.validMovingStreaks = validMovingStreaks;
            this.masterVetoMask = masterVetoMask;
        }
    }

    private TransientVeto() {
    }

    /**
     * Separates streaks from point sources, builds the veto mask from the master stars and removes the detections that
     * overlap it more than {@code maxMaskOverlapFraction}.
     */
    static Result apply(List<List<DetectedObject>> allFrames, List<DetectedObject> masterStars, DetectionConfig config,
                        TransientEngineProgressListener listener, int sensorWidth, int sensorHeight,
                        TrackerTelemetry telemetry) {
        int numFrames = allFrames.size();

        // =================================================================
        // PHASE 1: Separate Streaks
        // =================================================================
        List<DetectedObject> allStreaks = new ArrayList<>();
        List<List<DetectedObject>> pointSourcesOnly = new ArrayList<>();

        for (int i = 0; i < allFrames.size(); i++) {
            CancellationScope.check();
            pointSourcesOnly.add(new ArrayList<>());
            for (DetectedObject obj : allFrames.get(i)) {
                CancellationScope.check();
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
        if (DiagnosticContext.enabled()) DiagnosticContext.println("DEBUG: [PHASE 2] Building Binary Footprint Mask from Master Map...");

        boolean[][] masterVetoMask = MasterVetoMask.build(masterStars, sensorWidth, sensorHeight, config.maxStarJitter);

        // --- PHASE 2A: Veto Point Sources ---
        List<List<DetectedObject>> transients = new ArrayList<>();
        int totalTransientsFound = 0;

        for (int i = 0; i < numFrames; i++) {
            CancellationScope.check();
            if (listener != null) {
                int progress = 15 + (int) (((double) i / numFrames) * 10.0);
                listener.onProgressUpdate(progress, "Vetoing stationary sources: Frame " + (i + 1) + " of " + numFrames);
            }

            List<DetectedObject> currentFrame = pointSourcesOnly.get(i);
            List<DetectedObject> frameTransients = new ArrayList<>();
            int purgedCount = 0;

            for (DetectedObject candidateObj : currentFrame) {
                CancellationScope.check();
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
        List<DetectedObject> validMovingStreaks = new ArrayList<>();
        int purgedStreakCount = 0;
        for (DetectedObject streak : allStreaks) {
            CancellationScope.check();
            double overlapFraction = computeMaskOverlapFraction(streak, masterVetoMask);
            if (overlapFraction > config.maxMaskOverlapFraction) {
                purgedStreakCount++;
            } else {
                validMovingStreaks.add(streak);
            }
        }
        telemetry.totalStationaryStreaksPurged = purgedStreakCount;

        if (DiagnosticContext.enabled()) {
            DiagnosticContext.println("DEBUG: [PHASE 2] Completed. Total pure point transients: " + totalTransientsFound);
            DiagnosticContext.println("DEBUG: [PHASE 2] Purged " + purgedStreakCount + " stationary streaks. " + validMovingStreaks.size() + " remain.");
        }

        return new Result(transients, validMovingStreaks, masterVetoMask);
    }

    /**
     * Measures what fraction of an object's footprint overlaps a precomputed boolean mask.
     */
    private static double computeMaskOverlapFraction(DetectedObject obj, boolean[][] mask) {
        if (obj.rawPixels == null || obj.rawPixels.isEmpty()) {
            return 0.0;
        }

        int overlapCount = 0;
        int sensorHeight = mask.length;
        int sensorWidth = sensorHeight > 0 ? mask[0].length : 0;
        for (Pixel p : obj.rawPixels) {
            CancellationScope.check();
            if (p.x >= 0 && p.x < sensorWidth && p.y >= 0 && p.y < sensorHeight && mask[p.y][p.x]) {
                overlapCount++;
            }
        }
        return (double) overlapCount / obj.rawPixels.size();
    }
}
