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
import io.github.ppissias.jtransient.core.CoarseSearchBackend;
import io.github.ppissias.jtransient.core.CoarseTileJob;
import org.jocl.CLException;
import org.jocl.Pointer;
import org.jocl.Sizeof;
import org.jocl.cl_command_queue;
import org.jocl.cl_event;
import org.jocl.cl_kernel;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import static org.jocl.CL.*;

/**
 * OpenCL 1.2 {@link CoarseSearchBackend} executing {@code coarse_blocked} (with 2D velocity tiling, coalesced
 * transposed shifts and plane banks) or {@code coarse_simple} fallback, byte-identical to {@code JavaCoarseSearch}.
 */
public final class OpenClCoarseSearch implements CoarseSearchBackend {

    private static final int BLOCK_THREADS_X = OpenClContextManager.WGX;
    private static final int BLOCK_THREADS_Y = OpenClContextManager.WGY;
    private static final int MOTIONS_PER_BLOCK = OpenClContextManager.MB;
    private static final int REDUCE_LOCAL_SIZE = 256;

    private final OpenClContextManager ctx;
    private final boolean ownsContext;
    private final OpenClMemoryPlan memory;
    private boolean forceSimple;

    private int totalMotions;
    private int planeCount;
    private int totalBlocks;
    private boolean shortShifts = true;
    private int[] origMotion = new int[0];
    private OpenClLaunchSizer blockedSizer;
    private OpenClLaunchSizer simpleSizer;
    private int lastSizerPixels;

    /** Creates an OpenCL coarse-search backend owning its own {@link OpenClContextManager}. */
    OpenClCoarseSearch(OpenClContextManager ctx, boolean ownsContext) {
        this.ctx = ctx;
        this.ownsContext = ownsContext;
        this.memory = new OpenClMemoryPlan(ctx);
    }

    /** Package-private test toggle to exercise the {@code coarse_simple} path on any GPU. */
    void setForceSimpleForTest(boolean forceSimple) {
        this.forceSimple = forceSimple;
    }

    /** Package-private test hook to force multi-bank splitting on small tiles. */
    void setMaxBankBytesForTest(long maxBankBytes) {
        memory.setMaxBankBytesForTest(maxBankBytes);
    }

    /** Returns the context manager (used by {@link GpuSelfTest} and {@link GpuBenchmark}). */
    OpenClContextManager contextManager() {
        return ctx;
    }

    /** {@inheritDoc} */
    @Override
    public String description() {
        OpenClDevice d = ctx.device();
        return "OpenCL: " + d.name() + " (driver " + d.driverVersion() + ")";
    }

