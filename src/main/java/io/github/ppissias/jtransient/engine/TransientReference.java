/*
 * JTransient
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 * author: Petros Pissias <petrospis at gmail.com>
 */
package io.github.ppissias.jtransient.engine;

import io.github.ppissias.jtransient.core.DataArea;
import io.github.ppissias.jtransient.model.DetectedObject;

import java.util.List;

/**
 * The frame selection and star mask kept for one inspection window. Re-detecting a frame uses this reference
 * instead of building another master stack.
 */
public record TransientReference(
        List<ImageFrame> keptFrames,
        DataArea backgroundArea,
        DataArea searchArea,
        DataArea sessionArea,
        List<DetectedObject> masterStars,
        boolean[][] starMask,
        double masterSigmaMultiplier,
        double masterGrowSigmaMultiplier,
        int masterMinDetectionPixels,
        double maxStarJitter
) {
}
