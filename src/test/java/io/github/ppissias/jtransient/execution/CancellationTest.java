package io.github.ppissias.jtransient.execution;

import org.junit.Test;

import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class CancellationTest {
    @Test
    public void scopesRestoreThePreviousTokenAndDoNotLeakAcrossThreads() throws Exception {
        CancellationToken outer = () -> false;
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (CancellationScope scope = CancellationScope.open(outer)) {
            try (CancellationScope inner = CancellationScope.open(() -> true)) {
                assertThrows(CancellationException.class, CancellationScope::check);
                assertSame(CancellationToken.NONE, executor.submit(CancellationScope::current).get());
            }
            assertSame(outer, CancellationScope.current());
        } finally {
            executor.shutdownNow();
        }
        assertSame(CancellationToken.NONE, CancellationScope.current());
    }

    @Test
    public void interruptionCancelsWithoutClearingTheInterruptStatus() {
        Thread.currentThread().interrupt();
        try {
            assertThrows(CancellationException.class, CancellationScope::check);
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }
}
