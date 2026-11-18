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

import java.nio.file.Path;
import java.util.Objects;

/**
 * Caller-supplied compute options for {@code JTransientEngine}.
 *
 * @param mode compute policy ({@code AUTO}, {@code GPU}, or {@code CPU})
 * @param deviceId saved OpenCL device {@code stableId}, or empty for best ranked
 * @param cacheDirectory directory for OpenCL binary and benchmark caches, or null to disable disk caching
 */
public record ComputeOptions(ComputeMode mode, String deviceId, Path cacheDirectory) {

    /** Normalizes null mode to {@link ComputeMode#CPU} and null device ID to an empty string. */
    public ComputeOptions {
        mode = Objects.requireNonNullElse(mode, ComputeMode.CPU);
        deviceId = deviceId == null ? "" : deviceId.trim();
    }

    /**
     * Deterministic CPU-only options that never touch OpenCL or JOCL classes.
     *
     * @return CPU-only options
     */
    public static ComputeOptions cpuOnly() {
        return new ComputeOptions(ComputeMode.CPU, "", null);
    }
}
