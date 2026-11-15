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

import io.github.ppissias.jtransient.core.PixelEncoding;
import org.junit.Test;

import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Aperture photometry of a moving object: flux and error, brightness changes, faint and edge cases, and the ensemble. */
public class MovingObjectPhotometryTest {
    private static final int SIZE = 80;
    private static final double SKY = 1000, NOISE = 10, FWHM = 3;

    /** A frame of sky and noise with a Gaussian source of the given peak at (x, y). */
    private static short[][] frame(double x, double y, double peak, long seed) {
        Random random = new Random(seed);
        double sigma = FWHM / 2.3548;
        short[][] pixels = new short[SIZE][SIZE];
        for (int j = 0; j < SIZE; j++) for (int i = 0; i < SIZE; i++) {
            double value = SKY + NOISE * random.nextGaussian() + peak * Math.exp(-((i - x) * (i - x) + (j - y) * (j - y)) / (2 * sigma * sigma));
            pixels[j][i] = PixelEncoding.fromShiftedPositiveInt((int) Math.round(value));
        }
        return pixels;
    }

    /** The total light of a Gaussian of this peak, per second of a 120 s exposure. */
    private static double expected(double peak) {
        double sigma = FWHM / 2.3548;
        return 2 * Math.PI * sigma * sigma * peak / 120.0;
    }

    /** A constant object: the flux per second within three errors of its true light; a doubled one is 0.75 mag brighter. */
    @Test
    public void measuresFluxAndBrightnessChanges() {
        double sum = 0, sumSquares = 0, fluxSum = 0, error = 0;
        int n = 40;
        for (int k = 0; k < n; k++) {
            var m = MovingObjectPhotometry.measure(frame(40.3, 39.6, 200, k), 40.3, 39.6, FWHM, 120_000, 0.5, 65_000);
            assertEquals(0, m.flags());
            assertEquals(expected(200), m.flux(), 5 * m.fluxError());
            assertEquals(SKY, m.sky(), 2);
            assertEquals(4.5, m.apertureRadius(), 0);
            sum += m.magnitude();
            sumSquares += m.magnitude() * m.magnitude();
            fluxSum += m.flux();
            error = m.fluxError();
        }
        assertEquals("unbiased: the mean flux is the true light", expected(200), fluxSum / n, 3 * error / Math.sqrt(n) + 0.005 * expected(200));
        double meanFaint = sum / n;
        double scatter = Math.sqrt(sumSquares / n - meanFaint * meanFaint);
        double meanBright = 0;
        for (int k = 0; k < n; k++) meanBright += MovingObjectPhotometry.measure(frame(40.3, 39.6, 400, 100 + k), 40.3, 39.6, FWHM,
                120_000, 0.5, 65_000).magnitude() / n;
        assertEquals("twice the light is 0.75 mag brighter", -0.753, meanBright - meanFaint, 0.02);
        var one = MovingObjectPhotometry.measure(frame(40.3, 39.6, 200, 7), 40.3, 39.6, FWHM, 120_000, 0.5, 65_000);
        assertEquals("the stated error matches the scatter", scatter, one.magnitudeError(), 0.5 * scatter);
    }

    /** No object: the flux scatters around zero, negative values are kept and flagged, never dropped or clipped. */
    @Test
    public void keepsFaintAndNegativeFluxes() {
        int negative = 0;
        for (int k = 0; k < 30; k++) {
            var m = MovingObjectPhotometry.measure(frame(40, 40, 0, 200 + k), 40, 40, FWHM, 120_000, 0, 65_000);
            assertTrue(Double.isFinite(m.flux()));
            assertTrue(Math.abs(m.flux()) < 4 * m.fluxError());
            if (m.flux() <= 0) {
                negative++;
                assertTrue((m.flags() & PhotometryFlags.BAD_FLUX) != 0);
                assertTrue(Double.isNaN(m.magnitude()));
            }
        }
        assertTrue("about half are negative: " + negative, negative > 5 && negative < 25);
    }

