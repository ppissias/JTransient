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

import io.github.ppissias.jtransient.config.PhotometryGeometry;

import java.util.Arrays;

/**
 * Aperture photometry of a moving object for its light curve (MP-1): the object's light in a circle of radius
 * {@value #APERTURE_FWHM} FWHM at its position on one aligned frame, the sky from a sigma-clipped annulus from
 * {@value #ANNULUS_INNER_FWHM} to {@value #ANNULUS_OUTER_FWHM} FWHM, through the same aperture step as the
 * comparison-star photometry. The flux is per second of exposure, so frames of different lengths compare; faint and
 * negative fluxes are kept, never dropped or set to zero, so a faint curve is not biased upwards. The FWHM must be the
 * stars' measured (Gaussian) FWHM and the same for every frame of a curve.
 * <p>
 * {@link #ensembleOffsets} corrects each frame for transparency and extinction with comparison stars measured the same
 * way. A curve without that correction is relative and uncorrected; neither is a calibrated magnitude.
 */
public final class MovingObjectPhotometry {
    /** Aperture radius in FWHM: a diameter of 3 FWHM, the usual choice for faint point sources. */
    public static final double APERTURE_FWHM = 1.5;
    /** Sky annulus radii in FWHM, leaving a gap outside the aperture. */
    public static final double ANNULUS_INNER_FWHM = 3.0;
    public static final double ANNULUS_OUTER_FWHM = 5.0;
    /** Fewest comparison stars with a usable flux on a frame for its correction. */
    public static final int MIN_COMPARISON_STARS = 3;

    /**
     * One frame's measurement: the sky-subtracted flux and its error from the sky noise, per second of exposure (per
     * frame when the exposure is unknown), the sky level per pixel, the aperture radius in pixels, and
     * {@link PhotometryFlags} (edge or void, saturated, flux not positive, trailed), and the concentration index (light
     * within {@code CONCENTRATION_INNER_FACTOR} FWHM over light within {@code LARGEST_COMPARISON_FACTOR} FWHM, NaN when
     * either is not positive), whose departures {@link #contaminated} finds. {@code flux} is NaN only when the position is
     * too near the edge or the annulus is void.
     */
    public record Measurement(double flux, double fluxError, double sky, double apertureRadius, int flags, double concentration) {
        /** Instrumental magnitude, -2.5 log10(flux), or NaN when the flux is not positive. */
        public double magnitude() { return flux > 0 ? -2.5 * Math.log10(flux) : Double.NaN; }

        /** The magnitude error from the flux error (small-error approximation), or NaN when the flux is not positive. */
        public double magnitudeError() { return flux > 0 ? 1.0857 * fluxError / flux : Double.NaN; }
    }

    private MovingObjectPhotometry() { }

    /**
     * Measures the object at {@code x, y} (registered pixels) on one aligned frame.
     *
     * @param pixels              the frame's stored signed 16-bit pixels; not changed
     * @param fwhm                the stars' FWHM in pixels
     * @param exposureMillis      the exposure, or 0 when unknown
     * @param trailPixels         how far the object moved during the exposure, in pixels
     * @param saturationThreshold the stored peak level (shifted domain) at which a measurement is saturated
     */
    public static Measurement measure(short[][] pixels, double x, double y, double fwhm, long exposureMillis,
                                      double trailPixels, double saturationThreshold) {
        if (!(fwhm > 0)) throw new IllegalArgumentException("FWHM must be positive");
        double radius = APERTURE_FWHM * fwhm, inner = ANNULUS_INNER_FWHM * fwhm, outer = ANNULUS_OUTER_FWHM * fwhm;
        double[] skyBuffer = new double[(int) (Math.PI * outer * outer) + 64];
        double[] radii = {radius, PhotometryGeometry.CONCENTRATION_INNER_FACTOR * fwhm, PhotometryGeometry.LARGEST_COMPARISON_FACTOR * fwhm};
        var aperture = ApertureMeasurer.measureAperture(pixels, x, y, radii, 0, inner, outer, radii[2],
                null, saturationThreshold, skyBuffer);
        int flags = aperture.flags() | (trailPixels > radius ? PhotometryFlags.TRAILED : 0);
        if (aperture.sums() == null) return new Measurement(Double.NaN, Double.NaN, Double.NaN, radius, flags, Double.NaN);
        double seconds = exposureMillis > 0 ? exposureMillis / 1000.0 : 1.0;
        double[] sums = aperture.sums();
        double concentration = sums[1] > 0 && sums[2] > 0 ? sums[1] / sums[2] : Double.NaN;
        return new Measurement(sums[0] / seconds, aperture.fluxSigma(0) / seconds, aperture.sky(), radius, flags, concentration);
    }

    /**
     * The peak level at which a measurement on this frame is saturated, in the shifted domain: the default
     * {@code photometrySaturationFraction} of the frame's saturation level, found from the pile-up of clipped pixels as
     * for the comparison-star photometry. A full aligned frame is needed; a cut-out rarely holds a saturated star.
     */
    public static double saturationThreshold(short[][] pixels) {
        return SATURATION_FRACTION * PhotometryStarSelector.saturationLevel(pixels);
    }

    private static final double SATURATION_FRACTION = new io.github.ppissias.jtransient.config.DetectionConfig().photometrySaturationFraction;

    /** Level added to a difference frame so its sky stays well above the fill level and below saturation. */
    static final int DIFFERENCE_PEDESTAL = 10_000;

