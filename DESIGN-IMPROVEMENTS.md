# Design improvements and review decisions

Consolidated and checked against the source tree on 2026-10-19, at engine commit `f084ab9`. This is the single active decision list for engine improvements and review limitations. Current implementation is described in [ALGORITHM.md](ALGORITHM.md) (detection, slow movers, photometry and tuning), [PIPELINE.md](PIPELINE.md) (API contracts), and [CONFIG.md](CONFIG.md) (fields/defaults).

Original evidence and maintainer comments are preserved in the [October engine review](docs/archive/code-review-2026-10.md), [variable-star assessment](docs/archive/variable-star-assessment-2026-10.md), [slow-mover design](docs/archive/slow-mover-design-2026-10.md), and [maintenance/regression notes](docs/archive/maintenance-notes-2026-10-19.md). Original line numbers, suggested orders and statements about missing features describe those earlier checkouts.

The separate [design and architecture review](#design-and-architecture-review-2026-10-20) below was added on 2026-10-20. Its `JA-` findings concern library structure and engineering contracts; the existing algorithm findings and decisions retain their status. The [structural blueprint](#structural-blueprint-packages-classes-and-dependencies) makes the proposed package boundaries, class separations and dependency rules concrete.

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
| JA-01 | High | Proposed | Capture run configuration and centralize validation/alignment | One operation uses one coherent settings snapshot; GUI and engine use the same rule definitions. |
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

**Rules ownership clarification (agreed with the maintainer, 2026-10-20).** JTransient owns engine-setting meanings, defaults, valid values, cross-field relationships and alignment rules. Migrate the existing finite-number checks and six paired-setting rules from SpacePixels [DetectionSettingsRules](../SpacePixels/src/main/java/eu/startales/spacepixels/config/DetectionSettingsRules.java) into a shared JTransient rules service, and remove their application-side definitions. SpacePixels must consume that service instead of maintaining a second set of rules or comparing paired values in its widget code. This records the agreed ownership direction; the concrete implementation remains Proposed.

The shared service should expose two operations backed by the same rule definitions:

- **Validate:** inspect a configuration without modifying it and return structured issues identifying the affected fields and violated rule.
- **Align:** return an adjusted configuration copy, plus structured changes with old/new values and reasons. Preserve each existing pair's adjustment direction and report the changes to the caller.

SpacePixels owns parsing editor text, focusing invalid fields, formatting/displaying issues and adjustments, updating widgets, layout, step sizes, basic/expert/hidden classification, persistence and migration. Its GUI captures values, calls shared alignment, applies returned changes to the widgets, and validates before saving or starting work. The engine validates its captured snapshot with the same service before execution. Calling the shared validator at both boundaries is deliberate; there is only one definition of each rule. Prefer rejecting inconsistent execution input with structured issues, with alignment requested explicitly by callers, rather than silently changing submitted scientific settings. Reconcile this with existing engine fallback behavior before enforcing new rejection rules.

Genuine numeric validity limits should be exposed as JTransient metadata that GUI bindings can consume. Distinguish them from convenient spinner ranges before making existing widget bounds engine restrictions. Preserve special values such as master-grow `0`, hidden fields and accepted profile semantics; do not expand an automatic setting into a fixed persisted value during alignment. Keep shared rule tests in JTransient and GUI parsing, binding and feedback tests in SpacePixels.

**Acceptance.** Use a controlled pause after capture, change the caller's config, and verify the active run still uses its captured values. Verify local overrides and tuning success/fallback leave the base untouched. Check representative persisted profiles, hidden fields, sentinels and invalid numeric values through engine and tuner entry points. Verify GUI and direct callers obtain the same issues from the shared validator, and that GUI alignment preserves current pair behavior without retaining separate rule definitions. Compare unchanged valid runs on reference data before adopting new validation behavior.

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

### Structural blueprint: packages, classes and dependencies

**Proposed structural design, added 2026-10-20.** This elaborates JA-01–11 rather than creating another work queue or authorizing their implementation. The maintainer agreed the JA-01 rules-ownership direction separately. Names below specify a reviewable target; an individual change can refine a name with its rationale recorded here. Keep one Gradle project and published jar. Introduce a package when its boundary is implemented, without creating empty directories or moving every public class in one release.

#### Current dependency assessment

The current package graph is not layered: `engine` calls `core`, `quality` and `photometry`, while those packages refer back to `engine`. Examples are `SourceExtractor`, `MasterMapGenerator`, `TrackLinker`, `SessionEvaluator` and `VariableStarAnalyzer` reading `JTransientEngine.DEBUG`; shared frames and progress callbacks also reside in `engine`. These source dependencies were checked again when preparing this blueprint. The cycles mix access to data/diagnostics with orchestration; they do not demonstrate incorrect calculations.

`core` contains extraction, image geometry, stacks, tracking, slow movers and residual analysis. Its classes often have useful responsibilities, but the package does not express those boundaries. Conversely, `photometry` already gives its helpers a coherent home and uses package-private implementation classes. Keep that pattern. `PipelineTelemetry` has no imports of the science packages today, but placing their frame records inside it makes those packages depend on a pipeline-level container; moving diagnostics must reverse that ownership carefully.

There are also dependencies hidden by today's shared package: `TrackLinker` calls the streak-shape predicate on `SourceExtractor`, and deprecated `CommonMotionRejector.apply` recomputes residual analysis. The blueprint assigns the shape predicate to tracking with shared footprint geometry below it; the deprecated cross-stage operation remains a compatibility adapter. These are specific dependency changes to verify, not imports to rename mechanically.

#### Target package layout and allowed dependencies

All names are beneath `io.github.ppissias.jtransient`. The dependencies column names permitted **direct project-package dependencies**, in addition to the JDK and existing justified external dependencies. It describes new canonical implementations; the explicitly retained compatibility facades are covered separately below. Packages do not become separate artifacts.

| Package | Owns | Allowed project dependencies |
|---|---|---|
| `model` | Shared frame/detection/track contracts: top-level `ImageFrame`, `DetectedObject`, `Pixel`, `Track`, `AnomalyDetection` and `AnomalyType`. Only data used across stages belongs here. | None. |
| `config` | Existing `DetectionConfig` schema, shared `DetectionConfigRules`, field/range metadata, validation issues, alignment results and captured settings. | None; preserve the existing Gson annotation dependency. |
| `execution` | `ExecutionPolicy`, `OperationContext`, cancellation/progress/diagnostic contracts, stage identity/outcomes and `ExecutionSession` for owned or borrowed resources. | None. |
| `image` | Pixel encoding, coverage, registration, data areas, drift analysis, shared spatial lookup, `BlobGeometry`/`FootprintGeometry` and `FrameSequenceValidator`. `SkyBackground` remains an image transformation here. | `model`, `execution` (optional operation control for computations). |
| `extraction` | Standalone `SourceExtractor`, extraction/background results and private component-growth/morphology helpers when needed. | `model`, `image`, `config`, `execution`. |
| `quality` | `FrameQualityAnalyzer`, `SessionEvaluator`, top-level `FrameMetrics` and `SessionThresholds`; quality measurements and rejection decisions. | `model`, `image`, `config`, `execution`, `extraction`. |
| `reference` | `MasterMapGenerator`, `MasterReferenceAnalyzer`, `MasterVetoMask`, reference results and `PreparedMasterReference` provenance. | `model`, `image`, `config`, `execution`, `extraction`. |
| `tracking` | `TrackLinker` stage facade, vetoing, streak/point association, assignment/rescue, camera/common-motion filtering and their results/diagnostics. | `model`, `image`, `config`, `execution`, `reference`. |
| `residual` | `ResidualTransientAnalyzer` and local-rescue/activity-cluster results over final leftovers. | `model`, `image`, `config`, `execution`. |
| `slowmover` | Maximum-stack morphology/evidence analysis, candidates, diagnostics and its result bundle. | `model`, `image`, `config`, `execution`, `extraction`, `reference`. |
| `photometry` | Existing photometry stage/helpers/results plus its own input and frame/diagnostic contracts. | `model`, `image`, `config`, `execution`, `extraction`, `quality` (measurement contracts only). |
| `tuning` | Calibrated and legacy tuner facades, options, sampling, measurements, selection policies, summaries and text formatters. | `model`, `image`, `config`, `execution`, `extraction`, `quality`, `reference`. |
| `telemetry` | Pipeline diagnostic aggregation and text formatting, including compatibility projections of feature diagnostics. | `model`, `image`, `config`, `execution`; diagnostic/result contracts from `extraction`, `quality`, `reference`, `tracking`, `residual`, `slowmover`, `photometry`, `tuning`. |
| `engine` | Public pipeline facade/results, private preparation and pipeline orchestration, and adapters for existing engine entry points. | All listed packages as needed; no canonical stage depends on `engine`. |

`core` remains a compatibility location for existing public names during migration. Add new computational code to the package owning its responsibility. No new `util`, `common`, `manager` or `service` catch-all package is proposed. Keep the existing `engine` entry-point package rather than introducing a second `api` package solely to rename the facade location.

The dependency rules have practical consequences:

- Foundation contracts do not import analyzers, pipeline results or aggregate telemetry. `model` does not acquire settings, executor ownership or reporting behavior.
- Quality and reference construction use extraction independently. Neither calls the full engine, and neither depends on the other to perform QC orchestration. The engine selects frames, then requests references from that selection.
- Tracking consumes extracted observations and a reference mask; it does not run extraction, slow movers or photometry. Put mask construction/overlap mechanics in `reference` when genuinely shared; preserve the distinct masks' scientific semantics.
- Camera-fixed filtering stays inside tracking before point association. Common-motion filtering stays a separately invoked tracking operation after tracking and before residual analysis. The engine owns this order. Moving a class into `tracking` does not move its execution earlier.
- The canonical common-motion rejector does not recompute residual analysis. Keep deprecated `CommonMotionRejector.apply` at the legacy boundary with its existing behavior, routing its two operations through the appropriate public component facades.
- Photometry receives canonical tracks and slow-mover detection footprints as data. It does not call either detector or depend on their implementation classes. Its own star extraction, selection and mask retain their current purpose.
- Tuning calls lower-level image/extraction/quality/reference components, never `JTransientEngine.runPipeline`. Preserve its separate sample/measurement policies. Neither scientific stages nor shared profile validation depend on tuning.
- Each stage owns its diagnostic types. `telemetry` aggregates them; it does not supply the types a stage must instantiate. Stages receive a narrow execution context instead of importing `PipelineTelemetry` or reading engine statics.
- No canonical package cycle is allowed. Calls between features must follow the table; when an apparent reverse dependency appears, pass the required data or extract a genuinely shared lower-level contract before adding the import.

#### Class separations and ownership

These are concrete destinations for responsibilities, not a requirement to create one class for every method. Keep each stage's coordinator and helpers in the **same Java package** so helpers can be package-private; subpackages do not share package-private access. Existing cohesive static computations may remain static.

| Current responsibility | Proposed classes and boundary | State/visibility rule |
|---|---|---|
| Engine entry points, executor sizing, stage order, result assembly | `engine.JTransientEngine` delegates to package-private `PipelineRunner`, `FramePreparationService` and `PipelineResultBuilder`. Shared scheduling/resource policy lives in `execution`. | Facade owns engine lifetime; an operation owns its context and work. Helpers do not retain a caller's live config. |
| Parallel lists in `ExtractedFramesContext` and repeated master-only preparation | Package-private `engine.PreparedFrame` pairs frame, metrics, detections and registration; `PreparedSequence` groups selection/data-area facts. `FramePreparationService` shares selection policy and supports quality-only versus full extraction. | Keep explicit original sequence identity and retained position. Pass feature-specific inputs to stages; never export `PreparedSequence` into lower-level packages. |
| Input sequence rules currently in engine-only `InputRules` | `image.FrameSequenceValidator` owns dimensions, metadata availability, exposures, sequence identity and optional master-dimension checks. | Public reusable validation component; entry points still decide their own minimum frame counts and fallback outcomes. File-format validation remains in SpacePixels. |
| Profile values and GUI pair alignment | `config.DetectionConfigRules` with typed field identifiers, `ValidationIssue`, `AlignmentResult` and `ConfigAdjustment`; numeric validity metadata is shared with GUI bindings. | One definition of each rule, as agreed in JA-01. Keep `DetectionConfig` serialization unchanged and UI widget behavior outside the library. |
| Extraction computation and algorithm-nested data models | `extraction.SourceExtractor` retains its standalone service; `ExtractionResult` and `BackgroundMetrics` belong to `extraction`. Shared detections/pixels belong to `model`; reusable `BlobGeometry` and its measurement belong to `image.FootprintGeometry`. | Extract region-growth or morphology helpers only when they have an independent responsibility. Preserve footprint identity and numeric conventions. |
| Tracking's binary-star streak-shape test currently on `SourceExtractor` | Package-private `tracking.StreakShapeFilter` owns `isBinaryStarLikeStreakShape`, using shared `image.FootprintGeometry`; the old public extractor helper delegates through a tracking facade. | Tracking no longer imports the extractor implementation to judge an existing footprint. Retain the old public helper's signature and numerical behavior. |
| Star veto and population separation mixed with streak assembly | Package-private `tracking.TransientVeto` produces explicit point/streak populations and the veto diagnostics. `StreakAssembler` owns streak linking and delegates to `FaintStreakFragmentGrouper`; `StreakConsolidator` owns merging. | Vetoing does not quietly run point linking or anomaly rescue. Preserve the current transient-only entry point's included streak work through orchestration in `TrackLinker`. |
| Timed and geometric loops, conflict resolution and used-point sets | Package-private `TimedPointLinker` and `GeometricPointLinker` own their existing proposal/continuation logic. `DetectionAssignments` owns accepted membership, and `TrackLinker` preserves the current invocation/claim order. | No new global optimization, changed greediness, equality rule or tie order. `DetectionAssignments` is run-local and is not a global detection registry. |
| Anomaly rescue, private same-frame groups and final review exports | Package-private `AnomalyRescuer`, private/package-private `AnomalyGroup`, and `TrackingExports`; reuse `StreakConsolidator` and the existing faint-fragment helper. | A group is an internal consolidation input. Final exports preserve the current categories, shared detection graph and empty-frame positions. |
| Camera/common-motion results nested under unrelated stage models | `tracking.CameraFixedGroup`, `CommonMotionGroup`, `TrackingResult`, `TransientFilterResult` and `TrackingDiagnostics`; rejectors remain separate components. | Keep removed groups available for review. A common-motion result is available independently of enabling residual analysis. |
| Calibrated tuning from sample preparation through formatted explanation | `tuning.CalibratedAutoTuner` coordinates package-private `CalibrationSamplePreparer`, `CalibrationMeasurer`, `CalibratedSelectionPolicy` and `CalibratedTuningReportFormatter`. `Calibration` and `CalibrationSummary` distinguish full measurements from compact retained output. | Measurements are immutable after publication. Choosing settings or formatting a report does not mutate the measured sweep. Keep `calibrate`/`configFor` reuse. |
| Legacy tuning sweep, selection and report construction | `tuning.LegacyAutoTuner` coordinates package-private `LegacySampleSelector`, `LegacySweepEvaluator`, `LegacySelectionPolicy` and `LegacyTuningReportFormatter`. A top-level `AutoTuneProfile` and tuner-specific results replace ownership by a legacy implementation class. | Preserve legacy scoring separately from calibrated budgets. Share only proven equivalent crop/mask mechanics; retain explicit units in each result. |
| Photometry orchestration with crossing geometry and pipeline-owned records | Keep `photometry.VariableStarAnalyzer` as stage coordinator. Add `PhotometryInput`, `PhotometryFrameStat` and `PhotometryDiagnostics` in the same package. Extract package-private `TrackCrossingFlagger` around `flagCrossings`/`flagTrack` and related interpolation/segment code. | Its input has validated frame associations and shared model tracks. Keep existing selection/measurement/readiness/ensemble/scoring helpers; no automatic split of every remaining private method. |
| Stage counters converted into pipeline containers and text | Stage-owned diagnostic builders are private/package-private. `telemetry.PipelineTelemetry` holds completed stage summaries; package-private `PipelineTelemetryFormatter` builds the text. Tuner formatters stay with their own package-private measurement details. | Stages never call formatters to determine results. Legacy conversion belongs to aggregation/adapters, including `SlowMoverSummaryTelemetry.toLegacyTelemetry`. |

`ResidualTransientAnalyzer`, the slow-mover stage and the existing photometry numerical helpers do not need further class division merely because they contain several hundred lines. Revisit a split when a new independently testable responsibility or ownership boundary emerges. Preserve local statistical helpers such as `PhotometryMath` until another subsystem actually needs the same semantics; do not create a general maths abstraction by combining superficially similar formulas.

#### Public surface and encapsulation

The supported public surface should consist of pipeline entry points/results in `engine`, settings and rules in `config`, shared data in `model`, explicit image and execution contracts, and the standalone stage/tuner facades and their inputs/results. Publish a `package-info.java` description for each introduced boundary with its purpose, ownership, units/index spaces and thread-safety expectations. Public result fields do not make every referenced helper a supported extension point.

Use package-private top-level helpers and private nested temporary types by default. Keep existing package-private photometry helpers and `SlowMoverFrameEvidence` private to their stage package. Java visibility must match actual calls: if `engine`, `tuning` or a legacy facade must call a component in another package, expose a deliberate public component method or a small public facade in that component's package; do not mark it package-private and assume a parent or sibling package can reach it. Such public contracts need documentation and compatibility tests too.

New shared `DetectedObject`, `Track` and related models are top-level contracts, independent of their producer. Publish stable membership views when compatible, and keep mutable builders inside the owner package. Preserve object identity and shared buffers explicitly. Do not implement value equality during migration or equate unmodifiable containers with immutable elements. Each reusable component should have explicit inputs and outputs, without global settings, implicit engine callbacks or application-specific state.

Do not expose the executor field, the whole mutable `PipelineTelemetry`, Swing components, file paths for app workflows or every field of `PreparedSequence` merely to connect components. `OperationContext` carries cancellation, progress and diagnostics; `ExecutionSession` exposes the agreed resource operations and ownership policy without transferring executor shutdown rights to a stage. Neither is a service locator for arbitrary pipeline products. Add interfaces for real substitution boundaries, such as cancellation/progress sinks or a controllable scheduler, while retaining concrete stateless analyzers elsewhere.

#### Compatibility and package migration

A package move changes a Java class's binary name, including nested classes such as `SourceExtractor$DetectedObject` and `TrackLinker$Track`. SpacePixels imports many existing public helper and result types directly. Treat all existing public classes/methods/constructors as compatibility obligations until a release decision says otherwise; no current helper becomes inaccessible merely because its target design would prefer package-private visibility.

Keep the old `core` entry points, engine tuner entry points, nested models, telemetry records and constructor signatures available while new implementations are adopted. Compatibility code can delegate to a new public component facade, but must not maintain a second implementation of detection or configuration rules. Retain existing public fields and their declared generic types. Renaming imports, inheritance alone, or an unchecked cast from `List<OldDetectedObject>` to `List<NewDetectedObject>` does not provide a sound migration.

Use one of two explicit steps for a model boundary: first extract responsibilities while retaining the existing public model types under a temporary dependency exception; then add canonical contracts and adapters when that boundary can preserve the full object graph. If converting a completed result graph, use one identity map per conversion so a detection shared between a track, anomaly/group and transient view remains shared. Preserve image/mask aliases and measure any metadata-copy cost. Mutable input/output APIs may need more than a one-way adapter; verify their existing mutation contract before claiming source or binary compatibility.

During migration, legacy facade files may still reference old engine callbacks or nested model names. Track each such exception by source file, dependency and removal condition in the relevant JA item's implementation record. Canonical computational classes must not import compatibility facades. Distinguish these temporary exceptions from the acyclic target graph; do not claim the entire existing package graph is acyclic while the legacy API still creates cycles. Do not invent a generic `compat` package for old names: wrappers must remain at the original fully qualified names to preserve callers.

Migrate a vertical slice at a time: introduce its lower-level contracts, extract its private collaborators, add facade/adapters, switch engine and direct consumers, verify behavior, then retire the duplicate implementation. Deprecation and eventual removal of old names require an explicit release/version decision. Keep constructor/field compatibility, Java target support, silent defaults and the Gson profile schema visible in release checks. No Java module descriptor, new runtime dependency or multi-artifact publication is needed for this plan.

### Responsibility map and refactor scope

Paths below are relative to `src/main/java/io/github/ppissias/jtransient/`. This map accounts for all 50 current production files, including package-private helpers. Destinations are canonical implementation homes under the blueprint; existing public names remain through the compatibility process above. It is a migration inventory, not an instruction to split every class.

| Current files | Canonical destination and action | Findings |
|---|---|---|
| `config/DetectionConfig` | Keep in `config`; add shared rules/metadata and captured settings without changing profile fields. | JA-01 |
| `engine/JTransientEngine` | Keep in `engine`; extract private runner, preparation and result builder; delegate resource policy to `execution`. | JA-01/02/03/05/09 |
| `engine/InputRules`, `ExtractedFramesContext` | Sequence validation goes to `image.FrameSequenceValidator`; engine context becomes private `PreparedFrame`/`PreparedSequence` associations. | JA-01/05 |
| `engine/ImageFrame`, `FrameTransients`, `TransientEngineProgressListener` | Canonical frame data goes to `model`; keep pipeline frame export in `engine`; new progress contract goes to `execution`, with the old callback adapted. | JA-05/07/09 |
| `engine/PipelineResult` | Keep pipeline result in `engine`; build coherent groups from stage-owned results and retain legacy constructors/fields. | JA-07/10 |
| `engine/JTransientAutoTuner`, `CalibratedAutoTuner` | Canonical implementation in `tuning.LegacyAutoTuner` and `tuning.CalibratedAutoTuner`; keep old facades and adapt profiles/results. | JA-02/03/04/08 |
| `core/PixelEncoding`, `FrameCoverage`, `FrameRegistration`, `DataArea` | Canonical home `image`; retain their distinct purposes, numerical behavior and compatibility names. | JA-05/07 |
| `core/FrameDriftAnalyzer` | Canonical home `image`; consume `model.ImageFrame` without an engine dependency. | JA-05/07 |
| `core/SkyBackground`, `SourceExtractor`, `SpatialGrid` | Sky transformation and shared spatial lookup go to `image`; extraction service/results go to `extraction`; shared detection/pixel data goes to `model`. | JA-01/03/07 |
| `quality/FrameQualityAnalyzer`, `SessionEvaluator` | Keep in `quality`; promote shared metric/threshold data out of analyzer nesting and remove facade debug dependencies. | JA-04/05/07 |
| `core/MasterMapGenerator`, `MasterReferenceAnalyzer`, `MasterVetoMask` | Canonical home `reference`; retain three responsibilities and add provenance independently of caching. | JA-02/03/10 |
| `core/TrackLinker`, `FaintStreakFragmentGrouper`, `CameraFixedRejector` | Canonical home `tracking`; separate responsibilities as above, retaining track/anomaly data in `model` and groups/diagnostics in `tracking`. | JA-03/06/07 |
| `core/CommonMotionRejector`, `ResidualTransientAnalyzer`, `ResidualTransientAnalysis` | Common-motion component/group goes to `tracking`; residual miner/results go to `residual`. Legacy aggregate group placement remains an adapter concern. | JA-06/07/09 |
| `core/SlowMoverAnalyzer`, `SlowMoverFrameEvidence`, `SlowMoverAnalysis`, `SlowMoverCandidateResult`, `SlowMoverCandidateDiagnostics`, `SlowMoverSummaryTelemetry` | Canonical home `slowmover`; keep stage grouping and package-private frame evidence. Move legacy telemetry projection into the adapter/aggregation boundary. | JA-07/09 |
| `photometry/VariableStarAnalyzer` | Keep in `photometry`; strengthen input associations, extract crossing geometry and consume local diagnostic contracts. | JA-03/05/07/09 |
| `photometry/PhotometryStarSelector`, `ApertureMeasurer`, `PhotometricReadinessChecker`, `EnsembleSolver`, `SpatialFrameFilter`, `VariabilityScorer`, `PhotometryMath` | Keep existing package-private helpers in `photometry`; update shared contracts without rewriting their computations. | JA-01/03/07 |
| `photometry/VariableStarAnalysis`, `StarLightCurve`, `PhotometricReadiness`, `PhotometryFlags`, `VariabilityTier` | Keep scientific results in `photometry`; own photometry frame/diagnostic records here and preserve meanings, flags and frame alignment. | JA-05/07/09 |
| `telemetry/PipelineTelemetry`, `TrackerTelemetry` | Keep pipeline aggregation/formatter in `telemetry`; canonical tracking diagnostics go to `tracking`, photometry diagnostics to `photometry`, with old nested/public records retained as adapters. | JA-05/07/09 |

#### Structural acceptance checks

Before adopting a slice, identify its current methods/types, target package, public signatures retained, mutable state owner and any temporary dependency exception. Verify all 50 files remain accounted for as the inventory changes. Prefer package/import and visibility checks for agreed boundaries, supplemented by source review for same-package calls and fully qualified references; a raw import count alone does not prove separation.

Check that canonical dependencies follow the table, feature helpers stay inaccessible to external callers, and lower-level stages do not import `engine`, legacy `core` facades or aggregate `PipelineTelemetry`. Check actual public method/field signatures, not just imports: a supposedly independent input/result can still expose an implementation-nested type. Compile a small external consumer using the existing API and representative standalone components, in addition to compiling SpacePixels. Binary compatibility needs its own check against the previous jar; recompiling consumers alone does not establish it.

Run focused component/contract tests and the engine/SpacePixels regression procedure for every code slice. Compare classification membership, identity/alias relationships, frame indexing, ordering where contractual and numerical outputs, as well as counts and timings. For this blueprint update only the source/dependency inventory and documentation were checked; no structural code change, architecture test, build or real-data run was performed.

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

This diagram summarizes the [structural blueprint](#structural-blueprint-packages-classes-and-dependencies); its package dependency table and migration inventory provide the concrete target. Concrete stage collaborators and plain data objects are sufficient. An interface is useful where there is a real execution/testing boundary, not for every existing static helper. Adopt the proposed names and boundaries one reviewed slice at a time, with public compatibility assessed before moving a type.

| Order | Scope | Reason and dependency |
|---|---|---|
| 1 | JA-01 shared validation/alignment rules and configuration capture; JA-04 per-operation tuner options | Establish one rules authority and repeatable inputs; remove the existing reason for process-wide tuning coordination. Discuss validation behavior separately from mechanically copying settings. |
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

## Review

Comments from a review of this document on 2026-10-20, checked against the production tree at `bfafd64`. That commit does not change `src/main/java` relative to the architecture review's `b424a6e`, and the library version is still `1.0.5`. The blueprint's inventory matches this tree: 50 production Java files, 36 test files, and six paired rules in SpacePixels `DetectionSettingsRules.Pair`. Sampled evidence also matches the source: mutable `DetectionConfig` with stage-local clones, static tuner grids, in-place frame sorting, one frame pool reused by photometry after extraction, `IntStream.range(...).parallel()` in `MasterMapGenerator`, fallback results that keep the caller's `baseConfig` reference, `bestTransientRatio` assigned from `noisePerMpixFrame` in the calibrated tuner, `shutdown()` without waiting, and the iterative pipeline's full-session `short[][]` master. These comments do not change the status of any item above and do not authorize implementation.

The decision list is in good shape. Algorithm findings stay separate from the JA items, statuses mean what the legend says, and the blueprint keeps one jar, the current public names, and the scientific stage order. The next useful edit is to close the four questions that change the dependency table and the acceptance tests for steps 1 and 2.

> **Remark (2026-10-20):** The responses below distinguish factual corrections, clarifications and design preferences. There are seven questions below; each has a response. These remarks leave proposal statuses and implementation authorization unchanged.

### Comments

**The streak-shape move conflicts with the dependency table.** `SourceExtractor.isBinaryStarLikeStreakShape` is public, and `TrackLinker` calls it. The class table puts the predicate in package-private `tracking.StreakShapeFilter` and has the old extractor helper delegate through a tracking facade. The canonical home of `SourceExtractor` is `extraction`, whose allowed dependencies are `model`, `image`, `config` and `execution`. A delegate on that class adds `extraction` → `tracking`. Once `DetectedObject` lives in `model`, tracking does not need the extractor to judge a footprint. The arrangement that preserves the table is: measurements live in `image.FootprintGeometry`, the decision lives in tracking, and the old public method stays on a compatibility facade at today's class name. That facade is the temporary exception allowed to call tracking. The canonical extractor then has no streak-shape method.

> **Remark (2026-10-20):** Agree with the proposed arrangement, but this is an ambiguity to clarify rather than an unavoidable conflict. The old method stays on `io.github.ppissias.jtransient.core.SourceExtractor`, the compatibility facade. The new `io.github.ppissias.jtransient.extraction.SourceExtractor` has no tracking dependency or streak-shape method. Measurements belong in `image`; the decision belongs in tracking.

**Some allowed package edges follow today's call sites rather than the data those stages need.** `TrackLinker.filterTransients` calls `MasterVetoMask.build`, so the table allows `tracking` → `reference`. The mask is a `boolean[][]` built from master stars, width, height and jitter, and `MasterVetoMask` exists so the pipeline and the preview tools share one builder. If the engine passes the built mask in, tracking does not need the reference package, and mask construction stays in `reference`. `slowmover` → `reference` is required by the convenience overload `SlowMoverAnalyzer.analyze(cleanFrames, medianStackData, config)`, which calls `MasterMapGenerator.createMaximumMasterStack`. The analyzing method already receives both stacks. Stack construction can stay with the engine. `ResidualTransientAnalyzer.analyze` already takes detections only. Its package-private `collectAlreadyClassifiedPoints` takes `TrackLinker.Track` and `TrackLinker.AnomalyDetection`, and photometry's `Input.tracks` does the same in production. Those signatures become `residual` → `tracking` and `photometry` → `tracking` as soon as the packages split, which the table forbids. The delivery order places shared model contracts at step 5, after the tracking split, so the first tracking slice needs those signatures named as a temporary exception.

> **Remark (2026-10-20):** Prepared masks and stacks are a good boundary for the main analysis methods. Removing every `tracking`/`slowmover` dependency on `reference` is optional: standalone convenience facades may legitimately compose reference services. Distinguish those facades from the analysis methods before tightening the table. Agree that model migration needs explicit temporary exceptions. The cited residual helper is currently called only by a test, so it should not alone determine a production dependency.

**An instance admission guard misses the entries that share a process.** SpacePixels builds a new `JTransientEngine` for each detection, master stack and iterative pass, and calls `shutdown()` after the synchronous call returns. Two GUI passes do not share an instance. The tuners are static and allocate their own pools; `CalibratedAutoTuner.calibrate` calls `shutdownNow()` in `finally` on every path. Heap sizing reads process-wide `Runtime` figures, and master stacking uses the common `ForkJoinPool`. One active operation per engine is a sound instance contract. The runs that can still compete are two engines, a static tune beside an engine, and two tunes sharing the static grids. JA-02 and JA-04 need the same operation context, entered by the engine and by both tuners. A process-wide single-flight lock belongs with SpacePixels D02, which owns the GUI job slot. The library contract should say what a second direct caller may do, and leave job identity in the application.

> **Remark (2026-10-20):** The cross-instance concern is valid, but only `CalibratedAutoTuner` creates its own worker pool; the legacy tuner runs synchronously on its caller thread. Both expose mutable static settings. Common operation-control contracts do not themselves impose a process-wide resource bound: that requires application admission or explicitly shared scheduling/budget resources. Keep one-operation-per-engine behavior separate from that policy, with application job identity in SpacePixels.

**Step 1 changes observable behavior if rejection or fallback identity arrives with the snapshot.** Unsuccessful legacy `tune` paths set `optimizedConfig` to the supplied `baseConfig`. Calibrated `tune` does the same until `configFor` replaces it on success. An independent fallback copy is a contract change when a caller distinguishes fallback by reference identity. Rejecting a profile the engine currently accepts is the other contract change. The blueprint already asks to reconcile fallback before new rejection rules; the acceptance test for step 1 should name which paths stay same-reference fallback, which return an equal copy, and which become structured rejection.

> **Remark (2026-10-20):** Agree that fallback aliasing and new rejection are observable changes. Proposed compatibility policy: existing entry points preserve their documented same-reference fallback behavior; a new snapshot-based API returns independent configurations consistently. Callers should use `success` to distinguish fallback. Decide and test these paths explicitly rather than changing them as an incidental consequence of cloning settings.

SpacePixels currently checks finite `double` and `float` fields by reflection and checks the six pairs. Its own comment says profiles and the API are not checked yet. `DetectionConfigurationPanel` maps `DetectionSettingsRules.Pair` to spinners and shows `problems()` and `Pair.adjustment` sentences. Structured issues should carry field and rule identifiers, and the panel should keep those sentences, so display wording stays in the application. A test that every non-static `double` and `float` field has validity metadata keeps today's reflective coverage when a field is added. Master grow `0` still satisfies the current pair inequality whenever master sigma is non-negative. Alignment loses the sentinel only by writing the effective grow, which the blueprint already leaves unchanged.

> **Remark (2026-10-20):** Agree with structured field/rule identifiers and application-owned presentation. GUI message templates must not duplicate comparisons, ranges or adjustment decisions. Shared rule/metadata coverage tests should include newly added fields and preserve sentinels. Operation-scoped enforcement must distinguish genuine global invariants from settings used only by an inactive branch.

**Cancellation is the gap a caller can hit, and SpacePixels D04 is already in progress.** The long entry points throw `Exception`. Extraction invokes the progress listener on a pool thread while holding the monitor used as `synchronized (framesCompleted)`. A listener that throws fails that worker. `CalibratedAutoTuner.calibrate` catches `Exception`, appends `e` to `failureReason`, and does not restore the interrupt status. `JTransientEngine.shutdown` calls `executor.shutdown()` and returns. The current happy path closes an idle engine after `runPipeline` returns. A caller that stops waiting, or whose thread is interrupted in `Future.get`, leaves the pool tasks running. D04 should keep saying that JTransient does not yet stop cooperatively, until JA-03 names the checkpoints. The listener rules in JA-09 belong in that same contract.

> **Remark (2026-10-20):** The risk is real, but the waiting point needs correction: engine extraction calls `invokeAll` before reading the returned futures, so the later `Future.get` is not normally waiting for running extraction tasks. Interrupted `invokeAll` cancels unfinished tasks; cancellation still does not guarantee that computations ignoring interruption have stopped. See the [ExecutorService contract](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/ExecutorService.html). D04 should say that cancellation does not yet guarantee JTransient computation has stopped, and retain resource ownership until execution actually ends. Avoid saying workers always remain running after a stop request.

**Prepared-frame identity matches the code, with one ordering detail worth recording.** `extractSourcesFromFrames` sorts the caller's list after drift analysis and before creating extraction tasks. `generateMasterStackWithDetails` sorts before drift analysis. `FrameDriftAnalyzer.analyze` sorts a copy, and registrations are kept by sequence index, so both orders zip metrics back to frames by sequence index today. `VariableStarAnalyzer.analyze` reads `input.frameMetrics.get(j)` in lockstep with `input.frames` and does not check the list lengths; the matching-order rule is that indexing plus the field comments. `TrackLinker` records veto `frameIndex = i` in the retained-list position. The engine's `FrameExtractionResult.frameIndex` stores `sequenceIndex`. The two names in JA-05 match that split. `ImageFrame`'s class comment calls the payload immutable; the fields are final, and `pixelData` remains a caller-owned mutable matrix, as JA-07 says. The class comment should be brought into line with that contract when the boundary is written.

> **Remark (2026-10-20):** Agree. Preserve original sequence identity separately from retained position, and verify associations after sorting, rejection and subsetting. The `ImageFrame` comment should describe borrowed mutable pixel storage rather than imply deep immutability when that contract is implemented.

**The blueprint can be built one slice at a time.** The class table is a vocabulary. The delivery table is a dependency order. Each JA item still needs its own discussion, and a long analyzer stays whole until it has a new ownership boundary. `DetectionConfigRules` becomes supported API as soon as SpacePixels imports it, so the JA-11 note for that change needs the Java baseline, what is public, and source compatibility. The build declares no toolchain or release target, and the README asks for a local JDK without a minimum version. The flowchart is an ownership sketch; the package table is the rule. Both tuners stop at extraction, quality, drift and master construction. They do not call `TrackLinker` or `runPipeline`. That cut matches the code and matches AT-4, whose budgets count single-frame survivors.

> **Remark (2026-10-20):** Agree with incremental implementation and compatibility checks for the shared rules API. The proposed class names are design candidates, not a requirement to instantiate every listed helper. Preserve standalone services and introduce a collaborator when its responsibility and ownership are clear.

**Two Proposed algorithm items sit only in the deferred table.** PT-5 and SM-F1 are Proposed. The JA summary is the table a reader scans first. One line under Status and scope, listing the non-JA Proposed ids, would make that scan complete. The deferred and declined scientific wording was not re-checked against the datasets for this review. It already limits those rows to source concerns and named examples, and it points measurement at the SpacePixels regression method.

> **Remark (2026-10-20):** A navigation note or link is reasonable; keep PT-5 and SM-F1 in their existing scientific work list without changing their status or making them prerequisites for architectural refactoring.

### Questions

1. For the streak predicate, does the compatibility facade own the old public method, with measurements in `image` and the decision in tracking?

   **Remark:** Yes: retain the old public method on `core.SourceExtractor`; the canonical extractor stays independent of tracking.

2. Should tracking receive a built mask, and slow movers receive built stacks, so those packages do not depend on `reference`?

   **Remark:** Prefer prepared inputs for the main analysis path; retain justified reference-service dependencies on standalone convenience facades.

3. Is the first execution contract one operation per engine instance, with static tuners and parallel stacking entering the same operation context, while a process-wide GUI slot stays in D02?

   **Remark:** Yes to the instance contract and common operation-control conventions. Process-wide resource limits need actual admission or shared execution resources, beyond a context object.

4. Which JA-01 paths keep the same `baseConfig` instance, which return an equal copy, and which reject with structured issues?

   **Remark:** Proposed: existing compatibility entry points retain fallback aliases; new snapshot-based APIs return independent copies. Preserve accepted-profile behavior until the validation/rejection policy is explicitly agreed.

5. Should alignment results carry rule identifiers, with the settings tab keeping the current sentences?

   **Remark:** Yes. Return field/rule identifiers and structured adjustments; SpacePixels formats the messages while JTransient defines the rules.

6. Should validation be scoped as `validate(config, operation)`, so master-stack, transient-only and full-pipeline entry points check the settings that operation reads?

   **Remark:** Yes, covering shared preparation and enabled branches. Inactive-stage settings should not block unrelated operations unless they violate a genuine global invariant.

7. Until JA-03, should D04 state that stopping a detection job leaves JTransient workers running until the library checkpoints exist?

   **Remark:** Use qualified wording: cancellation does not guarantee that JTransient computation has stopped. Retain resources until actual termination rather than assuming every stop request leaves workers running.

### Review notes from the SpacePixels side (Claude, 2026-10-20)

These notes add measurements made while implementing SpacePixels D04 (Cancel). They change no status here. Measured on klangwolke-2 (33 frames of 61 MPix, the maintainer's profile) through SpacePixels' job coordinator. A cancel there is a `JobCancelledException`, a `java.util.concurrent.CancellationException`, thrown by the progress listener at its next call.

**Evidence for JA-03 and JA-09.**
- **A listener exception does not stop sibling tasks.** Cancelled at "Extracting features from frame 5 of 33", the run ended 26 s later. Each extraction task does its frame's work before it reports, and `invokeAll` waits for every task, so the other frames are still extracted before `Future.get` rethrows. The remark under question 7 is right that an *interrupted* `invokeAll` cancels unfinished tasks. But SpacePixels' cancel is a listener exception in a worker, not an interrupt, so that path does not apply. A checkpoint at the start of each frame task would bound the wait to about one frame.
- **Longest gaps between progress calls,** which bound a listener-driven cancel today:
  - photometry's ensemble solution: 5–29 s;
  - dither and corner-drift analysis: 2–14 s (14 s on a cold file cache);
  - master star map to the maximum stack: about 6 s;
  - calibrated tuning, preparing noise and injection images: 12.6 s (MEDIUM profile).

  Everything else stayed under about 5 s.
- **The calibrated tuner turns a cancel into a failed calibration.** All its progress calls run on the calling thread inside the `try` that catches `Exception`. A listener exception therefore comes back as `success = false`, with the exception in `failureReason`. SpacePixels now reports a tune as cancelled whenever a cancel was requested before the tuner returned. JA-03 should let a cancel leave the tuner as a cancel.
- **A suggestion for the contract's type:** use `java.util.concurrent.CancellationException`, or a subclass, as the cancellation signal across the library. SpacePixels already recognises it through wrapping exceptions without depending on application classes. A JDK type keeps the JA-03 contract usable by other callers and needs no new public exception class.
- **A listener that throws inside `synchronized (framesCompleted)`** releases the monitor normally. A slow listener serialises the workers there, as JA-09 notes. SpacePixels' listener only posts to the Swing thread, so it is cheap.

**On JA-01 rules ownership.** Agree with the direction and with the remarks on questions 5 and 6.
- **What SpacePixels uses today.** The six pairs drive three places:
  - the settings tab's auto-apply (`normalizeDependentSpinners`), which moves one spinner of a crossed pair and words the change in the footer;
  - `checkedSettings`, which refuses a save or run;
  - since 2026-10-20, Preview Frame, whose threshold spinners share the tab's models, so its edits go through the same alignment.
- **What a shared service must return** to replace this without changing behaviour:
  - the direction of each adjustment (which field moves);
  - a stable rule identifier, to which SpacePixels attaches its own titles and sentences;
  - the field identifiers, so the tab can focus the field.

**On JA-11 and releases.** SpacePixels builds against the published JTransient from Maven Central by default; the composite build is opt-in. A cancellation or rules API is usable in a SpacePixels release only after a JTransient release that contains it. The JA-03 and JA-01 slices should each name the JTransient version they need, and SpacePixels should keep working, with today's behaviour, against the version before.

**On the document itself.** The blueprint is thorough, and its slices are the right size. With the review's questions now answered, consider moving this Review section to `docs/archive/` once its answers are folded into the JA items, as was done for the October reviews. This list then stays the single current plan.


## Verification for future changes

1. Define the observable failure and the supported input contract. Use a deterministic component reproduction and an unchanged control where possible.
2. Discuss the proposed rule and acceptance condition before changing an algorithm. Retain the decision/status here.
3. Run engine tests and the consuming SpacePixels tests with the local composite build. Compare base/change on the same reference profiles and datasets using the SpacePixels regression procedure; compare frame rejection, tracks/anomalies, camera/common-motion groups, variable-star results star by star, and timings.
4. Report differences before committing. Preserve user profiles/preferences, use scratch diagnostic code, and keep memory-heavy runs sequential.
5. Update the relevant current reference after implementation, mark the item with evidence here, and archive lengthy experiment narratives rather than growing `AGENTS.md` into a session log.
