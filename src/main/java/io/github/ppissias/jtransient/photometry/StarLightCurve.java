/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 * author: Petros Pissias <petrospis at gmail.com>
 *
 */
package io.github.ppissias.jtransient.photometry;

import java.util.ArrayList;
import java.util.List;

/**
 * Differential light curve and variability scores of one measured master star.
 *
 * <p>The per-frame arrays are aligned with {@link VariableStarAnalysis#frames}.</p>
 */
public final class StarLightCurve {

    /** Index of the star in {@link VariableStarAnalysis#stars}. */
    public final int id;
    /** Master-stack position. */
    public final double x;
    public final double y;
    /** Total flux of the star in the master stack (extraction units). */
    public final double masterFlux;

    /** Mean instrumental magnitude M(i) from the ensemble solve. */
    public double meanMag = Double.NaN;

    /** Per-frame residual from the ensemble model (positive = fainter), NaN when not measured. */
    public double[] deltaMag;
    /** Per-frame sky-noise magnitude error (a lower bound; scoring uses the empirical noise model). */
    public double[] magError;
    /** Per-frame {@link PhotometryFlags} bit set. */
    public int[] flags;

    /** Number of unflagged measurements used for scoring. */
    public int usableFrames;
    /** Time between the first and last usable measurement, in minutes (NaN without timestamps). */
    public double timeSpanMinutes = Double.NaN;

    /** Standard deviation of the usable residuals, in mag. */
    public double scatter = Double.NaN;
    /** Expected scatter of a constant star of the same magnitude in this session. */
    public double expectedScatter = Double.NaN;
    /** scatter / expectedScatter. */
    public double excessScatter = Double.NaN;
    /** Stetson J index (consecutive measurements deviating in the same direction). */
    public double stetsonJ = Double.NaN;
    /** Robust z-score of the scatter against stars of similar magnitude. */
    public double scatterZ = Double.NaN;
    /** Robust z-score of Stetson J against stars of similar magnitude. */
    public double stetsonJZ = Double.NaN;
    /** Robust amplitude: 95th minus 5th percentile of the usable residuals, in mag. */
    public double amplitude = Double.NaN;

    /** Gate diagnostics, filled only for candidates. */
    public int longestDeviatingRun;
    public double splitHalfCorrelation = Double.NaN;
    public double apertureAmplitudeDifference = Double.NaN;
    public double maxSystematicsCorrelation = Double.NaN;
    public String maxSystematicsSource;
    /**
     * Largest change, in mag, that the most correlated systematic could explain for this star:
     * the response-factor multiple of what constant stars of similar brightness show (NaN if not evaluated).
     */
    public double systematicsLimitMag = Double.NaN;
    /** Largest correlation between frame-to-frame changes of the light curve and of a systematic. */
    public double maxFrameToFrameSystematicsCorrelation = Double.NaN;
    public double localCorrelation = Double.NaN;
    /** Median share of this star's pattern carried by the nearby constant stars (regression slope of theirs on it). */
    public double localSharedFraction = Double.NaN;
    public int localComparisonStars;

    /** Classification of this star. */
    public VariabilityTier tier = VariabilityTier.NOT_SCORED;
    /** Why the star was not scored, when {@link #tier} is NOT_SCORED. */
    public String notScoredReason;
    /** Names of the gates a candidate failed. */
    public final List<String> failedGates = new ArrayList<>();

    StarLightCurve(int id, double x, double y, double masterFlux, int frameCount) {
        this.id = id;
        this.x = x;
        this.y = y;
        this.masterFlux = masterFlux;
        this.deltaMag = new double[frameCount];
        this.magError = new double[frameCount];
        this.flags = new int[frameCount];
        java.util.Arrays.fill(deltaMag, Double.NaN);
        java.util.Arrays.fill(magError, Double.NaN);
    }
}
