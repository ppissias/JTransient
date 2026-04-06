# JTransient Pipeline Guide

This document explains what each public entrypoint does in the library and which parts of the pipeline it executes.

The public surface is split into three layers:

- standalone single-frame extraction via `SourceExtractor`
- sequence-level utilities and tracking via `JTransientEngine`
- configuration search via `JTransientAutoTuner`

## Pipeline Building Blocks

The full engine is assembled from these stages:

1. border drift diagnostics
2. per-frame source extraction
3. per-frame quality analysis
4. session-level frame rejection
5. median master-stack generation or reuse
6. master-star extraction
7. stationary-star veto masking
8. optional slow-mover stack analysis
9. streak linking
10. time-based point linking when timestamps exist
11. geometric fallback linking
12. anomaly rescue
13. maximum-stack export

Different entrypoints execute different subsets of that sequence.

## Entry Point Summary

| Entry point | Scope | Generates or uses a master stack | Returns tracks | Primary use |
| --- | --- | --- | --- | --- |
| `SourceExtractor.extractSources(...)` | One frame | No | No | Standalone object detection |
| `JTransientAutoTuner.tune(...)` | Representative sample of frames | Internal cropped master stacks | No | Derive a better `DetectionConfig` |
| `JTransientEngine.generateMasterStack(...)` | Full frame sequence | Generates a median master stack | No | Reuse the stack across repeated runs |
| `JTransientEngine.detectTransients(...)` | Full frame sequence | Generates a median master stack | No | Export per-frame transients after stationary-star filtering |
| `JTransientEngine.detectTransients(..., providedMasterStack)` | Full frame sequence | Uses a provided median master stack | No | Same as above, but skip stacking |
| `JTransientEngine.runPipeline(...)` | Full frame sequence | Generates a median master stack | Yes | Full detection run |
| `JTransientEngine.runPipeline(..., providedMasterStack)` | Full frame sequence | Uses a provided median master stack | Yes | Full detection run without restacking |

## `SourceExtractor.extractSources(...)`

Signature:

```java
SourceExtractor.ExtractionResult extractSources(
        short[][] image,
        double sigmaMultiplier,
        int minPixels,
        DetectionConfig config
)
```

What it does:

1. estimates the background with histogram-based sigma clipping
2. computes the seed and grow thresholds
3. scans the image with an 8-connected BFS blob grower
4. computes shape metrics for each surviving blob
5. rejects edge and alignment-void artifacts

What it returns:

- `ExtractionResult.objects`
- `ExtractionResult.backgroundMetrics`
- `ExtractionResult.seedThreshold`
- `ExtractionResult.growThreshold`

What it does not do:

- no frame rejection
- no master stack
- no stationary-star veto
- no track linking

Use this when you only need object detection on one image or you want to build your own higher-level tracker.

## `JTransientAutoTuner.tune(...)`

Signatures:

```java
JTransientAutoTuner.AutoTunerResult tune(
        List<ImageFrame> frames,
        DetectionConfig baseConfig,
        TransientEngineProgressListener listener
)

JTransientAutoTuner.AutoTunerResult tune(
        List<ImageFrame> frames,
        DetectionConfig baseConfig,
        JTransientAutoTuner.AutoTuneProfile profile,
        TransientEngineProgressListener listener
)
```

What it does:

1. evaluates frame quality across the sequence
2. selects a representative sample of frames
3. builds several interior crops from those frames
4. estimates a rough initial jitter baseline
5. sweeps detection sigma, grow sigma, minimum pixels, and mask overlap
6. scores each combination against cropped master-stack behavior
7. measures stable-star jitter and elongation from the winning combination
8. returns an optimized config clone

What it returns:

- `AutoTunerResult.success`
- `AutoTunerResult.optimizedConfig`
- `AutoTunerResult.telemetryReport`
- `AutoTunerResult.bestStarCount`
- `AutoTunerResult.bestTransientRatio`
- `AutoTunerResult.finalValidationTelemetry`

What it does not do:

- no full `PipelineResult`
- no slow-mover export
- no final track linking

Use this when you want to derive a better starting configuration before running the engine.

## `JTransientEngine.generateMasterStack(...)`

Signature:

```java
short[][] generateMasterStack(
        List<ImageFrame> inputFrames,
        DetectionConfig config,
        TransientEngineProgressListener listener
)
```

What it does:

1. sorts frames by `sequenceIndex`
2. runs `FrameQualityAnalyzer.evaluateFrame(...)` on every frame
3. rejects outlier frames with `SessionEvaluator`
4. builds a median master stack from the retained frames

What it returns:

- the median `short[][]` master stack

What it does not do:

- no per-frame `SourceExtractor` pass
- no drift diagnostics
- no master-star extraction
- no transients export
- no track linking

