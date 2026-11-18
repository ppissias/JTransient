/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 * author: Petros Pissias <petrospis at gmail.com>
 *
 */
package io.github.ppissias.jtransient.compute;

import io.github.ppissias.jtransient.core.FaintMoverSearch;
import io.github.ppissias.jtransient.gpu.GpuBenchmarkCache;
import io.github.ppissias.jtransient.gpu.OpenClBackendSupport;
import io.github.ppissias.jtransient.gpu.OpenClDevice;
import io.github.ppissias.jtransient.gpu.OpenClDeviceSelector;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * JOCL-free facade for UI settings and pre-run estimates to probe OpenCL devices, read cached self-test/benchmark
 * status, and trigger the Test GPU self-test and benchmark.
 */
public final class GpuDiagnostics {

    /**
     * Ranked OpenCL graphics device discovery result.
     *
     * @param devices ranked GPU devices (discrete first)
     * @param errorReason failure reason when OpenCL could not be loaded or has no graphics devices, or null
     */
    public record ProbeSummary(List<OpenClDevice> devices, String errorReason) {
    }

    /**
     * Resolved pre-run GPU status and limits for settings and runtime estimation, computed without running a benchmark.
     *
     * @param usingGpu true when the GPU is currently expected to run the coarse pass
     * @param maxMotions effective motion cap ({@code MAX_MOTIONS} or {@code MAX_MOTIONS_GPU})
     * @param activeDevice active GPU device when available, else null
     * @param cachedSpeedup cached GPU-over-CPU speedup, or {@code Double.NaN}
     * @param statusLine concise summary for detection settings
     * @param testedIso cached test timestamp, or empty
     */
    public record ResolvedStatus(
            boolean usingGpu,
            int maxMotions,
            OpenClDevice activeDevice,
            double cachedSpeedup,
            String statusLine,
            String testedIso) {
    }

    /**
     * Result of an interactive Test GPU invocation.
     *
     * @param passed true when self-test and benchmark succeeded
     * @param device tested device, or null
     * @param speedup measured speedup over CPU, or {@code Double.NaN}
     * @param message formatted result line
     * @param testedIso timestamp when measured, or empty
     */
    public record TestResult(
            boolean passed,
            OpenClDevice device,
            double speedup,
            String message,
            String testedIso) {
    }

    private GpuDiagnostics() {
    }

    /** Probes ranked OpenCL devices safely; never throws. */
    public static ProbeSummary probeDevices() {
        try {
            boolean allowCpu = Boolean.getBoolean(OpenClDeviceSelector.ALLOW_CPU_PROPERTY);
            OpenClDeviceSelector.Probe probe = OpenClBackendSupport.probeRanked(allowCpu);
            if (probe.errorReason() != null) {
                return new ProbeSummary(List.of(), probe.errorReason());
            }
            if (probe.devices().isEmpty()) {
                return new ProbeSummary(List.of(), "No OpenCL graphics device found");
            }
            return new ProbeSummary(probe.devices(), null);
        } catch (LinkageError e) {
            return new ProbeSummary(List.of(), "OpenCL library could not be loaded (" + e.getMessage() + ")");
        } catch (RuntimeException e) {
            return new ProbeSummary(List.of(), "OpenCL probe failed (" + e.getMessage() + ")");
        }
    }

    /**
     * Evaluates current {@link ComputeOptions} against a pre-probed {@link ProbeSummary} and disk benchmark cache
     * without launching OpenCL kernels or blocking on device initialization.
     */
    public static ResolvedStatus resolveFromProbe(ComputeOptions options, ProbeSummary probe, int cpuWorkers) {
        ComputeOptions opts = options != null ? options : ComputeOptions.cpuOnly();
        if (opts.mode() == ComputeMode.CPU) {
            return new ResolvedStatus(false, FaintMoverSearch.MAX_MOTIONS, null, Double.NaN,
                    "CPU (Always CPU setting)", "");
        }
        if (probe == null || probe.errorReason() != null || probe.devices().isEmpty()) {
            String reason = probe == null || probe.errorReason() == null
                    ? "no OpenCL graphics device found"
                    : probe.errorReason();
            return new ResolvedStatus(false, FaintMoverSearch.MAX_MOTIONS, null, Double.NaN,
                    "CPU: " + reason, "");
        }
        OpenClDeviceSelector.Selection choice = OpenClBackendSupport.chooseDevice(probe.devices(), opts.deviceId());
        OpenClDevice dev = choice.device();
        if (dev == null) {
            return new ResolvedStatus(false, FaintMoverSearch.MAX_MOTIONS, null, Double.NaN,
                    "CPU: no OpenCL graphics device found", "");
        }
        Optional<GpuBenchmarkCache.Entry> cached = OpenClBackendSupport.cachedEntry(
                dev, opts.cacheDirectory(), Math.max(1, cpuWorkers));
        if (cached.isPresent()) {
            GpuBenchmarkCache.Entry entry = cached.get();
            if (!entry.selfTestPassed()) {
                if (opts.mode() == ComputeMode.AUTO) {
                    return new ResolvedStatus(false, FaintMoverSearch.MAX_MOTIONS, dev, Double.NaN,
                            "CPU: " + dev.name() + " failed the parity self-test", entry.measured());
                }
            } else if (opts.mode() == ComputeMode.AUTO && Double.isFinite(entry.speedup()) && entry.speedup() < 2.0) {
                return new ResolvedStatus(false, FaintMoverSearch.MAX_MOTIONS, dev, entry.speedup(),
                        String.format(Locale.ROOT, "CPU: GPU only %.1f× faster than the CPU here", entry.speedup()),
                        entry.measured());
            } else {
                double sp = entry.speedup();
                return new ResolvedStatus(true, FaintMoverSearch.MAX_MOTIONS_GPU, dev, sp,
                        "GPU (" + dev.name() + ")", entry.measured());
            }
        }
        return new ResolvedStatus(true, FaintMoverSearch.MAX_MOTIONS_GPU, dev, Double.NaN,
                "GPU (" + dev.name() + (opts.mode() == ComputeMode.AUTO ? ", untested)" : ")"), "");
    }

    /** Runs the self-test and benchmark synchronously (call off the Swing EDT). */
    public static TestResult testDevice(String preferredDeviceId, Path cacheDirectory, int cpuWorkers) {
        try {
            OpenClBackendSupport.GpuTestOutcome outcome = OpenClBackendSupport.testDevice(
                    preferredDeviceId, cacheDirectory, Math.max(1, cpuWorkers));
            return new TestResult(outcome.passed(), outcome.device(), outcome.speedup(),
                    outcome.message(), outcome.testedAtIso());
        } catch (LinkageError e) {
            return new TestResult(false, null, Double.NaN,
                    "OpenCL library could not be loaded (" + e.getMessage() + ")", "");
        } catch (RuntimeException e) {
            return new TestResult(false, null, Double.NaN, "GPU test failed: " + e.getMessage(), "");
        }
    }
}
