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

/**
 * Helpers for converting between the signed-short storage domain used by image arrays
 * and the shifted non-negative intensity domain used by extraction and diagnostics.
 */
public final class PixelEncoding {
    private static final int SHIFT = 32768;

    private PixelEncoding() {
    }

    /**
     * Converts one stored pixel into the shifted non-negative intensity domain.
     */
    public static int toShiftedPositiveInt(short storedPixel) {
        return storedPixel + SHIFT;
    }

    /**
     * Converts one shifted non-negative intensity back into the stored signed-short domain.
     */
    public static short fromShiftedPositiveInt(int shiftedPixel) {
        return (short) (shiftedPixel - SHIFT);
    }
}