    /** Near the edge there is no measurement; a long trail and a saturated peak are flagged. */
    @Test
    public void flagsEdgesTrailsAndSaturation() {
        var edge = MovingObjectPhotometry.measure(frame(5, 40, 200, 1), 5, 40, FWHM, 120_000, 0, 65_000);
        assertTrue(Double.isNaN(edge.flux()));
        assertTrue((edge.flags() & PhotometryFlags.EDGE_OR_VOID) != 0);
        assertTrue("a trail longer than the aperture radius", (MovingObjectPhotometry.measure(frame(40, 40, 200, 1), 40, 40, FWHM,
                120_000, 5.0, 65_000).flags() & PhotometryFlags.TRAILED) != 0);
        assertEquals("one FWHM of trail stays inside the aperture", 0, MovingObjectPhotometry.measure(frame(40, 40, 200, 1), 40, 40,
                FWHM, 120_000, 3.0, 65_000).flags() & PhotometryFlags.TRAILED);
        assertTrue((MovingObjectPhotometry.measure(frame(40, 40, 200, 1), 40, 40, FWHM, 120_000, 0, 1100).flags()
                & PhotometryFlags.SATURATED) != 0);
    }

    /**
     * A mover crossing a bright fixed star: measured directly, its flux jumps while the star is in the aperture; measured
     * after subtracting the frames' median, the star cancels and the mover's light stays constant.
     */
    @Test
    public void aFixedStarCancelsOnTheMedianReference() {
        Random random = new Random(5);
        double sigma = FWHM / 2.3548;
        var cutouts = new java.util.ArrayList<io.github.ppissias.jtransient.core.SubStackAstrometry.Cutout>();
        double[] xs = new double[25];
        for (int k = 0; k < 25; k++) {
            xs[k] = 20 + k * 40.0 / 24;
            short[][] pixels = new short[SIZE][SIZE];
            for (int j = 0; j < SIZE; j++) for (int i = 0; i < SIZE; i++) {
                double star = 2000 * Math.exp(-((i - 40.0) * (i - 40.0) + (j - 40.0) * (j - 40.0)) / (2 * sigma * sigma));
                double mover = 200 * Math.exp(-((i - xs[k]) * (i - xs[k]) + (j - 40.0) * (j - 40.0)) / (2 * sigma * sigma));
                pixels[j][i] = PixelEncoding.fromShiftedPositiveInt((int) Math.round(SKY + NOISE * random.nextGaussian() + star + mover));
            }
            cutouts.add(new io.github.ppissias.jtransient.core.SubStackAstrometry.Cutout(300, 500, pixels, k * 120_000L, 120_000));
        }
        var reference = io.github.ppissias.jtransient.core.SubStackAstrometry.medianReference(cutouts);
        int crossing = 12;
        var direct = MovingObjectPhotometry.measure(cutouts.get(crossing).pixels(), xs[crossing], 40, FWHM, 120_000, 0, 65_000);
        assertTrue("the star adds its light when measured directly", direct.flux() > 5 * expected(200));
        for (int k = 0; k < 25; k++) {
            var m = MovingObjectPhotometry.measureOnReference(cutouts.get(k), reference, xs[k] + 300, 40 + 500, FWHM, 0, 65_000);
            assertEquals("frame " + k, expected(200), m.flux(), 5 * m.fluxError() + 0.05 * expected(200));
        }
    }

