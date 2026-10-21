package io.github.ppissias.jtransient.photometry;

import io.github.ppissias.jtransient.execution.CancellationScope;
import org.junit.Test;

import java.util.Arrays;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class EnsembleSolverCancellationTest {
    @Test
    public void cancellationIsCheckedInsideTheEnsembleIterations() {
        int starCount = 100;
        int frameCount = 40;
        double[][] magnitudes = new double[starCount][frameCount];
        boolean[][] usable = new boolean[starCount][frameCount];
        boolean[] active = new boolean[frameCount];
        double[] horizontal = new double[starCount];
        double[] vertical = new double[starCount];
        Arrays.fill(active, true);
        for (int star = 0; star < starCount; star++) {
            Arrays.fill(magnitudes[star], 10 + star / 100.0);
            Arrays.fill(usable[star], true);
            horizontal[star] = (star % 10) / 5.0 - 1;
            vertical[star] = (star / 10) / 5.0 - 1;
        }
        AtomicInteger checks = new AtomicInteger();
        try (CancellationScope scope = CancellationScope.open(() -> checks.incrementAndGet() > starCount + 1)) {
            assertThrows(CancellationException.class,
                    () -> EnsembleSolver.solve(magnitudes, usable, horizontal, vertical, active, true));
        }
        assertTrue(checks.get() > starCount);
    }
}
