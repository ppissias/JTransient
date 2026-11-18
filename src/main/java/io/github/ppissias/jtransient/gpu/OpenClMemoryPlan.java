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
import org.jocl.CLException;
import org.jocl.Pointer;
import org.jocl.cl_command_queue;
import org.jocl.cl_context;
import org.jocl.cl_mem;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import static org.jocl.CL.*;

/**
 * Manages OpenCL memory budgets, plane bank splitting across up to 4 buffers (respecting
 * {@code CL_DEVICE_MAX_MEM_ALLOC_SIZE} and 75% of {@code CL_DEVICE_GLOBAL_MEM_SIZE}), and reusable device buffers
 * with single-plane direct-buffer streaming.
 */
final class OpenClMemoryPlan implements AutoCloseable {

    static final int MAX_BANKS = 4;
    static final int MAX_Z_SLICES = 4;
    static final double MAX_VRAM_FRACTION = 0.75;

    private final OpenClContextManager ctx;
    private final OpenClDevice device;

    private cl_mem shiftXMem;
    private cl_mem shiftYMem;
    private cl_mem origMotionMem;
    private cl_mem boxMem;
    private long runShiftCapacityBytes;
    private long runOrigCapacityBytes;
    private long runBoxCapacityBytes;

    private final cl_mem[] bankMems = new cl_mem[MAX_BANKS];
    private final long[] bankCapacityBytes = new long[MAX_BANKS];
    private int[] bankBoundaries = new int[]{Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE};
    private int activeBanks;
    private long testMaxBankBytes;

    private cl_mem bestMem;
    private cl_mem bestMotionMem;
    private cl_mem partBestMem;
    private cl_mem partMotionMem;
    private long pixelCapacity;
    private int partialSliceCapacity;

    private ByteBuffer planeStaging;
    private int planeStagingCapacityFloats;

    OpenClMemoryPlan(OpenClContextManager ctx) {
        this.ctx = ctx;
        this.device = ctx.device();
    }

    /** Package-private hook so parity tests can force multi-bank plane splitting on small tiles. */
    void setMaxBankBytesForTest(long maxBankBytes) {
        this.testMaxBankBytes = maxBankBytes;
    }

    /**
     * Checks before planning whether the projected coarse tile and motion tables fit within the device's 75% VRAM
     * budget and max-allocation constraints (splitting planes into up to 4 banks).
     */
    static boolean fitsDeviceMemory(OpenClDevice device, int motions, int coarseFrames, int coarsePlanePixels) {
        if (coarseFrames <= 0 || coarsePlanePixels <= 0 || motions <= 0) {
            return true;
        }
        long planeBytes = (long) coarsePlanePixels * Float.BYTES;
        if (planeBytes > device.maxMemAllocBytes()) {
            return false;
        }
        int maxPlanesPerBank = (int) Math.max(1L, device.maxMemAllocBytes() / planeBytes);
        if ((long) maxPlanesPerBank * MAX_BANKS < coarseFrames) {
            return false;
        }
        long totalPlaneBytes = (long) coarseFrames * planeBytes;
        long shiftBytes = (long) motions * coarseFrames * Short.BYTES * 2L;
        long origBytes = (long) motions * Integer.BYTES;
        int blocks = (motions + OpenClContextManager.MB - 1) / OpenClContextManager.MB;
        long boxBytes = (long) blocks * coarseFrames * 4L * Integer.BYTES;
        long outputBytes = (long) coarsePlanePixels * (Float.BYTES + Integer.BYTES) * (1L + MAX_Z_SLICES);
        long totalBytes = totalPlaneBytes + shiftBytes + origBytes + boxBytes + outputBytes;
        long budget = (long) (device.globalMemBytes() * MAX_VRAM_FRACTION);
        return shiftBytes / 2 <= device.maxMemAllocBytes()
                && boxBytes <= device.maxMemAllocBytes()
                && totalBytes <= budget;
    }

