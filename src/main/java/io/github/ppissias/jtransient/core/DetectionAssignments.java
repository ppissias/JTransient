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
import io.github.ppissias.jtransient.model.DetectedObject;
import io.github.ppissias.jtransient.model.Track;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/**
 * The detections already accepted into a track or an anomaly during one tracking run. Each stage claims what it
 * accepts and skips what an earlier stage claimed, so the accepted classification uses a detection at most once,
 * while the review exports may still show it in several places. Detections compare by identity.
 */
final class DetectionAssignments {
    private final Set<DetectedObject> claimed = new HashSet<>();

    /** Whether a stage has already accepted this detection. */
    boolean isClaimed(DetectedObject detection) {
        return claimed.contains(detection);
    }

    /** Records an accepted detection. */
    void claim(DetectedObject detection) {
        claimed.add(detection);
    }

    /** Records the accepted detections of a track. */
    void claimAll(Collection<DetectedObject> detections) {
        claimed.addAll(detections);
    }

    /** Whether any point of the candidate track has already been claimed by a higher-ranked track. */
    boolean conflictsWith(Track track) {
        for (DetectedObject point : track.points) {
            CancellationScope.check();
            if (claimed.contains(point)) {
                return true;
            }
        }
        return false;
    }

    /** The live set, for {@link FaintStreakFragmentGrouper}, which claims the pieces it assembles. */
    Set<DetectedObject> claimed() {
        return claimed;
    }
}
