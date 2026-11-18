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
import io.github.ppissias.jtransient.execution.DiagnosticContext;
import org.jocl.CLException;
import org.jocl.Pointer;
import org.jocl.Sizeof;
import org.jocl.cl_context;
import org.jocl.cl_device_id;
import org.jocl.cl_program;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

import static org.jocl.CL.*;

/**
 * Disk binary cache for compiled OpenCL 1.2 programs, keyed by kernel source, build options, platform, device,
 * driver version and JOCL binding version.
 */
final class OpenClProgramCache {

    private static final String JOCL_VERSION_TAG = "jocl-2.0.6";
    private final Path programsDir;

    /**
     * Creates a program cache backed by {@code cacheDirectory/programs}, or memory-only when {@code cacheDirectory}
     * is null.
     *
     * @param cacheDirectory base cache directory, or null to skip disk caching
     */
    OpenClProgramCache(Path cacheDirectory) {
        this.programsDir = cacheDirectory == null ? null : cacheDirectory.resolve("programs");
    }

    /**
     * Loads and builds a previously cached OpenCL program binary when available and accepted by the driver; invalid
     * binaries are deleted quietly so the caller falls back to source compilation.
     */
    Optional<cl_program> load(cl_context context, OpenClNativeDevice device, String source, String options) {
        if (programsDir == null) {
            return Optional.empty();
        }
        Path file = binaryPath(device.info(), source, options);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        cl_program program = null;
        try {
            byte[] binary = Files.readAllBytes(file);
            if (binary.length == 0) {
                deleteQuietly(file);
                return Optional.empty();
            }
            int[] binaryStatus = new int[1];
            program = clCreateProgramWithBinary(context, 1, new cl_device_id[]{device.device()},
                    new long[]{binary.length}, new byte[][]{binary}, binaryStatus, null);
            if (binaryStatus[0] != CL_SUCCESS) {
                clReleaseProgram(program);
                deleteQuietly(file);
                return Optional.empty();
            }
            clBuildProgram(program, 1, new cl_device_id[]{device.device()}, options, null, null);
            return Optional.of(program);
        } catch (IOException | RuntimeException e) {
            if (program != null) {
                try {
                    clReleaseProgram(program);
                } catch (RuntimeException ignored) {
                }
            }
            deleteQuietly(file);
            if (DiagnosticContext.enabled()) {
                DiagnosticContext.printf("DEBUG: OpenCL cached binary rejected for %s (%s); rebuilding.%n",
                        device.info().name(), e.getMessage());
            }
            return Optional.empty();
        }
    }

    /** Compiles the OpenCL source and stores the resulting driver binary atomically when disk caching is enabled. */
    cl_program buildAndStore(cl_context context, OpenClNativeDevice device, String source, String options)
            throws CoarseBackendException {
        cl_program program = null;
        try {
            program = clCreateProgramWithSource(context, 1, new String[]{source}, null, null);
            try {
                clBuildProgram(program, 1, new cl_device_id[]{device.device()}, options, null, null);
            } catch (CLException buildError) {
                String log = readBuildLog(program, device.device());
                if (DiagnosticContext.enabled() && !log.isEmpty()) {
                    DiagnosticContext.printf("DEBUG: OpenCL build log for %s:%n%s%n", device.info().name(), log);
                }
                throw new CoarseBackendException(CoarseBackendException.Kind.BUILD,
                        "GPU program failed to build" + (log.isEmpty() ? "" : ": " + summarizeLog(log)), buildError);
            }
            storeQuietly(program, device, source, options);
            return program;
        } catch (CLException e) {
            if (program != null) {
                try {
                    clReleaseProgram(program);
                } catch (RuntimeException ignored) {
                }
            }
            throw new CoarseBackendException(CoarseBackendException.Kind.BUILD,
                    "GPU program creation failed: " + e.getMessage(), e);
        } catch (CoarseBackendException e) {
            if (program != null) {
                try {
                    clReleaseProgram(program);
                } catch (RuntimeException ignored) {
                }
            }
            throw e;
        }
    }

    /** Computes the SHA-256 hex digest of the kernel source for cache keys and benchmark provenance. */
    static String sha256Hex(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private Path binaryPath(OpenClDevice d, String source, String options) {
        String keyInput = String.join("|",
                source,
                options,
                d.platformVersion(),
                d.name(),
                d.driverVersion(),
                JOCL_VERSION_TAG);
        return programsDir.resolve(sha256Hex(keyInput) + ".bin");
    }

    private void storeQuietly(cl_program program, OpenClNativeDevice device, String source, String options) {
        if (programsDir == null) {
            return;
        }
        try {
            long[] sizes = new long[1];
            clGetProgramInfo(program, CL_PROGRAM_BINARY_SIZES, Sizeof.size_t, Pointer.to(sizes), null);
            if (sizes[0] <= 0 || sizes[0] > 64L * 1024 * 1024) {
                return;
            }
            byte[] binary = new byte[(int) sizes[0]];
            clGetProgramInfo(program, CL_PROGRAM_BINARIES, Sizeof.POINTER,
                    Pointer.to(new Pointer[]{Pointer.to(binary)}), null);
            Files.createDirectories(programsDir);
            Path target = binaryPath(device.info(), source, options);
            Path tmp = Files.createTempFile(programsDir, target.getFileName().toString() + ".", ".tmp");
            try {
                Files.write(tmp, binary);
                try {
                    Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(tmp);
            }
        } catch (IOException | RuntimeException ignored) {
            // Cache write failures never prevent execution.
        }
    }

    private static String readBuildLog(cl_program program, cl_device_id device) {
        try {
            long[] size = new long[1];
            clGetProgramBuildInfo(program, device, CL_PROGRAM_BUILD_LOG, 0, null, size);
            if (size[0] <= 1) {
                return "";
            }
            byte[] buf = new byte[(int) Math.min(size[0], 65536)];
            clGetProgramBuildInfo(program, device, CL_PROGRAM_BUILD_LOG, buf.length, Pointer.to(buf), null);
            int len = buf.length;
            while (len > 0 && buf[len - 1] == 0) {
                len--;
            }
            return new String(buf, 0, len, StandardCharsets.UTF_8).trim();
        } catch (RuntimeException e) {
            return "";
        }
    }

    private static String summarizeLog(String log) {
        String singleLine = log.replace('\r', ' ').replace('\n', ' ').trim();
        return singleLine.length() <= 240 ? singleLine : singleLine.substring(0, 240) + "...";
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
        }
    }
}
