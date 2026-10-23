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

import java.util.List;

/**
 * Describes one extracted source or streak candidate.
 */
public class DetectedObject {
    /** Intensity-weighted centroid X coordinate. */
    public double x, y;
    /** Background-subtracted integrated flux. */
    public double totalFlux;
    /** Number of pixels used when the object was instantiated. */
    public int pixelCount;
    /** Peak signal-to-noise estimate used by anomaly rescue. */
    public double peakSigma;
    /** Integrated signal-to-noise estimate used to rescue broader high-energy anomalies. */
    public double integratedSigma;

    /** Exact blob footprint used for mask building and diagnostics. */
    public List<Pixel> rawPixels = null;

    /** Number of connected raw pixels used by morphology filters. */
    public double pixelArea;

    /** Approximate full width at half maximum derived from image moments. */
    public double fwhm;

    /** Intensity-weighted principal-axis RMS ratio from second moments. */
    public double elongation;
    /** Intensity-weighted principal-axis orientation in radians. */
    public double angle;
    /** Longer extent of the raw-pixel footprint in the orientation frame, including one pixel of width. */
    public double majorExtent;
    /** Shorter extent of the raw-pixel footprint in the orientation frame. */
    public double minorExtent;
    /** Geometric majorExtent / minorExtent; distinct from moment-based {@link #elongation}. */
    public double axisRatio;
    /** Connected pixel count divided by the area of its oriented bounding rectangle. */
    public double fillFactor;
    /** Whether the footprint is classified as a streak-like object. */
    public boolean isStreak;
    /** Whether the footprint should be treated as noise. */
    public boolean isNoise;

    /** Source frame index assigned by the engine after extraction. */
    public int sourceFrameIndex;
    /** Source frame label assigned by the engine after extraction. */
    public String sourceFilename;
    /** Real-world capture time in milliseconds, or {@code -1} if unknown. */
    public long timestamp;
    /** Exposure duration in milliseconds, or {@code -1} if unknown. */
    public long exposureDuration;

    /**
     * Creates a detected-object shell with the supplied centroid and flux values.
     */
    public DetectedObject(double x, double y, double flux, int count) {
        this.x = x;
        this.y = y;
        this.totalFlux = flux;
        this.pixelCount = count;
    }
}
