# Design improvements and review decisions

Consolidated and checked against the source tree on 2026-10-19, at engine commit `f084ab9`. This is the single active decision list for engine improvements and review limitations. It holds only **pending work and open decisions**; completed, retired and declined items, and the answered review of this plan, are in the [closed items archive](docs/archive/design-improvements-closed-2026-10.md). Current implementation is described in [ALGORITHM.md](ALGORITHM.md) (detection, slow movers, photometry and tuning), [PIPELINE.md](PIPELINE.md) (API contracts), and [CONFIG.md](CONFIG.md) (fields/defaults).

Original evidence and maintainer comments are preserved in the [October engine review](docs/archive/code-review-2026-10.md), [variable-star assessment](docs/archive/variable-star-assessment-2026-10.md), [slow-mover design](docs/archive/slow-mover-design-2026-10.md), and [maintenance/regression notes](docs/archive/maintenance-notes-2026-10-19.md). Original line numbers, suggested orders and statements about missing features describe those earlier checkouts.

The separate [design and architecture review](#design-and-architecture-review-2026-10-20) below was added on 2026-10-20. Its `JA-` findings concern library structure and engineering contracts; the existing algorithm findings and decisions retain their status. The [structural blueprint](#structural-blueprint-packages-classes-and-dependencies) makes the proposed package boundaries, class separations and dependency rules concrete.

## Coordinated development and release policy (2026-10-23)

SpacePixels, JTransient and JPlateSolve evolve together through the local sibling builds. Library API/package/constructor changes can be made directly with coordinated caller updates; preserving old library source/binary APIs or making one SpacePixels binary support multiple historical engine versions is **not required**. At release time, choose and pin one exact library-version combination and validate its packaged artifacts with composites disabled. See [SpacePixels development policy](../SpacePixels/DEVELOPER.md#coordinated-library-and-release-policy).

This decision supersedes earlier compatibility-preservation requirements in this plan and its historical archive. It does not authorize scientific algorithm changes, user-data loss or silently changing persisted profile/session formats. Completed work is reviewed, including compatibility-only parts to simplify, in the [questions and implementation review](#maintainer-questions-and-release-model-clarification-2026-10-23) at the end of this file. The source code still contains those adapters until the coordinated cleanup is implemented.

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
| JA-06 | Medium | Accepted (2026-10-22) | Separate tracking responsibilities while retaining its facade | Vetoing, point linking, streak assembly and classification have explicit inputs and ownership. |
| JA-07 | Medium | Accepted (2026-10-22) | Stabilize public results and dependency direction | Public models and shared buffers have clear contracts independent of implementation helpers. |
| JA-08 | Medium | Accepted (2026-10-22) | Separate tuning measurement, selection and presentation | Measurements can explain a choice without depending on report construction or ambiguous legacy fields. |
| JA-09 | Medium | In progress; terminal operation outcomes implemented, stage progress/outcomes remain | Unify progress and stage outcome reporting | Callers can distinguish disabled, unavailable and completed-empty stages without parsing prose. |
| JA-10 | Medium; before expanding reuse | Accepted (2026-10-22) | Carry provenance with reusable master references | Callers can identify a reference's origin and deliberately reuse it across compatible runs. |
| JA-11 | Medium | In progress; retain Java/architecture/artifact gates, replace historical-version compatibility gates | Verify the chosen coordinated release contract | One pinned SpacePixels/JTransient/JPlateSolve release combination is tested without source substitution. |

### JA-01. Capture and validate configuration at an operation boundary

**Implemented (2026-10-22).** Configuration snapshots, shared rules/metadata and the coordinated SpacePixels rule-review integration are complete. Decisions, original acceptance criteria and validation are in the [JA-01 completion record](docs/archive/design-improvements-closed-2026-10.md#ja-01-configuration-snapshots-and-shared-rules-2026-10-22). Current contracts are in [PIPELINE.md](PIPELINE.md#configuration-snapshots-and-shared-rules) and [CONFIG.md](CONFIG.md#shared-rules-and-compatibility). Typed application bindings remain SpacePixels D10; tuner options are now implemented under JA-04, and selected-release artifact validation remains JA-11.

### JA-02. Define execution and resource ownership for a whole operation

**Implemented (2026-10-22).** `ExecutionPolicy` and owned/borrowed `ExecutionSession` apply the existing scratch heuristic to engine/tuner batches, including stack rows and photometry. Nested managed batches execute inline; engines still reject overlap/active close and join owned resources. Independent sessions make no process-wide resource promise. See the [completion and real-data record](docs/archive/design-improvements-closed-2026-10.md#ja-02-shared-execution-policy-and-resource-ownership-2026-10-22) and [current API contract](PIPELINE.md#execution-policy-and-resource-ownership-106).

### JA-03. Distinguish cancellation, failure and tuning fallback

**Implemented (2026-10-22).** Additive `OperationOutcome` APIs and scoped `OperationContext` retain stable stage/original-frame context and the original cause, without changing legacy thrown exceptions or calibrated `failureCause` identity. Completed empty/readiness/fallback results, execution failures and joined cooperative cancellation remain distinct. See the [completion record](docs/archive/design-improvements-closed-2026-10.md#ja-03-structured-failure-outcomes-with-legacy-exceptions-preserved-2026-10-22) and [current outcome contract](PIPELINE.md#structured-operation-outcomes-106). Detailed stage progress/outcomes remain JA-09; public artifact availability remains JA-11.

### JA-04. Make tuning options and diagnostics belong to an operation

**Implemented (2026-10-23).** Immutable tuner options and per-run legacy tuning diagnostics are now joined by scoped core `DiagnosticOptions`/`DiagnosticContext`. Engine/tuner boundaries capture defaults once; worker batches inherit explicit sinks and restore prior policy. Lower science code no longer depends on the engine facade for logging. Legacy debug flags remain compatibility adapters, and default use remains silent. See the [completion and original acceptance record](docs/archive/design-improvements-closed-2026-10.md#ja-04-operation-owned-core-diagnostics-2026-10-23) and [current diagnostic contract](PIPELINE.md#per-operation-core-diagnostics-106).

### JA-05. Represent prepared frames and their identities together

**Implemented (2026-10-23).** Prepared-frame associations now carry quality, extraction and registration through selection. Additive copy-sorting engine entry points, validated photometry input snapshots and explicit original-frame labels for retained-position tracker telemetry complete the identity boundary, preserving legacy entry-point behavior and shared pixels. See the [completion and original acceptance record](docs/archive/design-improvements-closed-2026-10.md#ja-05-frame-identity-and-public-alignment-boundaries-2026-10-23) and [current ownership/API contract](PIPELINE.md#prepared-frame-associations-106).

### JA-06. Separate tracking stages and make detection assignment explicit

**Evidence.** [TrackLinker.filterTransients](src/main/java/io/github/ppissias/jtransient/core/TrackLinker.java) builds the master veto, separates populations and links streaks. `findMovingObjects` adds camera-fixed removal, timed and geometric point linking, conflict resolution, anomaly rescue, streak consolidation and export construction. Private same-frame anomaly groups use the same `Track` type with a private flag. [FaintStreakFragmentGrouper](src/main/java/io/github/ppissias/jtransient/core/FaintStreakFragmentGrouper.java) is already separate, but mutates track membership. Camera-fixed removal updates both the point population and the full transient export; common-motion removal later changes the unclassified population.

**Risk and proposal.** The issue is several ownership rules in one orchestration path, not its line count. Extract cohesive internal collaborators in small steps: veto/population preparation, streak assembly/consolidation, timed point proposals, geometric point proposals, assignment/rescue, and export assembly. Move or simplify `TrackLinker` and its nested models with coordinated SpacePixels caller updates; retaining old facades is not required. Keep temporary existing model types only where that reduces refactor risk, not as a permanent compatibility obligation. Use a private anomaly-group input type when that seam is extracted, preserving the absence of a public suspected-streak category.

Make the current assignment sequence explicit: a detection may be visible in several review views, but accepted classification and removals must follow the existing rules. Preserve reference identity, tie order, greedy behavior, empty-frame slots and stage ordering. Do not add value-based equality or new association gates during extraction. This proposal does not reopen PT-6 or the declined streak/linker decisions.

**Acceptance.** Compare stage populations and final membership, not only counts: tracks, anomalies, camera/common-motion groups, all transients and leftovers. Use the existing streak consolidation, rescue, camera-fixed and export tests, plus deterministic ambiguous-assignment controls. Real-data outputs must remain equivalent for a structural refactor.

### JA-07. Give public models, mutable work state and shared buffers clear boundaries

**Evidence.** [PipelineResult](src/main/java/io/github/ppissias/jtransient/engine/PipelineResult.java) has a growing set of positional constructors and directly retains most lists and arrays. Its older constructors permit legacy fields without corresponding grouped slow-mover data. [ImageFrame](src/main/java/io/github/ppissias/jtransient/engine/ImageFrame.java) has final fields but exposes a caller-owned mutable pixel matrix. `SourceExtractor.DetectedObject`, `TrackLinker.Track`, telemetry and `StarLightCurve` remain mutable. Some analysis types copy list containers, but their elements remain shared; `VariableStarAnalysis.frames` is an unmodifiable view of a mutable telemetry list.

Dependencies also follow implementation locations: public results use `SourceExtractor.DetectedObject` and `TrackLinker.Track`; photometry frame records live inside `PipelineTelemetry`; common-motion groups live inside `ResidualTransientAnalysis` although common-motion filtering can run independently. Lower layers still use engine-package input types/callbacks; JA-04 has removed the facade debug dependency. These are source dependencies, not evidence that the stages calculate the wrong results.

**Proposal.** State the ownership contract first: which inputs are borrowed, when callers may mutate them, which output containers are stable, and which large arrays are shared read-only by agreement. Use internal builders for assembly, validate grouped-result invariants, and freeze small collection structure under an explicit ownership contract, updating callers together. Keep expensive buffers shared; do not claim deep immutability or break aliases. Separate mutable work state from finished result views gradually.

As a touched boundary needs it, put shared frame/detection/track and diagnostic contracts below orchestration, updating consumers of current nested public types in the same coordinated change. Keep domain measurements independent of their text formatter and aggregate telemetry attachment. Public classes/constructors/collection contracts may change when their consumers are migrated together; verify ownership and scientific identity rather than retaining old signatures solely for backward compatibility. Add an explicit opt-in output-retention policy only after identifying consumers: the engine currently builds the maximum stack even with slow movers disabled because it also exports that image. Any lighter mode must still compute products needed by enabled science stages and preserve today's default outputs.

**Decided in the review of this plan (2026-10-20).** When the `ImageFrame` contract is written, its class comment should describe borrowed, mutable pixel storage rather than imply deep immutability.

**Acceptance.** Test intended aliases, container ownership, repeat viewers reading one result, grouped/legacy consistency and preservation of detection identity. Compile existing SpacePixels consumers. Measure retained heap and runtime before adding copies or changing output retention; a read-only wrapper alone is not deep immutability.

### JA-08. Separate Auto-Tune measurement, policy and presentation

**Evidence.** Both tuners contain crop preparation, extraction, evaluation, selection and report construction. The calibrated tuner already offers the useful `calibrate`/`configFor` split, but `Calibration` combines a mutable sweep, profile-ordinal arrays, chosen settings and compact tradeoff data. `tune` discards the full sweep through `discardSweep`, whereas direct `calibrate` retains it. Its public return type and profile enum belong to the legacy `JTransientAutoTuner`. The shared result's `bestTransientRatio` is a legacy ratio for one tuner and a per-megapixel-frame noise rate for the calibrated tuner.

**Risk and proposal.** Explanations, memory retention and selection policy are coupled, and a caller can misinterpret a shared field's units. Keep the algorithms distinct. Extract immutable measurement/selection results with explicitly named units and profile-keyed choices, plus formatters that consume those results. Reuse crop/mask/extraction mechanics only where their semantics really match; the legacy and calibrated objectives need not be forced into a single strategy abstraction.

Retain measurement provenance: effective base settings, tuning options, sample/crop identity and random seed where used. Make full-sweep retention versus compact summary an explicit option and update the application caller explicitly. A selected measurement is valid only for its documented assumptions; applying it to another base config needs an explicit rule. Replace ambiguous result/profile types and migrate SpacePixels directly; old result/profile adapters are not required. Keep the distinct legacy tuning algorithm unless a separate algorithm-removal decision is made.

**Acceptance.** Replay selection and report generation from a frozen measurement without rereading images. Verify profile ordering, budgets, tradeoff choices and selected settings against controls; verify summary/full-sweep retention behavior and field units. Default legacy/calibrated results must remain unchanged. This is not a proposal to alter false-positive calibration or its deferred limitations.

### JA-09. Make progress, stage outcomes and diagnostics consistent

**Evidence.** [TransientEngineProgressListener](src/main/java/io/github/ppissias/jtransient/engine/TransientEngineProgressListener.java) documents worker-thread callbacks and repeated percentages. Engine, tracker and photometry manually map percentages into ranges. Some callbacks run while holding a progress counter monitor. [PipelineTelemetry](src/main/java/io/github/ppissias/jtransient/telemetry/PipelineTelemetry.java) records completed-stage timings and also formats a text report; slow-mover telemetry has a separate conversion to legacy counters. Empty branch results and absent timings do not uniformly distinguish disabled work from unavailable input. Photometry's readiness/check enums already show a better pattern.

**Proposal.** Use a small internal stage event carrying stage identity, completed/total work where known, and outcome; adapt it to today's percentage/message callback. Specify callback threading, ordering, exception handling and whether slow listeners can block workers. Do not introduce a GUI dispatcher or event bus into the library. Keep stage outcomes such as disabled, insufficient input, completed-empty, completed-with-findings, failed and cancelled separate from scientific confidence/readiness.

Keep numerical diagnostics in structured results; text formatting should consume them without changing their state. Preserve non-overlapping timing semantics, distinguish substeps from major stages and include the effective run settings/options or a stable reference to them. Progress describes execution and cannot serve as the completion/failure signal. This complements JA-03's terminal contract and SpacePixels D04 without duplicating application job IDs or report services.

**Acceptance.** Exercise each optional branch on/off, insufficient evidence, zero findings, task failure, cancellation and a throwing/slow listener. Verify stage order, callback guarantees, terminal outcomes and timing inclusion. Migrate text/percentage consumers together with the new stage contract; retaining a second old callback contract is optional, not a release requirement.

### JA-10. Carry reference provenance without introducing unsafe caching

**Evidence.** `generateMasterStackWithDetails` returns pixels, kept indices and rejection records, but the pipeline reuse overload accepts only a `short[][]`. `InputRules` checks dimensions. The full and transient-only paths still repeat extraction, QC and master-star preparation when a stack is supplied, as [PIPELINE.md](PIPELINE.md#what-changes-when-you-pass-providedmasterstack) documents. SpacePixels [IterativeDetectionPipelineService](../SpacePixels/src/main/java/eu/startales/spacepixels/util/IterativeDetectionPipelineService.java) intentionally builds a full-session reference and supplies it to subset runs.

**Risk and proposal.** Matching dimensions do not identify a reference's dataset, coordinate system, encoding or frame-selection origin. Add an optional prepared-reference value carrying those facts and the settings relevant to its construction. Keep raw-stack overloads available with a clear caller responsibility contract. Allow an explicit broader-session reference for a subset; requiring identical kept-frame lists would break an intentional current workflow.

Before caching master stars, masks or full preparation, define invalidation separately for each product. Master pixels, master extraction and veto dilation depend on different inputs/settings. A changed profile or subset must not accidentally reuse stale products. Start with provenance and measurable reuse needs; no global cache or full-image hashing on every call is proposed. Cached state needs an explicit owner and lifetime under JA-02/07.

**Acceptance.** Verify same-run reuse, intentional full-session/subset reuse, a foreign same-size reference, changed master-extraction settings and changed frame rejection. Compare cached and uncached results where caching is later introduced. Report actual saved work and retained heap before expanding the reuse API.

### JA-11. Verify the chosen coordinated release explicitly

**Implemented protections.** Java 21 toolchain/release target, current-source consumer checks and adopted bytecode architecture checks are useful independently of historical compatibility. SpacePixels can validate exact packaged dependencies with composites disabled and exercise isolated headless consumers using Gradle/POM metadata.

**Revised scope (2026-10-23).** Remove the requirement for old-JAR/new-runtime binary compatibility and for one SpacePixels version to work with both old and new engines. The current `legacyApiCompatibility` task, baseline-JAR option and two-engine script remain implemented historical scaffolding, not required release policy. Replace their compatibility-only portions with current-selected-version consumer/artifact checks as part of coordinated cleanup. Current tooling is described, with that limitation, in [the artifact validation guide](../SpacePixels/docs/engine-compatibility.md).

**Release procedure.** Develop with `useLocalJTransientBuild=true` and `useLocalJPlateSolveBuild=true`. Before distributing SpacePixels, select exact JTransient/JPlateSolve versions, publish or otherwise supply the selected artifacts through the intended repository/distribution, pin those versions in the release build, and run the application/consumer checks with both composites disabled. Verify the actual resolved versions and absence of sibling substitution. Local staged artifacts prove packaging integration, not availability from the final release repository. There is no need to publish an older unused coordinate solely for a compatibility matrix.

**Acceptance.** Build/test on Java 21, enforce the adopted architecture boundaries, compile the coordinated consumers, and validate the exact packaged release combination including metadata/resources and headless/desktop separation. Preserve scientific regression and relevant data-format/ownership checks; old Java signatures, constructor layouts, global flags and binary canaries are not independent acceptance requirements.

### Structural blueprint: packages, classes and dependencies

**Proposed structural design, added 2026-10-20.** This elaborates JA-01–11 rather than creating another work queue or authorizing their implementation. The maintainer agreed the JA-01 rules-ownership direction separately and authorized the remaining proposed JTransient work on 2026-10-22; deferred/declined algorithm decisions remain unchanged. Names below specify a reviewable target; an individual change can refine a name with its rationale recorded here. Keep one Gradle project and published jar. Introduce a package when its boundary is implemented, without creating empty directories or moving every public class in one release.

#### Current dependency assessment

The original review package graph was not layered: `engine` calls `core`, `quality` and `photometry`, while those packages referred back to `engine` for debug state, shared frames and progress callbacks. JA-04 now routes diagnostics through `execution` and the class-level bytecode gate prevents science classes from depending on `JTransientEngine` itself. Shared `ImageFrame`, progress callbacks and nested legacy models still reside in implementation packages; those remaining dependencies belong to JA-07. Removing facade debug dependencies does not claim the whole graph is layered or change scientific calculations.

`core` contains extraction, image geometry, stacks, tracking, slow movers and residual analysis. Its classes often have useful responsibilities, but the package does not express those boundaries. Conversely, `photometry` already gives its helpers a coherent home and uses package-private implementation classes. Keep that pattern. `PipelineTelemetry` has no imports of the science packages today, but placing their frame records inside it makes those packages depend on a pipeline-level container; moving diagnostics must reverse that ownership carefully.

There are also dependencies hidden by today's shared package: `TrackLinker` calls the streak-shape predicate on `SourceExtractor`, and deprecated `CommonMotionRejector.apply` recomputes residual analysis. The blueprint assigns the shape predicate to tracking with shared footprint geometry below it; the deprecated cross-stage operation remains a compatibility adapter. These are specific dependency changes to verify, not imports to rename mechanically.

#### Target package layout and allowed dependencies

All names are beneath `io.github.ppissias.jtransient`. The dependencies column names permitted **direct project-package dependencies**, in addition to the JDK and existing justified external dependencies. It describes canonical implementations; update application imports/callers with package changes instead of requiring historical-name facades. Packages do not become separate artifacts.

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

`core` may temporarily hold not-yet-migrated classes, but old public names need not remain as compatibility facades. Add new computational code to the package owning its responsibility. No new `util`, `common`, `manager` or `service` catch-all package is proposed. Keep the existing `engine` entry-point package rather than introducing a second `api` package solely to rename the facade location.

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

Do not expose the executor field, the whole mutable `PipelineTelemetry`, Swing components, file paths for app workflows or every field of `PreparedSequence` merely to connect components. In the current implementation, `OperationContext` labels stage/frame failures, `DiagnosticContext` routes debug text, and `CancellationScope` carries cancellation. `ExecutionSession` exposes worker/resource policy and ownership without transferring executor shutdown rights to a stage. Keep these roles explicit rather than describing the existing `OperationContext` as a general bag for all concerns. Neither is a service locator for arbitrary pipeline products. Add interfaces for real substitution boundaries, such as cancellation/progress sinks or a controllable scheduler, while retaining concrete stateless analyzers elsewhere.

#### Coordinated API and package migration

A package move changes Java type names, including nested detection/track types. Update the library, importing SpacePixels code and tests together using composite builds. Existing public methods, constructors and fields are not automatically permanent contracts across releases. Do not create facade aliases, dual result models, reflection or fallback dispatch solely to preserve old names.

Preserve the scientific object graph during a refactor: one detection can appear in tracks, anomaly/group collections and transient review views, and large image/mask buffers intentionally alias. Prefer a coordinated direct model migration; if a real conversion is needed, preserve shared identity with one mapping per conversion rather than rebuilding independent copies for every view. Avoid unchecked generic casts. Stable membership, units and ownership must remain explicit even when signatures change.

Migrate one vertical responsibility at a time, update its callers, run component/scientific regression and then remove redundant paths. Temporary adapters may be used when they genuinely reduce implementation risk, but name their removal point; there is no requirement to support old/new engine versions concurrently. Java target, final artifact resolution, user data and scientific behavior remain release checks. No module descriptor, new runtime dependency or multi-artifact publication is required by this policy.

#### Decided in the review of this plan (2026-10-20)

- **The streak-shape predicate.** The scientific decision must retain its behavior, but the old public method need not stay on `core.SourceExtractor` merely as a facade; migrate its callers with the extraction/tracking separation. The canonical `extraction.SourceExtractor` has no streak-shape method and no tracking dependency. Measurements live in `image.FootprintGeometry`, and the decision lives in tracking.
- **Prepared inputs.** The main analysis methods take prepared inputs: tracking receives a built mask, and slow movers receive built stacks. Standalone convenience facades may still compose reference services; tell them apart from the analysis methods before tightening the dependency table.
- **Temporary exceptions.** Model migration names its temporary dependency exceptions explicitly, for example `residual` and `photometry` naming `TrackLinker` types until the shared models move. The residual helper that takes `TrackLinker.Track` is called only by a test, so it alone does not decide a production dependency.
- **Names are candidates.** The class names are design candidates, not a requirement to create every listed helper. Introduce a collaborator when its responsibility and ownership are clear.

### Responsibility map and refactor scope

Paths below are relative to `src/main/java/io/github/ppissias/jtransient/`. This map accounts for all 50 current production files, including package-private helpers. Destinations are canonical implementation homes under the blueprint; package/signature changes use the coordinated migration process above. It is a migration inventory, not an instruction to split every class.

| Current files | Canonical destination and action | Findings |
|---|---|---|
| `config/DetectionConfig` | Keep in `config`; add shared rules/metadata and captured settings without changing profile fields. | JA-01 |
| `engine/JTransientEngine` | Keep in `engine`; extract private runner, preparation and result builder; delegate resource policy to `execution`. | JA-01/02/03/05/09 |
| `engine/InputRules`, `ExtractedFramesContext` | Sequence validation goes to `image.FrameSequenceValidator`; engine context becomes private `PreparedFrame`/`PreparedSequence` associations. | JA-01/05 |
| `engine/ImageFrame`, `FrameTransients`, `TransientEngineProgressListener` | Canonical frame data goes to `model`; keep pipeline frame export in `engine`; new progress contract goes to `execution`, with the old callback adapted. | JA-05/07/09 |
| `engine/PipelineResult` | Keep pipeline result in `engine`; build coherent groups from stage-owned results and migrate consumers to coherent result construction instead of retaining obsolete positional constructors/duplicate fields. | JA-07/10 |
| `engine/JTransientAutoTuner`, `CalibratedAutoTuner` | Canonical implementation in `tuning.LegacyAutoTuner` and `tuning.CalibratedAutoTuner`; migrate facade/profile/result consumers directly; preserve both distinct algorithms, not old names for their own sake. | JA-02/03/04/08 |
| `core/PixelEncoding`, `FrameCoverage`, `FrameRegistration`, `DataArea` | Canonical home `image`; retain their distinct purposes and numerical behavior; update imports instead of retaining compatibility names. | JA-05/07 |
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
| `telemetry/PipelineTelemetry`, `TrackerTelemetry` | Keep pipeline aggregation/formatter in `telemetry`; canonical tracking diagnostics go to `tracking`, photometry diagnostics to `photometry`, updating nested/public-record consumers together rather than requiring old record adapters. | JA-05/07/09 |

#### Structural acceptance checks

Before adopting a slice, identify its current methods/types, target package, public signatures retained, mutable state owner and any temporary dependency exception. Verify all 50 files remain accounted for as the inventory changes. Prefer package/import and visibility checks for agreed boundaries, supplemented by source review for same-package calls and fully qualified references; a raw import count alone does not prove separation.

Check that canonical dependencies follow the table, feature helpers stay inaccessible to external callers, and lower-level stages do not import `engine`, legacy `core` facades or aggregate `PipelineTelemetry`. Check actual public method/field signatures, not just imports: a supposedly independent input/result can still expose an implementation-nested type. Compile a small external consumer using the existing API and representative standalone components, in addition to compiling SpacePixels. Recompile consumers against the selected current library artifacts. Old-JAR/new-runtime binary compatibility is not required by the coordinated release policy.

Run focused component/contract tests and the engine/SpacePixels regression procedure for every code slice. Compare classification membership, identity/alias relationships, frame indexing, ordering where contractual and numerical outputs, as well as counts and timings. For this blueprint update only the source/dependency inventory and documentation were checked; no structural code change, architecture test, build or real-data run was performed.

### Proposed architecture and delivery order

Keep one library artifact and clear current entry points; old facade names are not a compatibility obligation. The intended dependency direction is from orchestration toward stage services and shared contracts; computational helpers should not need an engine facade to access debug state or a telemetry container to name their scientific data.

```mermaid
flowchart TD
    Caller[SpacePixels or another caller] --> API[Current engine / tuner / extractor APIs]
    API --> Run[Per-operation settings and execution context]
    Run --> Prep[Frame preparation and quality selection]
    Run --> Stages[Reference, tracking, residual, slow-mover and photometry stages]
    Run --> Tune[Tuning measurement and selection]
    Prep --> Model[Shared frame, detection, geometry and result contracts]
    Stages --> Model
    Tune --> Model
    Run --> Exec[Execution limits, cancellation and diagnostics]
    API --> Views[Structured results and text formatters]
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
| Alongside each step | JA-11 selected-artifact consumers, architecture and focused contract tests | Establish the Java baseline early; verify every adopted boundary without one large refactor release. |

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

## Maintainer questions and release-model clarification (2026-10-23)

This section answers the questions against the implemented code, distinguishing benefits already used by SpacePixels from optional APIs not yet adopted by it. The clarified release policy below supersedes earlier requirements in this plan to preserve old library APIs or support old/new engine combinations.

### JA-01: rules versus configuration snapshots

Your understanding of the rules is correct: `DetectionConfigRules` defines engine-owned field metadata and relationships in Java code. SpacePixels consumes those definitions rather than inventing a second set of numerical comparisons in Swing. `validate` reports issues without changing settings; `align` explicitly returns adjusted settings and a list of changes. At present, consumed non-finite values are blocking errors, while many finite domain/dependency findings are advisory. Defining a rule does not necessarily mean the engine silently corrects it or refuses the run.

A **configuration snapshot is a private settings copy taken when an operation starts**. For example, a run starts with detection sigma 5. If the caller subsequently changes its settings object to sigma 4, the running operation continues with its captured value 5; the next operation can use 4. The same separation prevents a stage's local settings changes from modifying the application's settings object.

This happens automatically in the ordinary engine entry points as well as the tuners; SpacePixels does not need to call a specially named snapshot method to get JA-01's configuration isolation. `DetectionConfig` contains primitive fields, so this is a small clone, not a copy of all image pixels. The cloned DTO remains mutable internally; it is private to the operation, not a new deeply immutable configuration type. Callers must still avoid concurrent multi-field writes while the initial copy is being taken.

### What the different uses of "snapshot" actually mean

| Where | What is captured | What it does not do |
|---|---|---|
| JA-01 configuration snapshot | A validated private copy of `DetectionConfig`. | Does not copy images or save a checkpoint to disk. |
| JA-04 tuner `Options` | Immutable search policy, with copied grids/arrays and scalar limits. | Does not make the borrowed input images immutable. |
| JA-05 engine `*Snapshot` methods | A private copy of the frame-list container, which the engine can sort. Ordinary configuration capture still happens underneath. | Does not clone frame descriptors, image rows or pixel matrices. |
| Photometry `Input.snapshot()` | Validated aligned list containers for frames, metrics and optional per-frame detections. | Does not deep-copy mutable measurements, detections, tracks or large buffers. |
| `OperationContext.Snapshot` | Small stage/frame failure-context information passed to workers. | Is not an image, settings or application-state snapshot. |

The shared idea is "capture the relevant small state so later changes elsewhere cannot alter its structure/settings". It does not mean a full backup, resumable run or expensive duplicate dataset.

### JA-05: practical frame associations and copy-sorting APIs

Before this change, preparation relied on corresponding positions in separate collections: frame 0, metrics 0, extraction 0, and a registration looked up separately. Keeping those collections aligned through sorting and frame rejection was a convention every caller/helper had to maintain.

`PreparedFrame` now bundles **one image descriptor + its quality metrics + its optional extraction + its registration**. Workers return that bundle, and quality selection keeps or removes the whole association. Downstream aligned views are derived from the retained bundles. Master-only preparation uses the same association/selection approach but does not run full transient extraction just to fill the bundle. Images and science products remain shared rather than deep-copied.

For example, original sequence IDs are 10, 35 and 90. If quality control rejects 35, retained positions are 0 and 1, but original identities remain 10 and 90. Metrics, registrations, telemetry and light-curve positions must not accidentally attach frame 35's information to frame 90. A kept frame with no detections still needs its slot; removing empty slots would shift all later associations. Tracker telemetry now distinguishes `retainedPosition()` from `originalFrame.sequenceIndex()`/filename.

The new `runPipelineSnapshot`, `detectTransientsSnapshot` and `generateMasterStackSnapshot` methods additionally leave the caller's frame-list order untouched and accept an unmodifiable list. The older methods still sort their supplied list in place. That duplication was created to preserve the old API behavior, not because two scientific algorithms are needed. With your clarified policy, a future cleanup can make private-copy sorting the ordinary API behavior and update SpacePixels directly.

**Currently used:** prepared associations are internal to ordinary detection, transient inspection and master generation, so SpacePixels already benefits without changing its calls. Engine photometry uses the validated input-snapshot path. SpacePixels production code still calls `runPipeline`, `detectTransients` and `generateMasterStackWithDetails`, not the new engine `*Snapshot` wrappers. The wrapper addition itself has not yet changed the application's frame-list ownership behavior.

### Outcome methods and `OperationContext`: these are JA-03, not JA-05

The two concepts are independent: **snapshot = ownership/capture of inputs; outcome = how completion or failure is returned**.

Ordinary `runPipeline` returns a result or throws an exception. `runPipelineOutcome` returns one of:
- `Completed(result)`: the operation ran, possibly finding no objects or producing a normal "not ready" scientific result.
- `Failed(failure)`: execution failed, with a stage, optional original frame identity, message and original exception.
- `Cancelled(cause)`: cooperative cancellation/interruption, after the engine has joined its running work.

Master preparation, transient-only detection and tuning have corresponding outcome adapters. Outcome wrappers do not themselves make a private frame-list copy: the current engine wrappers delegate to the in-place-sorting methods. Nor do they turn fatal VM errors into normal results. Tuning that finds insufficient evidence can complete normally with an unsuccessful scientific tuning result; that differs from a worker actually failing.

`OperationContext` is the small internal **"where were we when this failed?"** mechanism. A captured operation establishes a shared failure record. Stage scopes label extraction, stacking, photometry, etc.; frame scopes add the original sequence ID and filename. Managed worker batches carry those labels into worker threads and restore their previous state afterwards. If extraction fails on original frame 90, the outcome can identify `FRAME_EXTRACTION` and frame 90, rather than only reporting an exception from a future or retained-list position 1.

It does not schedule work, associate quality measurements with images, or print logs. Without an enclosing capture, the stage helpers simply execute the work and preserve its exceptions. **SpacePixels production code does not yet call the outcome APIs**, so it currently retains exception-based failure handling rather than receiving these structured outcomes. Preserving that old handling was an implementation compatibility choice; it is not a requirement under the clarified release model.

| Mechanism | Responsibility |
|---|---|
| `PreparedFrame` | Which image owns these metrics/detections/registration? |
| `OperationContext` | Which stage/frame failed? |
| `DiagnosticContext` | Is debug text enabled, and where should it go? |
| `ExecutionSession` | Which workers/policy belong to this operation, and who closes them? |

### JA-11: practical benefits, and the unnecessary compatibility part

The useful release protections are:
1. **Known Java baseline:** compile with a Java 21 toolchain and Java 21 release target rather than accidentally producing bytecode requiring a newer local JDK.
2. **Check the actual packaged release combination:** a passing composite build uses sibling sources. It does not prove the pinned JTransient/JPlateSolve JARs contain those changes. A no-composite artifact check catches a stale/wrong dependency or missing API before shipping SpacePixels.
3. **Check real consumers/metadata:** isolated SpacePixels API/CLI consumers verify the selected release artifacts, resources and transitive dependencies with Gradle and POM-only metadata, without silently obtaining missing classes from the development workspace. They also check that headless consumers do not acquire desktop classes/dependencies.
4. **Protect implemented architecture boundaries:** bytecode checks prevent configuration/execution contracts depending back on higher layers and prevent science classes depending on the engine facade for debug access.

The old-JAR/new-engine binary compatibility canary and testing one SpacePixels build against both old and new engines are **not necessary for your release process**. I implemented those under an overly broad backward-compatibility assumption. The required gate is one deliberately selected, internally consistent SpacePixels/JTransient/JPlateSolve release combination, not a matrix of interchangeable historical engines. The earlier reported absence of the old 1.0.5 coordinate only matters if that coordinate is selected for a release; there is no requirement to publish it merely to satisfy the historical matrix.

### JA-02: one operation does not mean one worker thread

You are correct that a `JTransientEngine` instance accepts only **one active top-level operation at a time**. It rejects an overlapping call on the same instance. SpacePixels's ordinary job flow does not need multiple complete detection pipelines running simultaneously.

However, the statement that the engine does no parallel work is not correct. **One detection run already performs parallel work internally**: per-frame extraction/quality evaluation, stack row batches and photometry measurements; calibrated tuning also has parallel crop/measurement work. Much of that parallelism existed before JA-02. JA-02 did not introduce a requirement for simultaneous SpacePixels detection jobs.

Its practical change is to put those batches under one operation-level worker/resource policy, rather than allowing some stages to use an independent pool or the common pool. The default engine constructors and tuner paths already use this automatically. The worker count takes CPU availability, work count and the existing approximate scratch-memory estimate into account; nested managed batches execute inline to avoid waiting for more workers from the same already-occupied bounded pool.

It also makes lifetime explicit: owned workers are closed/joined by their owner; borrowed executors/sessions are not shut down by the borrower. SpacePixels currently uses default owned engines and closes them after work; shared/borrowed session constructors are optional library flexibility, not a current SpacePixels requirement. This is resource control and reliable cleanup, not a promise of faster science, less memory, a hard heap limit or process-wide memory reservation. The recorded large-frame single-sample benchmark was in fact slower/higher in measured pool peaks; no optimization claim was justified.

### JA-04: diagnostic information versus logging

The diagnostic-routing change does **not** introduce new scientific measurements or automatically change reports, readiness calculations, GUI progress or log detail. It changes the ownership/destination of existing core debug text.

Previously, lower-level science code consulted the global `JTransientEngine.DEBUG` flag and wrote to `System.out`. Now it uses `DiagnosticContext` with captured `DiagnosticOptions`: enabled state plus a destination callback (the sink). For example, one operation can collect text in its own log while another is silent, without repeatedly changing a JVM-global flag. Workers inherit the operation's policy; closing the scope restores prior state. Lower-level science code no longer needs the engine facade merely to decide whether to print a message.

Sinks run synchronously on the caller/worker threads, so they must be thread-safe when shared and must not block indefinitely or change scientific inputs. A throwing sink can fail the operation. Default use remains silent; this is not an asynchronous logging service.

**Current SpacePixels integration:** `DebugOutput.applyToEngine()` still sets the engine/tuner global debug flags from `spacepixels.debug`. The new engine boundaries capture those defaults, so existing application debug behavior is retained. SpacePixels has not yet switched production calls to explicit per-operation sinks. The flag-supplier bridge and global-default paths can be removed when we migrate those callers together; they are not required indefinitely.

### JA-04: tuner options, available samples, and 1.0.5 versus 1.0.6

`DetectionConfig` describes the detection settings being evaluated. Tuner `Options` describe **how to search for settings**: how many sample frames/crops, which sigma/area grids to try, budgets, scoring/jitter policy and related limits. Before the options slice, these controls were mutable static fields. One run could otherwise observe changes made for another run or by a later callback.

Both tuners now capture immutable options once, clone supplied grid arrays, and keep the effective policy with their result/measurement. Explicit options allow independent runs without editing globals. This is still useful when SpacePixels runs only one job at a time: it gives a reproducible search policy and avoids temporary global edits; it is not solely a concurrency feature. Default entry points currently capture the old static values as an adapter. The legacy tuner's own `debug`/`diagnosticSink` options remain separate from the core diagnostic policy; that separation preserved previous logging behavior, but can be simplified with a coordinated migration.

The **available-sample API** is `JTransientAutoTuner.tuneAvailableSnapshot`. It caps the requested sample count to the frames available using private options. For example, a request for 20 samples with 12 available becomes 12 for that run, without temporarily assigning a different global `AUTO_TUNE_SAMPLE_SIZE`. Normal input/evidence checks still apply; this is not a guarantee that every small dataset can be tuned successfully.

The method was added in the source version labeled **JTransient 1.0.6**; the earlier 1.0.5 source baseline lacks it. To keep one SpacePixels build usable with both, I added `LegacyTunerSupport`: reflectively discover the new method, or use a synchronized old path that temporarily changes/restores the static sample count. This was deliberate compatibility machinery, **not evidence that SpacePixels needs to support mismatched engine versions or that either coordinate was necessarily published**.

Under your clarified workflow, that reflection/fallback is unnecessary. SpacePixels should call the agreed current API directly, compile with the sibling library during development, and pin the matching artifact at release time. A SpacePixels release is not expected to load an arbitrary older JTransient JAR. The useful retained feature is immutable per-run sample/search options, not support for both version labels.

### Clarified development and release policy

Active development treats SpacePixels, JTransient and JPlateSolve as one coordinated source change across separate repositories. Use the local sibling composite builds. Change library APIs and consuming SpacePixels code together whenever that produces a simpler design; backward source/binary compatibility with older library releases is not a requirement.

Before each SpacePixels release, choose and pin the exact JTransient and JPlateSolve versions that belong to it, make those artifacts available through the intended distribution mechanism, and validate that exact packaged combination with composites disabled. A single application binary need not support old and new engine versions interchangeably. This supersedes the earlier compatibility-preservation requirements in the active plan; archived implementation records remain historical evidence, not continuing obligations.

This clarification is about **library code/API compatibility**. It is not permission to silently change detection mathematics, lose persisted user profiles/session metadata, break report semantics or discard user data. Those are separate behavior/data decisions and retain their existing regression and review requirements.

### Review of the JTransient changes under that policy

**Conclusion:** keep the scientific safety/ownership improvements, but simplify the API surface and remove historical-engine compatibility machinery as coordinated caller migrations. Several prior choices were more conservative than your workflow needs. This review changes the development policy and follow-up plan; it does **not** claim that the source adapters/build gates below have already been removed. No runtime/scientific behavior is changed by this documentation review.

| Change reviewed | Still useful for the coordinated application | Simplification now permitted / follow-up |
|---|---|---|
| JA-01 settings capture and shared rules (`98f2275`, `6a410ad`) | One engine definition of numerical policy; private per-operation settings; no accidental caller/stage mutation. | Do not retain same-reference tuning fallbacks or duplicate entry points solely to preserve old behavior. Keep advisory-versus-blocking validation and sentinel/profile meanings as explicit scientific/data decisions, not accidental casualties of an API cleanup. |
| JA-02 execution ownership (`26c445b`) and existing cooperative cancellation (`8aa8b1c`) | Internal parallel batches share a resource policy, nested work avoids pool deadlock, cancellation/close join running work, and one engine rejects overlapping operations. | No requirement to support concurrent whole pipelines in the GUI. Borrowed executor/session overloads are optional resource flexibility, not backward-compatibility bridges; retain or trim them on actual use/complexity merits rather than deleting bounded execution. |
| JA-03 outcomes and failure context (`85029fa`) | Original cause plus stage/frame identity; distinguish cancellation, execution failure and normal scientific emptiness. | Select one clear application-facing failure contract and migrate SpacePixels. Preserving every old thrown-exception identity/overload as a separate public path is not a release obligation. Do not confuse this with losing causes or treating cancellation as success. |
| JA-04 immutable tuner options (`8592d4b`) | Reproducible grids/budgets/samples; defensive array copies; no per-run static edits. | Migrate callers to explicit options/default factories; static knobs need not remain public compatibility inputs. Remove SpacePixels `LegacyTunerSupport` reflection, version probing, lock and old sample-size mutation/restoration; call the selected engine API directly. The `tuneAvailableSnapshot` convenience behavior can be expressed through the ordinary options path if that is simpler. |
| JA-04 core diagnostics (`792603a`) | Explicit per-operation enable/sink policy, worker propagation/restoration, lower-layer independence and silent defaults. | Migrate SpacePixels `DebugOutput` to construct/pass per-run options. Then remove the global engine debug bridge and compatibility-only registration API. Independent legacy-tuner/core text settings may be consolidated if the intended application log behavior is made explicit. |
| JA-05 preparation and alignment (`f774e28`, `90afdd4`) | Frame/metric/detection/registration association, empty slots, original-versus-retained identity, small-container capture and explicit borrowed pixels. | Prefer one ordinary private-copy-sorting input contract and validated photometry boundary. Update callers and remove duplicated old/in-place versus `*Snapshot` paths instead of preserving both indefinitely. Snapshot ownership and outcome/error reporting remain separate concerns even if exposed through one canonical entry point. |
| JA-06 tracking separation (pending) | Clear stage responsibilities and classification ownership. | Extract helpers/models and update SpacePixels imports directly; old facade/nested-class names need not survive. Preserve detection identity, assignment/tie/greedy rules and scientific outputs. |
| JA-07 results/model ownership (pending) | Coherent result construction, explicit mutable/stable views, units and large-buffer sharing. | Replace obsolete positional constructors, duplicate legacy/grouped fields and nested implementation model types with coordinated consumers. Avoid maintaining two equivalent object graphs just for old Java names. Persisted representations and intended aliases still need explicit checks. |
| JA-08 tuner measurement/selection/reporting (pending) | Explain/replay selected settings and use unambiguous measurement units. | Introduce coherent result/profile types and migrate Overview/charts/session persistence directly rather than requiring permanent old result adapters. "Legacy tuner" names a distinct scientific algorithm, not merely an old library version; this policy does not remove that algorithm. |
| JA-09 stage events/outcomes (partly pending) | Distinguish disabled, unavailable, empty and finding-bearing work; clear callback threading and timing. | Migrate percentage/text consumers and live-preview integration together if the stage API changes. Do not require an old callback adapter just because a previous release had that signature. Keep GUI dispatch and job identity in SpacePixels. |
| JA-10 reference provenance (pending) | Guard against a foreign same-size reference or incompatible coordinate/selection assumptions when reuse is actually needed. | Design against current consumers; no need to retain every raw-stack overload or add a cache to demonstrate reuse. Dataset/reference compatibility is a scientific safety concept, not historical Java binary compatibility. |
| JA-11 release/architecture work (`6910aec` plus SpacePixels artifact checks) | Java 21 target, bytecode boundaries, current selected-artifact consumer/metadata/resource tests, no-composite release verification. | Retire the previous-JAR binary canary and mandatory two-engine matrix. Replace the script with validation of the one selected engine/solver/application combination. Representative current-version consumer tests are still useful; they need not assert historical constructors, fallback aliases or Java names. |

**Recommended next coordinated cleanup:** (1) remove historical-version gates and the reflective legacy-tuner fallback, keeping the selected-artifact release check; (2) migrate debug setup to explicit per-run options and remove global adapters; (3) consolidate frame-list/snapshot and failure entry points while adopting their useful contracts in SpacePixels; (4) continue JA-06–10 without mandatory compatibility facades. Choose release version numbers at the release decision, not as a reason to keep old APIs during source development. Re-run code/scientific checks when those code changes are made; this review's documentation-only validation is not a substitute.
