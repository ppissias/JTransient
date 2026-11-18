package io.github.ppissias.jtransient.core;

import io.github.ppissias.jtransient.config.DetectionConfig;
import io.github.ppissias.jtransient.engine.ImageFrame;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Verifies mid-run GPU backend failures fall back to Java and produce identical faint-mover results. */
public class FaintMoverBackendFallbackTest {

    @Test
    public void midRunDeviceLossFallsBackToJavaWithIdenticalResults() throws Exception {
        List<ImageFrame> frames = frames();
        FaintMoverSearch.Input baselineInput = new FaintMoverSearch.Input();
        baselineInput.frames = frames;
        baselineInput.masterStack = MasterMapGenerator.createMedianMasterStack(frames);
        DetectionConfig config = new DetectionConfig();
        config.faintMoverMaxSpeedPixelsPerMinute = 0.6;
        FaintMoverSearch.Result baseline = FaintMoverSearch.search(FaintMoverSearch.plan(baselineInput, config));

        AtomicInteger calls = new AtomicInteger();
        CoarseSearchBackend failingBackend = new CoarseSearchBackend() {
            @Override public String description() { return "OpenCL: Failing fake"; }
            @Override public void beginRun(int[][] x, int[][] y) { }
            @Override public boolean search(CoarseTileJob job, CoarseControl control) throws CoarseBackendException {
                calls.incrementAndGet();
                throw new CoarseBackendException(CoarseBackendException.Kind.DEVICE_LOST, "simulated TDR reset");
            }
            @Override public void endRun() { }
            @Override public void close() { }
        };

        FaintMoverSearch.Input gpuInput = new FaintMoverSearch.Input();
        gpuInput.frames = frames;
        gpuInput.masterStack = baselineInput.masterStack;
        gpuInput.compute = new CoarseBackendSelection(failingBackend, true, FaintMoverSearch.MAX_MOTIONS_GPU,
                config.faintMoverMaxSecondsGpu, "OpenCL", "Fake GPU", "1.0", "OpenCL 1.2",
                "Auto: 10.0× faster in benchmark", 10.0, "passed");
        FaintMoverSearch.Result recovered = FaintMoverSearch.search(FaintMoverSearch.plan(gpuInput, config));

        assertEquals(1, calls.get());
        assertEquals(1, recovered.telemetry.cpuFallbackTiles);
        assertEquals("Java", recovered.telemetry.coarsePath);
        assertTrue(recovered.telemetry.coarseReason.contains("GPU stopped responding"));
        assertEquals(baseline.candidates.size(), recovered.candidates.size());
        for (int i = 0; i < baseline.candidates.size(); i++) {
            var a = baseline.candidates.get(i);
            var b = recovered.candidates.get(i);
            assertEquals(a.x, b.x, 0.0);
            assertEquals(a.y, b.y, 0.0);
            assertEquals(a.score, b.score, 0.0);
        }
    }

    private static List<ImageFrame> frames() {
        int size = 200;
        int n = 12;
        double sigma = 3.0 / 2.3548;
        Random rng = new Random(42L);
        List<ImageFrame> list = new ArrayList<>();
        long start = 1_700_000_000_000L;
        long cadence = 120_000L;
        for (int k = 0; k < n; k++) {
            double minutes = k * cadence / 60_000.0;
            short[][] px = new short[size][size];
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) {
                    double v = 1000.0 + 10.0 * rng.nextGaussian();
                    double dx = x - (80.0 + 0.35 * minutes);
                    double dy = y - (90.0 - 0.2 * minutes);
                    v += 18.0 * Math.exp(-(dx * dx + dy * dy) / (2.0 * sigma * sigma));
                    px[y][x] = PixelEncoding.fromShiftedPositiveInt((int) Math.round(v));
                }
            }
            list.add(new ImageFrame(k, "f" + k, px, start + k * cadence, 120_000L));
        }
        return list;
    }
}
