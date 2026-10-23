package io.github.ppissias.jtransient.execution;

/** Stable engineering stage identities, independent of percentage text, scientific readiness and application UI. */
public enum StageId {
    OPERATION, INPUT_VALIDATION, REGISTRATION, FRAME_QUALITY, FRAME_EXTRACTION, FRAME_SELECTION,
    MEDIAN_STACK, MASTER_REFERENCE, MAXIMUM_STACK, SLOW_MOVERS, TRANSIENT_VETO, TRACKING,
    COMMON_MOTION, RESIDUAL_ANALYSIS, PHOTOMETRY, CALIBRATION, LISTENER
}
