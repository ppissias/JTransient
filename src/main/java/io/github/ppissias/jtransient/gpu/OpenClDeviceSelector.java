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
import org.jocl.CL;
import org.jocl.CLException;
import org.jocl.Pointer;
import org.jocl.Sizeof;
import org.jocl.cl_device_id;
import org.jocl.cl_platform_id;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.jocl.CL.*;

/**
 * Safely probes OpenCL platforms and devices, and ranks eligible graphics devices with discrete GPUs first.
 */
public final class OpenClDeviceSelector {

    /** System property allowing CPU OpenCL drivers (such as PoCL) during tests. */
    public static final String ALLOW_CPU_PROPERTY = "jtransient.opencl.allowCpu";

    /**
     * Probed OpenCL devices together with an unavailable reason when probing failed.
     *
     * @param devices discovered OpenCL devices
     * @param errorReason failure reason when probing could not load OpenCL or found no driver, or null
     */
    public record Probe(List<OpenClDevice> devices, String errorReason) {
        /** Creates a successful probe result. */
        public static Probe of(List<OpenClDevice> devices) {
            return new Probe(List.copyOf(devices), null);
        }

        /** Creates an empty probe result with an explanation. */
        public static Probe none(String reason) {
            return new Probe(List.of(), reason);
        }
    }

    /**
     * Device selection outcome from ranked candidates and an optional preferred device id.
     *
     * @param device selected device, or null when none matched
     * @param note optional fallback note when a saved device id was absent
     */
    public record Selection(OpenClDevice device, String note) {
    }

    private OpenClDeviceSelector() {
    }

    /**
     * Probes every OpenCL platform and device safely; never throws on missing drivers or native linkage errors.
     *
     * @return discovered devices or a failure reason
     */
    public static Probe probe() {
        try {
            return Probe.of(probeNative().stream().map(OpenClNativeDevice::info).toList());
        } catch (LinkageError e) {
            return Probe.none("OpenCL library could not be loaded (" + e.getMessage() + ")");
        } catch (CLException e) {
            return Probe.none(e.getStatus() == -1001 ? "No OpenCL driver installed"
                    : "OpenCL: " + CL.stringFor_errorCode(e.getStatus()));
        } catch (RuntimeException e) {
            return Probe.none("OpenCL probe failed (" + e.getMessage() + ")");
        }
    }

    /** Package-private native probe returning handles paired with device descriptors. */
    static List<OpenClNativeDevice> probeNative() {
        CL.setExceptionsEnabled(true);
        int[] numPlatforms = new int[1];
        try {
            clGetPlatformIDs(0, null, numPlatforms);
        } catch (CLException e) {
            if (e.getStatus() == -1001) { // CL_PLATFORM_NOT_FOUND_KHR
                throw e;
            }
            throw e;
        }
        if (numPlatforms[0] <= 0) {
            return List.of();
        }
        cl_platform_id[] platforms = new cl_platform_id[numPlatforms[0]];
        clGetPlatformIDs(platforms.length, platforms, null);

        List<OpenClNativeDevice> result = new ArrayList<>();
        for (int pIdx = 0; pIdx < platforms.length; pIdx++) {
            cl_platform_id p = platforms[pIdx];
            String platformName = getPlatformString(p, CL_PLATFORM_NAME);
            String platformVersion = getPlatformString(p, CL_PLATFORM_VERSION);
            int[] numDevices = new int[1];
            try {
                clGetDeviceIDs(p, CL_DEVICE_TYPE_ALL, 0, null, numDevices);
            } catch (CLException e) {
                if (e.getStatus() == CL_DEVICE_NOT_FOUND) {
                    continue;
                }
                throw e;
            }
            if (numDevices[0] <= 0) {
                continue;
            }
            cl_device_id[] devices = new cl_device_id[numDevices[0]];
            clGetDeviceIDs(p, CL_DEVICE_TYPE_ALL, devices.length, devices, null);
            for (int dIdx = 0; dIdx < devices.length; dIdx++) {
                cl_device_id d = devices[dIdx];
                OpenClDevice info = describeDevice(pIdx, dIdx, platformName, platformVersion, d);
                result.add(new OpenClNativeDevice(info, p, d));
            }
        }
        return result;
    }

    /** Looks up a live native handle matching the given device descriptor. */
    static OpenClNativeDevice resolveNative(OpenClDevice target) throws CoarseBackendException {
        try {
            for (OpenClNativeDevice nativeDev : probeNative()) {
                if (nativeDev.info().stableId().equals(target.stableId())
                        && nativeDev.info().platformIndex() == target.platformIndex()
                        && nativeDev.info().deviceIndex() == target.deviceIndex()) {
                    return nativeDev;
                }
            }
            for (OpenClNativeDevice nativeDev : probeNative()) {
                if (nativeDev.info().stableId().equals(target.stableId())) {
                    return nativeDev;
                }
            }
            throw new CoarseBackendException(CoarseBackendException.Kind.UNAVAILABLE,
                    "OpenCL device is no longer present: " + target.name());
        } catch (LinkageError | CLException e) {
            throw new CoarseBackendException(CoarseBackendException.Kind.UNAVAILABLE,
                    "Failed to locate OpenCL device " + target.name() + ": " + e.getMessage(), e);
        }
    }

