# Design improvements and review decisions

Consolidated and checked against the source tree on 2026-10-19, at engine commit `f084ab9`. This is the single active decision list for engine improvements and review limitations. It holds only **pending work and open decisions**; completed, retired and declined items, and the answered review of this plan, are in the [closed items archive](docs/archive/design-improvements-closed-2026-10.md). Current implementation is described in [ALGORITHM.md](ALGORITHM.md) (detection, slow movers, photometry and tuning), [PIPELINE.md](PIPELINE.md) (API contracts), and [CONFIG.md](CONFIG.md) (fields/defaults).

Original evidence and maintainer comments are preserved in the [October engine review](docs/archive/code-review-2026-10.md), [variable-star assessment](docs/archive/variable-star-assessment-2026-10.md), [slow-mover design](docs/archive/slow-mover-design-2026-10.md), and [maintenance/regression notes](docs/archive/maintenance-notes-2026-10-19.md). Original line numbers, suggested orders and statements about missing features describe those earlier checkouts.

The separate [design and architecture review](#design-and-architecture-review-2026-10-20) below was added on 2026-10-20. Its `JA-` findings concern library structure and engineering contracts; the existing algorithm findings and decisions retain their status. The [structural blueprint](#structural-blueprint-packages-classes-and-dependencies) makes the proposed package boundaries, class separations and dependency rules concrete.

## Status and scope

**Proposed** is an idea without an implementation decision; **Accepted** requires an explicit decision; **In progress** means implementation has started; **Deferred** is postponed work; **Declined** preserves a decision to keep current behavior; **Implemented** records a completed change; **Retired** means an old finding no longer applies. A source concern or synthetic reproduction is not a measured real-data incidence. This documentation cleanup changes no algorithm or review authorization.

Existing issue IDs are retained. Variable-star assessment numbers use a `VS-` prefix here to avoid ambiguity. No deferred issue becomes Accepted by being listed in this plan. Discuss a concrete engine fix, implement one at a time, and report real-data differences under [AGENTS.md](AGENTS.md).

- **When an item is done, retired or declined,** describe current behaviour in the reference documents and move its record (decision and evidence) to the archive. The [decisions to keep current behavior](docs/archive/design-improvements-closed-2026-10.md#decisions-to-keep-current-behavior) stay binding: do not re-propose a declined item without new evidence.
- **After each feature implementation,** commit its implementation, tests and documentation, then push to the current branch's configured upstream. Add commit-body notes explaining the change, rationale and validation or remaining limitations; report the commit ID and any push failure. Preserve ignored local configuration and do not force-push.
- **Besides the JA items,** two Proposed algorithm items are listed under [deferred work](#deferred-work-and-optional-ideas): PT-5 and SM-F1.

## Design and architecture review (2026-10-20)

### What to preserve

- **The library/application boundary and simple entry points.** Keep `short[][]` processing usable without SpacePixels, FITS readers, Swing, application settings or report services. Keep standalone `SourceExtractor`, both tuners and the engine facade available during migration.
- **Existing scientific boundaries.** `SkyBackground`, coverage/registration/data-area types, `MasterReferenceAnalyzer`, the camera/common-motion rejectors and the slow-mover analyzers already isolate useful responsibilities. Photometry has separate selection, measurement, readiness, ensemble and scoring helpers. Static methods are appropriate for stateless computations.
- **Stage-local configuration copies.** Master-reference, quality, slow-mover and photometry extraction already clone settings before applying local overrides. Preserve that practice while improving the outer run boundary.
- **Shared preparation and measured diagnostics.** Full detection and transient-only export share `extractSourcesFromFrames`; master generation shares the rejection policy. Stage timings use a monotonic clock, and photometry distinguishes readiness outcomes. Build on these instead of adding a second pipeline.
- **Memory-conscious sharing and identity.** Image/mask aliases avoid large duplicate allocations. Detections intentionally appear in several review collections, and classification uses object identity. Preserve those relationships until an explicit replacement contract is agreed.
- **The regression discipline.** Existing focused tests and the SpacePixels reference-data comparison are essential safeguards when moving scientific code without changing its behavior.

### Findings and priorities

Statuses distinguish proposals from work already started. High priority means to resolve the contract before expanding the affected use case; it does not authorize implementation or claim an observed production failure.

| ID | Priority | Status | Design improvement | First useful result |
|---|---|---|---|---|
| JA-03 | High | In progress; structured failure context remains | Give cancellation and failures an explicit contract | Cancellation stays distinct from fallback and failure; completion means the run's work has ended. |
| JA-04 | High | In progress; tuner options complete, core diagnostic propagation remains | Replace mutable global tuning options with operation options | Two tunings can use independent options without changing each other's settings. |
| JA-05 | Medium | Accepted (2026-10-22) | Make prepared-frame context and identity coherent | Frames, metrics, detections and registrations remain associated through QC and subset runs. |
| JA-06 | Medium | Accepted (2026-10-22) | Separate tracking responsibilities while retaining its facade | Vetoing, point linking, streak assembly and classification have explicit inputs and ownership. |
| JA-07 | Medium | Accepted (2026-10-22) | Stabilize public results and dependency direction | Public models and shared buffers have clear contracts independent of implementation helpers. |
| JA-08 | Medium | Accepted (2026-10-22) | Separate tuning measurement, selection and presentation | Measurements can explain a choice without depending on report construction or ambiguous legacy fields. |
| JA-09 | Medium | Accepted (2026-10-22) | Unify progress and stage outcome reporting | Callers can distinguish disabled, unavailable and completed-empty stages without parsing prose. |
| JA-10 | Medium; before expanding reuse | Accepted (2026-10-22) | Carry provenance with reusable master references | Callers can identify a reference's origin and deliberately reuse it across compatible runs. |
| JA-11 | Medium | Accepted (2026-10-22) | Declare and verify the supported library contract | Releases have a known Java target and a tested SpacePixels/JTransient dependency pair. |

### JA-01. Capture and validate configuration at an operation boundary

**Implemented (2026-10-22).** Configuration snapshots, shared rules/metadata and the coordinated SpacePixels rule-review integration are complete. Decisions, original acceptance criteria and validation are in the [JA-01 completion record](docs/archive/design-improvements-closed-2026-10.md#ja-01-configuration-snapshots-and-shared-rules-2026-10-22). Current contracts are in [PIPELINE.md](PIPELINE.md#configuration-snapshots-and-shared-rules) and [CONFIG.md](CONFIG.md#shared-rules-and-compatibility). Typed application bindings remain SpacePixels D10; global tuner options and published-pair certification remain JA-04/JA-11.

### JA-02. Define execution and resource ownership for a whole operation

**Implemented (2026-10-22).** `ExecutionPolicy` and owned/borrowed `ExecutionSession` apply the existing scratch heuristic to engine/tuner batches, including stack rows and photometry. Nested managed batches execute inline; engines still reject overlap/active close and join owned resources. Independent sessions make no process-wide resource promise. See the [completion and real-data record](docs/archive/design-improvements-closed-2026-10.md#ja-02-shared-execution-policy-and-resource-ownership-2026-10-22) and [current API contract](PIPELINE.md#execution-policy-and-resource-ownership-106).

### JA-03. Distinguish cancellation, failure and tuning fallback

**In progress: structured failure context remains.** The per-operation cancellation contract, interruption preservation, cooperative checkpoints, stop-and-join worker batches and direct propagation from calibrated tuning are implemented. The decision, tests and real-data evidence are [archived](docs/archive/design-improvements-closed-2026-10.md#ja-02ja-03-operation-cancellation-and-worker-lifetime-2026-10-21); the current contract is in [PIPELINE.md](PIPELINE.md#cancellation-and-worker-lifetime). SpacePixels already consumes this through the local composite build. Published-release validation remains JA-11.

**Open.** Complete consistent stage/frame context for runtime failures across orchestration, photometry and both tuners without losing their original cause. Calibrated tuning now exposes its cause as the runtime-only `Calibration.failureCause`; scientific readiness/insufficient-evidence outcomes retain their existing result shape. Other operations still expose broad exception contracts. Coordinate any structured stage model with JA-09, preserving existing entry points and saved tuning JSON.

**Acceptance.** Inject failures at extraction, stacking, tracking, tuning and photometry boundaries, including listeners. Each failure retains its original cause and identifies its operation/stage and frame where applicable. Keep invalid input, scientific readiness, cancellation and execution failure distinguishable; do not change detection verdicts merely to introduce a diagnostic model.

### JA-04. Make tuning options and diagnostics belong to an operation

**Implemented slice (2026-10-22).** Both tuner facades now capture immutable `Options` with defensive grid/budget copies and retain the effective policy with results. Explicit-options overloads allow independent concurrent operations. SpacePixels uses the 1.0.6 independent available-sample entry point when present and retains the 1.0.5 adapter. Legacy diagnostics have a per-run sink; propagation through non-tuner science stages remains for the execution/diagnostic-context seam. See the [validation record](docs/archive/design-improvements-closed-2026-10.md#ja-04-tuner-options-slice-2026-10-22). These nested public facade options deliberately stabilize the contract before moving canonical tuner implementations to the blueprint's `tuning` package.

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

**Decided in the review of this plan (2026-10-20).** When the `ImageFrame` contract is written, its class comment should describe borrowed, mutable pixel storage rather than imply deep immutability.

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

**Releases and SpacePixels.** SpacePixels builds against the published jar by default; the composite build is opt-in. A slice that adds API SpacePixels will use, such as the rules service (JA-01) or the cancellation contract (JA-03):
- names the JTransient version that brings it;
- keeps SpacePixels working, with today's behaviour, against the version before.

`DetectionConfigRules` becomes supported API as soon as SpacePixels imports it.

**Acceptance.** Compile, test and build the jar on the declared Java target; compile representative existing API consumers and run SpacePixels tests with the intended engine. For structural engine changes, complete the reference-data comparison below. Do not add brittle tests for class length or private method names, or mirror every implementation branch merely to increase coverage.

### Structural blueprint: packages, classes and dependencies

**Proposed structural design, added 2026-10-20.** This elaborates JA-01–11 rather than creating another work queue or authorizing their implementation. The maintainer agreed the JA-01 rules-ownership direction separately and authorized the remaining proposed JTransient work on 2026-10-22; deferred/declined algorithm decisions remain unchanged. Names below specify a reviewable target; an individual change can refine a name with its rationale recorded here. Keep one Gradle project and published jar. Introduce a package when its boundary is implemented, without creating empty directories or moving every public class in one release.

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
| Profile values and GUI pair alignment | Implemented `config.DetectionConfigRules` exposes `FieldMetadata`, `Issue`, `Alignment` and `Adjustment`; field IDs are the existing DTO names, with typed defaults/domains. Typed application bindings remain D10. | One rule definition, as completed in JA-01. Keep `DetectionConfig` serialization unchanged and UI widget behavior outside the library. |
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

#### Decided in the review of this plan (2026-10-20)

- **The streak-shape predicate.** The old public method stays on `core.SourceExtractor`, the compatibility facade. The canonical `extraction.SourceExtractor` has no streak-shape method and no tracking dependency. Measurements live in `image.FootprintGeometry`, and the decision lives in tracking.
- **Prepared inputs.** The main analysis methods take prepared inputs: tracking receives a built mask, and slow movers receive built stacks. Standalone convenience facades may still compose reference services; tell them apart from the analysis methods before tightening the dependency table.
- **Temporary exceptions.** Model migration names its temporary dependency exceptions explicitly, for example `residual` and `photometry` naming `TrackLinker` types until the shared models move. The residual helper that takes `TrackLinker.Track` is called only by a test, so it alone does not decide a production dependency.
- **Names are candidates.** The class names are design candidates, not a requirement to create every listed helper. Introduce a collaborator when its responsibility and ownership are clear.

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
| 1 | JA-01 rules/configuration capture completed; next JA-04 per-operation tuner options | Build on the shared rules authority and captured settings; remove the remaining reason for process-wide tuning coordination. |
| 2 | JA-02 execution ownership, then JA-03 cancellation/failure; JA-09 adapters alongside them | Know who owns workers and when they stop before the application relies on cancellation or admits concurrent work. |
| 3 | JA-05 prepared-frame associations and shared selection orchestration | Stabilize identity and stage inputs before moving tracker internals. |
| 4 | JA-06 one tracking responsibility at a time | Use membership-level controls to preserve the established classification/assignment order. |
| 5 | JA-07 model/result improvements and JA-08 tuning separation where needed | Migrate public contracts gradually; preserve large-buffer sharing, legacy callers and tuner objectives. |
| 6 | JA-10 reusable-reference provenance and measured reuse extensions | Expand caching only after inputs, ownership and invalidation are defined. |
| Alongside each step | JA-11 build, consumer compatibility and focused contract tests | Establish the Java baseline early; verify every adopted boundary without one large refactor release. |

### Verification and coordination with SpacePixels

Each item needs a concrete design discussion before implementation under [AGENTS.md](AGENTS.md). Agree whether it is strictly structural or intentionally changes an API behavior, such as rejecting an overlapping run, returning a copied fallback config, freezing a collection or rejecting a formerly accepted profile. Preserve the distinction in its acceptance criteria and release notes.

For each engine change, run `./gradlew compileJava`, `./gradlew test` and `./gradlew jar` as appropriate, then the SpacePixels tests with `useLocalJTransientBuild=true` and verify the actual resolved dependency pair. Use the same base/change profiles and reference datasets under the sibling [regression procedure](../SpacePixels/AGENTS.md#real-data-regression-method), sequentially for memory-heavy data. A structural refactor should preserve frame rejection, category membership and ordering where contractual, masks/stacks, candidate diagnostics and variable-star results, allowing only agreed timing differences. Investigate any scientific difference before calling the refactor equivalent. Report timing and memory evidence when changing execution or retention.

Coordinate JA-02/03/09 with SpacePixels D02/D04 (job ownership and outcomes), JA-01/04/07/11 with D10 (settings rules and engine integration), and JA-05/10 with iterative detection and session-aware inspection. SpacePixels lists the change each item needs on its side, and the order, in [engine changes that need SpacePixels work](../SpacePixels/DESIGN-IMPROVEMENTS.md#engine-changes-that-need-spacepixels-work). The application owns jobs, file access, settings persistence and report artifacts; the library owns computational run contracts. Neither review authorizes moving application services into JTransient or implementing the future MCP interface here.

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

## Verification for future changes

1. Define the observable failure and the supported input contract. Use a deterministic component reproduction and an unchanged control where possible.
2. Discuss the proposed rule and acceptance condition before changing an algorithm. Retain the decision/status here.
3. Run engine tests and the consuming SpacePixels tests with the local composite build. Compare base/change on the same reference profiles and datasets using the SpacePixels regression procedure; compare frame rejection, tracks/anomalies, camera/common-motion groups, variable-star results star by star, and timings.
4. Report differences before committing. Preserve user profiles/preferences, use scratch diagnostic code, and keep memory-heavy runs sequential.
5. Update the relevant current reference after implementation, then move the item with its evidence to the [closed items archive](docs/archive/design-improvements-closed-2026-10.md). Archive lengthy experiment narratives too, rather than growing `AGENTS.md` into a session log.
