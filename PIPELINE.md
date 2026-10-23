# JTransient API And Pipeline Guide

This is the API contract reference: signatures, input rules, entrypoint scope, result fields, ownership and progress. Algorithm details live in [ALGORITHM.md](ALGORITHM.md); `DetectionConfig` defaults live in [CONFIG.md](CONFIG.md).

**Release policy (2026-10-23):** this documents current behavior, not a promise to preserve these Java signatures across library versions. SpacePixels/JTransient/JPlateSolve develop together and pin one exact combination for each application release. Historical engine entry-point/version/debug adapters have been removed; see the [policy and review](docs/archive/architecture-clarifications-2026-10-23.md#maintainer-questions-and-release-model-clarification-2026-10-23). Input snapshots, scientific behavior, ownership and persisted user data have separate contracts from historical API compatibility.

The public surface is split into three layers:

- standalone single-frame extraction via `SourceExtractor`
- sequence-level utilities and tracking via `JTransientEngine`
- configuration search via `CalibratedAutoTuner`

## Pipeline Scope

`runPipeline(...)` executes the complete analysis. `generateMasterStack(...)` shares registration/quality selection but returns only a median stack. `detectTransients(...)` adds extraction and stationary-star/streak filtering, then stops before point linking. Tuners search configurations on sampled crops; `SourceExtractor` works on one image. The [algorithm flow](ALGORITHM.md#high-level-flow) defines stage ordering.

### Prepared-frame associations (1.0.6)

Preparation associates each borrowed frame descriptor with its metrics, extraction and registration, retaining empty detection slots. Original sequence IDs are not retained-list positions; tracker telemetry exposes both.

The ordinary `runPipeline`, `detectTransients` and `generateMasterStackWithDetails` APIs now sort a private list copy, accepting unsorted/unmodifiable inputs without changing caller order. There are no parallel `*Snapshot` engine methods. Pixel matrices/rows and descriptors are borrowed, not deep-copied; do not mutate them until joined completion/cancellation.

Photometry's ordinary `analyze` captures/validates aligned containers and a configuration copy. `Input.snapshot()` is the reusable input-value operation: metrics are required for every frame, known labels/detection IDs must agree, and empty slots remain present; a whole absent detection list is allowed. Light-curve arrays follow `VariableStarAnalysis.frames`, whose indices identify original sequence IDs. There is no separate permissive analysis API. Measurements/detections and large buffers remain shared.

## Per-operation tuner options (1.0.6)

`CalibratedAutoTuner` exposes immutable `Options`, `Options.builder()`, `Options.defaults()` and `toBuilder()`. Builders copy fixed private defaults; build and array accessors defensively copy grids. Explicit options control crop/search/budget policy without mutable global knobs. The calibration retains the effective policy. Pixels remain borrowed until joined completion.

## Per-operation diagnostics (1.0.6)

Open `DiagnosticContext.open(new DiagnosticOptions(enabled, sink))` around engine, tuner or standalone science work. All core and tuner text uses the same operation policy. Library defaults are silent: there are no engine debug globals, registration bridges or separate tuner text sink settings. Engine/tuner boundaries inherit scopes; managed workers propagate policy and restore prior state, including failure/cancellation cleanup.

Sinks receive formatted strings synchronously on caller/worker threads and must be thread-safe when shared. They must not mutate scientific inputs; slow/throwing sinks can delay/fail an operation. No asynchronous logger is implied. Sink fields are transient and JSON does not recreate callbacks.

SpacePixels captures `spacepixels.debug` through `DebugOutput.openEngineDiagnostics()` at engine/tuner boundaries and for standalone extraction. `openIfAbsent` respects an explicitly supplied caller scope, even an explicit disabled policy, rather than overriding it with application defaults. Later application-property changes do not change a running scope. Normal application logger configuration remains owned by SpacePixels.

## Cancellation and worker lifetime

Runtime diagnostic sinks are transient and excluded from serialized option provenance. Captured policy JSON is not a factory for restoring a live operation or callback; construct new validated options/builders for execution.

The cancellation types live in `io.github.ppissias.jtransient.execution`. To make an engine, tuner or standalone call cancellable, run it inside a cancellation scope on the calling thread:

```java
AtomicBoolean cancelled = new AtomicBoolean();
try (JTransientEngine engine = new JTransientEngine();
     CancellationScope scope = CancellationScope.open(cancelled::get)) {
    PipelineResult result = engine.runPipeline(frames, config, listener);
}
```

Another thread can set the flag. Scopes belong to their opening thread, restore the previous token when closed, and are carried into JTransient's worker batches. They do not change another operation's cancellation. Without a scope, an operation cannot be cancelled.

Cancellation throws `java.util.concurrent.CancellationException` and discards the partial scientific result. Extraction and image scans check between rows or bounded groups of pixels; tracking, tuning and photometry check their work loops. Waiting batches also poll cancellation. Interruption is treated as cancellation and the interrupt status is preserved. A blocking caller-supplied listener or an individual uninterruptible operation can still delay return; cancellation is cooperative, not a hard real-time guarantee.

When cancellation or a worker/listener failure occurs, a batch stops queued work, requests that running siblings stop, and waits for their task bodies and cleanup to end before returning. Worker exceptions retain their original cause. Calibrated tuning propagates cancellation instead of translating it to unsuccessful calibration. Other calibration failures still return the existing unsuccessful result, with the original exception available as `Calibration.failureCause`; this runtime-only field is excluded from saved JSON. Insufficient evidence keeps its ordinary unsuccessful-calibration outcome.

An engine admits one operation at a time. An overlapping operation, or `shutdown()`/`close()` during an active operation, throws `IllegalStateException`. Request cancellation and wait for the operation to return before closing it. The engine may be reused sequentially after cancellation or ordinary failure. Closing an idle engine waits for its worker threads to end and preserves interruption. Each tuner call starts and stops its own workers. A reported percentage, including 100%, is progress rather than a terminal outcome.

## Worker threads (1.0.6)

Each engine owns a pool of worker threads, one per available processor; each tuner call has its own. An operation's batches (frame extraction and quality, stack rows, calibration crops and photometry frames) use at most the smaller of the pool size, the number of work items and a scratch-memory estimate: 16 bytes per image pixel plus 1 MiB per worker, within a quarter of the maximum heap and half of the heap now free. The estimate is not a reservation; input and output buffers, retained crop/mask populations and unrelated JVM allocations are outside it. Results keep the task order. A batch started on a worker runs inline on that worker, so nested batches never wait for a full pool. Called outside an engine or tuner operation, a standalone stage such as `MasterMapGenerator.createMedianMasterStack` or `VariableStarAnalyzer.analyze` runs its batch on the calling thread.

`WorkerPool` and `OperationContext` are library-internal; they are public only so the library's own packages can share them. Each engine admits one active operation; this does not limit other engines or tuners in the JVM. Applications own admission control and dataset retention.

## Structured operation outcomes (1.0.6)

The canonical engine and tuner methods return scientific results or throw `OperationFailedException`. Its `failure()` gives stable stage, optional original frame identity, message and original cause; `getCause()` retains the scientific/listener exception. This is the contract used by ordinary SpacePixels calls, so its jobs/API/GUI receive useful stage/frame failure messages without an alternative engine entry point.

Cooperative cancellation/interruption remains `CancellationException`, after workers join; fatal errors propagate. Invalid consumed configuration is a structured `INPUT_VALIDATION` failure retaining the original rule issues in its cause. Engine admission/closed-instance errors and malformed option-builder arguments remain direct API-usage errors. Insufficient tuning evidence is a normal unsuccessful result with detached fallback settings, not an execution failure. Calibrated runtime failures throw instead of being hidden in a `failureCause` field.

`OperationContext.execute` supplies that throwing boundary. Stage/frame scopes label work, and the worker pool carries them into its threads. A nested operation recognizes an already structured exception and keeps its original metadata and cause rather than relabeling it.

Progress callbacks remain synchronous and may fail even at 100%; progress is not a terminal success signal. Typed stage progress/outcome details remain JA-09.


## Input Rules

Configuration capture/validation happens before frame processing; invalid configuration can therefore be reported before an invalid frame sequence.

Every `JTransientEngine` entry point and both auto-tuners check their frames and throw `IllegalArgumentException`, naming the frames, when:

- the frame list is empty (pipeline entry points), frames have no pixels or ragged rows, frames differ in size, or two frames share a sequence index;
- some frames have a timestamp and others don't (`-1` means missing; 0 is a real time);
- some frames have an exposure time and others don't, or the exposure times differ by more than 2 % (at least 0.1 s);
- a supplied master stack has another size than the frames.

`TrackLinker.findMovingObjects` throws when some detections have a timestamp and others have none. These are the SpacePixels import rules that still apply once the pixels are in memory.

Timestamps are taken as exposure starts. The linker compares them directly; SpacePixels converts to exposure midpoints only for the report.

## Configuration snapshots and shared rules

`DetectionConfig` keeps its public primitive fields and Gson profile shape. Each engine operation, tuner and direct `calibrate` captures its caller's config before progress/worker execution and uses private stage copies thereafter. Later caller edits cannot change that operation. The primitive copy is not a transaction across concurrent edits: synchronize callers that write several fields during capture. Pixel arrays and frame-list ownership are not changed by this config contract. Tuner options and core diagnostics have their separate operation-scoped capture contracts.

`DetectionConfigRules` is supported public API, independent of SpacePixels and Swing:

- `metadata()` covers all 107 fields with ID, type, default, stage and recommended domain; `pairs()` exposes shared ordering policies.
- `validate(config, operation[, timing])` returns immutable issues containing rule ID, severity, stage, fields and values, without changing the config.
- `snapshot(config, operation[, timing])` clones and throws `InvalidConfigurationException` for error-level issues; `issues()` retains the structured diagnostics.
- `align(config, operation[, policy])` returns a detached config and immutable adjustments (rule/stage/field IDs, old/new values and direction). Engine execution never requests alignment implicitly.

| Operation scope | Settings consumed |
|---|---|
| `CONFIGURATION` | All persisted numbers must be finite, including disabled preferences; advisory relationships follow enabled stages. Used by SpacePixels profiles/API requests. |
| `MASTER_STACK` | Background, extraction shape classification shared by quality, quality metrics and session rejection; not ordinary detection/master-reference thresholds. |
| `TRANSIENTS` | Shared preparation, frame extraction, master-reference extraction/veto; not downstream linking, optional slow movers, residual analysis or photometry. |
| `PIPELINE` | Shared preparation/extraction/reference plus linking and enabled optional stages. Timing scopes geometric-branch diagnostics. |
| `CALIBRATION` | The tuner's background/shape settings, master seed/minimum and the base pixel minimum used for streak corridors; no quality or downstream-stage settings. |
| `EXTRACTION` | Standalone background, extraction and shape settings. Low-level helpers do not automatically capture/validate on every call; direct users can request this scope explicitly. |

Non-finite consumed values are errors; finite out-of-domain or cross-field findings remain warnings/information for legacy compatibility. `LEGACY_GUI` retains the original six pair adjustment directions. `REVIEWED_CONSISTENCY` adds quality-grow and limited-amplitude policies and excludes rhythm/jump, which is not universally bounded after pruning. Neither policy invents a jitter/cap choice or persists resolved annulus factors. See [CONFIG.md](CONFIG.md#shared-rules-and-compatibility).

`CalibratedAutoTuner.tune(frames, base, profile, listener[, cancellation])` returns independent successful **and fallback** configs; inspect `success`. Direct `CalibratedAutoTuner.calibrate` also uses captured settings. Cancellation remains distinct from fallback. These APIs first appear in the unreleased `1.0.5` source line; a published dependency pair is not certified by the local composite tests.

## Entry Point Summary

| Entry point | Scope | Generates or uses a master stack | Returns tracks | Primary use |
| --- | --- | --- | --- | --- |
| `SourceExtractor.extractSources(...)` | One frame | No | No | Standalone object detection |
| `CalibratedAutoTuner.tune(...)` | Crops of a sample of frames | Internal cropped master stacks | No | Measure false positives and sensitivity, pick the most sensitive settings within a profile budget |
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
AutoTunerResult CalibratedAutoTuner.tune(
        List<ImageFrame> frames,
        DetectionConfig baseConfig,
        AutoTuneProfile profile,
        TransientEngineProgressListener listener
)

CalibratedAutoTuner.Calibration CalibratedAutoTuner.calibrate(
        List<ImageFrame> frames,
        DetectionConfig baseConfig,
        TransientEngineProgressListener listener
)
```

`tune(...)` measures all profile choices and returns the requested optimized config plus `success`, `summary`, `telemetryReport`, `noisePerMpixFrame` (the chosen setting's measured noise detections per megapixel per frame) and the complete `calibration`. On early failure it returns a fallback/base configuration; inspect `success` before treating the result as tuned.

`calibrate(...)` returns the measured candidates, FWHM, jitter, measured area and the per-profile `chosen`, `withinBudget` and `budgetVerifiable` arrays. Reuse it without repeating the measurement:

```java
DetectionConfig CalibratedAutoTuner.configFor(
        CalibratedAutoTuner.Calibration calibration,
        DetectionConfig baseConfig,
        AutoTuneProfile profile)
```

The result is a base-config clone with the selected values applied. The [calibrated algorithm](ALGORITHM.md#calibrated-auto-tuning) defines measurements, grids and profile budgets.

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
