package io.github.ppissias.jtransient.engine;

/**
 * Receives coarse-grained progress updates from long-running engine operations.
 *
 * <p>Callbacks are best-effort, may repeat percentages, and can arrive from worker threads
 * during concurrent extraction. Implementations should therefore be thread-safe.</p>
 */
public interface TransientEngineProgressListener {
    /**
     * Called by the engine to report progress.
     *
     * @param percentage completion percentage in the {@code 0-100} range
     * @param message short description of the current phase
     */
    void onProgressUpdate(int percentage, String message);
}
