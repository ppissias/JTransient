# Design improvements

This file lists design, refactoring and code-quality work for JTransient: changes to how the engine's code is organised, not to what it detects. Detection, tuning and photometry algorithm proposals are in [FEATURE-REQUESTS.md](FEATURE-REQUESTS.md). The current design is in [DEVELOPER.md](DEVELOPER.md), [PIPELINE.md](PIPELINE.md) and [ALGORITHM.md](ALGORITHM.md).

Every item is a proposal until the maintainer decides on it. When an item is completed or closed, remove it from this file; describe implemented behaviour in the reference documents, and use git history for the proposal and its reviews. A refactoring must leave the results byte-identical on the reference datasets (see [DEVELOPER.md](DEVELOPER.md#real-data-checks)).

**How to use the issue reviews (2026-10-24).** Before implementing an item, consider and evaluate its review comments against the current code, tests and documented API. Record which comments are accepted, adjusted or rejected, why, and the relevant verification with the change. They are review input, not authorization to implement the proposal or automatic proof of a current bug. This was a static review; no runtime or real-data checks were run. Scientific parity alone does not establish parity of input acceptance, diagnostics, cancellation or public API behaviour.

## Code review of 2026-10-23

A fresh review of the engine (about 20,000 lines of main code and 7,700 of tests), against the principles in [DEVELOPER.md](DEVELOPER.md#principles): readable code, clear boundaries, a few patterns applied consistently, and no abstraction without a real need.

**Overall.** The scientific code is in good shape. Since the track linker was split into stages, the core reads as a sequence of named stages with explicit inputs and outputs. The model types are small. `PipelineResult` has a builder, and the stage observer is small and isolated. The remaining work concerns verified unused code and legacy exports, duplicated validation, a coordinated logging review and clearer pipeline orchestration. The maintainer revised this scope on 2026-11-02; the existing failure-context scopes and configurable tuner options remain part of the current design.

### What is good and should stay

- **The stage classes** in `core` (`TransientVeto`, `StreakLinker`, `TimedPointLinker`, `GeometricPointLinker`, `AnomalyRescuer`, `StreakConsolidator`, `TrackingExports`, with `DetectionAssignments` making claims explicit), orchestrated by `TrackLinker`.
- **The model** (`model.DetectedObject`, `Track`, `AnomalyDetection`, `Pixel`) and the `PipelineResult` builder.
- **`WorkerPool`:** one pool per engine, with results in task order and a joined failure or cancellation.
- **`CancellationScope`:** cancellation reaches deep loops without a parameter in every signature. Preserve the existing failure-context and diagnostic contracts too.
- **`PipelineStageObserver`:** an explicit, optional parameter, isolated from failures.
- **Input and area rules** (`InputRules`, `FrameCoverage`, `DataArea`): small, documented and tested.

### Recommended changes

| ID | Change | Priority | Size |
|---|---|---|---|
| [JT-01](#jt-01-remove-dead-code-and-compatibility-aliases) | Remove dead code and compatibility aliases | High | Small (with SpacePixels callers) |
| [JT-02](#jt-02-centralize-shared-validation-and-setting-rules) | Centralize shared validation and setting rules | Medium | In-depth review first |
| [JT-04](#jt-04-review-logging-across-the-three-repositories) | Review logging across SpacePixels, JTransient and JPlateSolve | Medium | Review first |
| [JT-05](#jt-05-let-the-pipeline-method-read-as-the-algorithm) | Let the pipeline method read as the algorithm | Medium | Small |

**Working order.** Verify the dead-code subset of JT-01 first. Evaluate JT-02 in depth before choosing changes, and review JT-04 across all three repositories before choosing a logging approach. Keep each change independently reviewable. JT-05 remains a small orchestration cleanup; release-artifact checks continue through the existing [coordinated publishing procedure](../SpacePixels/PUBLISHING.md#maven-central-publishing).

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

### JT-02. Centralize shared validation and setting rules

**Maintainer decision (2026-11-02).** Keep this proposal and evaluate it in depth. The goal is to avoid duplicate logic: shared checks should have one implementation, and the same configuration should not undergo the same checks twice without a reason. The earlier proposal to remove validation scopes and metadata is replaced by this review.

**Review scope.** Trace the checks in JTransient's `DetectionConfigRules`, engine/tuner/extraction entry points, and SpacePixels' setting definitions, spinner ranges, profile loading, "Review rules…", Auto-Tune application and run admission.

1. Inventory each rule, its owner, consumers, entry points and call order. Include numeric ranges, finite-number checks, paired-field ordering, types, defaults, sentinel values, advisory findings and alignment policy. Identify both repeated definitions and repeated execution on an unchanged captured configuration.
2. Distinguish equivalent shared rules from checks with different responsibilities. Parsing a GUI value, checking a saved profile's serializability and validating the fields consumed by an engine operation need not have identical semantics. Establish where checks truly duplicate each other before removing them.
3. Propose one authoritative implementation for each shared rule. Engine invariants belong in JTransient; SpacePixels should consume the same rule or metadata where applicable. If a GUI range represents the same constraint, derive it from that source rather than defining it again. Keep presentation details such as spinner steps and labels with the UI.
4. Trace the public API, CLI and GUI paths so delegating a check does not leave an independently callable entry point unprotected. Consider reusing validation evidence only when the configuration snapshot and validation context are demonstrably unchanged; preserve capture before callbacks and workers.
5. Recommend the smallest concrete changes, with the duplicate definitions/check executions removed and the contracts each remaining boundary protects. Evaluate the benefit before introducing any new validation machinery.

**Deliverable before implementation.** A rule/owner/caller table, evidence of the duplication, and a proposed change list for agreement. Changes to accepted inputs, warnings, alignment or defaults must be identified separately from consolidation.

**Constraints and verification.** Preserve operation/timing scopes, disabled-stage and sentinel semantics, `NUMERIC_DOMAIN` advisories, caller immutability and configuration snapshots unless a change is explicitly decided. The existing tests accept some non-finite values when an operation does not read them, while persisted profiles require finite values; do not conflate those contracts. Check profile round trips, GUI ranges and "Review rules…", every public entry point, and unchanged scientific outputs after any agreed implementation.

### JT-04. Review logging across the three repositories

**Maintainer decision (2026-11-02).** Keep an overall logging review covering SpacePixels, JTransient and JPlateSolve. This is the canonical shared proposal, linked from the other two design plans. The earlier prescribed replacement with `java.util.logging` is replaced by an assessment of current behaviour and concrete improvements.

**Review scope.**

- Inventory normal output, optional debug diagnostics, warnings/errors, exception reporting and owned subprocess output across the GUI, CLI and library entry points. Trace `DebugOutput`, JTransient's diagnostic scopes, existing loggers and JPlateSolve's HTTP/process diagnostics, including their levels, destinations, ownership and shutdown.
- Look for duplicated messages or routing logic, missing useful failure context, unexpected normal-console output, inconsistent verbosity, expensive disabled diagnostics and secrets or observing-site information in logs. Distinguish progress notifications from diagnostic logging and user-visible failure messages.
- Review concurrent independent library calls, nested scopes and worker propagation. SpacePixels' single GUI job does not establish the requirements of embedding callers. Preserve cancellation, original failure causes and stage/frame identity.
- Propose the smallest useful improvements across the three repositories, with a clear owner for configuration and output. Choose any logging backend, adapters or scope changes only after evaluating the current contracts and the benefit; library code must not reconfigure the host's global logging handlers.

**Deliverable before implementation.** A current output-path/owner table and a ranked set of findings and proposed changes, including observable output and public-contract changes. Prefer consistency and removal of genuine duplication over replacing a working mechanism for its own sake.

**Constraints and verification.** Keep quiet library defaults, per-operation enable/disable and routing policies, credential/session redaction and borrowed-resource ownership. Check normal/debug GUI and CLI runs, independent concurrent engines/tuners/solves, worker and sink failures, cancellation and disabled-formatting cost. Use synthetic credentials for captured-log checks. Relevant existing tests include `EngineDiagnosticsTest`, `AutoTunerOptionsTest`, SpacePixels' `DebugOutputTest` and JPlateSolve's diagnostic-redaction tests. The review itself changes no logging or scientific behaviour.

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
- Compare scientific results and stage/observer sequences separately from elapsed-time values. Retain the current failure-context, per-worker frame identity and joined cancellation guarantees; extract orchestration without simultaneously changing diagnostic policy.

## Removed from the previous plan (2026-10-23)

- **JA-09, typed progress events.** Not needed. The stage observer that SpacePixels uses is done, and nothing needs the rest.
- **The target package layout, the class-separation analysis and the migration table.** The package layout is described in DEVELOPER.md; nothing schedules a migration.
- **The completed and closed records in `docs/archive`.** Removed; git history keeps them. The binding "declined" decisions are kept in [FEATURE-REQUESTS.md](FEATURE-REQUESTS.md#declined).
