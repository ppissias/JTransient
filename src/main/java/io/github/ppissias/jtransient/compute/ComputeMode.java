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
 * Requested compute policy for the faint-mover coarse search.
 */
public enum ComputeMode {
    /** Probes OpenCL GPUs and uses one only when self-test and benchmark show a clear speedup. */
    AUTO,
    /** Prefers an OpenCL GPU after passing safety and self-test checks, skipping the speedup threshold. */
    GPU,
    /** Runs purely on the CPU without loading OpenCL or JOCL classes. */
    CPU;

    /**
     * Parses a persisted mode name, defaulting unrecognized or blank strings to {@link #AUTO}.
     *
     * @param value mode string
     * @return parsed mode
     */
    public static ComputeMode parseOrDefault(String value, ComputeMode fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return switch (value.trim().toUpperCase(java.util.Locale.ROOT)) {
            case "AUTO" -> AUTO;
            case "GPU" -> GPU;
            case "CPU" -> CPU;
            default -> fallback;
        };
    }
}
