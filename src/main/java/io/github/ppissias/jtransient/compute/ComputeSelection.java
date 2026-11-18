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

/**
 * Provenance describing which coarse-search path was chosen and why.
 *
 * @param path {@code "Java"} or {@code "OpenCL"}
 * @param device selected device name, or empty for Java
 * @param driver selected driver version, or empty for Java
 * @param openclVersion OpenCL version string, or empty for Java
 * @param reason human-readable reason for the selection or fallback
 * @param benchmarkSpeedup measured GPU-over-CPU speedup, or {@code Double.NaN}
 * @param selfTestPassed whether the OpenCL parity self-test passed
 */
public record ComputeSelection(
        String path,
        String device,
        String driver,
        String openclVersion,
        String reason,
        double benchmarkSpeedup,
        boolean selfTestPassed) {
}
