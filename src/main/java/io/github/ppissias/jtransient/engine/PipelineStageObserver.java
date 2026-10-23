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
 * <p>The engine calls it on the thread that runs the pipeline, once per stage and in pipeline order, after that
 * stage's products are final: no later stage changes them. The products are the run's own objects and arrays, not
 * copies, so treat them as read-only and copy what you keep after the call returns. The run waits while the observer
 * runs. An exception from the observer is caught, reported through the run's diagnostics and ignored: it never fails
 * or cancels the run. Nothing is called after the run returns or throws.</p>
 */
@FunctionalInterface
public interface PipelineStageObserver {

    /** Called once for each finished stage. */
    void stageFinished(PipelineStage stage);
}
