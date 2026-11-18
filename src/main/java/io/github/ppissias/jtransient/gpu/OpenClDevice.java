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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Immutable OpenCL device description without JOCL native handles, safe for selection, caching and UI presentation.
 *
 * @param platformIndex zero-based OpenCL platform index
 * @param deviceIndex zero-based OpenCL device index on the platform
 * @param platformName OpenCL platform name
 * @param platformVersion OpenCL platform version string
 * @param openclVersion OpenCL device C/runtime version string
 * @param name device name
 * @param vendor device vendor string
 * @param vendorId numeric vendor identifier
 * @param driverVersion driver version string
 * @param isGpu true when the device reports {@code CL_DEVICE_TYPE_GPU}
 * @param isCpu true when the device reports {@code CL_DEVICE_TYPE_CPU}
 * @param hostUnifiedMemory true for integrated/APU devices sharing host RAM
 * @param globalMemBytes total global memory in bytes
 * @param maxMemAllocBytes largest single allocation allowed in bytes
 * @param localMemBytes local memory per compute unit in bytes
 * @param maxWorkGroupSize device maximum work-group size
 * @param singleFpConfig bitmask of {@code CL_DEVICE_SINGLE_FP_CONFIG}
 * @param supportsDenorms true when {@code CL_FP_DENORM} is reported
 * @param supportsIeeeRoundingAndInf true when {@code CL_FP_ROUND_TO_NEAREST} and {@code CL_FP_INF_NAN} are reported
 * @param available true when {@code CL_DEVICE_AVAILABLE} is true
 * @param compilerAvailable true when {@code CL_DEVICE_COMPILER_AVAILABLE} is true
 * @param stableId deterministic identifier persisting across runs for the same hardware/platform
 */
public record OpenClDevice(
        int platformIndex,
        int deviceIndex,
        String platformName,
        String platformVersion,
        String openclVersion,
        String name,
        String vendor,
        int vendorId,
        String driverVersion,
        boolean isGpu,
        boolean isCpu,
        boolean hostUnifiedMemory,
        long globalMemBytes,
        long maxMemAllocBytes,
        long localMemBytes,
        long maxWorkGroupSize,
        long singleFpConfig,
        boolean supportsDenorms,
        boolean supportsIeeeRoundingAndInf,
        boolean available,
        boolean compilerAvailable,
        String stableId) {

    /** Required bytes of local memory for {@code coarse_blocked} ({@code 4096 * 4 + 32 * 4}). */
    static final long BLOCKED_LOCAL_MEM_BYTES = 16L * 1024L;
    /** Required work-group size for {@code coarse_blocked} ({@code 32 * 8}). */
    static final long BLOCKED_WORK_GROUP_SIZE = 256L;

    /**
     * Whether this device can only use {@code coarse_simple} based on its hardware work-group or local-memory limits.
     *
     * @return {@code true} when {@code coarse_blocked} limits are not satisfied
     */
    public boolean simpleOnly() {
        return localMemBytes < BLOCKED_LOCAL_MEM_BYTES || maxWorkGroupSize < BLOCKED_WORK_GROUP_SIZE;
    }

    /**
     * Formats memory size in GiB for user-facing labels.
     *
     * @return formatted memory text (e.g. {@code "8.0 GB"})
     */
    public String formattedMemory() {
        double gb = globalMemBytes / (1024.0 * 1024.0 * 1024.0);
        return gb >= 1.0
                ? String.format(Locale.ROOT, "%.1f GB", gb)
                : String.format(Locale.ROOT, "%d MB", Math.max(1L, globalMemBytes >> 20));
    }

    /**
     * Computes a deterministic identifier from platform, vendor and device identity (excluding transient driver
     * version so saved preferences survive routine driver updates).
     *
     * @param platformName OpenCL platform name
     * @param vendor device vendor name
     * @param vendorId PCI/vendor identifier
     * @param name device name
     * @param isGpu whether device is GPU
     * @param isCpu whether device is CPU
     * @return short hex identifier
     */
    public static String computeStableId(String platformName, String vendor, int vendorId, String name,
                                         boolean isGpu, boolean isCpu) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            String raw = String.join("|",
                    platformName == null ? "" : platformName.trim(),
                    vendor == null ? "" : vendor.trim(),
                    Integer.toHexString(vendorId),
                    name == null ? "" : name.trim(),
                    isGpu ? "GPU" : isCpu ? "CPU" : "OTHER");
            byte[] digest = md.digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
