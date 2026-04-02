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
    public int anomaliesFound, integratedSigmaAnomaliesFound, suspectedStreakTracksFound;

    // --- Phase 3 Star Map Stats ---
    public List<FrameStarMapStat> frameStarMapStats = new ArrayList<>();
    public int totalStationaryStarsPurged = 0;
    public int totalStationaryStreaksPurged = 0;

    /**
     * Per-frame summary of the stationary-star veto pass.
     */
    public static class FrameStarMapStat {
        public int frameIndex;
        public int initialPointSources;
        public int survivingTransients;
        public int purgedStars;
    }
}
