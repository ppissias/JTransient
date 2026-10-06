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
 * Outcome of the photometric readiness checks that decide whether light curves can be trusted.
 *
 * <p>Check A looks at quantisation and zero-floor clipping, check B at star shape against
 * brightness in every frame, and check D at whether every star responds equally to a change in
 * transparency. A pure power-law stretch from zero passes all of them, so a Ready verdict means
 * no non-linearity was detected, not that linearity is proven.</p>
 */
public final class PhotometricReadiness {

    /** Session-level verdict. */
    public enum Verdict {
        /** Every check passed; photometry runs on stars fainter than the linear limit. */
        READY,
        /** No check failed, but one is marginal; only larger amplitudes are reported. */
        LIMITED,
        /** A check failed; no variability scoring is done. */
        NOT_READY,
        /** Photometry did not run (disabled or not enough input). */
        NOT_RUN
    }

    /** Status of a single check. */
    public enum CheckStatus {
        PASS,
        LIMITED,
        FAIL,
        INCONCLUSIVE,
        NOT_RUN
    }

    public Verdict verdict = Verdict.NOT_RUN;

    /** Check A: quantisation and zero-floor clipping. */
    public CheckStatus quantisationCheck = CheckStatus.NOT_RUN;
    /** Number of distinct pixel levels found in the sampled frame. */
    public int distinctPixelLevels;
    /** Median, over frames, of the share of sky-annulus pixels at the zero floor. */
    public double medianFloorClippedFraction = Double.NaN;

    /** Check B: concentration index against magnitude in every frame. */
    public CheckStatus shapeLinearityCheck = CheckStatus.NOT_RUN;
    /** Median over frames of the linear limit, in instrumental magnitudes (stars brighter than this are non-linear). */
    public double medianLinearLimitMag = Double.NaN;
    /** Median over frames of the linear magnitude range. */
    public double medianLinearRangeMag = Double.NaN;
    /** Frames that failed check B. */
    public int framesFailingShapeLinearity;

    /** Check D: per-frame residual slope against magnitude. */
    public CheckStatus responseCheck = CheckStatus.NOT_RUN;
    /** 5th to 95th percentile spread of the frame zero points, in mag. */
    public double zeroPointRangeMag = Double.NaN;
    /** Correlation between per-frame slopes and zero points. */
    public double slopeZeroPointCorrelation = Double.NaN;
    /** Correlation between per-frame slopes and sky level. */
    public double slopeSkyCorrelation = Double.NaN;
    /** Frames that failed check D. */
    public int framesFailingResponse;

    /** Plain-language explanations, one per notable finding. */
    public final List<String> messages = new ArrayList<>();

    /** Returns true when variability scoring may run. */
    public boolean allowsScoring() {
        return verdict == Verdict.READY || verdict == Verdict.LIMITED;
    }
}
