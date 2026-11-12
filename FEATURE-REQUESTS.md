# Feature requests and algorithm proposals

Proposed changes to what JTransient detects, measures or tunes. Code organisation belongs in [DESIGN-IMPROVEMENTS.md](DESIGN-IMPROVEMENTS.md), current behavior in [ALGORITHM.md](ALGORITHM.md), and application features in SpacePixels' [feature requests](../SpacePixels/FEATURE-REQUESTS.md).

These are proposals, not implementation authorisations. Retain current algorithms and defaults until evidence supports a change and the maintainer agrees. Change one algorithm at a time and check recovery, false candidates, cost and unaffected results as described in [DEVELOPER.md](DEVELOPER.md#real-data-checks). Remove completed or closed requests; git history and the [existing research archive](docs/research/slow-movers/README.md) preserve their reviews. The declined decisions below remain binding.

## Open proposals

| ID | Status | Remaining scope and trigger |
|---|---|---|
| [QC-1](#qc-1-star-count-rejection-envelope) | Proposed | Consider a count-envelope floor if corrected common-area counts reject useful frames too readily. |
| [PT-5](#pt-5-registration-quality-and-shared-jitter-tolerances) | Proposed assessment | Measure residual stellar registration quality before deciding whether shared jitter tolerances need to change. |
| [PT-10](#pt-10-minimum-fill-for-timed-point-tracks) | Proposed | Require timed point tracks to cover a minimum share of the frames they span; measure faint real movers first. |
| [SM-F8](#sm-f8-frame-check-for-runs-of-leaky-frames) | Proposed; later addition | Leave out runs of frames whose star tails leak past the faint-mover mask, which the few-frame check cannot stop when they cover a third of the frames or more. Needs a second affected session. |
| [SM-F9](#sm-f9-slow-mover-candidates-made-by-hot-pixels) | Proposed | Reject maximum-stack candidates that stay at one camera position: hot pixels that the drift draws into short dashes. |
| [SM-F10](#sm-f10-faint-mover-paths-beside-a-star) | Proposed | Reject faint-mover paths that stay beside the star mask for most of the session; validate on movers that pass bright stars first. |
| [XC-3](#xc-3-local-background-and-noise-for-frame-extraction) | Open; parked at maintainer request; new evidence (2026-11-08) | Resume with local sky correction alone, keeping baseline noise and thresholds fixed; preserve associations, photometry and boundary calibration. |
| [VS-13](#vs-13-variable-star-recovery-and-false-candidate-investigation) | Proposed investigation | Empirical assessment of known variable-star recovery and false-candidate mechanisms across Klangwolke-2 and multi-candidate reference sessions. |
| [RV-1](#rv-1-findings-from-the-pull-request-1-review) | Open; no fix agreed | Seven code-review findings, led by hot pixels that can pass the follows-sky test on steadily drifting frames. Decide each item separately. |

**Mover consolidation decision (2026-11-05).** The digital-tracking delivery (SM-F2 and SpacePixels F25) is complete. Close the current SM-F1/SM-F3 temporal, morphology, position and gate investigations and the bounded SM-F6 validation/review campaign. Retire SM-F4 residual weighting and SM-F5 acceleration as separate open proposals; both remain unimplemented, with no validated replacement selected. Retain the present scientific filters and defaults; this closure does not claim universal completeness, calibrated confidence or the absence of difficult sources. The existing archive and [code history](docs/research/slow-movers/CODE-HISTORY.md) remain available separately.

## QC-1. Star-count rejection envelope

**Proposed.** Counts share a common quality area, but their rejection envelope has no absolute or relative floor. A narrow count distribution can make a small loss sufficient to reject a frame. No allowance or formula is agreed.

Revisit with stable, cloudy and sparse sessions showing useful frames lost by this rule. Compare recovery, false detections, gaps and strictness levels before choosing a floor; retaining more frames alone is not an improvement. Current behavior: [session rejection](ALGORITHM.md#5-session-level-frame-rejection), [SessionEvaluator](src/main/java/io/github/ppissias/jtransient/quality/SessionEvaluator.java).

## PT-5. Registration quality and shared jitter tolerances

**Proposed assessment.** The maintainer prefers assessing registration quality before splitting `maxStarJitter` into more settings. No tolerance or migration change is agreed.

Measure residual stellar alignment across the field, including translation, rotation, sparse/crowded fields and uncertain matches. Determine whether photometry measurements can supply one shared, authoritative assessment, its cost, and its place relative to extraction and frame rejection. The fill-edge camera transform is not a measure of remaining stellar misalignment.

Separate measured uncertainty from detection-policy minima before changing jitter consumers. Validate useful-mover retention and stationary false tracks on good and poor registrations; coordinate any new settings with SpacePixels. Current behavior: [registration](ALGORITHM.md#1-border-drift-diagnostics), [configuration](CONFIG.md).

## PT-10. Minimum fill for timed point tracks

**Proposed; no rule is agreed.** In a long sequence a timed point track needs only five points, wherever they fall: the required count is the frame count divided by `trackMinFrameRatio`, capped at `absoluteMaxPointsRequired` (5). `TimedPointLinker` has no continuity rule; the rhythm check runs only in the geometric linker and in streak consolidation. Where single-frame detections are dense, unrelated ones line up by chance. Current behavior: [time-based linking](ALGORITHM.md#10-time-based-point-linking).

**Evidence (2026-11-08).** A SpacePixels archive batch at maximum auto-tune gave long DSLR sequences hundreds of moving tracks each. A typical false track has 5-6 points spread over most of the session and appears in 5-8% (median) of the kept frames between its first and last point. These tracks don't follow the frame drift (0-1% within 20% of its velocity), and they sit where detections are dense ([XC-3](#xc-3-local-background-and-noise-for-frame-extraction)). Real tracks fill their span:

| Sequence | Moving tracks | Tracks that appear in at least 30% of their span |
|---|---|---|
| DSLR, M31, 101 frames | 1,661 | 0 |
| DSLR, wide field, 150 frames | 1,366 | 1 |
| DSLR, wide field, defocused, 42 frames | 1,505 | 108 |
| DSLR, M31, 97 frames | 640 | 1 |
| M33 field, 38 frames | 129 | 44 |
| klangwolke-2, known asteroids | 3 | 3 (fill 43-100%, median 83%) |
| Apophis | 1 | 1 (100%) |

**Proposal.** Require a track's points to cover a minimum share of the kept frames it spans, for example 30%, or limit the longest gap between consecutive points. Applied while a track grows rather than only at the end, the rule would also shorten candidate evaluation across long spans (the local third-point search is accelerated by `SpatialGrid`, JT-06).

**Before adopting.** Only four real tracks were checked. A faint real mover that falls below the threshold in many frames, crosses star masks or bright regions, or loses rejected frames could drop under the line. Measure injected faint movers and the reference regression, and compare a fill share with a gap limit. Timed tracks may still bend ([PT-3](#declined)). Reducing the detection density first ([XC-3](#xc-3-local-background-and-noise-for-frame-extraction)) may remove most of these tracks; decide the order from that comparison.

<!-- Stable targets for retired request IDs in historical documents. -->
<a id="sm-f1-temporal-confirmation-of-slow-mover-candidates"></a>
<a id="sm-f3-improvements-to-maximum-stack-slow-mover-detection"></a>
<a id="sm-f4-bright-star-residuals-in-faint-mover-searches"></a>
<a id="sm-f5-faint-mover-runtime-and-memory-scaling"></a>
<a id="sm-f6-faint-mover-validation-and-candidate-review"></a>
<a id="sm-f7-complementary-high-sensitivity-moving-object-searches"></a>
<a id="sm-f7-slow-and-faint-mover-extensions"></a>
<a id="vs-9-selected-stars-and-the-linearity-check-minimum"></a>
<a id="vs-11-photometry-strictness-defaults-and-recovery-review"></a>

## SM-F8. Frame check for runs of leaky frames

**Proposed; a later addition to the delivered few-frame check.** In a 68-frame session, every star in frames 4-11 had a faint one-sided tail in the same direction, roughly 1% of its light or less, and quality control passed those frames (bright-star elongation 1.16-1.31 against a 1.32 limit). Bright stars' tails reached past the faint-mover star mask, and the search reported 200 false candidates. The few-frame check, `enableFaintMoverFrameLimit` (on by default, see [ALGORITHM.md](ALGORITHM.md#faint-mover-search)), removes candidates whose score comes from a few frames. On a crop of that session it left 1 of 174, whose strongest frames were in that run. It cannot stop a residual that a third or more of the frames share.

**Evidence for the check (2026-11-08, real-engine runs on crops).** Injected movers were recovered as before: 25 of 35 in quiet sky and 12 of 12 passing a bright star. klangwolke-2 kept its 10 known candidates with unchanged scores, and other false candidates on the injected crop fell from 102 to 2. A wider mask around bright stars (cores over 100 sigma grown by 1.5 FWHM) was rejected: it lost 3 of the 12 movers passing a bright star. Leaving out each path's strongest 18% of frames was rejected too, because it loses 41% of real movers at score 8 over 65 frames (simulation). Tightening quality control on elongation would also reject 26 of the session's 60 other frames.

**Proposal: a frame check.** Measure the light just past the mask of about 150 moderately bright, unsaturated stars (over 100 sigma in the stack; the brightest stars' masks hide the lobes) in 12 directions, in frame noises. Flag runs of 3 or more consecutive frames at 0.5-0.75 or more, each pointing within 45 degrees of the one before, and leave them out of the faint-mover search. Across 25 datasets this flagged exactly frames 4-11 of that session, 6 of 211 frames in one wide-field DSLR sequence (whether they cause candidates is unknown), and nothing else. It was designed on the only known bad session, so it needs another affected session before adoption. It would cover the long bad stretches that the few-frame check cannot.

## SM-F9. Slow-mover candidates made by hot pixels

**Proposed (2026-11-08).** In a batch of 102 archive sessions, two uncalibrated sessions gave 36 and 23 slow-mover candidates, where most sessions gave none: a 44-frame session on a 0.4 MP sensor and a 6-frame session on a 1.9 MP sensor. Their maximum-stack cut-outs are full of short parallel dashes with the length and direction of the session's drift (5 x 1 px and 7 x 2 px). They are hot pixels that registration moves along with the dither and the drift.

The camera-fixed rejection does not see them. It works on frame detections, and a single hot pixel is below `minDetectionPixels`, so it never becomes one; only the maximum stack joins its positions into an object large enough for `masterSlowMoverMinPixels`. The slow-mover stage has no camera check of its own: [`SlowMoverFrameEvidence`](src/main/java/io/github/ppissias/jtransient/core/SlowMoverFrameEvidence.java) only leaves out frames whose footprint is registration fill.

**Proposal.** Before a maximum-stack candidate is accepted, follow its brightest pixel back to camera coordinates in each frame (its position minus the frame's offset). Reject it when one camera position, within a pixel, is above the noise in most of the frames, as a hot pixel is; a real slow mover moves in camera coordinates as well. A cheaper shape test, a dash as long as the drift and pointing along it, would also reject a real mover that happens to move with the drift. Validate on the slow movers that the reference datasets keep, and count what remains on the two sessions.

## SM-F10. Faint-mover paths beside a star

**Proposed (2026-11-08).** A 32-frame, three-hour session on a 1.4 MP sensor gave 131 faint-mover candidates, about 90 per MPix; no other archive session gave more than 9 per MPix. Run again with the few-frame check, 82 remained. Of those, 95% lie within 5 px of the star mask (73% on it), and 56% share one direction and almost the same rate, about 20 px over the session. The cut-outs show the path sliding past a bright star whose halo, or a tail in the later frames with elongated stars, reaches past the mask while the sky brightness changes strongly over the session. The stationary check does not catch them, because the star is masked in the zero-motion stack (stationary score about 0).

Measured on the 82 paths: the share of each path's frames within 1.5 FWHM of the star mask has a median of 97%, and 76% of the paths stay that close in 80% of their frames or more. Random straight paths with the same velocities do so in 7% of cases (median 0%). [SM-F8](#sm-f8-frame-check-for-runs-of-leaky-frames) already rejects a blanket wider mask, which lost 3 of 12 injected movers passing a bright star.

**Proposal.** Reject a candidate whose path stays within about 1.5 FWHM of the star mask in at least about 80% of its frames. A real mover passing a star is near it only while it crosses; it is lost only when its whole motion over the session is shorter than about a star's masked footprint, where it cannot be told from the halo anyway. Before adoption, check the 12 injected movers passing bright stars and klangwolke-2's 10 known candidates (all must stay), and the false candidates on this session and on the SM-F8 session.

## XC-3. Local background and noise for frame extraction

**Open, parked at the maintainer's request; retain the current per-frame model.** A continuous local sky/noise prototype improved fixed-flux recovery, but also lost some controlled recoveries, changed source associations and photometry classifications/readiness, and added many unverified findings. No production algorithm, gate or default change is selected. The master reference's existing local-sky pass remains unchanged.

The next proposed experiment changes only the continuous local sky level, retaining the baseline's exact per-frame global noise estimates and configured seed/grow multipliers. Use consistent local background subtraction for measurements while preserving physical image pixels. Footprints, centroids, fluxes and elongation can still change; assess those changes and source associations explicitly rather than requiring identical shapes. Evaluate another noise estimator separately, after this comparison. Photometry's local sky does not solve frame extraction.

Keep path coverage and score calibration separate: continuous background interpolation avoids hard cell boundaries, and digital tracking already reads motion-sized halos, while per-tile noise estimates can still change scores near a reference-tile boundary. Successor validation needs crossing and near-threshold controls, retained source support/associations, star-by-star photometry, false-candidate review and runtime across gradients, extended emission and flat sky. Preserve the master reference's coverage. Current implementation: [extraction](ALGORITHM.md#3-single-frame-extraction-internals), [master stars](ALGORITHM.md#7-master-star-extraction), [faint search](ALGORITHM.md#faint-mover-search), [SourceExtractor](src/main/java/io/github/ppissias/jtransient/core/SourceExtractor.java).

**New evidence (2026-11-08).** A SpacePixels archive batch at maximum auto-tune gave long DSLR sequences hundreds of false moving tracks each ([PT-10](#pt-10-minimum-fill-for-timed-point-tracks)). Per-frame extraction thresholds the raw frame at one frame-wide median plus k sigma, so where the sky or an extended object is brighter than that median the threshold sits closer to the noise. Measured in 128-px blocks, in units of each block's own pixel noise:
- In a 101-frame M31 sequence, the 3.7% of blocks below 6 (the galaxy) hold 88% of the track points.
- In a 150-frame wide-field sequence with a gradient, the 23% of blocks below 6 hold 94%.
- Flat sky sits at 7-8, because the frame-wide sigma is about twice the pixel noise. The values differ between cameras; the comparison holds within a sequence.

In these sequences the auto-tuner also raised `maxMaskOverlapFraction` (0.65-0.85, against 0.5 in quiet sequences), and 12-21% of their track points lie on the saved star mask. Revisit that choice after a local-sky change. This evidence supports resuming with the planned local-sky experiment; whether to resume is the maintainer's decision.

Retain the frozen profile, executable/source and dependency snapshots, native baseline results, input hashes and boundary controls for resumption. The local resume bundle and its verification are located through the machine's ignored environment notes; detailed experimental material remains outside the algorithm reference and repository.

## VS-13. Variable-star recovery and false-candidate investigation

**Proposed investigation (2026-11-12).** A comprehensive empirical study comparing JTransient's variable-star detections against external catalogue ground truth across the primary and multi-candidate reference datasets. The goal is two-fold: verify completeness by checking how well the engine recovers known catalogued variables that should be detectable, and evaluate purity by analysing false-positive candidates that lack catalogue counterparts to identify why subtle frame noise or artifacts trigger detection.

### Input datasets

- **Primary benchmark:** `klangwolke-2` (using `_16bit_converted`), the golden reference. The saved 2026.11-02 report `detections_20261109_202345` has 70 variable candidates (51 high confidence, 19 possible). Catalogue confirmation of those candidates is this investigation.
- **Multi-candidate reference sessions:** Sessions from the variable-star batch and archive yielding significant variable detections (above 5–6 candidates). `M81-kumar-L-dataset-registered` has 18 candidates in `detections_20261111_174343`. The inventory in the local `REFERENCE-DATASETS.md` names the other sessions and their saved counts.

### Investigation scope

1. **True-positive recovery (known catalogued variables):**
   - Cross-match fetched catalogue variables (VSX, Gaia DR3) against the engine's scored stars and reported candidates (`HIGH_CONFIDENCE`, `POSSIBLE`, `REJECTED`).
   - Focus on variables with **detectable variability** rather than subtle sub-threshold noise (e.g. amplitudes $\ge 0.10$ mag, avoiding marginal ~0.03 mag variations).
   - Filter for **temporal visibility**: check published periods and variability types against session duration (e.g. period $\le 2 \times$ session span, or known short-period pulsating / eclipsing variables that undergo observable variation during the observing window).
   - Filter for **photometric feasibility**: exclude targets that are too faint for reliable aperture SNR given the session's sky noise and exposure depth.
   - For any expected detectable variable that was missed or demoted to `REJECTED`, pinpoint the exact stage responsible:
     - Extraction / star selection (e.g. `photometryMinSnr`, saturation, proximity to edges/neighbours);
     - Check B qualification / linear range exclusion;
     - Contamination / spatial exclusion;
     - Candidate gate failure (e.g. `APERTURE`, `PERSISTENCE`, `AMPLITUDE_FLOOR`, `LINEARITY`).

2. **False-positive analysis (uncatalogued detections):**
   - Examine candidates reported as variables that have no catalogue match in well-surveyed fields (likely false positives).
   - Trace the mechanisms allowing subtle or random frame noise to mimic real astrophysical variability:
     - Frame noise fluctuations aligning spuriously across apertures;
     - Star profile / seeing variations across exposures in undersampled frames;
     - Border, registration drift, or padding interpolation artifacts;
     - Undetected cosmic rays or cosmetic flaws leaking past star-peak / outlier cleaners;
     - Blended neighbours or faint unmasked companions contaminating the aperture.
   - Determine whether specific gates, outlier filters, or noise-dependent thresholds can suppress these false candidates without losing real variables.

### Deliverables and verification

- Run non-destructively on existing reference data and saved reports without altering current detection defaults or production code.
- Produce a structured audit report summarizing:
  - Known variable recovery rate (detected vs. expected detectable), categorized by amplitude and period;
  - Missed-variable failure points by pipeline gate;
  - Root causes and classifications of uncatalogued false detections;
  - Evidence-based proposals for any gate or threshold improvements for the maintainer to review before implementation.

## RV-1. Findings from the pull request 1 review

**Open; no fix is agreed.** The cloud code review of [pull request 1](https://github.com/ppissias/JTransient/pull/1) (2026-11-07) reported one normal-severity finding and six nits. GitHub refused the review's comment on the pull request, so this entry is the record of its findings. They are kept together at the maintainer's request, although RV-1.5 to RV-1.7 are code-quality items of the kind [DESIGN-IMPROVEMENTS.md](DESIGN-IMPROVEMENTS.md) normally holds.

Each mechanism below was checked by reading the reviewed source, which is identical to commit `1b7e4fd`; line numbers refer to that commit. None was reproduced by running the code or on real data. Evaluate each item and record the decision with its change, as [DEVELOPER.md](DEVELOPER.md#working-rules) requires, and retain current behavior until then.

| Item | Severity | Effect | Applies when |
|---|---|---|---|
| [RV-1.1](#rv-11-hot-pixels-can-pass-the-follows-sky-test) | Normal | False tracks; seen in 4 of 102 archive sessions | Steady sub-pixel drift without dither |
| [RV-1.2](#rv-12-faint-trails-can-be-reported-twice) | Nit | Same trail exported twice | Default configuration; normal pass finds only point-like pieces of a trail |
| [RV-1.3](#rv-13-worker-limit-is-recalculated-after-the-faint-searches) | Nit | Slower photometry | Photometry on (the default), heap under pressure |
| [RV-1.4](#rv-14-very-large-faint-mover-time-limit-overflows) | Nit | Faint-mover search stops at once | Search on (the default), limit of about 9.2e9 seconds or more |
| [RV-1.5](#rv-15-orphaned-notifyobserver-javadoc) | Nit | Documentation only | Always |
| [RV-1.6](#rv-16-duplicated-star-veto-overlap-rule) | Nit | Maintenance risk | Always |
| [RV-1.7](#rv-17-diagnostics-are-copied-under-the-evidence-pool-lock) | Nit | Run time and garbage collection | Faint-mover search on, many passing peaks |

### RV-1.1 Hot pixels can pass the follows-sky test

**Possible false detections compared with the last release, which has no follows-sky exception.** [`CameraFixedRejector.followsSky`](src/main/java/io/github/ppissias/jtransient/core/CameraFixedRejector.java) (lines 219-252) leaves a group to linking when its straight-line fit in the registered frames has an rms below 1 px and below half the rms of its fit in camera coordinates. [ALGORITHM.md](ALGORITHM.md#94-camera-fixed-sources) says a group on frames that drift steadily without dither stays camera-fixed, because both fits are tight.

The camera fit may not be tight on real registrations. For a pure translation, [`FrameRegistration.fit`](src/main/java/io/github/ppissias/jtransient/core/FrameRegistration.java) (lines 93-131) fits the whole-pixel fill edges measured by `FrameCoverage`, so a true shift of 0, 0.8, 1.6, 2.4, 3.2 px becomes whole-pixel steps such as 0, 1, 2, 2, 3 px. That rounding adds about 0.3 px rms to each drifting axis of the camera positions. A hot pixel's registered positions follow the true drift with only centroid noise, which the review puts at about 0.05 px. The group then counts as following the sky, its detections stay in `transients`, and their straight path can link into a false track or a local-rescue repeat.

- **Trigger.** Steady sub-pixel drift without dither, pure translation, sub-pixel registration upstream, at least 4 frames and at least 4 px of spread in the frames. From the rms formula alone, the per-axis centroid scatter must also be below about 0.12 px for drift along one axis, or 0.17 px along both; this is an estimate, not a measurement.
- **Test gap.** `CameraFixedRejectorTest.aMoverRidingASteadyDriftCannotBeToldFromAHotPixel` builds exact fractional shifts with `FrameRegistration.of`, so it does not exercise the rounded fill-edge fit.
- **Real sessions (2026-11-08).** In a batch of 102 archive sessions, 7 moving tracks in 4 sessions are hot pixels that passed this test. Each follows its frames' whole-pixel offsets (the report's drift table) to within 0.4-0.9 px. Its straight-line fit has an rms of 0.07-0.12 px in the registered frames and 0.35-0.42 px in camera coordinates, about the rounding of whole-pixel offsets (0.29 px per drifting axis), so the ratio is 0.18-0.35, under the 0.5 limit. In three of the four sessions the hot pixel is the only track reported.
- **Next step.** Reproduce it with registrations fitted from `FrameCoverage`; real sessions do reach it (above). The review proposes no fix. A fix must keep the slow movers that the follows-sky exception protects, so it needs the [algorithm-change comparison](DEVELOPER.md#real-data-checks).

### RV-1.2 Faint trails can be reported twice

**Default configuration.** In [`TrackLinker`](src/main/java/io/github/ppissias/jtransient/core/TrackLinker.java) (lines 325-347), `FaintStreakFragmentGrouper.assemble` absorbs same-frame point detections only into the streak tracks that already exist, before the faint-search tracks are added; a new line needs at least 4 pieces and an 80 px span. `FaintStreakLinking.add` later joins only rescued anomalies to the new tracks. Unrescued normal pieces on the trail therefore stay in `unclassifiedTransients`, which common-motion rejection and residual analysis both read, and the trail appears both as a streak track and as unclassified transients.

**Correction to the review.** It also says these pieces can become a `LOCAL_REPEAT`, citing `ResidualTransientAnalyzerTest.minimumDoesNotRequireThreeDistinctFrames`. That test uses detections from two frames, and a `LOCAL_REPEAT` needs a cluster radius of at most 2.5 px and a total displacement of at most 3 px, so pieces spread along a trail would not normally qualify. Three close pieces in a single frame were not checked.

[ALGORITHM.md](ALGORITHM.md#135-faint-streak-search) says the search changes nothing of the normal pass, and absorbing these pieces would change what the normal pass exports. Decide whether that is wanted, with evidence on trail recovery and false absorption.

### RV-1.3 Worker limit is recalculated after the faint searches

**Run time only.** The Javadoc of `searchFaintStreaks` and `searchFaintMovers` in [`JTransientEngine`](src/main/java/io/github/ppissias/jtransient/engine/JTransientEngine.java) says the run's limit is "set again" afterwards. The `finally` blocks (lines 990 and 1052) instead recalculate it through `WorkerPool.limitWork` from the heap in use at that moment, which also includes extraction results, stacks and uncollected garbage from the search. The only later batch that runs under a recalculated limit is photometry's (`VariableStarAnalyzer`, line 878), so under heap pressure it can get fewer workers, down to one.

Restoring the saved limit would match the comment, but a fresh estimate may be safer when the heap really is fuller. If the recalculation stays, correct the Javadoc.

### RV-1.4 Very large faint-mover time limit overflows

**Experimental search, on by default.** [`FaintMoverSearch`](src/main/java/io/github/ppissias/jtransient/core/FaintMoverSearch.java) computes `plan.startedNanos + (long) (faintMoverMaxSeconds * 1e9)` (line 514). At about 9.2e9 seconds (about 292 years), the addition overflows when `System.nanoTime()` is positive, leaving a negative deadline. `DetectionConfigRules` accepts any finite value above 0, including `Double.MAX_VALUE`, so a caller who sets a huge limit to mean "no limit" gets a search that stops before its first tile and reports truncation.

Compare elapsed time with `System.nanoTime() - started` against a limit that saturates, as the `System.nanoTime` documentation recommends, at all four comparison sites (lines 521, 579, 581 and 745).

### RV-1.5 Orphaned `notifyObserver` Javadoc

**Documentation only.** In [`JTransientEngine`](src/main/java/io/github/ppissias/jtransient/engine/JTransientEngine.java), the new search methods were inserted between `notifyObserver` (line 1056) and its Javadoc, which now sits at line 962 directly above the `searchFaintStreaks` Javadoc. Move it back above `notifyObserver`.

### RV-1.6 Duplicated star-veto overlap rule

**Maintenance risk.** `FaintStreakSearch.maskOverlap` ([lines 333-345](src/main/java/io/github/ppissias/jtransient/core/FaintStreakSearch.java)) repeats the private `TransientVeto.computeMaskOverlapFraction` ([lines 147-162](src/main/java/io/github/ppissias/jtransient/core/TransientVeto.java)), so a change to one would make the normal pass and the faint search veto stars differently. The copies differ only in that `TransientVeto` takes the width from the first row and checks for cancellation at each pixel; on a rectangular mask both return the same value.

Make the `TransientVeto` method package-private and call it from `FaintStreakSearch`.

### RV-1.7 Diagnostics are copied under the evidence-pool lock

**Run time and garbage collection.** `EvidencePool.capture` in [`FaintMoverSearch`](src/main/java/io/github/ppissias/jtransient/core/FaintMoverSearch.java) (lines 303-310) is synchronized and starts by copying the peak's full diagnostics: up to 128 frames of up to 256 x 256 floats, about 33 MB, plus two half maps. Only then does it insert the copy and evict down to the 64 MiB budget. Tile workers with passing peaks wait on that lock, and a peak ranked below the weakest entry of a full pool can be copied only to be evicted at once.

Check the rank or reserve the size inside the lock, copy outside it, then insert and evict inside it. Only review evidence is copied, so candidates change only if the extra time makes the search reach `faintMoverMaxSeconds`.

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
