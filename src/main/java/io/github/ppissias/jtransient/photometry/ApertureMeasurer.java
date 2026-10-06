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

import io.github.ppissias.jtransient.config.DetectionConfig;
import io.github.ppissias.jtransient.core.PixelEncoding;

import java.util.Arrays;
import java.util.List;

/**
 * Forced aperture photometry of the selected stars on one frame.
 *
 * <p>The frame FWHM is measured here from second moments of bright stars, because the extraction
 * FWHM uses sqrt(lambda1 + lambda2) and reads about sqrt(2) too large for round stars. Every star is
 * then measured in five apertures at once (0.7, 1.0, main, 2.0 and 2.5 x FWHM), so the concentration
 * index for check B, the main photometry flux and the aperture-consistency gate all come from the
 * same pass. The sky is a sigma-clipped median of an annulus with the pixels of master stars masked out.</p>
 */
final class ApertureMeasurer {

    /** Aperture indices in {@link FrameMeasurement#flux}. */
    static final int R_CI_INNER = 0;
    static final int R_SMALL = 1;
    static final int R_MAIN = 2;
    static final int R_LARGE = 3;
    static final int R_CI_OUTER = 4;
    static final int RADIUS_COUNT = 5;
    /**
     * Inner concentration radius in FWHM. A Gaussian's concentration index is most sensitive to
     * broadening near 0.6 FWHM; at 1.0 FWHM the aperture already holds 94% of the flux and the
     * index barely moves.
     */
    static final double CI_INNER_FWHM_FACTOR = 0.7;
    static final double CI_OUTER_FWHM_FACTOR = 2.5;

    /** Stars used to measure the registration offset and FWHM, skipping the brightest (possibly saturated) tenth. */
    private static final int SHAPE_STARS = 50;
    private static final int MIN_SHAPE_STARS = 5;
    /** Moment window radius in units of the extraction FWHM. */
    private static final double MOMENT_WINDOW_FACTOR = 1.5;
    /** Fewest usable sky pixels for a sky estimate. */
    private static final int MIN_SKY_PIXELS = 10;
    /** An annulus with more floor pixels than this share is treated as void padding. */
    private static final double MAX_FLOOR_SHARE_IN_ANNULUS = 0.5;
    private static final int SUPERSAMPLE = 5;
    private static final double MIN_RADIUS_PIXELS = 1.0;

    /** Measurements of every selected star on one frame. */
    static final class FrameMeasurement {
        double fwhm;
        final double[] radii = new double[RADIUS_COUNT];
        /** Background-subtracted flux per aperture and star. */
        final double[][] flux;
        final double[] sky;
        final double[] magError;
        final int[] flags;
        double offsetX;
        double offsetY;
        double registrationSpread = Double.NaN;
        double floorClippedFraction = Double.NaN;
        double skyMedian = Double.NaN;

        FrameMeasurement(double fwhm, int starCount) {
            this.fwhm = fwhm;
            this.flux = new double[RADIUS_COUNT][starCount];
            this.sky = new double[starCount];
            this.magError = new double[starCount];
            this.flags = new int[starCount];
            for (double[] f : flux) {
                Arrays.fill(f, Double.NaN);
            }
            Arrays.fill(sky, Double.NaN);
            Arrays.fill(magError, Double.NaN);
        }

        /** Instrumental magnitude in aperture {@code r}, or NaN when the flux is not positive. */
        double mag(int r, int star) {
            double f = flux[r][star];
            return f > 0 ? -2.5 * Math.log10(f) : Double.NaN;
        }

        /** Concentration index: flux in 0.7 x FWHM divided by flux in 2.5 x FWHM. */
        double concentration(int star) {
            double inner = flux[R_CI_INNER][star];
            double outer = flux[R_CI_OUTER][star];
            return (inner > 0 && outer > 0) ? inner / outer : Double.NaN;
        }
    }

    private ApertureMeasurer() {
    }

