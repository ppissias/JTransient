# Design improvements

This file lists design, refactoring and code-quality work for JTransient: changes to how the engine's code is organised, not to what it detects. Detection, tuning and photometry algorithm proposals are in [FEATURE-REQUESTS.md](FEATURE-REQUESTS.md). The current design is in [DEVELOPER.md](DEVELOPER.md), [PIPELINE.md](PIPELINE.md) and [ALGORITHM.md](ALGORITHM.md).

Every item is a proposal until the maintainer decides on it. When an item is done, describe the result in the reference documents and remove it from this file; git history keeps the record. A refactoring must leave the results byte-identical on the reference datasets (see [DEVELOPER.md](DEVELOPER.md#real-data-checks)).

## Code review of 2026-10-23

A fresh review of the engine (about 20,000 lines of main code and 7,700 of tests), against the principles in [DEVELOPER.md](DEVELOPER.md#principles): readable code, clear boundaries, a few patterns applied consistently, and no abstraction without a real need.

**Overall.** The scientific code is in good shape. Since the track linker was split into stages, the core reads as a sequence of named stages with explicit inputs and outputs. The model types are small. `PipelineResult` has a builder, and the stage observer is small and isolated. The over-engineering is in the infrastructure around the science:

- the execution layer has three thread-bound "ambient" scopes;
- the settings rules model more dimensions than their single consumer uses;
- the tuner has an options builder that nobody calls;
- the engine's orchestration method is buried under repeated cross-cutting code;
- compatibility aliases are kept although the libraries are now developed together with SpacePixels.

### What is good and should stay

- **The stage classes** in `core` (`TransientVeto`, `StreakLinker`, `TimedPointLinker`, `GeometricPointLinker`, `AnomalyRescuer`, `StreakConsolidator`, `TrackingExports`, with `DetectionAssignments` making claims explicit), orchestrated by `TrackLinker`.
- **The model** (`model.DetectedObject`, `Track`, `AnomalyDetection`, `Pixel`) and the `PipelineResult` builder.
- **`WorkerPool`:** one pool per engine, with results in task order and a joined failure or cancellation.
- **`CancellationScope`:** the one ambient scope that pays for itself. Cancellation must reach deep loops without a parameter in every signature.
- **`PipelineStageObserver`:** an explicit, optional parameter, isolated from failures.
- **Input and area rules** (`InputRules`, `FrameCoverage`, `DataArea`): small, documented and tested.

### Recommended changes

| ID | Change | Priority | Size |
|---|---|---|---|
| [JT-01](#jt-01-remove-dead-code-and-compatibility-aliases) | Remove dead code and compatibility aliases | High | Small (with SpacePixels callers) |
| [JT-02](#jt-02-shrink-the-settings-rules-to-what-is-used) | Shrink the settings rules to what is used | Medium | Medium |
| [JT-03](#jt-03-label-failures-at-stage-boundaries-without-a-thread-local) | Label failures at stage boundaries, without a thread-local | Medium | Medium |
| [JT-04](#jt-04-diagnostics-through-javautillogging) | Diagnostics through `java.util.logging` | Medium | Medium |
| [JT-05](#jt-05-let-the-pipeline-method-read-as-the-algorithm) | Let the pipeline method read as the algorithm | Medium | Small |
| [JT-06](#jt-06-tuner-constants-instead-of-an-options-builder) | Tuner constants instead of an options builder | Low | Small |
| [JT-07](#jt-07-split-the-largest-classes-along-their-phases-when-they-change) | Split the largest classes along their phases when they change | Low | Guidance |
| [JT-08](#jt-08-upgrade-the-dependencies) | Upgrade the dependencies | Medium | Small |
| [JA-11](#ja-11-validate-the-selected-release-artifacts) | Validate the selected release artifacts | Release gate | Process |

**Suggested order.** JT-01, then JT-05 (it makes JT-03 and JT-04 easy to see), then JT-03 and JT-04 together, then JT-02 and JT-06. JA-11 runs at each release.

### JT-01. Remove dead code and compatibility aliases

**Problem.**
- `core.SpatialGrid` (216 lines) has no users anywhere: not in the main code, the tests or SpacePixels.
- `PipelineResult.slowMoverStackData` is the same array as `maximumStackData`. `slowMoverMedianVetoMask` is the same as `SlowMoverAnalysis.medianMask`, and `slowMoverCandidates` lists the same objects as `SlowMoverAnalysis.candidates`. `SlowMoverAnalysis` keeps further aliases.
- `SlowMoverSummaryTelemetry.toLegacyTelemetry(candidates)` converts to older counters, and its parameter is "retained for compatibility" and unused.
- `TrackerTelemetry` keeps a "legacy zero-based position", which `ExtractedFramesContext.labelTrackerFrames` relabels afterwards.

The libraries are now developed together with SpacePixels, so none of these needs to survive.

**Proposal.** Delete `SpatialGrid`. Keep one name per product, drop the aliases and the legacy telemetry conversion, and update SpacePixels' report code (about 70 references) in the same change. Record the original frame identity in the tracker telemetry directly.

### JT-02. Shrink the settings rules to what is used

**Problem.** `DetectionConfigRules` (517 lines) builds metadata for all 107 `DetectionConfig` fields by reflection: a stage, a "recommended domain" and a default for each. On top of that it adds:
- six `Operation` scopes and three `Timing` states, which decide whether a field is "active";
- three severities;
- two alignment policies, `LEGACY_GUI` and `REVIEWED_CONSISTENCY`;
- `Issue` records with maps of evidence.

Its users, the engine's entry points and SpacePixels, need four things:
1. reject non-finite numbers;
2. keep seven pairs ordered (grow sigma ≤ sigma, and so on);
3. show a handful of advisory findings in "Review rules…";
4. check that a typed setting's Java type matches the field.

The per-field domains duplicate the ranges of the GUI's spinners, so there are two sources of numeric ranges. The operation and timing scoping lets a disabled stage hold a non-finite value, which no real profile does.

**Proposal.**
- Validate every number as finite, always.
- Keep the pairs and the advisory checks as they are.
- Keep `metadata()` only for the type check, or drop it in favour of the setting definitions' own types.
- Remove the operation and timing scoping, the per-field domains and the second alignment policy, unless the maintainer wants the "reviewed consistency" choice in the GUI.

**Benefit.** About half the class goes, and the rules a reader has to understand are the ones that act.

**Verification.** Profile loading, "Review rules…" and the API reject or accept the same inputs as before (unit tests). Detection results are unchanged.

### JT-03. Label failures at stage boundaries, without a thread-local

**Problem.** `OperationContext` keeps the current stage and frame in a `ThreadLocal`. The engine, tuner and photometry call `OperationContext.compute`, `call`, `run` and `notify` 68 times so that a failure can name its stage and frame (`OperationFailure`, `OperationFailedException`, `StageId`, `FrameIdentity`). `WorkerPool` copies the context into worker batches, and progress callbacks are wrapped too. SpacePixels never reads the structured failure; it shows the exception's message.

**Proposal.** One private stage runner in the engine (see JT-05) catches a failure at each stage boundary and rethrows it with the stage name, and with the frame where a stage loops over frames. Keep `OperationFailedException` with the message and the original cause. Remove the `ThreadLocal` and the wrapping calls. Keep `StageId` only if a caller needs to branch on it.

**Benefit.** The stage code shows the science only. A failure still says "failed at TRACKING for frame 12 [name.fits]: …".

### JT-04. Diagnostics through java.util.logging

**Problem.** `DiagnosticContext` and `DiagnosticOptions` route debug text through a per-operation `ThreadLocal` sink: 139 calls, most of them `if (DiagnosticContext.enabled()) DiagnosticContext.println/printf(...)`. SpacePixels bridges to it with `DebugOutput.openEngineDiagnostics()` around every engine call. Per-operation routing would matter for several concurrent runs with different sinks; SpacePixels runs one job at a time.

**Proposal.** Log through `java.util.logging` loggers at `FINE`, which is standard, needs no scope and costs nothing when off (`logger.isLoggable(FINE)`). SpacePixels' debug option sets the level and the console handler. Remove `DiagnosticContext`, `DiagnosticOptions` and the bridge.

### JT-05. Let the pipeline method read as the algorithm

**Problem.** `JTransientEngine.runPipelineInternal` is a long procedural method in which the stage calls are a small part. For each stage it repeats the same pattern:
- a diagnostic banner;
- `OperationContext.notify` for progress;
- `OperationContext.compute` around the call;
- `telemetry.recordStage` timing;
- the observer notification;
- often a large block of `printf` lines that format the telemetry.

The phase comments are numbered 0, 0.5, 4 and 5.

**Proposal.** A small private helper: `runStage(stage, percent, message, work)` handles timing, progress and the failure label. Move the telemetry printing into `PipelineTelemetry`'s own formatter, which already writes a text report. Name the phases by what they do. The method then reads as: extract, select frames, build the reference, slow movers, tracking, common motion, residual analysis, photometry.

### JT-06. Tuner constants instead of an options builder

**Problem.** `CalibratedAutoTuner.Options` and its `Builder` (about 300 lines, with defensive copies and validation) exist so that a caller could change the tuning grids. No caller does. SpacePixels only reads `Options.defaults().falsePositiveBudgetPerMpixFrame()[profile.ordinal()]`.

**Proposal.** Private constants in the tuner. Make the false-positive budget a property of `AutoTuneProfile` (`profile.falsePositiveBudget()`).

### JT-07. Split the largest classes along their phases when they change

`CalibratedAutoTuner` (1,976 lines), `VariableStarAnalyzer` (1,291) and `JTransientEngine` (1,082) are large but each holds one responsibility. Do not split them for their size. When one of them changes for another reason, extract the phase being changed, as was done for the track linker. For the tuner, that would be crops and injection, the sweep, the selection, or the trade-off; for photometry, measurement or scoring.

### JT-08. Upgrade the dependencies

Part of SpacePixels' [SP-18](../SpacePixels/DESIGN-IMPROVEMENTS.md#sp-18-upgrade-the-dependencies), which holds the version table, the risks and the verification for all three repositories. For JTransient:
- the Gradle wrapper goes from 9.2.1 to 9.8.2;
- Gson goes from 2.13.2 to 2.14.0;
- JUnit stays at 4.13.2;
- the release stays at Java 21, so that the library reaches the most users.

Detection results must stay byte-identical.

### JA-11. Validate the selected release artifacts

At each SpacePixels release:
1. Select and pin the JTransient and JPlateSolve versions.
2. Make those exact artifacts available in the release repository.
3. Run the application and consumer checks with both composite builds disabled and without the local validation repository, following the [selected-artifact workflow](../SpacePixels/docs/release-artifacts.md).
4. Verify the resolved versions, metadata and the headless/desktop separation.

Local staging is packaging evidence, not proof that the artifacts are publicly available.

## Removed from the previous plan (2026-10-23)

- **JA-09, typed progress events.** Not needed. The stage observer that SpacePixels uses is done, and nothing needs the rest.
- **The target package layout, the class-separation analysis and the migration table.** The package layout is described in DEVELOPER.md; nothing schedules a migration.
- **The completed and closed records in `docs/archive`.** Removed; git history keeps them. The binding "declined" decisions are kept in [FEATURE-REQUESTS.md](FEATURE-REQUESTS.md#declined).
