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

public class TrackerTelemetry {
    public long countBaselineJitter, countBaselineJump, countBaselineSize;
    public long countP3NotLine, countP3WrongDirection, countP3Jump, countP3Size;
    public long countTrackTooShort, countTrackErraticRhythm, countTrackDuplicate;
    public int streakTracksFound, pointTracksFound;

    // --- Phase 3 Star Map Stats ---
    public List<FrameStarMapStat> frameStarMapStats = new ArrayList<>();
    public int totalStationaryStarsPurged = 0;

    public static class FrameStarMapStat {
        public int frameIndex;
        public int initialPointSources;
        public int survivingTransients;
        public int purgedStars;
    }
}