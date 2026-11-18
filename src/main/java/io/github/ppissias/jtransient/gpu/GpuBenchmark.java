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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Measures real GPU coarse-pass throughput (including plane upload and readback) against multi-worker CPU
 * {@link JavaCoarseSearch} on a synthetic benchmark tile, shared by {@code Auto} selection and the UI Test GPU action.
 */
final class GpuBenchmark {

    /** Auto mode uses the GPU only when measured speedup meets or exceeds this factor. */
    static final double MIN_SPEEDUP = 2.0;

    private static final int CW = 256;
    private static final int CH = 256;
    private static final int PLANES = 48;
    private static final int MARGIN = 48;
    private static final int GPU_MOTIONS = 2048;
    private static final int CPU_MOTIONS = 512;
    private static final float SCALE = 0.02f;

    private static final CoarseControl NOOP_CONTROL = new CoarseControl() {
        @Override public boolean pastDeadline() { return false; }
        @Override public void checkCancelled() { }
        @Override public void progress(long done, long total, int sampleMotionIndex) { }
    };

    private GpuBenchmark() {
    }

    /**
     * Benchmarks {@code gpu} against {@code cpuWorkers} parallel Java tiles on a synthetic 256x256, 48-plane tile and
     * verifies bit-for-bit equality on the shared motion slice.
     */
    static double measureSpeedup(OpenClCoarseSearch gpu, int cpuWorkers) throws CoarseBackendException {
        int workers = Math.max(1, Math.min(cpuWorkers, Math.max(1, Runtime.getRuntime().availableProcessors())));
        Random rng = new Random(0xBEEF_2026L);
        int stride = CW + 2 * MARGIN;
        int rows = CH + 2 * MARGIN;
        int planeSize = stride * rows;

        float[][] planes = new float[PLANES][planeSize];
        for (int g = 0; g < PLANES; g++) {
            float[] p = planes[g];
            for (int i = 0; i < p.length; i++) {
                p[i] = (rng.nextFloat() - 0.5f) * 3.0f;
            }
        }

        int[][] gpuSx = new int[GPU_MOTIONS][PLANES];
        int[][] gpuSy = new int[GPU_MOTIONS][PLANES];
        for (int m = 0; m < GPU_MOTIONS; m++) {
            double vx = (m % 45) - 22;
            double vy = (m / 45) - 22;
            for (int g = 0; g < PLANES; g++) {
                double t = (g - PLANES / 2.0) / (PLANES / 2.0);
                gpuSx[m][g] = Math.max(-MARGIN + 2, Math.min(MARGIN - 2, (int) Math.round(vx * t)));
                gpuSy[m][g] = Math.max(-MARGIN + 2, Math.min(MARGIN - 2, (int) Math.round(vy * t)));
            }
        }

        int[][] cpuSx = Arrays.copyOf(gpuSx, CPU_MOTIONS);
        int[][] cpuSy = Arrays.copyOf(gpuSy, CPU_MOTIONS);

        // Warm up GPU and verify parity on the CPU_MOTIONS subset.
        float[] gpuVerifyBest = new float[CW * CH];
        Arrays.fill(gpuVerifyBest, Float.NEGATIVE_INFINITY);
        int[] gpuVerifyMotion = new int[CW * CH];
        gpu.beginRun(cpuSx, cpuSy);
        try {
            gpu.search(new CoarseTileJob(planes, stride, MARGIN, CW, CH, SCALE, gpuVerifyBest, gpuVerifyMotion),
                    NOOP_CONTROL);
        } finally {
            gpu.endRun();
        }

        // Measure Java wall-clock throughput across `workers` parallel tiles on CPU_MOTIONS.
        float[] javaFirstBest = new float[CW * CH];
        int[] javaFirstMotion = new int[CW * CH];
        long cpuNanos = measureJavaParallelNanos(planes, stride, cpuSx, cpuSy, workers, javaFirstBest, javaFirstMotion);

        // Verify parity against Java on the warm-up slice.
        for (int i = 0; i < javaFirstBest.length; i++) {
            if (Float.floatToRawIntBits(javaFirstBest[i]) != Float.floatToRawIntBits(gpuVerifyBest[i])
                    || javaFirstMotion[i] != gpuVerifyMotion[i]) {
                throw new CoarseBackendException(CoarseBackendException.Kind.SELF_TEST,
                        "GPU result differs from Java during benchmark parity check at pixel " + i);
            }
        }

        // Measure GPU tile time (upload + kernels + readback) over 2 timed runs on full GPU_MOTIONS.
        float[] gpuBest = new float[CW * CH];
        int[] gpuMotion = new int[CW * CH];
        gpu.beginRun(gpuSx, gpuSy);
        long gpuNanos;
        try {
            long bestRun = Long.MAX_VALUE;
            for (int r = 0; r < 2; r++) {
                Arrays.fill(gpuBest, Float.NEGATIVE_INFINITY);
                Arrays.fill(gpuMotion, 0);
                long t0 = System.nanoTime();
                gpu.search(new CoarseTileJob(planes, stride, MARGIN, CW, CH, SCALE, gpuBest, gpuMotion), NOOP_CONTROL);
                bestRun = Math.min(bestRun, System.nanoTime() - t0);
            }
            gpuNanos = bestRun;
        } finally {
            gpu.endRun();
        }

        // cpuNanos ran `workers` tiles in parallel for CPU_MOTIONS; effective wall time per GPU_MOTIONS tile is:
        double projectedJavaTileNanos = (double) cpuNanos * ((double) GPU_MOTIONS / CPU_MOTIONS) / workers;
        return projectedJavaTileNanos / Math.max(1.0, gpuNanos);
    }

