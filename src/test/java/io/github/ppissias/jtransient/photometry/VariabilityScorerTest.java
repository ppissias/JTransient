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
import static org.junit.Assert.assertFalse;
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

    @Test
    public void deviationsEitherSideOfAMissingMeasurementDoNotSupportEachOther() {
        int n = 20;
        boolean[] used = new boolean[n];
        java.util.Arrays.fill(used, true);

        // Frame 9 is used but the star has no measurement there (a crossing, say): 8 and 10 are both dropped.
        double[] r = quietCurve(n);
        r[8] = 1.0;
        r[9] = Double.NaN;
        r[10] = 1.1;
        StarLightCurve star = new StarLightCurve(0, 0, 0, 1000, n);
        VariabilityScorer.removeIsolatedOutliers(r, used, star);
        assertTrue(Double.isNaN(r[8]) && Double.isNaN(r[10]));
        assertTrue((star.flags[8] & PhotometryFlags.OUTLIER) != 0 && (star.flags[10] & PhotometryFlags.OUTLIER) != 0);

        // A frame the photometry left out does not break support: the two deviations keep each other.
        used[9] = false;
        r = quietCurve(n);
        r[8] = 1.0;
        r[9] = Double.NaN;
        r[10] = 1.1;
        VariabilityScorer.removeIsolatedOutliers(r, used, new StarLightCurve(0, 0, 0, 1000, n));
        assertFalse(Double.isNaN(r[8]) || Double.isNaN(r[10]));

        // Adjacent deviations keep each other; a lone one is dropped.
        used[9] = true;
        r = quietCurve(n);
        r[8] = 1.0;
        r[9] = 1.1;
        r[15] = 1.2;
        VariabilityScorer.removeIsolatedOutliers(r, used, new StarLightCurve(0, 0, 0, 1000, n));
        assertFalse(Double.isNaN(r[8]) || Double.isNaN(r[9]));
        assertTrue(Double.isNaN(r[15]));
    }

    /** Small alternating residuals with a robust scatter of about 0.01 mag. */
    private static double[] quietCurve(int n) {
        double[] r = new double[n];
        for (int j = 0; j < n; j++) {
            r[j] = (j % 2 == 0 ? 0.01 : -0.01) * (1 + 0.1 * (j % 3));
        }
        return r;
    }

    @Test
    public void sharedLocalFadingIsFoundEvenWhenItsNeighboursAreCandidates() {
        double[] fading = new double[40];
        for (int j = 0; j < fading.length; j++) {
            fading[j] = 0.6 * Math.max(0, j - 30) / 9.0 + 0.002 * Math.cos(j);
        }
        java.util.List<StarLightCurve> stars = scoreLocalGroup(new double[][]{
                fading, fading.clone(), fading.clone(), fading.clone()});
        StarLightCurve target = stars.get(0);
        assertTrue("the target and its neighbours must reach the candidate scores", target.scatterZ > 5);
        for (int k = 1; k < 4; k++) {
            assertTrue("affected neighbour " + k + " must also reach candidate scores", stars.get(k).scatterZ > 5);
        }
        assertTrue("shared fading must fail LOCAL: " + target.failedGates, target.failedGates.contains("LOCAL"));
        assertEquals(3, target.localComparisonStars);
        assertTrue(target.localSharedFraction > 0.9);
    }

    @Test
    public void independentNearbyVariablesKeepTheirOwnVariation() {
        java.util.List<StarLightCurve> stars = scoreLocalGroup(new double[][]{
                wave(0.15, 1), wave(0.15, 2), wave(0.15, 3), wave(0.15, 4)});
        StarLightCurve target = stars.get(0);
        assertEquals(target.failedGates.toString(), VariabilityTier.HIGH_CONFIDENCE, target.tier);
        assertTrue(target.localCorrelation < 0.3);
    }

    @Test
    public void faintCopiesOfAGenuineVariableDoNotVetoIt() {
        java.util.List<StarLightCurve> stars = scoreLocalGroup(new double[][]{
                wave(0.4, 1), wave(0.012, 1), wave(0.012, 1), wave(0.012, 1)});
        StarLightCurve target = stars.get(0);
        assertEquals(target.failedGates.toString(), VariabilityTier.HIGH_CONFIDENCE, target.tier);
        assertTrue(target.localCorrelation > 0.9);
        assertEquals(0.03, target.localSharedFraction, 0.001);
    }

    private static double[] wave(double amplitude, int cycles) {
        double[] curve = new double[40];
        for (int j = 0; j < curve.length; j++) {
            curve[j] = amplitude * Math.sin(2 * Math.PI * cycles * j / curve.length);
        }
        return curve;
    }

    /** A small affected group and independent stars that can supply the distant quiet-star fallback. */
    private static java.util.List<StarLightCurve> scoreLocalGroup(double[][] localCurves) {
        int frames = 40;
        java.util.List<StarLightCurve> stars = new java.util.ArrayList<>();
        for (int k = 0; k < localCurves.length; k++) {
            StarLightCurve star = new StarLightCurve(k, 100 + (k % 2) * 12,
                    100 + (k / 2) * 12, 10000, frames);
            star.deltaMag = localCurves[k].clone();
            stars.add(star);
        }
        java.util.Random random = new java.util.Random(87113L);
        for (int k = 0; k < 120; k++) {
            StarLightCurve star = new StarLightCurve(stars.size(), 250 + (k % 10) * 4,
                    80 + (k / 10) * 4, 10000, frames);
            for (int j = 0; j < frames; j++) {
                star.deltaMag[j] = 0.015 * random.nextGaussian();
            }
            stars.add(star);
        }
        int n = stars.size();
        boolean[][] usable = new boolean[n][frames];
        double[][] small = new double[n][frames];
        double[][] large = new double[n][frames];
        double[][] sky = new double[n][frames];
        for (int i = 0; i < n; i++) {
            stars.get(i).meanMag = -9 + 0.001 * i;
            java.util.Arrays.fill(usable[i], true);
            java.util.Arrays.fill(sky[i], 1000);
            small[i] = stars.get(i).deltaMag.clone();
            large[i] = stars.get(i).deltaMag.clone();
        }
        VariabilityScorer.FrameSeries series = new VariabilityScorer.FrameSeries();
        series.zeroPoint = new double[frames];
        series.fwhm = new double[frames];
        java.util.Arrays.fill(series.fwhm, 2);
        series.offsetX = new double[frames];
        series.offsetY = new double[frames];
        series.julianDate = new double[frames];
        for (int j = 0; j < frames; j++) {
            series.julianDate[j] = 2459000 + j / 720.0;
        }
        series.used = new boolean[frames];
        java.util.Arrays.fill(series.used, true);
        VariabilityScorer.score(stars, usable, small, large, sky, series, false,
                new io.github.ppissias.jtransient.config.DetectionConfig(),
                new io.github.ppissias.jtransient.telemetry.PipelineTelemetry.PhotometryTelemetry());
        return stars;
    }
}
