/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 * author: Petros Pissias <petrospis at gmail.com>
 *
 */
package io.github.ppissias.jtransient.engine;

/**
 * Receives each finished stage of a pipeline run while the run continues, for example to show the findings in a
 * progress window. Pass one to
 * {@link JTransientEngine#runPipeline(java.util.List, io.github.ppissias.jtransient.config.DetectionConfig,
 * TransientEngineProgressListener, short[][], PipelineStageObserver)}.
 *
 * <p>The engine calls {@link #stageFinished} on the thread that runs the pipeline, once per stage and in pipeline
 * order, after that stage's products are final: no later stage changes them. The products are the run's own objects
 * and arrays, not copies, so treat them as read-only and copy what you keep after the call returns. During a long
 * stage it may also call {@link #stageStarted} and, only when {@link #wantsUpdate()} returns true, {@link #liveUpdate}.
 * Source extraction and photometry call {@code wantsUpdate} from worker threads; the check must be safe for that and
 * let only one caller through each time a picture is wanted. The run waits while the observer runs. An exception from
 * the observer is caught, reported through the run's diagnostics and ignored: it never fails or cancels the run.
 * Nothing is called after the run returns or throws. A run that keeps too few frames stops after quality control
 * ({@link PipelineResult#tooFewFrames}) and calls it for no stage. Without an observer, nothing is built.</p>
 */
@FunctionalInterface
public interface PipelineStageObserver {

    /** Called once for each finished stage. */
    void stageFinished(PipelineStage stage);

    /** Called when a long stage begins, before its products exist. The default does nothing. */
    default void stageStarted(String stage) {
    }

    /**
     * Whether the observer wants one in-stage snapshot now. The default is no, so existing observers stay unchanged
     * and the engine copies nothing. A true answer reserves that snapshot; the engine then calls {@link #liveUpdate}.
     */
    default boolean wantsUpdate() {
        return false;
    }

    /** Receives a snapshot the engine built because {@link #wantsUpdate()} returned true. The default does nothing. */
    default void liveUpdate(LiveSnapshot snapshot) {
    }
}
