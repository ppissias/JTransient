# Design improvements: closed items, October 2026

> Historical record, archived 2026-10-21. These sections were moved out of [DESIGN-IMPROVEMENTS.md](../../DESIGN-IMPROVEMENTS.md) so that it holds only pending work and open decisions. They are kept as written, including statements that later changes superseded: the assessment of the 2026-10-20 architecture review, the decisions to keep current behaviour, the completed and retired findings, and the review of the plan with its answered questions. The decisions from that review were folded into the JA items when this was archived. Do not re-propose a declined item without new evidence.

Contents:
- [Configuration snapshots and shared rules](#ja-01-configuration-snapshots-and-shared-rules-2026-10-22)
- [Operation cancellation and worker lifetime](#ja-02ja-03-operation-cancellation-and-worker-lifetime-2026-10-21)
- [Assessment and scope of the architecture review](#assessment-and-scope-of-the-architecture-review-2026-10-20)
- [Decisions to keep current behavior](#decisions-to-keep-current-behavior)
- [Completed and retired findings](#completed-and-retired-findings)
- [Review of the plan, with answered questions](#review)

## JA-01 configuration snapshots and shared rules (2026-10-22)

**Implemented with SpacePixels, incorporating its [107-field rule review](../../../SpacePixels/docs/detection-settings-rule-review.md#implementation-decisions-ja-01-2026-10-22).** `DetectionConfig` remains the mutable primitive compatibility DTO. Each public engine operation, both tuners and direct calibration capture a private copy before progress/workers, then derive stage-local copies. Later caller edits cannot affect the operation; concurrent multi-field edits during capture still need caller synchronization. Frame/pixel ownership and static tuning/debug options are unchanged.

`config.DetectionConfigRules` is supported public API: complete typed/default/domain metadata, operation/timing-scoped validation, structured immutable issues and explicit copy-based alignment with IDs, direction and old/new values. SpacePixels no longer defines numeric pair comparisons; it owns binding, parsing/focus and English presentation. Profiles, API and iterative requests require finite persisted numbers; operation validation ignores inactive/unconsumed fields. Legacy and calibrated tuning have separate read scopes. Only non-finite consumed numbers become errors; finite scalar-domain findings and new consistency/capability conflicts remain advisory.

Compatibility decisions:
- Preserve all config fields/defaults and existing automatic/disabled/uncapped sentinels. Do not order independent frame/master/slow-stack sigmas or minimum pixels, nor persistence against minimum frame count.
- Preserve existing `tune` fallback aliasing; new `tuneSnapshot` APIs return independent success and fallback configs. SpacePixels uses the new APIs.
- `LEGACY_GUI` retains six historical adjustment directions. Explicit `REVIEWED_CONSISTENCY` adds hidden quality-grow and limited-amplitude policies, and excludes rhythm/jump. A regression actually prunes positions 10/10.8/11.3/12.3 to 10/10.8/12.3, accepting a 1.2 px rhythm threshold above a 1 px raw cap. Therefore rhythm/jump is not an execution invariant.
- Diagnose geometric jitter/jump with actual/unknown timing, positive photometry caps below the engine minimum, annulus-factor conflicts and anomaly redundancy. Never invent a jitter, star cap or annulus-factor correction.
- `PhotometryGeometry` centralizes the existing 1 px aperture floor, comparison apertures through 2.5 FWHM, inner clearance of 1 px and sky width of 2 px. Measurement/guide geometry, selection, frame-detection inner crossings and track/slow-mover outer crossings use it. Structured radius adjustments are available from the resolver without rewriting preferences; no new report UI or result-warning protocol is implied.

**Validation.** JTransient `check`: 235 tests, no failures. SpacePixels `check`: 375 tests, no failures, four opt-in tests skipped. Nine GUI mechanics cases pass, including all six legacy widget directions, hidden quality preservation until explicit alignment, Keep/Align, save/roundtrip, invalid text/revert and delayed/failing background writes. Tests also cover full metadata, consumed/unread non-finite settings, API/profile error equivalence, accepted sentinels, detached tuning success/fallback and a controlled pause followed by caller edits. Preview Frame/Blink navigation and annotations also pass.

The read-only real-data harness `EngineCancellationRealDataIT.main` compares pre-JA-01 `5f38022` and the completed implementation on all 153 africano-reg frames (2494 x 1587), engine defaults plus variable-star detection enabled. Full result/config scientific JSON matches byte for byte; image/mask arrays are SHA-256 hashed and execution timings excluded. Baseline, rules-only, initial geometry and final snapshots all hash `f0b49f2887ffe2a318b52ae288d39ef9f56527401057c363824da54ad69e58f4`. Ignored snapshots/logs live in SpacePixels `build/ja01-*`; the earlier default-heap OOM was a validation invocation that unintentionally enabled the opt-in real-data test, not an unresolved product failure. Custom expanded annuli have targeted edge, detection/track/slow-mover crossing and Gaussian flux regressions; no broad all-dataset invariance claim is made.

**All reference datasets (2026-10-22).** The same harness and engine pair (`5f38022` against `6a410ad`, with SpacePixels `3960b7b` loading the frames) ran on all 18 SpacePixels reference datasets: the original seven (klangwolke-2 from `_16bit_converted`) and the eleven variable-star sets. Every scientific snapshot is byte-identical between the two engines. The resolved photometry radii differ from the raw factors only when the session FWHM is below 2 px with default factors. The lowest session median here is M81-kumar at 2.05 px; 5652 of its 12702 per-star FWHM values are below 2 px, and its snapshot is still identical. The others range from 2.30 px (dc-reg) to 4.58 px (Apophis). No reference dataset exercises a session FWHM below 2 px, so that path remains covered only by the targeted tests above.

Both ignored local composite flags remain enabled. The coordinated API is in the unreleased `1.0.5` source line; Maven publication, old-binary fallback and certification of a published SpacePixels/JTransient pair are not claimed and remain release work under JA-11. D10's typed GUI bindings, actual dataset-readiness diagnostics and per-run tuning options remain separate work.

### Original design and agreed acceptance (pre-implementation)

The following preserves the evidence and agreements that governed this implementation, not a claim that the old deficiencies still exist.

**Evidence.** [DetectionConfig](../../src/main/java/io/github/ppissias/jtransient/config/DetectionConfig.java) is intentionally a public mutable, Gson-compatible collection of primitive fields. Its `clone()` copies those values, but it provides no central validation. [JTransientEngine](../../src/main/java/io/github/ppissias/jtransient/engine/JTransientEngine.java) passes the supplied object through worker tasks and later phases. [InputRules](../../src/main/java/io/github/ppissias/jtransient/engine/InputRules.java) validates frame sequences and master dimensions, not configuration. Both tuners also read supplied settings throughout their operations; some unsuccessful tuning paths return the original `baseConfig` reference.

**Risk and proposal.** Stage-local clones prevent the engine from leaking temporary overrides, but they do not isolate a run from a caller changing the original settings. Capture settings once at entry, then derive private stage settings from that snapshot. Keep the serialized `DetectionConfig` shape as the compatibility DTO; a validated internal settings object need not add, rename or remove profile fields. Return an independent fallback configuration as well as an independent successful one under a documented contract.

Define field and cross-field validation centrally, scoped to the operation and enabled stages. Preserve documented sentinels, automatic modes and existing intentional fallback rules; a refactor must not silently turn accepted profiles into errors or reinterpret their values. Engine validation must work for callers outside SpacePixels. GUI editor validation remains an application responsibility.

**Rules ownership clarification (agreed with the maintainer, 2026-10-20).** JTransient owns engine-setting meanings, defaults, valid values, cross-field relationships and alignment rules. Migrate the existing finite-number checks and six paired-setting rules from SpacePixels [DetectionSettingsRules](../../../SpacePixels/src/main/java/eu/startales/spacepixels/config/DetectionSettingsRules.java) into a shared JTransient rules service, and remove their application-side definitions. SpacePixels must consume that service instead of maintaining a second set of rules or comparing paired values in its widget code. This records the agreed ownership direction; the concrete implementation remains Proposed.

The shared service should expose two operations backed by the same rule definitions:

- **Validate:** inspect a configuration without modifying it and return structured issues identifying the affected fields and violated rule.
- **Align:** return an adjusted configuration copy, plus structured changes with old/new values and reasons. Preserve each existing pair's adjustment direction and report the changes to the caller.

SpacePixels owns parsing editor text, focusing invalid fields, formatting/displaying issues and adjustments, updating widgets, layout, step sizes, basic/expert/hidden classification, persistence and migration. Its GUI captures values, calls shared alignment, applies returned changes to the widgets, and validates before saving or starting work. The engine validates its captured snapshot with the same service before execution. Calling the shared validator at both boundaries is deliberate; there is only one definition of each rule. Prefer rejecting inconsistent execution input with structured issues, with alignment requested explicitly by callers, rather than silently changing submitted scientific settings. Reconcile this with existing engine fallback behavior before enforcing new rejection rules.

Genuine numeric validity limits should be exposed as JTransient metadata that GUI bindings can consume. Distinguish them from convenient spinner ranges before making existing widget bounds engine restrictions. Preserve special values such as master-grow `0`, hidden fields and accepted profile semantics; do not expand an automatic setting into a fixed persisted value during alignment. Keep shared rule tests in JTransient and GUI parsing, binding and feedback tests in SpacePixels.

**Decided in the review of this plan (2026-10-20).**
- **Fallback identity.** Existing compatibility entry points keep their documented same-reference fallback (`baseConfig`). New snapshot-based APIs return independent copies. Callers use `success` to tell a fallback.
- **Accepted profiles.** A profile accepted today stays accepted until a rejection policy is agreed.
- **Scoped validation.** `validate(config, operation)` checks what that operation reads, including shared preparation and enabled branches. Settings of an inactive stage do not block it unless they break a global invariant.
- **Identifiers, not sentences.** Issues and adjustments carry field and rule identifiers, and SpacePixels writes the messages. Its settings tab and Preview Frame need back the direction of each adjustment, a rule identifier and the field identifiers.
- **Coverage.** Shared rule and metadata tests cover newly added fields and preserve sentinels.

**Acceptance.** Use a controlled pause after capture, change the caller's config, and verify the active run still uses its captured values. Verify local overrides and tuning success/fallback leave the base untouched. Check representative persisted profiles, hidden fields, sentinels and invalid numeric values through engine and tuner entry points. Verify GUI and direct callers obtain the same issues from the shared validator, and that GUI alignment preserves current pair behavior without retaining separate rule definitions. Compare unchanged valid runs on reference data before adopting new validation behavior.

## JA-02/JA-03 operation cancellation and worker lifetime (2026-10-21)

The maintainer authorized the cancellation slice together with SpacePixels, using the local sibling composite build. Operations accept a thread-safe `CancellationToken`, directly through overloads or an enclosing `CancellationScope`. Scoped state is restored after the operation and explicitly propagated into worker batches. Image, tracking, calibration and photometry loops check cancellation. A failed or cancelled batch stops queued tasks, requests running siblings to stop, and waits for their bodies and cleanup to finish. Interrupt status is preserved; worker errors, including out-of-memory, cannot be hidden by a simultaneous cancel.

Calibrated tuning propagates `CancellationException` instead of returning a calibration fallback. Other runtime failures retain the existing unsuccessful result and expose the original cause as transient `Calibration.failureCause`, so existing saved tuning JSON remains readable and runtime exceptions do not enter its serialized shape. Scientific readiness outcomes are unchanged.

One operation per engine is enforced. An overlapping operation or a close during active work is rejected. Cancel and await the operation before closing it; sequential reuse after cancellation or ordinary failure remains supported. Idle engine shutdown and calibrated tuning wait for their owned pools to terminate. Stack rows use cancellation-aware, joined common-pool batches; they remain outside the frame-worker memory budget, so JA-02's shared resource-policy work remains open. Consistent stage/frame failure context remains open under JA-03 and should coordinate with JA-09.

Both projects' unit suites and focused cancellation tests pass, together with four viewer mechanics checks in SpacePixels. On klangwolke-2, baseline `6c3f34d` cancellation waits of 9.963 s (extraction), 6.039 s (photometry ensemble) and 11.374 s (tuning fallback) fall below 0.01 s with cooperative cancellation in the controlled comparison. Full scientific-output snapshots match byte for byte, including hashed stack/mask pixels and numerical telemetry, excluding execution timing. See the [SpacePixels D04 record](../../../SpacePixels/docs/archive/design-improvements-completed-2026-10.md#d04-engine-cancellation-and-viewer-lifecycle-2026-10-21) for configuration, triggers, dataset and snapshot hash. Current API behavior is in [PIPELINE.md](../../PIPELINE.md#cancellation-and-worker-lifetime). No published release or published-pair compatibility check is claimed.

## Assessment and scope of the architecture review (2026-10-20)

Reviewed against checkout `b424a6e` (library version `1.0.5`). JTransient has a sound application boundary: it processes in-memory images, exposes standalone extraction and tuning, and keeps file preparation, persistence, GUI work and report generation in SpacePixels. Its focused analyzers and existing regression tests are useful foundations. A replacement framework, plugin pipeline or multi-module rewrite is not justified by this review.

The main architectural weakness is that **run ownership and public data contracts are less explicit than the scientific stages**. A run uses caller-owned mutable settings and images, several execution mechanisms, and shared mutable detection objects. Tracking owns several distinct stages; tuning combines measurement, selection and presentation. These arrangements make safe reuse, cancellation, concurrent callers and compatibility harder to reason about.

The review inventoried all 50 production Java files, their declared types and internal dependencies, and examined the important entry points, stage boundaries, state mutations, execution and result construction in detail. It also inspected the 36-file test inventory, relevant contract tests, build/publication configuration, current library documentation, and selected SpacePixels consumers: standard/iterative pipelines, Auto-Tune integration and public API. The responsibility map below accounts for every production file. This is a broad architecture review, not an exhaustive correctness audit of every numerical method or a guarantee that every defect has been found.

No code, settings or datasets were changed, and no tests, fault-injection experiments, performance benchmarks or real-data comparisons were run for this documentation review. Evidence describes source mechanisms; consequences described as risks are inferred, with no claimed incidence. Detection rules, thresholds, association choices and photometry statistics are outside this review. In particular, the proposals below do not approve any deferred or declined algorithm finding elsewhere in this document.

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

The [archived engine review](code-review-2026-10.md#implementation-status-2026-10-18) retains full commit/decision provenance. The [maintenance notes](maintenance-notes-2026-10-19.md) retain the 19-dataset per-step results, tuner differences and M81-kumar experiments. In particular, the recorded HIGH-profile M81 sequence is LR 274 → 84 → 10 and CM 52 → 5 → 3 after master-star and camera-fixed changes, respectively; these are historical measurements with the recorded profiles, not new benchmarks from this cleanup.

## Review

Comments from a review of this document on 2026-10-20, checked against the production tree at `bfafd64`. That commit does not change `src/main/java` relative to the architecture review's `b424a6e`, and the library version is still `1.0.5`. The blueprint's inventory matches this tree: 50 production Java files, 36 test files, and six paired rules in SpacePixels `DetectionSettingsRules.Pair`. Sampled evidence also matches the source: mutable `DetectionConfig` with stage-local clones, static tuner grids, in-place frame sorting, one frame pool reused by photometry after extraction, `IntStream.range(...).parallel()` in `MasterMapGenerator`, fallback results that keep the caller's `baseConfig` reference, `bestTransientRatio` assigned from `noisePerMpixFrame` in the calibrated tuner, `shutdown()` without waiting, and the iterative pipeline's full-session `short[][]` master. These comments do not change the status of any item above and do not authorize implementation.

The decision list is in good shape. Algorithm findings stay separate from the JA items, statuses mean what the legend says, and the blueprint keeps one jar, the current public names, and the scientific stage order. The next useful edit is to close the four questions that change the dependency table and the acceptance tests for steps 1 and 2.

> **Remark (2026-10-20):** The responses below distinguish factual corrections, clarifications and design preferences. There are seven questions below; each has a response. These remarks leave proposal statuses and implementation authorization unchanged.

### Comments

**The streak-shape move conflicts with the dependency table.** `SourceExtractor.isBinaryStarLikeStreakShape` is public, and `TrackLinker` calls it. The class table puts the predicate in package-private `tracking.StreakShapeFilter` and has the old extractor helper delegate through a tracking facade. The canonical home of `SourceExtractor` is `extraction`, whose allowed dependencies are `model`, `image`, `config` and `execution`. A delegate on that class adds `extraction` → `tracking`. Once `DetectedObject` lives in `model`, tracking does not need the extractor to judge a footprint. The arrangement that preserves the table is: measurements live in `image.FootprintGeometry`, the decision lives in tracking, and the old public method stays on a compatibility facade at today's class name. That facade is the temporary exception allowed to call tracking. The canonical extractor then has no streak-shape method.

> **Remark (2026-10-20):** Agree with the proposed arrangement, but this is an ambiguity to clarify rather than an unavoidable conflict. The old method stays on `io.github.ppissias.jtransient.core.SourceExtractor`, the compatibility facade. The new `io.github.ppissias.jtransient.extraction.SourceExtractor` has no tracking dependency or streak-shape method. Measurements belong in `image`; the decision belongs in tracking.

**Some allowed package edges follow today's call sites rather than the data those stages need.** `TrackLinker.filterTransients` calls `MasterVetoMask.build`, so the table allows `tracking` → `reference`. The mask is a `boolean[][]` built from master stars, width, height and jitter, and `MasterVetoMask` exists so the pipeline and the preview tools share one builder. If the engine passes the built mask in, tracking does not need the reference package, and mask construction stays in `reference`. `slowmover` → `reference` is required by the convenience overload `SlowMoverAnalyzer.analyze(cleanFrames, medianStackData, config)`, which calls `MasterMapGenerator.createMaximumMasterStack`. The analyzing method already receives both stacks. Stack construction can stay with the engine. `ResidualTransientAnalyzer.analyze` already takes detections only. Its package-private `collectAlreadyClassifiedPoints` takes `TrackLinker.Track` and `TrackLinker.AnomalyDetection`, and photometry's `Input.tracks` does the same in production. Those signatures become `residual` → `tracking` and `photometry` → `tracking` as soon as the packages split, which the table forbids. The delivery order places shared model contracts at step 5, after the tracking split, so the first tracking slice needs those signatures named as a temporary exception. Preview Frame/Blink navigation and annotations also pass.

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

## JA-11 Java baseline and staged artifact compatibility slice (2026-10-22)

Declared Java 21 via a toolchain and release target for all Java source sets. `check` now runs a public legacy consumer and JDK `jdeps` dependency checks for the adopted config/execution boundaries. The consumer can compile against an explicit previous engine JAR while running against current classes, checking representative public constructors/nested types, frame/detection identity, legacy fallback aliases, detached fallbacks, stack encoding and sequential engine reuse. It checks current classfile major version 65. This is a representative canary, not exhaustive ABI-diff coverage; remaining package seams get checks when introduced.

Coordinated SpacePixels additions provide a repeatable local unsigned staging/matrix script, selectable `jTransientVersion`, a validation-only library repository and actual resolved-version checks in both application and isolated headless consumers. Central staging rejects the validation repository. Normal ignored composite flags remain true; per-invocation false flags drive the no-composite checks. The source baseline is 1.0.5 at `4926ad9`; new code is 1.0.6. Both use real generated Maven metadata, not hand-written POMs or substituted project outputs. The validation script never uploads artifacts, changes preferences, edits Git or installs a runtime.

Actual Maven Central does not resolve the predecessor **unreleased** 1.0.5 coordinate. Local source-built compatibility therefore cannot certify public artifact availability; publication and verification of the intended public dependency pair remain open under JA-11. Existing 1.0.5 release notes are historical preparation notes, not evidence that it was released.

Validation: the matrix script completed end-to-end. Engine `check jar javadoc` passes **248 tests**, the old-JAR/new-engine binary consumer and bytecode architecture checks. SpacePixels 2026.10-04 passes all **433 cases** against each staged engine: 1.0.5 has five intentional skips (four opt-in integrations and the unavailable-new-capability case), while 1.0.6 has four opt-in skips. Each old/new pair also passes seven independent headless consumer cases in both Gradle and POM-only metadata modes (**28 cases** across the matrix). The release guard is verified to reject the compatibility repository before signing/staging. A JSON compatibility assertion and a focused provenance test keep runtime diagnostic sinks out of saved measurement data. Neither scientific defaults nor calculations change in this slice; JA-02's real-data comparisons remain the scientific baseline.

## JA-02 shared execution policy and resource ownership (2026-10-22)

Completed the remaining execution-policy seam. `ExecutionPolicy` exposes the existing CPU/scratch heuristic, with explicit owned/borrowed `ExecutionSession` lifetime. Engine extraction, stack row batches and photometry, plus calibrated crop/extraction/measurement tasks, share the active policy instead of escaping to the common pool or an independent CPU-sized pool. Managed nested batches execute inline; task-result ordering, identity and existing scientific algorithms are preserved. Legacy tuning remains scientific work on its caller thread. The standalone compatibility path still uses its original executor when no session is active.

Engines retain one-active-operation admission and active-close rejection. Session close rejects active scopes; idle owned executors are stopped/joined, borrowed executors survive closure, and borrowed sessions survive engine/tuner closure. Scopes are thread-confined/LIFO. Concurrent sessions do not reserve aggregate heap; explicitly sharing a bounded pool supplies an aggregate worker ceiling, not an allocation guarantee. Retained input/output/crop/mask buffers are excluded from scratch estimates. Borrowed orchestration must not occupy the same pool's unmanaged worker; managed independent re-entry on that pool is rejected. Tuners record the actual borrowed-session policy in effective options.

Validation includes controlled worker ceilings, ordered results, single-worker nested stacks (no deadlock), original-cause propagation with joined sibling cleanup, owned termination, borrowed survival, session restoration, invalid/overflow memory estimates and engine lifecycle. Existing cancellation, master/pipeline parity and tuner isolation tests remain green. Full engine and SpacePixels check results are recorded in the commit body.

Read-only scientific regression used all **153 prepared africano-reg frames**, enabled variable-star detection and the same default config. Before/after snapshots, including frame rejection, detailed findings/diagnostics, variable-star data and SHA-256 checksums of master/maximum/slow-mover stacks and masks, are byte-identical after excluding runtime fields: `f0b49f2887ffe2a318b52ae288d39ef9f56527401057c363824da54ad69e58f4`. Fresh one- and four-processor JVM configurations give that same snapshot. Reduced-grid real-data tuner outputs also retain the JA-04 byte-identical hash.

Instrumentation (single samples, not a performance guarantee): fresh JVM read/load/pipeline/serialization took **54.208 s** with `ActiveProcessorCount=1`, summed per-heap-pool peaks **1,927,735,416 bytes**, max heap **8,303,607,808 bytes**; with `ActiveProcessorCount=4`, **21.409 s**, summed pool peaks **2,919,235,584 bytes**, max heap **8,589,934,592 bytes**. These summed pool peaks are not a simultaneous whole-process high-water mark. ActiveProcessorCount also affects GC/JVM ergonomics, so the difference is a configuration trade-off, not isolated scheduler speedup or memory savings. The 61-MPix/8-GiB heuristic retains its tested two-worker result.

Additional large-frame comparison: six evenly spaced original FITS from the **klangwolke-2** session (about 61 MPix/frame), read through scratch hardlinks without copying/modifying source data. The pre-change engine at `8592d4b` and new engine used fresh four-processor, 8-GiB JVMs and the same harness/configuration. Scientific snapshots are byte-identical, SHA-256 `e2142b21d68eeeb5467daf2b7928090eb06ce41ef5fa1079ef5a076651bf06e0`. Before: **13.642 s**, summed heap-pool peaks **4,009,754,624 bytes**; after: **19.426 s**, peaks **4,632,608,768 bytes**. This single sample is slower and has higher measured pool peaks; the change establishes resource ownership/bounds, not a speed or memory optimization. Repeated controlled profiling is required to attribute the difference, and the complete 33-frame session was not benchmarked. No scientific difference is accepted or hidden as a performance trade-off.

## JA-04 tuner options slice (2026-10-22)

Cross-repository validation: the complete SpacePixels `check` passes, including seven isolated headless artifact-consumer tests and API/desktop publication checks. A direct no-composite 1.0.5 run is currently blocked because Maven Central does not resolve `io.github.ppissias.jtransient:jtransient:1.0.5`; this is a release-dependency gap, not a passing published-pair claim. The JA-11 release-contract slice will exercise staged old/new artifacts without composite substitution. Ignored local composite flags remain enabled; the maintainer's unrelated SpacePixels plan edits remain uncommitted.

Both tuner facades provide immutable `Options` and detached-array builders/accessors. All sample, crop, sweep, budget, scoring and jitter knobs are captured before callbacks or workers; successful and fallback results retain the effective policy. Existing entry points capture the compatibility static defaults once. Concurrent writes to those defaults during capture are not supported; explicit options require no global edits. Calibrated tradeoff replay uses the measured policy rather than current global grids/budgets. The legacy sink is synchronous and per-run, silent unless that operation enables debug. Core debug-context propagation remains active under JA-04/JA-02/JA-09.

The independent available-sample API is introduced in JTransient **1.0.6**. SpacePixels discovers this single optional capability without importing its new options types, so it still builds/runs with 1.0.5. The older adapter serializes SpacePixels legacy calls, restores globals after failure and retains the warning against unmanaged concurrent direct legacy calls. No Central publication is performed by this feature.

Validation: engine `check jar javadoc` passed all **240 tests**. Five new option tests cover concurrent independent sample/grids/budgets versus isolated controls, every builder/accessor array, per-run diagnostics and invalid policies. Existing jitter/snapshot/tradeoff tests pass. Three SpacePixels adapter tests pass (new path, old fallback restoration, cancellation). A read-only reduced-grid comparison used eight evenly spaced real frames from `C:/astrodata/Subs/africano-reg`, one 384-pixel crop, two detection sigmas, and the same base settings before/after. Both tuners' numeric configs, validation, candidates/choices and tradeoff snapshots match byte-for-byte: SHA-256 `130a2a83ba626d6a5e9a3564f26874ba2a2d2f54999be186fadccfcce909f60b`. Only elapsed/report text and additive option provenance were excluded; this is not a full-grid scientific-performance claim. Pipeline equivalence is rechecked with the execution slice. Runtime for the reduced-grid read/load/tune Gradle task was approximately 4–6 seconds; no memory-retention changes are claimed.
