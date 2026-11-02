# Feature requests and algorithm proposals

Proposed changes to what JTransient detects, measures or tunes. How the code is organised is in [DESIGN-IMPROVEMENTS.md](DESIGN-IMPROVEMENTS.md), and application features in SpacePixels' [feature requests](../SpacePixels/FEATURE-REQUESTS.md).

These are not authorisations. Keep the existing algorithms until the stated evidence is available and the maintainer decides. An algorithm change goes one at a time, with recovery, false positives and run time measured on the reference datasets (see [DEVELOPER.md](DEVELOPER.md#real-data-checks)). Remove proposals when implemented or closed; describe implemented behaviour and current limitations in [ALGORITHM.md](ALGORITHM.md), and use git history for the proposal and its reviews. The binding declined decisions below remain in force.

<!-- root-toc:start -->
## Contents

- [Open proposals](#open-proposals)
- [QC-1. Star-count rejection envelope](#qc-1-star-count-rejection-envelope)
- [PT-5. Registration quality and shared jitter tolerances](#pt-5-registration-quality-and-shared-jitter-tolerances)
- [SM-F1. Temporal confirmation of slow-mover candidates](#sm-f1-temporal-confirmation-of-slow-mover-candidates)
- [XC-3. Local background and noise for frame extraction](#xc-3-local-background-and-noise-for-frame-extraction)
- [VS-2. Convergence of the full ensemble correction](#vs-2-convergence-of-the-full-ensemble-correction)
- [VS-5. Temporal agreement between photometry apertures](#vs-5-temporal-agreement-between-photometry-apertures)
- [VS-9. Selected stars and the linearity-check minimum](#vs-9-selected-stars-and-the-linearity-check-minimum)
- [VS-10. Deterministic streak interpolation for photometry](#vs-10-deterministic-streak-interpolation-for-photometry)
- [Declined](#declined)
<!-- root-toc:end -->

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

## VS-5. Temporal agreement between photometry apertures

**Status and decision.** Deferred. Evaluate a stronger consistency check with noise and blending controls before selecting a new gate.

**Problem.** The APERTURE gate compares the small and large apertures' 5th-to-95th percentile ranges, normalized by the main aperture's amplitude. Equal ranges do not establish that the measurements brighten and fade together. The current masking follows finite main-aperture residuals; nonfinite values in the other apertures can also leave their percentile calculations using different effective frame subsets.

**Recovered evidence.** In the historical scorer probe, the main and small residual curves were sine waves with a 0.15-mag semi-amplitude, and the large curve was their negative. With 80 quiet comparison stars, the amplitude difference was zero and no gates failed; the result was `HIGH_CONFIDENCE`. A coherent control also passed. This isolates a scorer weakness; it does not demonstrate that a real blended image passes all earlier image and crowding checks.

**Direction to evaluate.** Compare temporal agreement as well as amplitude on the intersection of frames with finite main, small and large residuals. Candidate measures include agreement of signed changes or a noise-aware relation between residual curves. Calibrate the choice against measurement uncertainty and blending; a simple correlation cutoff can reject faint real variability or accept a shared systematic.

**Evidence needed.** Compare coherent, reversed and time-shifted curves; unequal missing measurements; faint noisy variables; constant stars; and blended sources. Measure recovery and false acceptance through the full photometry path, in addition to repeating the isolated scorer case. Decide how too little shared evidence is reported.

**Recovered source:** [aperture finding, scorer reproduction and shared-frame requirement](https://github.com/ppissias/JTransient/blob/b37cecf519cbb73609ff56d30fcf00855b550908/docs/archive/variable-star-assessment-2026-10.md#L237-L270). Current implementation: [VariabilityScorer](src/main/java/io/github/ppissias/jtransient/photometry/VariabilityScorer.java).

## VS-9. Selected stars and the linearity-check minimum

**Status and decision.** Deferred. Align configuration expectations and readiness reporting with what check B actually counts; preserving the intended number of linear reference stars remains a valid policy.

**Problem.** `photometryMaxStars` caps the selected stars, while `linearityMinStars` is also required inside the measured linear range. The initial cap warning compares against the nominal minimum and ensemble minimum. Check B then excludes stars beyond its faint reference bound and may exclude others for measurement quality or bright-end shape departure. A cap equal to the nominal minimum can therefore pass validation while being too small to satisfy the later check.

**Recovered evidence.** The historical probe supplied 50 evenly spaced magnitudes with constant concentration index, small errors and a four-mag span. With the default 90 % faint bound, only 45 stars counted, despite a sufficient linear range of about 3.592 mag. Under that ranking policy, 55 distinct measurable magnitudes are the ideal minimum to retain 50 (`ceil(0.9 * 55) = 50`); exclusions can require more. This is a sample-count/configuration problem, not evidence of a false variable-star candidate.

**Current boundary.** Reporting already distinguishes too few stars in the linear range, insufficient magnitude range and a measured bright-end shape departure. The historical complaint that these all receive the same nonlinearity message is therefore partly addressed. Too few counted linear stars can still give check B a `FAIL`, and the nominal cap validation still does not account for the faint-bound loss.

**Direction to evaluate.** Validate or explain the effective minimum using the actual percentile/rank policy, including ties, rather than a simple percentage division. Preserve the distinction between selected, measurable and counted linear-reference stars. Evaluate whether insufficient evidence should be `INCONCLUSIVE` with a specific reason, and explicitly retain safe downstream handling when linearity cannot be established.

**Evidence needed.** Cover caps around 50 and 55, tied magnitudes, flagged or low-SNR measurements, narrow magnitude ranges and genuine bright-end nonlinearity. Check configuration warnings, per-frame reasons, session readiness and whether photometry proceeds. An ideal lower bound must not be presented as a guarantee that an arbitrary field supplies enough usable references.

**Recovered source:** [minimum-count finding, reproduction and classification caveat](https://github.com/ppissias/JTransient/blob/b37cecf519cbb73609ff56d30fcf00855b550908/docs/archive/variable-star-assessment-2026-10.md#L368-L397). Current implementation: [DetectionConfigRules](src/main/java/io/github/ppissias/jtransient/config/DetectionConfigRules.java), [PhotometricReadinessChecker](src/main/java/io/github/ppissias/jtransient/photometry/PhotometricReadinessChecker.java), [VariableStarAnalyzer](src/main/java/io/github/ppissias/jtransient/photometry/VariableStarAnalyzer.java).

## VS-10. Deterministic streak interpolation for photometry

**Maintainer decision (2026-11-02).** Narrow this proposal to correcting the dependence on streak-piece ordering. Defer any blanket expansion of the contamination mask over the entire predicted path of a streak in an unobserved frame.

**Problem.** In a frame a streak track skips, `VariableStarAnalyzer.flagTrack` interpolates a position and uses a nearby piece's shape for the contamination segment. Several pieces can share a source frame; their ordering must not determine the interpolated position, selected shape or stars flagged. The recorded klangwolke-2 example partially flags a satellite's path in missing frame 3, with the flagged part depending on piece order.

**Bounded scope.**

- Evaluate how observations with multiple pieces in the same frame should supply the interpolation reference and shape, including deterministic selection and tie-breaking. State the policy before implementation and preserve original frame identities and available capture times.
- Make permutations of the same physical pieces produce the same interpolated contamination flags. Preserve the rules for observed frames, the current mask-extent policy, and the existing handling of missing timing and rejected frames unless a change is explicitly agreed.
- Keep streak linking, extraction thresholds and candidate acceptance unchanged. Do not infer that an unobserved or flashing satellite painted its full predicted path, and do not add a new path-wide veto as part of the deterministic correction.

**Verification.** Compare original, reversed and shuffled piece order; multiple fragments in one frame; equidistant shape choices; missing middle frames; non-contiguous sequence indices; and available/missing timestamps. The same input geometry must yield the same flags. On the recorded example and relevant reference datasets, compare contamination flags, usable measurements, ensemble results and variable-star verdicts, and report any changes outside the intended correction.

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