Use this when you want to reuse the same master stack across repeated `detectTransients(...)` or `runPipeline(...)` calls.

## `JTransientEngine.detectTransients(...)`

Signatures:

```java
List<JTransientEngine.FrameTransients> detectTransients(
        List<ImageFrame> inputFrames,
        DetectionConfig config,
        TransientEngineProgressListener listener
)

List<JTransientEngine.FrameTransients> detectTransients(
        List<ImageFrame> inputFrames,
        DetectionConfig config,
        TransientEngineProgressListener listener,
        short[][] providedMasterStack
)
```

This is the transient-only path of the engine. Internally it runs the same shared setup used by `runPipeline(...)`, then stops before point-track linking.

What it does:

1. runs border drift diagnostics and may raise `voidProximityRadius`
2. extracts sources from every frame
3. computes frame quality metrics
4. rejects outlier frames for the session
5. generates a median master stack, or uses `providedMasterStack`
6. extracts `masterStars` from that stack
7. calls `TrackLinker.filterTransients(...)`
   - separates streaks from point detections
   - links fast streaks
   - builds the stationary-star veto mask
   - removes masked point sources
   - merges surviving point transients and preserved streak detections

What it returns:

- one `FrameTransients` object per retained frame
- each item contains:
  - `filename`
  - `transients`
  - `extractionResult`

What it does not do:

- no slow-mover stack generation
- no time-based point tracking
- no geometric point tracking
- no anomaly rescue
- no maximum-stack export
- no `PipelineResult`

Use this when you want JTransient to do extraction plus stationary-star filtering, but you intend to do your own track assembly.

## `JTransientEngine.runPipeline(...)`

Signatures:

```java
PipelineResult runPipeline(
        List<ImageFrame> inputFrames,
        DetectionConfig config,
        TransientEngineProgressListener listener
)

PipelineResult runPipeline(
        List<ImageFrame> inputFrames,
        DetectionConfig config,
        TransientEngineProgressListener listener,
        short[][] providedMasterStack
)
```

This is the full engine entrypoint.

What it does:

1. runs the same shared setup as `detectTransients(...)`
2. generates or reuses the median master stack
3. extracts the master-star map
4. optionally builds and filters the slow-mover stack
5. calls `TrackLinker.findMovingObjects(...)`
   - fast streak linking
   - stationary-star veto masking
   - time-based point linking when timestamps are available
   - geometric fallback point linking
   - anomaly rescue
   - residual transient analysis on leftover non-streak point detections
6. records pipeline and tracker telemetry
7. builds the maximum stack for export

What it returns:

- `PipelineResult.tracks`
- `PipelineResult.telemetry`
- `PipelineResult.masterStackData`
- `PipelineResult.masterStars`
- `PipelineResult.slowMoverStackData`
- `PipelineResult.slowMoverCandidates`
- `PipelineResult.anomalies`
- `PipelineResult.allTransients`
- `PipelineResult.unclassifiedTransients`
- `PipelineResult.residualTransientAnalysis`
- `PipelineResult.masterVetoMask`
- `PipelineResult.driftPoints`
- `PipelineResult.telemetry.slowMoverTelemetry`
- `PipelineResult.maximumStackData`

Use this when you want the library to go end-to-end and return all track-like detections.

## What Changes When You Pass `providedMasterStack`

The overloads with `providedMasterStack` skip only the median-stack construction step.

They still do the following:

- per-frame extraction
- frame quality analysis
- session rejection
- master-star extraction from the provided stack
- stationary-star filtering
- track linking, if you called `runPipeline(...)`

So `providedMasterStack` is a performance shortcut, not a full cached pipeline state.

## Typical Workflows

### 1. Tune, then run the full pipeline

Use:

1. `JTransientAutoTuner.tune(...)`
2. `JTransientEngine.runPipeline(...)`

Best for one-off scientific runs or production processing.

### 2. Precompute the master stack for repeated runs

Use:

1. `JTransientEngine.generateMasterStack(...)`
2. `JTransientEngine.runPipeline(..., providedMasterStack)` or `detectTransients(..., providedMasterStack)`

Best for UIs or parameter iteration where stacking would otherwise be repeated.

### 3. Use JTransient only for candidate extraction

Use:

1. `JTransientEngine.detectTransients(...)`

Best when you want the stationary-star veto and streak handling but will run your own higher-level tracker afterward.

### 4. Use the extractor directly

Use:

1. `SourceExtractor.extractSources(...)`

Best when you only need single-frame objects and do not want any sequence-level logic.

## Related Documents

- `ALGORITHM.md`: internal `runPipeline(...)` detection phases
- `CONFIG.md`: `DetectionConfig` field-by-field reference
- `README.md`: basic usage examples