    /**
     * Measures all stars on one frame.
     *
     * @param pixels frame pixels in signed-short storage
     * @param extractionFwhm frame FWHM from the quality analysis, used to size the moment window and as a fallback
     * @param stars selected stars, brightest first
     * @param otherStarMask pixels belonging to master stars (excluded from sky), may be null
     * @param saturationThreshold peak level (shifted domain) at or above which a measurement is saturated
     * @param frameBackground frame background median (shifted domain), used for the void test
     * @param config pipeline configuration
     */
    static FrameMeasurement measure(short[][] pixels,
                                    double extractionFwhm,
                                    List<PhotometryStarSelector.Star> stars,
                                    boolean[][] otherStarMask,
                                    double saturationThreshold,
                                    double frameBackground,
                                    DetectionConfig config) {
        int n = stars.size();
        FrameMeasurement m = new FrameMeasurement(extractionFwhm, n);
        double guideInner = config.photometryAnnulusInnerFwhmFactor * extractionFwhm;
        double guideOuter = Math.max(guideInner + 2.0, config.photometryAnnulusOuterFwhmFactor * extractionFwhm);
        measureShapeAndOffset(pixels, stars, extractionFwhm, guideInner, guideOuter, otherStarMask, m);

        double fwhm = m.fwhm;
        m.radii[R_CI_INNER] = Math.max(MIN_RADIUS_PIXELS, CI_INNER_FWHM_FACTOR * fwhm);
        m.radii[R_SMALL] = Math.max(MIN_RADIUS_PIXELS, 1.0 * fwhm);
        m.radii[R_MAIN] = Math.max(MIN_RADIUS_PIXELS, config.photometryApertureFwhmFactor * fwhm);
        m.radii[R_LARGE] = Math.max(MIN_RADIUS_PIXELS, 2.0 * fwhm);
        m.radii[R_CI_OUTER] = Math.max(MIN_RADIUS_PIXELS, CI_OUTER_FWHM_FACTOR * fwhm);
        double maxApertureRadius = Arrays.stream(m.radii).max().orElse(MIN_RADIUS_PIXELS);
        double innerRadius = Math.max(maxApertureRadius + 1.0, config.photometryAnnulusInnerFwhmFactor * fwhm);
        double outerRadius = Math.max(innerRadius + 2.0, config.photometryAnnulusOuterFwhmFactor * fwhm);
        double voidThreshold = frameBackground * config.voidThresholdFraction;

        double[] floorShares = new double[n];
        Arrays.fill(floorShares, Double.NaN);
        double[] skyBuffer = new double[(int) (Math.PI * outerRadius * outerRadius) + 64];

        for (int i = 0; i < n; i++) {
            PhotometryStarSelector.Star star = stars.get(i);
            double cx = star.x + m.offsetX;
            double cy = star.y + m.offsetY;

            if (!insideImage(pixels, cx, cy, outerRadius)) {
                m.flags[i] |= PhotometryFlags.EDGE_OR_VOID;
                continue;
            }

            // --- Sky annulus ---
            int skyCount = 0;
            int floorCount = 0;
            int annulusCount = 0;
            int ro = (int) Math.ceil(outerRadius);
            int x0 = (int) Math.round(cx);
            int y0 = (int) Math.round(cy);
            for (int y = y0 - ro; y <= y0 + ro; y++) {
                for (int x = x0 - ro; x <= x0 + ro; x++) {
                    double d = Math.hypot(x - cx, y - cy);
                    if (d < innerRadius || d > outerRadius) {
                        continue;
                    }
                    int v = PixelEncoding.toShiftedPositiveInt(pixels[y][x]);
                    annulusCount++;
                    if (v == 0) {
                        floorCount++;
                    }
                    if (otherStarMask != null && otherStarMask[y][x]) {
                        continue;
                    }
                    if (skyCount < skyBuffer.length) {
                        skyBuffer[skyCount++] = v;
                    }
                }
            }
            floorShares[i] = annulusCount > 0 ? (double) floorCount / annulusCount : Double.NaN;
            double[] skyStats = clippedMedianAndSigma(skyBuffer, skyCount);
            if (skyStats == null || floorShares[i] > MAX_FLOOR_SHARE_IN_ANNULUS) {
                m.flags[i] |= PhotometryFlags.EDGE_OR_VOID;
                continue;
            }
            double sky = skyStats[0];
            double skySigma = skyStats[1];
            m.sky[i] = sky;

            // --- Apertures with sub-pixel weights ---
            double[] sums = new double[RADIUS_COUNT];
            double[] areas = new double[RADIUS_COUNT];
            int peak = 0;
            boolean voidPixel = false;
            int ra = (int) Math.ceil(maxApertureRadius + 1);
            for (int y = y0 - ra; y <= y0 + ra; y++) {
                for (int x = x0 - ra; x <= x0 + ra; x++) {
                    double d = Math.hypot(x - cx, y - cy);
                    if (d > maxApertureRadius + 1) {
                        continue;
                    }
                    int v = PixelEncoding.toShiftedPositiveInt(pixels[y][x]);
                    double signal = v - sky;
                    for (int r = 0; r < RADIUS_COUNT; r++) {
                        double w = pixelWeight(x - cx, y - cy, d, m.radii[r]);
                        if (w > 0) {
                            sums[r] += w * signal;
                            areas[r] += w;
                        }
                    }
                    if (d <= m.radii[R_MAIN]) {
                        peak = Math.max(peak, v);
                        if (voidThreshold > 0 && sky > 0 && v < voidThreshold) {
                            voidPixel = true;
                        }
                    }
                }
            }
            for (int r = 0; r < RADIUS_COUNT; r++) {
                m.flux[r][i] = sums[r];
            }
            if (voidPixel) {
                m.flags[i] |= PhotometryFlags.EDGE_OR_VOID;
            }
            if (peak >= saturationThreshold) {
                m.flags[i] |= PhotometryFlags.SATURATED;
            }
            double mainFlux = sums[R_MAIN];
            if (!(mainFlux > 0)) {
                m.flags[i] |= PhotometryFlags.BAD_FLUX;
            } else {
                double nAp = areas[R_MAIN];
                double fluxSigma = skySigma * Math.sqrt(nAp * (1.0 + nAp / Math.max(1, skyCount)));
                m.magError[i] = 1.0857 * fluxSigma / mainFlux;
            }
        }

        m.floorClippedFraction = PhotometryMath.median(floorShares);
        m.skyMedian = PhotometryMath.median(m.sky);
        return m;
    }

