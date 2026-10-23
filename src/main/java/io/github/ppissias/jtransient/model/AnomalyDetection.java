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

/**
 * One rescued single-frame anomaly that did not become a multi-frame track.
 */
public class AnomalyDetection {
    /** The underlying extracted object. */
    public final DetectedObject object;
    /** The rescue path that accepted this anomaly. */
    public final AnomalyType type;

    /** Pairs a rescued detection with the rescue path that accepted it. */
    public AnomalyDetection(DetectedObject object, AnomalyType type) {
        this.object = object;
        this.type = type;
    }
}
