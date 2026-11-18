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

import io.github.ppissias.jtransient.config.DetectionConfig;
import io.github.ppissias.jtransient.core.CoarseBackendSelection;
import io.github.ppissias.jtransient.core.CoarseSearchBackend;
import io.github.ppissias.jtransient.core.CoarseWorkloadEstimate;
import io.github.ppissias.jtransient.core.FaintMoverSearch;
import io.github.ppissias.jtransient.core.JavaCoarseSearch;
import io.github.ppissias.jtransient.gpu.OpenClBackendSupport;

/**
 * Selects the coarse-search backend ({@link JavaCoarseSearch} or OpenCL) from caller {@link ComputeOptions},
 * enforcing safety checks and never throwing. When {@link ComputeMode#CPU} is active, OpenCL/JOCL classes are not touched.
 */
public final class CoarseBackendFactory {

    /**
     * Selection result combining the live backend, user-facing provenance, applied limits and core adapter.
     *
     * @param backend chosen {@link CoarseSearchBackend}
     * @param selection provenance record for telemetry and reports
     * @param limits effective motion and time limits
     * @param coreSelection core-package bundle passed to {@link FaintMoverSearch.Input}
     */
    public record Selected(
            CoarseSearchBackend backend,
            ComputeSelection selection,
            ComputeLimits limits,
            CoarseBackendSelection coreSelection) {
    }

    @FunctionalInterface
    interface OpenClSelector {
        CoarseBackendSelection select(boolean autoMode, String deviceId, java.nio.file.Path cacheDir,
                                      DetectionConfig config, CoarseWorkloadEstimate workload, int cpuWorkers);
    }

    private CoarseBackendFactory() {
    }

    /**
     * Chooses the coarse-search backend for this run; always returns a working backend (Java on any failure or CPU mode).
     *
     * @param options requested compute mode, preferred device id and cache folder (null means CPU-only)
     * @param config run detection configuration providing CPU and GPU time limits
     * @param workload optional pre-plan workload estimate for eligibility and VRAM checks
     * @param cpuWorkers effective CPU worker count used for multi-core benchmark comparison
     * @return selected backend, provenance and limits
     */
    public static Selected select(ComputeOptions options, DetectionConfig config,
                                  CoarseWorkloadEstimate workload, int cpuWorkers) {
        return selectInternal(options, config, workload, cpuWorkers, OpenClBackendSupport::select);
    }

    static Selected selectInternal(ComputeOptions options, DetectionConfig config, CoarseWorkloadEstimate workload,
                                   int cpuWorkers, OpenClSelector openClSelector) {
        DetectionConfig cfg = config != null ? config : new DetectionConfig();
        ComputeOptions opts = options != null ? options : ComputeOptions.cpuOnly();
        if (opts.mode() == ComputeMode.CPU) {
            String reason = options == null ? "Default CPU backend" : "Always CPU (setting)";
            return javaSelected(cfg, reason, Double.NaN, false);
        }

        try {
            boolean autoMode = opts.mode() == ComputeMode.AUTO;
            CoarseBackendSelection coreSel = openClSelector.select(autoMode, opts.deviceId(), opts.cacheDirectory(),
                    cfg, workload, Math.max(1, cpuWorkers));
            ComputeLimits limits = new ComputeLimits(coreSel.maxMotions(), coreSel.timeLimitSeconds());
            ComputeSelection sel = new ComputeSelection(coreSel.path(), coreSel.device(), coreSel.driver(),
                    coreSel.openclVersion(), coreSel.reason(), coreSel.benchmarkSpeedup(),
                    "passed".equals(coreSel.selfTest()));
            return new Selected(coreSel.backend(), sel, limits, coreSel);
        } catch (LinkageError e) {
            return javaSelected(cfg, "OpenCL library could not be loaded (" + e.getMessage() + ")", Double.NaN, false);
        } catch (RuntimeException e) {
            return javaSelected(cfg, "OpenCL initialization failed (" + e.getMessage() + ")", Double.NaN, false);
        }
    }

    private static Selected javaSelected(DetectionConfig cfg, String reason, double speedup, boolean selfTestPassed) {
        JavaCoarseSearch backend = new JavaCoarseSearch();
        ComputeLimits limits = new ComputeLimits(FaintMoverSearch.MAX_MOTIONS, cfg.faintMoverMaxSeconds);
        ComputeSelection sel = new ComputeSelection("Java", "", "", "", reason, speedup, selfTestPassed);
        CoarseBackendSelection coreSel = new CoarseBackendSelection(backend, false, limits.maxMotions(),
                limits.timeLimitSeconds(), sel.path(), sel.device(), sel.driver(), sel.openclVersion(),
                sel.reason(), sel.benchmarkSpeedup(), selfTestPassed ? "passed" : "not run");
        return new Selected(backend, sel, limits, coreSel);
    }
}