    /**
     * Ranks usable devices: GPUs before CPUs (when {@code allowCpu} is true), discrete GPUs before integrated GPUs,
     * and largest global memory first.
     *
     * @param all probed devices
     * @param allowCpu whether CPU OpenCL drivers (for tests) are retained
     * @return ordered list of eligible devices, best first
     */
    public static List<OpenClDevice> rank(List<OpenClDevice> all, boolean allowCpu) {
        return all.stream()
                .filter(d -> d.available() && d.compilerAvailable())
                .filter(d -> d.supportsIeeeRoundingAndInf())
                .filter(d -> d.isGpu() || (allowCpu && d.isCpu()))
                .sorted(Comparator.comparing(OpenClDevice::isGpu).reversed()
                        .thenComparing(OpenClDevice::hostUnifiedMemory)
                        .thenComparing(Comparator.comparingLong(OpenClDevice::globalMemBytes).reversed())
                        .thenComparing(OpenClDevice::name))
                .toList();
    }

    /**
     * Selects the preferred device if present in the ranked list, or falls back to the highest-ranked device.
     *
     * @param ranked already ranked devices from {@link #rank}
     * @param preferredDeviceId saved {@link OpenClDevice#stableId()}, or blank/null for best available
     * @return selected device and an optional fallback note
     */
    public static Selection choose(List<OpenClDevice> ranked, String preferredDeviceId) {
        if (ranked.isEmpty()) {
            return new Selection(null, null);
        }
        String wanted = preferredDeviceId == null ? "" : preferredDeviceId.trim();
        if (!wanted.isEmpty()) {
            for (OpenClDevice d : ranked) {
                if (d.stableId().equals(wanted)) {
                    return new Selection(d, null);
                }
            }
            OpenClDevice fallback = ranked.get(0);
            return new Selection(fallback, "Saved device not found; using " + fallback.name());
        }
        return new Selection(ranked.get(0), null);
    }

    /** Reads OpenCL device attributes into an immutable {@link OpenClDevice}. */
    private static OpenClDevice describeDevice(int platformIdx, int deviceIdx, String platformName,
                                               String platformVersion, cl_device_id d) {
        long type = getLong(d, CL_DEVICE_TYPE);
        boolean isGpu = (type & CL_DEVICE_TYPE_GPU) != 0;
        boolean isCpu = (type & CL_DEVICE_TYPE_CPU) != 0;
        String name = getDeviceString(d, CL_DEVICE_NAME);
        String vendor = getDeviceString(d, CL_DEVICE_VENDOR);
        int vendorId = getInt(d, CL_DEVICE_VENDOR_ID);
        String driver = getDeviceString(d, CL_DRIVER_VERSION);
        String openclVersion = getDeviceString(d, CL_DEVICE_VERSION);
        boolean hostUnified = getBool(d, CL_DEVICE_HOST_UNIFIED_MEMORY);
        long globalMem = getLong(d, CL_DEVICE_GLOBAL_MEM_SIZE);
        long maxAlloc = getLong(d, CL_DEVICE_MAX_MEM_ALLOC_SIZE);
        long localMem = getLong(d, CL_DEVICE_LOCAL_MEM_SIZE);
        long maxWg = getSize(d, CL_DEVICE_MAX_WORK_GROUP_SIZE);
        long fpConfig = getLong(d, CL_DEVICE_SINGLE_FP_CONFIG);
        boolean denorms = (fpConfig & CL_FP_DENORM) != 0;
        boolean ieee = (fpConfig & CL_FP_ROUND_TO_NEAREST) != 0 && (fpConfig & CL_FP_INF_NAN) != 0;
        boolean available = getBool(d, CL_DEVICE_AVAILABLE);
        boolean compiler = getBool(d, CL_DEVICE_COMPILER_AVAILABLE);
        String stableId = OpenClDevice.computeStableId(platformName, vendor, vendorId, name, isGpu, isCpu);
        return new OpenClDevice(platformIdx, deviceIdx, platformName, platformVersion, openclVersion, name, vendor,
                vendorId, driver, isGpu, isCpu, hostUnified, globalMem, maxAlloc, localMem, maxWg, fpConfig,
                denorms, ieee, available, compiler, stableId);
    }

    private static String getPlatformString(cl_platform_id p, int param) {
        long[] size = new long[1];
        clGetPlatformInfo(p, param, 0, null, size);
        if (size[0] <= 1) {
            return "";
        }
        byte[] buf = new byte[(int) size[0]];
        clGetPlatformInfo(p, param, buf.length, Pointer.to(buf), null);
        return trimNull(buf);
    }

    private static String getDeviceString(cl_device_id d, int param) {
        long[] size = new long[1];
        clGetDeviceInfo(d, param, 0, null, size);
        if (size[0] <= 1) {
            return "";
        }
        byte[] buf = new byte[(int) size[0]];
        clGetDeviceInfo(d, param, buf.length, Pointer.to(buf), null);
        return trimNull(buf);
    }

    private static String trimNull(byte[] buf) {
        int len = buf.length;
        while (len > 0 && buf[len - 1] == 0) {
            len--;
        }
        return new String(buf, 0, len, StandardCharsets.UTF_8).trim();
    }

    private static int getInt(cl_device_id d, int param) {
        int[] val = new int[1];
        clGetDeviceInfo(d, param, Sizeof.cl_int, Pointer.to(val), null);
        return val[0];
    }

    private static boolean getBool(cl_device_id d, int param) {
        return getInt(d, param) != 0;
    }

    private static long getLong(cl_device_id d, int param) {
        long[] val = new long[1];
        clGetDeviceInfo(d, param, Sizeof.cl_long, Pointer.to(val), null);
        return val[0];
    }

    private static long getSize(cl_device_id d, int param) {
        long[] val = new long[1];
        clGetDeviceInfo(d, param, Sizeof.size_t, Pointer.to(val), null);
        return val[0];
    }
}
