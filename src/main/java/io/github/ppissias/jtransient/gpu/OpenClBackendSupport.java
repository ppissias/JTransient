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

import io.github.ppissias.jtransient.config.DetectionConfig;
import io.github.ppissias.jtransient.core.CoarseBackendException;
import io.github.ppissias.jtransient.core.CoarseBackendSelection;
import io.github.ppissias.jtransient.core.CoarseWorkloadEstimate;
import io.github.ppissias.jtransient.core.FaintMoverSearch;
import io.github.ppissias.jtransient.core.JavaCoarseSearch;
import io.github.ppissias.jtransient.execution.DiagnosticContext;

import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Coordinates OpenCL probing, device ranking, pre-plan VRAM checking, self-test and benchmark caching.
 * Isolated inside {@code gpu} so {@code compute} can stay free of JOCL imports when running in CPU mode.
 */
public final class OpenClBackendSupport {

    /**
     * Outcome of probing and optionally testing/benchmarking an OpenCL device for UI diagnostics.
     *
     * @param passed whether self-test and benchmark completed cleanly
     * @param device selected device, or null when unavailable
     * @param speedup measured speedup over CPU, or {@code Double.NaN}
     * @param message user-facing summary message
     * @param testedAtIso ISO-8601 timestamp when measured, or empty
     */
    public record GpuTestOutcome(
            boolean passed,
            OpenClDevice device,
            double speedup,
            String message,
            String testedAtIso) {
    }

    private OpenClBackendSupport() {
    }

