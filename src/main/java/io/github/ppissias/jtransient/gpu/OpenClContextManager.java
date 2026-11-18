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
import io.github.ppissias.jtransient.execution.DiagnosticContext;
import org.jocl.CL;
import org.jocl.CLException;
import org.jocl.Pointer;
import org.jocl.Sizeof;
import org.jocl.cl_command_queue;
import org.jocl.cl_context;
import org.jocl.cl_context_properties;
import org.jocl.cl_kernel;
import org.jocl.cl_program;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

import static org.jocl.CL.*;

/**
 * Owns one OpenCL 1.2 context, profiling command queue, compiled programs and kernels for a selected device,
 * serialized across tile workers by {@link #lock()}.
 */
final class OpenClContextManager implements AutoCloseable {

    static final int WGX = 32;
    static final int WGY = 8;
    static final int MB = 16;
    static final int LMAX = 4096;

    private static final List<String> FORBIDDEN_FLAGS = List.of(
            "-cl-fast-relaxed-math",
            "-cl-mad-enable",
            "-cl-unsafe-math-optimizations",
            "-cl-finite-math-only",
            "-cl-no-signed-zeros",
            "-cl-denorms-are-zero");

    /** Strict OpenCL 1.2 production build options for {@code coarse_blocked} with 16-bit shifts. */
    static final String BLOCKED_OPTIONS_SHORT = blockedBuildOptions(true);
    /** Strict OpenCL 1.2 build options for {@code coarse_simple} and {@code reduce_blocks} with 16-bit shifts. */
    static final String SIMPLE_OPTIONS_SHORT = simpleBuildOptions(true);

    static final String KERNEL_RESOURCE = "/io/github/ppissias/jtransient/gpu/coarse_search.cl";
    static final String SOURCE = loadKernelSource();
    static final String KERNEL_SHA256 = OpenClProgramCache.sha256Hex(SOURCE);

    private final OpenClNativeDevice nativeDevice;
    private final OpenClProgramCache programCache;
    private final ReentrantLock lock = new ReentrantLock(true);
    private final cl_context context;
    private final cl_command_queue queue;

    private final KernelPair shortKernels;
    private KernelPair intKernels;
    private volatile boolean invalidated;
    private volatile boolean closed;

    /** Compiled kernels for one shift-width configuration ({@code short} or {@code int}). */
    static final class KernelPair {
        final cl_program simpleProgram;
        final cl_kernel simple;
        final cl_kernel reduce;
        final cl_program blockedProgram;
        final cl_kernel blocked;

        KernelPair(cl_program simpleProgram, cl_kernel simple, cl_kernel reduce,
                   cl_program blockedProgram, cl_kernel blocked) {
            this.simpleProgram = simpleProgram;
            this.simple = simple;
            this.reduce = reduce;
            this.blockedProgram = blockedProgram;
            this.blocked = blocked;
        }

        boolean hasBlocked() {
            return blocked != null;
        }

        void release() {
            if (blocked != null) tryReleaseKernel(blocked);
            if (blockedProgram != null) tryReleaseProgram(blockedProgram);
            if (reduce != null) tryReleaseKernel(reduce);
            if (simple != null) tryReleaseKernel(simple);
            if (simpleProgram != null) tryReleaseProgram(simpleProgram);
        }
    }

    OpenClContextManager(OpenClDevice deviceInfo, Path cacheDirectory) throws CoarseBackendException {
        this.nativeDevice = OpenClDeviceSelector.resolveNative(deviceInfo);
        this.programCache = new OpenClProgramCache(cacheDirectory);
        cl_context ctx = null;
        cl_command_queue q = null;
        try {
            CL.setExceptionsEnabled(true);
            cl_context_properties props = new cl_context_properties();
            props.addProperty(CL_CONTEXT_PLATFORM, nativeDevice.platform());
            ctx = clCreateContext(props, 1, new org.jocl.cl_device_id[]{nativeDevice.device()}, null, null, null);
            q = clCreateCommandQueue(ctx, nativeDevice.device(), CL_QUEUE_PROFILING_ENABLE, null);
            this.context = ctx;
            this.queue = q;
            this.shortKernels = buildKernels(true);
        } catch (CoarseBackendException e) {
            if (q != null) tryReleaseQueue(q);
            if (ctx != null) tryReleaseContext(ctx);
            throw e;
        } catch (LinkageError | RuntimeException e) {
            if (q != null) tryReleaseQueue(q);
            if (ctx != null) tryReleaseContext(ctx);
            throw new CoarseBackendException(CoarseBackendException.Kind.BUILD,
                    "Failed to open OpenCL context on " + deviceInfo.name() + ": " + e.getMessage(), e);
        }
    }

    static String simpleBuildOptions(boolean shortShifts) {
        String options = "-cl-std=CL1.2 -DBUILD_SIMPLE -DSHIFT_T=" + (shortShifts ? "short" : "int");
        assertSafeOptions(options);
        return options;
    }

    static String blockedBuildOptions(boolean shortShifts) {
        String options = "-cl-std=CL1.2 -DBUILD_BLOCKED -DWGX=" + WGX + " -DWGY=" + WGY
                + " -DMB=" + MB + " -DLMAX=" + LMAX + " -DSHIFT_T=" + (shortShifts ? "short" : "int");
        assertSafeOptions(options);
        return options;
    }

    static void assertSafeOptions(String options) {
        for (String flag : FORBIDDEN_FLAGS) {
            if (options.contains(flag)) {
                throw new IllegalStateException("Forbidden OpenCL relaxed-math option: " + flag);
            }
        }
    }

