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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Checks when the ensemble fit stops: on the predicted correction, zero point and plane together, so a plane still
 * settling under the robust reweighting is not cut short when the zero points have settled, and reaching the
 * iteration limit is reported.
 */
public class EnsembleConvergenceTest {

    private static final int FRAMES = 20;
    private static final double TOLERANCE = EnsembleSolver.CONVERGENCE_MAG;

    /** Ensemble inputs: magnitudes, usable measurements, normalized positions and active frames. */
    private static final class Ensemble {
        double[][] mag;
        boolean[][] usable;
        double[] x;
        double[] y;
        final boolean[] active = new boolean[FRAMES];

        /** Runs the solver on these inputs. */
        EnsembleSolver.Solution solve(boolean fitPlane, int maxIterations, double tolerance) {
            return EnsembleSolver.solve(mag, usable, x, y, active, fitPlane, maxIterations, tolerance);
        }
    }

    /**
     * 64 stars on a symmetric 8 x 8 grid under a changing X gradient. In frames 5 to 12 the star at the right edge
     * brightens and its mirror image at the left edge fades by the same amount. With {@code realistic} false there is
     * no transparency change and no noise, as in the historical probe: by symmetry the zero points stay put while the
     * plane is pulled by the two stars until the reweighting removes them. With it true, the frames also vary in
     * transparency and every measurement has 3 millimag of noise.
     */
    private static Ensemble gradient(boolean realistic) {
        Ensemble e = new Ensemble();
        int stars = 64;
        e.mag = new double[stars][FRAMES];
        e.usable = new boolean[stars][FRAMES];
        e.x = new double[stars];
        e.y = new double[stars];
        Arrays.fill(e.active, true);
        Random noise = new Random(3);
        for (int i = 0; i < stars; i++) {
            e.x[i] = -0.875 + 0.25 * (i % 8);
            e.y[i] = -0.875 + 0.25 * (i / 8);
        }
        int right = 8 * 4 + 7;
        int left = 8 * 4;
        for (int i = 0; i < stars; i++) {
            for (int j = 0; j < FRAMES; j++) {
                double excursion = j >= 5 && j <= 12 ? (i == right ? -0.25 : i == left ? 0.25 : 0) : 0;
                e.mag[i][j] = 12 + 0.02 * i + 0.05 * Math.sin(2 * Math.PI * j / FRAMES) * e.x[i] + excursion
                        + (realistic ? 0.1 * Math.sin(j / 3.0) + 0.003 * noise.nextGaussian() : 0);
                e.usable[i][j] = true;
            }
        }
        return e;
    }

    private static double maxZeroPointChange(EnsembleSolver.Solution a, EnsembleSolver.Solution b) {
        double max = 0;
        for (int j = 0; j < FRAMES; j++) {
            if (a.frameSolved[j] && b.frameSolved[j]) max = Math.max(max, Math.abs(a.zeroPoint[j] - b.zeroPoint[j]));
        }
        return max;
    }

    /** Largest difference of the predicted correction Z + a*x + b*y between two solutions at the star positions. */
    private static double maxCorrectionDifference(EnsembleSolver.Solution a, EnsembleSolver.Solution b, Ensemble e) {
        double max = 0;
        for (int j = 0; j < FRAMES; j++) {
            if (!a.frameSolved[j] || !b.frameSolved[j]) continue;
            for (int i = 0; i < e.x.length; i++) {
                double ca = a.zeroPoint[j] + a.planeX[j] * e.x[i] + a.planeY[j] * e.y[i];
                double cb = b.zeroPoint[j] + b.planeX[j] * e.x[i] + b.planeY[j] * e.y[i];
                max = Math.max(max, Math.abs(ca - cb));
            }
        }
        return max;
    }

