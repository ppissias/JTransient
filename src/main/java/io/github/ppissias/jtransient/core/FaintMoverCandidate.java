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
 * A point-like mover found by the faint-mover search ({@link FaintMoverSearch}): a constant motion along which the
 * kept frames add up to a source too faint for the single frames. Positions are in the registered frames.
 *
 * <p>Scores are empirical: the PSF-filtered stack along the motion over the noise of such stacks in the candidate's
 * tile. They are not calibrated significances, and a candidate is unverified.</p>
 */
public final class FaintMoverCandidate {
    /** Position at {@link #referenceTimeMillis}, in pixels. */
    public final double x;
    public final double y;
    /** Motion in pixels per minute. */
    public final double vx;
    public final double vy;
    /** The kept frames' mean mid-exposure time, to which the position refers, in milliseconds. */
    public final long referenceTimeMillis;
    /** Score of the stack along the motion. */
    public final double score;
    /**
     * Scores of the first and second time-ordered halves along the motion, scaled by the number of unmasked path
     * centres (star cores, fill, streaks); NaN when too few centres are unmasked. Mask zeros precede PSF filtering,
     * which can spread neighbouring residual signal into a masked centre.
     */
    public final double firstHalfScore;
    public final double secondHalfScore;
    /** Best score of the stationary stack within a few pixels. */
    public final double stationaryScore;
    /**
     * Best score of the stack at one camera position near the path's median camera position (what a hot pixel would
     * give); negative infinity without frame registrations.
     */
    public final double cameraScore;
    /**
     * The PSF-filtered stack along the motion around the position, in score units, {@code cutout[row][column]} with
     * the integer scoring position at the centre. The reported x/y additionally have a subpixel refinement of up to
     * half a pixel per axis.
     */
    public final float[][] cutout;
    /** Bounded review evidence; an explicit reason is supplied if resource limits prevent its collection. */
    public final FaintMoverDiagnostics diagnostics;

    /** Takes ownership of the selected peak's small score cutout and optional replay evidence. */
    FaintMoverCandidate(double x, double y, double vx, double vy, long referenceTimeMillis, double score,
                        double firstHalfScore, double secondHalfScore, double stationaryScore, double cameraScore,
                        float[][] cutout, FaintMoverDiagnostics diagnostics) {
        this.x = x;
        this.y = y;
        this.vx = vx;
        this.vy = vy;
        this.referenceTimeMillis = referenceTimeMillis;
        this.score = score;
        this.firstHalfScore = firstHalfScore;
        this.secondHalfScore = secondHalfScore;
        this.stationaryScore = stationaryScore;
        this.cameraScore = cameraScore;
        this.cutout = cutout;
        this.diagnostics = diagnostics;
    }

    /** Speed in pixels per minute. */
    public double speedPixelsPerMinute() {
        return Math.hypot(vx, vy);
    }

    /** Position at the time, in pixels: {@code {x, y}}. */
    public double[] positionAt(long timeMillis) {
        double minutes = (timeMillis - referenceTimeMillis) / 60000.0;
        return new double[]{x + vx * minutes, y + vy * minutes};
    }
}
