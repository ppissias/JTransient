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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class VariabilityScorerTest {

    @Test
    public void regressionSlopeMeasuresTheSharedShareOfAPattern() {
        int n = 40;
        double[] candidate = new double[n];
        double[] faintCopy = new double[n];
        double[] fullCopy = new double[n];
        for (int j = 0; j < n; j++) {
            candidate[j] = 0.4 * Math.sin(2 * Math.PI * j / n);
            faintCopy[j] = 0.03 * candidate[j];
            fullCopy[j] = candidate[j] + 0.001 * Math.cos(j);
        }
        faintCopy[7] = Double.NaN;

        // A neighbour that picked up a little of a bright variable's light correlates perfectly but shares only 3%.
        assertEquals(0.03, VariabilityScorer.regressionSlope(candidate, faintCopy), 1e-9);
        assertEquals(1.0, VariabilityScorer.regressionSlope(candidate, fullCopy), 0.01);
        assertTrue(Double.isNaN(VariabilityScorer.regressionSlope(new double[]{1, 1, 1}, new double[]{1, 2, 3})));
    }
}
