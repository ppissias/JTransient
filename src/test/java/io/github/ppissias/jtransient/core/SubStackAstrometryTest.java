/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 * author: Petros Pissias <petrospis at gmail.com>
 *
 */
package io.github.ppissias.jtransient.core;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Measures a mover too faint for single frames in three sub-stacks, among fixed stars, and finds nothing where nothing is. */
public class SubStackAstrometryTest {
    private static final int SIZE = 120, FRAMES = 30;
    private static final double SKY = 1000, NOISE = 10, FWHM = 3;
    private static final long START = 1_700_000_000_000L, CADENCE = 120_000L;
    private static final double X0 = 40, Y0 = 70, VX = 0.4, VY = -0.25;

    /** Thirty frames with stars and, optionally, a mover of the given peak, starting at (X0, Y0) at START. */
    private static List<SubStackAstrometry.Cutout> frames(double moverPeak, long seed) {
        Random random = new Random(seed);
        double sigma = FWHM / 2.3548;
        double[][] stars = {{30, 30, 3000}, {90, 40, 1500}, {60, 95, 800}, {50, 60, 400}};
        var cutouts = new ArrayList<SubStackAstrometry.Cutout>();
        for (int k = 0; k < FRAMES; k++) {
            double minutes = k * CADENCE / 60000.0;
            short[][] pixels = new short[SIZE][SIZE];
            for (int y = 0; y < SIZE; y++) for (int x = 0; x < SIZE; x++) {
                double value = SKY + NOISE * random.nextGaussian();
                for (double[] s : stars) value += s[2] * Math.exp(-((x - s[0]) * (x - s[0]) + (y - s[1]) * (y - s[1])) / (2 * sigma * sigma));
                double mx = X0 + VX * minutes, my = Y0 + VY * minutes;
                value += moverPeak * Math.exp(-((x - mx) * (x - mx) + (y - my) * (y - my)) / (2 * sigma * sigma));
                pixels[y][x] = PixelEncoding.fromShiftedPositiveInt((int) Math.round(value));
            }
            // A cut-out of a larger frame: its origin is 100, 200 in registered pixels.
            cutouts.add(new SubStackAstrometry.Cutout(100, 200, pixels, START + k * CADENCE, 60_000));
        }
        return cutouts;
    }

    /** A mover of peak 2 sigma, below single-frame detection: each third of the frames gives a clear centroid on the true path at its mean time. */
    @Test
    public void measuresAFaintMoverInIndependentSubStacks() {
        var cutouts = frames(2.0 * NOISE, 1);
        var measurements = SubStackAstrometry.measure(cutouts, X0 + 100, Y0 + 200, START, VX, VY, 3, FWHM);
        assertEquals(3, measurements.size());
        int previousLast = -1;
        for (var m : measurements) {
            assertEquals("each frame used once, in order", previousLast + 1, m.firstCutout());
            previousLast = m.lastCutout();
            assertEquals(10, m.frames());
            assertEquals(600_000, m.exposureMillis());
            double minutes = (m.midpointMillis() - START) / 60000.0;
            assertEquals(X0 + 100 + VX * minutes, m.x(), 0.5);
            assertEquals(Y0 + 200 + VY * minutes, m.y(), 0.5);
            assertTrue(m.problem() + ", snr " + m.snr(), m.clear());
        }
        assertEquals(FRAMES - 1, previousLast);
    }

    /** Without a mover, no sub-stack shows one clearly. */
    @Test
    public void findsNothingClearWhereNothingIs() {
        var measurements = SubStackAstrometry.measure(frames(0, 2), X0 + 100, Y0 + 200, START, VX, VY, 3, FWHM);
        for (var m : measurements) assertFalse("snr " + m.snr(), m.clear());
    }
}
