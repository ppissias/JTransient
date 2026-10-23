package io.github.ppissias.jtransient.execution;

import org.junit.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/** Exercises operation-owned diagnostic options and their thread-confined scopes. */
public class DiagnosticContextTest {

    /** Runtime core sinks cannot accidentally become serialized callback provenance. */
    @Test
    public void runtimeSinkIsExcludedFromJson() {
        String json = new com.google.gson.GsonBuilder().serializeNulls()
                .create().toJson(new DiagnosticOptions(true, message -> { }));
        assertTrue(json.contains("enabled"));
        assertFalse(json.contains("sink"));
    }

    /** Invalid scope closure cannot corrupt the current policy, and valid repeated close is harmless. */
    @Test
    public void scopesAreThreadConfinedAndLifo() throws Exception {
        DiagnosticOptions options = new DiagnosticOptions(true, message -> { });
        DiagnosticContext.Scope outer = DiagnosticContext.open(options);
        DiagnosticContext.Scope inner = DiagnosticContext.open(new DiagnosticOptions(false, message -> { }));
        ExecutorService otherThread = Executors.newSingleThreadExecutor();
        try {
            assertThrows(IllegalStateException.class, outer::close);
            assertTrue(otherThread.submit(() -> {
                try {
                    inner.close();
                    return false;
                } catch (IllegalStateException expected) {
                    return true;
                }
            }).get());
            assertFalse(DiagnosticContext.enabled());
            inner.close();
            assertSame(options, DiagnosticContext.currentOptions());
            outer.close();
            outer.close();
        } finally {
            inner.close();
            outer.close();
            otherThread.shutdownNow();
            assertTrue(otherThread.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

}