    /** {@inheritDoc} */
    @Override
    public void beginRun(int[][] coarseShiftX, int[][] coarseShiftY) throws CoarseBackendException {
        if (coarseShiftX == null || coarseShiftY == null || coarseShiftX.length != coarseShiftY.length) {
            throw new IllegalArgumentException("Shift tables must be non-null and equal length.");
        }
        int motions = coarseShiftX.length;
        int gCount = motions == 0 ? 0 : coarseShiftX[0].length;
        this.totalMotions = motions;
        this.planeCount = gCount;
        this.totalBlocks = (motions + MOTIONS_PER_BLOCK - 1) / MOTIONS_PER_BLOCK;
        this.blockedSizer = new OpenClLaunchSizer(1, Math.max(1, totalBlocks));
        this.simpleSizer = new OpenClLaunchSizer(Math.min(64, Math.max(1, motions)), Math.max(1, motions));
        this.lastSizerPixels = 0;

        if (motions == 0 || gCount == 0) {
            this.origMotion = new int[0];
            return;
        }

        int maxAbsShift = 0;
        for (int m = 0; m < motions; m++) {
            int[] sx = coarseShiftX[m];
            int[] sy = coarseShiftY[m];
            for (int g = 0; g < gCount; g++) {
                maxAbsShift = Math.max(maxAbsShift, Math.max(Math.abs(sx[g]), Math.abs(sy[g])));
            }
        }
        this.shortShifts = maxAbsShift <= Short.MAX_VALUE;

        // Order motions into compact 2D (vx, vy) tiles via Morton code on the largest-span plane so each block of
        // MB=16 motions stays within a tight bounding window in __local memory while preserving tie-breaking via origMotion.
        int[] perm = computeMortonPermutation(coarseShiftX, coarseShiftY, motions, gCount);
        this.origMotion = perm;

        int shiftElemBytes = shortShifts ? Short.BYTES : Integer.BYTES;
        byte[] sxTransposed = new byte[motions * gCount * shiftElemBytes];
        byte[] syTransposed = new byte[motions * gCount * shiftElemBytes];
        ByteBuffer bxBuf = ByteBuffer.wrap(sxTransposed).order(ByteOrder.nativeOrder());
        ByteBuffer byBuf = ByteBuffer.wrap(syTransposed).order(ByteOrder.nativeOrder());
        for (int g = 0; g < gCount; g++) {
            for (int p = 0; p < motions; p++) {
                int orig = perm[p];
                if (shortShifts) {
                    bxBuf.putShort((short) coarseShiftX[orig][g]);
                    byBuf.putShort((short) coarseShiftY[orig][g]);
                } else {
                    bxBuf.putInt(coarseShiftX[orig][g]);
                    byBuf.putInt(coarseShiftY[orig][g]);
                }
            }
        }

        int[] box = new int[totalBlocks * gCount * 4];
        for (int blk = 0; blk < totalBlocks; blk++) {
            int mFirst = blk * MOTIONS_PER_BLOCK;
            int nm = Math.min(MOTIONS_PER_BLOCK, motions - mFirst);
            for (int g = 0; g < gCount; g++) {
                int minX = Integer.MAX_VALUE;
                int maxX = Integer.MIN_VALUE;
                int minY = Integer.MAX_VALUE;
                int maxY = Integer.MIN_VALUE;
                for (int j = 0; j < nm; j++) {
                    int orig = perm[mFirst + j];
                    int sx = coarseShiftX[orig][g];
                    int sy = coarseShiftY[orig][g];
                    if (sx < minX) minX = sx;
                    if (sx > maxX) maxX = sx;
                    if (sy < minY) minY = sy;
                    if (sy > maxY) maxY = sy;
                }
                int idx = (blk * gCount + g) * 4;
                box[idx] = minX;
                box[idx + 1] = minY;
                box[idx + 2] = maxX - minX;
                box[idx + 3] = maxY - minY;
            }
        }

        ctx.lock().lock();
        try {
            if (ctx.isInvalidated()) {
                throw new CoarseBackendException(CoarseBackendException.Kind.DEVICE_LOST,
                        "OpenCL context was previously invalidated.");
            }
            // Ensure shift-width kernels are compiled before uploading buffers.
            ctx.kernels(shortShifts);
            memory.ensureRunBuffers(sxTransposed, syTransposed, origMotion, box);
        } catch (OpenClContextManager.UncheckedBuildException e) {
            throw e.cause;
        } finally {
            ctx.lock().unlock();
        }
    }

    /** {@inheritDoc} */
    @Override
    public boolean search(CoarseTileJob job, CoarseControl control) throws CoarseBackendException {
        if (totalMotions == 0 || job.planes().length == 0 || job.width() <= 0 || job.height() <= 0) {
            return true;
        }
        if (!acquireLockCooperatively(control)) {
            return false;
        }
        try {
            if (ctx.isInvalidated()) {
                throw new CoarseBackendException(CoarseBackendException.Kind.DEVICE_LOST,
                        "GPU stopped responding; finished on CPU");
            }
            if (control.pastDeadline()) {
                return false;
            }
            control.checkCancelled();

            int cw = job.width();
            int ch = job.height();
            int pixels = cw * ch;
            int planeSize = job.planes()[0].length;
            OpenClContextManager.KernelPair kernels = ctx.kernels(shortShifts);
            boolean useBlocked = kernels.hasBlocked() && !forceSimple;
            int gridX = roundUp(cw, BLOCK_THREADS_X);
            int gridY = roundUp(ch, BLOCK_THREADS_Y);
            int groupsXY = (gridX / BLOCK_THREADS_X) * (gridY / BLOCK_THREADS_Y);
            int gridZ = useBlocked ? (groupsXY >= 512 ? 1 : Math.min(OpenClMemoryPlan.MAX_Z_SLICES, Math.max(1, totalBlocks))) : 1;

            if (lastSizerPixels > 0 && pixels > lastSizerPixels * 2) {
                // Tile grew compared to an earlier edge tile: reset launch batch size conservatively.
                blockedSizer = new OpenClLaunchSizer(1, Math.max(1, totalBlocks));
                simpleSizer = new OpenClLaunchSizer(Math.min(64, Math.max(1, totalMotions)), Math.max(1, totalMotions));
            }
            lastSizerPixels = pixels;

            memory.ensureTileBuffers(planeSize, job.planes().length, pixels, gridZ);
            memory.writePlanes(job.planes());

            cl_command_queue queue = ctx.queue();
            clEnqueueFillBuffer(queue, memory.best(), Pointer.to(new float[]{Float.NEGATIVE_INFINITY}),
                    Float.BYTES, 0L, (long) pixels * Float.BYTES, 0, null, null);
            clEnqueueFillBuffer(queue, memory.bestMotion(), Pointer.to(new int[]{0}),
                    Integer.BYTES, 0L, (long) pixels * Integer.BYTES, 0, null, null);

            if (useBlocked) {
                runBlocked(job, control, kernels, queue, gridX, gridY, gridZ, planeSize, pixels);
            } else {
                runSimple(job, control, kernels, queue, planeSize, pixels);
            }

            if (control.pastDeadline()) {
                return false;
            }

            clEnqueueReadBuffer(queue, memory.best(), CL_TRUE, 0L, (long) pixels * Float.BYTES,
                    Pointer.to(job.best()), 0, null, null);
            clEnqueueReadBuffer(queue, memory.bestMotion(), CL_TRUE, 0L, (long) pixels * Integer.BYTES,
                    Pointer.to(job.bestMotion()), 0, null, null);
            return true;
        } catch (CoarseBackendException e) {
            if (e.kind == CoarseBackendException.Kind.DEVICE_LOST || e.kind == CoarseBackendException.Kind.LAUNCH) {
                ctx.invalidate();
            }
            throw e;
        } catch (OpenClContextManager.UncheckedBuildException e) {
            ctx.invalidate();
            throw e.cause;
        } catch (CLException e) {
            ctx.invalidate();
            throw new CoarseBackendException(classifyLaunchFailure(e),
                    "OpenCL execution failed: " + e.getMessage(), e);
        } finally {
            ctx.lock().unlock();
        }
    }