    /**
     * Measures the object on one cut-out after subtracting {@code reference}, the per-pixel median of the session's
     * cut-outs ({@link io.github.ppissias.jtransient.core.SubStackAstrometry#medianReference}): fixed stars and
     * galaxies the object passes then cancel and only its own light is measured, through the same aperture step.
     * Saturation is judged on the original pixels. {@code x, y} are registered pixels.
     */
    public static Measurement measureOnReference(io.github.ppissias.jtransient.core.SubStackAstrometry.Cutout cutout, float[][] reference,
                                                 double x, double y, double fwhm, double trailPixels, double saturationThreshold) {
        short[][] original = cutout.pixels();
        short[][] difference = new short[original.length][original[0].length];
        for (int row = 0; row < original.length; row++) {
            for (int column = 0; column < original[row].length; column++) {
                short stored = original[row][column];
                float level = reference[row][column];
                if (stored <= io.github.ppissias.jtransient.core.DataArea.FILL_THRESHOLD || Float.isNaN(level)) {
                    difference[row][column] = Short.MIN_VALUE;
                    continue;
                }
                double value = io.github.ppissias.jtransient.core.PixelEncoding.toShiftedPositiveInt(stored) - level + DIFFERENCE_PEDESTAL;
                difference[row][column] = io.github.ppissias.jtransient.core.PixelEncoding.fromShiftedPositiveInt(
                        (int) Math.round(Math.max(io.github.ppissias.jtransient.core.DataArea.FILL_LEVEL + 1, Math.min(65_535, value))));
            }
        }
        double localX = x - cutout.originX(), localY = y - cutout.originY();
        Measurement measured = measure(difference, localX, localY, fwhm, cutout.exposureMillis(), trailPixels, Double.POSITIVE_INFINITY);
        int saturated = measure(original, localX, localY, fwhm, cutout.exposureMillis(), 0, saturationThreshold).flags() & PhotometryFlags.SATURATED;
        return new Measurement(measured.flux(), measured.fluxError(), measured.sky(), measured.apertureRadius(), measured.flags() | saturated,
                measured.concentration());
    }

    /**
     * Which measurements of one object have an unusual shape, by the comparison-star photometry's rule: the object's
     * concentration index, divided by the frame's typical one (seeing taken out; {@code frameConcentration} may be NaN
     * where unknown), departing from its usual value by more than {@code SHAPE_OUTLIER_SIGMA} times the robust spread
     * of its departures (at least {@code MIN_SHAPE_SPREAD}). Such a measurement is blurred, blended or hit by a cosmic
     * ray, and its flux is not the object's. Needs five measurements with a concentration; none are flagged otherwise.
     */
    public static boolean[] contaminated(double[] concentration, double[] frameConcentration) {
        int n = concentration.length;
        double[] relative = new double[n];
        double[] finite = new double[n];
        int count = 0;
        for (int k = 0; k < n; k++) {
            double frame = frameConcentration[k] > 0 ? frameConcentration[k] : 1.0;
            relative[k] = concentration[k] > 0 ? concentration[k] / frame : Double.NaN;
            if (Double.isFinite(relative[k])) finite[count++] = relative[k];
        }
        boolean[] flagged = new boolean[n];
        if (count < 5) return flagged;
        double typical = PhotometryMath.median(finite, count);
        double[] departures = new double[count];
        int d = 0;
        for (double value : relative) if (Double.isFinite(value)) departures[d++] = value / typical - 1.0;
        double spread = Math.max(PhotometryMath.robustSigma(departures, d), VariableStarAnalyzer.MIN_SHAPE_SPREAD);
        for (int k = 0; k < n; k++) {
            flagged[k] = Double.isFinite(relative[k]) && Math.abs(relative[k] / typical - 1.0) > VariableStarAnalyzer.SHAPE_OUTLIER_SIGMA * spread;
        }
        return flagged;
    }

    /**
     * Each frame's magnitude offset from comparison stars: for each star its flux relative to its own median over the
     * frames, and per frame the median of those ratios in magnitudes. Subtracting the offset from a magnitude measured
     * on that frame removes changes common to the field, such as transparency and extinction. NaN for a frame with
     * fewer than {@value #MIN_COMPARISON_STARS} stars of positive flux.
     *
     * @param comparisonFlux fluxes per frame and star; NaN or non-positive values are left out
     */
    public static double[] ensembleOffsets(double[][] comparisonFlux) {
        int frames = comparisonFlux.length;
        int stars = frames == 0 ? 0 : comparisonFlux[0].length;
        double[] reference = new double[stars];
        double[] column = new double[frames];
        for (int s = 0; s < stars; s++) {
            int n = 0;
            for (double[] frame : comparisonFlux) if (frame[s] > 0) column[n++] = frame[s];
            reference[s] = n == 0 ? Double.NaN : PhotometryMath.median(column, n);
        }
        double[] offsets = new double[frames];
        double[] deviations = new double[stars];
        for (int f = 0; f < frames; f++) {
            int n = 0;
            for (int s = 0; s < stars; s++) {
                double value = comparisonFlux[f][s];
                if (value > 0 && reference[s] > 0) deviations[n++] = -2.5 * Math.log10(value / reference[s]);
            }
            offsets[f] = n < MIN_COMPARISON_STARS ? Double.NaN : PhotometryMath.median(Arrays.copyOf(deviations, n));
        }
        return offsets;
    }
}
