package io.github.ppissias.jtransient.execution;

/**
 * Immutable limits for one operation's parallel batches, not a hard heap reservation or a process-wide scheduler.
 * Scratch estimates exclude retained input/output buffers and unrelated allocations; at least one worker is allowed.
 *
 * @param maxWorkers maximum parallel workers in each operation
 * @param scratchBytesPerPixel estimated additional worker scratch bytes per image pixel
 * @param scratchOverheadBytes estimated additional fixed scratch bytes per worker
 * @param maximumHeapFraction maximum share of the JVM heap used for the scratch estimate
 * @param freeHeapFraction maximum share of currently unused heap used for the scratch estimate
 */
public record ExecutionPolicy(int maxWorkers, long scratchBytesPerPixel, long scratchOverheadBytes,
                              double maximumHeapFraction, double freeHeapFraction) {
    /** Validates positive worker/scratch limits and finite heap fractions in (0, 1]. */
    public ExecutionPolicy {
        if (maxWorkers < 1 || scratchBytesPerPixel < 1 || scratchOverheadBytes < 0
                || !Double.isFinite(maximumHeapFraction) || maximumHeapFraction <= 0 || maximumHeapFraction > 1
                || !Double.isFinite(freeHeapFraction) || freeHeapFraction <= 0 || freeHeapFraction > 1) {
            throw new IllegalArgumentException("Invalid execution policy limits.");
        }
    }

    /** Captures CPU availability with the engine's existing 16-byte/pixel plus 1-MiB scratch heuristic. */
    public static ExecutionPolicy defaults() {
        return bounded(Runtime.getRuntime().availableProcessors());
    }

    /** Uses the existing memory heuristic with an explicit positive worker ceiling. */
    public static ExecutionPolicy bounded(int maxWorkers) {
        return new ExecutionPolicy(maxWorkers, 16, 1024 * 1024, 0.25, 0.5);
    }

    /** Computes a per-batch limit from work count, largest work-image pixels and the current JVM heap estimate. */
    public int workerCount(int workCount, long pixelsPerWork) {
        Runtime runtime = Runtime.getRuntime();
        return workerCount(workCount, pixelsPerWork, runtime.maxMemory(), runtime.totalMemory() - runtime.freeMemory());
    }

    /** Computes a deterministic memory/CPU limit from supplied heap byte counts, saturating oversized estimates. */
    public int workerCount(int workCount, long pixelsPerWork, long maximumHeap, long usedHeap) {
        if (maximumHeap < 0 || usedHeap < 0) throw new IllegalArgumentException("Heap byte counts must be nonnegative.");
        long pixels = Math.max(1, pixelsPerWork);
        long scratch = pixels > (Long.MAX_VALUE - scratchOverheadBytes) / scratchBytesPerPixel
                ? Long.MAX_VALUE : pixels * scratchBytesPerPixel + scratchOverheadBytes;
        long available = Math.max(0, maximumHeap - Math.max(0, usedHeap));
        long budget = (long) Math.min(Math.max(0, maximumHeap) * maximumHeapFraction, available * freeHeapFraction);
        long memoryWorkers = Math.max(1, budget / scratch);
        return (int) Math.max(1, Math.min(memoryWorkers, Math.min(Math.max(1, workCount), maxWorkers)));
    }
}
