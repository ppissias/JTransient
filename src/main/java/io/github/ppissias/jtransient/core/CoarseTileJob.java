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
 * Tile inputs and output arrays for one {@link CoarseSearchBackend#search} invocation.
 *
 * @param planes coarse residual planes {@code [coarseFrame][pixel]}, read-only
 * @param stride row stride of each plane, in coarse pixels
 * @param margin margin before the inner area, in coarse pixels
 * @param width inner coarse tile width, in coarse pixels
 * @param height inner coarse tile height, in coarse pixels
 * @param scale stack-to-score multiplier {@code 1 / (coarseFrames * coarseNoise)}
 * @param best pre-initialized ({@code -Infinity}) output array of length {@code width * height}
 * @param bestMotion pre-initialized ({@code 0}) winning motion index array of length {@code width * height}
 */
public record CoarseTileJob(
        float[][] planes,
        int stride,
        int margin,
        int width,
        int height,
        float scale,
        float[] best,
        int[] bestMotion) {
}
