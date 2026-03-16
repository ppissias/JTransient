package io.github.ppissias.jtransient.engine;

public interface TransientEngineProgressListener {
    /**
     * Called by the engine to report progress.
     * * @param percentage The completion percentage (0-100)
     * @param message    A description of the current operation
     */
    void onProgressUpdate(int percentage, String message);
}