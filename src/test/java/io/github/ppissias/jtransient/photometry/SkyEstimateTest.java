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

import org.junit.Test;

import java.util.Arrays;
import java.util.Random;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * Checks that the clipped sky estimate reports the size of the sample its median and sigma came from, against a
 * plain sorting implementation of the same three-pass, 3-sigma rule.
 */
public class SkyEstimateTest {

    /** The documented rule, written plainly: median and MAD sigma of the sample, then clip and repeat, three times. */
    private static double[] reference(double[] values) {
        double[] sample = values.clone();
        double median = Double.NaN;
        double sigma = Double.NaN;
        int used = sample.length;
        for (int pass = 0; pass < 3; pass++) {
            used = sample.length;
            double m = median(sample);
            double[] deviations = Arrays.stream(sample).map(v -> Math.abs(v - m)).toArray();
            double s = PhotometryMath.MAD_TO_SIGMA * median(deviations);
            median = m;
            sigma = s;
            if (!(s > 0)) break;
            double[] kept = Arrays.stream(sample).filter(v -> Math.abs(v - m) <= 3.0 * s).toArray();
            if (kept.length == sample.length || kept.length < 10) break;
            sample = kept;
        }
        return new double[]{median, sigma, used};
    }

    private static double median(double[] values) {
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        int n = sorted.length;
        return n % 2 == 1 ? sorted[n / 2] : 0.5 * (sorted[n / 2 - 1] + sorted[n / 2]);
    }

    @Test
    public void aSampleWithoutOutliersUsesEveryValue() {
        double[] values = new double[50];
        for (int i = 0; i < values.length; i++) values[i] = 1000 + (i % 10) - 4.5;
        double[] sky = ApertureMeasurer.clippedMedianAndSigma(values, values.length);
        assertEquals(50, sky[2], 0);
        assertArrayEquals(reference(values), sky, 1e-12);
    }

    @Test
    public void outliersAreLeftOutOfTheCount() {
        Random random = new Random(1);
        double[] values = new double[108];
        for (int i = 0; i < 100; i++) values[i] = 1000 + 10 * random.nextGaussian();
        for (int i = 100; i < 108; i++) values[i] = 1500;
        double[] sky = ApertureMeasurer.clippedMedianAndSigma(values, values.length);
        assertArrayEquals(reference(values), sky, 1e-12);
        assertEquals(true, sky[2] <= 100);
    }

    /** Skewed and contaminated samples that keep clipping through all three passes match the plain rule. */
    @Test
    public void theCountIsThatOfTheLastSampleMeasured() {
        Random random = new Random(7);
        int passesUsedThree = 0;
        for (int trial = 0; trial < 300; trial++) {
            int n = 10 + random.nextInt(300);
            double[] values = new double[n];
            for (int i = 0; i < n; i++) {
                double v = 1000 + 10 * random.nextGaussian();
                if (trial % 3 == 1) v += 40 * -Math.log(1 - random.nextDouble());   // long faint-star tail
                if (trial % 3 == 2 && random.nextDouble() < 0.1) v += 300 * random.nextDouble(); // contaminated annulus
                values[i] = Math.rint(v);
            }
            double[] sky = ApertureMeasurer.clippedMedianAndSigma(values, n);
            double[] expected = reference(values);
            assertArrayEquals("trial " + trial, expected, sky, 1e-9);
            if (sky[2] < n) passesUsedThree++;
        }
        assertEquals(true, passesUsedThree > 50);
    }

    @Test
    public void tooFewValuesGiveNoEstimateAndClippingStopsAtTheMinimum() {
        assertNull(ApertureMeasurer.clippedMedianAndSigma(new double[9], 9));
        // Clipping these would leave fewer than ten values, so the full sample stays.
        double[] values = {0, 0, 0, 0, 1, 1, 1, 1, 50, 60, 70, 80};
        double[] sky = ApertureMeasurer.clippedMedianAndSigma(values, values.length);
        assertEquals(12, sky[2], 0);
        assertArrayEquals(reference(values), sky, 1e-12);
    }
}
