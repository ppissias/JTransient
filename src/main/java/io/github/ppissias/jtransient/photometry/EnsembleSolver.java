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

import java.util.Arrays;

/**
 * Inhomogeneous ensemble photometry (Honeycutt 1992) solved by iteratively reweighted least squares.
 *
 * <p>Model: {@code m(i,j) = M(i) + Z(j) + a(j)*x(i) + b(j)*y(i)}. M is each star's mean magnitude,
 * Z the frame zero point and the optional plane term absorbs gradients that change between frames.
 * Every star with large scatter for its brightness is down-weighted, so variable stars drop out
 * of the reference by themselves.</p>
 */
final class EnsembleSolver {

    /** Fewest usable stars for a frame to be solved. */
    static final int MIN_STARS_PER_FRAME = 10;
    /** Fewest usable stars for the plane term; below this only the zero point is fitted. */
    private static final int MIN_STARS_FOR_PLANE = 30;
    private static final int MAX_ITERATIONS = 15;
    private static final double CONVERGENCE_MAG = 1e-5;
    /** Smallest per-star sigma used in weights, in mag. */
    private static final double SIGMA_FLOOR = 0.001;
    /** Stars of similar magnitude used for the expected-scatter curve in the weights. */
    private static final int NOISE_NEIGHBOURS = 50;
    /** Cauchy weight scale, in units of the star's effective sigma. */
    private static final double CAUCHY_SCALE = 3.0;

    /** Ensemble solution. */
    static final class Solution {
        double[] starMag;
        double[] zeroPoint;
        double[] planeX;
        double[] planeY;
        boolean[] frameSolved;
        int[] starsPerFrame;
        /** Residual m - model for every finite measurement on a solved frame, NaN otherwise. [star][frame] */
        double[][] residual;
        /** Effective per-star sigma used for weighting (max of own and expected robust scatter). */
        double[] starSigma;
    }

    private EnsembleSolver() {
    }

    /**
     * Solves the ensemble.
     *
     * @param mag instrumental magnitudes [star][frame], NaN when not measured
     * @param usable whether each measurement may constrain the fit [star][frame]
     * @param x normalized star x positions in [-1, 1]
     * @param y normalized star y positions in [-1, 1]
     * @param frameActive frames allowed in the solve
     * @param fitPlane whether to fit the per-frame plane term
     */
    static Solution solve(double[][] mag, boolean[][] usable, double[] x, double[] y,
                          boolean[] frameActive, boolean fitPlane) {
        int nStars = mag.length;
        int nFrames = frameActive.length;

        Solution s = new Solution();
        s.starMag = new double[nStars];
        s.zeroPoint = new double[nFrames];
        s.planeX = new double[nFrames];
        s.planeY = new double[nFrames];
        s.frameSolved = new boolean[nFrames];
        s.starsPerFrame = new int[nFrames];
        s.starSigma = new double[nStars];
        double[][] pointWeight = new double[nStars][nFrames];

        for (int i = 0; i < nStars; i++) {
            double[] row = new double[nFrames];
            for (int j = 0; j < nFrames; j++) {
                row[j] = (usable[i][j] && frameActive[j]) ? mag[i][j] : Double.NaN;
                pointWeight[i][j] = 1.0;
            }
            s.starMag[i] = PhotometryMath.median(row);
            double sigma = PhotometryMath.robustSigma(row);
            s.starSigma[i] = Double.isFinite(sigma) ? Math.max(SIGMA_FLOOR, sigma) : Double.NaN;
        }

        double[] previousZ = new double[nFrames];
        for (int iter = 0; iter < MAX_ITERATIONS; iter++) {
            solveFrames(mag, usable, x, y, frameActive, fitPlane, s, pointWeight);
            fixGauge(s);
            solveStars(mag, usable, x, y, s, pointWeight);
            updateWeights(mag, usable, x, y, s, pointWeight);

            double maxChange = 0;
            for (int j = 0; j < nFrames; j++) {
                if (s.frameSolved[j]) {
                    maxChange = Math.max(maxChange, Math.abs(s.zeroPoint[j] - previousZ[j]));
                }
            }
            System.arraycopy(s.zeroPoint, 0, previousZ, 0, nFrames);
            if (iter > 0 && maxChange < CONVERGENCE_MAG) {
                break;
            }
        }

        s.residual = new double[nStars][nFrames];
        for (int i = 0; i < nStars; i++) {
            Arrays.fill(s.residual[i], Double.NaN);
            for (int j = 0; j < nFrames; j++) {
                if (s.frameSolved[j] && Double.isFinite(mag[i][j]) && Double.isFinite(s.starMag[i])) {
                    s.residual[i][j] = mag[i][j] - model(s, i, j, x, y);
                }
            }
        }
        return s;
    }

    static double model(Solution s, int star, int frame, double[] x, double[] y) {
        return s.starMag[star] + s.zeroPoint[frame] + s.planeX[frame] * x[star] + s.planeY[frame] * y[star];
    }

