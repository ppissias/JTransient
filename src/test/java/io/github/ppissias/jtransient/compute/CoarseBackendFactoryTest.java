package io.github.ppissias.jtransient.compute;

import io.github.ppissias.jtransient.config.DetectionConfig;
import io.github.ppissias.jtransient.core.CoarseBackendSelection;
import io.github.ppissias.jtransient.core.CoarseControl;
import io.github.ppissias.jtransient.core.CoarseSearchBackend;
import io.github.ppissias.jtransient.core.CoarseTileJob;
import io.github.ppissias.jtransient.core.CoarseWorkloadEstimate;
import io.github.ppissias.jtransient.core.FaintMoverSearch;
import io.github.ppissias.jtransient.core.JavaCoarseSearch;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Exercises {@link CoarseBackendFactory} policy selection and fallback guarantees using injected delegates. */
public class CoarseBackendFactoryTest {

    @Test
    public void cpuModeNeverTouchesOpenClSelector() {
        AtomicBoolean invoked = new AtomicBoolean(false);
        DetectionConfig config = new DetectionConfig();
        CoarseBackendFactory.Selected selected = CoarseBackendFactory.selectInternal(
                ComputeOptions.cpuOnly(), config, eligibleWorkload(), 4,
                (auto, id, cache, cfg, wl, workers) -> {
                    invoked.set(true);
                    throw new AssertionError("CPU mode must not call OpenCL");
                });
        assertFalse(invoked.get());
        assertTrue(selected.backend() instanceof JavaCoarseSearch);
        assertEquals("Java", selected.selection().path());
        assertEquals("Always CPU (setting)", selected.selection().reason());
        assertEquals(FaintMoverSearch.MAX_MOTIONS, selected.limits().maxMotions());
        assertEquals(config.faintMoverMaxSeconds, selected.limits().timeLimitSeconds(), 0.0);
    }

    @Test
    public void linkageErrorAndUnexpectedRuntimeErrorsFallBackCleanlyToJava() {
        DetectionConfig config = new DetectionConfig();
        CoarseBackendFactory.Selected linkage = CoarseBackendFactory.selectInternal(
                new ComputeOptions(ComputeMode.AUTO, "", null), config, eligibleWorkload(), 4,
                (auto, id, cache, cfg, wl, workers) -> {
                    throw new UnsatisfiedLinkError("no jocl");
                });
        assertTrue(linkage.backend() instanceof JavaCoarseSearch);
        assertEquals("Java", linkage.selection().path());
        assertTrue(linkage.selection().reason().contains("OpenCL library could not be loaded"));
        assertEquals(FaintMoverSearch.MAX_MOTIONS, linkage.limits().maxMotions());

        CoarseBackendFactory.Selected runtime = CoarseBackendFactory.selectInternal(
                new ComputeOptions(ComputeMode.GPU, "", null), config, eligibleWorkload(), 4,
                (auto, id, cache, cfg, wl, workers) -> {
                    throw new IllegalStateException("driver crash");
                });
        assertTrue(runtime.backend() instanceof JavaCoarseSearch);
        assertTrue(runtime.selection().reason().contains("OpenCL initialization failed"));
    }

    @Test
    public void gpuSelectionAppliesRaisedLimitsAndJavaFallbackReasonsPropagate() {
        DetectionConfig config = new DetectionConfig();
        config.faintMoverMaxSecondsGpu = 1800.0;
        CoarseSearchBackend fakeGpu = new CoarseSearchBackend() {
            @Override public String description() { return "OpenCL: Fake GPU"; }
            @Override public void beginRun(int[][] x, int[][] y) { }
            @Override public boolean search(CoarseTileJob job, CoarseControl control) { return true; }
            @Override public void endRun() { }
            @Override public void close() { }
        };

        CoarseBackendFactory.Selected gpu = CoarseBackendFactory.selectInternal(
                new ComputeOptions(ComputeMode.AUTO, "", null), config, eligibleWorkload(), 4,
                (auto, id, cache, cfg, wl, workers) -> new CoarseBackendSelection(
                        fakeGpu, true, FaintMoverSearch.MAX_MOTIONS_GPU, cfg.faintMoverMaxSecondsGpu,
                        "OpenCL", "Fake GPU", "1.0", "OpenCL 1.2", "Auto: 12.0× faster in benchmark",
                        12.0, "passed"));
        assertEquals("OpenCL", gpu.selection().path());
        assertEquals(FaintMoverSearch.MAX_MOTIONS_GPU, gpu.limits().maxMotions());
        assertEquals(1800.0, gpu.limits().timeLimitSeconds(), 0.0);
        assertTrue(gpu.selection().selfTestPassed());

        CoarseBackendFactory.Selected tooSlow = CoarseBackendFactory.selectInternal(
                new ComputeOptions(ComputeMode.AUTO, "", null), config, eligibleWorkload(), 4,
                (auto, id, cache, cfg, wl, workers) -> new CoarseBackendSelection(
                        new JavaCoarseSearch(), false, FaintMoverSearch.MAX_MOTIONS, cfg.faintMoverMaxSeconds,
                        "Java", "", "", "", "GPU only 1.4× faster than the CPU here", 1.4, "passed"));
        assertEquals("Java", tooSlow.selection().path());
        assertEquals(FaintMoverSearch.MAX_MOTIONS, tooSlow.limits().maxMotions());
        assertEquals("GPU only 1.4× faster than the CPU here", tooSlow.selection().reason());
    }

    private static CoarseWorkloadEstimate eligibleWorkload() {
        return new CoarseWorkloadEstimate(true, 16, 1024, 1024, 16, 1, 32, 576 * 576, 1000, 1000);
    }
}
