# JTransient API And Pipeline Guide

This is the API contract reference: signatures, input rules, entrypoint scope, result fields, ownership and progress. Algorithm details live in [ALGORITHM.md](ALGORITHM.md); `DetectionConfig` defaults live in [CONFIG.md](CONFIG.md).

The public surface is split into three layers:

- standalone single-frame extraction via `SourceExtractor`
- sequence-level utilities and tracking via `JTransientEngine`
- configuration search via `CalibratedAutoTuner` (and the legacy `JTransientAutoTuner`)

## Pipeline Scope

`runPipeline(...)` executes the complete analysis. `generateMasterStack(...)` shares registration/quality selection but returns only a median stack. `detectTransients(...)` adds extraction and stationary-star/streak filtering, then stops before point linking. Tuners search configurations on sampled crops; `SourceExtractor` works on one image. The [algorithm flow](ALGORITHM.md#high-level-flow) defines stage ordering.

## Input Rules

Every `JTransientEngine` entry point and both auto-tuners check their frames first and throw `IllegalArgumentException`, naming the frames, when:

- the frame list is empty (pipeline entry points), frames have no pixels or ragged rows, frames differ in size, or two frames share a sequence index;
- some frames have a timestamp and others don't (`-1` means missing; 0 is a real time);
- some frames have an exposure time and others don't, or the exposure times differ by more than 2 % (at least 0.1 s);
- a supplied master stack has another size than the frames.

`TrackLinker.findMovingObjects` throws when some detections have a timestamp and others have none. These are the SpacePixels import rules that still apply once the pixels are in memory.

Timestamps are taken as exposure starts. The linker compares them directly; SpacePixels converts to exposure midpoints only for the report.

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
5. keeps point-source footprints and streak centroids inside the search area (the default is the image minus a 3 px margin)

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

The four-argument overload measures the sky over the whole image; it does not infer registration padding. The overloads taking `backgroundArea` and `searchArea` let callers supply coverage-derived areas. Use `DataArea.ofFrame(image)` for a standalone registered-frame area, or the engine for session-wide intersections. There are no darkness/void probes.

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

`tune(...)` measures all profile choices and returns the requested optimized config plus `success`, `summary`, `telemetryReport` and the complete `calibration`. On early failure it returns a fallback/base configuration; inspect `success` before treating the result as tuned.

`calibrate(...)` returns the measured candidates, FWHM, jitter, measured area and the per-profile `chosen`, `withinBudget` and `budgetVerifiable` arrays. Reuse it without repeating the measurement:

```java
DetectionConfig CalibratedAutoTuner.configFor(
        CalibratedAutoTuner.Calibration calibration,
        DetectionConfig baseConfig,
        JTransientAutoTuner.AutoTuneProfile profile)
```

The result is a base-config clone with the selected values applied. The [calibrated algorithm](ALGORITHM.md#calibrated-auto-tuning) defines measurements, grids and profile budgets.

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

The no-profile overload uses MEDIUM. Successful tuning returns an optimized clone; fallback paths may return the base config. Inspect `AutoTunerResult.success`, `optimizedConfig`, `telemetryReport`, `bestStarCount` and `bestTransientRatio`.

`finalValidationTelemetry` records the winner's replay on frozen crops, or is null before a winner exists. It distinguishes execution, crop completion and hard-gate validity; reports object/stable/transient counts, coverage and variability metrics; and carries a status message and failed-crop label. A winning score alone does not guarantee every replay gate passed. This method does not return a full pipeline result. See the [legacy algorithm](ALGORITHM.md#legacy-auto-tuning).

## `JTransientEngine.generateMasterStack(...)`

Signature:

```java
short[][] generateMasterStack(
        List<ImageFrame> inputFrames,
        DetectionConfig config,
        TransientEngineProgressListener listener
)
```

The method uses the same registration/coverage and quality selection as `runPipeline(...)`, then constructs a median stack of retained frames. It does not perform the main transient pass, extract master stars or link tracks.

What it returns:

- the median `short[][]` master stack
- `generateMasterStackWithDetails(...)` also returns the kept frames and the rejected frames with their reasons (`MasterStackResult`)

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

It shares the full pipeline's registration, per-frame extraction/quality, rejection, kept-frame data area and median/master-star preparation. `TrackLinker.filterTransients(...)` applies the stationary-star veto, separates point/streak populations and links surviving fast streaks. It stops before camera-fixed point rejection, point linking, anomaly rescue, common-motion/residual analysis and photometry. A provided stack skips only median construction.

What it returns:

- one `FrameTransients` object per retained frame
- each item contains:
  - `filename`
  - `transients`
  - `extractionResult`

It returns neither a maximum stack nor a `PipelineResult`. Use this when you want JTransient to do extraction plus stationary-star filtering, but you intend to do your own track assembly.

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

This is the full engine entrypoint. It executes the [complete algorithm](ALGORITHM.md#high-level-flow), including camera-fixed rejection, common-motion filtering and the enabled slow-mover/residual/photometry branches.

Common-motion filtering has its own switch and removes groups before optional residual rescue/clustering; groups remain available even when residual analysis is disabled. Camera-fixed groups are removed from `allTransients`; common-motion groups remain in it for review. Slow-mover outputs are morphology candidates, not temporally confirmed movers. These category meanings are independent of application report-inclusion choices.

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
- `PipelineResult.dataArea` (kept-frame coverage intersection with the search margin)
- `PipelineResult.cameraFixedGroups` (detections removed from `allTransients` before point linking)
- `PipelineResult.telemetry.slowMoverTelemetry`
- `PipelineResult.maximumStackData`
- `PipelineResult.variableStarAnalysis` (empty, verdict `NOT_RUN`, when disabled)
- `PipelineResult.telemetry.photometryTelemetry`

When slow-mover detection is enabled, `slowMoverStackData` is the same array as `maximumStackData`, and `slowMoverMedianVetoMask` is the same array as `slowMoverAnalysis.medianMask`.

Result image/mask arrays are shared data, not defensive deep copies. In particular, slow-mover compatibility aliases refer to the same arrays. Treat engine outputs as read-only when passing them between viewers or analysis stages.

### Photometry outputs and indexing

`VariableStarAnalysis`:

- `readiness`: verdict, status of each check, the measurements behind them, and `messages`
- `frames`: one `PhotometryFrameStat` per clean frame, holding FWHM, aperture radius, sky, registration offset and spread, floor-clipped share, CI profile and linear limit, response slope, zero point and plane terms, star counts, flag counts, and the exclusion reason. Every light-curve array is aligned with this list.
- `stars`: one `StarLightCurve` per selected star, holding position, master flux, `meanMag`, per-frame `deltaMag`, `magError` and `flags`, the scores, gate diagnostics, tier and `notScoredReason`
- `candidates`, `highConfidenceCandidates()`
- `telemetry`: selection and exclusion counters, measurement-flag counts, gate failure counts, the noise model (scatter against magnitude per scored star, with tier), session FWHM, saturation level, and processing time

`telemetry.framesExcludedSpatial` records spatial exclusions; the aggregate text report includes it alongside other frame-exclusion counts. `telemetry.processingTimeMs` is the elapsed photometry duration. The full engine also records this phase in `PipelineTelemetry.stageTimeMs`; it is part of the engine total, not an additional cost to add to it.

Frame times are mid-exposure Julian dates (`timestamp + exposure / 2`). They are `NaN` without timestamps.


Use this when you want the library to go end-to-end and return all track-like detections.

## Timing And Frame-Exclusion Telemetry

`PipelineResult.telemetry.stageTimeMs` maps `PipelineTelemetry.Stage` values to elapsed milliseconds in execution order. Its stages are `DRIFT_ANALYSIS`, `SOURCE_EXTRACTION`, `QUALITY_FILTERING`, `MASTER_REFERENCE`, `MAXIMUM_STACK`, `SLOW_MOVERS`, `TRACKING`, `COMMON_MOTION`, `RESIDUAL_ANALYSIS`, and `PHOTOMETRY`. Only phases that ran are present; durations are measured using a monotonic clock.

The phases do not overlap. Parallel extraction/photometry report elapsed wall time rather than summed worker CPU time. `processingTimeMs` includes the full engine run through photometry and orchestration overhead, but excludes application file loading, Auto-Tune and report export. With a provided master, master-reference timing includes only reuse and star extraction. `Stage.getLabel()` supplies a display name.

`trackerTelemetry.faintStreakFragmentTimeMs` is a tracking substep and is already included in `stageTimeMs.get(Stage.TRACKING)`; -1 means the pass did not run. Do not add it to the major-phase sum. The text telemetry report includes both the phase table and this substep.

`photometryTelemetry.framesExcludedSpatial` counts frames removed for a spatial brightness residual after the initial ensemble fit and before response check D. Their per-frame records retain `used = false` and `exclusionReason`. The text report includes this count alongside registration, star-shape, response and insufficient-star exclusions. These exclusions apply to variable-star photometry, not to the main transient detections.

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
- anomaly rescue, common-motion filtering and optional residual transient analysis, if you called `runPipeline(...)`
- variable-star photometry, if you called `runPipeline(...)` and it is enabled; stars are selected from, and positioned on, the provided stack
- maximum-stack export, if you called `runPipeline(...)`

So `providedMasterStack` is a performance shortcut, not a full cached pipeline state.

## Typical Workflows

Tune once and pass `optimizedConfig` to `runPipeline(...)` for a complete run. Precompute a median stack and use a `providedMasterStack` overload for repeated threshold experiments. Choose `detectTransients(...)` only for per-frame post-veto candidates, or `SourceExtractor` for raw single-frame detections. [README.md](README.md#basic-usage) contains runnable usage examples.

## Progress Reporting

`runPipeline(...)` reports monotonic progress through the `TransientEngineProgressListener`:

| Range | Stage |
| --- | --- |
| 0-40% | drift diagnostics and per-frame extraction |
| 42% | session-level frame rejection |
| 45-49% | master stack, master stars, slow-mover analysis |
| 50-90% | stationary-star veto and track linking |
| 92% | common-motion filtering and optional residual transient analysis |
| 93-99% | variable-star photometry (when enabled) |
| 100% | complete |

`detectTransients(...)` reports extraction and frame rejection up to 42%, the master stack at 45-48% and the stationary-star veto at 60-100%.

## Related Documents

- [README.md](README.md): setup and usage examples
- [ALGORITHM.md](ALGORITHM.md): detection, slow movers, variable stars and Auto-Tune
- [CONFIG.md](CONFIG.md): `DetectionConfig` fields/defaults
- [DESIGN-IMPROVEMENTS.md](DESIGN-IMPROVEMENTS.md): proposals, decisions and deferred findings