    /** Allocates or reuses run-level shift, original-index and bounding-box buffers. */
    void ensureRunBuffers(byte[] shiftXBytes, byte[] shiftYBytes, int[] origMotion, int[] box)
            throws CoarseBackendException {
        try {
            long shiftSize = shiftXBytes.length;
            long origSize = (long) origMotion.length * Integer.BYTES;
            long boxSize = box == null ? 0L : (long) box.length * Integer.BYTES;
            checkSingleAlloc(shiftSize, "shift tables");
            checkSingleAlloc(origSize, "motion index map");
            if (boxSize > 0) {
                checkSingleAlloc(boxSize, "motion block boxes");
            }
            if (shiftXMem == null || runShiftCapacityBytes < shiftSize) {
                releaseRunBuffers();
                shiftXMem = createBuffer(CL_MEM_READ_ONLY, shiftSize);
                shiftYMem = createBuffer(CL_MEM_READ_ONLY, shiftSize);
                runShiftCapacityBytes = shiftSize;
            }
            if (origMotionMem == null || runOrigCapacityBytes < origSize) {
                if (origMotionMem != null) {
                    tryRelease(origMotionMem);
                    origMotionMem = null;
                }
                origMotionMem = createBuffer(CL_MEM_READ_ONLY, origSize);
                runOrigCapacityBytes = origSize;
            }
            if (boxSize > 0 && (boxMem == null || runBoxCapacityBytes < boxSize)) {
                if (boxMem != null) {
                    tryRelease(boxMem);
                    boxMem = null;
                }
                boxMem = createBuffer(CL_MEM_READ_ONLY, boxSize);
                runBoxCapacityBytes = boxSize;
            }
            cl_command_queue queue = ctx.queue();
            clEnqueueWriteBuffer(queue, shiftXMem, CL_TRUE, 0, shiftSize, Pointer.to(shiftXBytes), 0, null, null);
            clEnqueueWriteBuffer(queue, shiftYMem, CL_TRUE, 0, shiftSize, Pointer.to(shiftYBytes), 0, null, null);
            clEnqueueWriteBuffer(queue, origMotionMem, CL_TRUE, 0, origSize, Pointer.to(origMotion), 0, null, null);
            if (boxSize > 0) {
                clEnqueueWriteBuffer(queue, boxMem, CL_TRUE, 0, boxSize, Pointer.to(box), 0, null, null);
            }
        } catch (CLException e) {
            throw new CoarseBackendException(classifyAllocFailure(e),
                    "Failed to allocate GPU motion tables: " + e.getMessage(), e);
        }
    }

