# JTransient Pipeline Guide

This document explains what each public entrypoint does in the library and which parts of the pipeline it executes.

The public surface is split into three layers:

- standalone single-frame extraction via `SourceExtractor`
- sequence-level utilities and tracking via `JTransientEngine`
- configuration search via `CalibratedAutoTuner` (and the legacy `JTransientAutoTuner`)

## Pipeline Building Blocks

The full engine is assembled from these stages:

1. border drift diagnostics (blank and failed-registration frames are left out, then rejected)
2. per-frame source extraction
3. per-frame quality analysis
4. session-level frame rejection
5. median master-stack generation or reuse
6. master-star extraction
7. maximum-stack generation and optional slow-mover candidate analysis
8. stationary-star veto masking
9. fast streak linking
10. time-based point linking when timestamps exist
11. geometric point linking when timestamps are missing or explicitly enabled
12. anomaly rescue, suspected same-frame streak grouping, and streak consolidation
13. residual transient analysis
14. optional stationary-star photometry and variable-star detection
15. maximum-stack result export

Different entrypoints execute different subsets of that sequence.

## Entry Point Summary

| Entry point | Scope | Generates or uses a master stack | Returns tracks | Primary use |
| --- | --- | --- | --- | --- |
| `SourceExtractor.extractSources(...)` | One frame | No | No | Standalone object detection |
| `CalibratedAutoTuner.tune(...)` | Crops of a sample of frames | Internal cropped master stacks | No | Measure false positives and sensitivity, pick the most sensitive settings within a profile budget |
| `JTransientAutoTuner.tune(...)` (legacy) | Representative sample of frames | Internal cropped master stacks | No | Score-based configuration search |
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

## `CalibratedAutoTuner.tune(...)`

Signatures:

```java
JTransientAutoTuner.AutoTunerResult CalibratedAutoTuner.tune(
        List<ImageFrame> frames,
        DetectionConfig baseConfig,
        JTransientAutoTuner.AutoTuneProfile profile,
        TransientEngineProgressListener listener
)

CalibratedAutoTuner.Calibration CalibratedAutoTuner.calibrate(
        List<ImageFrame> frames,
        DetectionConfig baseConfig,
        TransientEngineProgressListener listener
)
```

What it does:

1. leaves out blank and failed-registration frames
2. cuts crops from the frames (centre and corners, or a grid on large sensors) and builds cropped median master stacks
3. measures the star FWHM and the residual jitter
4. builds, for every crop-frame, a negative image (noise only) and a copy with injected synthetic stars, and runs a streak pass to recognise satellite trails
5. evaluates every combination of detection sigma, grow sigma, minimum pixels, master sigma, master grow sigma, master minimum pixels and veto overlap: noise, star leakage, real-frame excess, recovery of the synthetic stars and mask coverage
6. picks, for each profile, the most sensitive combination within its false-positive budget (0.05, 0.2, 0.6 and 3.5 per megapixel per frame)

What it returns:

- `AutoTunerResult.success`, `optimizedConfig`, `summary` and `telemetryReport`
- with `calibrate(...)`: every measured combination and the choice for every profile

See `AUTOTUNER.md`.

## `JTransientAutoTuner.tune(...)` (legacy)

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
4. calibrates `maxStarJitter` from sampled crop stars
5. sweeps detection sigma, grow sigma, minimum pixels, and mask overlap
6. scores each combination against cropped master-stack behavior
7. validates the winning combination against the frozen tuning crops
8. returns an optimized config on success, or falls back to the base config

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

- no main transient `SourceExtractor` pass; the quality analyzer still extracts quality-reference stars
- no drift diagnostics
- no master-star extraction
- no transients export
- no track linking

Use this when you want to reuse the same master stack across repeated `detectTransients(...)` or `runPipeline(...)` calls.

## `JTransientEngine.detectTransients(...)`

Signatures:

```java
List<FrameTransients> detectTransients(
        List<ImageFrame> inputFrames,
        DetectionConfig config,
        TransientEngineProgressListener listener
)

List<FrameTransients> detectTransients(
        List<ImageFrame> inputFrames,
        DetectionConfig config,
        TransientEngineProgressListener listener,
        short[][] providedMasterStack
)
```

This is the transient-only path of the engine. Internally it runs the same shared setup used by `runPipeline(...)`, then stops before point-track linking.

What it does:

1. runs border drift diagnostics and may raise `voidProximityRadius`; frames where less than half of the pixels hold image data (blank frames, failed registrations) are left out of the drift analysis and rejected with the outlier frames
2. extracts sources from every frame
3. computes frame quality metrics
4. rejects outlier frames for the session
5. generates a median master stack, or uses `providedMasterStack`
6. extracts `masterStars` from that stack
7. calls `TrackLinker.filterTransients(...)`
   - separates streaks from point detections
   - builds the stationary-star veto mask
   - removes masked point sources and masked streaks
   - links surviving fast streaks
   - merges surviving point transients and preserved streak detections

