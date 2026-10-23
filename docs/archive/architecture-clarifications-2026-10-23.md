# Architecture explanations and coordinated cleanup review

Historical questions and answers recorded before the coordinated cleanup. The implementation now uses ordinary copy-sorting APIs, structured throwing failures, fixed immutable tuner defaults and explicit diagnostic scopes; obsolete method names below describe the earlier state. Use [PIPELINE.md](../../PIPELINE.md) for the current contract and [the active plan](../../DESIGN-IMPROVEMENTS.md) for pending work. This archive preserves explanations, not compatibility obligations.

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
