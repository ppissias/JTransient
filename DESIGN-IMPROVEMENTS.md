# Design improvements and review decisions

Consolidated and checked against the source tree on 2026-10-19, at engine commit `f084ab9`. This is the single active decision list for engine improvements and review limitations. Current implementation is described in [ALGORITHM.md](ALGORITHM.md) (detection, slow movers, photometry and tuning), [PIPELINE.md](PIPELINE.md) (API contracts), and [CONFIG.md](CONFIG.md) (fields/defaults).

Original evidence and maintainer comments are preserved in the [October engine review](docs/archive/code-review-2026-10.md), [variable-star assessment](docs/archive/variable-star-assessment-2026-10.md), [slow-mover design](docs/archive/slow-mover-design-2026-10.md), and [maintenance/regression notes](docs/archive/maintenance-notes-2026-10-19.md). Original line numbers, suggested orders and statements about missing features describe those earlier checkouts.

The separate [design and architecture review](#design-and-architecture-review-2026-10-20) below was added on 2026-10-20. Its `JA-` findings concern library structure and engineering contracts; the existing algorithm findings and decisions retain their status.

## Status and scope

**Proposed** is an idea without an implementation decision; **Accepted** requires an explicit decision; **In progress** means implementation has started; **Deferred** is postponed work; **Declined** preserves a decision to keep current behavior; **Implemented** records a completed change; **Retired** means an old finding no longer applies. A source concern or synthetic reproduction is not a measured real-data incidence. This documentation cleanup changes no algorithm or review authorization.

Existing issue IDs are retained. Variable-star assessment numbers use a `VS-` prefix here to avoid ambiguity. No deferred or declined issue becomes Accepted by being listed in this plan. Discuss a concrete engine fix, implement one at a time, and report real-data differences under [AGENTS.md](AGENTS.md).

## Design and architecture review (2026-10-20)

### Assessment and scope

Reviewed against checkout `b424a6e` (library version `1.0.5`). JTransient has a sound application boundary: it processes in-memory images, exposes standalone extraction and tuning, and keeps file preparation, persistence, GUI work and report generation in SpacePixels. Its focused analyzers and existing regression tests are useful foundations. A replacement framework, plugin pipeline or multi-module rewrite is not justified by this review.

The main architectural weakness is that **run ownership and public data contracts are less explicit than the scientific stages**. A run uses caller-owned mutable settings and images, several execution mechanisms, and shared mutable detection objects. Tracking owns several distinct stages; tuning combines measurement, selection and presentation. These arrangements make safe reuse, cancellation, concurrent callers and compatibility harder to reason about.

The review inventoried all 50 production Java files, their declared types and internal dependencies, and examined the important entry points, stage boundaries, state mutations, execution and result construction in detail. It also inspected the 36-file test inventory, relevant contract tests, build/publication configuration, current library documentation, and selected SpacePixels consumers: standard/iterative pipelines, Auto-Tune integration and public API. The responsibility map below accounts for every production file. This is a broad architecture review, not an exhaustive correctness audit of every numerical method or a guarantee that every defect has been found.

No code, settings or datasets were changed, and no tests, fault-injection experiments, performance benchmarks or real-data comparisons were run for this documentation review. Evidence describes source mechanisms; consequences described as risks are inferred, with no claimed incidence. Detection rules, thresholds, association choices and photometry statistics are outside this review. In particular, the proposals below do not approve any deferred or declined algorithm finding elsewhere in this document.

### What to preserve

- **The library/application boundary and simple entry points.** Keep `short[][]` processing usable without SpacePixels, FITS readers, Swing, application settings or report services. Keep standalone `SourceExtractor`, both tuners and the engine facade available during migration.
- **Existing scientific boundaries.** `SkyBackground`, coverage/registration/data-area types, `MasterReferenceAnalyzer`, the camera/common-motion rejectors and the slow-mover analyzers already isolate useful responsibilities. Photometry has separate selection, measurement, readiness, ensemble and scoring helpers. Static methods are appropriate for stateless computations.
- **Stage-local configuration copies.** Master-reference, quality, slow-mover and photometry extraction already clone settings before applying local overrides. Preserve that practice while improving the outer run boundary.
- **Shared preparation and measured diagnostics.** Full detection and transient-only export share `extractSourcesFromFrames`; master generation shares the rejection policy. Stage timings use a monotonic clock, and photometry distinguishes readiness outcomes. Build on these instead of adding a second pipeline.
- **Memory-conscious sharing and identity.** Image/mask aliases avoid large duplicate allocations. Detections intentionally appear in several review collections, and classification uses object identity. Preserve those relationships until an explicit replacement contract is agreed.
- **The regression discipline.** Existing focused tests and the SpacePixels reference-data comparison are essential safeguards when moving scientific code without changing its behavior.

### Findings and priorities

All findings are **Proposed**. High priority means to resolve the contract before expanding the affected use case; it does not authorize implementation or claim an observed production failure.

| ID | Priority | Status | Design improvement | First useful result |
|---|---|---|---|---|
| JA-01 | High | Proposed | Capture and validate run configuration | One operation uses one coherent settings snapshot and reports invalid settings before expensive work. |
| JA-02 | High | Proposed | Define execution, concurrency and resource ownership | Callers know whether an engine can overlap runs and which work its resource limit controls. |
| JA-03 | High | Proposed | Give cancellation and failures an explicit contract | Cancellation stays distinct from fallback and failure; completion means the run's work has ended. |
| JA-04 | High | Proposed | Replace mutable global tuning options with operation options | Two tunings can use independent options without changing each other's settings. |
| JA-05 | Medium | Proposed | Make prepared-frame context and identity coherent | Frames, metrics, detections and registrations remain associated through QC and subset runs. |
| JA-06 | Medium | Proposed | Separate tracking responsibilities while retaining its facade | Vetoing, point linking, streak assembly and classification have explicit inputs and ownership. |
| JA-07 | Medium | Proposed | Stabilize public results and dependency direction | Public models and shared buffers have clear contracts independent of implementation helpers. |
| JA-08 | Medium | Proposed | Separate tuning measurement, selection and presentation | Measurements can explain a choice without depending on report construction or ambiguous legacy fields. |
| JA-09 | Medium | Proposed | Unify progress and stage outcome reporting | Callers can distinguish disabled, unavailable and completed-empty stages without parsing prose. |
| JA-10 | Medium; before expanding reuse | Proposed | Carry provenance with reusable master references | Callers can identify a reference's origin and deliberately reuse it across compatible runs. |
| JA-11 | Medium | Proposed | Declare and verify the supported library contract | Releases have a known Java target and a tested SpacePixels/JTransient dependency pair. |

### JA-01. Capture and validate configuration at an operation boundary

**Evidence.** [DetectionConfig](src/main/java/io/github/ppissias/jtransient/config/DetectionConfig.java) is intentionally a public mutable, Gson-compatible collection of primitive fields. Its `clone()` copies those values, but it provides no central validation. [JTransientEngine](src/main/java/io/github/ppissias/jtransient/engine/JTransientEngine.java) passes the supplied object through worker tasks and later phases. [InputRules](src/main/java/io/github/ppissias/jtransient/engine/InputRules.java) validates frame sequences and master dimensions, not configuration. Both tuners also read supplied settings throughout their operations; some unsuccessful tuning paths return the original `baseConfig` reference.

**Risk and proposal.** Stage-local clones prevent the engine from leaking temporary overrides, but they do not isolate a run from a caller changing the original settings. Capture settings once at entry, then derive private stage settings from that snapshot. Keep the serialized `DetectionConfig` shape as the compatibility DTO; a validated internal settings object need not add, rename or remove profile fields. Return an independent fallback configuration as well as an independent successful one under a documented contract.

Define field and cross-field validation centrally, scoped to the operation and enabled stages. Preserve documented sentinels, automatic modes and existing intentional fallback rules; a refactor must not silently turn accepted profiles into errors or reinterpret their values. Engine validation must work for callers outside SpacePixels. GUI editor validation remains an application responsibility.

**Acceptance.** Use a controlled pause after capture, change the caller's config, and verify the active run still uses its captured values. Verify local overrides and tuning success/fallback leave the base untouched. Check representative persisted profiles, hidden fields, sentinels and invalid numeric values through engine and tuner entry points. Compare unchanged valid runs on reference data before adopting new validation behavior.

### JA-02. Define execution and resource ownership for a whole operation

**Evidence.** [JTransientEngine.configureFrameExecutor](src/main/java/io/github/ppissias/jtransient/engine/JTransientEngine.java) resizes an owned `ThreadPoolExecutor` using frame size and heap headroom. The same pool later serves photometry. [MasterMapGenerator](src/main/java/io/github/ppissias/jtransient/core/MasterMapGenerator.java) uses parallel streams, outside that explicit pool. [CalibratedAutoTuner.calibrate](src/main/java/io/github/ppissias/jtransient/engine/CalibratedAutoTuner.java) creates another pool sized from processor count. Engine entry points have an open check but no run-admission guard; `shutdown()` requests orderly shutdown without waiting for termination. [JTransientEngineConcurrencyTest](src/test/java/io/github/ppissias/jtransient/engine/JTransientEngineConcurrencyTest.java) checks sizing and close/reuse rejection, not overlapping operations or close during a run.

**Risk and proposal.** The extraction memory bound is valuable, but it is not a whole-library resource bound. Concurrent engines, tunings and stacks can compete for heap and CPU, and same-instance overlap has no stated contract. First define whether one engine permits one active operation. Rejecting overlap clearly is a reasonable initial design; supporting it requires separate run state and resource accounting. Do not imply thread safety merely because per-frame tasks run concurrently.

Introduce a small execution policy shared by orchestration and tuning: worker limits, memory admission assumptions and owned versus borrowed executor lifetime. Account for row-stack work as well as extraction. Keep the default memory heuristic, and document that estimates cannot guarantee enough heap for arbitrary input/output populations. Define close during active work, sequential reuse after ordinary failure, and whether a completion operation waits. Avoid nested work that submits to and waits on an already occupied bounded pool.

**Acceptance.** Exercise overlap, repeated use, close during work, task failure and borrowed-executor ownership with controlled workers. Check bounded active work and absence of retained workers after terminal completion. Benchmark representative large frames sequentially, including stacking and tuning, before changing scheduling defaults.

### JA-03. Distinguish cancellation, failure and tuning fallback

**Evidence.** Engine and photometry operations expose broad `throws Exception` contracts and collect work with `invokeAll`/`Future.get`. There is no cancellation parameter or cooperative interruption check in the long extraction, stacking and tracking loops. [CalibratedAutoTuner.calibrate](src/main/java/io/github/ppissias/jtransient/engine/CalibratedAutoTuner.java) catches `Exception`, records a string failure reason and returns an unsuccessful calibration, then calls `shutdownNow()` in `finally`. That catch also covers an `InterruptedException` from waiting for tasks; it does not restore interruption or expose cancellation separately. Calling shutdown is not itself evidence that those compute loops have finished.

**Risk and proposal.** An application can stop waiting while computation still owns buffers and workers. An interrupted tuning can look like an ordinary inability to calibrate, and the original structured cause is lost to callers. Add a small per-operation cancellation contract, with checks at bounded chunks of expensive loops and before starting another stage. Specify that cancellation discards a partial scientific result unless a separate partial-result API is deliberately designed.

Preserve interruption, distinguish invalid input, insufficient evidence, cancellation and execution failure, and retain causes with stage/frame context. Keep scientific readiness outcomes separate from runtime errors. Define how sibling tasks are stopped and joined before a terminal outcome is delivered, including when a listener throws. Compatibility wrappers may translate a typed internal outcome to the existing exception/result shape. This is the engine counterpart to SpacePixels D04; UI job ownership remains in SpacePixels.

**Acceptance.** Cancel extraction, stacking, tracking, tuning and photometry at controlled checkpoints. Inject a worker and listener failure. Verify the terminal category and cause, no successful completion after cancellation, worker cleanup and the documented engine reuse behavior. Measure cancellation latency rather than promising immediate interruption of every calculation.

### JA-04. Make tuning options and diagnostics belong to an operation

**Evidence.** [JTransientAutoTuner](src/main/java/io/github/ppissias/jtransient/engine/JTransientAutoTuner.java) and [CalibratedAutoTuner](src/main/java/io/github/ppissias/jtransient/engine/CalibratedAutoTuner.java) expose writable static scalars and arrays for sample counts, search grids, crop settings and budgets. Copying an array reference does not freeze its contents. SpacePixels [AutoTunerRunner](../SpacePixels/src/main/java/eu/startales/spacepixels/util/AutoTunerRunner.java) temporarily changes `AUTO_TUNE_SAMPLE_SIZE` and restores it in `finally`; its public API documents a same-JVM Auto-Tune concurrency restriction. Core extraction, stacking, tracking and quality code also reference `JTransientEngine.DEBUG`, while legacy tuning has a separate debug switch.

**Risk and proposal.** Global options make independent requests depend on process state and complicate reproducibility. Provide immutable tuner options with defensive copies of grids and budgets, captured once for an operation. Record the effective options with the measurement. Keep legacy entry points as adapters to snapshotted defaults while callers migrate; merely synchronizing the SpacePixels wrapper cannot protect unrelated library users.

Pass an optional per-run diagnostic sink or diagnostic options through the execution context so lower-level stages do not depend on the engine facade for logging. Preserve silent defaults and the existing debug switches as compatibility adapters until their callers migrate. This needs no logging framework or new `DetectionConfig` profile fields.

**Acceptance.** Interleave tunings with different sample counts, grids and budgets and compare each with its isolated control. Mutate arrays supplied when constructing options and verify isolation. Verify default library use remains silent. Remove SpacePixels' documented concurrency restriction only after both its integration and the engine contract support doing so.

### JA-05. Represent prepared frames and their identities together

**Evidence.** [ExtractedFramesContext](src/main/java/io/github/ppissias/jtransient/engine/ExtractedFramesContext.java) already gives full and transient-only runs a shared preparation result. It holds parallel lists of frames, extraction results and quality metrics, plus separate registrations. `generateMasterStackWithDetails` repeats the surrounding drift/measurement/selection orchestration while sharing the rejection function. [VariableStarAnalyzer.Input](src/main/java/io/github/ppissias/jtransient/photometry/VariableStarAnalyzer.java) is a public field bag with lists required to have matching order. Engine methods sort the caller's frame list in place, as currently documented. Tracker veto telemetry assigns `frameIndex = i`, while engine frame telemetry records `sequenceIndex`; these describe different index spaces after rejection or subsetting.

**Risk and proposal.** Associations depend on positional conventions, and a generic `frameIndex` can mean a retained-list position or original sequence identity. Use an internal prepared-frame value containing the frame descriptor, metrics, detections and registration. Give original sequence index and retained position distinct names, preserve empty-frame entries, and derive aligned numeric arrays only where numerical routines need them. Keep light-curve alignment with photometry's frame list explicit.

Share preparation/selection policy between entry points without forcing the master-only path to perform full transient extraction. Validate required associations at public stage boundaries. For new APIs, sort a private list copy and borrow read-only pixel buffers; changing the old documented in-place sorting behavior requires a compatibility decision. Do not deep-copy every image to obtain stable frame metadata.

**Acceptance.** Compare all entry points using unsorted inputs, non-contiguous indices, empty detection frames and QC rejection in the middle. Verify metrics, registrations, telemetry labels and light-curve positions refer to the intended frames. Preserve the master/pipeline parity covered by [MasterStackConsistencyTest](src/test/java/io/github/ppissias/jtransient/engine/MasterStackConsistencyTest.java).

### JA-06. Separate tracking stages and make detection assignment explicit

**Evidence.** [TrackLinker.filterTransients](src/main/java/io/github/ppissias/jtransient/core/TrackLinker.java) builds the master veto, separates populations and links streaks. `findMovingObjects` adds camera-fixed removal, timed and geometric point linking, conflict resolution, anomaly rescue, streak consolidation and export construction. Private same-frame anomaly groups use the same `Track` type with a private flag. [FaintStreakFragmentGrouper](src/main/java/io/github/ppissias/jtransient/core/FaintStreakFragmentGrouper.java) is already separate, but mutates track membership. Camera-fixed removal updates both the point population and the full transient export; common-motion removal later changes the unclassified population.

**Risk and proposal.** The issue is several ownership rules in one orchestration path, not its line count. Extract cohesive internal collaborators in small steps: veto/population preparation, streak assembly/consolidation, timed point proposals, geometric point proposals, assignment/rescue, and export assembly. Keep `TrackLinker` and its nested public models as compatibility facades initially. Use a private anomaly-group input type when that seam is extracted, preserving the absence of a public suspected-streak category.

Make the current assignment sequence explicit: a detection may be visible in several review views, but accepted classification and removals must follow the existing rules. Preserve reference identity, tie order, greedy behavior, empty-frame slots and stage ordering. Do not add value-based equality or new association gates during extraction. This proposal does not reopen PT-6 or the declined streak/linker decisions.

**Acceptance.** Compare stage populations and final membership, not only counts: tracks, anomalies, camera/common-motion groups, all transients and leftovers. Use the existing streak consolidation, rescue, camera-fixed and export tests, plus deterministic ambiguous-assignment controls. Real-data outputs must remain equivalent for a structural refactor.

### JA-07. Give public models, mutable work state and shared buffers clear boundaries

**Evidence.** [PipelineResult](src/main/java/io/github/ppissias/jtransient/engine/PipelineResult.java) has a growing set of positional constructors and directly retains most lists and arrays. Its older constructors permit legacy fields without corresponding grouped slow-mover data. [ImageFrame](src/main/java/io/github/ppissias/jtransient/engine/ImageFrame.java) has final fields but exposes a caller-owned mutable pixel matrix. `SourceExtractor.DetectedObject`, `TrackLinker.Track`, telemetry and `StarLightCurve` remain mutable. Some analysis types copy list containers, but their elements remain shared; `VariableStarAnalysis.frames` is an unmodifiable view of a mutable telemetry list.

Dependencies also follow implementation locations: public results use `SourceExtractor.DetectedObject` and `TrackLinker.Track`; photometry frame records live inside `PipelineTelemetry`; common-motion groups live inside `ResidualTransientAnalysis` although common-motion filtering can run independently. `core` and `quality` depend back on the engine package for input types, callbacks or debug state. These are source dependencies, not evidence that the stages calculate the wrong results.

**Proposal.** State the ownership contract first: which inputs are borrowed, when callers may mutate them, which output containers are stable, and which large arrays are shared read-only by agreement. Use internal builders for assembly, validate grouped-result invariants, and freeze small collection structure where compatible. Keep expensive buffers shared; do not claim deep immutability or break aliases. Separate mutable work state from finished result views gradually.

As a touched boundary needs it, put shared frame/detection/track and diagnostic contracts below orchestration, with adapters for current nested public types. Keep domain measurements independent of their text formatter and aggregate telemetry attachment. Do not move public classes, remove constructors or make old mutable collections unmodifiable in one compatibility-breaking cleanup. Add an explicit opt-in output-retention policy only after identifying consumers: the engine currently builds the maximum stack even with slow movers disabled because it also exports that image. Any lighter mode must still compute products needed by enabled science stages and preserve today's default outputs.

**Acceptance.** Test intended aliases, container ownership, repeat viewers reading one result, grouped/legacy consistency and preservation of detection identity. Compile existing SpacePixels consumers. Measure retained heap and runtime before adding copies or changing output retention; a read-only wrapper alone is not deep immutability.

### JA-08. Separate Auto-Tune measurement, policy and presentation

**Evidence.** Both tuners contain crop preparation, extraction, evaluation, selection and report construction. The calibrated tuner already offers the useful `calibrate`/`configFor` split, but `Calibration` combines a mutable sweep, profile-ordinal arrays, chosen settings and compact tradeoff data. `tune` discards the full sweep through `discardSweep`, whereas direct `calibrate` retains it. Its public return type and profile enum belong to the legacy `JTransientAutoTuner`. The shared result's `bestTransientRatio` is a legacy ratio for one tuner and a per-megapixel-frame noise rate for the calibrated tuner.

**Risk and proposal.** Explanations, memory retention and selection policy are coupled, and a caller can misinterpret a shared field's units. Keep the algorithms distinct. Extract immutable measurement/selection results with explicitly named units and profile-keyed choices, plus formatters that consume those results. Reuse crop/mask/extraction mechanics only where their semantics really match; the legacy and calibrated objectives need not be forced into a single strategy abstraction.

Retain measurement provenance: effective base settings, tuning options, sample/crop identity and random seed where used. Make full-sweep retention versus compact summary an explicit option, keeping the current wrapper behavior compatible. A selected measurement is valid only for its documented assumptions; applying it to another base config needs an explicit rule. Continue supporting legacy result/profile types through adapters while SpacePixels migrates.

**Acceptance.** Replay selection and report generation from a frozen measurement without rereading images. Verify profile ordering, budgets, tradeoff choices and selected settings against controls; verify summary/full-sweep retention behavior and field units. Default legacy/calibrated results must remain unchanged. This is not a proposal to alter false-positive calibration or its deferred limitations.

### JA-09. Make progress, stage outcomes and diagnostics consistent

**Evidence.** [TransientEngineProgressListener](src/main/java/io/github/ppissias/jtransient/engine/TransientEngineProgressListener.java) documents worker-thread callbacks and repeated percentages. Engine, tracker and photometry manually map percentages into ranges. Some callbacks run while holding a progress counter monitor. [PipelineTelemetry](src/main/java/io/github/ppissias/jtransient/telemetry/PipelineTelemetry.java) records completed-stage timings and also formats a text report; slow-mover telemetry has a separate conversion to legacy counters. Empty branch results and absent timings do not uniformly distinguish disabled work from unavailable input. Photometry's readiness/check enums already show a better pattern.

**Proposal.** Use a small internal stage event carrying stage identity, completed/total work where known, and outcome; adapt it to today's percentage/message callback. Specify callback threading, ordering, exception handling and whether slow listeners can block workers. Do not introduce a GUI dispatcher or event bus into the library. Keep stage outcomes such as disabled, insufficient input, completed-empty, completed-with-findings, failed and cancelled separate from scientific confidence/readiness.

Keep numerical diagnostics in structured results; text formatting should consume them without changing their state. Preserve non-overlapping timing semantics, distinguish substeps from major stages and include the effective run settings/options or a stable reference to them. Progress describes execution and cannot serve as the completion/failure signal. This complements JA-03's terminal contract and SpacePixels D04 without duplicating application job IDs or report services.

**Acceptance.** Exercise each optional branch on/off, insufficient evidence, zero findings, task failure, cancellation and a throwing/slow listener. Verify stage order, callback guarantees, terminal outcomes and timing inclusion. Existing text diagnostics and percentage consumers should continue through compatibility adapters.

### JA-10. Carry reference provenance without introducing unsafe caching

**Evidence.** `generateMasterStackWithDetails` returns pixels, kept indices and rejection records, but the pipeline reuse overload accepts only a `short[][]`. `InputRules` checks dimensions. The full and transient-only paths still repeat extraction, QC and master-star preparation when a stack is supplied, as [PIPELINE.md](PIPELINE.md#what-changes-when-you-pass-providedmasterstack) documents. SpacePixels [IterativeDetectionPipelineService](../SpacePixels/src/main/java/eu/startales/spacepixels/util/IterativeDetectionPipelineService.java) intentionally builds a full-session reference and supplies it to subset runs.

**Risk and proposal.** Matching dimensions do not identify a reference's dataset, coordinate system, encoding or frame-selection origin. Add an optional prepared-reference value carrying those facts and the settings relevant to its construction. Keep raw-stack overloads available with a clear caller responsibility contract. Allow an explicit broader-session reference for a subset; requiring identical kept-frame lists would break an intentional current workflow.

Before caching master stars, masks or full preparation, define invalidation separately for each product. Master pixels, master extraction and veto dilation depend on different inputs/settings. A changed profile or subset must not accidentally reuse stale products. Start with provenance and measurable reuse needs; no global cache or full-image hashing on every call is proposed. Cached state needs an explicit owner and lifetime under JA-02/07.

**Acceptance.** Verify same-run reuse, intentional full-session/subset reuse, a foreign same-size reference, changed master-extraction settings and changed frame rejection. Compare cached and uncached results where caching is later introduced. Report actual saved work and retained heap before expanding the reuse API.

### JA-11. Verify architecture and release contracts explicitly

**Evidence.** [build.gradle](build.gradle) uses `java-library`, sources/Javadoc artifacts and a small dependency set, but declares no Java toolchain, release target or compatibility level. [README.md](README.md#build) requests a local JDK without specifying a minimum supported version. Existing tests focus on scientific components and several input/concurrency contracts; the concurrency test name does not imply coverage of run overlap or cancellation. The public facade also exposes implementation-nested types, so moving internal code can affect source/binary consumers. SpacePixels can use either a published dependency or a sibling composite build.

**Proposal.** Agree the supported Java baseline and encode it in the build and documentation. Add focused contract checks alongside each adopted seam: settings isolation, frame mapping, lifecycle, public result ownership and deterministic stage equivalence. Add lightweight dependency checks only for boundaries that have actually been introduced; a large architecture-testing framework is not required.

Define source and binary compatibility expectations for published releases, including nested public classes, constructors and legacy exports. Verify an explicit supported SpacePixels/JTransient pair both with the local composite and with the dependency arrangement intended for distribution. A passing composite build alone cannot prove that a release using the published artifact includes the required engine contract.

**Acceptance.** Compile, test and build the jar on the declared Java target; compile representative existing API consumers and run SpacePixels tests with the intended engine. For structural engine changes, complete the reference-data comparison below. Do not add brittle tests for class length or private method names, or mirror every implementation branch merely to increase coverage.

### Responsibility map and refactor scope

Paths below are relative to `src/main/java/io/github/ppissias/jtransient/`. This map covers all 50 production files, including package-private helpers. Inclusion is an ownership assessment, not an instruction to split every class.

| Current files | Assessment and destination of responsibility | Findings |
|---|---|---|
| `config/DetectionConfig` | Preserve the serialized profile DTO; derive validated, isolated operation settings. | JA-01 |
| `engine/JTransientEngine` | Retain public facade and top-level stage order; delegate preparation and execution/lifecycle policy. | JA-01/02/03/05/09 |
| `engine/InputRules`, `ExtractedFramesContext` | Retain shared validation/preparation seams; make frame associations explicit and reuse selection policy. | JA-01/05 |
| `engine/ImageFrame`, `FrameTransients`, `TransientEngineProgressListener` | Shared input/export/callback contracts; document buffer ownership and identity before any package/API move. | JA-05/07/09 |
| `engine/PipelineResult` | Compatibility result facade; assemble through internal builders and coherent grouped products. | JA-07/10 |
| `engine/JTransientAutoTuner`, `CalibratedAutoTuner` | Preserve both entry points and objectives; isolate options, measurement, selection and formatting. | JA-02/03/04/08 |
| `core/PixelEncoding`, `FrameCoverage`, `FrameRegistration`, `DataArea` | Cohesive shared image/geometry contracts; retain their distinct purposes and existing margin/coverage rules. | JA-05/07; no numerical redesign |
| `core/FrameDriftAnalyzer` | Retain drift/coverage analysis; consume shared frame contracts and execution context as needed. | JA-05/07 |
| `core/SkyBackground`, `SourceExtractor`, `SpatialGrid` | Keep stateless numerical helpers and standalone extraction; separate shared detection models/diagnostics only during compatible API work. No general index rewrite. | JA-01/03/07 |
| `quality/FrameQualityAnalyzer`, `SessionEvaluator` | Keep measurement and rejection policy distinct; share selection orchestration and remove facade debug dependency when that seam changes. | JA-04/05/07 |
| `core/MasterMapGenerator`, `MasterReferenceAnalyzer`, `MasterVetoMask` | Preserve stack, master-star and veto-mask responsibilities; make scheduling, provenance and reuse dependencies explicit. | JA-02/03/10 |
| `core/TrackLinker`, `FaintStreakFragmentGrouper`, `CameraFixedRejector` | Keep established rejector/grouper seams; extract tracker responsibilities and document mutation/assignment. | JA-03/06/07 |
| `core/CommonMotionRejector`, `ResidualTransientAnalyzer`, `ResidualTransientAnalysis` | Keep filtering before residual rescue/clustering. Make removal ownership explicit; common-motion result placement can evolve with compatibility adapters. No change to grouping/rescue rules. | JA-06/07/09 |
| `core/SlowMoverAnalyzer`, `SlowMoverFrameEvidence`, `SlowMoverAnalysis`, `SlowMoverCandidateResult`, `SlowMoverCandidateDiagnostics`, `SlowMoverSummaryTelemetry` | Already a useful stage/result grouping. Preserve evidence versus confirmed-motion semantics; consolidate legacy projections at a boundary instead of duplicating state. | JA-07/09 |
| `photometry/VariableStarAnalyzer` | Retain photometry orchestration; strengthen input associations, stage outcomes and cancellation. Extract another helper only when it owns a clear stage/product. | JA-03/05/07/09 |
| `photometry/PhotometryStarSelector`, `ApertureMeasurer`, `PhotometricReadinessChecker`, `EnsembleSolver`, `SpatialFrameFilter`, `VariabilityScorer`, `PhotometryMath` | Existing cohesive computational seams, mostly package-private. Keep them; narrow settings and diagnostic dependencies when touched. No numerical rewrite or blanket interface conversion. | JA-01/03/07 |
| `photometry/VariableStarAnalysis`, `StarLightCurve`, `PhotometricReadiness`, `PhotometryFlags`, `VariabilityTier` | Preserve public scientific meanings, flags and frame alignment; separate mutable assembly from finished views gradually. | JA-05/07/09 |
| `telemetry/PipelineTelemetry`, `TrackerTelemetry` | Structured diagnostic contracts with small formatting adapters; clarify index spaces and stage status. Avoid making telemetry the owner of unrelated domain models. | JA-05/07/09 |

### Proposed architecture and delivery order

Keep one library artifact and the current public facades. The intended dependency direction is from orchestration toward stage services and shared contracts; computational helpers should not need an engine facade to access debug state or a telemetry container to name their scientific data.

```mermaid
flowchart TD
    Caller[SpacePixels or another caller] --> API[Compatible engine / tuner / extractor APIs]
    API --> Run[Per-operation settings and execution context]
    Run --> Prep[Frame preparation and quality selection]
    Run --> Stages[Reference, tracking, residual, slow-mover and photometry stages]
    Run --> Tune[Tuning measurement and selection]
    Prep --> Model[Shared frame, detection, geometry and result contracts]
    Stages --> Model
    Tune --> Model
    Run --> Exec[Execution limits, cancellation and diagnostics]
    API --> Views[Result and text compatibility adapters]
    Views --> Model
```

This is a dependency direction, not a new general-purpose pipeline engine. Concrete stage collaborators and plain data objects are sufficient. An interface is useful where there is a real execution/testing boundary, not for every existing static helper. Package names and exact new types should follow a concrete change, with public compatibility assessed first.

| Order | Scope | Reason and dependency |
|---|---|---|
| 1 | JA-01 configuration capture; JA-04 per-operation tuner options | Establish repeatable inputs and remove the existing reason for process-wide tuning coordination. Discuss validation behavior separately from mechanically copying settings. |
| 2 | JA-02 execution ownership, then JA-03 cancellation/failure; JA-09 adapters alongside them | Know who owns workers and when they stop before the application relies on cancellation or admits concurrent work. |
| 3 | JA-05 prepared-frame associations and shared selection orchestration | Stabilize identity and stage inputs before moving tracker internals. |
| 4 | JA-06 one tracking responsibility at a time | Use membership-level controls to preserve the established classification/assignment order. |
| 5 | JA-07 model/result improvements and JA-08 tuning separation where needed | Migrate public contracts gradually; preserve large-buffer sharing, legacy callers and tuner objectives. |
| 6 | JA-10 reusable-reference provenance and measured reuse extensions | Expand caching only after inputs, ownership and invalidation are defined. |
| Alongside each step | JA-11 build, consumer compatibility and focused contract tests | Establish the Java baseline early; verify every adopted boundary without one large refactor release. |

### Verification and coordination with SpacePixels

Each item needs a concrete design discussion before implementation under [AGENTS.md](AGENTS.md). Agree whether it is strictly structural or intentionally changes an API behavior, such as rejecting an overlapping run, returning a copied fallback config, freezing a collection or rejecting a formerly accepted profile. Preserve the distinction in its acceptance criteria and release notes.

For each engine change, run `./gradlew compileJava`, `./gradlew test` and `./gradlew jar` as appropriate, then the SpacePixels tests with `useLocalJTransientBuild=true` and verify the actual resolved dependency pair. Use the same base/change profiles and reference datasets under the sibling [regression procedure](../SpacePixels/AGENTS.md#real-data-regression-method), sequentially for memory-heavy data. A structural refactor should preserve frame rejection, category membership and ordering where contractual, masks/stacks, candidate diagnostics and variable-star results, allowing only agreed timing differences. Investigate any scientific difference before calling the refactor equivalent. Report timing and memory evidence when changing execution or retention.

Coordinate JA-02/03/09 with SpacePixels D02/D04 (job ownership and outcomes), JA-01/04/07/11 with D05/D10 (snapshots and engine integration), and JA-05/10 with iterative detection and session-aware inspection. The application owns jobs, file access, settings persistence and report artifacts; the library owns computational run contracts. Neither review authorizes moving application services into JTransient or implementing the future MCP interface here.

Do not combine these changes with new detection gates, altered association order, thresholds, background models, slow-mover motion confirmation or variable-star statistics. Do not introduce deep copies of every image, replace all static helpers, split by target line counts, remove legacy exports in one sweep, or introduce a global prepared-session cache. Existing algorithm proposals and declined decisions below remain independent.

## Deferred work and optional ideas

| ID | Status | Current scope and condition for revisiting |
|---|---|---|
| QC-1 | Deferred | Star-count rejection has no absolute/relative envelope floor. Counts now share a quality area, but small count changes can still matter. A new floor needs a recovery/false-retention study. |
| ST-1 | Deferred | Missing exposure duration prevents assigning streak endpoint times even when capture timestamps exist. A fallback must represent what is actually known. |
| ST-5 | Deferred | A one-frame streak track can lie on the line of a longer streak track in the same frame and stay separate. On klangwolke-2, with a second profile, a satellite tracked through frames 8-14 also left a separate 12-piece streak in frame 8 and a one-piece streak in frame 11. Establish why the consolidation leaves them apart before changing it. |
| PT-2 | Deferred | A small timed-link step can extend a moving seed with nearly stationary detections. Establish real-data impact before imposing a displacement/velocity gate. |
| PT-4 | Deferred | The geometric linker's fixed 2-degree direction tolerance can limit short steps. Evaluate uncertainty-scaled alternatives on timed and untimed controls. |
| PT-5 | Proposed | `maxStarJitter` serves several tolerances. The maintainer preferred a future registration-quality assessment over splitting tolerances immediately. The fill-edge registration used by camera-fixed rejection is not a general residual-registration QC measurement. |
| PT-6 | Deferred | Greedy continuation/assignment can select a locally favorable association. Compare ambiguous synthetic crossings and real tracks before changing ownership. |
| PT-7 | Deferred | Dense leftover sets can make point linking expensive. Profile representative high-density inputs before adding indices or pruning rules. |
| PT-8 | Deferred | Strict exposure-motion limits infer a scale from footprint area. Assess a PSF/width alternative before changing kinematics. |
| AN-2 | Deferred; reduced scope | Private same-frame anomaly groups do not retain their seed line. They are only offered to confirmed-streak consolidation; they are no longer independently exported. Revisit only if a concrete consolidation error is demonstrated. |
| RS-1 | Deferred; discuss first | Common-motion grouping can include two stationary leftover sources. The maintainer retained current behavior because this filter seeks instrumental patterns. Camera-fixed rejection now addresses a major dither/hot-pixel population independently; it does not change the common-motion acceptance rule. |
| RS-3 | Deferred | Two-point LOCAL_REPEAT lacks a calibrated chance-alignment check. Begin with diagnostics under measured source density rather than an uncalibrated new veto. |
| SM-2 | Deferred | A maximum stack's sigma is not a single-frame sigma. Compare empirical maximum-stack nulls across frame counts before relating this threshold to per-frame Auto-Tune settings. |
| AT-3 | Deferred | The negative image `2 * master - frame` can contain residual star rings under changing seeing. Quantify post-veto ring leakage first; selecting only easy median-seeing frames would hide relevant leakage. |
| AT-4 | Deferred; limitation documented | False-positive budgets count surviving single-frame detections, not final false tracks/anomalies. Track-level calibration needs multi-frame nulls and a justified runtime budget. |
| XC-2 | Deferred | No shared chance-alignment model covers linking, local repeats and common motion. Start with stage-specific null diagnostics; a uniform independent-pixel formula is not a calibrated candidate probability. |
| XC-3 | Partly implemented; frame work deferred | Master-star extraction now adds a local-sky pass. Per-frame extraction still uses one sky level/noise estimate. Preserve extended emission and propagate local noise consistently before proposing a frame-sky redesign. A later median-stack check of leftovers remains an unimplemented idea, not an accepted next step. |
| VS-2 | Deferred | Ensemble convergence still watches zero points only, not plane coefficients. A future fix should monitor changes in predicted spatial corrections and report convergence/iteration exhaustion; increasing the iteration cap alone is insufficient. |
| VS-5 | Deferred | APERTURE compares percentile ranges, not temporal agreement. Any stronger check should use shared finite frames and test recovery under measurement noise and blending. |
| VS-6 | Deferred | Persistence and adjacent-pair statistics bridge missing observations. Preserve indices/times and define maximum support gaps before treating surviving points as continuous temporal evidence. |
| VS-8 | Deferred | Five similar unsaturated maxima can resemble a clipping ceiling. Consider a caller-supplied ceiling in the correct pixel domain and stronger automatic evidence; no new ceiling option currently exists. |
| VS-9 | Deferred | The nominal selected-star minimum differs from check B's counted linear-range minimum. With the default 50 required references and a 90% faint bound, an ideal sample needs at least 55 before further exclusions. Improve cap validation and distinguish insufficient samples from measured nonlinearity. |
| VS-10 | Deferred | In a frame a streak track skips, photometry flags the stars along one piece's length around a position interpolated between two pieces, not along the streak's predicted path in that frame. On klangwolke-2 a satellite's path in its missing frame 3 (x about 7270-7750) was only partly flagged, and which part depended on the order of the pieces. Flagging the whole predicted path must be weighed against losing good measurements when a flashing satellite left no trail in that frame. |
| SM-F1 | Proposed | Three-bin temporal stacks or a measured slow-mover path, described in the old design, are not implemented. Compare real-mover recovery, false positives and runtime before introducing either; current frame-support/stationarity screens do not establish motion. |

For VS-2/5/6/8/9 the assessment contains component reproductions and their caveats. They are retained as evidence, not presented as measured false-positive rates on the reference datasets. Secondary ideas about per-frame quantisation coverage, source-photon-noise errors, reference-population robustness, capping/performance and pre-epoch metadata remain unapproved investigations in that archive; they are not additional confirmed defects.

## Decisions to keep current behavior

| ID | Decision | Current behavior |
|---|---|---|
| IN-3 | Declined | Tracking returns no categories when fewer than three frame lists remain; slow movers and photometry are separate pipeline stages. Do not reinterpret this as an instruction to recover one/two-frame tracks. |
| ST-4 | Declined | Keep existing streak-chain linking without a new global common-line/distance gate. |
| PT-1 | Declined | Keep the geometric rhythm check's distance-based rule; it makes no capture-time assumption. |
| PT-3 | Declined | Keep timed tracks that can bend; no final straightness requirement was accepted. |
| SM-1 | Declined | No drift-path veto on maximum-stack slow-mover candidates. Camera-fixed point filtering does not implement this proposal. |
| PH-1 | Declined | Slow-mover footprints continue to flag nearby photometry stars across frames. |
| PT-9, X-1, X-2, X-3 | No separate linker change | Retain exposure-start timing and uniform metadata contracts. Engine/tuner `InputRules`, missing timestamp defaults and linker timestamp checks cover applicable boundary cases; do not schedule the old mixed-metadata examples as independent fixes. |
| EX-3 | Formula retained; documented | Extraction FWHM has its existing second-moment convention (about sqrt(2) above a round Gaussian FWHM). Photometry and SpacePixels Night & Equipment measure size separately. |
| XC-1 | Formula retained; documented | Integrated significance assumes uncorrelated pixels and photometry errors omit source photon noise. State these limits; do not insert a numerical correlation correction without blank-aperture evidence. |

## Completed and retired findings

| IDs | Outcome and implementation evidence |
|---|---|
| IN-1 | Implemented in SpacePixels: Manual Transient Inspection receives a configuration copy (`fd98a9a`, `475fc15` in SpacePixels). |
| IN-2 | Implemented: engine/tuner `InputRules` and uniform linker timestamps (`0f28d7d`). Missing time is -1; 0 remains a real timestamp. |
| IN-4 | Implemented: effective point minimum cannot fall below three; invalid ratios use the default (`3468ac1`). |
| XC-4, EX-1 | Implemented: coverage/quality/data areas, common sky measurement area, kept-frame search areas, tuner crop constraints, removal of edge/void settings (`0200684`, `5e99e43`, `ba7c75a`, `13f7bce`, `8dcb41b`, `bec3455`). |
| EX-2 | Retired: darkness/void probes and their configuration were removed with data areas. |
| ST-2, ST-3 | Implemented: streak motion uses a unit-axis pixel displacement; a stationary multi-frame streak fails the motion check (`b502535`, `46e1dda`). |
| AN-1, AN-3 | Implemented: standalone suspected-streak results removed; same-frame groups use present frame keys and survive only through confirmed-streak consolidation (`b92a985`). |
| RS-2 | Implemented: residual span, gaps and coverage count kept frames; the line fit retains sequence indices (`48d7a9b`). |
| AT-1, AT-2 | Implemented: calibrated crops fit inside the pool data area, rates use owned searchable pixels and injections have a safe margin (`8dcb41b`). |
| AT-5 | Implemented: legacy mask growth matches `max(1, round(jitter / 2))` (`15f63b3`). This did not make the legacy master-grow threshold identical to the calibrated tuner's configurable hysteresis. |
| DOC-1, HY-1 | Implemented: references/defaults/contracts corrected and unused code removed; output stays behind debug switches (`a6069a5`, `ed5aaea`, `e2e89d2`, `6992e9e` and subsequent documentation updates). |
| VS-1, VS-3, VS-4, VS-7 | Implemented: crossing positions use registration-corrected coordinates, interpolation uses available capture times, every same-frame track fragment is considered, and undefined response slopes are INCONCLUSIVE. See current photometry sources and reference. |
| XC-3 master pass | Implemented: global-sky stars plus nonoverlapping local-sky stars seeded at least at 5 sigma (`ffa60aa`); the first pass is retained to mask extended emission that per-frame extraction also detects. |
| Camera-fixed rejection | Implemented: fill-edge registration and camera-coordinate groups (`4b443d7`), with `enableCameraFixedRejection` and exported review groups. The earlier Option A sensor study is historical, not a pending implementation. |
| Anomalies on streak paths | Implemented: an anomaly on a confirmed streak's line in the same frame, or where the streak's motion puts it in a skipped, preceding or following frame, joins that streak; `AnomalyDetection.lowConfidence` is removed (`de1dc48`). On klangwolke-2, 17 of 39 anomalies joined (24 of 40 with a second profile), on klangwolke-1 one of six; the other 16 reference runs were identical. Joined pieces now flag the stars they cross in photometry: one borderline POSSIBLE star became CONSTANT, and with the second profile one constant star fell below the usable-frame minimum. |

The [archived engine review](docs/archive/code-review-2026-10.md#implementation-status-2026-10-18) retains full commit/decision provenance. The [maintenance notes](docs/archive/maintenance-notes-2026-10-19.md) retain the 19-dataset per-step results, tuner differences and M81-kumar experiments. In particular, the recorded HIGH-profile M81 sequence is LR 274 → 84 → 10 and CM 52 → 5 → 3 after master-star and camera-fixed changes, respectively; these are historical measurements with the recorded profiles, not new benchmarks from this cleanup.

## Verification for future changes

1. Define the observable failure and the supported input contract. Use a deterministic component reproduction and an unchanged control where possible.
2. Discuss the proposed rule and acceptance condition before changing an algorithm. Retain the decision/status here.
3. Run engine tests and the consuming SpacePixels tests with the local composite build. Compare base/change on the same reference profiles and datasets using the SpacePixels regression procedure; compare frame rejection, tracks/anomalies, camera/common-motion groups, variable-star results star by star, and timings.
4. Report differences before committing. Preserve user profiles/preferences, use scratch diagnostic code, and keep memory-heavy runs sequential.
5. Update the relevant current reference after implementation, mark the item with evidence here, and archive lengthy experiment narratives rather than growing `AGENTS.md` into a session log.