    /**
     * Selects OpenCL or falls back to {@link JavaCoarseSearch} without throwing.
     *
     * @param autoMode true for {@code AUTO}, false for forced {@code GPU}
     * @param preferredDeviceId preferred {@link OpenClDevice#stableId()}, or empty/null for best ranked
     * @param cacheDirectory cache directory for compiled binaries and benchmark results, or null
     * @param config detection configuration providing GPU/CPU time limits
     * @param workload optional pre-plan workload estimate for eligibility and VRAM checks
     * @param cpuWorkers effective CPU worker concurrency for multi-core benchmark comparisons
     * @return selected backend, applied limits and telemetry provenance
     */
    public static CoarseBackendSelection select(boolean autoMode, String preferredDeviceId, Path cacheDirectory,
                                                DetectionConfig config, CoarseWorkloadEstimate workload,
                                                int cpuWorkers) {
        if (workload != null && !workload.eligible()) {
            return javaSelection("Search skipped before GPU setup", "not run", Double.NaN, config);
        }
        OpenClDeviceSelector.Probe probe = OpenClDeviceSelector.probe();
        if (probe.errorReason() != null) {
            return javaSelection(probe.errorReason(), "not run", Double.NaN, config);
        }
        boolean allowCpu = Boolean.getBoolean(OpenClDeviceSelector.ALLOW_CPU_PROPERTY);
        List<OpenClDevice> ranked = OpenClDeviceSelector.rank(probe.devices(), allowCpu);
        if (ranked.isEmpty()) {
            return javaSelection("No OpenCL graphics device", "not run", Double.NaN, config);
        }
        OpenClDeviceSelector.Selection choice = OpenClDeviceSelector.choose(ranked, preferredDeviceId);
        OpenClDevice device = choice.device();
        if (device == null) {
            return javaSelection("No OpenCL graphics device", "not run", Double.NaN, config);
        }

        if (workload != null && workload.actualMotionsAtGpuCap() > 0
                && !OpenClMemoryPlan.fitsDeviceMemory(device, workload.actualMotionsAtGpuCap(),
                workload.coarseFrames(), workload.coarsePlanePixels())) {
            return javaSelection("Not enough GPU memory", "not run", Double.NaN, config);
        }

        GpuBenchmarkCache cache = new GpuBenchmarkCache(cacheDirectory);
        String cpuKey = GpuBenchmarkCache.cpuFingerprint(cpuWorkers);
        Optional<GpuBenchmarkCache.Entry> cached = cache.lookup(device, OpenClContextManager.KERNEL_SHA256, cpuKey);
        if (autoMode && cached.isPresent() && !cached.get().selfTestPassed()) {
            return javaSelection("GPU failed the parity self-test", "failed", Double.NaN, config);
        }

        OpenClContextManager ctx = null;
        OpenClCoarseSearch gpu = null;
        try {
            ctx = new OpenClContextManager(device, cacheDirectory);
            gpu = new OpenClCoarseSearch(ctx, true);
            GpuSelfTest.run(gpu);
        } catch (CoarseBackendException e) {
            if (gpu != null) {
                gpu.close();
            } else if (ctx != null) {
                ctx.close();
            }
            if (e.kind == CoarseBackendException.Kind.SELF_TEST) {
                cache.store(device, new GpuBenchmarkCache.Entry(device.stableId(), device.name(),
                        device.driverVersion(), OpenClContextManager.KERNEL_SHA256, cpuKey, Double.NaN,
                        "failed", e.getMessage(), nowIso()));
                return javaSelection("GPU failed the parity self-test", "failed", Double.NaN, config);
            }
            String reason = e.kind == CoarseBackendException.Kind.BUILD
                    ? "GPU program failed to build"
                    : e.getMessage();
            if (DiagnosticContext.enabled()) {
                DiagnosticContext.printf("DEBUG: GPU unavailable (%s): %s%n", e.kind, e.getMessage());
            }
            return javaSelection(reason, "not run", Double.NaN, config);
        } catch (LinkageError | RuntimeException e) {
            if (gpu != null) {
                gpu.close();
            } else if (ctx != null) {
                ctx.close();
            }
            return javaSelection("OpenCL failed (" + e.getMessage() + ")", "not run", Double.NaN, config);
        }

        double speedup = Double.NaN;
        if (autoMode) {
            if (cached.isPresent() && cached.get().selfTestPassed() && Double.isFinite(cached.get().speedup())) {
                speedup = cached.get().speedup();
            } else {
                try {
                    speedup = GpuBenchmark.measureSpeedup(gpu, cpuWorkers);
                    cache.store(device, new GpuBenchmarkCache.Entry(device.stableId(), device.name(),
                            device.driverVersion(), OpenClContextManager.KERNEL_SHA256, cpuKey, speedup,
                            "passed", null, nowIso()));
                } catch (CoarseBackendException e) {
                    gpu.close();
                    if (e.kind == CoarseBackendException.Kind.SELF_TEST) {
                        cache.store(device, new GpuBenchmarkCache.Entry(device.stableId(), device.name(),
                                device.driverVersion(), OpenClContextManager.KERNEL_SHA256, cpuKey, Double.NaN,
                                "failed", e.getMessage(), nowIso()));
                        return javaSelection("GPU failed the parity self-test", "failed", Double.NaN, config);
                    }
                    return javaSelection(e.getMessage(), "passed", Double.NaN, config);
                }
            }
            if (!(speedup >= GpuBenchmark.MIN_SPEEDUP)) {
                gpu.close();
                String msg = String.format(Locale.ROOT, "GPU only %.1f× faster than the CPU here", speedup);
                return javaSelection(msg, "passed", speedup, config);
            }
            String reason = String.format(Locale.ROOT, "Auto: %.1f× faster in benchmark", speedup);
            if (choice.note() != null) {
                reason = choice.note() + "; " + reason;
            }
            return new CoarseBackendSelection(gpu, true, FaintMoverSearch.MAX_MOTIONS_GPU,
                    config.faintMoverMaxSecondsGpu, "OpenCL", device.name(), device.driverVersion(),
                    device.openclVersion(), reason, speedup, "passed");
        } else {
            if (cached.isPresent() && cached.get().selfTestPassed() && Double.isFinite(cached.get().speedup())) {
                speedup = cached.get().speedup();
            }
            String reason = choice.note() != null ? choice.note() : "Always GPU (setting)";
            return new CoarseBackendSelection(gpu, true, FaintMoverSearch.MAX_MOTIONS_GPU,
                    config.faintMoverMaxSecondsGpu, "OpenCL", device.name(), device.driverVersion(),
                    device.openclVersion(), reason, speedup, "passed");
        }
    }

    /**
     * Probes OpenCL devices and returns ranked candidates (GPUs only unless CPU OpenCL tests are enabled).
     */
    public static OpenClDeviceSelector.Probe probeRanked(boolean allowCpu) {
        OpenClDeviceSelector.Probe probe = OpenClDeviceSelector.probe();
        if (probe.errorReason() != null) {
            return probe;
        }
        return OpenClDeviceSelector.Probe.of(OpenClDeviceSelector.rank(probe.devices(), allowCpu));
    }