    /** The historical failure: a zero-point stopping rule ends after two iterations while the plane still moves. */
    @Test
    public void aPlaneStillSettlingKeepsTheFitGoingAfterTheZeroPointsSettle() {
        Ensemble e = gradient(false);
        EnsembleSolver.Solution one = e.solve(true, 1, TOLERANCE);
        EnsembleSolver.Solution two = e.solve(true, 2, TOLERANCE);
        EnsembleSolver.Solution full = e.solve(true, EnsembleSolver.MAX_ITERATIONS, TOLERANCE);

        assertTrue("zero-point change " + maxZeroPointChange(one, two), maxZeroPointChange(one, two) < 1e-12);
        assertTrue("correction change " + two.lastChangeMag, two.lastChangeMag > 50 * TOLERANCE);
        assertFalse(two.converged);
        assertTrue(full.converged);
        assertTrue("iterations " + full.iterations, full.iterations > 2);
        assertTrue(full.lastChangeMag < TOLERANCE);
    }

    /** A correction declared converged moves by far less than the noise when the fit is run much longer. */
    @Test
    public void aConvergedCorrectionHardlyMovesUnderFurtherIterations() {
        Ensemble e = gradient(true);
        EnsembleSolver.Solution full = e.solve(true, EnsembleSolver.MAX_ITERATIONS, TOLERANCE);
        EnsembleSolver.Solution longer = e.solve(true, 200, 1e-12);

        assertTrue(full.converged);
        double moved = maxCorrectionDifference(full, longer, e);
        assertTrue("correction moved " + moved, moved < 1e-4);
    }

    /** Without a plane the predicted correction is the zero point, so the change measured is the zero-point change. */
    @Test
    public void withoutAPlaneTheChangeIsTheZeroPointChange() {
        Ensemble e = gradient(true);
        EnsembleSolver.Solution previous = e.solve(false, 1, TOLERANCE);
        for (int n = 2; n <= 6; n++) {
            EnsembleSolver.Solution s = e.solve(false, n, TOLERANCE);
            for (int j = 0; j < FRAMES; j++) assertEquals(0.0, s.planeX[j], 0);
            assertEquals("iteration " + n, maxZeroPointChange(previous, s), s.lastChangeMag, 1e-15);
            previous = s;
        }
    }

    /**
     * Missing measurements, a frame with too few stars and a frame too sparse for a plane: the first is left
     * unsolved, the second gets a zero point only, and the stopping state is consistent either way. (With these
     * gaps the zero points themselves settle slowly, so this fixture may reach the iteration limit.)
     */
    @Test
    public void missingMeasurementsAndSparseFramesAreHandled() {
        Ensemble e = gradient(true);
        Random random = new Random(9);
        for (int i = 0; i < e.mag.length; i++) {
            for (int j = 0; j < FRAMES; j++) {
                if (random.nextDouble() < 0.2) e.mag[i][j] = Double.NaN;
                if (j == 3) e.usable[i][j] = i < 8;
                if (j == 4) e.usable[i][j] = i < 20;
            }
        }
        EnsembleSolver.Solution s = e.solve(true, EnsembleSolver.MAX_ITERATIONS, TOLERANCE);

        assertFalse(s.frameSolved[3]);
        assertTrue(s.frameSolved[4]);
        // Fewer than 30 stars: zero point only (the gauge then moves the mean plane, not this frame's own fit, into it).
        assertTrue(s.starsPerFrame[4] < 30);
        assertTrue(s.converged ? s.lastChangeMag < TOLERANCE : s.iterations == EnsembleSolver.MAX_ITERATIONS);
        assertTrue(Double.isFinite(s.lastChangeMag));
    }

    /** Reaching the iteration limit is reported and still yields a usable solution. */
    @Test
    public void theIterationLimitIsReportedNotFailed() {
        Ensemble e = gradient(false);
        EnsembleSolver.Solution capped = e.solve(true, 2, TOLERANCE);

        assertFalse(capped.converged);
        assertEquals(2, capped.iterations);
        assertTrue(capped.lastChangeMag >= TOLERANCE);
        for (int j = 0; j < FRAMES; j++) {
            assertTrue(capped.frameSolved[j]);
            assertTrue(Double.isFinite(capped.residual[0][j]));
        }
    }
}
