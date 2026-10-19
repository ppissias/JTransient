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

import java.util.ArrayList;
import java.util.List;

/**
 * How registration moved one frame: a shift and a rotation, measured from the frame's registration fill.
 *
 * <p>Registration moves the camera's rectangle of pixels; the fill marks where the rectangle no longer reaches, so the
 * edges of the fill are the rectangle's sides. A camera pixel (u, v) lands at
 * {@code x = tx + u cos(theta) - v sin(theta)}, {@code y = ty + u sin(theta) + v cos(theta)}. The fit uses the first
 * and last data pixel of each row and column ({@link FrameCoverage}), away from the corners, and drops points more than
 * {@link #OUTLIER_PX} off the fitted sides.</p>
 *
 * <p>Only the sides that show fill are measured. Without fill on the left and the right the horizontal shift is taken
 * as 0 (the frame moved less than a pixel that way), likewise the vertical shift without fill on the top and the
 * bottom, and the rotation with fewer than {@link #MIN_EDGE_POINTS} edge points. A frame without fill keeps the
 * camera's position. A frame turned by 180 degrees (a meridian flip) leaves the same fill as one that is not turned,
 * so its camera positions come out mirrored: frames of one pier side only match frames of the same side.</p>
 */
public final class FrameRegistration {
    /** Fewest edge points on an axis (or in all) for its shift (or the rotation) to be measured. */
    static final int MIN_EDGE_POINTS = 50;
    /** Edge points this far off the fitted sides are left out, in pixels. */
    static final double OUTLIER_PX = 2.0;
    /** Above this fit residual (rms, pixels) the fill is not a moved rectangle and the frame is not usable. */
    static final double MAX_RMS_PX = 2.0;
    private static final int ITERATIONS = 20;

    /** The frame's sequence index. */
    public final int sequenceIndex;
    /** Where the camera pixel (0, 0) landed. */
    public final double tx;
    /** Where the camera pixel (0, 0) landed. */
    public final double ty;
    /** Rotation of the camera's rectangle, in radians. */
    public final double theta;
    /** Residual of the fit over the edge points kept, in pixels (0 without fill). */
    public final double rmsPx;
    /** False when the fill did not fit a moved rectangle; the frame's camera positions are then unknown. */
    public final boolean usable;

    private FrameRegistration(int sequenceIndex, double tx, double ty, double theta, double rmsPx, boolean usable) {
        this.sequenceIndex = sequenceIndex;
        this.tx = tx;
        this.ty = ty;
        this.theta = theta;
        this.rmsPx = rmsPx;
        this.usable = usable;
    }

    /** A registration with a known shift and rotation (tests, callers with their own measurement). */
    public static FrameRegistration of(int sequenceIndex, double tx, double ty, double theta) {
        return new FrameRegistration(sequenceIndex, tx, ty, theta, 0.0, true);
    }

    /**
     * Camera position of a pixel of this frame, as {@code {u, v}}.
     *
     * @param x frame x
     * @param y frame y
     */
    public double[] toCamera(double x, double y) {
        double c = Math.cos(theta);
        double s = Math.sin(theta);
        double dx = x - tx;
        double dy = y - ty;
        return new double[]{c * dx + s * dy, -s * dx + c * dy};
    }

