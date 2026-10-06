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
 * Small robust-statistics helpers shared by the photometry stages.
 * All methods ignore non-finite values.
 */
final class PhotometryMath {

    /** Converts a median absolute deviation into a Gaussian-equivalent sigma. */
    static final double MAD_TO_SIGMA = 1.4826;

    private PhotometryMath() {
    }

    /**
     * Returns the finite values of the input, sorted ascending.
     */
    static double[] finiteSorted(double[] values, int length) {
        double[] out = new double[length];
        int n = 0;
        for (int i = 0; i < length; i++) {
            if (Double.isFinite(values[i])) {
                out[n++] = values[i];
            }
        }
        double[] trimmed = Arrays.copyOf(out, n);
        Arrays.sort(trimmed);
        return trimmed;
    }

    static double median(double[] values) {
        return median(values, values.length);
    }

    static double median(double[] values, int length) {
        double[] sorted = finiteSorted(values, length);
        return medianOfSorted(sorted);
    }

    static double medianOfSorted(double[] sorted) {
        int n = sorted.length;
        if (n == 0) {
            return Double.NaN;
        }
        return (n % 2 == 1) ? sorted[n / 2] : 0.5 * (sorted[n / 2 - 1] + sorted[n / 2]);
    }

    /**
     * Linear-interpolated percentile of already sorted values, with {@code p} in [0, 1].
     */
    static double percentileOfSorted(double[] sorted, double p) {
        int n = sorted.length;
        if (n == 0) {
            return Double.NaN;
        }
        double pos = p * (n - 1);
        int lo = (int) Math.floor(pos);
        int hi = Math.min(n - 1, lo + 1);
        double frac = pos - lo;
        return sorted[lo] * (1.0 - frac) + sorted[hi] * frac;
    }

    static double percentile(double[] values, double p) {
        return percentileOfSorted(finiteSorted(values, values.length), p);
    }

    /**
     * Robust sigma (1.4826 x MAD) of the finite values.
     */
    static double robustSigma(double[] values) {
        return robustSigma(values, values.length);
    }

    static double robustSigma(double[] values, int length) {
        double[] sorted = finiteSorted(values, length);
        if (sorted.length < 2) {
            return Double.NaN;
        }
        double med = medianOfSorted(sorted);
        double[] dev = new double[sorted.length];
        for (int i = 0; i < sorted.length; i++) {
            dev[i] = Math.abs(sorted[i] - med);
        }
        Arrays.sort(dev);
        return MAD_TO_SIGMA * medianOfSorted(dev);
    }

    /**
     * Median of the first {@code n} values in linear expected time. Reorders those values;
     * they must all be finite.
     */
    static double medianInPlace(double[] a, int n) {
        if (n == 0) {
            return Double.NaN;
        }
        int k = n / 2;
        double upper = selectInPlace(a, n, k);
        if (n % 2 == 1) {
            return upper;
        }
        // After selection every value left of k is <= a[k]; the lower middle is their maximum.
        double lower = a[0];
        for (int i = 1; i < k; i++) {
            if (a[i] > lower) {
                lower = a[i];
            }
        }
        return 0.5 * (lower + upper);
    }

    /**
     * Hoare quickselect: places the k-th smallest of the first {@code n} values at index k and returns it.
     */
    static double selectInPlace(double[] a, int n, int k) {
        int lo = 0;
        int hi = n - 1;
        while (lo < hi) {
            double pivot = a[(lo + hi) >>> 1];
            int i = lo;
            int j = hi;
            while (i <= j) {
                while (a[i] < pivot) i++;
                while (a[j] > pivot) j--;
                if (i <= j) {
                    double t = a[i];
                    a[i] = a[j];
                    a[j] = t;
                    i++;
                    j--;
                }
            }
            if (k <= j) {
                hi = j;
            } else if (k >= i) {
                lo = i;
            } else {
                break;
            }
        }
        return a[k];
    }

    /**
     * Sample standard deviation of the finite values.
     */
    static double std(double[] values) {
        double sum = 0;
        int n = 0;
        for (double v : values) {
            if (Double.isFinite(v)) {
                sum += v;
                n++;
            }
        }
        if (n < 2) {
            return Double.NaN;
        }
        double mean = sum / n;
        double ss = 0;
        for (double v : values) {
            if (Double.isFinite(v)) {
                ss += (v - mean) * (v - mean);
            }
        }
        return Math.sqrt(ss / (n - 1));
    }

    /**
     * Pearson correlation over the index positions where both arrays are finite.
     *
     * @return correlation, or NaN when fewer than three pairs exist or a series is constant
     */
    static double correlation(double[] a, double[] b) {
        int n = 0;
        double sa = 0;
        double sb = 0;
        for (int i = 0; i < a.length; i++) {
            if (Double.isFinite(a[i]) && Double.isFinite(b[i])) {
                sa += a[i];
                sb += b[i];
                n++;
            }
        }
        if (n < 3) {
            return Double.NaN;
        }
        double ma = sa / n;
        double mb = sb / n;
        double cov = 0;
        double va = 0;
        double vb = 0;
        for (int i = 0; i < a.length; i++) {
            if (Double.isFinite(a[i]) && Double.isFinite(b[i])) {
                double da = a[i] - ma;
                double db = b[i] - mb;
                cov += da * db;
                va += da * da;
                vb += db * db;
            }
        }
        if (va <= 0 || vb <= 0) {
            return Double.NaN;
        }
        return cov / Math.sqrt(va * vb);
    }

