/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 * author: Petros Pissias <petrospis at gmail.com>
 *
 */
package io.github.ppissias.jtransient.telemetry;

import io.github.ppissias.jtransient.execution.FrameIdentity;

import java.util.ArrayList;
import java.util.List;

/**
 * Diagnostic counters emitted by the track-linking stages.
 */
public class TrackerTelemetry {
    public long countBaselineJitter, countBaselineJump, countBaselineSize, countBaselineNonPositiveDelta;
    public long countP3NotLine, countP3WrongDirection, countP3Jump, countP3Size, countP3NonPositiveDelta, countP3VelocityMismatch;
    public long countTrackTooShort, countTrackErraticRhythm, countTrackDuplicate;
    public int rejectedBinaryStarStreakShape;
    public int streakTracksFound, pointTracksFound;
    public int anomaliesFound, integratedSigmaAnomaliesFound;

    /** Faint-fragment assembly wall-clock time; included in tracking, -1 when the pass did not run. */
    public long faintStreakFragmentTimeMs = -1L;

    // --- Phase 3 Star Map Stats ---
    public List<FrameStarMapStat> frameStarMapStats = new ArrayList<>();
    public int totalStationaryStarsPurged = 0;
    /** Camera-fixed groups found after the star mask (hot pixels, dust), and the detections they removed. */
    public int cameraFixedGroups = 0;
    public int cameraFixedDetections = 0;
    public int totalStationaryStreaksPurged = 0;

    /**
     * Per-frame summary of the stationary-star veto pass.
     */
    public static class FrameStarMapStat {
        /** Legacy zero-based position in the retained input list, not original sequence identity. */
        public int frameIndex;
        /** Original identity supplied by engine orchestration; null for standalone legacy tracker calls. */
        public FrameIdentity originalFrame;
        public int initialPointSources;
        public int survivingTransients;
        public int purgedStars;

        /** Returns the zero-based retained-list position without confusing it with original sequence identity. */
        public int retainedPosition() {
            return frameIndex;
        }
    }
}
