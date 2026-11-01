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
 * What the faint-streak search did in one run: the smoothing it used and how many objects each step kept.
 *
 * <p>The counts describe one population, the streak-shaped objects found on the smoothed frames, in the order the
 * steps see it: {@link #extractedStreaks} = {@link #removedByStarMask} + {@link #duplicatesOfNormalStreaks} +
 * {@link #linkedCandidates}, and {@link #linkedCandidates} = {@link #detectionsInTracks} + {@link #discarded}. The
 * track counts and {@link #anomaliesJoined} describe the final streak tracks and do not add to them.</p>
 */
public class FaintStreakSearchTelemetry {
    /** Sigma of the Gaussian the frames were smoothed with, in pixels. */
    public double smoothingSigma;
    /** Whether the width came from the measured star size; false when no kept frame had a measurement. */
    public boolean widthFromStars;
    /** Kept frames whose star-size measurement set the width. */
    public int widthFrames;
    /** Streak-shaped objects extracted from the smoothed frames. */
    public int extractedStreaks;
    /** Of those, removed by the star mask of the smoothed median stack. */
    public int removedByStarMask;
    /** Of those, dropped because a normal streak detection of the same frame already measures them. */
    public int duplicatesOfNormalStreaks;
    /** Of those, offered to streak linking. */
    public int linkedCandidates;
    /** Of the linked candidates, the ones in a final streak track. */
    public int detectionsInTracks;
    /** Of the linked candidates, the ones in no final streak track, which no other result receives. */
    public int discarded;
    /** Single-frame candidates rejected by the binary-star shape screen. */
    public int rejectedBinaryStarShape;
    /** Final streak tracks with at least one faint-search detection. */
    public int tracksWithFaintDetections;
    /** Of those, tracks made only of faint-search detections. */
    public int faintOnlyTracks;
    /** Rescued anomalies that joined a track with faint-search detections and so left the anomaly list. */
    public int anomaliesJoined;
}