    /** {@inheritDoc} */
    @Override
    public void endRun() {
        ctx.lock().lock();
        try {
            memory.releaseRunBuffers();
        } finally {
            ctx.lock().unlock();
        }
        this.totalMotions = 0;
        this.planeCount = 0;
        this.totalBlocks = 0;
        this.origMotion = new int[0];
    }

    /** {@inheritDoc} */
    @Override
    public void close() {
        endRun();
        memory.close();
        if (ownsContext) {
            ctx.close();
        }
    }

    private void runBlocked(CoarseTileJob job, CoarseControl control, OpenClContextManager.KernelPair kernels,
                            cl_command_queue queue, int gridX, int gridY, int gridZ, int planeSize, int pixels)
            throws CoarseBackendException {
        cl_kernel blocked = kernels.blocked;
        cl_kernel reduce = kernels.reduce;
        setBankArgs(blocked, planeSize, job.stride(), job.margin(), job.width(), job.height(), planeCount);
        int arg = 13;
        clSetKernelArg(blocked, arg++, Sizeof.cl_int, Pointer.to(new int[]{totalMotions}));
        clSetKernelArg(blocked, arg++, Sizeof.cl_mem, Pointer.to(memory.shiftX()));
        clSetKernelArg(blocked, arg++, Sizeof.cl_mem, Pointer.to(memory.shiftY()));
        clSetKernelArg(blocked, arg++, Sizeof.cl_mem, Pointer.to(memory.origMotion()));
        clSetKernelArg(blocked, arg++, Sizeof.cl_mem, Pointer.to(memory.box()));
        int argB0 = arg++;
        int argBEnd = arg++;
        clSetKernelArg(blocked, arg++, Sizeof.cl_int, Pointer.to(new int[]{gridZ}));
        clSetKernelArg(blocked, arg++, Sizeof.cl_float, Pointer.to(new float[]{job.scale()}));
        clSetKernelArg(blocked, arg++, Sizeof.cl_mem, Pointer.to(memory.partBest()));
        clSetKernelArg(blocked, arg, Sizeof.cl_mem, Pointer.to(memory.partMotion()));

        clSetKernelArg(reduce, 0, Sizeof.cl_mem, Pointer.to(memory.partBest()));
        clSetKernelArg(reduce, 1, Sizeof.cl_mem, Pointer.to(memory.partMotion()));
        int argSlices = 2;
        clSetKernelArg(reduce, 3, Sizeof.cl_int, Pointer.to(new int[]{pixels}));
        clSetKernelArg(reduce, 4, Sizeof.cl_mem, Pointer.to(memory.best()));
        clSetKernelArg(reduce, 5, Sizeof.cl_mem, Pointer.to(memory.bestMotion()));

        long[] globalBlocked = new long[]{gridX, gridY, gridZ};
        long[] localBlocked = new long[]{BLOCK_THREADS_X, BLOCK_THREADS_Y, 1};
        long[] globalReduce = new long[]{roundUp(pixels, REDUCE_LOCAL_SIZE)};
        long[] localReduce = new long[]{REDUCE_LOCAL_SIZE};

        for (int b0 = 0; b0 < totalBlocks; ) {
            control.checkCancelled();
            if (control.pastDeadline()) {
                return;
            }
            int nb = Math.min(blockedSizer.unitsPerLaunch(), totalBlocks - b0);
            int bEnd = b0 + nb;
            clSetKernelArg(blocked, argB0, Sizeof.cl_int, Pointer.to(new int[]{b0}));
            clSetKernelArg(blocked, argBEnd, Sizeof.cl_int, Pointer.to(new int[]{bEnd}));
            cl_event evBlocked = new cl_event();
            clEnqueueNDRangeKernel(queue, blocked, 3, null, globalBlocked, localBlocked, 0, null, evBlocked);

            int activeSlices = Math.min(gridZ, nb);
            clSetKernelArg(reduce, argSlices, Sizeof.cl_int, Pointer.to(new int[]{activeSlices}));
            cl_event evReduce = new cl_event();
            clEnqueueNDRangeKernel(queue, reduce, 1, null, globalReduce, localReduce, 0, null, evReduce);

            blockedSizer.observeAndRelease(nb, evBlocked, evReduce);
            b0 = bEnd;
            int doneMotions = Math.min(b0 * MOTIONS_PER_BLOCK, totalMotions);
            int sampleOrig = origMotion[Math.min(totalMotions - 1, Math.max(0, doneMotions - 1))];
            control.progress(doneMotions, totalMotions, sampleOrig);
        }
    }

