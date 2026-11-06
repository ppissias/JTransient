# Feature requests and algorithm proposals

Proposed changes to what JTransient detects, measures or tunes. Code organisation belongs in [DESIGN-IMPROVEMENTS.md](DESIGN-IMPROVEMENTS.md), current behavior in [ALGORITHM.md](ALGORITHM.md), and application features in SpacePixels' [feature requests](../SpacePixels/FEATURE-REQUESTS.md).

These are proposals, not implementation authorisations. Retain current algorithms and defaults until evidence supports a change and the maintainer agrees. Change one algorithm at a time and check recovery, false candidates, cost and unaffected results as described in [DEVELOPER.md](DEVELOPER.md#real-data-checks). Remove completed or closed requests; git history and the [existing research archive](docs/research/slow-movers/README.md) preserve their reviews. The declined decisions below remain binding.

## Open proposals

| ID | Status | Remaining scope and trigger |
|---|---|---|
| [QC-1](#qc-1-star-count-rejection-envelope) | Deferred | Consider a count-envelope floor only if corrected common-area counts reject useful frames too readily. |
| [PT-5](#pt-5-registration-quality-and-shared-jitter-tolerances) | Proposed assessment | Measure residual stellar registration quality before deciding whether shared jitter tolerances need to change. |
| [SM-F7](#sm-f7-slow-and-faint-mover-extensions) | Deferred; no further campaign scheduled | Revisit specific mover losses or a demonstrated resource bottleneck; retain digital tracking and current maximum-stack gates. |
| [XC-3](#xc-3-local-background-and-noise-for-frame-extraction) | Pilot reviewed; adoption deferred | Improve spatial sensitivity while preserving source associations, photometry and score calibration near tile boundaries. |
| [VS-9](#vs-9-selected-stars-and-the-linearity-check-minimum) | Deferred policy | Distinguish insufficient Check-B evidence from measured nonlinearity; retained-subset scoring is not implemented. |
| [VS-11](#vs-11-photometry-strictness-defaults-and-recovery-review) | Deferred follow-ups | Six bounded photometry questions below; the initial audit and adopted VS-12 work are complete. |

**Mover consolidation decision (2026-11-05).** The digital-tracking delivery (SM-F2 and SpacePixels F25) is complete. Close the current SM-F1/SM-F3 temporal, morphology, position and gate investigations and the bounded SM-F6 validation/review campaign. Retire SM-F4 residual weighting and SM-F5 acceleration as separate open proposals; both remain unimplemented, with no validated replacement selected. Keep one conditional extension item, SM-F7, below. Retain the present scientific filters and defaults; this closure does not claim universal completeness, calibrated confidence or the absence of difficult sources. The existing archive and [code history](docs/research/slow-movers/CODE-HISTORY.md) remain available separately.

## QC-1. Star-count rejection envelope

**Deferred.** Counts share a common quality area, but their rejection envelope has no absolute or relative floor. A narrow count distribution can make a small loss sufficient to reject a frame. No allowance or formula is agreed.

Revisit with stable, cloudy and sparse sessions showing useful frames lost by this rule. Compare recovery, false detections, gaps and strictness levels before choosing a floor; retaining more frames alone is not an improvement. Current behavior: [session rejection](ALGORITHM.md#5-session-level-frame-rejection), [SessionEvaluator](src/main/java/io/github/ppissias/jtransient/quality/SessionEvaluator.java).

## PT-5. Registration quality and shared jitter tolerances

**Proposed assessment.** The maintainer prefers assessing registration quality before splitting `maxStarJitter` into more settings. No tolerance or migration change is agreed.

Measure residual stellar alignment across the field, including translation, rotation, sparse/crowded fields and uncertain matches. Determine whether photometry measurements can supply one shared, authoritative assessment, its cost, and its place relative to extraction and frame rejection. The fill-edge camera transform is not a measure of remaining stellar misalignment.

Separate measured uncertainty from detection-policy minima before changing jitter consumers. Validate useful-mover retention and stationary false tracks on good and poor registrations; coordinate any new settings with SpacePixels. Current behavior: [registration](ALGORITHM.md#1-border-drift-diagnostics), [configuration](CONFIG.md).

<!-- Stable targets for retired request IDs in historical documents. -->
<a id="sm-f1-temporal-confirmation-of-slow-mover-candidates"></a>
<a id="sm-f3-improvements-to-maximum-stack-slow-mover-detection"></a>
<a id="sm-f4-bright-star-residuals-in-faint-mover-searches"></a>
<a id="sm-f5-faint-mover-runtime-and-memory-scaling"></a>
<a id="sm-f6-faint-mover-validation-and-candidate-review"></a>
<a id="sm-f7-complementary-high-sensitivity-moving-object-searches"></a>

## SM-F7. Slow and faint mover extensions

**Deferred; no additional experiment or integration is scheduled.** Digital tracking is the delivered faint-source route, experimental and off by default. Maximum-stack morphology remains a separate candidate route, with copied position/time evidence and cautious track comparisons for review. The completed investigations do not justify changing its filters, the default axis-ratio limit, or the digital-tracking checks. See the current [maximum-stack method](ALGORITHM.md#slow-movers) and [digital tracking](ALGORITHM.md#faint-mover-search).

Keep these conditional leads together:

- **Weak close companions.** Temporal-bin proposals followed by the unchanged full-sequence verifier added targeted injected companions; the native full-field follow-up added no detection. Revisit only with a credible observed missed companion or an independent case that can distinguish source light from contamination. This is an additive proposal method, not a temporal veto on maximum-stack candidates. PSF-filtered frame-point linking has not demonstrated a useful complete comparison and is not a scheduled implementation.
- **Star or trail overlap.** Revisit local source separation, masking or candidate construction when a concrete useful mover is lost. Broad mask, shape, support, clipping or score relaxations have recovery/control costs; none earns a default change. Descriptive correlation and nominal fit covariance do not justify a new motion gate or automatic duplicate suppression.
- **Complete coverage within a useful budget.** Investigate exact computation reuse or another acceleration if an actual workload cannot finish within its required time/memory budget. Existing cancellation, time/heap/trial guards and partial-coverage reporting remain. A computation-only optimisation must preserve scientific values; an approximation needs its own sensitivity/control decision.

Before adopting a lead, demonstrate incremental recovery or useful complete-coverage benefit against the current implementation, with source attribution, matched domains and controls, bounded cost and retained baseline findings. Agreement is also required on engine/application settings, result provenance and review behavior. Catalogue compatibility remains post-detection evidence; empirical scores remain uncalibrated. Do not reopen general threshold sweeps or repeat the reviewed grids without a specific unanswered failure.

## XC-3. Local background and noise for frame extraction

**Gradient confirmed; retain the current per-frame model.** A continuous local sky/noise prototype improved fixed-flux recovery, but also lost some controlled recoveries, changed source associations and photometry classifications/readiness, and added many unverified findings. No production algorithm, gate or default change is selected. The master reference's existing local-sky pass remains unchanged.

The next bounded question is whether correcting the local sky level separately from noise calibration can retain the sensitivity benefit with fewer downstream changes. Define the conservative or residual-noise policy before testing; preserve physical pixel values and consistent centroid, flux and significance calculations. Photometry's local sky does not solve frame extraction.

Keep path coverage and score calibration separate: continuous background interpolation avoids hard cell boundaries, and digital tracking already reads motion-sized halos, while per-tile noise estimates can still change scores near a reference-tile boundary. Successor validation needs crossing and near-threshold controls, retained source support/associations, star-by-star photometry, false-candidate review and runtime across gradients, extended emission and flat sky. Preserve the master reference's coverage. Current implementation: [extraction](ALGORITHM.md#3-single-frame-extraction-internals), [master stars](ALGORITHM.md#7-master-star-extraction), [faint search](ALGORITHM.md#faint-mover-search), [SourceExtractor](src/main/java/io/github/ppissias/jtransient/core/SourceExtractor.java).

## VS-9. Selected stars and the linearity-check minimum

**Deferred readiness policy.** The star-cap warning is delivered and is not pending. Check B already uses `INCONCLUSIVE` when too few high-SNR shape measurements are initially available; too little counted range or too few references after restriction can still give `FAIL` without measured nonlinearity.

Decide whether those cases warrant different classification and whether a separately validated retained subset can be scored when the original session fails. The completed review demonstrated no recovered valid variable from these changes. Preserve the original-session diagnosis, required reference count and minimum-final-frame guard; do not iterate rejection/refitting until a session becomes READY.

Revisit with known-linear sparse/range-limited inputs and genuine response faults, measuring variable recovery and false candidates under original versus retained-subset readiness. Current contracts: [Check B](ALGORITHM.md#photometry-8-3), [readiness verdict](ALGORITHM.md#photometry-10), [configuration validation](src/main/java/io/github/ppissias/jtransient/config/DetectionConfigRules.java).

## VS-11. Photometry strictness, defaults and recovery review

**The audit and adopted VS-12 changes are complete.** Only the following six questions remain. Investigation is authorised; a scientific-policy or default change still needs evidence and agreement. Low-count readiness belongs solely to VS-9. Current behavior is in [photometry](ALGORITHM.md#16-variable-star-photometry) and the [release notes](RELEASE_NOTES.md#108-unreleased).

**Initial-settings decision (2026-11-06).** Retain the current general defaults, including 50 magnitude neighbours, the 100-pixel local radius and the 0.05/0.10-mag amplitude floors. Larger comparison pools and a lower Ready amplitude floor give field-dependent additions or tier promotions, without a consistent recovery benefit in the bounded image controls. Broader spatial radii do not justify a change. This closes the general starting-settings sweep; the specific questions below remain deferred or open as stated. Catalogue associations are review evidence, not session-variable truth or a false-positive calibration.

### Late-frame population lifecycle

**Deferred.** Check D and spatial rejection occur after population/contamination statistics. Revisit with a READY/LIMITED session that actually has late frame exclusions; the previous review showed no recovered valid variable. Compare one bounded recomputation on final active frames, preserving reasons, identities and the reference convention, without a rejection/refitting loop. See [contaminated shape](ALGORITHM.md#photometry-7-1) and [spatial exclusion](ALGORITHM.md#photometry-9-4).

### Reason-specific LIMITED handling

**Deferred.** No gain was demonstrated from changing the LIMITED amplitude floor. Compare real low-amplitude variable recovery and false candidates under inadequate range/transparency evidence and genuine response faults before assigning reason-specific policies. Retain the current floor until then. See [verdict](ALGORITHM.md#photometry-10) and [gates](ALGORITHM.md#photometry-11-4).

### Spatial correction of comparison cutouts

**Deferred; SpacePixels presentation.** Cutouts match sky and zero point but omit the spatial-plane difference. Revisit only with a visible mismatch; choose an explicit main-reference or selected-solution display contract and handle unavailable corrections. A display approximation must not claim exact selected-curve calibration or change measurements.

### Brief and fast variability

**Deferred.** The current cleaner, persistence gate and positive-correlation statistic suppress very brief or rapidly alternating signals. The completed peak-removal check addresses one artifact cause, not a brief-event search. Re-establish the current residual-artifact baseline before testing an additional path with image-level variables and realistic constant-star controls over cadence, missingness, seeing and blends. Keep the existing gates and the adopted gap rule. See [cleaning](ALGORITHM.md#photometry-11-1), [scores](ALGORITHM.md#photometry-11-2) and [coverage](ALGORITHM.md#photometry-11-5).

### Peak-removal check follow-up

**Open diagnosis.** Milder fading/brightening imbalances and residual single-pointing fadings remain unexplained. More reference comparisons catch artifacts at the cost of rejecting more real profile-shaped dimmings; a pixel-phase-matched reference would need better subpixel registration. Establish the cause and genuine-event cost before changing the session gate or one-pixel test. See [light-curve cleaning](ALGORITHM.md#photometry-11-1); general registration measurement belongs to PT-5.

### Blank-sky factor in star selection

**Deferred.** The agreed blank-sky factor scales reported errors only. Applying it to median-SNR selection and Check B would make those selections use measured correlated-noise errors, but would change the scored population and possibly readiness. Evaluate variable recovery, false candidates and `photometryMinSnr` on the reference regression before changing that contract. See [errors and flags](ALGORITHM.md#photometry-6-3).

**Boundaries.** Weighted Check-D slope tracking and per-candidate ensemble refits stay closed under [Declined](#declined). Catalogue-calibrated magnitudes belong to [SpacePixels F24](../SpacePixels/FEATURE-REQUESTS.md#f24-calibrated-magnitudes-for-variable-stars); fixed-camera analysis to [SpacePixels F23](../SpacePixels/FEATURE-REQUESTS.md#f23-fixed-camera-and-all-sky-sequence-analysis). Keep the current empirical noise model and reported-error contract unless a separate change is agreed.

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
