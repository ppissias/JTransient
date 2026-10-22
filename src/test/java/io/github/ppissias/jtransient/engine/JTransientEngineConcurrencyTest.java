package io.github.ppissias.jtransient.engine;

import io.github.ppissias.jtransient.config.DetectionConfig;
import org.junit.Test;

import java.util.ArrayList;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

/** Verifies observable lifecycle and the compatibility memory heuristic without depending on pool implementation details. */
public class JTransientEngineConcurrencyTest {

    private static final long GIBIBYTE = 1024L * 1024L * 1024L;

    @Test
    public void workerCountIsBoundedByCpuAndFrameCount() {
        assertEquals(4, JTransientEngine.frameWorkerCount(100, 100, 4, 8 * GIBIBYTE, 0));
        assertEquals(2, JTransientEngine.frameWorkerCount(2, 100, 16, 8 * GIBIBYTE, 0));
    }

    @Test
    public void largeFramesReduceConcurrencyUsingTheHeapBudget() {
        assertEquals(2, JTransientEngine.frameWorkerCount(100, 61_000_000, 16, 8 * GIBIBYTE, 0));
        assertEquals(1, JTransientEngine.frameWorkerCount(100, 61_000_000, 16,
                8 * GIBIBYTE, 7 * GIBIBYTE));
    }

    @Test
    public void exhaustedHeapStillUsesAtMostOneWorker() {
        assertEquals(1, JTransientEngine.frameWorkerCount(100, 61_000_000, 16, GIBIBYTE, GIBIBYTE));
    }

    @Test
    public void closeShutsDownThePoolAndRejectsFurtherRuns() throws Exception {
        JTransientEngine engine = new JTransientEngine();
        engine.close();
        engine.close();
        assertThrows(IllegalStateException.class,
                () -> engine.runPipeline(new ArrayList<>(), new DetectionConfig(), null));
    }
}
