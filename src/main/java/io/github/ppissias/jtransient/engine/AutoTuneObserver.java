package io.github.ppissias.jtransient.engine;

/**
 * Optional live calibration feedback. Callbacks run synchronously on the tuning thread or its joined workers,
 * never after tuning returns. Implementations must be thread-safe and quick. A failed callback is diagnosed and
 * ignored; it cannot fail tuning. No snapshots or annotations are built without an observer requesting them.
 */
public interface AutoTuneObserver {
    /** Atomically reserves one snapshot; false avoids all display-only copying and matching. */
    boolean wantsUpdate();

    /** Receives read-only borrowed pixels; detach anything kept beyond this callback, and never touch a UI here. */
    void update(AutoTuneSnapshot snapshot);

    /** Invalidates pending pictures when work changes phase; it does not reserve a new snapshot. */
    default void phaseStarted(AutoTuneSnapshot.Kind kind) { }
}
