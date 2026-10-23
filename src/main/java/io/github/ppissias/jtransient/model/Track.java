/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 * author: Petros Pissias <petrospis at gmail.com>
 *
 */
package io.github.ppissias.jtransient.model;

import java.util.ArrayList;
import java.util.List;

/**
 * One returned track-like detection.
 *
 * <p>A track can represent a confirmed point track or a confirmed streak track. Standalone
 * anomalies are not represented here; they are exported separately as {@link AnomalyDetection}.</p>
 */
public class Track {
    /** Chronological points that make up the track. */
    public List<DetectedObject> points = new ArrayList<>();
    /** Whether the track is composed of streak detections. */
    public boolean isStreakTrack = false;
    /** Whether the track was accepted by the time-aware linker. */
    public boolean isTimeBasedTrack = false;

    /**
     * Appends one detection to the track.
     *
     * @param obj detection to append
     */
    public void addPoint(DetectedObject obj) {
        points.add(obj);
    }
}
