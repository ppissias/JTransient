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
 * Pre-plan workload geometry estimated from input metadata so compute-backend selection can verify GPU memory
 * and eligibility before {@link FaintMoverSearch#plan} admits a GPU motion limit.
 *
 * @param eligible whether basic frame, timing, speed and CPU heap checks allow the faint-mover search to run
 * @param frames searched frames in the selected session
 * @param imageWidth frame width in pixels
 * @param imageHeight frame height in pixels
 * @param coarseFrames number of coarse residual planes per tile
 * @param coarseBin spatial binning factor (1 or 2)
 * @param margin search margin in full-resolution pixels
 * @param coarsePlanePixels number of floats in one coarse plane for a full {@code TILE x TILE} tile
 * @param estimatedMotions estimated coarse trial motions ({@code Double.POSITIVE_INFINITY} if skipped before counting)
 * @param actualMotionsAtGpuCap exact motion count if within GPU cap, else {@code -1} when over cap or not computed
 */
public record CoarseWorkloadEstimate(
        boolean eligible,
        int frames,
        int imageWidth,
        int imageHeight,
        int coarseFrames,
        int coarseBin,
        int margin,
        int coarsePlanePixels,
        double estimatedMotions,
        int actualMotionsAtGpuCap) {
}
