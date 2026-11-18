package io.github.ppissias.jtransient.gpu;

import io.github.ppissias.jtransient.config.DetectionConfig;
import io.github.ppissias.jtransient.core.CoarseControl;
import io.github.ppissias.jtransient.core.CoarseTileJob;
import io.github.ppissias.jtransient.core.FaintMoverSearch;
import io.github.ppissias.jtransient.core.JavaCoarseSearch;
import io.github.ppissias.jtransient.core.MasterMapGenerator;
import io.github.ppissias.jtransient.core.PixelEncoding;
import io.github.ppissias.jtransient.engine.ImageFrame;
import io.github.ppissias.jtransient.engine.JTransientEngine;
import io.github.ppissias.jtransient.engine.PipelineResult;
import io.github.ppissias.jtransient.compute.ComputeMode;
import io.github.ppissias.jtransient.compute.ComputeOptions;
import io.github.ppissias.jtransient.quality.FrameQualityAnalyzer;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Hardware parity tests comparing {@link OpenClCoarseSearch} against {@link JavaCoarseSearch} across every ranked
 * OpenCL device available in the environment (skipped via {@code Assume} when no OpenCL device is present).
 */
public class OpenClCoarseParityTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private static final CoarseControl NOOP_CONTROL = new CoarseControl() {
        @Override public boolean pastDeadline() { return false; }
        @Override public void checkCancelled() { }
        @Override public void progress(long done, long total, int sampleMotionIndex) { }
    };

    private static List<OpenClDevice> availableDevices() {
        OpenClDeviceSelector.Probe probe = OpenClDeviceSelector.probe();
        if (probe.errorReason() != null) {
            return List.of();
        }
        boolean allowCpu = Boolean.getBoolean(OpenClDeviceSelector.ALLOW_CPU_PROPERTY);
        return OpenClDeviceSelector.rank(probe.devices(), allowCpu);
    }

    @Test
    public void bitIdenticalAcrossNonMultipleSizesMotionsWideWindowsBanksAndTiesOnAllDevices() throws Exception {
        List<OpenClDevice> devices = availableDevices();
        Assume.assumeFalse("No OpenCL device available", devices.isEmpty());

        for (OpenClDevice device : devices) {
            Path cacheDir = temp.newFolder("cache-" + device.stableId()).toPath();
            try (OpenClContextManager ctx = new OpenClContextManager(device, cacheDir);
                 OpenClCoarseSearch gpu = new OpenClCoarseSearch(ctx, false)) {
                if (!device.simpleOnly()) {
                    assertTrue("Expected coarse_blocked kernel on " + device.name(), ctx.kernels(true).hasBlocked());
                }

                // Verify odd tile dimensions (not multiples of 32x8), odd motion count (not multiple of 16),
                // forced 4-bank split, forced wide-window global fallback, random shifts, ties, +-0.0f and subnormals.
                int cw = 103;
                int ch = 77;
                int planesCount = 29;
                int margin = 96;
                int stride = cw + 2 * margin;
                int planeSize = stride * (ch + 2 * margin);
                int motions = 333;
                float scale = 0.03125f;

                Random rng = new Random(0xA1B2C3D4L);
                float[][] planes = new float[planesCount][planeSize];
                for (int g = 0; g < planesCount; g++) {
                    for (int i = 0; i < planeSize; i++) {
                        planes[g][i] = (rng.nextFloat() - 0.5f) * 5.0f;
                    }
                    planes[g][margin * stride + margin] = (g % 2 == 0) ? 0.0f : -0.0f;
                    planes[g][margin * stride + margin + 1] = (g % 2 == 0) ? 1e-40f : -1e-40f;
                }

                int[][] sx = new int[motions][planesCount];
                int[][] sy = new int[motions][planesCount];
                for (int m = 0; m < motions; m++) {
                    if (m < 24) {
                        // Force wide bounding box > LMAX.
                        int signX = (m % 2 == 0) ? 80 : -80;
                        int signY = (m % 3 == 0) ? 80 : -80;
                        Arrays.fill(sx[m], signX);
                        Arrays.fill(sy[m], signY);
                    } else if (m >= 150 && m <= 154) {
                        // Exact tie across multiple motion indices.
                        Arrays.fill(sx[m], 4);
                        Arrays.fill(sy[m], -5);
                    } else {
                        for (int g = 0; g < planesCount; g++) {
                            sx[m][g] = rng.nextInt(41) - 20;
                            sy[m][g] = rng.nextInt(41) - 20;
                        }
                    }
                }

                float[] javaBest = new float[cw * ch];
                Arrays.fill(javaBest, Float.NEGATIVE_INFINITY);
                int[] javaMotion = new int[cw * ch];
                JavaCoarseSearch java = new JavaCoarseSearch();
                java.beginRun(sx, sy);
                java.search(new CoarseTileJob(planes, stride, margin, cw, ch, scale, javaBest, javaMotion), NOOP_CONTROL);
                java.endRun();

                // Force 4 plane banks (8 planes/bank for 29 planes -> 4 banks).
                gpu.setMaxBankBytesForTest((long) planeSize * Float.BYTES * 8L);
                verifyExact(device.name() + " blocked+4banks", gpu, planes, stride, margin, cw, ch, scale, sx, sy, javaBest, javaMotion);

                gpu.setForceSimpleForTest(true);
                verifyExact(device.name() + " simple+4banks", gpu, planes, stride, margin, cw, ch, scale, sx, sy, javaBest, javaMotion);
                gpu.setForceSimpleForTest(false);
                gpu.setMaxBankBytesForTest(0L);
            }

            // Second context open loads cached program binaries cleanly.
            Path programsDir = cacheDir.resolve("programs");
            assertTrue("Program cache directory populated", Files.isDirectory(programsDir));
            try (Stream<Path> bins = Files.list(programsDir)) {
                assertTrue("At least one cached OpenCL binary written", bins.findAny().isPresent());
            }
            try (OpenClContextManager reopened = new OpenClContextManager(device, cacheDir);
                 OpenClCoarseSearch gpu2 = new OpenClCoarseSearch(reopened, false)) {
                GpuSelfTest.run(gpu2);
            }
        }
    }

    @Test
    public void fullEnginePipelineIdenticalAcrossSearchSpeeds012BetweenCpuAndGpu() throws Exception {
        List<OpenClDevice> devices = availableDevices();
        Assume.assumeFalse("No OpenCL device available", devices.isEmpty());
        OpenClDevice target = devices.get(0);

        List<ImageFrame> frames = syntheticMovingFrames();
        Path cacheDir = temp.newFolder("engine-cache").toPath();

        for (int speed : new int[]{0, 1, 2}) {
            DetectionConfig cpuConfig = new DetectionConfig();
            cpuConfig.enableFaintMoverSearch = true;
            cpuConfig.faintMoverSearchSpeed = speed;
            cpuConfig.faintMoverMaxSpeedPixelsPerMinute = 0.8;

            PipelineResult cpuResult;
            try (JTransientEngine cpuEngine = new JTransientEngine(ComputeOptions.cpuOnly())) {
                cpuResult = cpuEngine.runPipeline(frames, cpuConfig, null);
            }

            PipelineResult gpuResult;
            try (JTransientEngine gpuEngine = new JTransientEngine(
                    new ComputeOptions(ComputeMode.GPU, target.stableId(), cacheDir))) {
                gpuResult = gpuEngine.runPipeline(frames, cpuConfig, null);
            }

            assertEquals("OpenCL", gpuResult.telemetry.faintMoverSearchTelemetry.coarsePath);
            assertEquals(target.name(), gpuResult.telemetry.faintMoverSearchTelemetry.coarseDevice);
            assertEquals("passed", gpuResult.telemetry.faintMoverSearchTelemetry.gpuSelfTest);
            assertEquals(cpuResult.faintMoverCandidates.size(), gpuResult.faintMoverCandidates.size());
            assertEquals(cpuResult.telemetry.faintMoverSearchTelemetry.refinedPeaks,
                    gpuResult.telemetry.faintMoverSearchTelemetry.refinedPeaks);
            assertEquals(cpuResult.telemetry.faintMoverSearchTelemetry.searchedPixels,
                    gpuResult.telemetry.faintMoverSearchTelemetry.searchedPixels);
            for (int i = 0; i < cpuResult.faintMoverCandidates.size(); i++) {
                var a = cpuResult.faintMoverCandidates.get(i);
                var b = gpuResult.faintMoverCandidates.get(i);
                assertEquals(a.x, b.x, 0.0);
                assertEquals(a.y, b.y, 0.0);
                assertEquals(a.vx, b.vx, 0.0);
                assertEquals(a.vy, b.vy, 0.0);
                assertEquals(Float.floatToRawIntBits((float) a.score), Float.floatToRawIntBits((float) b.score));
            }
        }
    }

    private static void verifyExact(String label, OpenClCoarseSearch gpu, float[][] planes, int stride, int margin,
                                    int cw, int ch, float scale, int[][] sx, int[][] sy,
                                    float[] expectedBest, int[] expectedMotion) throws Exception {
        float[] gpuBest = new float[cw * ch];
        Arrays.fill(gpuBest, Float.NEGATIVE_INFINITY);
        int[] gpuMotion = new int[cw * ch];
        gpu.beginRun(sx, sy);
        try {
            assertTrue(gpu.search(new CoarseTileJob(planes, stride, margin, cw, ch, scale, gpuBest, gpuMotion),
                    NOOP_CONTROL));
        } finally {
            gpu.endRun();
        }
        for (int i = 0; i < expectedBest.length; i++) {
            assertEquals(label + " value bit mismatch at " + i,
                    Float.floatToRawIntBits(expectedBest[i]), Float.floatToRawIntBits(gpuBest[i]));
        }
        assertArrayEquals(label + " motion mismatch", expectedMotion, gpuMotion);
    }

    private static List<ImageFrame> syntheticMovingFrames() {
        int size = 256;
        int count = 16;
        double fwhm = 4.0;
        double sigma = fwhm / 2.3548;
        Random random = new Random(99L);
        List<ImageFrame> frames = new ArrayList<>();
        long start = 1_700_000_000_000L;
        long cadence = 120_000L;
        for (int k = 0; k < count; k++) {
            double minutes = k * cadence / 60_000.0;
            short[][] pixels = new short[size][size];
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) {
                    double v = 1000.0 + 10.0 * random.nextGaussian();
                    // Faint moving target: 0.4 px/min, ~1.7 sigma peak/frame.
                    double dx = x - (90.0 + 0.4 * minutes);
                    double dy = y - (110.0 - 0.25 * minutes);
                    v += 17.0 * Math.exp(-(dx * dx + dy * dy) / (2.0 * sigma * sigma));
                    pixels[y][x] = PixelEncoding.fromShiftedPositiveInt((int) Math.round(v));
                }
            }
            frames.add(new ImageFrame(k, "f" + k + ".fit", pixels, start + k * cadence, 120_000L));
        }
        return frames;
    }
}