    /** Ensures plane banks and tile output/partial buffers fit inside 75% of VRAM and {@code CL_DEVICE_MAX_MEM_ALLOC_SIZE}. */
    void ensureTileBuffers(int planeSize, int planeCount, int pixels, int zSlices) throws CoarseBackendException {
        try {
            long onePlaneBytes = (long) planeSize * Float.BYTES;
            long effectiveMaxAlloc = testMaxBankBytes > 0
                    ? Math.min(device.maxMemAllocBytes(), testMaxBankBytes)
                    : device.maxMemAllocBytes();
            if (onePlaneBytes > effectiveMaxAlloc) {
                throw new CoarseBackendException(CoarseBackendException.Kind.OUT_OF_MEMORY,
                        "One coarse plane exceeds CL_DEVICE_MAX_MEM_ALLOC_SIZE");
            }
            int maxPlanesPerBank = (int) Math.max(1L, effectiveMaxAlloc / onePlaneBytes);
            int neededBanks = (planeCount + maxPlanesPerBank - 1) / maxPlanesPerBank;
            if (neededBanks > MAX_BANKS) {
                throw new CoarseBackendException(CoarseBackendException.Kind.OUT_OF_MEMORY,
                        "Coarse planes require " + neededBanks + " banks (limit " + MAX_BANKS + ")");
            }
            int planesPerBank = (planeCount + neededBanks - 1) / neededBanks;
            int[] boundaries = new int[3];
            int[] counts = new int[MAX_BANKS];
            for (int b = 0; b < neededBanks; b++) {
                int start = b * planesPerBank;
                int end = Math.min(planeCount, start + planesPerBank);
                counts[b] = Math.max(0, end - start);
            }
            boundaries[0] = counts[0];
            boundaries[1] = counts[0] + counts[1];
            boundaries[2] = counts[0] + counts[1] + counts[2];

            long totalPlaneBytes = (long) planeCount * onePlaneBytes;
            long outBytes = (long) pixels * (Float.BYTES + Integer.BYTES);
            long partBytes = outBytes * zSlices;
            long totalNeeded = runShiftCapacityBytes * 2L + runOrigCapacityBytes + runBoxCapacityBytes
                    + totalPlaneBytes + outBytes + partBytes;
            long budget = (long) (device.globalMemBytes() * MAX_VRAM_FRACTION);
            if (totalNeeded > budget) {
                throw new CoarseBackendException(CoarseBackendException.Kind.OUT_OF_MEMORY,
                        "Not enough GPU memory (" + (totalNeeded >> 20) + " MB needed, budget " + (budget >> 20) + " MB)");
            }

            boolean banksChanged = (activeBanks != neededBanks);
            for (int b = 0; b < neededBanks; b++) {
                long need = (long) counts[b] * onePlaneBytes;
                if (bankMems[b] == null || bankCapacityBytes[b] < need) {
                    banksChanged = true;
                }
            }
            if (banksChanged) {
                releasePlaneBanks();
                for (int b = 0; b < neededBanks; b++) {
                    long need = (long) counts[b] * onePlaneBytes;
                    bankMems[b] = createBuffer(CL_MEM_READ_ONLY, need);
                    bankCapacityBytes[b] = need;
                }
                activeBanks = neededBanks;
            }
            this.bankBoundaries = boundaries;

            if (bestMem == null || pixelCapacity < pixels || partialSliceCapacity < zSlices) {
                releaseTileOutputs();
                bestMem = createBuffer(CL_MEM_READ_WRITE, (long) pixels * Float.BYTES);
                bestMotionMem = createBuffer(CL_MEM_READ_WRITE, (long) pixels * Integer.BYTES);
                partBestMem = createBuffer(CL_MEM_READ_WRITE, (long) zSlices * pixels * Float.BYTES);
                partMotionMem = createBuffer(CL_MEM_READ_WRITE, (long) zSlices * pixels * Integer.BYTES);
                pixelCapacity = pixels;
                partialSliceCapacity = zSlices;
            }
            if (planeStaging == null || planeStagingCapacityFloats < planeSize) {
                planeStaging = ByteBuffer.allocateDirect(planeSize * Float.BYTES).order(ByteOrder.nativeOrder());
                planeStagingCapacityFloats = planeSize;
            }
        } catch (CLException e) {
            throw new CoarseBackendException(classifyAllocFailure(e),
                    "Not enough GPU memory: " + e.getMessage(), e);
        }
    }

    /** Streams {@code planes[0..G-1]} one plane at a time through the reusable direct staging buffer into bank memory. */
    void writePlanes(float[][] planes) throws CoarseBackendException {
        try {
            cl_command_queue queue = ctx.queue();
            int planeSize = planes[0].length;
            long planeBytes = (long) planeSize * Float.BYTES;
            FloatBuffer view = planeStaging.asFloatBuffer();
            for (int g = 0; g < planes.length; g++) {
                int bankIdx;
                int localPlaneIdx;
                if (g < bankBoundaries[0]) {
                    bankIdx = 0;
                    localPlaneIdx = g;
                } else if (g < bankBoundaries[1]) {
                    bankIdx = 1;
                    localPlaneIdx = g - bankBoundaries[0];
                } else if (g < bankBoundaries[2]) {
                    bankIdx = 2;
                    localPlaneIdx = g - bankBoundaries[1];
                } else {
                    bankIdx = 3;
                    localPlaneIdx = g - bankBoundaries[2];
                }
                view.clear();
                view.put(planes[g], 0, planeSize);
                clEnqueueWriteBuffer(queue, bankMems[bankIdx], CL_TRUE, (long) localPlaneIdx * planeBytes,
                        planeBytes, Pointer.to(planeStaging), 0, null, null);
            }
        } catch (CLException e) {
            throw new CoarseBackendException(classifyAllocFailure(e),
                    "GPU plane upload failed: " + e.getMessage(), e);
        }
    }

