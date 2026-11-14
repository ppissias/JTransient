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
        assertTrue((MovingObjectPhotometry.measure(frame(40, 40, 200, 1), 40, 40, FWHM, 120_000, 3.5, 65_000).flags()
                & PhotometryFlags.TRAILED) != 0);
        assertTrue((MovingObjectPhotometry.measure(frame(40, 40, 200, 1), 40, 40, FWHM, 120_000, 0, 1100).flags()
                & PhotometryFlags.SATURATED) != 0);
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