What it returns:

- one `FrameTransients` object per retained frame
- each item contains:
  - `filename`
  - `transients`
  - `extractionResult`

What it does not do:

- no maximum-stack slow-mover analysis
- no time-based point tracking
- no geometric point tracking
- no anomaly rescue
- no maximum-stack export
- no variable-star photometry
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
4. builds the maximum stack once and, when enabled, finds slow-mover candidates from its geometric footprints, an exact median-stack object mask, and original-frame support measurements
5. calls `TrackLinker.findMovingObjects(...)`
   - stationary-star veto masking
   - fast streak linking
   - time-based point linking when timestamps are available
   - geometric point linking when timestamps are missing or `enableGeometricTrackLinking` is enabled
   - anomaly rescue
   - suspected same-frame streak grouping and streak consolidation
6. records pipeline and tracker telemetry
7. runs residual transient analysis on leftover non-streak point detections
8. when `enableVariableStarDetection` is on, runs stationary-star photometry and variable-star detection on the retained frames
9. exports the maximum stack, reusing the same array for slow-mover analysis when enabled

The slow-mover branch keeps maximum-stack `DetectedObject` instances whose geometric axis ratio lies within the configured window and whose fill factor passes the optional minimum. It measures each candidate's raw-pixel overlap with an undilated mask of median-stack objects. The optional lower overlap bound and the upper stationary-source veto use that fraction; the two stacks are never subtracted. It also measures frame-support and stationary-likelihood percentages from the original retained frames. Their default thresholds (`0` and `100`) record diagnostics without rejecting candidates. Its output remains a candidate, not a temporally confirmed mover.

The variable-star stage measures forced aperture photometry of isolated master stars in every retained frame. Moving detections, tracks and slow-mover footprints flag the measurements they contaminate. Readiness checks test whether the frames respond linearly to light and give a `READY`, `LIMITED` or `NOT_READY` verdict. An ensemble solve removes per-frame zero points and gradients. When the verdict allows it, each star is scored against stars of similar brightness, and candidates must then pass a set of gates. See `VariableStarAlgorithm.md`.

What it returns:

- `PipelineResult.tracks`
- `PipelineResult.telemetry`
- `PipelineResult.masterStackData`
- `PipelineResult.masterStars`
- `PipelineResult.slowMoverAnalysis`
- `PipelineResult.slowMoverStackData`
- `PipelineResult.slowMoverMedianVetoMask`
- `PipelineResult.slowMoverCandidates`
- `PipelineResult.anomalies`
- `PipelineResult.allTransients`
- `PipelineResult.unclassifiedTransients`
- `PipelineResult.residualTransientAnalysis`
- `PipelineResult.masterVetoMask`
- `PipelineResult.driftPoints`
- `PipelineResult.telemetry.slowMoverTelemetry`
- `PipelineResult.maximumStackData`
- `PipelineResult.variableStarAnalysis` (empty, verdict `NOT_RUN`, when disabled)
- `PipelineResult.telemetry.photometryTelemetry`

When slow-mover detection is enabled, `slowMoverStackData` is the same array as `maximumStackData`, and `slowMoverMedianVetoMask` is the same array as `slowMoverAnalysis.medianMask`.

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
- slow-mover analysis, if you called `runPipeline(...)` and it is enabled
- anomaly rescue and residual transient analysis, if you called `runPipeline(...)`
- variable-star photometry, if you called `runPipeline(...)` and it is enabled; stars are selected from, and positioned on, the provided stack
- maximum-stack export, if you called `runPipeline(...)`

So `providedMasterStack` is a performance shortcut, not a full cached pipeline state.

## Typical Workflows

### 1. Tune, then run the full pipeline

Use:

1. `CalibratedAutoTuner.tune(...)`
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

## Progress Reporting

`runPipeline(...)` reports monotonic progress through the `TransientEngineProgressListener`:

| Range | Stage |
| --- | --- |
| 0-40% | drift diagnostics and per-frame extraction |
| 42% | session-level frame rejection |
| 45-49% | master stack, master stars, slow-mover analysis |
| 50-90% | stationary-star veto and track linking |
| 92% | residual transient analysis |
| 93-99% | variable-star photometry (when enabled) |
| 100% | complete |

`detectTransients(...)` reports extraction and frame rejection up to 42%, the master stack at 45-48% and the stationary-star veto at 60-100%.

## Related Documents

- `AUTOTUNER.md`: calibrated and legacy auto-tuners

- `ALGORITHM.md`: internal `runPipeline(...)` detection phases
- `VariableStarAlgorithm.md`: variable-star photometry stage
- `CONFIG.md`: `DetectionConfig` field-by-field reference
- `README.md`: basic usage examples