    cl_mem bank(int index) {
        return index < activeBanks && bankMems[index] != null ? bankMems[index] : bankMems[0];
    }

    int g1() {
        return bankBoundaries[0];
    }

    int g2() {
        return bankBoundaries[1];
    }

    int g3() {
        return bankBoundaries[2];
    }

    cl_mem shiftX() {
        return shiftXMem;
    }

    cl_mem shiftY() {
        return shiftYMem;
    }

    cl_mem origMotion() {
        return origMotionMem;
    }

    cl_mem box() {
        return boxMem;
    }

    cl_mem best() {
        return bestMem;
    }

    cl_mem bestMotion() {
        return bestMotionMem;
    }

    cl_mem partBest() {
        return partBestMem;
    }

    cl_mem partMotion() {
        return partMotionMem;
    }

    /** Releases run-scoped buffers and large staging arrays between runs. */
    void releaseRunBuffers() {
        if (shiftXMem != null) { tryRelease(shiftXMem); shiftXMem = null; }
        if (shiftYMem != null) { tryRelease(shiftYMem); shiftYMem = null; }
        if (origMotionMem != null) { tryRelease(origMotionMem); origMotionMem = null; }
        if (boxMem != null) { tryRelease(boxMem); boxMem = null; }
        runShiftCapacityBytes = 0L;
        runOrigCapacityBytes = 0L;
        runBoxCapacityBytes = 0L;
        releasePlaneBanks();
        releaseTileOutputs();
        planeStaging = null;
        planeStagingCapacityFloats = 0;
    }

    @Override
    public void close() {
        releaseRunBuffers();
    }

    private void checkSingleAlloc(long bytes, String what) throws CoarseBackendException {
        if (bytes > device.maxMemAllocBytes()) {
            throw new CoarseBackendException(CoarseBackendException.Kind.OUT_OF_MEMORY,
                    "GPU buffer (" + what + ") exceeds CL_DEVICE_MAX_MEM_ALLOC_SIZE");
        }
    }

    private cl_mem createBuffer(long flags, long bytes) {
        return clCreateBuffer(ctx.context(), flags, bytes, null, null);
    }

    private void releasePlaneBanks() {
        for (int i = 0; i < MAX_BANKS; i++) {
            if (bankMems[i] != null) {
                tryRelease(bankMems[i]);
                bankMems[i] = null;
                bankCapacityBytes[i] = 0L;
            }
        }
        activeBanks = 0;
    }

    private void releaseTileOutputs() {
        if (bestMem != null) { tryRelease(bestMem); bestMem = null; }
        if (bestMotionMem != null) { tryRelease(bestMotionMem); bestMotionMem = null; }
        if (partBestMem != null) { tryRelease(partBestMem); partBestMem = null; }
        if (partMotionMem != null) { tryRelease(partMotionMem); partMotionMem = null; }
        pixelCapacity = 0L;
        partialSliceCapacity = 0;
    }

    static CoarseBackendException.Kind classifyAllocFailure(CLException e) {
        int s = e.getStatus();
        if (s == CL_MEM_OBJECT_ALLOCATION_FAILURE || s == CL_OUT_OF_RESOURCES || s == CL_OUT_OF_HOST_MEMORY) {
            return CoarseBackendException.Kind.OUT_OF_MEMORY;
        }
        return CoarseBackendException.Kind.LAUNCH;
    }

    private static void tryRelease(cl_mem mem) {
        try {
            clReleaseMemObject(mem);
        } catch (RuntimeException ignored) {
        }
    }
}
