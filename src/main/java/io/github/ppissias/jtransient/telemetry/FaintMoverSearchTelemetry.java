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

/**
 * What the faint-mover search did in one run: the search domain, how much of it was searched, and how many peaks each
 * check kept.
 *
 * <p>The counts follow one population in the order the checks see it: {@link #refinedPeaks} = {@link #belowScore} +
 * {@link #rejectedSplitHalf} + {@link #rejectedStationary} + {@link #followingCamera} + {@link #duplicates} +
 * {@link #matchedTracks} + {@link #nearStronger} + {@link #candidates}.</p>
 */
public class FaintMoverSearchTelemetry {
    /** Original sequence indices of the selected search session, in kept order; empty when it was not selected. */
    public java.util.List<Integer> searchedFrameIndices = java.util.List.of();
    /** Why the search did not run, or null when it ran. */
    public String skippedReason;
    /** Kept frames searched: those of the longest session, without a gap of over two hours. */
    public int frames;
    /** Kept frames left out because a gap of over two hours separates them from the searched session. */
    public int framesOutsideSession;
    /** Time from the first to the last kept frame's mid-exposure, in minutes. */
    public double sessionMinutes;
    /** Star size the stacks were filtered with (Gaussian FWHM), in pixels. */
    public double fwhmPixels;
    /** Whether the star size came from the frames' measurements; false when a fixed size was used. */
    public boolean fwhmFromStars;
    /** Slowest and fastest motion searched, in pixels per minute. */
    public double minSpeedPixelsPerMinute;
    public double maxSpeedPixelsPerMinute;
    /** Spacing of the coarse motion grid and of its refinement, in pixels per minute. */
    public double coarseStepPixelsPerMinute;
    public double fineStepPixelsPerMinute;
    /** Motions in the coarse grid. */
    public int coarseMotions;
    /** Tiles the search area was divided into, and those searched before the time limit. */
    public int tiles;
    public int tilesSearched;
    /** Whether the time limit stopped the search before every tile was searched. */
    public boolean truncated;
    /** Pixels whose motions were searched, outside masked star cores, fill and trails. */
    public long searchedPixels;
    /** Coarse local maxima that were refined. */
    public int refinedPeaks;
    /** Refined peaks below the minimum score. */
    public int belowScore;
    /** Peaks one half of the session does not support. */
    public int rejectedSplitHalf;
    /** Peaks that do not score clearly above the stationary stack. */
    public int rejectedStationary;
    /** Peaks that repeat a stronger peak at a nearby position and motion (neighbouring tiles). */
    public int duplicates;
    /** Peaks that follow a moving track of the run. */
    public int matchedTracks;
    /** Peaks whose path meets a moving track or a much stronger peak in some kept frame (their side lobes). */
    public int nearStronger;
    /** Peaks that do not score clearly above the stack at one camera position (hot pixels, defects). */
    public int followingCamera;
    /** Candidates reported. */
    public int candidates;
    /** Wall-clock time of the search, in milliseconds. */
    public long elapsedMs;
}
