# Feature requests and algorithm proposals

Proposed changes to what JTransient detects, measures or tunes. How the code is organised is in [DESIGN-IMPROVEMENTS.md](DESIGN-IMPROVEMENTS.md), and application features in SpacePixels' [feature requests](../SpacePixels/FEATURE-REQUESTS.md).

These are not authorisations. Keep the existing algorithms until the stated evidence is available and the maintainer decides. An algorithm change goes one at a time, with recovery, false positives and run time measured on the reference datasets (see [DEVELOPER.md](DEVELOPER.md#real-data-checks)). Remove proposals when implemented or closed; describe implemented behaviour and current limitations in [ALGORITHM.md](ALGORITHM.md), and use git history for the proposal and its reviews. The binding declined decisions below remain in force.

## Open proposals

| ID | Status | Proposal, and the condition for revisiting it |
|---|---|---|
| [QC-1](#qc-1-star-count-rejection-envelope) | Deferred | Star-count rejection has no absolute or relative envelope floor. Counts now share a quality area, but small count changes can still matter. A new floor needs a recovery and false-retention study. |
| [PT-5](#pt-5-registration-quality-and-shared-jitter-tolerances) | Proposed | `maxStarJitter` serves several tolerances. The maintainer preferred a future registration-quality assessment over splitting the tolerances now. The fill-edge registration used by camera-fixed rejection is not a general registration-quality measurement. |
| [SM-F1](#sm-f1-temporal-confirmation-of-slow-mover-candidates) | Proposed | Three-bin temporal stacks, or a measured slow-mover path, are not implemented. Compare real-mover recovery, false positives and run time before introducing either; the current frame-support and stationarity screens do not establish motion. |
| [SM-F2](#sm-f2-experimental-high-sensitivity-moving-object-detection) | Digital tracking implemented 2026-11-03 as an engine stage, off by default; SpacePixels F25 integrated; engine follow-ups remain | All three prototypes recover the three magnitude-18.5 Klangwolke-2 asteroids in blind full-field searches. Digital tracking has the best recovery in both injection experiments and finds all five faint known asteroids in the independent series. The first version implements digital tracking only, off by default, with SpacePixels settings and presentation; control artifacts remain open. |
| [SM-F3](#sm-f3-improvements-to-maximum-stack-slow-mover-detection) | Proposed | Review of the maximum-stack slow-mover branch: narrow motion corridor (5.0–6.3 px), lack of temporal progression check, and lock-on to stationary stars. Proposes temporal correlation, widened axis ratio, residual maximum stack, and row hoisting. |
| [XC-3](#xc-3-local-background-and-noise-for-frame-extraction) | Partly implemented; frame work deferred | Master-star extraction adds a local-sky pass. Per-frame extraction still uses one sky level and noise estimate. Preserve extended emission and propagate local noise consistently before proposing a frame-sky redesign. |
| [VS-9](#vs-9-selected-stars-and-the-linearity-check-minimum) | Deferred policy only; warning implemented | Check-B insufficient-count/range classification and separately validated retained-subset readiness; the completed star-cap warning is not pending. |
| [VS-11](#vs-11-photometry-strictness-defaults-and-recovery-review) | Four deferred investigations and two follow-ups remain | Late-frame population lifecycle, reason-specific LIMITED handling, spatial correction of comparison cutouts and brief/fast variability; the peak-removal check's open questions and the blank-sky factor in star selection. The initial audit and adopted VS-12 work are complete. |
| [ST-7](#st-7-elongation-threshold-for-single-faint-streaks) | Investigation requested 2026-11-02 | Evaluate retaining isolated faint-streak detections above a configurable elongation, recommend an evidence-based default, and expose the agreed control as an advanced setting in SpacePixels. |

The photometry backlog was reconciled with the completed VS-12 work on 2026-11-03. Only VS-9 and the deferred follow-up in VS-11 remain open. Implemented behavior belongs in [ALGORITHM.md](ALGORITHM.md#16-variable-star-photometry) and [RELEASE_NOTES.md](RELEASE_NOTES.md#108-unreleased); completed proposals and their detailed reviews remain in git history. Closed decisions below remain binding.

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

## SM-F2. Experimental high-sensitivity moving-object detection

**Request and authorisation (2026-11-03).** "can we investigate all 3 approaches with real full size data ?" The maintainer wants to evaluate digital tracking, temporal-stack proposals followed by full-sequence verification, and PSF-filtered frame extraction, potentially retaining all three as additional experimental high-sensitivity mechanisms. Write this request first, then proceed with experiments. This authorises scratch prototypes and real-data investigations; production integration and defaults remain a separate decision.

**Motivation and initial evidence.** In the latest existing Klangwolke-2 report, the Maximum Auto-Tune configuration misses 25729 (2000 AV187), 74326 (1998 UU37) and 77716 (2001 OF50), with predicted magnitudes about 18.5. The maintainer can see their motion in Blink after locating them with the known-object overlay. The sequence contains 33 aligned 9544 by 6361 luminance frames, with 120-second exposures spanning 79.37 minutes; the saved report retains 28. Predicted displacements are about 20–31 pixels, while motion during an exposure is under one pixel. The initial local pixel experiments found roughly 1.2-sigma single-pixel peaks and useful PSF-integrated evidence that fails the existing pixel seed/growth requirements.

Searching all positions in each selected 128 by 128 region over 2,356 common trial motions recovered the three as the strongest candidates, with local scores about 21, 21 and 15. The regions were chosen using the predictions; the search itself received neither the positions within them nor the true motions. These are local feasibility results, not blind whole-field recovery rates or calibrated significance. A three-bin experiment recovered only two objects: the early evidence for 77716 overlaps stationary sources. Whole-frame cost, candidate counts and false positives remain unmeasured at the start of this request.

**Ownership and relationship to other requests.** Detection science belongs in JTransient. SpacePixels would eventually own separate experimental controls, resource/progress information, presentation and catalogue comparison; [F22](../SpacePixels/FEATURE-REQUESTS.md#f22-known-asteroids-and-comets-in-the-field) supplies validation and later identification, never the blind search seeds. SM-F1 confirms candidates already selected from maximum-stack morphology; SM-F2 also investigates creating candidates that never enter that branch. Coordinate local sky/noise work with XC-3 rather than introducing incompatible significance definitions.

**Three mechanisms to compare independently.**

1. **Digital tracking.** Model/subtract the stationary field, estimate the PSF and residual variance, and accumulate weak pixel evidence over a grid of timed trajectories before demanding a per-frame detection. Search all valid field positions, not catalogue-centred crops. Compare velocity-grid spacing, coarse/refined searches, robust exposure contributions, star blends, minimum temporal coverage and handling of rejected frames. Constant velocity is supported by these short asteroid examples; do not generalise it to all objects or cadences.
2. **Temporal-stack proposals, then full-sequence verification.** Form time groups, detect weak sources or short trails, and propose trajectories from supported groups. Refine and verify them using the original complete time series. Permit a weak or blended group: requiring every bin to contain a detection already loses 77716 locally. Measure sensitivity lost relative to direct digital tracking, including objects below every proposal threshold. Do not report a missing proposal as a failed verification.
3. **PSF-filtered frame extraction and linking.** Match the spatial footprint before source extraction, estimate the noise after filtering, and connect weak per-frame measurements through time. Compare recovery and false-survivor density at documented thresholds with the saved Maximum configuration. Reusing raw-pixel sigma thresholds after smoothing is invalid. Separate candidate generation, linking and final evidence checks so failures can be attributed.

**Shared experimental conditions.** Use original full-resolution frames and their retained-frame identities, timestamps, exposure durations and coverage. Bounded tiles with enough motion/filter margins are allowed; every valid field position must be covered and tile seams must be checked. Freeze preprocessing and each search configuration before looking up known-object matches. Use local sky/PSF/variance information consistently; record correlated noise, median-reference self-subtraction and any empirically normalised scores. Test modelling/downweighting blends as well as hard masks, since aggressive masks erase real path segments. Bound the influence of cosmic rays and exceptional exposures. Candidate lists must retain their generating mechanism, fitted path, temporal contributions, availability and rejection reasons. Deduplication between mechanisms must preserve that provenance.

**Evaluation protocol.**

- Begin with full-size Klangwolke-2 and the latest existing report/configuration as baseline. Keep its original retained subset for the primary comparison; any use of additional rejected frames is a separate experiment. Do not overwrite the report, original FITS data, settings or stored catalogues.
- Save blind candidate lists before matching to predictions. Report the three faint targets, other valid known objects and existing moving tracks separately; do not assume catalogue completeness or that predicted brightness guarantees visibility.
- For each approach report field/velocity/coverage limits, recovery and failure stage, candidate counts before and after verification, candidate density, path agreement, signal/coverage diagnostics, wall time, preprocessing versus search cost and measured peak memory. Record parameter selection and any candidate cap; truncated searches are not complete searches.
- Use sign-reversed residuals, several shuffled-time controls, independent time subsets and realistic moving-source injections across flux, speed, field position, blends and gaps. Distinguish noise-control survivors and unlabelled real-data candidates from confirmed false positives. Calibrate thresholds for the large number of searched paths and structured stellar residuals; local scores are not discovery probabilities.
- Compare each method alone and their union, including duplicate findings, sources found by only one mechanism and whether the extra recovery earns its computation and false-candidate burden. Broaden real-data acceptance to other reference sessions before proposing defaults.

**Potential production contract, still to decide.** Additional experimental mechanisms should be independently selectable, have explicit time/memory/search limits and cancellation, and leave existing detection outputs and defaults unchanged when disabled. The application should distinguish exploratory candidates from validated moving tracks and catalogue associations. Decide API/result fields, tuner policy, CPU/GPU/dependency requirements, reuse of prepared data and progress/resource reporting after the full-frame measurements. No calibrated magnitude or asteroid identity follows from a detection score alone. A first version is proposed in [Proposed design](#proposed-design-experimental-faint-mover-search-2026-11-03).

### SM-F2 full-frame experiments (2026-11-03)

**Completed scope.** Scratch prototypes searched all 60,709,384 reference-image positions, subject to coverage/quality availability, using the same 28 retained frames as the existing report. Processing used 247 tiles of up to 512 by 512 pixels with 112-pixel margins, not catalogue-centred regions. The direct search evaluated 616 coarse two-dimensional motions: displacement components from -48 to +48 pixels over the retained time span in steps of 4, excluding total motion below 8 pixels. Candidate paths were then refined. Minimum usable coverage was 14 frames spanning half the sequence. Predictions were consulted only after blind candidate lists were saved. These limits define the experiment's search domain, not general asteroid completeness.

All three mechanisms share a stationary-reference subtraction, PSF filter, local filtered-noise estimate, downweighting of excess temporal variation, and a complete-sequence verifier with bounded exposure contributions. This weighting retains the blended evidence for 77716 that the earlier hard-mask experiment lost. The per-frame prototype uses a scratch point linker and the common verifier; it is not a test of PSF smoothing alone with the unchanged library linker. Agreement between mechanisms is correlated evidence from the same pixels.

The exploratory cuts were a coarse score of at least 7, refined score of at least 8, temporal-bin peaks of at least 4 and frame peaks of at least 3.5. Frame-track proposals need at least five linked peaks over half the time span. These settings were frozen before matching the full-field detections to the predictions; they are not recommended production defaults.

| Mechanism | Three predicted magnitude-18.5 asteroids | Two further known asteroids near magnitude 19 | Real-data candidate trajectories after common verification/deduplication | Injected sources recovered |
|---|---|---|---:|---:|
| Digital tracking | 3 / 3 | 2 / 2 | 15 | 12 / 16 |
| Temporal-stack proposals and full-sequence verification | 3 / 3 | 2 / 2 | 9 | 10 / 16 |
| PSF-filtered frame points, linking and common verification | 3 / 3 | 1 / 2 | 10 | 11 / 16 |

The three faint targets' fitted paths agree with the predictions within about 0.3–0.6 pixel RMS. Their scores are roughly 13–16 in this model, with positive contributions from both time halves. These are empirical evidence scores, not calibrated Gaussian significances or identity probabilities. The temporal proposals add no distinct candidate beyond the direct search in this run; the point path adds two unverified trajectories. Candidate trajectories are not necessarily distinct physical objects. The point path misses Robinbock (predicted magnitude 18.94), which the other two recover.

**Injection check.** Sixteen artificial moving sources were inserted into the original pixel cutouts before rebuilding the reference and noise model; all field positions were searched again. Unchanged tiles reused the identical prepared data. A zero-injection recomputation matched the baseline prepared pixels exactly. Injections used Gaussian profiles with the saved per-frame seeing variation, constant normalised integrated flux, four directions/motion lengths and locations distributed over the field. Source Poisson noise was omitted; this is a small background-dominated sensitivity experiment, not a completeness calibration.

| Reference injected peak / pixel noise | Digital tracking | Temporal proposals | Frame points |
|---|---:|---:|---:|
| 0.5 | 0 / 4 | 0 / 4 | 0 / 4 |
| 0.8 | 4 / 4 | 2 / 4 | 3 / 4 |
| 1.2 | 4 / 4 | 4 / 4 | 4 / 4 |
| 1.8 | 4 / 4 | 4 / 4 | 4 / 4 |

The weaker missed frame-point injection supplies only two nearby peaks above the point threshold, too few to link. One missed temporal-proposal injection has no nearby seed; another has a coarse proposal whose extrapolated trajectory is not recovered by the fixed refinement/verification window. This calls for uncertainty-aware proposal refinement and a direct-search fallback, not a requirement that every temporal bin be detected. Scores near the direct proposal/verifier cutoffs also limit the faintest group; those misses do not establish a physical detection limit.

**Full-field controls expose unfinished verification.** Before an additional stationary comparison, positive survivors in the three shuffled-time runs were 4/2/2 for digital tracking, 5/2/3 for temporal proposals and 5/2/3 for frame points. Sign-reversed data produced 150, 3,249 and 6,444 verified trajectories respectively in the correctly ordered sequence. A high summed score therefore does not establish genuine motion through structured stationary residuals.

An exploratory post-search check requires the moving score to exceed the best stationary score within 4 pixels by at least 2. It retains all real-data candidates in the table and their target recoveries, and leaves 0/1/0 positive shuffled-time survivors for each mechanism. Correctly ordered negative survivors fall to 59, 99 and 85. The check was chosen after examining control tails and is not an independently validated acceptance rule; negative and positive tails remain asymmetric. Improve stationary/PSF modelling, motion-versus-stationary evidence, source morphology and camera-fixed/common-motion checks before treating these candidates as validated moving tracks. Unmatched real-data candidates remain unverified, not confirmed false positives or discoveries.

**Measured resources.** The CPU prototype used eight compiled search workers. Shared preparation took 843 seconds (14.1 minutes), peaking at 498 MiB process RSS. The direct whole-field search took 52 seconds including startup, tile I/O and both polarity extrema; tile accumulation/I/O accounted for 44 seconds. Positive temporal-bin extraction took 20 seconds and pairing about 0.05 seconds. Positive frame-peak extraction took 52 seconds, followed by about 20 seconds of point seeding/linking. Refinement itself was under a second per mechanism; loading the verification tiles was shared and took about 11 seconds. These are separate measured stages, not additive end-to-end production guarantees. A profiling repeat reproduced proposal counts exactly.

The frame route produces 245,259 peaks at threshold 3.5, 655,138 consolidated pair seeds and 683 point-track proposals before the common verifier reduces them to ten candidates. The temporal route starts with 1,861 pair proposals; the direct route with 18 coarse positive peaks. Their generation costs and failure stages differ substantially. The complete research cache occupies about 20 GB on disk, including controls and shared prepared tiles; the quoted RSS does not include the operating-system file cache. A library port must respect caller-owned image lifetimes and bounded memory rather than assume these scratch files are an engine facility.

**Validation boundaries.** Compiled accumulation was compared with a scalar reference, and trajectory refinement checked on a synthetic moving source. All 33 original FITS SHA256 values remained unchanged. No new detection report, production algorithm, library dependency, user setting or default was changed. This is one demanding full-size sequence plus a small injection experiment; operational false-alarm rates, cross-dataset completeness, cancellations, seams/edges and final result/API contracts still require acceptance work.

**Proposed next decisions.**

1. Prioritise digital tracking for sensitivity; retain temporal proposals and frame points as separately selectable experimental mechanisms if their additional cost/use cases justify them. Do not interpret this as authorisation to enable all three by default.
2. Define a shared, validated motion-versus-stationary verifier and preserve per-mechanism provenance. Evaluate the existing camera-fixed/common-motion contracts on fitted faint trajectories; overlap with a historical rejected finding alone is not ground truth.
3. Profile a library implementation that reuses appropriate engine products and processes bounded tiles. SpacePixels owns any file/cache orchestration; JTransient must not acquire filesystem or session ownership from these prototypes. Decide search range, time/memory limits, cancellation, progress, optional controls and API/result fields before integration.
4. Address temporal-proposal uncertainties/fallbacks and compare frame-point proposals with the actual existing extraction/linking path. Broaden controls, realistic injections and reference sessions before calibrating an experimental acceptance threshold or tuner policy.

Scientific background: [Heinze et al. (2015), digital tracking](https://arxiv.org/abs/1508.01599) and [Smotherman et al. (2021), moving-object detection in difference images](https://arxiv.org/abs/2109.03296). Reproducible programs, frozen configurations, full candidate lists, controls, resource measurements and figures are retained outside the repositories; their machine-local location is recorded only in `LOCAL-ENVIRONMENT.txt`.

### SM-F2 independent experiment series (2026-11-03)

At the maintainer's request, a second series was designed and run without reference to the series above; its text was read only afterwards. It uses its own code and preprocessing. Its numbers are therefore an independent check, not a repetition. Scores are empirical in both series and are not comparable one to one.

**Method.**
- *Frames.* All 33 frames, not the report's 28 retained frames; that is a deviation from the protocol.
- *Search domain.* 176 tiles of 600 by 600 px covering every position. The coarse grid has 168 motions at 0.08 px/min, with speed from 0.1 to 0.6 px/min in any direction. Peaks with a coarse score of 5.5 or more are refined at 0.02 px/min.
- *Preprocessing, per tile:*
  - subtract the frame median;
  - subtract each frame's own local background (41 px running mean);
  - divide by each frame's robust sigma and clip to ±5;
  - mask star cores (median more than 8 sky sigma above the sky, grown 3 px) and registration fill.
- *Score.* The PSF-filtered (Gaussian, FWHM 3 px) shift-and-stack along each motion, with whole-pixel shifts, divided by one robust noise of the filtered stack per tile.
- *Split-half check.* Both halves of the frames, stacked separately at the found motion, must reach 3.
- *Blindness.* Known objects were matched only after the blind lists were saved.

**Known objects, whole field.**

| Object | Predicted mag | Score (rank among 592 peaks) | First / second half |
|---|---|---|---|
| 611 Valeria | 14.31 | 71.6 (1) | 42.3 / 59.6 |
| 4767 Sutoku | 17.72 | 49.5 (2) | 27.1 / 42.7 |
| 74326 (1998 UU37) | 18.54 | 24.5 (5) | 17.0 / 17.6 |
| 25729 (2000 AV187) | 18.48 | 21.2 (6) | 15.3 / 14.9 |
| 77716 (2001 OF50) | 18.46 | 15.4 (8) | 8.0 / 13.6 |
| 43283 Robinbock | 18.93 | 14.9 (9) | 11.8 / 9.3 |
| 26239 (1998 QP33) | 18.97 | 13.4 (11) | 12.4 / 6.6 |

All five faint asteroids are recovered, with motions within 0.02–0.05 px/min of the predictions. 194 Prokne (13.3 mag) is lost to the star-core mask; the normal pipeline finds it. (2010 DR82), which has no predicted magnitude, is not found. 2606 Odessa and 4332 Milton fall outside the frames.

**Injections.** Two whole-field runs placed one synthetic mover in every tile: 352 movers, 88 each at 18.0, 18.5, 19.0 and 19.5 mag. Each was a Gaussian of FWHM 3 px whose flux was scaled from Sutoku's measured flux (3,941 ADU at 17.72 mag), with a random speed of 0.15–0.5 px/min in any direction. There was no seeing or transparency variation and no source noise.

| Mag | Recovered at score ≥ 8 | Also passing the split-half check |
|---|---|---|
| 18.0 | 93–98 % | 91–93 % |
| 18.5 | 89–93 % | 84–89 % |
| 19.0 | 89–91 % | 89 % |
| 19.5 | 77–82 % | 75–77 % |

A recovery means a position within 4 px and a motion within 0.08 px/min. The misses were not examined individually; star masks are the likely cause.

**Null controls.** Over the whole field:

| Run | Candidates at score ≥ 8 | Passing the split-half check | Strongest |
|---|---|---|---|
| Sign-reversed | 8 | 6 | 18.1 |
| Shuffled times, run 1 | 7 | 1 | 25.8 |
| Shuffled times, run 2 | 8 | 3 | 33.9 |
| Shuffled times, run 3 | 12 | 3 | 26.0 |

Almost all of them lie within about 20 px of a real bright mover (Valeria, Prokne, Sutoku, 25729):
- Shuffled times scramble a bright mover's signal into peaks at wrong motions.
- Sign reversal turns the dark halo that local-background subtraction leaves around a bright object into positive peaks.

Away from real movers, at most one control candidate per run reaches a score of 8. In a search, a peak near a much stronger one should therefore be suppressed. The real run has 15 candidates at 8 or more, 11 of which pass the split-half check.

**Cheaper mechanisms, measured on 600 px crops around the targets.**

| Approach | 25729 | 74326 | 77716 | Robinbock |
|---|---|---|---|---|
| PSF-filtered single frames: median SNR | 4.9 | 5.3 | 3.8 | 3.8 |
| Single frames reaching 5 | 48 % | 58 % | 33 % | 6 % |
| Unshifted bins of 4–6 frames: SNR | 8.8–9.5 | 9.0–10.6 | 5.6–8.4 | 6.3–6.8 |
| Bins reaching 4 | 100 % | 100 % | 62–80 % | 88–100 % |

- *Single frames.* Noise peaks number about 250–390 per Mpx per frame at 4 and 21–42 at 5. A linker therefore has to handle about 1,500–2,500 noise points per full frame at 5.
- *Bins.* Bins of 11 frames smear the source (74326 falls from 10.6 to 7.0), so 4–6 frames is the useful range here. The weak early bins of 77716 overlap stars, as in the series above.

**Existing pipeline.**
- *Raw signal.* At the predicted positions, the raw peak pixel of the five faint asteroids is 2.6–3.5 sky sigma (median per frame).
- *Maximum configuration.* It finds Prokne, Valeria and Sutoku but none of the five.
- *Lower thresholds.* The auto-tune's sigma-2.5 setting (grow 1.75, 4 px minimum, mask overlap 0.65) also finds none of them, and breaks Prokne's track into five single-frame anomalies (Valeria keeps 9 frames instead of 12). Lowering the per-frame thresholds is therefore counterproductive.
- *Slow movers taken for camera-fixed sources.* Sutoku moves about 0.5 px per frame (0.2 px/min) while the frames shift 0–9 px horizontally. Of its detections, 9 frames (Maximum) and 7 frames (sigma 2.5) went to camera-fixed groups (FC65, FC38). A fainter slow asteroid could lose its whole track this way. Fixed on 2026-11-03: see [camera-fixed sources](ALGORITHM.md#94-camera-fixed-sources).

**Unmatched real-data candidates.** Image strips were made for the strongest isolated candidates: the shift-and-stack, then the early, middle and late thirds unshifted, then the median.

| Position | Score | Motion (px/min) | Verdict |
|---|---|---|---|
| (2512, 3037) | 14.2 | (−0.16, 0.00) | Likely real: a compact dot in the stack and in each third |
| (5961, 1601) | 9.3 | (−0.32, −0.20) | Plausible: a compact stack dot and main-belt-like motion |
| (5272, 400) | 9.4 | — | Artifact: residuals of a bright star whose shape changes between frames |
| (4431, 4015) | 7.8 | — | Artifact: a satellite trail crosses the early frames |
| (7145, 5507) | 9.0 | — | Uncertain: a single-pixel spike in one third |
| (8182, 994) | 7.6 | — | Uncertain: a single-pixel spike in one third |

The session's JPL fetch stops at magnitude 19.0. Candidates scoring like Robinbock and 26239 (13–15) are near or below that limit, so a deeper fetch is needed before they can be called unknown.

**Pitfalls found.**
- *Per-frame sky level.* The sky level changes during the session. Without subtracting each frame's own background, the median-subtracted stack is biased: 25729 scored 10 with halves of 2.1 and 12.2, against 21.2 with halves of 15.3 and 14.9 after the correction.
- *Bright stars and real bright movers* leave sidelobes; see the null controls.
- *Satellite trails* need the pipeline's streak masks.

**Resources.** A Python/NumPy prototype with 4 processes takes 126–149 s per whole-field run, including FITS reading and the per-tile preparation, and about 1 GB of memory in total. An earlier 8-process run was stopped for low memory on this machine. Preparation is lightweight here because it reuses only the frames; an engine version could reuse the master median, star mask and frame noise it already has.

**Validation boundaries.** The prototype uses whole-pixel shifts, one fixed PSF and constant velocity. The injections are idealised. The input FITS files were not modified. No production code, report, setting or default was changed.

### Review of SM-F2 and recommendations (2026-11-03)

**Where the two series agree.**
- The 18.5-mag targets are far below single-frame detection but well within reach of digital tracking.
- All three mechanisms reach the 18.5-mag targets.
- Digital tracking is the most sensitive.
- Unshifted temporal bins lose 77716 where it overlaps stars.
- Stationary structure, not noise, limits the false-candidate rate.

**What this series adds or questions.**
1. *Preparation cost.* The series above reports 843 s of shared preparation. A per-tile median, local background, robust normalisation and star mask took under a second per tile here and reached comparable scores. The engine already has a master median, star mask and frame noise, so the preparation need not dominate.
2. *Sign-reversed survivors.* The 150 verified trajectories in the sign-reversed run above point to stationary negative structure in that residual model. With per-frame local background subtraction, ±5 clipping and star-core masks, the sign-reversed run here left 8 candidates at 8 or more, nearly all next to real bright movers. Suppressing peaks near a much stronger peak, together with the motion-versus-stationary comparison above, should handle the rest.
3. *Completeness by magnitude.* The 352 magnitude-scaled injections give completeness per magnitude, which the 16 relative-peak injections cannot: about 90 % to 19.0 mag and about 80 % at 19.5 mag at a score of 8.
4. *Single-frame thresholds.* Lowering them is not an alternative; it breaks existing tracks. PSF-filtered frames help only with a linker built for thousands of points per frame and for missing detections.
5. *Retained frames.* Using all 33 frames did not hurt here. The robust clipping bounds the 5 rejected frames' influence. The primary comparison should still use the retained 28, as the protocol asks.
6. *Ground truth.* The 19.0 fetch limit leaves candidates at 13–15 unclassifiable. A deeper fetch, around 20.5, should precede any false-positive count.
7. *Two concrete false-candidate sources* need handling before acceptance work: satellite trails (use the pipeline's streak tracks) and bright-star residuals (weight pixels by their temporal variance).
8. *Camera-fixed contract.* The Sutoku case is concrete evidence that this contract must test motion before calling a slow source camera-fixed. One way is to compare a constant-camera-position fit with a constant-sky-motion fit.

**Recommended order.**
1. *No known-object stacking.* Proposed here first, then declined by the maintainer (2026-11-03): known objects must be discovered independently. See the design below.
2. *Digital tracking as an experimental JTransient stage, off by default.*
   - Reuse engine products: master median, star mask, frame noise, timestamps, registration, coverage and streak tracks.
   - Run on tiles in parallel, coarse to fine.
   - Remove each frame's local background, clip robustly and weight by temporal variance.
   - Suppress peaks near a much stronger peak, and require motion versus stationary and split-half consistency.
   - Report a separate candidate category with the stacked cutout and the path.
   - Measure it in Java on Klangwolke-2 and the other reference sessions before setting thresholds.
3. *Camera-fixed rule* fixed for slow movers: implemented 2026-11-03, see [camera-fixed sources](ALGORITHM.md#94-camera-fixed-sources).
4. *Temporal bins of 4–6 frames and PSF-filtered frame points* only as optional proposal routes, after (2), if they recover objects digital tracking misses.

### Proposed design: experimental faint-mover search (2026-11-03)

**Decisions (made, 2026-11-03).**
- "I would not like to do a known object stack, I would like to independently discover these using the methods we are exploring." Fetched known objects stay a reference for checking results, never seeds and never a separate product.
- "I think this feature should be disabled by default, I think we need another dedicated section for this and any settings it may need." The application side is [SpacePixels F25](../SpacePixels/MANUAL.md#faint-movers-experimental).

**Scope of the first version.**
- *Mechanism.* Digital tracking only (mechanism 1), as an optional JTransient stage after tracking, off by default. Temporal-bin and PSF-filtered-frame proposals stay experiments until they recover objects that the direct search misses.
- *Motion model.* Constant velocity over the session, for point-like sources (motion during one exposure below about one FWHM).
- *Speeds.* From a minimum displacement over the session up to a maximum rate.

**Engine stage.**
1. *Inputs reused:* the kept frames and their capture times, the master median (the static field), the star mask, per-frame noise, frame registrations and coverage, and the session's streak tracks.
2. *Residuals, per tile:*
   - frame minus master median, minus the frame's own local background (the sky level changes during the session), divided by the frame noise, and clipped to ±5;
   - star cores, registration fill and streak paths masked;
   - pixels with large temporal variance near bright stars down-weighted.
3. *Search:* shift-and-stack over a grid of motions whose step keeps the end-of-session offset below about half the FWHM; coarse to fine, PSF-filtered, with one noise scale per tile.
4. *Candidate checks:*
   - local maxima above the score threshold;
   - suppressed when a much stronger peak is near (sidelobes of bright movers);
   - both halves of the session positive;
   - moving score above the best stationary score nearby;
   - the camera-fixed two-fit comparison ([camera-fixed sources](ALGORITHM.md#94-camera-fixed-sources)) applied to the candidate's path, so that hot pixels are not reported.
5. *Output:* a separate candidate list, not tracks. Each candidate has a fitted path (position at a reference time and motion), score, half scores, per-frame contributions, a shift-stacked cutout, and the generating mechanism.
6. *Resources:*
   - tiles processed in parallel within the engine's worker pool and memory estimate;
   - cancellable, with progress per tile;
   - a time limit that ends with an explicit "search truncated" result;
   - existing outputs byte-identical when the stage is off.

**Settings (`DetectionConfig`, proposed names).**

| Setting | Proposed default | Meaning |
|---|---|---|
| `enableFaintMoverSearch` | false | Runs the stage. |
| `faintMoverMinDisplacementFwhm` | 2.0 | Smallest motion over the session, in FWHM; slower objects stay in the static field. |
| `faintMoverMaxSpeedPixelsPerMinute` | 1.0 | Fastest motion searched; cost grows with its square. |
| `faintMoverMinScore` | 8.0, to be calibrated | Score a candidate needs before the checks. |
| `faintMoverMaxSeconds` | 600 | Time limit for the search. |

The grid step, tile size, clipping, star-mask level, half-session and moving-versus-stationary margins, and minimum frame count stay internal constants in the first version.

**Validation before release.**
- A Java implementation reproduces the prototype on Klangwolke-2: all five faint known asteroids, completeness per magnitude from injections, and null controls.
- The score threshold is calibrated on the other reference sessions, so that null controls give at most about one false candidate per session.
- Run time and memory are measured on those sessions.
- The 18-dataset regression is byte-identical with the stage off.

### Implementation (2026-11-03)

**Implemented.** Digital tracking as the engine stage `FaintMoverSearch`, off by default, with the settings of the design above. The algorithm, its checks and its measurements are in [ALGORITHM.md](ALGORITHM.md#faint-mover-search). The application integration ([SpacePixels F25](../SpacePixels/MANUAL.md#faint-movers-experimental)) provides dedicated settings, resource estimates, unverified FM report evidence and viewer overlays, with saved catalogue comparisons only after detection.

**Changes from the design, from the measurements.**
- *Camera check.* The design proposed the two-fit comparison of camera-fixed groups. A candidate has no per-frame positions to fit, and testing its path's camera spread failed both ways:
  - a real candidate on Klangwolke-2 moves at the frames' mean drift rate, so the trend of its camera path is under 1 FWHM over the 28 kept frames, although the dither scatters it by 3.8 px;
  - on whynot, a straight path follows hot pixels only partly because the field rotates, so their camera paths spread by more than the limit.

  The check now stacks the frames at one camera position near the path, reading each frame where its registration puts that camera pixel, and requires the candidate to score at least 2 above that stack and twice as high. Real and injected movers on Klangwolke-2 reach 0.16 (median) to 0.42 of their score in that stack; whynot's hot-pixel candidates reach 0.34-0.78. The whynot candidates fell from 239 with a camera-spread limit of 0.5 FWHM, and 20 with 1 FWHM, to 2.
- *Split-half check.* A half counts only frames whose path position is unmasked. Injected movers crossing a bright star in one half had lost that half to the star mask; recovery at 19.0 mag rose from 75-84 % to 93 %, and 18.0 mag from 86-93 % to 89-95 %.
- *Side lobes.* A distance of 7 FWHM from a stronger object's path did not catch side lobes 25-40 px from 611 Valeria, 194 Prokne and 4767 Sutoku. The check now asks whether the candidate's path meets the stronger object in any frame, within 2 FWHM; it removed all seven on Klangwolke-2.
- *Sessions and limits.* Klangwolke-1 contains frames a month apart; one motion over a month made the grid and the tile margins enormous, and the run ran out of memory. Only the session with the most frames is searched now (a gap of over two hours separates sessions), and the search is not run beyond 100,000 trial motions or a quarter of the heap per tile. Klangwolke-1 keeps 7 frames in its largest session and is not searched.
- *Not implemented:* down-weighting pixels by their temporal variance near bright stars. The bright-star residual candidate at (5273, 400) on Klangwolke-2 remains.

**Open.**
1. *Run time of long sessions.* The cost is trial motions × frames × pixels and grows with the cube of the session length: 313 s for m101-reg (106 frames over 233 minutes, 10,116 motions) against 70-90 s for Klangwolke-2. A hierarchical shift-and-add (half-session stacks combined on a coarser motion grid) would make it nearly independent of the frame count, at the cost of memory.
2. *Ground truth for the unmatched candidates.* A catalogue fetch beyond magnitude 19.0 is needed before the five unmatched Klangwolke-2 candidates and those on Eagle-noisy, helix-reg and m101-reg can be called new or false.
3. *Other mechanisms* (temporal bins, PSF-filtered frame points) only if they recover objects that digital tracking misses.

## SM-F3. Improvements to maximum-stack slow-mover detection

**Status and decision.** Proposed (investigated 2026-11-03). The maintainer requested a thorough review of the maximum-stack slow-mover detection algorithm (`SlowMoverAnalyzer`, `SlowMoverFrameEvidence`, `MasterMapGenerator`) and proposals for improvements. This item records the investigation findings, edge-case limitations, and concrete improvement directions.

**Problem analysis and investigation findings.**

1. *Narrow motion corridor between axis ratio and stationary clustering.*
   `slowMoverMinAxisRatio = 1.2` and `slowMoverMaxAxisRatio = 2.8` define the primary morphological window. For a typical seeing profile with minor extent ~3.5 px, `axisRatio <= 2.8` limits total displacement to at most (2.8 - 1) * 3.5 ≈ 6.3 px. Faster movers are rejected by `rejectedAboveMaxAxisRatio`. Meanwhile, `slowMoverMaxStationaryLikelihood = 60.0%` with clustering radius ≈ 1.5 px requires that no single location contains more than 60% of supported frame centroids. For uniform motion, spending <= 60% of N frames in a 3 px diameter requires total motion:
   $$\Delta x = N \cdot v \ge \frac{3\text{ px}}{0.60} = 5.0\text{ px}$$
   Consequently, the combination of default settings allows only objects with total displacement in a narrow corridor of roughly **5.0 to 6.3 px** to pass both gates. Slower objects fail stationary likelihood (> 60%), while slightly faster objects fail axis ratio (> 2.8).

2. *Absence of chronological / temporal progression validation.*
   `SlowMoverFrameEvidence` measures spatial clustering of frame centroids, but does not examine sequence order or capture timestamps (`DATE-OBS`). If seeing fluctuations or noise spikes cause the best-aperture position to jump back and forth randomly between frames, the spatial cluster size can remain below 60%, allowing an unmoving, noisy artifact to pass as a slow mover. The algorithm lacks a test for monotonic physical displacement over time.

3. *Noise elevation in maximum stacks (Gumbel extreme-value effect).*
   The per-pixel maximum across N Gaussian noise frames has an expected value $\mu_{\max} \approx \mu_{\text{sky}} + \sigma_{\text{sky}}\sqrt{2 \ln N}$ (elevated by ≈ 2.6 sigma for N = 30). Because a moving source only illuminates a given pixel in a small subset of frames, faint slow movers with single-frame SNR ~ 1-2 do not rise above the maximum-stack background median. The maximum stack is therefore suited for moderately bright slow movers (single-frame SNR >= 4-5), while faint targets require coherent integration (such as digital tracking in [SM-F2](#sm-f2-experimental-high-sensitivity-moving-object-detection)).

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
| VS-12 4C | Do not add weighted Check-D slope tracking without new evidence: the study changed no readiness verdict, and the existing per-frame slope rule already uses its error. |
| VS-12 4G | Do not add per-candidate ensemble refits without a demonstrated self-leverage problem: in the 151 held-out tests the in-fit amplitudes were at least 99.7 % of the held-out ones, with substantial extra cost for refitting. |
