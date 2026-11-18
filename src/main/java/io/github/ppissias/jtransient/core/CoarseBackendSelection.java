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

/**
 * Selected {@link CoarseSearchBackend}, applied search limits and provenance recorded in telemetry.
 * Lives in {@code core} so {@link FaintMoverSearch} remains independent of {@code compute} and {@code gpu}.
 *
 * @param backend chosen coarse backend (never null)
 * @param accelerated true when an accelerated GPU backend is active and concurrent tile prep should stay bounded
 * @param maxMotions motion cap applied to the search plan
 * @param timeLimitSeconds search time limit applied to the run, in seconds
 * @param path coarse path label ({@code "Java"} or {@code "OpenCL"})
 * @param device selected GPU device name, or empty for Java
 * @param driver selected GPU driver version, or empty for Java
 * @param openclVersion selected OpenCL version string, or empty for Java
 * @param reason human-readable selection or fallback reason
 * @param benchmarkSpeedup measured GPU speedup over CPU, or {@code Double.NaN} when not measured
 * @param selfTest self-test outcome ({@code "passed"}, {@code "failed"}, or {@code "not run"})
 */
public record CoarseBackendSelection(
        CoarseSearchBackend backend,
        boolean accelerated,
        int maxMotions,
        double timeLimitSeconds,
        String path,
        String device,
        String driver,
        String openclVersion,
        String reason,
        double benchmarkSpeedup,
        String selfTest) {
}