    private static void solveFrames(double[][] mag, boolean[][] usable, double[] x, double[] y,
                                    boolean[] frameActive, boolean fitPlane, Solution s, double[][] pointWeight) {
        int nStars = mag.length;
        for (int j = 0; j < frameActive.length; j++) {
            s.frameSolved[j] = false;
            s.starsPerFrame[j] = 0;
            if (!frameActive[j]) {
                continue;
            }
            double[][] a = new double[3][3];
            double[] b = new double[3];
            int count = 0;
            for (int i = 0; i < nStars; i++) {
                if (!usable[i][j] || !Double.isFinite(mag[i][j]) || !Double.isFinite(s.starMag[i])
                        || !Double.isFinite(s.starSigma[i])) {
                    continue;
                }
                double w = pointWeight[i][j] / (s.starSigma[i] * s.starSigma[i]);
                double r = mag[i][j] - s.starMag[i];
                double[] basis = {1.0, x[i], y[i]};
                for (int p = 0; p < 3; p++) {
                    b[p] += w * basis[p] * r;
                    for (int q = 0; q < 3; q++) {
                        a[p][q] += w * basis[p] * basis[q];
                    }
                }
                count++;
            }
            if (count < MIN_STARS_PER_FRAME || a[0][0] <= 0) {
                continue;
            }
            double[] solution = (fitPlane && count >= MIN_STARS_FOR_PLANE) ? PhotometryMath.solve3(a, b) : null;
            if (solution != null) {
                s.zeroPoint[j] = solution[0];
                s.planeX[j] = solution[1];
                s.planeY[j] = solution[2];
            } else {
                s.zeroPoint[j] = b[0] / a[0][0];
                s.planeX[j] = 0;
                s.planeY[j] = 0;
            }
            s.frameSolved[j] = true;
            s.starsPerFrame[j] = count;
        }
    }

    /**
     * Removes the degeneracy between star magnitudes and frame terms: the median zero point and the
     * mean plane terms are moved into the star magnitudes on the next star solve.
     */
    private static void fixGauge(Solution s) {
        int n = 0;
        double[] zs = new double[s.zeroPoint.length];
        double sumA = 0;
        double sumB = 0;
        for (int j = 0; j < s.zeroPoint.length; j++) {
            if (s.frameSolved[j]) {
                zs[n++] = s.zeroPoint[j];
                sumA += s.planeX[j];
                sumB += s.planeY[j];
            }
        }
        if (n == 0) {
            return;
        }
        double zMedian = PhotometryMath.median(zs, n);
        double meanA = sumA / n;
        double meanB = sumB / n;
        for (int j = 0; j < s.zeroPoint.length; j++) {
            if (s.frameSolved[j]) {
                s.zeroPoint[j] -= zMedian;
                s.planeX[j] -= meanA;
                s.planeY[j] -= meanB;
            }
        }
    }

    private static void solveStars(double[][] mag, boolean[][] usable, double[] x, double[] y,
                                   Solution s, double[][] pointWeight) {
        for (int i = 0; i < mag.length; i++) {
            double sw = 0;
            double sum = 0;
            for (int j = 0; j < s.zeroPoint.length; j++) {
                if (s.frameSolved[j] && usable[i][j] && Double.isFinite(mag[i][j])) {
                    double w = pointWeight[i][j];
                    sum += w * (mag[i][j] - s.zeroPoint[j] - s.planeX[j] * x[i] - s.planeY[j] * y[i]);
                    sw += w;
                }
            }
            s.starMag[i] = sw > 0 ? sum / sw : Double.NaN;
        }
    }

    private static void updateWeights(double[][] mag, boolean[][] usable, double[] x, double[] y,
                                      Solution s, double[][] pointWeight) {
        int nStars = mag.length;
        int nFrames = s.zeroPoint.length;
        double[] ownSigma = new double[nStars];
        double[][] residuals = new double[nStars][nFrames];
        for (int i = 0; i < nStars; i++) {
            for (int j = 0; j < nFrames; j++) {
                residuals[i][j] = (s.frameSolved[j] && usable[i][j] && Double.isFinite(mag[i][j])
                        && Double.isFinite(s.starMag[i]))
                        ? mag[i][j] - model(s, i, j, x, y)
                        : Double.NaN;
            }
            ownSigma[i] = PhotometryMath.robustSigma(residuals[i]);
        }
        double[] expected = PhotometryMath.neighbourStats(s.starMag, ownSigma, NOISE_NEIGHBOURS)[0];
        for (int i = 0; i < nStars; i++) {
            double own = ownSigma[i];
            double exp = expected[i];
            double sigma = Double.isFinite(own) ? own : exp;
            if (Double.isFinite(exp)) {
                sigma = Double.isFinite(sigma) ? Math.max(sigma, exp) : exp;
            }
            s.starSigma[i] = Double.isFinite(sigma) ? Math.max(SIGMA_FLOOR, sigma) : Double.NaN;
            for (int j = 0; j < nFrames; j++) {
                double r = residuals[i][j];
                if (Double.isFinite(r) && Double.isFinite(s.starSigma[i])) {
                    double u = r / (CAUCHY_SCALE * s.starSigma[i]);
                    pointWeight[i][j] = 1.0 / (1.0 + u * u);
                } else {
                    pointWeight[i][j] = 0.0;
                }
            }
        }
    }
}