    /** Chooses the active device from ranked candidates for UI status display. */
    public static OpenClDeviceSelector.Selection chooseDevice(List<OpenClDevice> ranked, String preferredDeviceId) {
        return OpenClDeviceSelector.choose(ranked, preferredDeviceId);
    }

    /** Reads the most recent cached test result for {@code device}, if any. */
    public static Optional<GpuBenchmarkCache.Entry> cachedEntry(OpenClDevice device, Path cacheDirectory, int cpuWorkers) {
        if (device == null || cacheDirectory == null) {
            return Optional.empty();
        }
        GpuBenchmarkCache cache = new GpuBenchmarkCache(cacheDirectory);
        Optional<GpuBenchmarkCache.Entry> exact = cache.lookup(device, OpenClContextManager.KERNEL_SHA256,
                GpuBenchmarkCache.cpuFingerprint(cpuWorkers));
        return exact.isPresent() ? exact : cache.latestForDevice(device, OpenClContextManager.KERNEL_SHA256);
    }

    /** Runs a fresh self-test and benchmark on the chosen device and caches the result. */
    public static GpuTestOutcome testDevice(String preferredDeviceId, Path cacheDirectory, int cpuWorkers) {
        OpenClDeviceSelector.Probe probe = OpenClDeviceSelector.probe();
        if (probe.errorReason() != null) {
            return new GpuTestOutcome(false, null, Double.NaN, probe.errorReason(), "");
        }
        boolean allowCpu = Boolean.getBoolean(OpenClDeviceSelector.ALLOW_CPU_PROPERTY);
        List<OpenClDevice> ranked = OpenClDeviceSelector.rank(probe.devices(), allowCpu);
        OpenClDeviceSelector.Selection choice = OpenClDeviceSelector.choose(ranked, preferredDeviceId);
        OpenClDevice device = choice.device();
        if (device == null) {
            return new GpuTestOutcome(false, null, Double.NaN, "No OpenCL graphics device found", "");
        }

        GpuBenchmarkCache cache = new GpuBenchmarkCache(cacheDirectory);
        String cpuKey = GpuBenchmarkCache.cpuFingerprint(cpuWorkers);
        try (OpenClContextManager ctx = new OpenClContextManager(device, cacheDirectory);
             OpenClCoarseSearch gpu = new OpenClCoarseSearch(ctx, false)) {
            GpuSelfTest.run(gpu);
            double speedup = GpuBenchmark.measureSpeedup(gpu, cpuWorkers);
            String now = nowIso();
            cache.store(device, new GpuBenchmarkCache.Entry(device.stableId(), device.name(),
                    device.driverVersion(), OpenClContextManager.KERNEL_SHA256, cpuKey, speedup,
                    "passed", null, now));
            String msg = String.format(Locale.ROOT,
                    "Passed: identical results, %.1f× faster than the CPU on a test tile", speedup);
            return new GpuTestOutcome(true, device, speedup, msg, now);
        } catch (CoarseBackendException e) {
            if (e.kind == CoarseBackendException.Kind.SELF_TEST) {
                String now = nowIso();
                cache.store(device, new GpuBenchmarkCache.Entry(device.stableId(), device.name(),
                        device.driverVersion(), OpenClContextManager.KERNEL_SHA256, cpuKey, Double.NaN,
                        "failed", e.getMessage(), now));
                return new GpuTestOutcome(false, device, Double.NaN, "GPU failed the parity self-test: " + e.getMessage(), now);
            }
            return new GpuTestOutcome(false, device, Double.NaN, e.getMessage(), "");
        } catch (LinkageError | RuntimeException e) {
            return new GpuTestOutcome(false, device, Double.NaN, "GPU test failed: " + e.getMessage(), "");
        }
    }

    private static CoarseBackendSelection javaSelection(String reason, String selfTest, double speedup,
                                                        DetectionConfig config) {
        return new CoarseBackendSelection(new JavaCoarseSearch(), false, FaintMoverSearch.MAX_MOTIONS,
                config.faintMoverMaxSeconds, "Java", "", "", "", reason, speedup, selfTest);
    }

    private static String nowIso() {
        return OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS).toString();
    }
}
