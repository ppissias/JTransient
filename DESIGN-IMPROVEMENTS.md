# Design improvements

This file lists design, refactoring and code-quality work for JTransient: changes to how the engine's code is organised, not to what it detects. Detection, tuning and photometry algorithm proposals are in [FEATURE-REQUESTS.md](FEATURE-REQUESTS.md). The current design is in [DEVELOPER.md](DEVELOPER.md), [PIPELINE.md](PIPELINE.md) and [ALGORITHM.md](ALGORITHM.md).

Every item is a proposal until the maintainer decides on it. When an item is done, describe the result in the reference documents and remove it from this file; git history keeps the record. A refactoring must leave the results byte-identical on the reference datasets (see [DEVELOPER.md](DEVELOPER.md#real-data-checks)).

**How to use the issue reviews (2026-10-24).** Before implementing an item, consider and evaluate its review comments against the current code, tests and documented API. Record which comments are accepted, adjusted or rejected, why, and the relevant verification with the change. They are review input, not authorization to implement the proposal or automatic proof of a current bug. This was a static review; no runtime or real-data checks were run. Scientific parity alone does not establish parity of input acceptance, diagnostics, cancellation or public API behaviour.

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
| [JA-11](#ja-11-validate-the-selected-release-artifacts) | Validate the selected release artifacts | Release gate | Process |

**Suggested order.** JT-01, then JT-05 (it makes JT-03 and JT-04 easy to see), then JT-03 and JT-04 together, then JT-02 and JT-06. JA-11 runs at each release.

**Order review.** Verify the dead-code subset of JT-01 first. Keep failure-context and diagnostic-routing changes independently reviewable, even if scheduled together. Evaluate the contract changes in JT-02, JT-04 and JT-06 before treating them as behaviour-preserving simplifications.

### JT-01. Remove dead code and compatibility aliases

**Problem.**
- `core.SpatialGrid` (216 lines) has no users anywhere: not in the main code, the tests or SpacePixels.
- `PipelineResult.slowMoverStackData` is the same array as `maximumStackData`. `slowMoverMedianVetoMask` is the same as `SlowMoverAnalysis.medianMask`, and `slowMoverCandidates` lists the same objects as `SlowMoverAnalysis.candidates`. `SlowMoverAnalysis` keeps further aliases.
- `SlowMoverSummaryTelemetry.toLegacyTelemetry(candidates)` converts to older counters, and its parameter is "retained for compatibility" and unused.
- `TrackerTelemetry` keeps a "legacy zero-based position", which `ExtractedFramesContext.labelTrackerFrames` relabels afterwards.

The libraries are now developed together with SpacePixels, so none of these needs to survive.

**Proposal.** Delete `SpatialGrid`. Keep one name per product, drop the aliases and the legacy telemetry conversion, and update SpacePixels' report code (about 70 references) in the same change. Record the original frame identity in the tracker telemetry directly.

**Issue review (2026-10-24; evaluate before implementation).**
- Verify absence of callers separately from deciding to remove an exposed capability. `PipelineResult` and its builder are public, and their products are documented in README.md and PIPELINE.md. Update the API consumer, SpacePixels reports and examples together, and document intentional signature changes.
- Preserve optional-product semantics: `slowMoverStackData` is documented as null when slow movers are disabled, whereas `maximumStackData` is a separate pipeline product. Removing the alias must not make a disabled analysis appear enabled in callers. Preserve shared buffer ownership without introducing image copies.
- Tracker statistics currently translate retained positions through `ExtractedFramesContext.labelTrackerFrames`. Test original identity after rejected frames, empty detections and non-contiguous sequence indices when removing that translation; an array position is not an original frame identity.

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

**Issue review (2026-10-24; evaluate before implementation).**
- **This changes accepted inputs.** `DetectionConfigRulesTest` explicitly accepts non-finite values in fields unused by a particular operation or timing branch and rejects them when active. Validating every number always cannot also preserve the stated accept/reject behaviour. Decide whether to change that API contract, and separate the decision from removing redundant metadata.
- Per-field domains currently produce `NUMERIC_DOMAIN` advisory warnings in `validate`; they are not only duplicate spinner ranges. Removing them changes "Review rules..." findings. Inventory the advisories and preserve or explicitly revise them, including disabled stages, calibration, timing branches and legitimate sentinel values.
- Keep configuration capture before callbacks and worker execution, caller immutability, and the distinction between engine invariants and the GUI's alignment policy. Update PIPELINE.md and SpacePixels tests for any accepted contract change.
- Note from maintainer: If the SpacePixels GUI Spinners also have their own range definition, this is a suplication. See how we can have this information about the range in the rules and the GUI gets that information about the range from the rules and applies it to the Spinners.  

### JT-03. Label failures at stage boundaries, without a thread-local

**Problem.** `OperationContext` keeps the current stage and frame in a `ThreadLocal`. The engine, tuner and photometry call `OperationContext.compute`, `call`, `run` and `notify` 68 times so that a failure can name its stage and frame (`OperationFailure`, `OperationFailedException`, `StageId`, `FrameIdentity`). `WorkerPool` copies the context into worker batches, and progress callbacks are wrapped too. SpacePixels never reads the structured failure; it shows the exception's message.

**Proposal.** One private stage runner in the engine (see JT-05) catches a failure at each stage boundary and rethrows it with the stage name, and with the frame where a stage loops over frames. Keep `OperationFailedException` with the message and the original cause. Remove the `ThreadLocal` and the wrapping calls. Keep `StageId` only if a caller needs to branch on it.

**Benefit.** The stage code shows the science only. A failure still says "failed at TRACKING for frame 12 [name.fits]: …".

**Issue review (2026-10-24; evaluate before implementation).**
- `OperationContext` does more than format a message: it preserves the original cause and nested stage/frame identity, excludes cancellation from ordinary failure recording, restores interrupt status and propagates fatal errors. `OperationContextTest` and `WorkerPoolTest` exercise those contracts.
- An outer engine-stage catch cannot reconstruct the frame that failed inside a parallel batch, and it does not cover standalone tuner, extraction and photometry entry points. Define an explicit replacement for those paths before deleting the scopes. Preserve worker joining and the original failure when sibling tasks are cancelled.
- Retain meaningful distinction between progress-listener failures and scientific failures. If structured public failure fields are removed, update PIPELINE.md and consumer checks as an intentional API change, even though the GUI currently displays only the message.


### JT-04. Diagnostics through java.util.logging

**Problem.** `DiagnosticContext` and `DiagnosticOptions` route debug text through a per-operation `ThreadLocal` sink: 139 calls, most of them `if (DiagnosticContext.enabled()) DiagnosticContext.println/printf(...)`. SpacePixels bridges to it with `DebugOutput.openEngineDiagnostics()` around every engine call. Per-operation routing would matter for several concurrent runs with different sinks; SpacePixels runs one job at a time.

**Proposal.** Log through `java.util.logging` loggers at `FINE`, which is standard, needs no scope and costs nothing when off (`logger.isLoggable(FINE)`). SpacePixels' debug option sets the level and the console handler. Remove `DiagnosticContext`, `DiagnosticOptions` and the bridge.

**Issue review (2026-10-24; evaluate before implementation).**
- **The single-GUI-job argument is insufficient.** `EngineDiagnosticsTest.independentEnginesMatchTheirIsolatedDiagnosticStreams` checks concurrent engines with separate sinks. PIPELINE.md documents per-operation routing, and SpacePixels' `DebugOutputTest` checks that an explicit disabled caller policy overrides application defaults.
- Changing a shared logger level or console handler does not preserve those contracts and can affect an embedding application's other runs. Evaluate keeping the scoped policy, or passing an explicit per-operation policy, even if logging is used as an output destination. Library code must not reconfigure the host's global handlers.
- Check quiet defaults, enabled and disabled concurrent callers, nested scopes, worker propagation, sink-failure behaviour and absence of expensive formatting when diagnostics are off. Any intentional loss of per-run routing needs a documented contract decision, not only unchanged detection results.

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

**Issue review (2026-10-24; evaluate before implementation).**
- A small private helper is reasonable if it preserves stage-specific result types and leaves branch decisions visible. Do not introduce an untyped stage framework to shorten the method.
- Preserve the ordering of progress, stage timing and observer delivery, including disabled/empty stages and early returns. PIPELINE.md distinguishes observer exceptions, which are isolated, from progress-listener exceptions, which fail the operation. Keep that distinction when centralizing callbacks and timing.
- Compare scientific results and stage/observer sequences separately from elapsed-time values. Retain the failure and cancellation guarantees evaluated in JT-03; extract orchestration first without simultaneously changing diagnostic policy.

### JT-06. Tuner constants instead of an options builder

**Problem.** `CalibratedAutoTuner.Options` and its `Builder` (about 300 lines, with defensive copies and validation) exist so that a caller could change the tuning grids. No caller does. SpacePixels only reads `Options.defaults().falsePositiveBudgetPerMpixFrame()[profile.ordinal()]`.

**Proposal.** Private constants in the tuner. Make the false-positive budget a property of `AutoTuneProfile` (`profile.falsePositiveBudget()`).

**Issue review (2026-10-24; evaluate before implementation).**
- **The builder has exercised callers and a documented contract.** `AutoTunerOptionsTest` builds small search grids, runs concurrent calibrations with different budgets, checks array isolation and rebuilds trade-offs from retained options. README.md also gives an options-builder example, and PIPELINE.md describes the capability. Revise the claim that nobody calls it before deciding removal.
- Evaluate whether the public customization is wanted and whether a smaller test seam can preserve useful bounded calibration tests. Default-only SpacePixels usage does not by itself make options or their defensive copies redundant.
- If removal is chosen, document the API change and retained calibration provenance. Preserve grid order, crop policy, random seed and nominal/effective profile budgets so the cleanup does not change tuning science. Check all profiles and trade-off reconstruction as well as default end-to-end tuning.

### JT-07. Split the largest classes along their phases when they change

`CalibratedAutoTuner` (1,976 lines), `VariableStarAnalyzer` (1,291) and `JTransientEngine` (1,082) are large but each holds one responsibility. Do not split them for their size. When one of them changes for another reason, extract the phase being changed, as was done for the track linker. For the tuner, that would be crops and injection, the sweep, the selection, or the trade-off; for photometry, measurement or scoring.

**Issue review (2026-10-24; evaluate before implementation).**
- The guidance is sound: extract a phase only when it has a coherent responsibility and explicit inputs/outputs. Keep algorithm changes separately reviewable from moving their code.
- Identify ownership and release points for large arrays, and preserve numerical iteration order, deterministic seeds and cancellation checks. Neither a new class nor an immutable wrapper justifies copying large images. Existing operation snapshots and result views can remain distinct without creating competing mutable owners.

### JA-11. Validate the selected release artifacts

**Maintainer decision (2026-10-27).** Use ordinary dependency declarations and the API consumer test; separate version-override properties and a staged release-version checker are unnecessary.

At each SpacePixels Maven release:
1. Declare the required JTransient and JPlateSolve versions once in SpacePixels' dependency declarations.
2. Make those exact artifacts available in the release repository.
3. For a Maven release, run SpacePixels' `check apiConsumerTest` with both composite builds disabled, using the versions declared in its dependency declarations. Follow the [SpacePixels publishing guide](../SpacePixels/PUBLISHING.md#maven-central-publishing).
4. Check publication metadata and the headless/desktop separation through the API consumer test.

Local staging is packaging evidence, not proof that the artifacts are publicly available.

**Issue review (2026-10-24; evaluate before implementation).**
- Retain this release gate. Record the resolved artifact versions and repository configuration so success against sibling sources or a locally staged artifact is not mistaken for a check of the published combination.
- Include API consumer compilation after the removals proposed in JT-01/JT-03/JT-04/JT-06 and the SpacePixels package moves. Packaging checks and scientific regression answer different questions; both remain necessary for the changes that affect them.

## Removed from the previous plan (2026-10-23)

- **JA-09, typed progress events.** Not needed. The stage observer that SpacePixels uses is done, and nothing needs the rest.
- **The target package layout, the class-separation analysis and the migration table.** The package layout is described in DEVELOPER.md; nothing schedules a migration.
- **The completed and closed records in `docs/archive`.** Removed; git history keeps them. The binding "declined" decisions are kept in [FEATURE-REQUESTS.md](FEATURE-REQUESTS.md#declined).
