/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 * author: Petros Pissias <petrospis at gmail.com>
 *
 */
package io.github.ppissias.jtransient.engine;

import java.util.Locale;

/**
 * Sensitivity presets of the calibrated auto-tuner. Each one allows a different false-positive budget, from the
 * fewest noise detections ({@link #LOW}) to as sensitive as possible ({@link #MAXIMUM}).
 */
public enum AutoTuneProfile {
    /** Low sensitivity: the fewest noise detections. */
    LOW,
    /** Medium sensitivity. */
    MEDIUM,
    /** High sensitivity: close to the noise level. */
    HIGH,
    /** As sensitive as possible: many more candidates to review, for small sensors or targeted searches for faint objects. */
    MAXIMUM;

    /** The name for display, for example "Medium". */
    public String displayName() {
        String name = name().toLowerCase(Locale.ROOT);
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    /**
     * Parses a profile name, case-insensitive. The names of earlier versions are still accepted:
     * conservative = low, balanced = medium, aggressive = high.
     */
    public static AutoTuneProfile parse(String value) {
        String name = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        switch (name) {
            case "CONSERVATIVE":
                return LOW;
            case "BALANCED":
                return MEDIUM;
            case "AGGRESSIVE":
                return HIGH;
            default:
                try {
                    return valueOf(name);
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException("Unknown auto-tune profile '" + value
                            + "'. Expected low, medium, high or maximum.");
                }
        }
    }
}