    /**
     * Measures centroids and second moments of bright (but not the brightest) stars. Stores the median
     * offset from the master positions, the robust spread around it, and the median FWHM
     * {@code 2.355 * sqrt((Mxx + Myy) / 2)}. Keeps the extraction FWHM when too few stars are usable.
     */
    private static void measureShapeAndOffset(short[][] pixels,
                                              List<PhotometryStarSelector.Star> stars,
                                              double extractionFwhm,
                                              double innerRadius,
                                              double outerRadius,
                                              boolean[][] otherStarMask,
                                              FrameMeasurement m) {
        int first = stars.size() / 10;
        int last = Math.min(stars.size(), first + SHAPE_STARS);
        int count = Math.max(0, last - first);
        double[] dxs = new double[count];
        double[] dys = new double[count];
        double[] fwhms = new double[count];
        int found = 0;
        double window = Math.max(3.0, MOMENT_WINDOW_FACTOR * extractionFwhm);
        double[] skyBuffer = new double[(int) (Math.PI * outerRadius * outerRadius) + 64];
        for (int k = first; k < last; k++) {
            PhotometryStarSelector.Star star = stars.get(k);
            if (!insideImage(pixels, star.x, star.y, outerRadius)) {
                continue;
            }
            int x0 = (int) Math.round(star.x);
            int y0 = (int) Math.round(star.y);
            int ro = (int) Math.ceil(outerRadius);
            int skyCount = 0;
            for (int y = y0 - ro; y <= y0 + ro; y++) {
                for (int x = x0 - ro; x <= x0 + ro; x++) {
                    double d = Math.hypot(x - star.x, y - star.y);
                    if (d >= innerRadius && d <= outerRadius
                            && (otherStarMask == null || !otherStarMask[y][x])
                            && skyCount < skyBuffer.length) {
                        skyBuffer[skyCount++] = PixelEncoding.toShiftedPositiveInt(pixels[y][x]);
                    }
                }
            }
            double[] skyStats = clippedMedianAndSigma(skyBuffer, skyCount);
            if (skyStats == null) {
                continue;
            }
            int rw = (int) Math.ceil(window);
            double sw = 0;
            double sx = 0;
            double sy = 0;
            double sxx = 0;
            double syy = 0;
            for (int y = y0 - rw; y <= y0 + rw; y++) {
                for (int x = x0 - rw; x <= x0 + rw; x++) {
                    if (Math.hypot(x - star.x, y - star.y) > window) {
                        continue;
                    }
                    double s = PixelEncoding.toShiftedPositiveInt(pixels[y][x]) - skyStats[0];
                    sw += s;
                    sx += s * x;
                    sy += s * y;
                    sxx += s * x * x;
                    syy += s * y * y;
                }
            }
            if (sw <= 0) {
                continue;
            }
            double cx = sx / sw;
            double cy = sy / sw;
            double variance = 0.5 * ((sxx / sw - cx * cx) + (syy / sw - cy * cy));
            dxs[found] = cx - star.x;
            dys[found] = cy - star.y;
            fwhms[found] = variance > 0 ? 2.355 * Math.sqrt(variance) : Double.NaN;
            found++;
        }
        if (found < MIN_SHAPE_STARS) {
            m.offsetX = 0;
            m.offsetY = 0;
            return;
        }
        double mdx = PhotometryMath.median(dxs, found);
        double mdy = PhotometryMath.median(dys, found);
        double[] distances = new double[found];
        for (int i = 0; i < found; i++) {
            distances[i] = Math.hypot(dxs[i] - mdx, dys[i] - mdy);
        }
        m.offsetX = mdx;
        m.offsetY = mdy;
        m.registrationSpread = PhotometryMath.MAD_TO_SIGMA * PhotometryMath.median(distances, found);
        double measured = PhotometryMath.median(fwhms, found);
        if (Double.isFinite(measured) && measured > 0.5) {
            m.fwhm = measured;
        }
    }