    /** Fits the shift and rotation to the edges of a frame's fill. */
    public static FrameRegistration fit(FrameCoverage coverage) {
        int width = coverage.getWidth();
        int height = coverage.getHeight();
        // Each point: {side, x, y}; sides 0 left, 1 right (camera u = 0 or width - 1), 2 top, 3 bottom (v = 0 or
        // height - 1). Rows and columns near the corners are left out, where a row's first data pixel can lie on the
        // neighbouring side.
        List<double[]> points = new ArrayList<>();
        int vertical = 0;
        int horizontal = 0;
        for (int y = height / 30; y < height - height / 30; y++) {
            int start = coverage.rowStart[y];
            int end = coverage.rowEnd[y];
            if (start >= width) {
                continue;
            }
            if (start > 0 && start < width / 3) {
                points.add(new double[]{0, start, y});
                vertical++;
            }
            if (end < width - 1 && end > 2 * width / 3) {
                points.add(new double[]{1, end, y});
                vertical++;
            }
        }
        for (int x = width / 30; x < width - width / 30; x++) {
            int start = coverage.columnStart[x];
            int end = coverage.columnEnd[x];
            if (start >= height) {
                continue;
            }
            if (start > 0 && start < height / 3) {
                points.add(new double[]{2, x, start});
                horizontal++;
            }
            if (end < height - 1 && end > 2 * height / 3) {
                points.add(new double[]{3, x, end});
                horizontal++;
            }
        }
        int index = coverage.getSequenceIndex();
        if (points.isEmpty()) {
            return new FrameRegistration(index, 0.0, 0.0, 0.0, 0.0, true);
        }
        boolean[] free = {vertical >= MIN_EDGE_POINTS, horizontal >= MIN_EDGE_POINTS, points.size() >= MIN_EDGE_POINTS};
        double[] p = new double[3];
        List<double[]> kept = points;
        for (int round = 0; round < 5; round++) {
            solve(kept, p, free, width, height);
            List<double[]> inliers = new ArrayList<>();
            for (double[] point : points) {
                if (Math.abs(residual(point, p, width, height)) <= OUTLIER_PX) {
                    inliers.add(point);
                }
            }
            if (inliers.size() == kept.size() || inliers.size() < MIN_EDGE_POINTS) {
                kept = inliers.size() < MIN_EDGE_POINTS ? kept : inliers;
                break;
            }
            kept = inliers;
        }
        double sum = 0;
        for (double[] point : kept) {
            double r = residual(point, p, width, height);
            sum += r * r;
        }
        double rms = Math.sqrt(sum / kept.size());
        boolean usable = rms <= MAX_RMS_PX && kept.size() * 2 >= points.size();
        return new FrameRegistration(index, p[0], p[1], p[2], rms, usable);
    }

    /** Distance of an edge point from its side of the moved rectangle (camera u or v minus the side's value). */
    private static double residual(double[] point, double[] p, int width, int height) {
        double c = Math.cos(p[2]);
        double s = Math.sin(p[2]);
        double dx = point[1] - p[0];
        double dy = point[2] - p[1];
        int side = (int) point[0];
        if (side < 2) {
            return c * dx + s * dy - (side == 0 ? 0 : width - 1);
        }
        return -s * dx + c * dy - (side == 2 ? 0 : height - 1);
    }

    /** Gauss-Newton on (tx, ty, theta), the parameters that are not free kept at their value (0). */
    private static void solve(List<double[]> points, double[] p, boolean[] free, int width, int height) {
        for (int iteration = 0; iteration < ITERATIONS; iteration++) {
            double[][] a = new double[3][3];
            double[] b = new double[3];
            double c = Math.cos(p[2]);
            double s = Math.sin(p[2]);
            for (double[] point : points) {
                double dx = point[1] - p[0];
                double dy = point[2] - p[1];
                double[] j = point[0] < 2
                        ? new double[]{-c, -s, -s * dx + c * dy}
                        : new double[]{s, -c, -c * dx - s * dy};
                double r = residual(point, p, width, height);
                for (int k = 0; k < 3; k++) {
                    if (!free[k]) {
                        continue;
                    }
                    b[k] -= j[k] * r;
                    for (int l = 0; l < 3; l++) {
                        if (free[l]) {
                            a[k][l] += j[k] * j[l];
                        }
                    }
                }
            }
            for (int k = 0; k < 3; k++) {
                if (!free[k]) {
                    a[k][k] = 1.0;
                    b[k] = 0.0;
                }
            }
            double[] step = gauss(a, b);
            if (step == null) {
                return;
            }
            double size = 0;
            for (int k = 0; k < 3; k++) {
                p[k] += step[k];
                size = Math.max(size, Math.abs(step[k]) * (k == 2 ? 1000 : 1));
            }
            if (size < 1e-4) {
                return;
            }
        }
    }

    private static double[] gauss(double[][] a, double[] b) {
        int n = b.length;
        double[][] m = new double[n][n + 1];
        for (int i = 0; i < n; i++) {
            System.arraycopy(a[i], 0, m[i], 0, n);
            m[i][n] = b[i];
        }
        for (int col = 0; col < n; col++) {
            int pivot = col;
            for (int r = col + 1; r < n; r++) {
                if (Math.abs(m[r][col]) > Math.abs(m[pivot][col])) {
                    pivot = r;
                }
            }
            if (Math.abs(m[pivot][col]) < 1e-12) {
                return null;
            }
            double[] swap = m[col];
            m[col] = m[pivot];
            m[pivot] = swap;
            for (int r = 0; r < n; r++) {
                if (r != col) {
                    double f = m[r][col] / m[col][col];
                    for (int k = col; k <= n; k++) {
                        m[r][k] -= f * m[col][k];
                    }
                }
            }
        }
        double[] x = new double[n];
        for (int i = 0; i < n; i++) {
            x[i] = m[i][n] / m[i][i];
        }
        return x;
    }
}
