# Feature requests and algorithm proposals

Proposed changes to what JTransient detects, measures or tunes. How the code is organised is in [DESIGN-IMPROVEMENTS.md](DESIGN-IMPROVEMENTS.md), and application features in SpacePixels' [feature requests](../SpacePixels/FEATURE-REQUESTS.md).

These are not authorisations. Keep the existing algorithms until the stated evidence is available and the maintainer decides. An algorithm change goes one at a time, with recovery, false positives and run time measured on the reference datasets (see [DEVELOPER.md](DEVELOPER.md#real-data-checks)). Remove proposals when implemented or closed; describe implemented behaviour and current limitations in [ALGORITHM.md](ALGORITHM.md), and use git history for the proposal and its reviews. The binding declined decisions below remain in force.

## Open proposals

| ID | Status | Proposal, and the condition for revisiting it |
|---|---|---|
| [QC-1](#qc-1-star-count-rejection-envelope) | Deferred | Star-count rejection has no absolute or relative envelope floor. Counts now share a quality area, but small count changes can still matter. A new floor needs a recovery and false-retention study. |
| [PT-5](#pt-5-registration-quality-and-shared-jitter-tolerances) | Proposed | `maxStarJitter` serves several tolerances. The maintainer preferred a future registration-quality assessment over splitting the tolerances now. The fill-edge registration used by camera-fixed rejection is not a general registration-quality measurement. |
| [SM-F1](#sm-f1-temporal-confirmation-of-slow-mover-candidates) | Proposed | Three-bin temporal stacks, or a measured slow-mover path, are not implemented. Compare real-mover recovery, false positives and run time before introducing either; the current frame-support and stationarity screens do not establish motion. |
| [SM-F3](#sm-f3-improvements-to-maximum-stack-slow-mover-detection) | Proposed | Review of the maximum-stack slow-mover branch: narrow motion corridor (5.0–6.3 px), lack of temporal progression check, and lock-on to stationary stars. Proposes temporal correlation, widened axis ratio, residual maximum stack, and row hoisting. |
| [SM-F4](#sm-f4-bright-star-residuals-in-faint-mover-searches) | Proposed experiment | Reduce structured bright-star residuals while preserving faint movers and paths crossing star wings; temporal-variance weighting remains unimplemented. |
| [SM-F5](#sm-f5-faint-mover-runtime-and-memory-scaling) | Proposed investigation | Speed up long/full-size searches within bounded memory; the cooperative time limit and explicit partial coverage are already implemented and acceptance-tested. |
| [SM-F6](#sm-f6-faint-mover-validation-and-candidate-review) | Open validation | Broader injections, independent null controls, deeper catalogue checks and review of unmatched candidates; empirical scores have no discovery-probability interpretation. |
| [SM-F7](#sm-f7-complementary-high-sensitivity-moving-object-searches) | Prototype follow-up | Temporal-bin proposals and PSF-filtered frame points remain experimental; integrate only after demonstrating useful recovery beyond digital tracking. |
| [XC-3](#xc-3-local-background-and-noise-for-frame-extraction) | Partly implemented; frame work deferred | Master-star extraction adds a local-sky pass. Per-frame extraction still uses one sky level and noise estimate. Preserve extended emission and propagate local noise consistently before proposing a frame-sky redesign. |
| [VS-9](#vs-9-selected-stars-and-the-linearity-check-minimum) | Deferred policy only; warning implemented | Check-B insufficient-count/range classification and separately validated retained-subset readiness; the completed star-cap warning is not pending. |
| [VS-11](#vs-11-photometry-strictness-defaults-and-recovery-review) | Four deferred investigations and two follow-ups remain | Late-frame population lifecycle, reason-specific LIMITED handling, spatial correction of comparison cutouts and brief/fast variability; the peak-removal check's open questions and the blank-sky factor in star selection. The initial audit and adopted VS-12 work are complete. |

The photometry backlog was reconciled with the completed VS-12 work on 2026-11-03. Only VS-9 and the deferred follow-up in VS-11 remain open. Implemented behavior belongs in [ALGORITHM.md](ALGORITHM.md#16-variable-star-photometry) and [RELEASE_NOTES.md](RELEASE_NOTES.md#108-unreleased); completed proposals and their detailed reviews remain in git history. Closed decisions below remain binding.

**Faint-mover delivery closed (2026-11-04).** The initial SM-F2 investigation and agreed digital-tracking delivery are complete, including the SpacePixels F25 integration. Only digital tracking is integrated; it remains experimental and off by default. Implemented behaviour and acceptance evidence are in [the algorithm reference](ALGORITHM.md#faint-mover-search), the [result contract](PIPELINE.md#jtransientenginerunpipeline), and the [SpacePixels manual](../SpacePixels/MANUAL.md#faint-movers-experimental). The completed proposal, prototype comparisons and reviews remain in git history. Remaining work is split into SM-F4 through SM-F7 below; the separate maximum-stack proposal SM-F3 remains open.

The Lagoon track/local-rescue association request is captured as [SpacePixels F27](../SpacePixels/FEATURE-REQUESTS.md#f27-associate-local-rescue-candidates-with-moving-tracks). Its local report reference is retained in the ignored environment notes; any engine linking/consolidation change awaits investigation and agreement.

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

**Status and decision.** Proposed. Compare the scientific benefit and processing cost before introducing a new confirmation stage or acceptance rule.

**Problem.** A swept shape in the maximum stack can resemble a slow mover without establishing motion. Stationary elongated sources, blends and artifacts can also leave extended footprints. The implemented per-frame support and stationary-likelihood screens add evidence about a candidate's presence and overlap, but do not measure its trajectory.

**Recovered design.** After the existing morphology filters, inspect a small region around each surviving candidate. Divide the retained sequence into early, middle and late groups, then form robust local stacks or estimate the source position in each group. A supported trajectory would have a consistent direction, approximately monotonic displacement and a middle position compatible with the endpoints. Compare its direction and displacement with the maximum-stack geometry. The original design also allowed measuring positions directly in individual frames as an alternative.

**Limits to resolve.** Maximum-stack extent and temporal displacement measure different representations of the source; seeing, extraction thresholds and temporal binning prevent treating their ratio as an exact identity. Define position uncertainties, minimum evidence and behavior when a group has no reliable position. Preserve retained-frame identities and use available timestamps when interpreting motion. Evaluate this as an optional confirmation step before deciding whether it should reject candidates.

**Evidence needed.** Measure real-mover recovery, false candidates, run time and peak memory against the current screens. Include faint movers with little displacement, irregular sampling and rejected frames, stationary elongated stars, close blends and one-frame transients. Synthetic injections can map sensitivity; real sequences are needed to assess whether the extra stage earns its complexity.

**Recovered source:** [optional confirmation, three-bin design and morphology-versus-motion caveats](https://github.com/ppissias/JTransient/blob/b37cecf519cbb73609ff56d30fcf00855b550908/docs/archive/slow-mover-design-2026-10.md#L803-L900). Current behavior: [slow movers](ALGORITHM.md#slow-movers).

## SM-F3. Improvements to maximum-stack slow-mover detection

**Priority (2026-11-04).** The maintainer selected this as the next investigation after SpacePixels F26's faint-mover presentation work. Its algorithm proposals still require their evidence and review; this priority does not implement them.

**Status and decision.** Proposed (investigated 2026-11-03). The maintainer requested a thorough review of the maximum-stack slow-mover detection algorithm (`SlowMoverAnalyzer`, `SlowMoverFrameEvidence`, `MasterMapGenerator`) and proposals for improvements. This item records the investigation findings, edge-case limitations, and concrete improvement directions.

**Problem analysis and investigation findings.**

1. *Narrow motion corridor between axis ratio and stationary clustering.*
   `slowMoverMinAxisRatio = 1.2` and `slowMoverMaxAxisRatio = 2.8` define the primary morphological window. For a typical seeing profile with minor extent ~3.5 px, `axisRatio <= 2.8` limits total displacement to at most (2.8 - 1) * 3.5 ≈ 6.3 px. Faster movers are rejected by `rejectedAboveMaxAxisRatio`. Meanwhile, `slowMoverMaxStationaryLikelihood = 60.0%` with clustering radius ≈ 1.5 px requires that no single location contains more than 60% of supported frame centroids. For uniform motion, spending <= 60% of N frames in a 3 px diameter requires total motion:
   $$\Delta x = N \cdot v \ge \frac{3\text{ px}}{0.60} = 5.0\text{ px}$$
   Consequently, the combination of default settings allows only objects with total displacement in a narrow corridor of roughly **5.0 to 6.3 px** to pass both gates. Slower objects fail stationary likelihood (> 60%), while slightly faster objects fail axis ratio (> 2.8).

2. *Absence of chronological / temporal progression validation.*
   `SlowMoverFrameEvidence` measures spatial clustering of frame centroids, but does not examine sequence order or capture timestamps (`DATE-OBS`). If seeing fluctuations or noise spikes cause the best-aperture position to jump back and forth randomly between frames, the spatial cluster size can remain below 60%, allowing an unmoving, noisy artifact to pass as a slow mover. The algorithm lacks a test for monotonic physical displacement over time.

3. *Noise elevation in maximum stacks (Gumbel extreme-value effect).*
   The per-pixel maximum across N Gaussian noise frames has an expected value $\mu_{\max} \approx \mu_{\text{sky}} + \sigma_{\text{sky}}\sqrt{2 \ln N}$ (elevated by ≈ 2.6 sigma for N = 30). Because a moving source only illuminates a given pixel in a small subset of frames, faint slow movers with single-frame SNR ~ 1-2 do not rise above the maximum-stack background median. The maximum stack is therefore suited for moderately bright slow movers (single-frame SNR >= 4-5), while faint targets require coherent integration (such as the implemented [digital-tracking stage](ALGORITHM.md#faint-mover-search)).

4. *Lock-on by stationary background stars inside the candidate footprint.*
   `SlowMoverFrameEvidence.findBestAperture` searches for the maximum SNR aperture within the candidate's footprint in each frame. If a moving object grazes a faint background star (or residual hot pixel), that star is present in all frames and yields the highest SNR in every frame. The search locks onto the stationary star, driving `stationaryLikelihood` to 100% and causing false rejection of genuine movers.

5. *Redundant row indexing in stack generation.*
   In `MasterMapGenerator.createMaximumMasterStack`, `frames.get(i).pixelData[y][x]` dereferences the 1D row array inside the innermost x loop, performing millions of redundant pointer lookups on large multi-megapixel sequences.

**Proposed improvements.**

1. **Temporal progression check in `SlowMoverFrameEvidence` (P1):**
   Project supported centroids $(x_i, y_i)$ onto the candidate's principal moment axis $\theta$:
   $$s_i = (x_i - x_0)\cos\theta + (y_i - y_0)\sin\theta$$
   Compute the rank or linear correlation between projected coordinate $s_i$ and frame timestamp $t_i$: $|r(s_i, t_i)| \ge 0.70$. Real physical motion progresses monotonically over time ($|r| \approx 1.0$), whereas seeing jitter and noise scatter randomly around zero ($r \approx 0$). This provides a robust physical motion filter without restricting the allowable speed range.

2. **Widen axis ratio corridor with TrackLinker hand-off (P2):**
   Increase `slowMoverMaxAxisRatio` to 4.0–5.0. If a candidate has $axisRatio > 2.8$, verify whether `TrackLinker` already claimed it into a moving track. If `TrackLinker` missed it (e.g., due to dropped intermediate frames or sub-jitter steps), retain it in the slow-mover branch.

3. **Residual maximum stack / median subtraction (P3):**
   Form a median-subtracted maximum stack:
   $$M_{\text{diff}}(x, y) = \max_t \left( I_t(x, y) - m(x, y) \right)$$
   Subtracting the static median stack $m(x, y)$ removes stationary stars and background gradients before taking the maximum. This prevents faint stationary stars from distorting `findBestAperture`, removes star wing bleed, and simplifies stationary-mask overlap filtering.

4. **Row buffer hoisting in `MasterMapGenerator` (P4):**
   Cache `short[] rowI = frames.get(i).pixelData[y]` outside the column loop, eliminating $W \times N$ array indexing operations per row.

**Evidence needed before implementation.**
- Test candidate recovery and false-candidate rates across reference sessions (Klangwolke-2, Eagle-noisy, M81-Kumar, helix-reg, m101-reg).
- Validate that the temporal monotonicity check rejects seeing/noise artifacts on real sessions without rejecting curved or slightly uneven asteroid paths.
- Profile stack generation runtime before and after row buffer hoisting.

## SM-F4. Bright-star residuals in faint-mover searches

**Status and decision.** Proposed experiment following the completed SM-F2/F25 delivery. No weighting rule, extra setting or default change has been agreed.

**Current boundary.** Digital tracking already masks star cores, registration fill and streaks, clips residuals, checks half-session support, compares stationary and camera-fixed stacks, and rejects duplicates, tracked objects and side lobes. Temporal-variance weighting near bright stars is still absent. A bright-star residual remains among the 11 Klangwolke-2 candidates, while aggressive masks can also remove real movers crossing stars.

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

**Remaining investigations.**

SpacePixels F26 now supplies bounded residual blinks, half-stack maps and actual scoring-path sample/mask evidence for review. These views do not complete the independent validation tasks below or turn empirical scores into significance estimates.

1. Review unmatched candidates with original-frame blinks, independent time subsets and deeper predictions using SpacePixels' existing configurable catalogue fetch. Record magnitude limit, passband, timing/WCS/ephemeris uncertainty, fetch completeness and ambiguous matches. Catalogue absence does not establish a new object or a false detection; catalogue information remains a comparison after blind detection.
2. Expand independent shuffled-time, sign-reversed-residual and other justified null controls across datasets and seeds. M81 Kumar still needs a shuffled-time control. Account for real bright movers turned into structured artifacts by a control; control survivors are not automatically a calibrated false-positive rate for ordinary data.
3. Broaden moving-source injections over flux, speed, direction, cadence, gaps, field position, blends, PSF/seeing and registration quality. Test masks, reference self-subtraction and correlated noise, and hold validation examples out of rule/threshold selection. Report recovery and control-survivor uncertainty against the actually searched domain.
4. Review the longer M81 Kumar results or run a justified complete/fixed-domain evaluation. Compare controls and injections on matching footprints when time limits truncate a search. Preserve reasons for unavailable support and distinguish unreviewed candidates from confirmed artifacts.

**Acceptance.** Produce a traceable sensitivity and control-survivor study with frozen configurations, coverage and resource limits. Keep empirical scores and unverified labels until independent evidence supports stronger claims. Any new threshold, default or classification policy requires agreement and a regression of the unaffected pipeline outputs. Catalogue/report enhancements remain application-owned under [SpacePixels F22](../SpacePixels/FEATURE-REQUESTS.md#f22-known-asteroids-and-comets-in-the-field).

## SM-F7. Complementary high-sensitivity moving-object searches

**Status and decision.** Prototype follow-up from the completed three-method investigation. Production currently integrates digital tracking only. The maintainer's request was to investigate all three approaches with real full-size data and potentially retain them; integration of either additional mechanism still needs evidence and agreement.

**Methods to evaluate.**

1. **Temporal-bin proposals followed by full-sequence verification.** Form time groups and propose paths from weak points or short trails, then verify/refine against the original complete sequence. Allow missing or blended bins: demanding a detection in every bin lost one of the original faint examples. Measure objects lost before verification as proposal failures, not rejected verified paths.
2. **PSF-filtered frame extraction and linking.** Match the spatial footprint before extracting weak per-frame evidence, estimate the filtered/correlated noise consistently, then link and verify timed paths. Existing raw-pixel sigma thresholds cannot be reused unchanged after filtering.

**Current evidence and constraints.** All three prototypes recovered the original three magnitude-18.5 Klangwolke-2 examples in full-field searches; digital tracking gave the strongest recovery in the subsequent injection comparisons and was selected for the first delivery. The detailed prototype comparisons and review remain in git history. Proposed mechanisms must search independently of catalogue positions, retain generating-mechanism and temporal-support provenance, and keep fitted positions distinct from individual detections. Coordinate artifact handling and calibration with SM-F4 and SM-F6, and maximum-stack temporal confirmation with [SM-F1](#sm-f1-temporal-confirmation-of-slow-mover-candidates)/[SM-F3](#sm-f3-improvements-to-maximum-stack-slow-mover-detection).

**Acceptance and application handoff.** Compare incremental recovery beyond digital tracking on the same full-size frames, domains, time/resource budgets, held-out injections and controls. Integration needs a useful demonstrated benefit after duplicate removal, with runtime and memory justified; repeated detections of the same paths do not establish added sensitivity. Agree the engine result/settings contract and corresponding SpacePixels controls, resource guidance, mechanism labels, CSV/JSON evidence and searched-frame overlays together. Additional mechanisms remain off by default if adopted; broader motion models or known-object-seeded searches are outside this proposal.

## XC-3. Local background and noise for frame extraction

**Status and decision.** Partly implemented for the master reference; a broader per-frame redesign remains deferred.

**Problem.** Per-frame extraction uses one sky level and noise estimate. A strong sky gradient can therefore produce different effective sensitivity across the field: excess detections on the bright side and missed faint sources on the dark side. Thresholds, significance and flux/background calculations must remain consistent if their sky model changes.

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
