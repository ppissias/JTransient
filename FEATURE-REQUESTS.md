# Feature requests and algorithm proposals

Proposed changes to what JTransient detects, measures or tunes. How the code is organised is in [DESIGN-IMPROVEMENTS.md](DESIGN-IMPROVEMENTS.md), and application features in SpacePixels' [feature requests](../SpacePixels/FEATURE-REQUESTS.md).

These are not authorisations. Keep the existing algorithms until the stated evidence is available and the maintainer decides. An algorithm change goes one at a time, with recovery, false positives and run time measured on the reference datasets (see [DEVELOPER.md](DEVELOPER.md#real-data-checks)). Remove proposals when implemented or closed; describe implemented behaviour and current limitations in [ALGORITHM.md](ALGORITHM.md), and use git history for the proposal and its reviews. The binding declined decisions below remain in force.

## Open proposals

| ID | Status | Proposal, and the condition for revisiting it |
|---|---|---|
| [QC-1](#qc-1-star-count-rejection-envelope) | Deferred | Star-count rejection has no absolute or relative envelope floor. Counts now share a quality area, but small count changes can still matter. A new floor needs a recovery and false-retention study. |
| [PT-5](#pt-5-registration-quality-and-shared-jitter-tolerances) | Proposed | `maxStarJitter` serves several tolerances. The maintainer preferred a future registration-quality assessment over splitting the tolerances now. The fill-edge registration used by camera-fixed rejection is not a general registration-quality measurement. |
| [SM-F1](#sm-f1-temporal-confirmation-of-slow-mover-candidates) | Proposed | Three-bin temporal stacks, or a measured slow-mover path, are not implemented. Compare real-mover recovery, false positives and run time before introducing either; the current frame-support and stationarity screens do not establish motion. |
| [XC-3](#xc-3-local-background-and-noise-for-frame-extraction) | Partly implemented; frame work deferred | Master-star extraction adds a local-sky pass. Per-frame extraction still uses one sky level and noise estimate. Preserve extended emission and propagate local noise consistently before proposing a frame-sky redesign. |
| [VS-2](#vs-2-convergence-of-the-full-ensemble-correction) | Deferred | Ensemble convergence watches zero points only, not the plane coefficients. A fix should monitor changes in the predicted spatial corrections and report convergence or iteration exhaustion; raising the iteration cap alone is not enough. |
| [VS-5](#vs-5-temporal-agreement-between-photometry-apertures) | Deferred | The APERTURE check compares percentile ranges, not temporal agreement. A stronger check should use shared finite frames and test recovery under measurement noise and blending. |
| [VS-9](#vs-9-selected-stars-and-the-linearity-check-minimum) | Deferred | The nominal selected-star minimum differs from check B's counted linear-range minimum. With the default 50 required references and a 90 % faint bound, an ideal sample needs at least 55 before further exclusions. Improve cap validation and evaluate the readiness status for insufficient samples; current diagnostics already distinguish them from measured nonlinearity. |
| [VS-10](#vs-10-deterministic-streak-interpolation-for-photometry) | Rescoped 2026-11-02; evaluate the deterministic correction | Remove piece-order dependence from interpolated streak contamination flags. Expanding masks across the whole unobserved path remains outside this scope. |
| [VS-11](#vs-11-photometry-strictness-defaults-and-recovery-review) | Initial audit complete 2026-11-02; decisions pending | Review below includes the gate/default assessment, 720 scorer experiments, six real datasets, three isolated setting comparisons and nine new tests. Prioritize correctness and explicit sensitivity limits before recalibrating defaults. |
| [VS-12](#vs-12-consolidated-photometry-improvement-roadmap) | Proposed 2026-11-02; detailed review added | Consolidated roadmap using six real sequences. The [review](#detailed-review-by-codex-2026-11-02) recommends correcting source descriptions and separating bounded consistency fixes from scientific-policy changes before implementation. |
| [ST-7](#st-7-elongation-threshold-for-single-faint-streaks) | Investigation requested 2026-11-02 | Evaluate retaining isolated faint-streak detections above a configurable elongation, recommend an evidence-based default, and expose the agreed control as an advanced setting in SpacePixels. |

The descriptions below recover the earlier design discussions and reviews from git history, reconciled with the current implementation and maintainer decisions on 2026-11-02. Reproduction results belong to the linked historical reviews. They have not been rerun for this documentation update.

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

## XC-3. Local background and noise for frame extraction

**Status and decision.** Partly implemented for the master reference; a broader per-frame redesign remains deferred.

**Problem.** Per-frame extraction uses one sky level and noise estimate. A strong sky gradient can therefore produce different effective sensitivity across the field: excess detections on the bright side and missed faint sources on the dark side. Thresholds, significance and flux/background calculations must remain consistent if their sky model changes.

**What is already addressed.** The common data areas handle registration fill separately. Master-star extraction now combines its original global-sky pass with a local-sky pass to find stationary sources that the global pass misses. The first pass also preserves coverage of extended emission that the per-frame extractor can detect. Photometry already estimates local sky for its own measurements. These changes narrow the remaining proposal to the extraction paths that still use a global estimate.

**Direction to evaluate.** Assess a spatial background and noise model, such as a robust mesh, for those frame paths. Define mesh scale, source masking, interpolation and treatment of extended emission before changing extraction. Propagate the corresponding local noise into seed/growth thresholds and reported significance; subtracting a smooth sky while retaining an unrelated global noise estimate needs separate justification. Reassess master/frame consistency if the frame detection model changes.

**Evidence needed.** Compare gradients, nebulosity, crowded fields, dark regions and ordinary flat backgrounds. Measure true-source and mover recovery, false detections, stationary-mask coverage, tuner behavior, run time and memory. A gain on one gradient must be weighed against lost extended structure and changed threshold calibration.

**Recovered source:** [original global-background limitation and review caveats](https://github.com/ppissias/JTransient/blob/b37cecf519cbb73609ff56d30fcf00855b550908/docs/archive/code-review-2026-10.md#L1449-L1473). Current implementation: [SourceExtractor](src/main/java/io/github/ppissias/jtransient/core/SourceExtractor.java), [MasterReferenceAnalyzer](src/main/java/io/github/ppissias/jtransient/core/MasterReferenceAnalyzer.java).

## VS-2. Convergence of the full ensemble correction

**Status and decision.** Deferred. Retain the finding and establish the effect of a correction before changing the solver.

**Problem.** `EnsembleSolver` alternates frame fitting, gauge fixing, star fitting and robust reweighting. Its stopping test compares only successive frame zero points. The spatial plane can still be changing while the zero points appear stable, particularly for a symmetric field under a changing cloud gradient.

**Recovered evidence.** The historical component probe used 64 symmetrically placed stars, a varying X gradient and opposite excursions in two stars. At iteration 1 the maximum zero-point change was about `3.72e-16` mag, below the `1e-5` mag tolerance, while the fitted plane had changed substantially. One further frame fit with reconstructed weights changed a plane coefficient by `0.008419` mag per normalized coordinate. This demonstrates premature stopping in that constructed case; the review did not establish its frequency or a full-pipeline false-candidate rate.

**Direction to evaluate.** Monitor changes in the predicted magnitude correction at the measured star positions, covering both zero points and spatial terms in comparable units. Consider whether the objective or robust weights also need a stability check. Report convergence separately from iteration-limit exhaustion. Raising the iteration cap alone leaves the early stopping condition in place.

**Evidence needed.** Repeat the component probe and controls without a plane, without variable stars, and with gradients, outliers and incomplete measurements. Check invariance to the solver's gauge convention. On real sessions compare final corrections, residuals, readiness, candidate verdicts and run time before choosing a tolerance and iteration policy.

**Recovered source:** [ensemble finding, numerical reproduction and fix direction](https://github.com/ppissias/JTransient/blob/b37cecf519cbb73609ff56d30fcf00855b550908/docs/archive/variable-star-assessment-2026-10.md#L121-L165). Current implementation: [EnsembleSolver](src/main/java/io/github/ppissias/jtransient/photometry/EnsembleSolver.java).

**Review update (2026-11-02).** The current stopping condition still checks zero points alone. [VS-11](#open-proposal-reassessment) recommends settling this before calibrating gates on the solver's residuals. The earlier numerical convergence probe was recovered from history; it was not repeated in the new strictness audit.

## VS-5. Temporal agreement between photometry apertures

**Status and decision.** Deferred. Evaluate a stronger consistency check with noise and blending controls before selecting a new gate.

**Problem.** The APERTURE gate compares the small and large apertures' 5th-to-95th percentile ranges, normalized by the main aperture's amplitude. Equal ranges do not establish that the measurements brighten and fade together. The current masking follows finite main-aperture residuals; nonfinite values in the other apertures can also leave their percentile calculations using different effective frame subsets.

**Recovered evidence.** In the historical scorer probe, the main and small residual curves were sine waves with a 0.15-mag semi-amplitude, and the large curve was their negative. With 80 quiet comparison stars, the amplitude difference was zero and no gates failed; the result was `HIGH_CONFIDENCE`. A coherent control also passed. This isolates a scorer weakness; it does not demonstrate that a real blended image passes all earlier image and crowding checks.

**Direction to evaluate.** Compare temporal agreement as well as amplitude on the intersection of frames with finite main, small and large residuals. Candidate measures include agreement of signed changes or a noise-aware relation between residual curves. Calibrate the choice against measurement uncertainty and blending; a simple correlation cutoff can reject faint real variability or accept a shared systematic.

**Evidence needed.** Compare coherent, reversed and time-shifted curves; unequal missing measurements; faint noisy variables; constant stars; and blended sources. Measure recovery and false acceptance through the full photometry path, in addition to repeating the isolated scorer case. Decide how too little shared evidence is reported.

**Recovered source:** [aperture finding, scorer reproduction and shared-frame requirement](https://github.com/ppissias/JTransient/blob/b37cecf519cbb73609ff56d30fcf00855b550908/docs/archive/variable-star-assessment-2026-10.md#L237-L270). Current implementation: [VariabilityScorer](src/main/java/io/github/ppissias/jtransient/photometry/VariabilityScorer.java).

**Review update (2026-11-02).** Fresh [VS-11 experiments](#controlled-recovery-experiments) gave HIGH_CONFIDENCE in all 30 opposite-aperture controls despite the large aperture's reversed signal. This is an active correctness concern alongside the overly restrictive sensitivity boundaries; stricter defaults elsewhere do not repair it.

## VS-9. Selected stars and the linearity-check minimum

**Status and decision.** Deferred. Align configuration expectations and readiness reporting with what check B actually counts; preserving the intended number of linear reference stars remains a valid policy.

**Problem.** `photometryMaxStars` caps the selected stars, while `linearityMinStars` is also required inside the measured linear range. The initial cap warning compares against the nominal minimum and ensemble minimum. Check B then excludes stars beyond its faint reference bound and may exclude others for measurement quality or bright-end shape departure. A cap equal to the nominal minimum can therefore pass validation while being too small to satisfy the later check.

**Recovered evidence.** The historical probe supplied 50 evenly spaced magnitudes with constant concentration index, small errors and a four-mag span. With the default 90 % faint bound, only 45 stars counted, despite a sufficient linear range of about 3.592 mag. Under that ranking policy, 55 distinct measurable magnitudes are the ideal minimum to retain 50 (`ceil(0.9 * 55) = 50`); exclusions can require more. This is a sample-count/configuration problem, not evidence of a false variable-star candidate.

**Current boundary.** Reporting already distinguishes too few stars in the linear range, insufficient magnitude range and a measured bright-end shape departure. The historical complaint that these all receive the same nonlinearity message is therefore partly addressed. Too few counted linear stars can still give check B a `FAIL`, and the nominal cap validation still does not account for the faint-bound loss.

**Direction to evaluate.** Validate or explain the effective minimum using the actual percentile/rank policy, including ties, rather than a simple percentage division. Preserve the distinction between selected, measurable and counted linear-reference stars. Evaluate whether insufficient evidence should be `INCONCLUSIVE` with a specific reason, and explicitly retain safe downstream handling when linearity cannot be established.

**Evidence needed.** Cover caps around 50 and 55, tied magnitudes, flagged or low-SNR measurements, narrow magnitude ranges and genuine bright-end nonlinearity. Check configuration warnings, per-frame reasons, session readiness and whether photometry proceeds. An ideal lower bound must not be presented as a guarantee that an arbitrary field supplies enough usable references.

**Recovered source:** [minimum-count finding, reproduction and classification caveat](https://github.com/ppissias/JTransient/blob/b37cecf519cbb73609ff56d30fcf00855b550908/docs/archive/variable-star-assessment-2026-10.md#L368-L397). Current implementation: [DetectionConfigRules](src/main/java/io/github/ppissias/jtransient/config/DetectionConfigRules.java), [PhotometricReadinessChecker](src/main/java/io/github/ppissias/jtransient/photometry/PhotometricReadinessChecker.java), [VariableStarAnalyzer](src/main/java/io/github/ppissias/jtransient/photometry/VariableStarAnalyzer.java).

**Review update (2026-11-02).** Two new scientific controls reproduce FAIL with 45 counted stars from 50 ideal selected stars, and PASS with 50 counted stars from 55 ideal selected stars. Neither has a measured shape departure. The shipped unlimited cap avoids forcing this boundary, but naturally sparse fields and explicit caps still encounter it. Keep the count/status distinction in the implementation discussion.

## VS-10. Deterministic streak interpolation for photometry

**Maintainer decision (2026-11-02).** Narrow this proposal to correcting the dependence on streak-piece ordering. Defer any blanket expansion of the contamination mask over the entire predicted path of a streak in an unobserved frame.

**Problem.** In a frame a streak track skips, `VariableStarAnalyzer.flagTrack` interpolates a position and uses a nearby piece's shape for the contamination segment. Several pieces can share a source frame; their ordering must not determine the interpolated position, selected shape or stars flagged. The recorded klangwolke-2 example partially flags a satellite's path in missing frame 3, with the flagged part depending on piece order.

**Bounded scope.**

- Evaluate how observations with multiple pieces in the same frame should supply the interpolation reference and shape, including deterministic selection and tie-breaking. State the policy before implementation and preserve original frame identities and available capture times.
- Make permutations of the same physical pieces produce the same interpolated contamination flags. Preserve the rules for observed frames, the current mask-extent policy, and the existing handling of missing timing and rejected frames unless a change is explicitly agreed.
- Keep streak linking, extraction thresholds and candidate acceptance unchanged. Do not infer that an unobserved or flashing satellite painted its full predicted path, and do not add a new path-wide veto as part of the deterministic correction.

**Verification.** Compare original, reversed and shuffled piece order; multiple fragments in one frame; equidistant shape choices; missing middle frames; non-contiguous sequence indices; and available/missing timestamps. The same input geometry must yield the same flags. On the recorded example and relevant reference datasets, compare contamination flags, usable measurements, ensemble results and variable-star verdicts, and report any changes outside the intended correction.

## VS-11. Photometry strictness, defaults and recovery review

**Request (2026-11-02).** Evaluate whether the current photometry checks and default settings are appropriate or unnecessarily strict. Investigation is authorised; changing scientific behavior or defaults requires an evidence-backed recommendation and decision.

**Scope.** Trace the whole path: input suitability, star selection, registration/measurement flags, frame and ensemble exclusions, readiness verdicts, variability statistics, candidate gates and tier assignment. Distinguish rejection of a measurement, star, frame, session or candidate. Audit fixed constants and shipped settings together, including Auto-Tune overrides and whether the application exposes or hides a control.

**Evidence.** Inventory existing tests, add focused scientific checks where the audit exposes a gap, and use representative real datasets with controlled injections where appropriate. Measure variable-star recovery and false candidates together, as a function of amplitude, cadence, event duration, signal-to-noise, missing/contaminated measurements, blending and field quality. Report which gate first rejects an otherwise recoverable source and the effect of changing it in isolation. A larger candidate count alone does not establish an improvement. Separate an exploratory comparison from a calibrated default recommendation.

**Related proposals.** Reassess [VS-2](#vs-2-convergence-of-the-full-ensemble-correction), [VS-5](#vs-5-temporal-agreement-between-photometry-apertures), [VS-9](#vs-9-selected-stars-and-the-linearity-check-minimum) and the narrowed [VS-10](#vs-10-deterministic-streak-interpolation-for-photometry). Consider the registration assessment in [PT-5](#pt-5-registration-quality-and-shared-jitter-tolerances), and keep previously closed proposals closed unless new evidence is presented for a fresh decision.

**Deliverable.** A documented gate/default inventory, reproducible evidence and limitations, prioritized recommendations to retain, revise or investigate, and a clear boundary between implementation defects and scientific-policy choices.

### Review findings (2026-11-02)

**Opinion.** The defaults favor well-sampled changes lasting several exposures. The audit finds both restrictive scientific policies and specific correctness gaps. It does not justify a blanket relaxation. First fix how evidence is evaluated, then measure whether a change in a particular default improves recovery at an acceptable false-candidate rate. The requested accessible explanation is now in [ALGORITHM.md](ALGORITHM.md#photometry-in-plain-english).

**Scope and reproducibility.** Code review used the current `VariableStarAnalyzer`, `PhotometryStarSelector`, `ApertureMeasurer`, `PhotometricReadinessChecker`, `EnsembleSolver`, `SpatialFrameFilter`, `VariabilityScorer` and `PhotometryMath`. Baselines were JTransient `e88bf2f`, SpacePixels `05be7e1` and JPlateSolve `9ca6b42`. Production algorithms and defaults were not changed. The shipped photometry values match the library defaults; photometry is disabled in both until explicitly enabled. Auto-Tune does not run this branch or calibrate its photometry thresholds. Application setting visibility is defined in SpacePixels' [VariableStarsSettings](../SpacePixels/src/main/java/eu/startales/spacepixels/config/VariableStarsSettings.java).

### Gate and default assessment

| Stage / default | Assessment and recommendation |
|---|---|
| Independent master-star list, elongation <= 1.5, uncontaminated apertures and sky rings | Keep the independent list and geometry checks. Selection permanently limits completeness in crowded or elongated fields. The 2 % neighbour-flux model assumes Gaussian wings; changing crowding acceptance needs blend/PSF experiments, not just a larger candidate count. |
| Main radius 1.5 FWHM; sky annulus 3–5 FWHM; alternative apertures and measured aperture optimization | Reasonable starting geometry, with larger contaminated apertures as a known tradeoff. Formal flux uncertainties omit some noise sources, so retain the empirical same-brightness noise model. Include non-Gaussian PSFs and changing seeing in later calibration. |
| Median SNR >= 10; saturation fraction 0.85; more than half saturated/nonlinear measurements excludes a star | Keep the protection provisionally. None of the new experiments establishes a safer alternative cutoff. Audit which frames contribute to the star-level exclusion, because the current count and median precede final frame selection. |
| Check A: >= 1,024 pixel levels; <= 1 % floor clipping for PASS | Keep quantisation/clipping diagnostics. Distinct-level count is a heuristic and cannot prove native bit depth or linearity. Floor clipping gives LIMITED, while coarse quantisation gives NOT_READY. Original calibrated linear frames remain necessary even after PASS. |
| Registration spread <= 0.5 px; at least five of up to 50 sampled stars supply the offset measurement | No registration exclusions occurred in these four runs: maximum measured spreads were 0.18–0.39 px. This sample does not calibrate the threshold. Sparse/uncertain fits and representative field coverage belong in PT-5 before promoting the check into general quality control. |
| Check B: error <= 0.05 mag, CI drift tolerance 0.03 plus bin noise allowance, >= 50 counted stars, >= 2 mag range | Separate lack of evidence from measured nonlinearity. The 50/55 reference issue is reproduced below. A frame can have constant CI and still fail through insufficient count or range. Keep unsuitable bright measurements excluded while evaluating those status decisions. |
| Session fraction: more than 20 % failing Check-B/Check-D frames blocks the session | A restrictive policy even when enough individually passing frames remain. Helix demonstrates the distinction between a usable subset and the original session verdict. Evaluate an explicitly validated retained subset before replacing this rule; unsuitable frames can reveal a shared calibration fault. |
| Check D: slope > 0.01 mag/mag and 3 standard errors; tracking correlation >= 0.7 with a sufficient fitted slope change; zero-point range >= 0.05 mag for PASS | Protects against unresolved magnitude-dependent response, but a correlation threshold alone is not a calibrated false-alarm rate. DC lies just over 0.7. Missing transparency leverage yields INCONCLUSIVE and hence LIMITED, even for otherwise stable data. Response/linearity conclusions need known-linear controls and alternative physical causes considered. |
| Ensemble: >= 10 usable stars per frame; spatial plane with >= 30; up to 15 iterations | Keep the minimum-evidence requirements. VS-2's incomplete convergence test can change residual systematics and should be settled before residual-based thresholds are calibrated. Sparse or ill-conditioned spatial coverage needs separate controls. |
| Spatial filter: 3 x 3 cells, >= 30 references in each compared cell, >= 0.10 mag residual difference | Useful defense against unmodelled field structure; only DC lost a frame here. Cells without enough stars leave this check unavailable. These four runs do not justify changing the limit. |
| Candidate scores: scatter and adjacent-pair J each >= 5 empirical spread units; 50 magnitude neighbours | Retain provisionally for the conservative search. These are correlated statistics of one light curve, not independent five-sigma detections. Fast periodic signals can have strong scatter and negative J and receive CONSTANT. Document that limitation and assess a bounded alternative only if recovery earns its complexity. |
| DATA: >= 20 points and >= 30 min when timestamps permit | Twenty is a minimum-evidence choice, not a universal astronomical requirement. A known short span fails one gate and can still yield POSSIBLE; missing timing skips the span check. Evaluate cadence and scientific purpose before changing the span requirement. |
| AMPLITUDE: central 90 % range >= 0.05 mag (0.10 LIMITED) and >= 4 x expected scatter | Appropriate for broad changes, insensitive to low-duty-cycle eclipses. LIMITED combines several different reasons under one higher amplitude floor. Assess reason-specific policy rather than globally lowering the floor. The measured percentile range is not an uncertainty-corrected estimate of true peak-to-peak amplitude. |
| PERSISTENCE: five same-side usable points beyond 1.5 x expected scatter; SPLIT_HALF correlation >= 0.5 | Together with J, these favor similar time scales. At a two-minute cadence, the first and fifth measurement centres span eight minutes; exposure durations and gaps change the covered interval. The experiment shows recovery costs and why lowering persistence alone is insufficient. |
| APERTURE: relative amplitude difference <= 0.3 | Fix temporal agreement and common finite-frame selection under VS-5 before tightening or relaxing its numerical cutoff. Equal amplitude does not establish a common signal. |
| SYSTEMATICS: correlation > 0.6, trend amplitude compared with 2 x the comparison-star response; LOCAL: at least three useful neighbours, correlation > 0.3 and >= 30 % shared amplitude | Keep both defenses and existing tests of independent variables versus shared image faults. Calibrate with coloured noise, blends, spatial gradients and accidental physical-variable/driver correlations. LOCAL also fails when evidence is unavailable; its reason should remain distinguishable from a measured common pattern. |
| LINEARITY: any saturated/nonlinear flag anywhere in the stored light curve | Review the scope first. A flag on a permanently excluded frame can demote otherwise clean retained evidence. Preserve warnings for clipped measurements on retained frames, where missing bright states can bias variability. |

**Tier semantics matter.** Both initial scores must pass before the eight gates are even evaluated. One failed gate gives POSSIBLE, not an automatic rejection; two failures give REJECTED. Gate counts omit stars that fail the initial scores. Consequently an absence of gate failures does not establish completeness, and CONSTANT is a detector classification rather than proof of physical constancy.

### Controlled recovery experiments

The scratch probe ran **720 scored ensembles: 24 scenarios x 30 seeds**, with 80 observations, 120 independent constant reference stars and 0.01 mag Gaussian noise per measurement. The nominal cadence was two minutes. Sine phases varied with seed. These are scorer-level residual fixtures with ideal aperture agreement and no seeing/sky/position systematics; they bypass extraction, readiness and the ensemble fit. The same noise seeds are reused across scenarios. Their purpose is to isolate decision boundaries, not estimate whole-image completeness or a survey false-alarm probability.

| Injected signal / condition | HIGH_CONFIDENCE / POSSIBLE / REJECTED / CONSTANT, out of 30 | Interpretation |
|---|---|---|
| Constant target | 0 / 0 / 0 / 30 | No candidate target in this noise control. |
| 0.06 mag peak-to-peak sine, 40 frames/cycle | 30 / 0 / 0 / 0 | Defaults recover coherent variability above the nominal amplitude floor in this ideal fixture. |
| 0.03 mag peak-to-peak sine, 40 frames/cycle | 3 / 6 / 10 / 11 | Noise changes the observed percentile amplitude and other scores; a true-amplitude cutoff is not exact completeness. |
| 0.20 mag peak-to-peak sine, 3 or 4 frames/cycle | 0 / 0 / 0 / 30, for each period | Strong scatter does not rescue inadequate adjacent-pair J. |
| Same sine, 6 frames/cycle | 0 / 11 / 19 / 0 | All fail persistence; 19 also fail split-pair agreement. |
| Same sine, 8 frames/cycle | 0 / 30 / 0 / 0 | Persistence alone prevents the high tier. |
| Same sine, 12, 20 or 40 frames/cycle | 30 / 0 / 0 / 0, for each period | Coherent changes spanning more samples pass in this setup. |
| 0.30 mag eclipse, 1 of 80 frames | 0 / 0 / 0 / 30 | Isolated-event suppression removes this short signal. |
| Same eclipse, 2 of 80 frames | 0 / 0 / 29 / 1 | Percentile amplitude, persistence and pair agreement reject the event despite its depth. |
| Same eclipse, 3 or 4 of 80 frames | 0 / 2 / 28 / 0; 0 / 6 / 24 / 0 | A few extra points do not reliably escape the combined time-support requirements. |
| Same eclipse, 5, 8 or 12 of 80 frames | 30 / 0 / 0 / 0, for each duration | Recovery changes sharply with duty cycle and supporting samples. |
| 0.20 mag sine over a known 7.9 min span | 0 / 30 / 0 / 0 | DATA alone lowers the tier; the same fixture with unavailable times gives 30 high-tier results. |
| 0.06 mag sine on LIMITED data | 0 / 30 / 0 / 0 | The higher amplitude floor lowers the tier relative to the READY control. |
| Large aperture is the negative of the main/small sine | 30 / 0 / 0 / 0 | Reproduces VS-5's false agreement. This is a scorer control, not a demonstrated blended-image false positive. |
| One frame globally excluded, with NONLINEAR on the target in that frame | 0 / 30 / 0 / 0 | LINEARITY alone demotes the candidate even though that frame supplies no scored evidence. |
| 8-frame sine, persistence reduced from 5 to 2 in scratch | 30 / 0 / 0 / 0 | A focused recovery gain in ideal data; no realistic artifact-rate calibration supports adopting 2 as the default. |

No constant reference received HIGH_CONFIDENCE or POSSIBLE in these fixtures. Shared seeds and ideal residual/aperture assumptions limit that result; it does not establish safety for real images, correlated noise or a less restrictive detector.

### Real-data crosschecks

The public SpacePixels headless API ran the complete sequences below with the shipped profile, **only enabling photometry**, no Auto-Tune and no report export. They are six of the documented reference datasets, not the full reference batch. The two additional full-resolution runs used Klangwolke-2's converted frames and M81 Kumar, sequentially. Input files were read in place with a scratch application home; SHA-256 checks covered 263 originals with no changes (189 in the original batch and 74 in the additional pair). No catalogue identities or variability truth labels were established.

| Dataset | Input / kept by general QC / used by photometry | Selected / scored stars | Default result |
|---|---|---|---|
| m5-reg | 36 / 32 / 26 | 418 / 253 | LIMITED: median verified range 2.20 mag; six shape-failing frames. One score candidate fails PERSISTENCE, APERTURE and SYSTEMATICS; no reported candidates. |
| helix-reg | 45 / 39 / 28 | 354 / 0 | NOT_READY: 11 of 39 evaluated frames fail the shape check (28.2 %), above 20 %, although 28 frames remain. |
| m31-red-reg | 49 / 43 / 16 | 429 / 0 | NOT_READY: 27 response-failing frames, tracking of the zero point, and fewer than 20 final frames. Several independent barriers remain. |
| dc-reg | 59 / 52 / 48 | 8,081 / 0 | NOT_READY: response slopes track sky at r = -0.7056. Three response frames and one spatial outlier are excluded. |
| klangwolke-2 | 33 / 28 / 28 | 53,914 / 43,603 | READY: all photometry frames pass shape and response checks. There are 47 HIGH_CONFIDENCE, 22 POSSIBLE and 27 REJECTED score candidates. |
| M81-kumar-L-dataset-registered | 41 / 39 / 32 | 6,155 / 3,435 | LIMITED: median linear range 2.14 mag and response INCONCLUSIVE at a 0.036 mag zero-point range. There are 10 HIGH_CONFIDENCE, 18 POSSIBLE and 22 REJECTED score candidates. |

**Additional checks.** Both full-resolution runs completed successfully, with no concurrent processing JVM. Klangwolke-2 uses an alternative aperture for 42 of its 69 reported candidates; M81 uses the 1.0-FWHM alternative for all 28 reported candidates. Those curves still carry main-aperture errors, confirming that the comparison's aperture/error inconsistency is exercised by real runs. These candidate counts are not independently verified variables. M81 has six scored stars with nonlinear/saturated flags only in excluded frames, but none of its 50 score candidates receives LINEARITY solely from those historical flags. Recomputing only the SNR criterion on the final active-frame mask finds 73 M81 stars and two M5 stars that were excluded for median SNR but would meet that criterion; six M5 stars cross the other way. These are criterion changes, not demonstrated additional recoveries.

Three **single-setting scratch comparisons** distinguish causes without changing production defaults:

- Helix: allowing a 35 % instead of 20 % failing-frame fraction gives LIMITED with the **same 28 retained frames** and 174 scored stars; there are no score candidates. This demonstrates a session-policy barrier, not that the retained photometry is independently validated.
- DC: increasing the slope-tracking correlation threshold from 0.7 to 0.8 gives LIMITED with the **same 48 retained frames**, 3,171 scored stars, two HIGH_CONFIDENCE candidates and one rejected candidate. The two are unverified; their appearance is not evidence that 0.8 is the correct limit. Too little transparency leverage still leaves the response assessment inconclusive.
- M5: lowering persistence from five to two changes its one candidate from three failed gates to two. It remains REJECTED through APERTURE and SYSTEMATICS. A sensitivity improvement on the ideal sine does not imply useful recovery on this real example.

Of M5's 253 scored stars, **42 carry saturated/nonlinear flags only in excluded frames**. The corresponding counts are 27 of 174 in the Helix comparison and four of 3,171 in the DC comparison. These are stars potentially exposed to the all-frame LINEARITY penalty; they are not 42, 27 or four demonstrated lost variables.

**Limits.** The data have not been independently certified linear, and the possible astrophysical variables have not been identified. The experiments therefore expose where decisions act; they cannot classify the three baseline NOT_READY results as false rejections. Pixel-level injections through extraction, masks, fitting and the final gates, together with labelled real sources and realistic negative controls, remain necessary before changing defaults.

### Open-proposal reassessment

| Proposal | Recommendation after this audit |
|---|---|
| VS-2 | Keep and prioritize solver convergence before threshold calibration. The missing plane stopping criterion remains in source; the old numerical probe was not rerun here. |
| VS-5 | Keep and prioritize an uncertainty-aware temporal aperture comparison on shared finite frames. Fresh counterexamples show why a stricter amplitude-difference cutoff cannot solve the problem. |
| VS-9 | Keep as a bounded count/validation/status correction. Two new tests cover the distinct-magnitude 50/55 boundary. Reporting has already improved; do not restore the old claim that every shortage is described as measured stretching. |
| VS-10 | Keep the agreed deterministic interpolation scope. Mask ordering can change usable evidence, but a full path-wide veto would need its own recovery/false-positive study. No blanket masking expansion is proposed here. |
| PT-5 | Keep the registration-quality assessment. The current photometry fit can inform a shared design, but neither these clean registrations nor the fill-edge fit establish the cost or reliability of a general QC rejection test. |

### Recommended next work and verification

1. **Bounded correctness work first:** settle the LINEARITY gate's treatment of permanently excluded frames; evaluate VS-5 and VS-2; align the VS-9 effective reference requirement and reporting. Preserve useful warnings for partial saturation in frames that remain in the analysis. Discuss each change separately and verify its effect before implementation.
2. **Define intended temporal coverage:** retain the current conservative defaults provisionally. If short eclipses or fast periodic stars are desired, evaluate a specific alternative statistic/policy against realistic artifacts. Lowering all the current correlated evidence requirements would also weaken the protections they provide.
3. **Evaluate readiness on retained evidence:** test known-linear sessions with controlled bad-frame fractions and response/sky systematics. Decide whether subset validation and reason-specific LIMITED handling can recover useful work without masking a shared fault. Do not adopt 35 %, 0.8 or persistence 2 from the illustrative comparisons.
4. **Calibrate scientifically:** use amplitude/period/duty-cycle and cadence grids, irregular timing, missing points, different SNR and PSFs, crowding, saturation, clouds and sensor artifacts. Report high-tier recovery separately from POSSIBLE recovery and false candidates per field/session. Include events near batch/session boundaries and preserve original capture times.

**Added verification.** Seven tests in [PhotometrySensitivityTest](src/test/java/io/github/ppissias/jtransient/photometry/PhotometrySensitivityTest.java) cover coherent recovery/noise controls, short-span and LIMITED tier behavior, combined gate failures, rapid-signal J and short-eclipse percentile amplitude. Two additions to [PhotometricReadinessCheckerTest](src/test/java/io/github/ppissias/jtransient/photometry/PhotometricReadinessCheckerTest.java) reproduce the reference-count boundary. JTransient `check` and `apiConsumer` passed: **297 tests, zero failures/errors/skips**, with architecture verification and the public API consumer passing. Probe sources, detailed result files and machine-specific reproduction commands remain outside the tracked documentation, with their locations in `LOCAL-ENVIRONMENT.txt`.

### Comparison with Romanishin (2002) and Stetson (1996) (2026-11-02)

**Reference-star selection and ensemble model.** `VariableStarAnalyzer` extracts a dedicated source list from the median master image; `PhotometryStarSelector` rejects unsuitable geometry, crowding, saturation and edges, then distributes the retained sample across an 8x8 field grid and brightness ranks without requiring an external catalog of certified constant stars. `EnsembleSolver` fits `m(i,j) = M(i) + Z(j) + a(j)x(i) + b(j)y(i)` with iterative scatter estimates and Cauchy weights to estimate session stability, while the `LOCAL` gate deliberately includes other candidates when testing for a shared local artifact.

**Agreement with Romanishin.**
- `ApertureMeasurer` (`:152-185`) estimates a local sky around each star on each frame using a clipped median while masking master-star footprints, and (`:188-236`) subtracts sky before converting flux to magnitude, uses fractional pixel weights, and flags invalid/saturated measurements.
- Apertures follow frame FWHM, and multiple aperture sizes are solved with a brightness-bin choice based on measured scatter (addressing the sky-noise vs. stellar-light tradeoff rather than applying a large-aperture curve-of-growth correction to standard-system flux).
- The ensemble removes shared transparency changes, while the optional magnitude plane is distinct from additive sky subtraction and cannot repair an incorrect sky estimate equally for bright and faint stars. Readiness, crossing, shape, spatial-residual and systematics checks act as evidence screens rather than proof that imported images are calibrated or linear.

**Findings and limitations from the literature comparison:**

1. **Error bars can describe a different aperture than the displayed curve (`VariableStarAnalyzer`, `PhotometryReportSectionWriter`):** `VariableStarAnalyzer` (`:523-524`) initially copies main-aperture residuals and errors, and at `:1141-1143` an alternative aperture can replace `deltaMag` without replacing `magError`. SpacePixels `PhotometryReportSectionWriter` (`:1357`) plots those errors and (`:1595`) exports them with `deltaMag`, so the uncertainty column and error bars can refer to the main aperture even when the plotted variability comes from a different one. *Suggested next action:* carry errors with each aperture solution, retain the chosen aperture identity per curve, and add a focused test forcing an alternative aperture choice to verify the exported residual/error pairing.
2. **Reported uncertainty is a background-based approximation (`ApertureMeasurer`):** `ApertureMeasurer` (`:235-236`) uses `sigma_sky * sqrt(nAp * (1 + nAp / skyCount))` divided by stellar flux to obtain magnitude error. There is no explicit source-electron shot-noise term or ensemble-correction uncertainty (though annulus scatter can already contain background, dark and read fluctuations, which must not be double-counted without a defined variance model), nor does the formula explicitly represent the clipped-median estimator's uncertainty, retained sky sample size, fractional-weight squared sums, or correlations from registration (see also `XC-1`). *Suggested next action:* keep the approximation clearly identified, then validate any physical/empirical error model using known gain/scaling where available, blank apertures and constant-star repeatability.
3. **Stetson-style serial evidence ignores actual time gaps (`VariabilityScorer`):** `VariabilityScorer` (`:568-597`) pairs successive finite residuals without receiving timestamps, and the persistence and split-half methods likewise treat usable-point adjacency as temporal adjacency, so a pair separated by a long excluded interval receives the same pairing treatment as neighboring exposures. The implementation also assigns every point one `expectedScatter` rather than per-measurement uncertainty, making it an empirical magnitude-dependent adaptation rather than an exact implementation of Stetson's full J/K/L and Cepheid template pipeline. *Suggested next action:* assess time-aware pairing and gap-breaking with irregular-cadence fixtures after establishing credible per-observation uncertainties, preserving the current empirical noise baseline as a comparison.
4. **Candidates participate in their own ensemble reference (`EnsembleSolver`):** `EnsembleSolver` (`:132-171`) fits frame corrections using all usable stars and (`:121`) returns residuals for those same stars without per-candidate exclusion or an independent held-out reference. Robust weighting reduces self-influence but does not mathematically eliminate it: in a simplified fixed-weight, zero-point-only fit, a target carrying fraction `f` of the reference weight retains approximately `(1 - f)` of its signal after subtracting the correction it helped determine, and uneven weights or spatial fits can increase individual leverage. *Suggested next action:* measure ensemble leverage and compare candidate curves against a solve excluding that candidate, and assess independent held-out reference stars before changing the solver.
5. **Aperture consistency checks amplitude, not agreement through time (`VariabilityScorer`):** The `APERTURE` gate compares the 95th-minus-5th-percentile ranges of the small and large curves, which can have equal ranges while varying in opposite directions (recorded under `VS-5`). *Suggested next action:* compare matched-time aperture residuals with their uncertainties rather than relying solely on range agreement, particularly for variable seeing, blends and sky contamination.
6. **Discovery completeness is deliberately limited (`VariabilityScorer`):** Positive serial evidence, persistence, percentile amplitude and isolated-outlier removal favor changes lasting multiple frames, so rapid oscillations, single-frame events and brief eclipses can be missed. This is a selection function to quantify rather than evidence that all thresholds should be lowered; Stetson's Cepheid templates, period search, changing-color calibration and cycle-integrated mean flux are separate analysis stages not supplied by this discovery pipeline.

**Recommended order from the comparison:** (1) correct the selected-aperture/error association; (2) validate and label the uncertainty model; (3) assess timestamp-aware serial statistics and temporal aperture agreement; (4) measure candidate self-influence and held-out reference stability; (5) evaluate proposed behavior changes with artificial signals in images spanning varying sky, seeing, transparency, crowding and cadence, followed by real-data comparisons. Do not infer a calibrated false-alarm probability from the `HIGH_CONFIDENCE` label.

### Additional code-level findings (2026-11-02 follow-up)

1. **Selected-aperture consistency (`VariableStarAnalyzer.chooseApertures`, `VariabilityScorer.runGates`):** When `chooseApertures` replaces `curve.deltaMag` with an alternative aperture's residuals (`0.7x`, `1.0x` or `2.0x FWHM`), `curve.magError` and `curve.meanMag` remain from `R_MAIN`, the chosen aperture is not recorded on `StarLightCurve`, and `BAD_FLUX` / `SATURATED` / `NONLINEAR` flags are evaluated only for `R_MAIN`. In `VariabilityScorer.runGates`, `star.amplitude` is then the amplitude of the chosen aperture while the `APERTURE` gate still tests `|smallAmplitude - largeAmplitude| / star.amplitude`.
2. **Pre-solve star exclusions and shape-contamination pooling across already-excluded frames (`VariableStarAnalyzer`):** Frames failing registration or Check B are marked `frameActive[j] = false` before `flagContaminatedShapes` and the star-level `MAX_NONLINEAR_SHARE` / `photometryMinSnr` exclusions run, yet both loops iterate over all `nFrames` without checking `frameActive[j]`. Already-excluded frames can therefore bias a star's typical concentration, inflate the brightness-group shape spread, or exclude a star before the ensemble solve.
3. **Bridging across `NaN` / excluded frames in `removeIsolatedOutliers`, `differenceCorrelation` and serial statistics (`VariabilityScorer`):** `removeIsolatedOutliers` (`previousFinite` / `nextFinite`), `stetsonJ`, `longestDeviatingRun`, `splitHalfCorrelation` and `differenceCorrelation` treat consecutive finite array entries as adjacent observations regardless of excluded frames or timestamp gaps. Two isolated single-frame spikes separated by a gap of excluded frames can mutually support each other in `removeIsolatedOutliers`.
4. **Unclipped `skyCount` in `magError` (`ApertureMeasurer`):** `clippedMedianAndSigma` clips 3-sigma outliers in the sky annulus down to `kept` pixels, but `fluxSigma` uses the pre-clipping `skyCount` in `1 + nAp / skyCount`.
5. **Check D re-evaluation and unweighted slope tracking (`VariableStarAnalyzer`, `PhotometricReadinessChecker.tracks`):** Frames failing Check D (`response.frameFails[j]`) are excluded and the ensemble is re-solved without them, but `readiness.responseCheck` is not re-evaluated on the retained frames. In addition, `PhotometricReadinessChecker.tracks` fits per-frame slopes against zero points and sky levels with uniform weights (`weights[j] = 1.0`), ignoring `responseSlopeError`.
6. **Report and CSV omission of `CONTAMINATED`, and cutout scaling without the spatial plane (`PhotometryReportSectionWriter`):** `PhotometryFrameStat.contaminated` is counted by `VariableStarAnalyzer` and excludes measurements from the ensemble, but is omitted from both the HTML Per-Frame Measurements table and `photometry_frames.csv`. In `matchedCutouts`, the faintest-frame cutout is scaled using only `faintZeroPoint - brightZeroPoint`, omitting the fitted spatial plane term `planeX * xNorm + planeY * yNorm`.

### Architectural assessment and further algorithmic items (2026-11-02 follow-up)

**Overall assessment.** The 4-stage pipeline (**Select & Measure -> Readiness & Frame Filtering -> Iterative WLS + Spatial Plane -> Coherence + Diagnostic Gates**) is sound and well-suited to short, single-session amateur/survey sequences (10-100 frames) without external catalog calibration or flat-field metadata:
- Alternating WLS (`EnsembleSolver`) with iteratively downweighted variable stars and per-frame 2D spatial plane detrending (`SpatialFrameFilter`) is the appropriate foundational model for raw pixel sequences lacking external reference catalogs.
- Readiness gates (`PhotometricReadinessChecker` Checks A-D for saturation/linearity, seeing stability, spatial gradients and instrumental magnitude-response linearity) directly diagnose the dominant failure modes of amateur stacks (clouds, dewing, focus drift, stretched/non-linear FITS).
- Requiring temporal coherence (lag-1 autocorrelation, Von Neumann `eta`, block-binned retention in `VariabilityScorer`) together with multi-aperture agreement and local-comparison checks is the right way to suppress noisy false positives where formal photon+sky errors understate true systematics.

**Additional algorithmic items to implement/evaluate alongside the code-level fixes:**

1. **Magnitude-dependent empirical error floor (`sigma_sys(m)`) in scoring and gating (`EnsembleSolver`, `VariabilityScorer`):**
   - *Current behavior:* `EnsembleSolver` adds a single constant `SYS_FLOOR = 0.003` mag in quadrature with formal photon+sky error (`magError`), while `VariabilityScorer.rmsExpectedRatio` fits a running-median empirical RMS-vs-magnitude floor only for display/reporting (`rmsExpectedRatio` is unused in scoring or gating).
   - *Problem:* When camera gain is unknown (`DEFAULT_GAIN = 1.0` in `ApertureMeasurer`) or scintillation/flat-field errors dominate bright stars, `magError` can be off by 2x-5x. Because `chi2Red` (`gateChi2Red`) and `amplitude / medianSigma` (`gateAmplitudeToNoise`) divide by `magError`, bright stars can pass on scintillation alone while faint stars can be over-penalized when true gain `< 1 e-/ADU`.
   - *Action:* Use the empirical RMS-vs-magnitude locus of constant stars (already computed by `VariabilityScorer.rmsExpectedRatio`) to rescale `magError` or incorporate `rmsExpectedRatio` directly into noise gating.
2. **Per-frame centroid refinement vs. rigid global transform (`PhotometryStarSelector`, `VariableStarAnalyzer`, `ApertureMeasurer`):**
   - *Current behavior:* Star positions are measured once on the reference frame and projected into each frame via the global `FrameTrans`. `ApertureMeasurer.measure` integrates the aperture at that exact projected coordinate while passively computing first-moment centroids (`centroidX`, `centroidY`) inside the aperture.
   - *Problem:* Differential atmospheric refraction or higher-order optical distortion combined with tracking drift can shift stars near field edges by 0.3-1.5 px relative to a rigid global transform. In small apertures (`0.7x` or `1.0x FWHM`), a systematic sub-pixel centering drift creates a smooth, coherent light-curve trend that passes coherence checks and triggers the `CENTROID` gate.
   - *Action:* Recenter bright/high-SNR stars per frame within a small windowed search radius around the projected coordinate (while keeping fixed projected offsets for faint stars) to eliminate centering-drift trends at the source.
3. **Adaptive coherence block size and fast/eclipsing variable recovery (`VariabilityScorer`):**
   - *Current behavior:* Every candidate must satisfy `coherencePass` (lag-1 autocorrelation or low Von Neumann `eta`, plus `binnedRetention >= photometryMinBinnedRetention` over fixed 3-frame blocks), and `removeIsolatedOutliers` strips single-frame excursions before scoring.
   - *Problem:* In slower-cadence or shorter sequences (e.g., 15-20 frames), sharp eclipsing binary minima, flares, or short-period pulsators whose half-cycle spans only 2-3 frames are washed out by fixed 3-frame block averaging.
   - *Action:* Make the block size adaptive to cadence/active frame count (e.g., 2-frame blocks when `activeFrames < 20`) or provide a secondary multi-aperture-confirmed path for high-SNR short-duration dips/flares.
4. **Sub-pixel integration accuracy for small comparison apertures (`ApertureMeasurer`):**
   - *Current behavior:* `ApertureMeasurer` uses a linear radial boundary ramp `w = clamp(r_ap + 0.5 - r, 0, 1)` based on pixel-center distance `r`.
   - *Problem:* While accurate for `r_ap >= 2.5` px, tight undersampled PSFs (`FWHM ~ 2.2` px) produce a `0.7x` `R_SMALL` aperture of `r_ap ~ 1.5` px, where the linear radial ramp's effective area varies by several percent with sub-pixel center phase (`(0.0, 0.0)` vs. `(0.5, 0.5)`). This injects phase noise specifically into `R_SMALL`, inflating `|smallAmplitude - largeAmplitude|` and causing false `APERTURE` gate flags on undersampled setups.
   - *Action:* Enforce a safe minimum radius for `R_SMALL` or use sub-pixel oversampling (e.g., 5x5) when `r_ap < 3` px.
5. **Differential color extinction / zero-point correlation check (`VariabilityScorer`, `PhotometricReadinessChecker`):**
   - *Current behavior:* `SpatialFrameFilter` removes spatial `(x, y)` gradients and Check D removes global magnitude-dependent response nonlinearity.
   - *Problem:* In unfiltered broadband sequences spanning a significant altitude change, very red or blue stars dim at a different rate with airmass than the ensemble average, producing a smooth, monotonic 0.02-0.05 mag trend that correlates linearly with frame zero point `z_j`.
   - *Action:* Measure correlation between a candidate's light curve `deltaMag_j` and the frame zero point `z_j` (analogous to Check D's slope-vs-`z_j` check) and flag/warn when variability is explained by differential extinction tracking `z_j`.

**Summary recommendation.** Retain the 4-stage architecture; do not redesign the pipeline. Implement the bounded correctness fixes in `VS-11` together with empirical RMS-vs-magnitude noise scaling and small-aperture sub-pixel/centroid handling to cover the remaining practical edge cases.

## VS-12. Consolidated photometry improvement roadmap

**Purpose (2026-11-02).** Consolidate all open stationary-star photometry proposals ([VS-2](#vs-2-convergence-of-the-full-ensemble-correction), [VS-5](#vs-5-temporal-agreement-between-photometry-apertures), [VS-9](#vs-9-selected-stars-and-the-linearity-check-minimum), [VS-10](#vs-10-deterministic-streak-interpolation-for-photometry) and [VS-11](#vs-11-photometry-strictness-defaults-and-recovery-review)), the Romanishin (2002) and Stetson (1996) comparison, the code-level defects and the architectural review into one prioritized improvement plan, backed by six real reference sequences including `klangwolke-2` and `M81-kumar-L-dataset-registered`.

### Big-picture diagnosis

1. **The 4-stage architecture is sound and does not need a redesign.** Independent master-star extraction and grid/brightness selection (`PhotometryStarSelector`), per-star local sky-annulus subtraction (`ApertureMeasurer`), pre-solve readiness and frame filtering (`PhotometricReadinessChecker`), alternating robust WLS with a 2D spatial plane (`EnsembleSolver`, `SpatialFrameFilter`), and coherence-plus-gate candidate scoring (`VariabilityScorer`) are well-matched to uncalibrated 10-100 frame amateur/survey sequences lacking external reference catalogs.
2. **Correctness and internal consistency defects currently distort evidence before policy thresholds act.** Across the pipeline, several steps mix state across apertures or across already-excluded frames:
   - `VariableStarAnalyzer.chooseApertures` replaces `deltaMag` for 88 % of scored stars on `klangwolke-2` and `M81-kumar-L-dataset-registered` while leaving `magError`, `meanMag`, measurement flags and the `APERTURE` gate denominator tied to the `1.5x FWHM` main aperture.
   - Already-excluded frames still participate in `flagContaminatedShapes`, pre-solve star exclusions (`MAX_NONLINEAR_SHARE`, `photometryMinSnr`) and the `LINEARITY` candidate gate.
   - `removeIsolatedOutliers`, `stetsonJ`, `longestDeviatingRun`, `splitHalfCorrelation` and `differenceCorrelation` bridge across excluded/`NaN` frames and timestamp gaps as if non-adjacent frames were consecutive exposures.
3. **Readiness and `LIMITED` policy combine distinct physical causes under one broad penalty.** A session with rock-steady atmospheric transparency (`zeroPointRangeMag < 0.05` mag, as on `M81-kumar-L-dataset-registered` at `0.036` mag, or `klangwolke-2` at `0.056` mag with only `0.006` mag margin) gets `responseCheck = INCONCLUSIVE` -> `LIMITED`, which doubles the candidate amplitude floor from `0.05` to `0.10` mag (`LIMITED_MIN_AMPLITUDE`) even when frame shapes and residuals are clean.

### Real-data baseline across six reference sequences

All six sequences were run through the public SpacePixels headless API with the shipped profile and photometry enabled (no Auto-Tune, no source modifications):

| Dataset | Frames (input / analyzed / used) | Session FWHM (px) | Readiness verdict & primary reason | Selected / scored stars | Candidates (`HIGH` / `POSSIBLE` / `REJECTED`) | Key diagnostic evidence on this dataset |
|---|---|---|---|---|---|---|
| `klangwolke-2` | 33 / 28 / 28 | 3.70 | `READY` (`zeroPointRangeMag = 0.056` mag, just `0.006` mag above `INCONCLUSIVE`) | 53,914 / 43,603 | 47 / 22 / 27 (96 total) | **38,288 / 43,603 scored stars (87.8 %)** and **42 / 69 reported candidates (60.9 %)** choose `1.0x` (`10,936`) or `0.7x FWHM` (`27,352`) while keeping `1.5x` `magError`/flags. `17,007` `CONTAMINATED` measurements omitted from report/CSV. `13` `HIGH` and `5` `POSSIBLE` candidates have `0.05 <= amp < 0.10` mag and would be demoted if transparency were `0.007` mag steadier (`LIMITED`). |
| `M81-kumar-L-dataset-registered` | 41 / 39 / 32 | 2.24 | `LIMITED` (median linear range `2.14` mag; `7` shape-failing frames; `responseCheck = INCONCLUSIVE` at `zeroPointRangeMag = 0.036` mag) | 6,155 / 3,435 | 10 / 18 / 22 (50 total) | **3,026 / 3,435 scored stars (88.1 %)** and **28 / 28 reported candidates (100 %)** choose `1.0x FWHM` (`r = 2.24` px) while keeping `1.5x` `magError`/flags. Tight PSF (`FWHM = 2.24` px) puts `0.7x` `R_SMALL` at `r = 1.57` px (sub-pixel ramp regime). All `50 / 50` candidates have internal `NaN` gaps from the 7 excluded Check-B frames. `6` scored stars carry `SATURATED`/`NONLINEAR` flags only on excluded frames. `4,632` `CONTAMINATED` measurements omitted from report/CSV. `PERSISTENCE` is the sole failed gate for `14 / 18` `POSSIBLE` candidates. |
| `m5-reg` | 36 / 32 / 26 | 3.81 | `LIMITED` (median linear range `2.20` mag; `6` shape-failing frames) | 418 / 253 | 0 / 0 / 1 (1 total) | `42 / 253` scored stars carry `SATURATED`/`NONLINEAR` flags only on the 6 excluded frames. |
| `helix-reg` | 45 / 39 / 28 | 4.86 | `NOT_READY` (`11 / 39` frames = `28.2 %` fail Check B, above `20 %` session cap, though 28 passing frames remain) | 354 / 0 | 0 / 0 / 0 | Allowing the 28 passing frames (`35 %` cap) yields `LIMITED` with 174 scored stars (`27` carrying `NONLINEAR` only on excluded frames). |
| `dc-reg` | 59 / 52 / 48 | 2.51 | `NOT_READY` (Check D slopes track sky at `r = -0.706`, just beyond `-0.70`) | 8,081 / 0 | 0 / 0 / 0 | Unweighted slope fit in `PhotometricReadinessChecker.tracks` gives equal leverage to noisy frames; Check D is not re-evaluated after excluding the 3 failing Check-D frames. |
| `m31-red-reg` | 49 / 43 / 16 | 3.92 | `NOT_READY` (`27` response-failing frames, slope tracking, `< 20` final frames) | 429 / 0 | 0 / 0 / 0 | Genuine multi-barrier rejection control (`NOT_READY` should be preserved). |

---

### Prioritized improvements to implement

#### Phase 1: Bounded correctness and internal consistency (implement first; zero policy looseness)

These items fix internal state mismatches where the code does not evaluate what it intends to evaluate:

1. **Selected-aperture consistency (`VariableStarAnalyzer.chooseApertures`, `StarLightCurve`, `VariabilityScorer.runGates`) — from [VS-11](#comparison-with-romanishin-2002-and-stetson-1996-2026-11-02) Finding 1 & [follow-up](#additional-code-level-findings-2026-11-02-follow-up) #1:**
   - *Fix:* Carry `magError`, `starMag` (`meanMag`) and per-measurement quality flags (`BAD_FLUX`, `SATURATED`, `NONLINEAR`) per aperture solution; when `chooseApertures` selects an alternative aperture (`0.7x`, `1.0x` or `2.0x FWHM`) for a brightness bin, update `curve.deltaMag`, `curve.magError` and `curve.meanMag` together, record the selected aperture factor/radius on `StarLightCurve`, and normalize the `APERTURE` gate against a consistent reference amplitude.
   - *Real-data impact:* Directly corrects the error bars, mean magnitudes and gate denominators for **87.8 % of scored stars (`42 / 69` reported candidates) on `klangwolke-2`** and **88.1 % of scored stars (`28 / 28` reported candidates) on `M81-kumar-L-dataset-registered`**.
2. **Exclude already-rejected frames from pre-solve star exclusions, shape contamination and the `LINEARITY` gate (`VariableStarAnalyzer`, `VariabilityScorer`) — from [VS-11](#gate-and-default-assessment) & [follow-up](#additional-code-level-findings-2026-11-02-follow-up) #2:**
   - *Fix:* Guard `flagContaminatedShapes`, the star-level `MAX_NONLINEAR_SHARE` (`> 50 %` nonlinear) and `photometryMinSnr` exclusions, and `VariabilityScorer`'s `LINEARITY` gate by `frameActive[j]` so that frames already rejected by registration or Check B/D cannot distort `typical` concentration, inflate shape `spread`, exclude stars before the solve, or demote candidates via `LINEARITY`. Retain `LINEARITY` warnings for saturated/nonlinear measurements that occur on *active* frames.
   - *Real-data impact:* Removes excluded-frame leakage on `M81-kumar-L-dataset-registered` (7 excluded Check-B frames, 6 history-only stars), `m5-reg` (6 excluded frames, 42 history-only stars) and `helix-reg` (11 excluded frames, 27 history-only stars).
3. **Deterministic streak-piece interpolation (`VariableStarAnalyzer.flagTrack`) — from [VS-10](#vs-10-deterministic-streak-interpolation-for-photometry):**
   - *Fix:* When a streak track skips a frame and multiple streak pieces share a bounding frame, select the interpolation reference and footprint geometry deterministically (independent of input piece list order) without expanding masks into a full path-wide veto.
   - *Real-data impact:* Eliminates permutation dependence in unobserved-frame streak masking on `klangwolke-2`.
4. **Retained sky pixel count in `magError` (`ApertureMeasurer`) — from [VS-11 follow-up](#additional-code-level-findings-2026-11-02-follow-up) #4:**
   - *Fix:* Return the post-clipping `kept` sky pixel count from `clippedMedianAndSigma` and use `kept` (rather than the unclipped annulus count `skyCount`) in `fluxSigma = skySigma * sqrt(nAp * (1 + nAp / kept))`.
5. **Report/CSV `CONTAMINATED` column and spatial-plane cutout scaling (`PhotometryReportSectionWriter`) — from [VS-11 follow-up](#additional-code-level-findings-2026-11-02-follow-up) #6:**
   - *Fix:* Include `PhotometryFrameStat.contaminated` in both the HTML Per-Frame Measurements table and `photometry_frames.csv`, and include the fitted spatial plane term `planeX * xNorm + planeY * yNorm` alongside `faintZeroPoint - brightZeroPoint` when scaling the faintest-frame comparison cutout in `matchedCutouts`.
   - *Real-data impact:* Surfaces `17,007` previously hidden frame-table exclusions on `klangwolke-2` and `4,632` on `M81-kumar-L-dataset-registered`.

---

#### Phase 2: Solver convergence, temporal aperture agreement and readiness policy (`VS-2`, `VS-5`, `VS-9`, `VS-11`)

1. **Full-correction convergence in `EnsembleSolver` — from [VS-2](#vs-2-convergence-of-the-full-ensemble-correction):**
   - *Fix:* Check convergence on the maximum change in predicted frame correction `|Delta Z(j) + Delta a(j) * x + Delta b(j) * y|` across usable stars (covering both zero points and spatial plane coefficients in magnitude units), and record whether the solver converged or hit `MAX_ITERATIONS`.
2. **Temporal agreement in the `APERTURE` gate and small-aperture sub-pixel safety (`VariabilityScorer`, `ApertureMeasurer`) — from [VS-5](#vs-5-temporal-agreement-between-photometry-apertures) & [VS-11 architectural item](#architectural-assessment-and-further-algorithmic-items-2026-11-02-follow-up) #4:**
   - *Fix:* Evaluate `APERTURE` on the intersection of frames where small, main and large aperture residuals are all finite; require uncertainty-aware temporal agreement (not just equal 5th-95th percentile range, which currently passes reversed curves in 30/30 controls); and enforce a minimum effective pixel radius or sub-pixel oversampling when `R_SMALL` falls below `~2.0` px (as on `M81-kumar-L-dataset-registered`, where `FWHM = 2.24` px gives `0.7x` `r_ap = 1.57` px).
   - *Real-data impact:* `APERTURE` is the #1 failing gate on `klangwolke-2` (`29 / 96` candidates) and fails `8 / 50` candidates on `M81-kumar-L-dataset-registered`.
3. **Check B effective reference-star count and Check D weighted slope tracking + post-exclusion re-evaluation (`PhotometricReadinessChecker`, `DetectionConfigRules`, `VariableStarAnalyzer`) — from [VS-9](#vs-9-selected-stars-and-the-linearity-check-minimum) & [VS-11 follow-up](#additional-code-level-findings-2026-11-02-follow-up) #5:**
   - *Fix:*
     - Account for the `90 %` faint bound when validating `photometryMaxStars` against `linearityMinStars` (`>= 55` ideal distinct stars for 50 counted references) and distinguish insufficient reference count (`INCONCLUSIVE`) from measured bright-end shape nonlinearity (`FAIL`).
     - Weight the slope regressions in `PhotometricReadinessChecker.tracks` by `1 / responseSlopeError^2` instead of uniform `1.0` weights, and re-evaluate `readiness.responseCheck` on the retained frames after excluding frames where `response.frameFails[j]` is true.
     - Decouple `responseCheck = INCONCLUSIVE` caused solely by stable sky transparency (`zeroPointRangeMag < 0.05` mag, as on `M81-kumar-L-dataset-registered` and nearly on `klangwolke-2`) from doubling the candidate amplitude floor (`LIMITED_MIN_AMPLITUDE = 0.10` mag); stable transparency is benign when frame slopes are flat.

---

#### Phase 3: Gap-aware serial statistics, empirical noise calibration and centroid/extinction refinements

1. **Gap-aware outlier filtering and serial statistics (`VariabilityScorer`) — from [VS-11](#comparison-with-romanishin-2002-and-stetson-1996-2026-11-02) Finding 3 & [follow-up](#additional-code-level-findings-2026-11-02-follow-up) #3:**
   - *Fix:* Do not bridge across excluded/`NaN` frames (or large timestamp gaps when timestamps are available) in `removeIsolatedOutliers`, `stetsonJ`, `longestDeviatingRun`, `splitHalfCorrelation` and `differenceCorrelation`. Only immediately adjacent active frames (or pairs within a bounded cadence multiple) should count as serial neighbors.
   - *Real-data impact:* Directly affects `M81-kumar-L-dataset-registered`, where all `50 / 50` score candidates contain internal `NaN` gaps from the 7 excluded Check-B frames.
2. **Empirical magnitude-dependent error floor and candidate self-leverage check (`ApertureMeasurer`, `EnsembleSolver`, `VariabilityScorer`) — from [VS-11](#comparison-with-romanishin-2002-and-stetson-1996-2026-11-02) Findings 2 & 4, and [architectural item](#architectural-assessment-and-further-algorithmic-items-2026-11-02-follow-up) #1:**
   - *Fix:* Use the empirical RMS-vs-magnitude locus of constant stars (already estimated by `VariabilityScorer`) to calibrate per-point `magError` / noise gating when camera gain or scintillation floor is unknown, and exclude or leave-one-out adjust high-leverage candidate stars from the final ensemble reference so a bright variable cannot attenuate its own residual by `(1 - f)`.
3. **Per-frame windowed centroid refinement and differential extinction warning (`VariableStarAnalyzer`, `ApertureMeasurer`, `VariabilityScorer`) — from [VS-11 architectural items](#architectural-assessment-and-further-algorithmic-items-2026-11-02-follow-up) #2 & #5:**
   - *Fix:* Evaluate a small windowed per-frame centroid refinement for high-SNR stars to prevent edge-of-field differential refraction/distortion drift from masquerading as smooth variability in `0.7x`/`1.0x` apertures, and flag candidates whose `deltaMag` correlates strongly with frame zero point `z_j` (second-order color/airmass extinction).

---

### Verification protocol for VS-12

Every phase must be verified against:
1. **Unit and synthetic sensitivity suite:** `PhotometrySensitivityTest` and `PhotometricReadinessCheckerTest` (covering selected-aperture error/flag propagation, excluded-frame isolation, deterministic streak-piece permutations, `EnsembleSolver` spatial-plane convergence, opposite-phase vs. noisy coherent `APERTURE` curves, and gap-aware `removeIsolatedOutliers` / `stetsonJ`).
2. **Real-data regression batch:** Run all six reference sequences (`klangwolke-2`, `M81-kumar-L-dataset-registered`, `m5-reg`, `helix-reg`, `dc-reg`, `m31-red-reg`) before and after each phase, recording readiness verdicts, retained frames, aperture choices, gate failure counts, candidate lists (`HIGH_CONFIDENCE`, `POSSIBLE`, `REJECTED`), and processing time.

### Detailed review by Codex (2026-11-02)

**Recommendation: revise before implementation.** I agree with preserving the existing architecture, fixing the selected-aperture/error association, isolating rejected-frame evidence, and addressing VS-2, VS-5 and the bounded VS-10 correction. I disagree with treating this entire roadmap as mechanical correctness work. Some premises describe code that is absent from this checkout, and several actions change scientific selection, uncertainty interpretation or readiness policy. Those need separate decisions and recovery/false-positive evidence.

**Review basis.** I checked the current photometry sources and SpacePixels' report writer, the six baseline result files, the three earlier setting comparisons and the existing tests. The two additional full-resolution API runs completed sequentially: Klangwolke-2 was READY with 47 HIGH_CONFIDENCE / 22 POSSIBLE / 27 REJECTED candidates; M81 Kumar was LIMITED with 10 / 18 / 22. All 74 additional original FITS files retained their SHA-256 hashes. The earlier JTransient `check` and `apiConsumer` passed 297 tests; this document review did not rerun that suite or change production behavior. Candidate tiers have not been verified against astrophysical truth.

#### R1. Correct the description of the implementation used to justify Phase 3

**Material disagreement.** The preceding VS-11 architectural assessment names mechanisms that do not exist in the reviewed photometry package. VS-12 inherits recommendations from that assessment, so it must correct the basis first:

| Claim in the supporting assessment | Current source evidence |
|---|---|
| `SYS_FLOOR = 0.003` added in quadrature to formal errors | `EnsembleSolver` uses an empirical per-star residual scatter, a magnitude-neighbour expected scatter, their maximum, and `SIGMA_FLOOR = 0.001`. Its fitting weights are Cauchy point weights divided by that empirical variance. |
| `DEFAULT_GAIN = 1.0` and formal photon-plus-sky errors | `ApertureMeasurer` has no gain term. Its stored error is the background-based approximation described in the literature comparison. |
| `rmsExpectedRatio` is display-only; `chi2Red`, `gateChi2Red` and `gateAmplitudeToNoise` drive detection | These identifiers are absent. `expectedScatter` already affects `scatterZ`, Stetson J, the AMPLITUDE threshold, PERSISTENCE and comparison-star selection. The empirical magnitude-dependent model is active science, not just a plot. |
| `FrameTrans`, per-star `centroidX`/`centroidY`, and a `CENTROID` gate | The photometry position correction is a robust common X/Y offset measured from sampled stars. These named products and gate are absent. |
| `coherencePass`, Von Neumann eta and fixed three-frame `binnedRetention` | The current discovery path uses scatter and adjacent-pair J, then eight named gates. It does not implement these additional coherence statistics or a three-frame retention rule. |

See [EnsembleSolver](src/main/java/io/github/ppissias/jtransient/photometry/EnsembleSolver.java), [VariabilityScorer](src/main/java/io/github/ppissias/jtransient/photometry/VariabilityScorer.java), and [ApertureMeasurer](src/main/java/io/github/ppissias/jtransient/photometry/ApertureMeasurer.java). The unreferenced 2x–5x error bias and 0.3–1.5 px centroid drift ranges are hypotheses, not measurements established here.

The architectural introduction also overstates input suitability. Defaults require at least 20 quality-filtered frames, not ten. Original linear, appropriately calibrated and registered input remains the intended basis; a comparison-star ensemble cannot generally repair missing flat-field calibration or certify arbitrary uncalibrated images. The shape and response checks constrain suitability without proving calibration.

#### R2. Phase 1.1: fix error association, but define the magnitude and flag contracts first

**Agree with the demonstrated inconsistency.** `chooseApertures` can replace `deltaMag` while the curve keeps main-aperture `magError`. The new runs exercise that branch: 42 of 69 reported Klangwolke-2 candidates and all 28 reported M81 candidates use alternatives. Recording the aperture identity and associating its own errors with its residuals is high-value bounded work.

**Disagree with two parts of the diagnosis/fix.** The APERTURE denominator is already `star.amplitude`, calculated from the selected curve by `VariabilityScorer.score`. It is not still tied to the 1.5-FWHM main curve. Choosing a different normalization could be useful, but would be a gate-policy change rather than correction of the stated stale-denominator bug.

`meanMag` is also the common main-aperture brightness coordinate used for magnitude-neighbour comparisons and aperture-bin assignment. Simply replacing it with each selected aperture's instrumental mean can introduce aperture-dependent offsets and discontinuities into that common coordinate. Define whether the contract needs a reference magnitude, a selected-aperture mean, or both; keep noise modelling and exports explicit about the choice. A mismatch in error provenance does not prove that every retained reference magnitude is numerically wrong.

Per-aperture flags likewise need definitions. Check B's NONLINEAR limit is derived in main-aperture magnitude coordinates; it cannot be transferred to a different instrumental magnitude without a justified mapping. BAD_FLUX, geometry, saturation and crossing support must correspond to the measurements actually admitted, while preserving protections that remain intentionally common. Alternative fits currently reuse the main usable matrix, and their finite samples can differ. Compare aperture quality on matched valid evidence so an alternative does not win merely by losing difficult points.

Record the selected factor or aperture identity, with enough shared frame geometry to obtain actual per-frame radii. A single radius per curve is insufficient when measured FWHM changes between frames. The first correction can preserve the existing selection policy while fixing error/identity provenance; changing brightness coordinates, mask acceptance or gate normalization should be reviewed separately.

#### R3. Phase 1.2: agree on rejected-frame isolation; the lifecycle needs a precise design

The pre-solve shape statistics and star exclusions do inspect frames already rejected by registration or Check B. The final LINEARITY gate scans historical flags from all frames. These are concrete places to review evidence isolation.

There is stronger SNR evidence than the history-only flag counts alone: on the existing M81 measurements, **73 stars excluded for median SNR would meet SNR 10 when only final active frames contribute**. M5 has two crossings in that direction and six in the other direction. This is an isolated criterion comparison; it does not establish how many extra stars would survive a complete rerun or become real variable detections.

The proposed single `frameActive` guard cannot retroactively remove Check-D or spatially rejected frames from statistics computed before those stages. Define the mask available to each operation and whether any final-mask recomputation is needed. A bounded recomputation, if selected, must account for the dependency between selected stars, the ensemble and later frame rejection; repeatedly recomputing until the checks pass would be a different algorithm.

For LINEARITY, filter by the final **frame** mask while still inspecting saturated/nonlinear measurements on retained frames. Filtering by the existing `usable` measurement matrix instead would suppress the warning itself, because that matrix already excludes those flagged measurements. Preserve historical flags for display.

The six M81 history-only stars do not demonstrate six lost candidates: none of its 50 score candidates fails LINEARITY solely from excluded-frame flags. State potential exposure and measured classification changes separately. Add tests that poison only an excluded frame, exercise every exclusion stage, and confirm that clipped measurements on active frames still warn.

#### R4. Phase 1.3: retain the narrow deterministic interpolation scope

**Agree.** Piece-order independence is a bounded correctness requirement. Keep the original VS-10 policy questions, timestamp/sequence identities and observed-frame behavior intact. Tests should permute fragments with shared bounding-frame indices and equidistant shape choices. The new ordinary Klangwolke-2 run did not repeat a permutation experiment, so its output alone does not demonstrate the claimed masking correction or its size. Do not expand the mask across an entire unobserved trail.

#### R5. Phase 1.4: retained sky count helps an approximation; it is not a complete error correction

Using a justified post-clipping sky sample count is worth evaluating. However, replacing `skyCount` with `kept` in the existing formula still does not model a clipped-median estimator's variance, fractional-pixel squared weights, covariance from registration, or source shot noise. The final retained count also needs to correspond to the sample that produced the returned estimator.

Calling all Phase-1 changes free of scientific-selection effects is misleading: `magError` participates in Check B's 0.05-mag cut and the median-SNR exclusion before scoring. A larger estimated error can change those outcomes even if configured thresholds stay fixed. Test estimator/count behavior, fractional-pixel controls and the resulting frame/star decisions. Continue labelling the error approximation accurately, and keep arbitrary correlation corrections outside the binding XC-1 decision.

#### R6. Phase 1.5: split aggregate reporting from display calibration

**Agree** that the per-frame HTML table and `photometry_frames.csv` omit the `contaminated` aggregate. Adding that column is useful and low-impact. The exclusions are not wholly absent from reports: contamination flags already appear in light-curve tooltips and the per-candidate light-curve CSV. Counts such as 17,007 are measurements across stars and frames, can overlap other flags, and are not numbers of undisclosed objects.

Including the spatial correction in cutout scaling is a separate display change. Evaluate `C(j) = Z(j) + a(j)x + b(j)y` at the star's original normalized field coordinates and use the difference of the appropriate corrections. Do not normalize the star position within its cropped thumbnail. More importantly, frame telemetry currently carries the main solution, whereas many displayed curves use another aperture's solution. Decide whether the display explicitly shows the main reference correction or retains the selected solution's corrections; adding main-plane coefficients alone does not guarantee agreement with a selected-aperture curve.

Preserve local-sky matching, missing/nonfinite-correction behavior, clear captions and source pixels. Verify that this change cannot alter measurements or candidate selection. Do the column addition independently so it need not wait for the display-calibration contract.

#### R7. Phase 2.1: predicted-correction convergence is the right direction

**Agree.** Comparing predicted corrections in magnitude units at usable star positions avoids comparing dimensionless plane-coordinate coefficients directly with a magnitude tolerance. Compare solutions in a consistent gauge, account for active/solved frames, and verify stability after robust star/point reweighting as well as in the zero-point-only case.

Report convergence and iteration exhaustion separately. Decide explicitly how a nonconverged result is presented and whether it changes readiness; do not silently introduce a new rejection through telemetry. Test the symmetric-gradient failure, changing weights, missing data, insufficient spatial leverage and iteration exhaustion. The earlier numerical reproduction is historical evidence, not a probe rerun during this review.

#### R8. Phase 2.2: temporal agreement is valuable; the small-aperture premise is incorrect

**Agree** on shared finite frames and a temporal comparison that rejects reversed curves while retaining noisy coherent variability. Define whether the reference is the selected curve or fixed main curve, and use matching observations after exclusions. Apertures overlap and their ensemble residuals are correlated, so a difference-error calculation must not assume independent aperture errors without validation. Existing empirical noise information can support initial controls; a complete new physical uncertainty model need not block investigation of VS-5.

**Disagree with the stated geometry diagnosis.** `R_SMALL` is **1.0 x FWHM**. The 0.7 factor belongs to `R_CI_INNER`, which also supplies the optional tiny aperture. [PhotometryGeometry](src/main/java/io/github/ppissias/jtransient/config/PhotometryGeometry.java) owns these radii, and `ApertureMeasurer.pixelWeight` already uses **5 x 5 fractional-pixel supersampling**. Alternative aperture selection also already has a two-pixel guide-radius safeguard. The proposed generic addition of supersampling duplicates an existing mechanism.

M81's session guide FWHM is 2.238 px, while the median measured per-frame FWHM is 2.041 px and its minimum is 1.727 px. Actual measurement radii use those frame measurements and the existing pixel floor. Thus some small radii do deserve tests, but `0.7 x 2.24 = 1.57 px` does not describe the gate's fixed R_SMALL aperture. Clamping the CI aperture or changing a comparison radius requires remeasuring its flux and recalibrating the relevant shape/gate behavior. Do not change a radius only in a gate after flux was already measured elsewhere.

The 29 APERTURE failures on Klangwolke-2 and eight on M81 establish usage, not that undersampling caused any failure or that those candidates were true variables. Require subpixel phase, seeing, blending and real-signal controls before choosing a new radius rule.

#### R9. Phase 2.3: separate reference counts, slope weighting and readiness policy

**Check B:** agree on the distinct-magnitude 50/55 problem and better status explanations. Fifty-five is an ideal lower bound for the current rank policy, not a guarantee after flags or range exclusions. Ties also affect the bound. The current cap rule is a warning; do not turn it into an unconditional hard rejection without a separate input-policy decision. Changing a per-frame result from FAIL to INCONCLUSIVE changes which frames enter the session failing-fraction denominator, so it can change readiness as well as wording. Test that consequence explicitly.

**Slope tracking:** inverse-variance weighting is a proposal to test, not an established correction. `tracks` currently combines an unweighted Pearson correlation with a fitted slope/range test. Weighting only the fitted slope leaves the correlation criterion unweighted. Any coherent alternative must handle missing, zero or unreliable slope errors and the fact that slopes and zero points are estimated from the same ensemble. Compare false tracking on known-linear data and recovery of injected response faults before choosing weights or significance rules. DC's r = -0.706 does not by itself prove a false rejection.

**Post-exclusion rechecking:** retain the original-session diagnosis separately from a validated retained-subset result. Removing frames selected precisely because of their response and then declaring PASS on the survivors can hide a real calibration fault. A bounded, clearly identified second assessment needs known-linear and known-nonlinear controls, minimum remaining evidence and a stopping rule; it must not become repeated trimming until READY.

**Stable transparency:** little variation is physically welcome but leaves response leverage unavailable. Flat measured slopes do not independently prove linearity. Reason-specific LIMITED handling may be worth changing, but requires a scientific-policy decision. M81 would remain LIMITED from its **2.14-mag shape range** even if its inconclusive response alone stopped causing LIMITED. Klangwolke-2 is presently READY; it is not an example of an actual inconclusive response. The 13 high-tier and five possible low-amplitude candidates illustrate what a **status-only change with all curves frozen** would do. A physically steadier-transparency sequence has not been simulated, so the stated 0.007-mag counterfactual is not a measured recovery result.

#### R10. Phase 3.1: gap handling is an explicit sensitivity policy

The adjacency limitation is real, and all 50 M81 score candidates do have gaps. That establishes relevance without establishing a wrong verdict. Strictly breaking every missing-point boundary can also discard useful support for smooth genuine variables.

Define gaps using original frame identities and available capture times, including frames rejected before photometry begins. Specify behavior for irregular cadence and absent timestamps, the minimum usable pair counts, and whether outlier support, J, persistence, split pairs and driver differences need identical gap rules. A cadence multiple must be calibrated rather than selected as a new unexplained constant. The related earlier VS-6 proposal was closed; record the new evidence and the new maintainer decision before adopting a changed policy.

Test coherent variables across gaps, abrupt isolated artifacts, short eclipses, missing metadata and clustered observing windows, measuring both recovery and false acceptance. Do not derive the policy from a single example or classify gap-aware metrics as automatically more complete.

#### R11. Phase 3.2: empirical error calibration and self-influence are different investigations

The current empirical noise locus already constrains detection and ensemble fitting (R1). An ensemble residual RMS is a total repeatability estimate and is not automatically a calibrated uncertainty for each exposure. Assigning it to every point loses frame-dependent noise, and adding it to formal error can count the same component twice. Separate reporting errors, selection errors, frame-fit weights and empirical detection thresholds; validate coverage with constant-star repeatability, blank apertures and realistic resampling before connecting them differently.

Candidate self-influence is a legitimate risk to **measure**. The `(1 - f)` attenuation describes a simplified frozen-weight zero-point fit, not the current adaptive solver. Robust downweighting and a spatial plane change its behavior. First measure conditional leverage and compare a bounded set of candidate-excluded fits or held-out controls; do not remove every candidate from every reference, assume a measured amount of lost amplitude, or adopt a new per-target solver for tens of thousands of stars without evidence of value and acceptable cost.

#### R12. Phase 3.3: centroid refinement and extinction warnings need separate scope

The supporting `FrameTrans`/CENTROID description is incorrect (R1). Per-star recentering would be a new measurement algorithm. It can follow noise peaks or blend motion and produce flux-dependent bias, especially when a high-SNR eligibility cut changes between frames. Investigate it through PT-5 and controlled spatial-registration/PSF experiments; do not promise that it will eliminate the trend or include it as a routine consistency fix.

Correlation with zero point alone does not identify differential colour extinction: genuine monotonic variability can correlate with a transparency trend. The existing SYSTEMATICS code, stored driver diagnostics and the test `steadyTrendCorrelatedWithTransparencyDriftIsNotRejectedAsSystematic` intentionally address that ambiguity. A new duplicate warning needs user value beyond those diagnostics, and a physical extinction attribution needs additional evidence such as colour/airmass. It must not silently become another veto.

#### R13. Correct baseline labels and strengthen the verification protocol

The candidate/frame counts and aperture-choice counts for the two new datasets agree with the saved results. Several other FWHM numbers do not match the current baseline. Distinguish the extraction-derived `sessionFwhm` guide from the measured per-frame FWHM used for aperture geometry:

| Dataset | Session guide FWHM from telemetry (px) | Median measured frame FWHM (px) |
|---|---|---|
| klangwolke-2 | 3.703 | 3.872 |
| M81-kumar-L-dataset-registered | 2.238 | 2.041 |
| m5-reg | 2.977 | 3.391 |
| helix-reg | 3.437 | 3.655 |
| dc-reg | 2.468 | 2.272 |
| m31-red-reg | 2.943 | 2.806 |

The medians here use all measured photometry frames before photometry-specific exclusions. The roadmap's m5/Helix/DC/m31 session values should be corrected or their alternative provenance explained. The m31 run demonstrates several current rejection criteria; its true calibration state was not independently established, so describe it as an observed multi-barrier outcome rather than a certified nonlinear negative control.

The listed verification cases are mostly **tests still to add**, not coverage already supplied by `PhotometrySensitivityTest` and `PhotometricReadinessCheckerTest`. Existing tests do not establish selected-aperture error/flag propagation, full convergence, or the proposed new temporal policies. Record each new case and its expected scientific result before changing production behavior.

Run an appropriate before/after comparison for **each scientific change**, not only after a phase bundling many changes. Include the JTransient checks and API consumer, affected SpacePixels tests/report/export checks, the six sequences, independent labelled image injections and realistic negative controls. Check flags, usable-point identities, masks, brightness-coordinate conventions and deterministic unaffected outputs alongside verdicts, candidates and timing. Candidate count increases alone are not success. Keep original files and saved settings unchanged; perform the large native-resolution runs sequentially.

Editorially, the summary calls this four phases while the body lists three. More importantly, keep VS-12 as an ordering/decision map linked to the canonical VS-2/5/9/10 details, so the same rules do not drift into conflicting descriptions.

#### Recommended bounded order after revising VS-12

1. **Product consistency:** selected-aperture error/identity provenance and missing aggregate report columns. Preserve the common brightness coordinate and current scientific selection unless a separate change is agreed.
2. **Evidence isolation and solver correctness:** rejected-frame statistics with a defined lifecycle, final-frame LINEARITY scope, full-correction convergence and deterministic interpolation. Implement and measure each independently.
3. **Temporal aperture evidence:** matched valid observations, noise/covariance-aware agreement and controlled subpixel tests. Settle this before loosening global scores to recover more candidates.
4. **Measured policy/calibration work:** effective reference-count/status handling, response weighting/subset readiness, LIMITED reasons, timing gaps, per-point uncertainties and candidate leverage. Keep centroid refinement and physical extinction modelling as separate investigations if the evidence justifies their complexity.

This order supports the roadmap's aim while keeping confirmed inconsistencies, policy choices and unmeasured hypotheses explicit. No production fix or default change is implemented by this review.

### Follow-up comments on R1–R13 (2026-11-02)

- **Comment on R1 (Implementation description for Phase 3):** Fully agreed. The earlier architectural notes used identifier names (`SYS_FLOOR`, `DEFAULT_GAIN`, `rmsExpectedRatio`, `coherencePass`, `FrameTrans`, `CENTROID` gate) that do not exist in the current checkout. Verified in source: `EnsembleSolver` already weights by empirical per-star/neighbour scatter with `SIGMA_FLOOR = 0.001` and `CAUCHY_SCALE = 3.0`; `VariabilityScorer` already uses empirical `expectedScatter` in `scatterZ`, `stetsonJZ`, `AMPLITUDE`, `PERSISTENCE` and local-neighbour selection; `ApertureMeasurer` applies a global per-frame `(offsetX, offsetY)` from `measureShapeAndOffset` and already uses `5 x 5` supersampling (`SUPERSAMPLE = 5`) in `pixelWeight`. Those stale premise descriptions should be disregarded in favor of R1's table.
- **Comment on R2 (Phase 1.1 — Selected-aperture consistency):** Agreed on both refinements:
  1. Keep `meanMag` as the common `1.5x FWHM` reference magnitude coordinate so magnitude-bin ordering and 50-neighbour noise lookups do not jump across aperture offsets.
  2. In `VariabilityScorer.runGates`, `star.amplitude` is already the selected curve's amplitude while the numerator `|smallAmplitude - largeAmplitude|` compares fixed `1.0x` and `2.0x` curves. Changing that normalization is a gate-policy question for `VS-5`, so Phase 1.1 should be scoped strictly to **propagating the chosen aperture's `magError`, recording the chosen `fwhmFactor` on `StarLightCurve`, and ensuring `chooseApertures` compares and adopts alternatives on valid measurements without transferring Check B's `R_MAIN` `NONLINEAR` threshold across unmapped magnitude scales**.
- **Comment on R3 (Phase 1.2 — Rejected-frame isolation lifecycle):** Agreed. The M81 finding (**73 stars excluded by `medianSnr < 10` meet `SNR >= 10` on the final 32 active frames**) shows why the two-stage frame rejection lifecycle must be explicit:
  1. Immediately filter `flagContaminatedShapes` and pre-solve star exclusions (`MAX_NONLINEAR_SHARE`, `photometryMinSnr`) by the post-registration / post-Check-B `frameActive` mask.
  2. Decide explicitly whether a single bounded recomputation of star exclusions / shape contamination should run if Stage-2 (Check D or `SpatialFrameFilter`) rejects additional frames, without looping Check B/D.
  3. Pass the final `frameActive` mask (not `usable[i][j]`) into `VariabilityScorer`'s `LINEARITY` gate so clipped measurements on active frames still trigger `LINEARITY` while flags on rejected frames are ignored.
- **Comment on R4 (Phase 1.3 — Deterministic streak interpolation):** Agreed. Keep `VS-10` strictly scoped to piece-order permutation invariance in `VariableStarAnalyzer.flagTrack` with dedicated permutation unit tests.
- **Comment on R5 (Phase 1.4 — Retained sky pixel count):** Agreed. `clippedMedianAndSigma` should report the sample count `n` that actually produced the returned `(median, sigma)` pair, and because `magError` feeds `photometryMinSnr` (`snr = 1.0857 / magError`) and Check B (`<= 0.05` mag), this change must be tested for downstream star/frame selection effects rather than assumed selection-neutral.
- **Comment on R6 (Phase 1.5 — Report `contaminated` column vs. cutout scaling):** Agreed. Split these into two independent items:
  1. Add `f.contaminated` to the HTML Per-Frame Measurements table and `photometry_frames.csv` immediately.
  2. Evaluate cutout spatial-plane scaling (`C(j) = Z(j) + a(j)x + b(j)y` at the star's normalized field coordinates) separately, after deciding whether frame telemetry and cutout scaling should use the main solution or the star's selected-aperture solution.
- **Comment on R7 (Phase 2.1 — Ensemble convergence `VS-2`):** Agreed. Test convergence on predicted magnitude corrections `|Delta(Z_j + a_j x_i + b_j y_i)|` at usable star positions in a consistent gauge, verify weight stability, and report convergence vs. iteration-cap exhaustion in telemetry without silently introducing a new readiness failure.
- **Comment on R8 (Phase 2.2 — Temporal aperture agreement `VS-5` and small-aperture premise):** Agreed with the geometry correction. Because `ApertureMeasurer.pixelWeight` (`:346-367`) already performs `5 x 5` supersampling, `PhotometryGeometry` sets `R_SMALL = 1.0x FWHM` (`0.7x` is `R_CI_INNER`), and `chooseApertures` already enforces `APERTURE_MIN_RADIUS_PIXELS = 2.0` px, the generic supersampling proposal is redundant and should be dropped. Phase 2.2 should focus on `VS-5` (temporal agreement on shared finite frames, accounting for covariance between concentric apertures).
- **Comment on R9 (Phase 2.3 — Check B counts, Check D slope weights and `LIMITED` policy):** Agreed on separating all three:
  1. Keep `VS-9` cap validation as a warning (accounting for the `90 %` faint bound and ties) and measure how changing low-count frames from `FAIL` to `INCONCLUSIVE` changes the session failing-frame fraction.
  2. In `PhotometricReadinessChecker.tracks`, test weighting both the correlation and slope regression consistently (with safe fallback for missing/non-positive slope errors).
  3. Preserve pre-exclusion readiness diagnostics alongside any retained-subset check, and treat reason-specific `LIMITED` handling as an explicit policy decision (noting that `M81-kumar-L-dataset-registered` is `LIMITED` from its `2.14` mag linear range as well as its `0.036` mag zero-point range).
- **Comment on R10 (Phase 3.1 — Gap-aware serial statistics):** Agreed. Distinguish outlier-survival support in `removeIsolatedOutliers` (where bridging across multi-frame exclusions lets two isolated single-frame artifacts support each other) from serial coherence statistics (`stetsonJ`, `longestDeviatingRun`, `splitHalfCorrelation`), and define gaps using original frame indices / capture timestamps with calibrated recovery vs. false-acceptance tests.
- **Comment on R11 (Phase 3.2 — Uncertainty model vs. candidate self-leverage):** Agreed. Since empirical scatter is already active in `EnsembleSolver` and `VariabilityScorer` (R1), keep per-point `magError` calibration separate from an empirical study of candidate self-leverage in `EnsembleSolver` (comparing candidate residuals against leave-one-out / candidate-excluded solves on a bounded sample).
- **Comment on R12 (Phase 3.3 — Centroid refinement and zero-point correlation):** Agreed. Defer per-star recentering to `PT-5` (to avoid flux-dependent centroid bias on faint stars) and drop the standalone zero-point correlation warning (since `SYSTEMATICS` and `steadyTrendCorrelatedWithTransparencyDriftIsNotRejectedAsSystematic` already govern zero-point trend handling).
- **Comment on R13 (Baseline FWHM table, per-change verification and ordering):** Agreed. Adopt R13's two-column FWHM table (`sessionFwhm` guide vs. median measured frame FWHM), verify each change individually rather than only per phase, and adopt R13's four-step bounded implementation order (`1. Product consistency` -> `2. Evidence isolation and solver correctness` -> `3. Temporal aperture evidence` -> `4. Measured policy/calibration work`).

## ST-7. Elongation threshold for single faint streaks

**Request (2026-11-02).** Investigate retaining individual streaks from the faint-streak search when their elongation provides adequate evidence, and recommend a default elongation threshold with an advanced SpacePixels control.

**Analysis scope.** Verify the current single-frame acceptance policy first. State which shape measure is used: the engine's moment-based elongation and a geometric length/width ratio are different quantities. Check interaction with smoothing, the ordinary streak threshold, star masking, consolidation and duplication of tracks already found by the normal pass. Quantify the recovery/false-alarm tradeoff on real single-frame trails and negative examples such as elongated stars, blends, diffraction spikes, hot columns and registration artifacts.

**Before implementation.** Recommend the metric, range and default from evidence, then agree the setting's name, advanced visibility, profile migration and documentation. Keep the evaluation separate from restoring the previously closed faint-streak proposals. Record whether the change is a new acceptance rule, a refinement of an existing rule, or just an exposed control.

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