    /**
     * Fraction of the pixel centred at (dx, dy) from the aperture centre that lies inside radius r.
     */
    static double pixelWeight(double dx, double dy, double distance, double r) {
        if (distance <= r - 0.75) {
            return 1.0;
        }
        if (distance >= r + 0.75) {
            return 0.0;
        }
        int inside = 0;
        double r2 = r * r;
        for (int sy = 0; sy < SUPERSAMPLE; sy++) {
            double py = dy - 0.5 + (sy + 0.5) / SUPERSAMPLE;
            for (int sx = 0; sx < SUPERSAMPLE; sx++) {
                double px = dx - 0.5 + (sx + 0.5) / SUPERSAMPLE;
                if (px * px + py * py <= r2) {
                    inside++;
                }
            }
        }
        return inside / (double) (SUPERSAMPLE * SUPERSAMPLE);
    }

    /**
     * Three-pass 3-sigma clipped median and robust sigma of the first {@code count} values.
     *
     * @return {@code [median, sigma]}, or null when too few values remain
     */
    static double[] clippedMedianAndSigma(double[] values, int count) {
        if (count < MIN_SKY_PIXELS) {
            return null;
        }
        double[] work = Arrays.copyOf(values, count);
        double[] scratch = new double[count];
        int n = count;
        double median = Double.NaN;
        double sigma = Double.NaN;
        for (int iter = 0; iter < 3; iter++) {
            // Linear-time selection; this runs once per star and frame, so sorting would dominate.
            System.arraycopy(work, 0, scratch, 0, n);
            median = PhotometryMath.medianInPlace(scratch, n);
            for (int i = 0; i < n; i++) {
                scratch[i] = Math.abs(work[i] - median);
            }
            sigma = PhotometryMath.MAD_TO_SIGMA * PhotometryMath.medianInPlace(scratch, n);
            if (!(sigma > 0)) {
                break;
            }
            int kept = 0;
            for (int i = 0; i < n; i++) {
                if (Math.abs(work[i] - median) <= 3.0 * sigma) {
                    work[kept++] = work[i];
                }
            }
            if (kept == n || kept < MIN_SKY_PIXELS) {
                break;
            }
            n = kept;
        }
        if (!Double.isFinite(median)) {
            return null;
        }
        return new double[]{median, Double.isFinite(sigma) ? sigma : 0.0};
    }

    private static boolean insideImage(short[][] pixels, double cx, double cy, double radius) {
        return cx - radius - 1 >= 0 && cy - radius - 1 >= 0
                && cx + radius + 1 < pixels[0].length && cy + radius + 1 < pixels.length;
    }
}