    /**
     * For every element, the median and robust sigma of {@code value} over the {@code k} elements
     * nearest in {@code key} (by rank), excluding the element itself. Elements with a non-finite
     * key or value get NaN and are not used as neighbours.
     *
     * @return {@code [medians, robustSigmas]}
     */
    static double[][] neighbourStats(double[] key, double[] value, int k) {
        int n = key.length;
        double[] medians = new double[n];
        double[] sigmas = new double[n];
        Arrays.fill(medians, Double.NaN);
        Arrays.fill(sigmas, Double.NaN);

        Integer[] order = new Integer[n];
        int valid = 0;
        for (int i = 0; i < n; i++) {
            if (Double.isFinite(key[i]) && Double.isFinite(value[i])) {
                order[valid++] = i;
            }
        }
        if (valid < 2) {
            return new double[][]{medians, sigmas};
        }
        Integer[] sorted = Arrays.copyOf(order, valid);
        Arrays.sort(sorted, (a, b) -> Double.compare(key[a], key[b]));

        int window = Math.min(k, valid - 1);
        double[] buffer = new double[window];
        for (int rank = 0; rank < valid; rank++) {
            int lo = rank - window / 2;
            lo = Math.max(0, Math.min(lo, valid - 1 - window));
            int count = 0;
            for (int r = lo; r <= lo + window && count < window; r++) {
                if (r != rank) {
                    buffer[count++] = value[sorted[r]];
                }
            }
            int index = sorted[rank];
            medians[index] = median(buffer, count);
            sigmas[index] = robustSigma(buffer, count);
        }
        return new double[][]{medians, sigmas};
    }

    /**
     * Weighted least-squares straight line {@code y = intercept + slope * x}.
     *
     * @return {@code [intercept, slope, slopeStandardError]}, or NaNs when underdetermined
     */
    static double[] weightedLine(double[] x, double[] y, double[] w) {
        double sw = 0;
        double sx = 0;
        double sy = 0;
        int n = 0;
        for (int i = 0; i < x.length; i++) {
            if (w[i] > 0 && Double.isFinite(x[i]) && Double.isFinite(y[i])) {
                sw += w[i];
                sx += w[i] * x[i];
                sy += w[i] * y[i];
                n++;
            }
        }
        if (n < 3 || sw <= 0) {
            return new double[]{Double.NaN, Double.NaN, Double.NaN};
        }
        double mx = sx / sw;
        double my = sy / sw;
        double sxx = 0;
        double sxy = 0;
        for (int i = 0; i < x.length; i++) {
            if (w[i] > 0 && Double.isFinite(x[i]) && Double.isFinite(y[i])) {
                sxx += w[i] * (x[i] - mx) * (x[i] - mx);
                sxy += w[i] * (x[i] - mx) * (y[i] - my);
            }
        }
        if (sxx <= 0) {
            return new double[]{my, Double.NaN, Double.NaN};
        }
        double slope = sxy / sxx;
        double intercept = my - slope * mx;
        double rss = 0;
        for (int i = 0; i < x.length; i++) {
            if (w[i] > 0 && Double.isFinite(x[i]) && Double.isFinite(y[i])) {
                double r = y[i] - intercept - slope * x[i];
                rss += w[i] * r * r;
            }
        }
        // Scale the formal error by the observed residual variance, since weights are only relative.
        double residualVariance = rss / (n - 2);
        double slopeError = Math.sqrt(residualVariance / sxx);
        return new double[]{intercept, slope, slopeError};
    }

    /**
     * Solves a 3x3 linear system with Gaussian elimination and partial pivoting.
     *
     * @return solution, or null when the system is singular
     */
    static double[] solve3(double[][] a, double[] b) {
        double[][] m = new double[3][4];
        for (int i = 0; i < 3; i++) {
            System.arraycopy(a[i], 0, m[i], 0, 3);
            m[i][3] = b[i];
        }
        for (int col = 0; col < 3; col++) {
            int pivot = col;
            for (int r = col + 1; r < 3; r++) {
                if (Math.abs(m[r][col]) > Math.abs(m[pivot][col])) {
                    pivot = r;
                }
            }
            if (Math.abs(m[pivot][col]) < 1e-12) {
                return null;
            }
            double[] tmp = m[col];
            m[col] = m[pivot];
            m[pivot] = tmp;
            for (int r = 0; r < 3; r++) {
                if (r != col) {
                    double f = m[r][col] / m[col][col];
                    for (int c = col; c < 4; c++) {
                        m[r][c] -= f * m[col][c];
                    }
                }
            }
        }
        return new double[]{m[0][3] / m[0][0], m[1][3] / m[1][1], m[2][3] / m[2][2]};
    }
}
