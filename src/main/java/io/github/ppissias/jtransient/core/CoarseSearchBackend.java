/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 * author: Petros Pissias <petrospis at gmail.com>
 *
 */
package io.github.ppissias.jtransient.core;

/**
 * Backend contract for the coarse pass of {@link FaintMoverSearch}: stacks coarse planes along trial shifts, scales
 * each stack, and records the strongest score and lowest winning motion index at each coarse pixel.
 */
public interface CoarseSearchBackend extends AutoCloseable {

    /**
     * Human-readable backend description, such as {@code "Java"} or
     * {@code "OpenCL: NVIDIA GeForce RTX 5060 Laptop GPU (driver 591.97)"}.
     *
     * @return backend label for diagnostics and progress messages
     */
    String description();

    /**
     * Prepares the run's integer coarse shift tables once before any tile is searched. Implementations must treat the
     * supplied arrays as read-only and never recompute shifts from velocities.
     *
     * @param coarseShiftX whole binned-pixel X shifts indexed by {@code [motion][coarseFrame]}
     * @param coarseShiftY whole binned-pixel Y shifts indexed by {@code [motion][coarseFrame]}
     * @throws CoarseBackendException if the backend cannot allocate or upload run-level state
     */
    void beginRun(int[][] coarseShiftX, int[][] coarseShiftY) throws CoarseBackendException;

    /**
     * Searches one coarse tile and populates {@link CoarseTileJob#best()} and {@link CoarseTileJob#bestMotion()}
     * identically to the Java coarse loop.
     *
     * @param job tile pixel planes, geometry, scale and output buffers
     * @param control cooperative deadline, cancellation and progress callback
     * @return {@code true} if every motion completed, or {@code false} if {@link CoarseControl#pastDeadline()} stopped the tile
     * @throws CoarseBackendException if execution fails and the caller should fall back to Java
     */
    boolean search(CoarseTileJob job, CoarseControl control) throws CoarseBackendException;

    /** Releases run-scoped buffers while keeping reusable device state (context/program) open if owned. */
    void endRun();

    /** Releases all resources owned by this backend. */
    @Override
    void close();
}
