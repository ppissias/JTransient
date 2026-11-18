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

import java.util.Arrays;

/**
 * Default CPU {@link CoarseSearchBackend} executing the reference {@link FaintMoverSearch} coarse strip loop and
 * 4-frame unrolled summation verbatim.
 */
public final class JavaCoarseSearch implements CoarseSearchBackend {

    /** Rows of a tile added together for every motion, so the rows read stay in the processor's cache. */
    private static final int STRIP_ROWS = 16;

    private volatile int[][] coarseShiftX;
    private volatile int[][] coarseShiftY;

    /** Creates the default Java coarse-search backend. */
    public JavaCoarseSearch() {
    }

    /** {@inheritDoc} */
    @Override
    public String description() {
        return "Java";
    }

    /** {@inheritDoc} */
    @Override
    public void beginRun(int[][] coarseShiftX, int[][] coarseShiftY) {
        this.coarseShiftX = coarseShiftX;
        this.coarseShiftY = coarseShiftY;
    }

    /** {@inheritDoc} */
    @Override
    public boolean search(CoarseTileJob job, CoarseControl control) {
        int[][] sx = this.coarseShiftX;
        int[][] sy = this.coarseShiftY;
        if (sx == null || sy == null) {
            throw new IllegalStateException("beginRun must be called before search.");
        }
        float[][] coarseFrames = job.planes();
        int stride = job.stride();
        int margin = job.margin();
        int cw = job.width();
        int ch = job.height();
        float scale = job.scale();
        float[] best = job.best();
        int[] bestMotion = job.bestMotion();
        int motionCount = sx.length;

        float[] acc = new float[STRIP_ROWS * cw];
        long totalUnits = ((ch + STRIP_ROWS - 1L) / STRIP_ROWS) * motionCount;
        for (int s0 = 0; s0 < ch; s0 += STRIP_ROWS) {
            int rows = Math.min(STRIP_ROWS, ch - s0);
            if (control.pastDeadline()) {
                return false;
            }
            long stripBase = (long) (s0 / STRIP_ROWS) * motionCount;
            for (int mi = 0; mi < motionCount; mi++) {
                control.checkCancelled();
                if ((mi & 63) == 0) {
                    control.progress(stripBase + mi, totalUnits, mi);
                }
                Arrays.fill(acc, 0, rows * cw, 0f);
                addCoarseFrames(coarseFrames, sx[mi], sy[mi], stride, margin, s0, rows, cw, acc);
                int base = s0 * cw;
                for (int i = 0; i < rows * cw; i++) {
                    float value = acc[i] * scale;
                    if (value > best[base + i]) {
                        best[base + i] = value;
                        bestMotion[base + i] = mi;
                    }
                }
            }
        }
        return true;
    }

    /** {@inheritDoc} */
    @Override
    public void endRun() {
        this.coarseShiftX = null;
        this.coarseShiftY = null;
    }

    /** {@inheritDoc} */
    @Override
    public void close() {
        endRun();
    }

    /**
     * Adds the coarse frames, shifted along one motion, into {@code acc} for a strip of coarse rows, four frames per
     * pass over {@code acc}. Each sum is formed frame by frame in order, exactly as when adding them one at a time.
     */
    private static void addCoarseFrames(float[][] frames, int[] sx, int[] sy, int stride, int margin, int s0, int rows,
                                        int w, float[] acc) {
        int n = frames.length;
        int k = 0;
        for (; k + 4 <= n; k += 4) {
            float[] r0 = frames[k];
            float[] r1 = frames[k + 1];
            float[] r2 = frames[k + 2];
            float[] r3 = frames[k + 3];
            for (int row = 0; row < rows; row++) {
                int line = (s0 + row + margin) * stride + margin;
                int a0 = line + sy[k] * stride + sx[k];
                int a1 = line + sy[k + 1] * stride + sx[k + 1];
                int a2 = line + sy[k + 2] * stride + sx[k + 2];
                int a3 = line + sy[k + 3] * stride + sx[k + 3];
                int dst = row * w;
                for (int x = 0; x < w; x++) {
                    float sum = acc[dst + x];
                    sum += r0[a0 + x];
                    sum += r1[a1 + x];
                    sum += r2[a2 + x];
                    sum += r3[a3 + x];
                    acc[dst + x] = sum;
                }
            }
        }
        for (; k < n; k++) {
            float[] r = frames[k];
            for (int row = 0; row < rows; row++) {
                int src = (s0 + row + margin + sy[k]) * stride + margin + sx[k];
                int dst = row * w;
                for (int x = 0; x < w; x++) {
                    acc[dst + x] += r[src + x];
                }
            }
        }
    }
}