    private static long measureJavaParallelNanos(float[][] planes, int stride, int[][] sx, int[][] sy, int workers,
                                                 float[] firstBestOut, int[] firstMotionOut) {
        // Warm up JIT once on a tiny single-strip pass.
        JavaCoarseSearch warmup = new JavaCoarseSearch();
        warmup.beginRun(Arrays.copyOf(sx, 32), Arrays.copyOf(sy, 32));
        float[] warmBest = new float[CW * CH];
        Arrays.fill(warmBest, Float.NEGATIVE_INFINITY);
        warmup.search(new CoarseTileJob(planes, stride, MARGIN, CW, CH, SCALE, warmBest, new int[CW * CH]), NOOP_CONTROL);
        warmup.endRun();

        if (workers == 1) {
            Arrays.fill(firstBestOut, Float.NEGATIVE_INFINITY);
            Arrays.fill(firstMotionOut, 0);
            JavaCoarseSearch java = new JavaCoarseSearch();
            java.beginRun(sx, sy);
            long t0 = System.nanoTime();
            java.search(new CoarseTileJob(planes, stride, MARGIN, CW, CH, SCALE, firstBestOut, firstMotionOut),
                    NOOP_CONTROL);
            long dt = System.nanoTime() - t0;
            java.endRun();
            return dt;
        }

        ExecutorService pool = Executors.newFixedThreadPool(workers);
        try {
            List<Callable<float[][]>> tasks = new ArrayList<>(workers);
            List<float[]> bests = new ArrayList<>(workers);
            List<int[]> motions = new ArrayList<>(workers);
            for (int w = 0; w < workers; w++) {
                float[] b = w == 0 ? firstBestOut : new float[CW * CH];
                int[] m = w == 0 ? firstMotionOut : new int[CW * CH];
                Arrays.fill(b, Float.NEGATIVE_INFINITY);
                Arrays.fill(m, 0);
                bests.add(b);
                motions.add(m);
                final int idx = w;
                tasks.add(() -> {
                    JavaCoarseSearch java = new JavaCoarseSearch();
                    java.beginRun(sx, sy);
                    java.search(new CoarseTileJob(planes, stride, MARGIN, CW, CH, SCALE, bests.get(idx),
                            motions.get(idx)), NOOP_CONTROL);
                    java.endRun();
                    return null;
                });
            }
            long t0 = System.nanoTime();
            List<Future<float[][]>> futures = pool.invokeAll(tasks);
            for (Future<float[][]> f : futures) {
                f.get();
            }
            return System.nanoTime() - t0;
        } catch (Exception e) {
            throw new IllegalStateException("CPU benchmark execution failed", e);
        } finally {
            pool.shutdownNow();
        }
    }
}
