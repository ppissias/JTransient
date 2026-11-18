package io.github.ppissias.jtransient.core;

import org.junit.Test;

import java.util.Arrays;
import java.util.Random;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Verifies {@link JavaCoarseSearch} stays bit-identical to the original inline strip loop and unrolled adder. */
public class JavaCoarseSearchTest {

    private static final CoarseControl NEVER_STOP = new CoarseControl() {
        @Override public boolean pastDeadline() { return false; }
        @Override public void checkCancelled() { }
        @Override public void progress(long done, long total, int sampleMotionIndex) { }
    };

    @Test
    public void matchesFrozenReferenceLoopBitForBitAcrossFrameRemaindersAndEdgeCases() {
        Random rng = new Random(1234567L);
        for (int gCount : new int[]{8, 9, 10, 11, 41}) {
            int cw = 37;
            int ch = 29;
            int margin = 18;
            int stride = cw + 2 * margin;
            int rows = ch + 2 * margin;
            float[][] planes = new float[gCount][stride * rows];
            for (int g = 0; g < gCount; g++) {
                for (int i = 0; i < planes[g].length; i++) {
                    planes[g][i] = (rng.nextFloat() - 0.5f) * 4.0f;
                }
                planes[g][margin * stride + margin] = (g % 2 == 0) ? 0.0f : -0.0f;
                planes[g][margin * stride + margin + 1] = (g % 2 == 0) ? 1e-40f : -1e-40f;
            }
            int motions = 73;
            int[][] sx = new int[motions][gCount];
            int[][] sy = new int[motions][gCount];
            for (int m = 0; m < motions; m++) {
                if (m >= 20 && m <= 23) {
                    Arrays.fill(sx[m], 2);
                    Arrays.fill(sy[m], -3);
                } else {
                    for (int g = 0; g < gCount; g++) {
                        sx[m][g] = rng.nextInt(17) - 8;
                        sy[m][g] = rng.nextInt(17) - 8;
                    }
                }
            }
            float scale = 1.0f / (gCount * 1.25f);

            float[] refBest = new float[cw * ch];
            Arrays.fill(refBest, Float.NEGATIVE_INFINITY);
            int[] refMotion = new int[cw * ch];
            frozenReferenceSearch(planes, sx, sy, stride, margin, cw, ch, scale, refBest, refMotion);

            float[] backendBest = new float[cw * ch];
            Arrays.fill(backendBest, Float.NEGATIVE_INFINITY);
            int[] backendMotion = new int[cw * ch];
            JavaCoarseSearch backend = new JavaCoarseSearch();
            backend.beginRun(sx, sy);
            assertTrue(backend.search(new CoarseTileJob(planes, stride, margin, cw, ch, scale,
                    backendBest, backendMotion), NEVER_STOP));
            backend.endRun();

            for (int i = 0; i < refBest.length; i++) {
                assertEquals("bit mismatch at pixel " + i,
                        Float.floatToRawIntBits(refBest[i]), Float.floatToRawIntBits(backendBest[i]));
            }
            assertArrayEquals(refMotion, backendMotion);
        }
    }

    @Test
    public void stopsCooperativelyWhenDeadlinePasses() {
        float[][] planes = new float[8][60 * 60];
        int[][] sx = new int[5][8];
        int[][] sy = new int[5][8];
        float[] best = new float[20 * 20];
        Arrays.fill(best, Float.NEGATIVE_INFINITY);
        int[] bestMotion = new int[20 * 20];
        JavaCoarseSearch backend = new JavaCoarseSearch();
        backend.beginRun(sx, sy);
        assertFalse(backend.search(new CoarseTileJob(planes, 60, 10, 20, 20, 0.1f, best, bestMotion),
                new CoarseControl() {
                    @Override public boolean pastDeadline() { return true; }
                    @Override public void checkCancelled() { }
                    @Override public void progress(long done, long total, int sampleMotionIndex) { }
                }));
    }

    /** Frozen verbatim copy of the pre-refactor FaintMoverSearch coarse loop and addCoarseFrames. */
    private static void frozenReferenceSearch(float[][] coarseFrames, int[][] sx, int[][] sy, int stride, int margin,
                                              int cw, int ch, float scale, float[] best, int[] bestMotion) {
        final int stripRows = 16;
        float[] acc = new float[stripRows * cw];
        for (int s0 = 0; s0 < ch; s0 += stripRows) {
            int rows = Math.min(stripRows, ch - s0);
            for (int mi = 0; mi < sx.length; mi++) {
                Arrays.fill(acc, 0, rows * cw, 0f);
                frozenAddCoarseFrames(coarseFrames, sx[mi], sy[mi], stride, margin, s0, rows, cw, acc);
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
    }

    private static void frozenAddCoarseFrames(float[][] frames, int[] sx, int[] sy, int stride, int margin, int s0,
                                              int rows, int w, float[] acc) {
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
