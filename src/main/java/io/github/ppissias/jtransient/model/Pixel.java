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
 * Represents one pixel belonging to an extracted footprint.
 */
public class Pixel {
    /** Pixel X coordinate. */
    public int x, y, value;

    /**
     * Creates a pixel coordinate and value tuple.
     */
    public Pixel(int x, int y, int value) {
        this.x = x;
        this.y = y;
        this.value = value;
    }
}
