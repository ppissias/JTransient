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
| [JT-08](#jt-08-use-the-cpus-on-large-sensors-then-the-gpu-for-faint-mover-tiles) | Use the CPUs on large sensors, then the GPU for faint-mover tiles | High (planned next) | Exact CPU memory and parallelism changes first; OpenCL tile preparation only if they fall short |

**Working order.** Verify the dead-code subset of JT-01 first. Evaluate JT-02 in depth before choosing changes, and review JT-04 across all three repositories before choosing a logging approach. Keep each change independently reviewable. JT-05 remains a small orchestration cleanup; release-artifact checks continue through the existing [coordinated publishing procedure](../SpacePixels/PUBLISHING.md#maven-central-publishing).

### JT-01. Remove dead code and compatibility aliases

**Problem.**
- `PipelineResult.slowMoverStackData` is the same array as `maximumStackData`. `slowMoverMedianVetoMask` is the same as `SlowMoverAnalysis.medianMask`, and `slowMoverCandidates` lists the same objects as `SlowMoverAnalysis.candidates`. `SlowMoverAnalysis` keeps further aliases.
- `SlowMoverSummaryTelemetry.toLegacyTelemetry(candidates)` converts to older counters, and its parameter is "retained for compatibility" and unused.
- `TrackerTelemetry` keeps a "legacy zero-based position", which `ExtractedFramesContext.labelTrackerFrames` relabels afterwards.

The libraries are now developed together with SpacePixels, so none of these needs to survive.

**Proposal.** Keep one name per product, drop the aliases and the legacy telemetry conversion, and update SpacePixels' report code (about 70 references) in the same change. Record the original frame identity in the tracker telemetry directly.

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

### JT-08. Use the CPUs on large sensors, then the GPU for faint-mover tiles

**Planned (2026-11-20; profiled again and plan revised 2026-11-22).** Large sensors leave most of a 32-thread machine idle, and memory pressure (worker budgets, garbage collection, paging) costs more time than the computation itself. Fix memory use and CPU parallelism first. Move faint-mover tile preparation to OpenCL only if the CPU work falls short.

#### Rules for every step

- **One baseline.** Measure every change against the same baseline: the saved reference profiles of Klangwolke-2, B1m and Austral Kiwi, data-only export, one session at a time with nothing else running, and a heap that does not page (about 8 GiB on a 15 GB machine). Take the median of three runs. Record engine and stage wall time, garbage-collection pauses, peak heap and page faults. The November 22 figures below include paging and are not this baseline.
- **CPU changes are exact.** Steps 1 and 2 must leave `report-data.json` byte-identical on the reference sessions and keep cancellation behaviour.
- **GPU results need not be bit-identical (maintainer, 2026-11-22).** An OpenCL path may differ from the CPU in floating-point detail, provided it is tested on real scientific data, loses no candidate, and the differences are measured and recorded: candidates gained and lost per session, score differences of matched candidates, and mask differences. The **Auto** mode's parity self-test, which now requires an exact match, then needs a tolerance-based check for those stages.

#### Proposed order of work

1. **Right-size worker budgets and remove overhead (exact).**
   - **Per-stage worker budgets.** Every stage after the faint-mover search inherits `limitWorkToFrames`: 16 bytes per frame pixel per worker, within a quarter of the heap and half the free heap. Aperture measurement shares the frames and needs little scratch, yet runs under that full-frame limit; on November 20 the large sensors used 1.5–3 of 32 cores. Budget each stage by its own working set, and reuse the clipped-sky scratch arrays within each photometry worker.
   - **Cancellation checks.** There are 409 `CancellationScope.check()` calls, several in per-star and per-detection loops. They took about 45% of Auto-Tune's CPU samples and 20–30% of aperture measurement. Check once per row or batch.
   - **Explicit collection.** Skip the `System.gc` in `WorkerPool.workerCount` when even an empty heap would not allow another worker. One such collection took 31 s on Klangwolke-2.
   - **Smaller exact reuses.** Count the 41 × 41 valid pixels once per faint-mover tile, since its star-and-fill mask is fixed across exposures. Reuse the background statistics from strict source extraction in the frame-quality check. Give the saturation scan its early exit (see the measurements). Build the median master with a selected median instead of a per-pixel sort; the median reference and star map took 74 s on Klangwolke-2 and Kiwi.
2. **Parallel CPU preparation inside a faint-mover tile (exact).** B1m's saved search domain needs 1.32 GiB of scratch per tile, so the quarter-heap cap allows one tile worker. Preparation took 363 s of its 434 s faint-mover stage. Normalise and filter separate exposures of one resident tile on several workers. The fill mask must be complete first, each worker needs its own scratch, and the pool runs nested calls inline, so this work needs its own explicit budget. Reduce per-tile scratch where it stays exact. If it scales, eight workers would bring preparation to about a minute. Measure peak heap and paging.
3. **OpenCL tile preparation, only if step 2 leaves preparation dominant.** Prototype on B1m: residual assembly, normalisation and the Gaussian pass next to the existing coarse kernel, keeping the frames and reference on the device across tiles when they fit. Reuse the device selection, memory planning and CPU fallback. The Java noise estimates, refinement and diagnostic cutouts also use the residuals, so compare full-plane readback (about 1.03 GiB for a full B1m tile) with returning only the samples and regions the CPU needs. Measure uploads, kernels, readbacks and peak host and device memory against the step 2 CPU result, and the result differences under the GPU rule above. Preserve tile-local normalisation, mask growth, noise estimates and candidate evidence at tile boundaries.
4. **Later, each measured separately.**
   - OpenCL faint-streak smoothing, once the memory work is measured. Smoothing and convolution were 84–90% of the faint-streak samples, with 61–76 s of garbage collection inside the stage.
   - An earlier photometry readiness verdict for sessions like Kiwi, which spent 308 s in photometry and then returned `NOT_READY` after the shape-linearity check rejected 97 of 98 frames. It must reproduce the full check's verdict, frame diagnostics and flags.
   - **Not proposed.** GPU ports of aperture photometry (its cost is the worker budget and the cancellation checks; a sky median per star fits a GPU poorly), source extraction (branchy component labelling) and Auto-Tune (its next gains are the cancellation checks and a spatial index for its all-pairs recurrence test).

#### Measurements

**November 20**, shipped default profile, one session at a time, 32-thread machine with a laptop RTX 5060, Java Flight Recorder:

| Session | Total | Largest stages |
|---|---|---|
| Klangwolke-2 (61 MP, 28 kept frames) | 240 s | photometry 103 s, faint-mover search 44 s, faint-streak search 39 s, extraction 22 s, median master 13 s |
| Austral Kiwi (26 MP, 98 kept frames) | 573 s | faint-mover search 273 s, photometry 164 s, faint-streak search 66 s |
| B1m (8 MP) | 32 s | faint-mover search 13 s |
| Klangwolke-2 with Auto-Tune medium | 567 s | Auto-Tune about 210 s; the later stages slower (photometry 192 s) with the heap fuller |

Large sensors used 1.5–3 of 32 cores in every stage, while B1m used 11–23 in the same stages.

**November 22**, JTransient 1.1.2 with the saved reference profiles (larger search domains than the default; B1m has a 583 px motion margin), data-only export, faint-mover search, faint-streak search and variable-star photometry on. The heap cap was 10 GiB for Klangwolke-2 and B1m and 11 GiB for Kiwi. OpenCL completed all 247, 32 and 104 faint-mover tiles with no CPU fallback. These runs paged: garbage-collection pauses totalled 102, 21 and 344 s, about 265 s of them inside Kiwi's faint-mover stage. Klangwolke-2 grew from 240 s to 931 s partly from the profile and partly from paging.

| Stage (s) | Klangwolke-2 (28 frames) | B1m (98 frames) | Austral Kiwi (98 frames) |
|---|---:|---:|---:|
| Engine total | 931.0 | 466.8 | 1,778.8 |
| Faint movers | 135.0 | 434.2 | 1,035.2 |
| Photometry | 404.7 | 5.8 | 308.4 |
| Faint streaks | 178.5 | 17.6 | 169.1 |
| Source extraction | 114.5 | 2.0 | 88.9 |
| Median reference and star map | 74.1 | 1.2 | 74.6 |
| Other engine work | 24.2 | 5.9 | 102.6 |

- **Faint-mover preparation** was 86–94% of the summed per-tile preparation, coarse and refinement timers; workers overlap, so these sums measure accumulated work. In Klangwolke-2 and Kiwi about 80% of the faint-mover samples were the pixel read and median subtraction in `FaintMoverSearch.readResiduals`; in B1m, `normalise` and `filter` took about 76%.
- **Photometry.** Klangwolke-2 spent 284 s measuring apertures. The clipped-sky calculation allocates two working arrays on each call that reaches clipping.
- **Saturation scan.** `PhotometryStarSelector.pileUpLevel` needs only the maximum and whether five pixels fall within its tolerance, so keeping the five brightest values, ties included, gives the same decision, and a frame can stop once the maximum reaches 65535 and the fifth value 65469. A scratch version matched the production method on all 259 raw frames of the three fields. The median call fell from 21.6–22.5 ms to 0.009–0.046 ms on Klangwolke-2, 3.1–3.4 to 2.2–2.4 ms on B1m and 10.0–12.1 to 7.0–8.7 ms on Kiwi. That is about a second per session at most: worth doing, not a priority.

## Removed from the previous plan (2026-10-23)

- **JA-09, typed progress events.** Not needed. The stage observer that SpacePixels uses is done, and nothing needs the rest.
- **The target package layout, the class-separation analysis and the migration table.** The package layout is described in DEVELOPER.md; nothing schedules a migration.
- **The completed and closed records in `docs/archive`.** Removed; git history keeps them. The binding "declined" decisions are kept in [FEATURE-REQUESTS.md](FEATURE-REQUESTS.md#declined).
