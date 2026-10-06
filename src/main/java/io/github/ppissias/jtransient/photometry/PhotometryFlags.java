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
 * Bit flags attached to every star measurement in every frame.
 * A measurement with any flag set is kept for display but excluded from the ensemble and scoring.
 */
public final class PhotometryFlags {
    /** Peak pixel above the configured fraction of the session saturation level. */
    public static final int SATURATED = 1;
    /** Brighter than the frame's linear limit from readiness check B. */
    public static final int NONLINEAR = 1 << 1;
    /** Aperture or sky annulus touches the image edge or registration void padding. */
    public static final int EDGE_OR_VOID = 1 << 2;
    /** A non-stationary detection, track, or slow-mover footprint overlaps the aperture or annulus. */
    public static final int CROSSING = 1 << 3;
    /** Isolated single-frame outlier removed before scoring. */
    public static final int OUTLIER = 1 << 4;
    /** The whole frame was excluded from photometry (registration, linearity, or response check). */
    public static final int FRAME_EXCLUDED = 1 << 5;
    /** Background-subtracted flux was not positive, so no magnitude exists. */
    public static final int BAD_FLUX = 1 << 6;

    private PhotometryFlags() {
    }

    /**
     * Returns the readable names of the flags set in {@code flags}.
     */
    public static List<String> describe(int flags) {
        List<String> names = new ArrayList<>();
        if ((flags & SATURATED) != 0) names.add("SATURATED");
        if ((flags & NONLINEAR) != 0) names.add("NONLINEAR");
        if ((flags & EDGE_OR_VOID) != 0) names.add("EDGE_OR_VOID");
        if ((flags & CROSSING) != 0) names.add("CROSSING");
        if ((flags & OUTLIER) != 0) names.add("OUTLIER");
        if ((flags & FRAME_EXCLUDED) != 0) names.add("FRAME_EXCLUDED");
        if ((flags & BAD_FLUX) != 0) names.add("BAD_FLUX");
        return names;
    }
}
