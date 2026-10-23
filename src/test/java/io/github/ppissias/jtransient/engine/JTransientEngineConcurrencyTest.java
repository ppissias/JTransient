package io.github.ppissias.jtransient.engine;

import io.github.ppissias.jtransient.config.DetectionConfig;
import org.junit.Test;

import java.util.ArrayList;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

/** Verifies the observable engine lifecycle; WorkerPoolTest covers the worker-count heuristic. */
public class JTransientEngineConcurrencyTest {


    @Test
    public void closeShutsDownThePoolAndRejectsFurtherRuns() throws Exception {
        JTransientEngine engine = new JTransientEngine();
        engine.close();
        engine.close();
        assertThrows(IllegalStateException.class,
                () -> engine.runPipeline(new ArrayList<>(), new DetectionConfig(), null));
    }
}