    OpenClDevice device() {
        return nativeDevice.info();
    }

    cl_context context() {
        return context;
    }

    cl_command_queue queue() {
        return queue;
    }

    ReentrantLock lock() {
        return lock;
    }

    boolean isInvalidated() {
        return invalidated || closed;
    }

    void invalidate() {
        this.invalidated = true;
    }

    KernelPair kernels(boolean shortShifts) throws CoarseBackendException {
        if (shortShifts) {
            return shortKernels;
        }
        if (intKernels == null) {
            intKernels = buildKernels(false);
        }
        return intKernels;
    }

    private KernelPair buildKernels(boolean shortShifts) throws CoarseBackendException {
        String simpleOpts = simpleBuildOptions(shortShifts);
        cl_program simpleProg = programCache.load(context, nativeDevice, SOURCE, simpleOpts)
                .orElseGet(() -> buildUncached(simpleOpts));
        cl_kernel simpleKernel = null;
        cl_kernel reduceKernel = null;
        cl_program blockedProg = null;
        cl_kernel blockedKernel = null;
        try {
            simpleKernel = clCreateKernel(simpleProg, "coarse_simple", null);
            reduceKernel = clCreateKernel(simpleProg, "reduce_blocks", null);
            if (!nativeDevice.info().simpleOnly()) {
                try {
                    String blockedOpts = blockedBuildOptions(shortShifts);
                    blockedProg = programCache.load(context, nativeDevice, SOURCE, blockedOpts)
                            .orElseGet(() -> buildUncached(blockedOpts));
                    blockedKernel = clCreateKernel(blockedProg, "coarse_blocked", null);
                    if (!fitsKernelLimits(blockedKernel)) {
                        tryReleaseKernel(blockedKernel);
                        tryReleaseProgram(blockedProg);
                        blockedKernel = null;
                        blockedProg = null;
                    }
                } catch (UncheckedBuildException | CLException e) {
                    if (blockedKernel != null) tryReleaseKernel(blockedKernel);
                    if (blockedProg != null) tryReleaseProgram(blockedProg);
                    blockedKernel = null;
                    blockedProg = null;
                    if (DiagnosticContext.enabled()) {
                        DiagnosticContext.printf("DEBUG: Falling back to coarse_simple on %s (%s)%n",
                                nativeDevice.info().name(), e.getMessage());
                    }
                }
            }
            return new KernelPair(simpleProg, simpleKernel, reduceKernel, blockedProg, blockedKernel);
        } catch (CLException e) {
            if (blockedKernel != null) tryReleaseKernel(blockedKernel);
            if (blockedProg != null) tryReleaseProgram(blockedProg);
            if (reduceKernel != null) tryReleaseKernel(reduceKernel);
            if (simpleKernel != null) tryReleaseKernel(simpleKernel);
            tryReleaseProgram(simpleProg);
            throw new CoarseBackendException(CoarseBackendException.Kind.BUILD,
                    "Failed to create OpenCL kernels: " + e.getMessage(), e);
        }
    }

    private cl_program buildUncached(String options) {
        try {
            return programCache.buildAndStore(context, nativeDevice, SOURCE, options);
        } catch (CoarseBackendException e) {
            throw new UncheckedBuildException(e);
        }
    }

    private boolean fitsKernelLimits(cl_kernel blockedKernel) {
        long[] wgSize = new long[1];
        clGetKernelWorkGroupInfo(blockedKernel, nativeDevice.device(), CL_KERNEL_WORK_GROUP_SIZE,
                Sizeof.size_t, Pointer.to(wgSize), null);
        long[] localMem = new long[1];
        clGetKernelWorkGroupInfo(blockedKernel, nativeDevice.device(), CL_KERNEL_LOCAL_MEM_SIZE,
                Sizeof.cl_ulong, Pointer.to(localMem), null);
        return wgSize[0] >= (long) WGX * WGY && localMem[0] <= nativeDevice.info().localMemBytes();
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        lock.lock();
        try {
            if (intKernels != null) {
                intKernels.release();
                intKernels = null;
            }
            if (shortKernels != null) {
                shortKernels.release();
            }
            tryReleaseQueue(queue);
            tryReleaseContext(context);
        } finally {
            lock.unlock();
        }
    }

    private static String loadKernelSource() {
        try (InputStream in = OpenClContextManager.class.getResourceAsStream(KERNEL_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Missing OpenCL kernel resource: " + KERNEL_RESOURCE);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read OpenCL kernel resource: " + KERNEL_RESOURCE, e);
        }
    }

    private static void tryReleaseKernel(cl_kernel k) {
        try {
            clReleaseKernel(k);
        } catch (RuntimeException ignored) {
        }
    }

    private static void tryReleaseProgram(cl_program p) {
        try {
            clReleaseProgram(p);
        } catch (RuntimeException ignored) {
        }
    }

    private static void tryReleaseQueue(cl_command_queue q) {
        try {
            clReleaseCommandQueue(q);
        } catch (RuntimeException ignored) {
        }
    }

    private static void tryReleaseContext(cl_context c) {
        try {
            clReleaseContext(c);
        } catch (RuntimeException ignored) {
        }
    }

    /** Wraps {@link CoarseBackendException} inside {@code Optional.orElseGet}. */
    static final class UncheckedBuildException extends RuntimeException {
        final CoarseBackendException cause;

        UncheckedBuildException(CoarseBackendException cause) {
            super(cause);
            this.cause = cause;
        }
    }
}
