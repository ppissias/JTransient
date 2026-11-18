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

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Persists GPU self-test and benchmark outcomes in {@code <cacheDirectory>/benchmark.json}, keyed by device,
 * driver, kernel source SHA-256 and host CPU fingerprint.
 */
public final class GpuBenchmarkCache {

    private static final String FILE_NAME = "benchmark.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type MAP_TYPE = new TypeToken<Map<String, Entry>>() { }.getType();

    /**
     * Cached self-test and speedup entry for one device/driver/kernel/CPU combination.
     *
     * @param deviceKey device stable identifier
     * @param deviceName human-readable device name
     * @param driver driver version string
     * @param kernelSha SHA-256 of {@code coarse_search.cl}
     * @param cpu host CPU/OS fingerprint
     * @param speedup measured GPU-over-CPU speedup (NaN/0 if self-test failed)
     * @param selfTest {@code "passed"} or {@code "failed"}
     * @param reason failure reason when self-test failed, else null
     * @param measured ISO-8601 timestamp when measured
     */
    public record Entry(
            String deviceKey,
            String deviceName,
            String driver,
            String kernelSha,
            String cpu,
            double speedup,
            String selfTest,
            String reason,
            String measured) {

        /** Whether this entry records a passing self-test. */
        public boolean selfTestPassed() {
            return "passed".equals(selfTest);
        }
    }

    private final Path file;

    /** Creates a benchmark cache in {@code cacheDirectory}, or memory-only when {@code cacheDirectory} is null. */
    public GpuBenchmarkCache(Path cacheDirectory) {
        this.file = cacheDirectory == null ? null : cacheDirectory.resolve(FILE_NAME);
    }

    /** Returns the host CPU/OS fingerprint used in cache keys. */
    public static String cpuFingerprint(int cpuWorkers) {
        return System.getProperty("os.name", "") + "|" + System.getProperty("os.arch", "")
                + "|cores=" + Runtime.getRuntime().availableProcessors() + "|w=" + Math.max(1, cpuWorkers);
    }

    /** Looks up a matching benchmark entry for the given device, kernel and CPU worker configuration. */
    public Optional<Entry> lookup(OpenClDevice device, String kernelSha, String cpu) {
        if (file == null || !Files.isRegularFile(file)) {
            return Optional.empty();
        }
        Map<String, Entry> entries = readAll();
        Entry direct = entries.get(cacheKey(device, kernelSha, cpu));
        if (direct != null) {
            return Optional.of(direct);
        }
        // Check if this device + driver + kernel previously failed self-test under any worker count.
        String selfTestKey = selfTestKey(device, kernelSha);
        Entry failed = entries.get(selfTestKey);
        if (failed != null && !failed.selfTestPassed()) {
            return Optional.of(failed);
        }
        return Optional.empty();
    }

    /** Looks up the latest stored benchmark entry for a device regardless of CPU worker count (for UI display). */
    public Optional<Entry> latestForDevice(OpenClDevice device, String kernelSha) {
        if (file == null || !Files.isRegularFile(file)) {
            return Optional.empty();
        }
        Map<String, Entry> entries = readAll();
        for (Entry e : entries.values()) {
            if (e != null && device.stableId().equals(e.deviceKey())
                    && device.driverVersion().equals(e.driver())
                    && kernelSha.equals(e.kernelSha())) {
                return Optional.of(e);
            }
        }
        return Optional.empty();
    }

    /** Stores a benchmark or self-test failure entry atomically. */
    public synchronized void store(OpenClDevice device, Entry entry) {
        if (file == null || entry == null) {
            return;
        }
        Map<String, Entry> entries = readAll();
        entries.put(cacheKey(device, entry.kernelSha(), entry.cpu()), entry);
        if (!entry.selfTestPassed()) {
            entries.put(selfTestKey(device, entry.kernelSha()), entry);
        } else {
            entries.remove(selfTestKey(device, entry.kernelSha()));
        }
        writeAll(entries);
    }

    private static String cacheKey(OpenClDevice d, String kernelSha, String cpu) {
        return d.stableId() + "|" + d.driverVersion() + "|" + kernelSha + "|" + cpu;
    }

    private static String selfTestKey(OpenClDevice d, String kernelSha) {
        return "selftest|" + d.stableId() + "|" + d.driverVersion() + "|" + kernelSha;
    }

    private Map<String, Entry> readAll() {
        if (file == null || !Files.isRegularFile(file)) {
            return new LinkedHashMap<>();
        }
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            Map<String, Entry> parsed = GSON.fromJson(json, MAP_TYPE);
            return parsed != null ? new LinkedHashMap<>(parsed) : new LinkedHashMap<>();
        } catch (IOException | RuntimeException e) {
            return new LinkedHashMap<>();
        }
    }

    private void writeAll(Map<String, Entry> entries) {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = Files.createTempFile(file.getParent(), FILE_NAME + ".", ".tmp");
            try {
                Files.writeString(tmp, GSON.toJson(entries), StandardCharsets.UTF_8);
                try {
                    Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(tmp);
            }
        } catch (IOException | RuntimeException ignored) {
            // Cache write failures never prevent execution.
        }
    }
}
