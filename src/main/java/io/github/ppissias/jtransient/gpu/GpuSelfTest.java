/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 * author: Petros Pissias <petrospis at gmail.com>
 *
 */
package io.github.ppissias.jtransient.gpu;

import io.github.ppissias.jtransient.core.CoarseBackendException;
import io.github.ppissias.jtransient.core.CoarseControl;
import io.github.ppissias.jtransient.core.CoarseTileJob;
import io.github.ppissias.jtransient.core.JavaCoarseSearch;

import java.util.Arrays;
import java.util.Random;

/**
 * Verifies bit-for-bit parity between {@link OpenClCoarseSearch} and {@link JavaCoarseSearch} on every OpenCL context
 * open, exercising non-multiple tile/motion counts, wide-window global fallback, ties, {@code ±0.0f}, and subnormals.
 */
final class GpuSelfTest {

    private static final int CW = 67;
    private static final int CH = 53;
    private static final int PLANES = 40;
    private static final int MARGIN = 84;
    private static final int MOTIONS = 300;
    private static final float SCALE = 0.025f;

    private static final CoarseControl NOOP_CONTROL = new CoarseControl() {
        @Override public boolean pastDeadline() { return false; }
        @Override public void checkCancelled() { }
        @Override public void progress(long done, long total, int sampleMotionIndex) { }
    };

    private GpuSelfTest() {
    }

    /**
     * Runs the parity check on {@code gpu} against {@link JavaCoarseSearch} and throws
     * {@link CoarseBackendException} with {@link CoarseBackendException.Kind#SELF_TEST} on any differing bit or motion.
     */
    static void run(OpenClCoarseSearch gpu) throws CoarseBackendException {
        Random rng = new Random(0x5EED_CAFE_2026L);
        int stride = CW + 2 * MARGIN;
        int rows = CH + 2 * MARGIN;
        int planeSize = stride * rows;

        float[][] planes = new float[PLANES][planeSize];
        for (int g = 0; g < PLANES; g++) {
            float[] p = planes[g];
            for (int i = 0; i < p.length; i++) {
                p[i] = (rng.nextFloat() - 0.5f) * 4.0f;
            }
        }
        // Inject edge-case floating-point patterns: exact zeros, -0.0f, subnormals, and repeated tie regions.
        for (int g = 0; g < PLANES; g++) {
            planes[g][MARGIN * stride + MARGIN] = (g % 2 == 0) ? 0.0f : -0.0f;
            planes[g][MARGIN * stride + MARGIN + 1] = (g % 2 == 0) ? 1e-40f : -1e-40f;
            planes[g][MARGIN * stride + MARGIN + 2] = 0.25f;
        }

        int[][] sx = new int[MOTIONS][PLANES];
        int[][] sy = new int[MOTIONS][PLANES];
        for (int m = 0; m < MOTIONS; m++) {
            if (m < 32) {
                // Force a wide-window block (ww * wh > LMAX) to verify the global fallback path inside coarse_blocked.
                int dx = (m % 2 == 0 ? 75 : -75);
                int dy = (m % 3 == 0 ? 75 : -75);
                for (int g = 0; g < PLANES; g++) {
                    sx[m][g] = dx;
                    sy[m][g] = dy;
                }
            } else if (m >= 100 && m < 105) {
                // Identical shifts across multiple motions to test ascending-index tie-breaking.
                for (int g = 0; g < PLANES; g++) {
                    sx[m][g] = 3;
                    sy[m][g] = -2;
                }
            } else {
                double vx = rng.nextInt(49) - 24;
                double vy = rng.nextInt(49) - 24;
                for (int g = 0; g < PLANES; g++) {
                    double t = (g - PLANES / 2.0) / (PLANES / 2.0);
                    sx[m][g] = Math.max(-24, Math.min(24, (int) Math.round(vx * t)));
                    sy[m][g] = Math.max(-24, Math.min(24, (int) Math.round(vy * t)));
                }
            }
        }

        float[] javaBest = new float[CW * CH];
        Arrays.fill(javaBest, Float.NEGATIVE_INFINITY);
        int[] javaMotion = new int[CW * CH];
        JavaCoarseSearch java = new JavaCoarseSearch();
        java.beginRun(sx, sy);
        java.search(new CoarseTileJob(planes, stride, MARGIN, CW, CH, SCALE, javaBest, javaMotion), NOOP_CONTROL);
        java.endRun();

        verifyBackend(gpu, planes, stride, sx, sy, javaBest, javaMotion);
        if (!gpu.contextManager().device().simpleOnly()) {
            gpu.setForceSimpleForTest(true);
            try {
                verifyBackend(gpu, planes, stride, sx, sy, javaBest, javaMotion);
            } finally {
                gpu.setForceSimpleForTest(false);
            }
        }
    }

    private static void verifyBackend(OpenClCoarseSearch gpu, float[][] planes, int stride,
                                      int[][] sx, int[][] sy, float[] javaBest, int[] javaMotion)
            throws CoarseBackendException {
        float[] gpuBest = new float[CW * CH];
        Arrays.fill(gpuBest, Float.NEGATIVE_INFINITY);
        int[] gpuMotion = new int[CW * CH];
        gpu.beginRun(sx, sy);
        try {
            boolean done = gpu.search(new CoarseTileJob(planes, stride, MARGIN, CW, CH, SCALE, gpuBest, gpuMotion), NOOP_CONTROL);
            if (!done) {
                throw new CoarseBackendException(CoarseBackendException.Kind.SELF_TEST,
                        "GPU self-test stopped unexpectedly");
            }
        } finally {
            gpu.endRun();
        }
        for (int i = 0; i < javaBest.length; i++) {
            if (Float.floatToRawIntBits(javaBest[i]) != Float.floatToRawIntBits(gpuBest[i])
                    || javaMotion[i] != gpuMotion[i]) {
                throw new CoarseBackendException(CoarseBackendException.Kind.SELF_TEST,
                        "GPU result differs from Java at pixel " + i
                                + " (java=" + javaBest[i] + "/m" + javaMotion[i]
                                + ", gpu=" + gpuBest[i] + "/m" + gpuMotion[i] + ")");
            }
        }
    }
}
