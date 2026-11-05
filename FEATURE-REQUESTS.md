# Feature requests and algorithm proposals

The [slow-mover investigation archive](docs/research/slow-movers/README.md) preserves dated protocols, conclusions, numerical case tables and figures; its [code history](docs/research/slow-movers/CODE-HISTORY.md) separates delivered changes from research-only alternatives. Current algorithm behavior remains in [ALGORITHM.md](ALGORITHM.md), and pending decisions remain here.

Proposed changes to what JTransient detects, measures or tunes. How the code is organised is in [DESIGN-IMPROVEMENTS.md](DESIGN-IMPROVEMENTS.md), and application features in SpacePixels' [feature requests](../SpacePixels/FEATURE-REQUESTS.md).

These are not authorisations. Keep the existing algorithms until the stated evidence is available and the maintainer decides. An algorithm change goes one at a time, with recovery, false positives and run time measured on the reference datasets (see [DEVELOPER.md](DEVELOPER.md#real-data-checks)). Remove proposals when implemented or closed; describe implemented behaviour and current limitations in [ALGORITHM.md](ALGORITHM.md), and use git history for the proposal and its reviews. The binding declined decisions below remain in force.

## Open proposals

| ID | Status | Proposal, and the condition for revisiting it |
|---|---|---|
| [QC-1](#qc-1-star-count-rejection-envelope) | Deferred | Star-count rejection has no absolute or relative envelope floor. Counts now share a quality area, but small count changes can still matter. A new floor needs a recovery and false-retention study. |
| [PT-5](#pt-5-registration-quality-and-shared-jitter-tolerances) | Proposed | `maxStarJitter` serves several tolerances. The maintainer preferred a future registration-quality assessment over splitting the tolerances now. The fill-edge registration used by camera-fixed rejection is not a general registration-quality measurement. |
| [SM-F1](#sm-f1-temporal-confirmation-of-slow-mover-candidates) | Joint investigation with SM-F3 | Compare temporal confirmation with the existing gates and copied position review. Three-bin stacks and temporal acceptance remain experimental; frame support and stationarity do not establish motion. |
| [SM-F3](#sm-f3-improvements-to-maximum-stack-slow-mover-detection) | Partly implemented; science proposals open | Source-row caching and non-gating centroid/track review are delivered. Temporal rejection, broader default shapes, residual detection and automatic deduplication still need independent validation. |
| [SM-F4](#sm-f4-bright-star-residuals-in-faint-mover-searches) | Proposed experiment | Reduce structured bright-star residuals while preserving faint movers and paths crossing star wings; temporal-variance weighting remains unimplemented. |
| [SM-F5](#sm-f5-faint-mover-runtime-and-memory-scaling) | Proposed investigation | Speed up long/full-size searches within bounded memory; the cooperative time limit and explicit partial coverage are already implemented and acceptance-tested. |
| [SM-F6](#sm-f6-faint-mover-validation-and-candidate-review) | Open validation | Broader injections, independent null controls, deeper catalogue checks and review of unmatched candidates; empirical scores have no discovery-probability interpretation. |
| [SM-F7](#sm-f7-complementary-high-sensitivity-moving-object-searches) | Prototype follow-up | Temporal-bin proposals and PSF-filtered frame points remain experimental; integrate only after demonstrating useful recovery beyond digital tracking. |
| [XC-3](#xc-3-local-background-and-noise-for-frame-extraction) | Partly implemented; frame work deferred | Master-star extraction adds a local-sky pass. Per-frame extraction still uses one sky level and noise estimate. Preserve extended emission and propagate local noise consistently before proposing a frame-sky redesign. |
| [VS-9](#vs-9-selected-stars-and-the-linearity-check-minimum) | Deferred policy only; warning implemented | Check-B insufficient-count/range classification and separately validated retained-subset readiness; the completed star-cap warning is not pending. |
| [VS-11](#vs-11-photometry-strictness-defaults-and-recovery-review) | Four deferred investigations and two follow-ups remain | Late-frame population lifecycle, reason-specific LIMITED handling, spatial correction of comparison cutouts and brief/fast variability; the peak-removal check's open questions and the blank-sky factor in star selection. The initial audit and adopted VS-12 work are complete. |

The photometry backlog was reconciled with the completed VS-12 work on 2026-11-03. Only VS-9 and the deferred follow-up in VS-11 remain open. Implemented behavior belongs in [ALGORITHM.md](ALGORITHM.md#16-variable-star-photometry) and [RELEASE_NOTES.md](RELEASE_NOTES.md#108-unreleased); completed proposals and their detailed reviews remain in git history. Closed decisions below remain binding.

**Faint-mover delivery closed (2026-11-04).** The initial SM-F2 investigation and agreed digital-tracking delivery are complete, including the SpacePixels F25 integration. Only digital tracking is integrated; it remains experimental and off by default. Implemented behaviour and acceptance evidence are in [the algorithm reference](ALGORITHM.md#faint-mover-search), the [result contract](PIPELINE.md#jtransientenginerunpipeline), and the [SpacePixels manual](../SpacePixels/MANUAL.md#faint-movers-experimental). The completed proposal, prototype comparisons and reviews remain in git history. Remaining work is split into SM-F4 through SM-F7 below; the separate maximum-stack proposal SM-F3 remains open.

SpacePixels implements report-only possible continuations between local-rescue candidates and ordinary tracks, preserving all findings and competing associations; see [the manual](../SpacePixels/MANUAL.md#a-moving-object). The Lagoon review identifies forward-only growth and the local small-step direction check as the causes of its split. No engine linking/consolidation rule has changed; any such change still needs a separate decision and scientific validation.

## QC-1. Star-count rejection envelope

**Status and decision.** Deferred. The maintainer requested retaining the limitation for future evaluation; no floor or numerical allowance has been agreed.

**Problem.** `SessionEvaluator` sets the lower star-count limit to the session median minus the configured multiplier times the MAD-based spread. Unlike the other quality envelopes, this count envelope has no absolute or relative floor. In a stable session, a small spread can therefore make a small loss of stars sufficient to reject a frame. The historical illustration used a median near 1,500 stars, a limit of 1,485 and rejection of a frame with 1,480. This illustrates the policy concern; it is not a measured recovery loss on a reference dataset.

**Current boundary.** Counts now use the common quality area, so the older concern about comparing differently padded areas has been addressed. The remaining question is whether the statistical count envelope can still be too tight after that correction. Retaining a hazy frame can also increase false detections, so keeping more frames alone does not establish an improvement.

**Direction to evaluate.** Consider a minimum count-loss allowance, possibly relative to the session median. The historical 10 % suggestion was illustrative and remains uncalibrated. Preserve a meaningful distinction between quality strictness levels and establish the allowance from the corrected count measurements.

**Evidence needed.** Compare stable, cloudy and sparse sessions across strictness levels. Record newly retained and rejected frames, true-mover recovery, track gaps, false detections and run time. Include very small and nearly constant count distributions before choosing a formula or default.

**Recovered source:** [original finding, example and maintainer/reviewer decisions](https://github.com/ppissias/JTransient/blob/b37cecf519cbb73609ff56d30fcf00855b550908/docs/archive/code-review-2026-10.md#L491-L523). Current implementation: [SessionEvaluator](src/main/java/io/github/ppissias/jtransient/quality/SessionEvaluator.java).

## PT-5. Registration quality and shared jitter tolerances

**Status and decision.** Proposed for future assessment. The maintainer preferred reviewing registration quality as part of the quality checks. Splitting `maxStarJitter` into additional settings has not been approved.

**Expanded request (2026-11-02).** Investigate a general frame-quality registration check to find outliers, including whether the photometry registration measurements can be shared. Measure the additional computation and determine where the check belongs relative to extraction, frame rejection and photometry. Include translation, rotation/spatial residuals, sparse or crowded fields and uncertain matches. Prefer one authoritative measurement with appropriate consumers over repeating the same work. A photometry-specific verdict or fill-edge fit must not be adopted as a general rejection rule without validating its assumptions and false-rejection cost.

**Problem.** `maxStarJitter` participates in several related decisions: stationary-star mask growth, stationary-source tests and motion/linking tolerances. Increasing it to accommodate worse registration can consequently make smaller motions harder to distinguish from stationary structure. The earlier review illustrated a 3 px/frame source becoming harder to seed when the jitter allowance rises to 4 px. This is an example of the coupling, not evidence that accepting motion below the actual registration uncertainty would be safe.

**Direction to evaluate.** First establish whether a registration-quality measurement would identify sessions or frames whose stellar alignment is too poor for reliable detection. Inventory which uses of jitter represent measured uncertainty and which represent a detection-policy minimum. Decide whether a separate tolerance has value only after that distinction is understood. Preserve existing field meanings and profile compatibility while evaluating it.

**Current boundary.** The fill-edge `FrameRegistration` fit estimates the camera's translation and rotation for camera-fixed rejection. Its fit residual measures agreement with the image boundary; it does not measure the remaining stellar misalignment throughout the field. A future quality check needs evidence from suitable star correspondences or another justified measure of alignment accuracy.

**Evidence needed.** Compare well-registered and poorly registered sessions, spatially varying residuals, true slow movers and stationary false tracks. Check whether the proposed quality measure predicts failures and whether any tolerance change improves recovery without admitting registration noise as motion. Agree any new settings and migration separately.

**Recovered source:** [original coupling analysis and the decision to assess registration quality](https://github.com/ppissias/JTransient/blob/b37cecf519cbb73609ff56d30fcf00855b550908/docs/archive/code-review-2026-10.md#L792-L829). Current contracts: [registration and coverage](ALGORITHM.md#1-border-drift-diagnostics), [configuration](CONFIG.md).

## SM-F1. Temporal confirmation of slow-mover candidates

**Status and decision (2026-11-05).** The maintainer included SM-F1 in the continuing SM-F3 investigation and requested a critical review of every current slow-mover filter. Compare the scientific benefit and processing cost with the existing screens before introducing a confirmation stage or acceptance rule. A conclusion that the present filters should remain is a valid outcome; no change is required merely because a proposal exists.

**Relationship to completed work.** Copied original-frame positions and descriptive timed fits are available for review. SM-F3's scratch three-bin trajectory-prediction trial is relevant evidence, but it is not an implementation of the proposed robust temporal-bin stacks. Its losses on real moving injections and the later measurement/source-confusion results must inform this investigation; neither establishes that all temporal confirmation methods fail. Share the measurement, availability, uncertainty and gate-comparison work with [SM-F3](#sm-f3-improvements-to-maximum-stack-slow-mover-detection) rather than adding two independent confirmation stages.

**First joint milestone complete (2026-11-05).** The frozen individual/paired-gate study and a three-bin median-residual prototype evaluate 1,910 new cases on the existing observations. The bin fits are often unavailable, and the tested straight-middle veto loses a correctly associated curved history without removing associated shuffled controls. It is not adopted. The [measured results](ALGORITHM.md#maximum-stack-investigation) distinguish this one prototype from the broader temporal-confirmation proposal. Further confirmation work needs source separation, calibrated position/availability evidence and models that preserve curves/gaps; adding another hard cutoff to the present measurements is unsupported.

**Problem.** A swept shape in the maximum stack can resemble a slow mover without establishing motion. Stationary elongated sources, blends and artifacts can also leave extended footprints. The implemented per-frame support and stationary-likelihood screens add evidence about a candidate's presence and overlap, but do not measure its trajectory.

**Recovered design.** After the existing morphology filters, inspect a small region around each surviving candidate. Divide the retained sequence into early, middle and late groups, then form robust local stacks or estimate the source position in each group. A supported trajectory would have a consistent direction, approximately monotonic displacement and a middle position compatible with the endpoints. Compare its direction and displacement with the maximum-stack geometry. The original design also allowed measuring positions directly in individual frames as an alternative.

**Limits to resolve.** Maximum-stack extent and temporal displacement measure different representations of the source; seeing, extraction thresholds and temporal binning prevent treating their ratio as an exact identity. Define position uncertainties, minimum evidence and behavior when a group has no reliable position. Preserve retained-frame identities and use available timestamps when interpreting motion. Evaluate this as an optional confirmation step before deciding whether it should reject candidates.

**Evidence needed.** Measure real-mover recovery, false candidates, run time and peak memory against the current screens. Include faint movers with little displacement, irregular sampling and rejected frames, stationary elongated stars, close blends and one-frame transients. Synthetic injections can map sensitivity; real sequences are needed to assess whether the extra stage earns its complexity.

**Recovered source:** [optional confirmation, three-bin design and morphology-versus-motion caveats](https://github.com/ppissias/JTransient/blob/b37cecf519cbb73609ff56d30fcf00855b550908/docs/archive/slow-mover-design-2026-10.md#L803-L900). Current behavior: [slow movers](ALGORITHM.md#slow-movers).

## SM-F3. Improvements to maximum-stack slow-mover detection

**Status and decision (2026-11-04).** The maintainer selected this investigation after SpacePixels F26. Source-row caching (P4), copied temporal review evidence and post-tracking correspondence measurements are implemented without changing detection gates/settings or suppressing findings. Following the five development sessions and 975 broader controls, the authorised broader-shape validation now covers all 18 native fields and 3,666 new paired cases from the other 13 datasets. It establishes a recovery/control-load trade-off, not support for changing the default. The remaining scientific proposals stay open; implementation, measured results and limitations are in [ALGORITHM.md](ALGORITHM.md#maximum-stack-investigation).

**Review corrections.** The original 5–6 px motion corridor was an illustrative capsule calculation, not a general bound: footprint width is measured, and Prokne passes the current gates after about 16 px of actual motion. For 30 independent Gaussian samples the expected maximum is about 2.04 sigma above sky; `sqrt(2 log N) = 2.61` is only a leading asymptotic term. This does not establish a universal single-frame SNR limit. Subtracting one static median commutes with the maximum: `max(I - m) = max(I) - m`. It changes extraction contrast, but cannot change raw-frame aperture lock-on unless frame evidence also changes.

**Motion/streak follow-up.** The authorised next study and native-frame audit are complete; [results](ALGORITHM.md#maximum-stack-investigation) show that the intersecting bright streaks were already detected separately, but their contamination did not explain or veto the maximum-stack candidates. A frozen three-bin straight/quadratic prediction review reduces shuffled controls but loses 32/674 held-out moving recoveries, including curved/gapped paths. It remains a scratch diagnostic; no motion gate is adopted. Missing exposure lengths and poor temporal coverage remain unavailable, not inferred evidence. Severe blend/streak-overlap losses already occur during candidate construction, before confirmation.

**Position-measurement follow-up (2026-11-05).** The authorised candidate-local experiment is complete: 1,910 of 2,052 new cases on the same 18 observations are evaluable. On unchanged pre-frame-gate proposals, residual Gaussian fitting improves blended-source histories but loses some straight/curved/gapped histories and retains more shuffled controls. Its nominal covariance is strongly overconfident on the native-background injections; Prokne's native replay demonstrates no astrometric advantage. The [measured results and limits](ALGORITHM.md#maximum-stack-investigation) do not justify replacing raw evidence or adding a motion gate. Continue source separation, PSF/noise/availability and trail-crossing candidate construction as research; P1/P2/P3 remain open, and the default remains 2.8.

**Expanded investigation agreed (2026-11-05).** Include [SM-F1](#sm-f1-temporal-confirmation-of-slow-mover-candidates) and critically evaluate the complete existing maximum-stack selection: shared background/seed/grow/minimum-size extraction, data-area/fill handling, lower and upper axis ratios, fill factor, both median-overlap bounds, aperture SNR and localization, frame-support percentage, stationary clustering and every evidence-availability condition. Account for upstream retained-frame selection, shared jitter and downstream consumers, and distinguish report-only motion/track comparisons from actual gates. The [current-filter evidence review](ALGORITHM.md#maximum-stack-investigation) records the initial code audit and retrospective case inventory. Related digital-tracking gates are reviewed under SM-F6 with their own evidence, rather than transferring maximum-stack thresholds or conclusions.

**How to decide whether a filter earns its place.** Freeze the baseline, configurations, search domains, association definitions and fresh validation cases before examining new outcomes. Measure the effect of removing or varying one gate at a time, plus predeclared paired comparisons where two gates may cover the same failure mode. Retain the full proposal pool and evaluate downstream predicates for rejected proposals as well: ordered first-failure counters and experiments conditioned on surviving earlier gates cannot establish redundancy. Extraction changes require fresh extraction and mask construction; rescoring saved survivors alone cannot recover missing components. Include stationary/elongated sources, noise, flashes, shuffled histories, camera drift, blends, star/trail crossings, small displacements, curves/gaps, irregular cadence and missing evidence. An association definition that already requires 40% frame support cannot independently validate that threshold; use additional predeclared truth/error and availability measurements without building the tested threshold into success.

For each gate, report its distinct control rejection, moving-source gains/losses, unavailable cases, interactions, runtime and memory, with uncertainty and target/dataset dependence. Classify the conclusion as retain, remove, retune, replace or insufficient evidence, and explain it against the combined studies. A recommendation needs a useful demonstrated improvement and fresh validation, not an isolated attractive recovery count. No recommendation is required, and no scientific gate/default changes are authorised by the review itself. Any supported change is discussed separately and validated one at a time across the engine and application.

**First controlled milestone complete (2026-11-05).** The [19-policy comparison](ALGORITHM.md#maximum-stack-investigation) changes individual gates, selected pairs, extraction/mask construction and the aperture-SNR floor on new fixed tiles/histories. Source association no longer assumes 40% support. It demonstrates the support floor's cost on sparse/gapped signals and the hidden interaction of median overlap and stationarity. Because primary flash-control loads largely match repeated blank backgrounds, a separately frozen brighter-flash follow-up directly checks source attribution: removing support admits 30 controlled flash cases in the other cohort, versus zero with the current or 20% floor. Paired shape/fill removal can recover injected trail-crossing histories, but also admits more shuffled histories. The tested bin veto does not earn adoption. No scientific filter is shown removable or retunable without further validation; current defaults remain.

**Remaining decision evidence.** Prioritise support/measurement availability and source attribution on faint, gapped and overlapping paths, with fresh cases and explicit 3-8-frame controls before reconsidering a percentage or minimum-count rule. Preserve individual-frame raw evidence and conditional position errors; agreement within 2 px on a short path does not itself establish motion. Measure new policies against both all-source candidate load and source-specific independent controls, including stack-only/unknown-evidence callers, rather than choosing thresholds from the reviewed grid. Carry these limits into SM-F6's independent candidate/control validation and the eventual SM-F7 comparison; SM-F1/P1/P2/P3 remain open without blocking that broader validation.

**Remaining proposals and decisions.**

1. **P1 — A validated temporal gate.** Copied original-frame centroids and descriptive timed fits are available in production. Both the earlier correlation cutoff and the later midpoint/three-bin prediction trial lose known moving injections; camera-fixed-compatible whynot positions also have `abs(r) > 0.999`. Correlation or a fixed prediction-error limit is not calibrated confidence. Before another veto proposal, address centroid/source lock-on and measurement uncertainty, exposure/timing availability, seeing/registration trends, limited temporal coverage and the camera-drift/sky-motion degeneracy. Validate on fresh held-out cases and preserve curved/gapped motion. Per-frame raw-footprint streak contact can explain contamination without asserting identity; preserve real movers crossing trails rather than applying a whole-candidate streak veto. No temporal, camera-coordinate or streak-contact cutoff is implemented.

2. **P2 — Broader shapes with explicit duplicate ownership.** The held-out comparison of 2.8 versus 5 raises moving-injection recovery from 371/2,080 to 670/2,080, but shuffled-position survivors also rise from 201/1,248 to 554/1,248. Across all 18 fields it adds six candidates: Valeria already has an agreeing 27-frame ordinary track; the others show trail/camera contamination or lack a confirmed moving identity. `SlowMoverTrackMatcher` supplies post-tracking spatial screening and actual common-frame distances, including ambiguous-ID/time counts, without claiming identity. The authorised validation is complete; retain 2.8 as the default and the existing manual shape control. Before any default change or automatic duplicate suppression, validate temporal/camera evidence and define adequate coverage, uncertainty-normalized agreement and competing-track ambiguity. Crossings, curved/gapped paths, reversed/parallel/partial tracks and ambiguous IDs remain required controls.

3. **P3 — An additional residual route, not a replacement.** At the current axis limit, residual extraction with raw-frame evidence recovers only 25/150 injections; residual extraction plus residual frame evidence recovers 35/150, compared with 38/150 currently. With the wider axis limit, the full residual route recovers 60/150 but produces many additional full-field candidates (108 in M81). None of the three approximately magnitude-18.5 Klangwolke-2 targets is recovered by the frozen maximum-stack variants. Evaluate an additive route, local residual/PSF normalization, self-subtraction at low displacement, star-crossing lock-on and independent negative controls. Do not simplify median-mask vetoing or describe residual subtraction as removing all star wings/gradients. [SM-F4](#sm-f4-bright-star-residuals-in-faint-mover-searches) and [XC-3](#xc-3-local-background-and-noise-for-frame-extraction) remain separate work.

**Before scientific gate/default changes.** Freeze fresh held-out cases and an uncertainty/coverage-aware association policy; measure incremental recovery, unverified candidate load, runtime and memory one algorithm change at a time. The initial controls, 975-case grid and later 3,666-case validation do not establish a survey false-positive rate. Do not select a new rule on those reviewed outcomes and then describe them as an independent test of it. No science/default change or claim of motion is justified solely by these counts. The implemented measurements are for review, with both findings preserved.

## SM-F4. Bright-star residuals in faint-mover searches

**Status and decision.** Proposed experiment following the completed SM-F2/F25 delivery. No weighting rule, extra setting or default change has been agreed.

**Current boundary.** Digital tracking already masks star cores, registration fill and streaks, clips residuals, checks half-session support, compares stationary and camera-fixed stacks, and rejects duplicates, tracked objects and side lobes. Temporal-variance weighting near bright stars is still absent, and structured stellar residuals can survive the checks. The [deeper Klangwolke-2 review](ALGORITHM.md#faint-mover-validation) now finds compatible predictions for all 11 paths, including the five previously unmatched above the earlier magnitude limit. Star-wing contamination remains visible, but those unmatched labels are not negative truth. Aggressive masks can also remove real movers crossing stars.

**Investigation.** Compare temporal-variance weighting and improved stationary/PSF residual modelling with the present method. Measure how the estimator responds to seeing, registration errors, correlated noise and real moving-source flux; the source being sought must not be down-weighted merely because it moves. Coordinate noise definitions with [XC-3](#xc-3-local-background-and-noise-for-frame-extraction). Preserve the blind search and the existing score/evidence interpretation unless a separate change is agreed.

**Acceptance.** Use original full-size frames, known and injected movers across star wings, blends, masks and tile seams, and independent positive/negative controls. Compare recovery, control survivors, retained support, runtime and memory on Klangwolke-2 and other reference fields. Freeze the proposed rule before held-out validation, document any threshold recalibration, and preserve existing outputs with the experimental stage off.

## SM-F5. Faint-mover runtime and memory scaling

**Status and decision.** Proposed investigation. Hierarchical shift-and-add or another acceleration is not implemented. The time limit, memory/trial guards, cancellation, tile progress and partial-coverage reporting are already delivered.

**Current boundary.** Cost grows with coarse motions × searched frames × pixels, and margins grow with session duration and maximum speed. In acceptance, M81 Kumar's 600 s budget ended after about 605 s with 18 of 110 tiles completed and zero retained candidates; this is partial coverage. UG 6524 completed all 36 tiles in about 276 s. Raising a time limit is already supported and does not itself improve scaling.

**Investigation.** Prototype hierarchical combination of time subsets or another justified reuse of filtered/shifted data. Measure the time/memory trade-off before choosing an algorithm or adding controls. Preserve the search domain, original frame identities, masks, temporal support, evidence availability and deterministic ordering. Coordinate SpacePixels' resource estimate with an agreed engine plan.

**Acceptance.** Compare full-resolution Klangwolke-2, M81 Kumar, UG 6524, m101-reg and m81-reg with bounded workers and heap. Record complete versus partial area, recovery and control survivors within a fixed budget, elapsed time, peak memory, cancellation latency and tile-boundary behavior. A computation-only optimisation must preserve scientific values; an approximation that changes them needs separately agreed recovery and false-candidate evidence. Keep the current resource guards and cooperative limit effective.

## SM-F6. Faint-mover validation and candidate review

**Status and decision.** Open validation following the first experimental delivery. No universal false-candidate rate, calibrated significance or discovery probability has been established for the score threshold.

**Established baseline.** Full-size Klangwolke-2 acceptance reproduces all 11 reference candidates, including six paths compatible with saved predictions, while ordinary results are unchanged. The 18-dataset off-mode regression, searched-frame identities, unavailable evidence, skip/partial reporting and application exports/viewers have been checked. Klangwolke-1 correctly skips its seven-frame largest kept session; UG 6524 completes with zero candidates. M81 Kumar's time-limit fix is acceptance-tested, but its partial zero-candidate result does not assess the unsearched field or replace review of the six candidates from the earlier, longer pre-fix run.

**Candidate-review milestone (2026-11-05).** A new full-field production replay again reproduces all 11 K2 candidates. Separately recorded magnitude-23 candidate-local predictions supply compatible paths for the five previously unmatched findings, using the existing position/motion tolerances on all 28 searched frames and the admitted corrected WCS. All 11 now have compatible known-object predictions; original-pixel blinks, disjoint raw coadds and mask/scoring evidence retain source-contamination context. [Results and limitations](ALGORITHM.md#faint-mover-validation) distinguish this from identity confidence, independent subset rediscovery, passband calibration or catalogue completeness. No original report, detection, threshold or classification policy changes.

**Matched-domain milestone complete (2026-11-05).** The [462-search study](ALGORITHM.md#faint-mover-validation) completes all fixed native/null/injection footprints on six existing observations, including M81 at its unchanged full speed range. Seventeen policies test individual gates and selected interactions; production/research parity and all 77 cached/uncached K2 comparisons are exact. Baseline recovery is 82/170 available moving histories. Mask/clipping/score relaxations have measured control costs; removing track/side-lobe checks adds candidates without improving source recovery in the separate 12-encounter follow-up. Three blind disjoint K2 thirds rebuild their own medians and retain unmatched/kinematically uncertain outcomes. Current gates/defaults remain. This completes a bounded mechanism/coverage milestone, not universal sensitivity, independent-night validation or confidence calibration.

**Next decision evidence.** Prioritise source/proposal separation at star crossings and closer/parallel paths, steady-camera controls whose paths actually fit the searched motion model, score/mask boundary cases, and independent observations. The camera margin is implied by the share check at scores at least 4 but can differ for valid lower settings; zero effect at the studied floors does not justify deleting its general behavior. Resolve capped candidate loads and missing review evidence before stronger load/sensitivity claims, distinguishing them from the already associated planned sources in those capped fields. Keep upstream QC/background/input-validity rules outside claims from gates that were actually ablated. Maximum-stack 3-8-frame support/uncertainty experiments remain separate SM-F1/SM-F3 work.

**Remaining investigations.**

SpacePixels F26 now supplies bounded residual blinks, half-stack maps and actual scoring-path sample/mask evidence for review. These views do not complete the independent validation tasks below or turn empirical scores into significance estimates.

1. Extend original-frame/subset and deeper-prediction review beyond the completed K2 catalogue milestone, using SpacePixels' existing configurable catalogue fetch. Record magnitude limit, passband, timing/WCS/ephemeris uncertainty, fetch completeness and ambiguous matches. Catalogue absence does not establish a new object or a false detection; catalogue information remains a comparison after blind detection.
2. Expand independent shuffled-time, sign-reversed-residual and other justified null controls across datasets and seeds. M81 Kumar still needs a shuffled-time control. Account for real bright movers turned into structured artifacts by a control; control survivors are not automatically a calibrated false-positive rate for ordinary data.
3. Broaden moving-source injections over flux, speed, direction, cadence, gaps, field position, blends, PSF/seeing and registration quality. Test masks, reference self-subtraction and correlated noise, and hold validation examples out of rule/threshold selection. Report recovery and control-survivor uncertainty against the actually searched domain.
4. Review the longer M81 Kumar results or run a justified complete/fixed-domain evaluation. Compare controls and injections on matching footprints when time limits truncate a search. Preserve reasons for unavailable support and distinguish unreviewed candidates from confirmed artifacts.

5. Critically evaluate each existing digital-tracking selection mechanism alongside the joint SM-F1/SM-F3 review: session/motion-domain selection, residual normalization/clipping and masks, coarse proposal/refinement and score cuts, half-session support/availability, stationary and camera-fixed comparisons, duplicate removal, ordinary-track association and single-frame side-lobe rejection. Keep timing/heap/candidate limits and incomplete coverage distinct from scientific rejection. Use matched searched domains and predeclared individual/paired gate comparisons, including distinct close/parallel/crossing paths, masked halves and real movers near stronger objects. Measure incremental benefit and losses; shared inputs or similar purposes alone do not establish redundancy. Apply the same evidence-based retain/remove/retune/replace/insufficient-evidence decision rule, with no obligation to change a gate.

**Acceptance.** Produce a traceable sensitivity and control-survivor study with frozen configurations, coverage and resource limits. Keep empirical scores and unverified labels until independent evidence supports stronger claims. Any new threshold, default or classification policy requires agreement and a regression of the unaffected pipeline outputs. Catalogue/report comparisons remain [application-owned](../SpacePixels/MANUAL.md#known-asteroids-and-comets); the implemented field table and clock diagnostic do not calibrate faint-search completeness or candidate confidence.

## SM-F7. Complementary high-sensitivity moving-object searches

**Status and decision.** Prototype follow-up from the completed three-method investigation. Production currently integrates digital tracking only. The maintainer's request was to investigate all three approaches with real full-size data and potentially retain them; integration of either additional mechanism still needs evidence and agreement.

**Methods to evaluate.**

1. **Temporal-bin proposals followed by full-sequence verification.** Form time groups and propose paths from weak points or short trails, then verify/refine against the original complete sequence. Allow missing or blended bins: demanding a detection in every bin lost one of the original faint examples. Measure objects lost before verification as proposal failures, not rejected verified paths.
2. **PSF-filtered frame extraction and linking.** Match the spatial footprint before extracting weak per-frame evidence, estimate the filtered/correlated noise consistently, then link and verify timed paths. Existing raw-pixel sigma thresholds cannot be reused unchanged after filtering.

**Current evidence and constraints.** All three prototypes recovered the original three magnitude-18.5 Klangwolke-2 examples in full-field searches; digital tracking gave the strongest recovery in the subsequent injection comparisons and was selected for the first delivery. The detailed prototype comparisons and review remain in git history. Proposed mechanisms must search independently of catalogue positions, retain generating-mechanism and temporal-support provenance, and keep fitted positions distinct from individual detections. Coordinate artifact handling and calibration with SM-F4 and SM-F6, and maximum-stack temporal confirmation with [SM-F1](#sm-f1-temporal-confirmation-of-slow-mover-candidates)/[SM-F3](#sm-f3-improvements-to-maximum-stack-slow-mover-detection).

The [SM-F6 matched-domain milestone](ALGORITHM.md#faint-mover-validation) now supplies an unchanged production baseline and exposes star-crossing/close-passage losses without supporting blanket gate removal. Begin the two proposal-method comparisons separately, retaining the existing full-sequence verifier and matched footprint/motion/resource reporting. Fresh frozen examples must assess incremental source recovery and control load beyond digital tracking, with missing bins allowed and rejected/absent proposals preserved. The already reviewed six-field grid and 12 targeted encounters can diagnose mechanisms but must not become threshold-selection data presented again as independent validation.

**Acceptance and application handoff.** Compare incremental recovery beyond digital tracking on the same full-size frames, domains, time/resource budgets, held-out injections and controls. Integration needs a useful demonstrated benefit after duplicate removal, with runtime and memory justified; repeated detections of the same paths do not establish added sensitivity. Agree the engine result/settings contract and corresponding SpacePixels controls, resource guidance, mechanism labels, CSV/JSON evidence and searched-frame overlays together. Additional mechanisms remain off by default if adopted; broader motion models or known-object-seeded searches are outside this proposal.

## XC-3. Local background and noise for frame extraction

**Status and decision.** Partly implemented for the master reference; a broader per-frame redesign remains deferred.

**Problem.** Per-frame extraction uses one sky level and noise estimate. A strong sky gradient can therefore produce different effective sensitivity across the field: excess detections on the bright side and missed faint sources on the dark side. Thresholds, significance and flux/background calculations must remain consistent if their sky model changes.

**Additional maintainer example (2026-11-04).** The current M81 Kumar report is reported to show a sky gradient with uneven effective sensitivity across the image. This matches the scope of XC-3 rather than requiring a duplicate proposal. Review that existing report, map the background/noise and detections across the gradient, and distinguish background bias from noise, masking or seeing before choosing a model. The report analysis is pending; its machine-local reference is retained in the ignored environment notes. This example does not change the deferred implementation decision.

**What is already addressed.** The common data areas handle registration fill separately. Master-star extraction now combines its original global-sky pass with a local-sky pass to find stationary sources that the global pass misses. The first pass also preserves coverage of extended emission that the per-frame extractor can detect. Photometry already estimates local sky for its own measurements. These changes narrow the remaining proposal to the extraction paths that still use a global estimate.

**Direction to evaluate.** Assess a spatial background and noise model, such as a robust mesh, for those frame paths. Define mesh scale, source masking, interpolation and treatment of extended emission before changing extraction. Propagate the corresponding local noise into seed/growth thresholds and reported significance; subtracting a smooth sky while retaining an unrelated global noise estimate needs separate justification. Reassess master/frame consistency if the frame detection model changes.

**Evidence needed.** Compare gradients, nebulosity, crowded fields, dark regions and ordinary flat backgrounds. Measure true-source and mover recovery, false detections, stationary-mask coverage, tuner behavior, run time and memory. A gain on one gradient must be weighed against lost extended structure and changed threshold calibration.

**Recovered source:** [original global-background limitation and review caveats](https://github.com/ppissias/JTransient/blob/b37cecf519cbb73609ff56d30fcf00855b550908/docs/archive/code-review-2026-10.md#L1449-L1473). Current implementation: [SourceExtractor](src/main/java/io/github/ppissias/jtransient/core/SourceExtractor.java), [MasterReferenceAnalyzer](src/main/java/io/github/ppissias/jtransient/core/MasterReferenceAnalyzer.java).

## VS-9. Selected stars and the linearity-check minimum

**Status (reviewed 2026-11-03).** Deferred readiness-policy work only. The star-cap warning is implemented: `DetectionConfigRules.minimumStarsForLinearityCheck` accounts for Check B's 90 % rank bound, so caps below 55 warn for the default 50 references. Do not keep that completed warning as an open task.

**What remains.** Decide how Check B should classify an insufficient counted linear range or reference population, and whether a separately validated retained subset can be scored when the original session fails. Preserve the distinction between insufficient evidence and measured nonlinearity, the required reference count, and the original-session diagnosis. This is the unfinished VS-12 4B investigation, not permission to relax readiness.

**Current behavior and evidence.** The initial shortage of high-SNR shape measurements already gives INCONCLUSIVE. After the brightness/range restriction, insufficient counted references or magnitude span can still give FAIL without a measured shape departure. Fifty ideal selected stars supply only 45 counted references; 55 can supply 50, before ties, flags, noise and genuine nonlinearity remove more. The warning is a lower bound, not a guarantee that a field is suitable.

In the completed study, no reference session failed solely for too few counted stars. Reclassifying M81 Kumar's five short-range frames did not cross the session failure threshold, and those frames remained excluded. A retained-subset probe made Helix score 171 stars from 28 surviving frames and found no candidate. That evidence did not justify changing the policy; the maintainer deferred it.

**Evidence needed before a decision.** Test known-linear images with controlled bad-frame fractions, real response faults, sparse and tied samples, caps around 50/55, limited magnitude range and flagged/low-SNR measurements. Compare FAIL/INCONCLUSIVE denominator effects, original and retained-subset readiness, variable recovery and false candidates. Keep the minimum-final-frame guard and a bounded validation sequence; do not iteratively reject/refit until the session becomes READY.

Current contracts: [shape linearity](ALGORITHM.md#photometry-8-3), [readiness](ALGORITHM.md#photometry-10) and [configuration validation](src/main/java/io/github/ppissias/jtransient/config/DetectionConfigRules.java).

## VS-11. Photometry strictness, defaults and recovery review

**Status (reviewed 2026-11-03).** The initial audit and the adopted VS-12 work are complete. Only the four deferred investigations and the two follow-ups below remain here; the fifth, low-count/retained-subset readiness, is owned by [VS-9](#vs-9-selected-stars-and-the-linearity-check-minimum). Investigation is authorised; a scientific-policy or default change needs evidence and a maintainer decision. Completed rules are described in [ALGORITHM.md](ALGORITHM.md#16-variable-star-photometry) and [the release notes](RELEASE_NOTES.md#108-unreleased); their old plans, reviews and implementation diary remain in git history.

### Late-frame population lifecycle

**Deferred (former VS-12 4A).** Check D and spatial rejection occur after the star-population and contamination statistics have been formed. Define whether a bounded recomputation on the final active frames would improve qualified stars or contamination decisions without introducing a rejection/refitting loop.

The study found these late exclusions only in NOT_READY sessions, so none of the five scored reference sessions could benefit. Recomputing in the other sessions changed some star qualifications and flags but did not demonstrate recovered valid variables. Revisit with a READY/LIMITED session that has late exclusions. Preserve early/final reasons, original frame identities, the reference convention and stopping behavior; compare recovery, false candidates and cost before selecting a policy.

Current behavior: [contaminated shape](ALGORITHM.md#photometry-7-1) and [spatial frame exclusion](ALGORITHM.md#photometry-9-4).

### Reason-specific LIMITED handling

**Deferred (former VS-12 4D).** Evaluate whether different readiness limitations warrant different variability policies. Do not replace the LIMITED amplitude floor globally.

All three LIMITED sessions in the study had a restricted Check-B magnitude range: M81 Kumar 2.14 mag, m5-reg 2.19 and UG 6524 2.77. M81 Kumar also lacked Check-D transparency leverage, but none was limited for that reason alone. No then-reported candidate failed the 0.10-mag amplitude floor, so no gain was demonstrated. Use physical 0.05–0.10-mag variable injections under stable transparency, clipping, inadequate shape range and genuine response faults, with false-candidate controls, before recommending reason-specific treatment.

Current behavior: [readiness verdict](ALGORITHM.md#photometry-10) and [candidate gates](ALGORITHM.md#photometry-11-4).

### Spatial correction of comparison cutouts

**Deferred (former VS-12 4H; application presentation).** Brightest/faintest cutouts match the local background and zero point but omit the spatial-plane difference at the star. The study measured a median 0.003 mag and a maximum 0.020 mag across reported candidates; this did not justify extra display-model plumbing.

If a visible case appears, choose an explicit main-reference or selected-solution display contract. Apply the corresponding `Z + a*x + b*y` difference using the star's original normalised coordinates, and handle unavailable corrections explicitly. A main-plane addition must not claim exact selected-curve calibration. Test gradients, captions and unchanged measurements before adopting a display change.

### Brief and fast variability

**Deferred (former VS-12 4I).** The conservative search loses single-frame events, very brief dips/flares and signals alternating on a few-frame cadence. The limits are documented; a new event statistic or parallel discovery path is not implemented.

The earlier brief-event probe recovered some short injected dips but encountered heavy-tailed excursions in real constant stars, with little useful recovery on several fields. The subsequent `PeakRemovalCheck` now flags one demonstrated cause: star peaks removed before registration, producing short fadings within a pointing. That completed artifact check does not implement a broader brief/fast-variable search. Re-establish the residual-artifact baseline on the current outputs before testing an alternative.

Use image-level variables and realistic negative controls over amplitude, period, event duration, phase, cadence, missingness, seeing, sky and blends. Compare high-confidence and possible recovery, false candidates per field/session and processing cost. Preserve original capture identities/times, distinguish unavailable evidence from measured inconsistency, and do not lower all correlated gates or impose the previously rejected cadence-gap rule. The adopted gap change remains limited to outlier support.

Current behavior: [light-curve cleaning](ALGORITHM.md#photometry-11-1), [scores](ALGORITHM.md#photometry-11-2) and [temporal coverage](ALGORITHM.md#photometry-11-5).

### Peak-removal check follow-up

**Open (2026-11-03).** `PeakRemovalCheck` flags M81 Kumar's peak-clipped fadings. Two questions remain:
- *Klangwolke-2's imbalance.* The session has 393 concentrated brief fadings against 180 brightenings (2.2 to 1), below the 5-to-1 session gate. Whether a milder form of peak removal or a bias of the one-pixel test causes it was not examined.
- *Residual artifacts on M81 Kumar.* Five one-pointing fadings (stars 429, 634, 701, 1225 and 2412) remain possible candidates. Their lost light is concentrated against one nearest clean pointing but not the other.
  - Comparing against more references catches more of the artifact fadings (80–82 % against 72 %), but flags 9–15 % of synthetic real dimmings instead of 3 %. The current rule was chosen to protect genuine eclipses.
  - A reference matched to pixel phase would need sub-pixel registration, which the fill fit does not provide.

Current behavior: [light-curve cleaning](ALGORITHM.md#photometry-11-1).

### Blank-sky factor in star selection

**Deferred (former VS-12 4F option (c)).** The blank-sky factor scales only the reported errors: option (b), chosen 2026-11-03. Using the corrected errors in the median-SNR star cut and in Check B's choice of high-SNR shape stars would make those cuts mean the same in every session; on m5-reg and Eagle-noisy the true SNR is 2.3–2.9 times lower than the formula's. It would, however, change which stars are scored and possibly the readiness verdicts. Evaluate it with the 18-dataset regression and a review of `photometryMinSnr` before adopting.

Current behavior: [errors and flags](ALGORITHM.md#photometry-6-3).

**Boundaries.** No completed or excluded VS proposal is reopened by this cleanup. Weighted Check-D tracking and per-candidate ensemble refits were closed after the VS-12 study and remain recorded under [Declined](#declined). General registration quality/recentring belongs to [PT-5](#pt-5-registration-quality-and-shared-jitter-tolerances); catalogue-calibrated magnitudes belong to [SpacePixels F24](../SpacePixels/FEATURE-REQUESTS.md#f24-calibrated-magnitudes-for-variable-stars). Keep the current empirical noise model and reported-error contract unless a separate evidence-backed change is agreed.

Fixed-camera detection is a coordinated application/engine investigation under SpacePixels [F23](../SpacePixels/FEATURE-REQUESTS.md#f23-fixed-camera-and-all-sky-sequence-analysis); its input contract and algorithms are to be designed before implementation.

## Declined

These decisions are binding. Do not propose them again without new evidence.

| ID | Decision |
|---|---|
| IN-3 | Tracking returns no categories when fewer than three frame lists remain; slow movers and photometry are separate stages. Do not reinterpret this as an instruction to recover one- or two-frame tracks. |
| ST-4 | Keep the existing streak-chain linking without a new global common-line or distance gate. |
| PT-1 | Keep the geometric rhythm check's distance-based rule; it makes no capture-time assumption. |
| PT-3 | Keep timed tracks that can bend; no final straightness requirement. |
| SM-1 | No drift-path veto on maximum-stack slow-mover candidates. Camera-fixed point filtering does not implement this proposal. |
| PH-1 | Slow-mover footprints continue to flag nearby photometry stars across frames. |
| EX-3 | The extraction FWHM keeps its second-moment convention (about √2 above a round Gaussian FWHM); documented. Photometry and SpacePixels' Night & Equipment measure size separately. |
| XC-1 | Integrated significance assumes uncorrelated pixels, and photometry errors omit source photon noise; documented. Do not insert a numerical correlation correction without blank-aperture evidence. |
| VS-12 4C | Do not add weighted Check-D slope tracking without new evidence: the study changed no readiness verdict, and the existing per-frame slope rule already uses its error. |
| VS-12 4G | Do not add per-candidate ensemble refits without a demonstrated self-leverage problem: in the 151 held-out tests the in-fit amplitudes were at least 99.7 % of the held-out ones, with substantial extra cost for refitting. |