    private void runSimple(CoarseTileJob job, CoarseControl control, OpenClContextManager.KernelPair kernels,
                           cl_command_queue queue, int planeSize, int pixels) throws CoarseBackendException {
        cl_kernel simple = kernels.simple;
        setBankArgs(simple, planeSize, job.stride(), job.margin(), job.width(), job.height(), planeCount);
        int arg = 13;
        clSetKernelArg(simple, arg++, Sizeof.cl_int, Pointer.to(new int[]{totalMotions}));
        clSetKernelArg(simple, arg++, Sizeof.cl_mem, Pointer.to(memory.shiftX()));
        clSetKernelArg(simple, arg++, Sizeof.cl_mem, Pointer.to(memory.shiftY()));
        clSetKernelArg(simple, arg++, Sizeof.cl_mem, Pointer.to(memory.origMotion()));
        int argM0 = arg++;
        int argM1 = arg++;
        clSetKernelArg(simple, arg++, Sizeof.cl_float, Pointer.to(new float[]{job.scale()}));
        clSetKernelArg(simple, arg++, Sizeof.cl_mem, Pointer.to(memory.best()));
        clSetKernelArg(simple, arg, Sizeof.cl_mem, Pointer.to(memory.bestMotion()));

        long[] global = new long[]{job.width(), job.height()};
        for (int m0 = 0; m0 < totalMotions; ) {
            control.checkCancelled();
            if (control.pastDeadline()) {
                return;
            }
            int nm = Math.min(simpleSizer.unitsPerLaunch(), totalMotions - m0);
            int m1 = m0 + nm;
            clSetKernelArg(simple, argM0, Sizeof.cl_int, Pointer.to(new int[]{m0}));
            clSetKernelArg(simple, argM1, Sizeof.cl_int, Pointer.to(new int[]{m1}));
            cl_event ev = new cl_event();
            clEnqueueNDRangeKernel(queue, simple, 2, null, global, null, 0, null, ev);
            simpleSizer.observeAndRelease(nm, ev);
            m0 = m1;
            int sampleOrig = origMotion[Math.min(totalMotions - 1, Math.max(0, m0 - 1))];
            control.progress(m0, totalMotions, sampleOrig);
        }
    }

