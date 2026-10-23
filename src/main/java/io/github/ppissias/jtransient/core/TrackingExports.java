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

import io.github.ppissias.jtransient.execution.CancellationScope;
import io.github.ppissias.jtransient.model.AnomalyDetection;
import io.github.ppissias.jtransient.model.DetectedObject;
import io.github.ppissias.jtransient.model.Track;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The per-frame populations the tracking stage exports: all post-veto transients, the anomaly-rescue candidates and
 * what remains unclassified after tracks and anomalies take their detections.
 */
final class TrackingExports {

    private TrackingExports() {
    }

    /**
     * Removes any detection already consumed by an accepted track before Phase 5 anomaly rescue.
     */
    static List<List<DetectedObject>> buildPhase5AnomalyCandidates(
            List<List<DetectedObject>> allTransients,
            List<Track> streakTracks,
            List<Track> pointTracks) {
        Set<DetectedObject> trackedObjects = new HashSet<>();
        for (Track track : streakTracks) {
            CancellationScope.check();
            trackedObjects.addAll(track.points);
        }
        for (Track track : pointTracks) {
            CancellationScope.check();
            trackedObjects.addAll(track.points);
        }

        List<List<DetectedObject>> anomalyCandidates = new ArrayList<>(allTransients.size());
        for (List<DetectedObject> frameObjects : allTransients) {
            CancellationScope.check();
            List<DetectedObject> filteredFrame = new ArrayList<>();
            for (DetectedObject object : frameObjects) {
                CancellationScope.check();
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
    static List<List<DetectedObject>> buildAllTransients(
            List<List<DetectedObject>> pointTransients,
            List<DetectedObject> validMovingStreaks,
            List<List<DetectedObject>> retainedFrames) {
        int numFrames = retainedFrames.size();
        Map<Integer, Integer> retainedPositions = new HashMap<>();
        List<List<DetectedObject>> allTransients = new ArrayList<>(numFrames);
        for (int i = 0; i < numFrames; i++) {
            CancellationScope.check();
            for (DetectedObject object : retainedFrames.get(i)) {
                CancellationScope.check();
                retainedPositions.put(object.sourceFrameIndex, i);
            }
            List<DetectedObject> frameObjects = new ArrayList<>();
            if (pointTransients != null && i < pointTransients.size() && pointTransients.get(i) != null) {
                frameObjects.addAll(pointTransients.get(i));
            }
            allTransients.add(frameObjects);
        }

        if (validMovingStreaks != null) {
            for (DetectedObject streak : validMovingStreaks) {
                CancellationScope.check();
                Integer position = streak == null ? null : retainedPositions.get(streak.sourceFrameIndex);
                if (position == null) {
                    continue;
                }
                allTransients.get(position).add(streak);
            }
        }
        return allTransients;
    }

    /**
     * Removes all detections that were consumed by accepted tracks or surviving standalone anomalies.
     */
    static List<List<DetectedObject>> buildUnclassifiedTransients(
            List<List<DetectedObject>> allTransients,
            List<Track> tracks,
            List<AnomalyDetection> anomalies) {
        Set<DetectedObject> classifiedObjects = new HashSet<>();
        if (tracks != null) {
            for (Track track : tracks) {
                CancellationScope.check();
                if (track != null && track.points != null) {
                    classifiedObjects.addAll(track.points);
                }
            }
        }
        if (anomalies != null) {
            for (AnomalyDetection anomaly : anomalies) {
                CancellationScope.check();
                if (anomaly != null && anomaly.object != null) {
                    classifiedObjects.add(anomaly.object);
                }
            }
        }

        List<List<DetectedObject>> unclassifiedTransients = new ArrayList<>(allTransients.size());
        for (List<DetectedObject> frameObjects : allTransients) {
            CancellationScope.check();
            List<DetectedObject> frameUnclassified = new ArrayList<>();
            if (frameObjects != null) {
                for (DetectedObject object : frameObjects) {
                    if (!classifiedObjects.contains(object)) {
                        frameUnclassified.add(object);
                    }
                }
            }
            unclassifiedTransients.add(frameUnclassified);
        }
        return unclassifiedTransients;
    }
}