    /**
     * A point source's concentration index is the Gaussian's (about 0.74 within 0.7 FWHM of 2.5 FWHM, a little less on pixels); a blurred frame
     * among sharp ones is flagged, a frame whose stars are blurred alike is not.
     */
    @Test
    public void flagsAnUnusualShape() {
        var sharp = MovingObjectPhotometry.measure(frame(40.3, 39.6, 400, 3), 40.3, 39.6, FWHM, 120_000, 0, 65_000);
        assertEquals("pixelation lowers it a little", 0.72, sharp.concentration(), 0.04);
        double[] concentration = new double[12];
        double[] frames = new double[12];
        Random random = new Random(9);
        for (int k = 0; k < 12; k++) {
            concentration[k] = 0.74 * (1 + 0.01 * random.nextGaussian());
            frames[k] = Double.NaN;
        }
        concentration[4] = 0.5;
        boolean[] flagged = MovingObjectPhotometry.contaminated(concentration, frames);
        for (int k = 0; k < 12; k++) assertEquals("frame " + k, k == 4, flagged[k]);
        frames = new double[12];
        java.util.Arrays.fill(frames, 0.74);
        frames[4] = 0.5;
        flagged = MovingObjectPhotometry.contaminated(concentration, frames);
        assertTrue("the whole frame was blurred", !flagged[4]);
        assertTrue("too few measurements: nothing flagged", !MovingObjectPhotometry.contaminated(new double[]{0.7, 0.7, 0.3},
                new double[]{Double.NaN, Double.NaN, Double.NaN})[2]);
    }

    /** A frame whose bright star is clipped at 50000 saturates below that; without clipped pixels, near the 16-bit top. */
    @Test
    public void findsTheSaturationLevel() {
        short[][] pixels = frame(40, 40, 0, 4);
        for (int j = 38; j <= 42; j++) for (int i = 38; i <= 42; i++) pixels[j][i] = PixelEncoding.fromShiftedPositiveInt(50_000);
        assertEquals(0.85 * 50_000, MovingObjectPhotometry.saturationThreshold(pixels), 1);
        assertEquals(0.85 * 0.95 * 65_535, MovingObjectPhotometry.saturationThreshold(frame(40, 40, 400, 4)), 1);
        assertTrue("a star peaking just under the clip level is saturated", (MovingObjectPhotometry.measure(frame(20, 20, 46_000, 4), 20, 20,
                FWHM, 120_000, 0, MovingObjectPhotometry.saturationThreshold(pixels)).flags() & PhotometryFlags.SATURATED) != 0);
    }

    /** The FWHM from star profiles: Gaussians of FWHM 3 and 7 pixels; saturated or faint stars do not count. */
    @Test
    public void measuresTheStarFwhm() {
        for (double fwhm : new double[]{3, 7}) {
            int size = 200;
            double sigma = fwhm / 2.3548;
            Random random = new Random(11);
            double[][] stars = {{50, 50}, {150, 50}, {50, 150}, {150, 150}, {100, 100}};
            short[][] pixels = new short[size][size];
            for (int j = 0; j < size; j++) for (int i = 0; i < size; i++) {
                double value = SKY + NOISE * random.nextGaussian();
                for (double[] s : stars) value += 3000 * Math.exp(-((i - s[0]) * (i - s[0]) + (j - s[1]) * (j - s[1])) / (2 * sigma * sigma));
                pixels[j][i] = PixelEncoding.fromShiftedPositiveInt((int) Math.round(value));
            }
            assertEquals("FWHM " + fwhm, fwhm, MovingObjectPhotometry.starFwhm(pixels, stars, 65_000), 0.1 * fwhm);
            assertTrue("all saturated", Double.isNaN(MovingObjectPhotometry.starFwhm(pixels, stars, 2000)));
        }
    }

    /** Comparison stars dimmed together on one frame give that frame's offset; too few stars give none. */
    @Test
    public void ensembleOffsetsFollowTheField() {
        double[][] flux = {{100, 200, 400}, {50, 100, 200}, {100, 200, 400}, {100, 200, Double.NaN}};
        double[] offsets = MovingObjectPhotometry.ensembleOffsets(flux);
        assertEquals(0, offsets[0], 1e-12);
        assertEquals("half the light: 0.75 mag", 0.7526, offsets[1], 1e-4);
        assertTrue("two usable stars are too few", Double.isNaN(offsets[3]));
    }
}