    private void setBankArgs(cl_kernel kernel, int planeSize, int stride, int margin, int cw, int ch, int gCount) {
        int arg = 0;
        clSetKernelArg(kernel, arg++, Sizeof.cl_mem, Pointer.to(memory.bank(0)));
        clSetKernelArg(kernel, arg++, Sizeof.cl_mem, Pointer.to(memory.bank(1)));
        clSetKernelArg(kernel, arg++, Sizeof.cl_mem, Pointer.to(memory.bank(2)));
        clSetKernelArg(kernel, arg++, Sizeof.cl_mem, Pointer.to(memory.bank(3)));
        clSetKernelArg(kernel, arg++, Sizeof.cl_int, Pointer.to(new int[]{memory.g1()}));
        clSetKernelArg(kernel, arg++, Sizeof.cl_int, Pointer.to(new int[]{memory.g2()}));
        clSetKernelArg(kernel, arg++, Sizeof.cl_int, Pointer.to(new int[]{memory.g3()}));
        clSetKernelArg(kernel, arg++, Sizeof.cl_int, Pointer.to(new int[]{planeSize}));
        clSetKernelArg(kernel, arg++, Sizeof.cl_int, Pointer.to(new int[]{stride}));
        clSetKernelArg(kernel, arg++, Sizeof.cl_int, Pointer.to(new int[]{margin}));
        clSetKernelArg(kernel, arg++, Sizeof.cl_int, Pointer.to(new int[]{cw}));
        clSetKernelArg(kernel, arg++, Sizeof.cl_int, Pointer.to(new int[]{ch}));
        clSetKernelArg(kernel, arg, Sizeof.cl_int, Pointer.to(new int[]{gCount}));
    }

    private boolean acquireLockCooperatively(CoarseControl control) throws CoarseBackendException {
        while (true) {
            control.checkCancelled();
            if (control.pastDeadline()) {
                return false;
            }
            try {
                if (ctx.lock().tryLock(25L, TimeUnit.MILLISECONDS)) {
                    return true;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                control.checkCancelled();
                throw new CoarseBackendException(CoarseBackendException.Kind.LAUNCH,
                        "Interrupted waiting for GPU device lock.", e);
            }
        }
    }

    /** Orders motions along a 2D Morton curve on the plane with the largest shift range. */
    private static int[] computeMortonPermutation(int[][] coarseShiftX, int[][] coarseShiftY, int motions, int gCount) {
        int refPlane = 0;
        int bestSpan = -1;
        int bestMinX = 0;
        int bestMinY = 0;
        for (int g = 0; g < gCount; g++) {
            int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
            int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
            for (int m = 0; m < motions; m++) {
                int sx = coarseShiftX[m][g];
                int sy = coarseShiftY[m][g];
                if (sx < minX) minX = sx;
                if (sx > maxX) maxX = sx;
                if (sy < minY) minY = sy;
                if (sy > maxY) maxY = sy;
            }
            int span = (maxX - minX) + (maxY - minY);
            if (span > bestSpan) {
                bestSpan = span;
                refPlane = g;
                bestMinX = minX;
                bestMinY = minY;
            }
        }
        final int plane = refPlane;
        final int minX = bestMinX;
        final int minY = bestMinY;
        Integer[] order = new Integer[motions];
        final long[] keys = new long[motions];
        for (int m = 0; m < motions; m++) {
            order[m] = m;
            int dx = Math.min(65535, Math.max(0, coarseShiftX[m][plane] - minX));
            int dy = Math.min(65535, Math.max(0, coarseShiftY[m][plane] - minY));
            keys[m] = (morton16(dx, dy) << 32) | (m & 0xFFFF_FFFFL);
        }
        Arrays.sort(order, (a, b) -> Long.compareUnsigned(keys[a], keys[b]));
        int[] perm = new int[motions];
        for (int i = 0; i < motions; i++) {
            perm[i] = order[i];
        }
        return perm;
    }

    private static long morton16(int x, int y) {
        return part1By1(x) | (part1By1(y) << 1);
    }

    private static long part1By1(int v) {
        long x = v & 0xFFFFL;
        x = (x | (x << 8)) & 0x00FF00FFL;
        x = (x | (x << 4)) & 0x0F0F0F0FL;
        x = (x | (x << 2)) & 0x33333333L;
        x = (x | (x << 1)) & 0x55555555L;
        return x;
    }

    private static int roundUp(int value, int multiple) {
        return ((value + multiple - 1) / multiple) * multiple;
    }

    static CoarseBackendException.Kind classifyLaunchFailure(CLException e) {
        int s = e.getStatus();
        if (s == CL_MEM_OBJECT_ALLOCATION_FAILURE || s == CL_OUT_OF_HOST_MEMORY) {
            return CoarseBackendException.Kind.OUT_OF_MEMORY;
        }
        if (s == -14 /* CL_EXEC_STATUS_ERROR_FOR_EVENTS_IN_WAIT_LIST */ || s == CL_OUT_OF_RESOURCES
                || s == CL_DEVICE_NOT_AVAILABLE) {
            return CoarseBackendException.Kind.DEVICE_LOST;
        }
        return CoarseBackendException.Kind.LAUNCH;
    }
}
