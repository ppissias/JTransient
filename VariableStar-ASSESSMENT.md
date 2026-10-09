# Variable-star detection algorithm assessment

Reviewed on 2026-10-09 against the current workspace implementation, including
`VariableStarAlgorithm.md`, the complete `photometry` package, its configuration and results,
the engine integration, and the existing photometry tests. No detection logic was changed.

## Scope and input assumptions

This review preserves the assumptions established during the TrackLinker assessment:

- Frames are chronological, registered images of the same field.
- Capture timestamps are available for every frame or unavailable for every frame, never mixed.
- Exposures have the same physical duration. Timestamped reproductions use positive, equal
  exposure durations and real epoch-based timestamps.
- Equal exposure durations do not imply equal intervals between captures. Readout, pauses,
  missed captures and rejected frames can still create gaps.
- The intended photometric input is original linear data in the library's signed-short pixel
  representation, not stretched display images. A stable passband and comparable calibration
  are also needed for interpreting the differential light curves.

The public analyzer is normally fed by the engine. Missing parallel input lists, mismatched
image dimensions and arbitrary malformed metadata are not counted as supported-workflow bugs.

The observing examples below are plausible illustrations, not failures measured in a real
observing dataset. The report explicitly distinguishes full-pipeline synthetic image tests,
component-level reproductions, source-inspection concerns and deliberate detection limits.

## Overall assessment

The design is coherent and appropriately conservative for finding **candidate** variables in a
single session. It does considerably more than compare each star's brightest and faintest frame:

1. Selects isolated, unsaturated master stars away from edges and void padding.
2. Measures each frame's residual registration offset and FWHM, then forced photometry in five
   apertures with a clipped local sky estimate and sub-pixel aperture weights.
3. Flags crossing objects, contaminated shapes, bad fluxes and non-linear measurements.
4. Checks quantisation, concentration against brightness, and magnitude-dependent response.
5. Fits `m(i,j) = M(i) + Z(j) + a(j)x(i) + b(j)y(i)`, robustly down-weighting unstable stars and
   individual bad measurements.
6. Scores excess scatter and a Stetson-style serial-correlation statistic against similarly
   bright stars, then applies eight acceptance gates.

The per-star/per-frame ensemble approach is a sensible foundation for differential photometry;
the original inhomogeneous ensemble method explicitly accommodates changes in the available
comparison stars. This is methodological context, not a claim that the current robust plane
solver exactly reproduces that paper's implementation.
[Honeycutt, 1992](https://articles.adsabs.harvard.edu/pdf/1992PASP..104..435H).

Useful safeguards include retaining flagged measurements for inspection, estimating the noise
from the session rather than trusting incomplete formal errors, using a common frame mask in
the main solve, down-weighting variable references, and distinguishing readiness from candidate
classification. The existing tests exercise genuine variability, a steady trend, seeing-related
blends, asteroid crossings, hot pixels, stretching, quantisation and aperture flux recovery.

However, I found **nine findings**, not nine equally severe unconditional bugs. The most
important implementation problems are inconsistent registration coordinates in crossing flags
and incomplete convergence checks in the ensemble solver. Some candidate safeguards are also
weaker than their physical interpretation suggests.

| # | Finding | Classification | Evidence |
| --- | --- | --- | --- |
| 1 | Correctable frame offsets turn stationary stars into crossings | High-priority implementation defect | Full pipeline and crossing component |
| 2 | Ensemble convergence ignores changing plane coefficients | High-priority numerical defect | Ensemble component and debugger |
| 3 | Track crossing interpolation ignores available capture times | Conditional implementation defect | Crossing component; regular-cadence control passes |
| 4 | A same-frame fragmented track flags only one fragment's position | Conditional implementation defect | Crossing component |
| 5 | Opposite aperture light curves pass the aperture gate | Acceptance weakness | Scorer returns HIGH_CONFIDENCE |
| 6 | Separated excursions can satisfy persistence and every other gate | Temporal-model weakness | Scorer returns HIGH_CONFIDENCE |
| 7 | Unmeasurable response slopes can be reported as PASS | Conditional readiness-status defect | Readiness component |
| 8 | Similar unsaturated peaks can be mistaken for the saturation ceiling | Conservative heuristic limitation | Saturation estimator component |
| 9 | The effective minimum star count exceeds the advertised initial threshold | Configuration/diagnostic trap | Shape-check component |

## Findings and observing examples

### 1. Crossing flags ignore the registration correction used for photometry

Source: `src/main/java/io/github/ppissias/jtransient/photometry/ApertureMeasurer.java:142`,
`src/main/java/io/github/ppissias/jtransient/photometry/VariableStarAnalyzer.java:582`,
`src/main/java/io/github/ppissias/jtransient/photometry/VariableStarAnalyzer.java:594`.

**In plain language:** the photometry correctly finds that an image is shifted, but another
stage interprets the shifted stars as moving objects and discards their measurements.

`ApertureMeasurer` measures at `master position + measured frame offset`. In contrast,
`flagCrossings` builds its master and selected-star grids at the unshifted master positions and
compares them with detections and pixels in frame coordinates. It never applies the measured
offset when deciding whether a detection is stationary.

**Observing example:** an otherwise well-aligned exposure has a uniform two-pixel residual
translation. All stars are shifted together, so the registration spread is small and the offset
can be corrected. With a frame FWHM near three pixels, the stationary-matching radius is only
about 1.5 pixels. A stationary detection two pixels from its master position is treated as a
non-stationary detection; its own pixels then flag its star as `CROSSING`.

**Verified full-pipeline reproduction:** translating frame 5 of the existing synthetic image
fixture two pixels horizontally gives:

- Measured offset X: **1.990417 pixels**.
- Registration spread: **0.017617 pixels**, below the default 0.5-pixel exclusion threshold.
- **238 measurements flagged CROSSING** on that frame.
- Frame exclusion: **"Too few measurable stars for the linearity check"**.

The frame survived upstream quality filtering; the probe locates it by its original sequence
index rather than assuming that retained-frame array indices equal original indices.

Debugger evidence shows execution entering the non-stationary branch even though matching
the same detection after subtracting the measured offset returns `correctedMatchesMaster=true`.

**Trigger:** a residual translation larger than the stationary-matching radius but small enough
for the offset measurement to correct. This does not require rotation, mixed timestamps or
different exposure lengths. Ordinary frames with smaller offsets do not exhibit it.

**Impact:** unnecessary loss of otherwise measurable frames and possibly an entire session if
too few frames remain. This reproduction establishes lost data, not a false variable candidate.

**Fix direction:** perform stationary matching, footprint checks and track crossing checks in
one consistent coordinate system. Either transform frame positions back to the master system
or use frame-shifted star grids. Apply the same correction to raw footprint pixels.

### 2. The ensemble can stop while its spatial correction is still changing

Source: `src/main/java/io/github/ppissias/jtransient/photometry/EnsembleSolver.java:91`,
`src/main/java/io/github/ppissias/jtransient/photometry/EnsembleSolver.java:100`,
`src/main/java/io/github/ppissias/jtransient/photometry/EnsembleSolver.java:104`.

**In plain language:** it checks whether the image's overall brightness correction has settled,
but not whether the fitted gradient across the image has settled.

The solver alternates frame fitting, gauge fixing, star fitting and robust reweighting. Its stop
condition examines only changes in `zeroPoint`. It ignores `planeX`, `planeY`, star magnitudes
and the stability of the reweighted solution.

**Observing example:** a changing thin-cloud gradient darkens one side of the field and brightens
the other relative to the ensemble, while the field-wide average correction barely changes.
A few contaminated or variable stars initially influence the plane. Reweighting should reduce
their influence over successive iterations, but an almost unchanged zero point can stop the
iteration before that spatial correction settles.

**Verified component reproduction:** 64 symmetrically positioned stars, a time-varying X gradient,
and opposite excursions in a pair of stars produce these frame-18 coefficients:

| Iteration, zero-based | Maximum zero-point change | Plane X | Plane Y |
| --- | --- | --- | --- |
| 0 | 0 | 0.142421 | 0.211919 |
| 1 | 3.72e-16 | 0.020766 | 0.008437 |

The solver stops at iteration 1 because the zero-point change is below `1e-5` mag, despite the
large change in the plane. Reconstructing the point weights from the returned solution and
performing one further frame solve changes a plane coefficient by **0.008419 mag per normalized
coordinate**. That is not a numerically settled solution at the declared tolerance.

**Trigger:** frame terms can change without appreciably changing the zero points, particularly
with `photometryFitPlane=true`, which is the default. The exact reproduction is deliberately
symmetric to isolate the missing convergence criterion; it is not a frequency estimate for
real fields.

**Impact:** residual spatial systematics or removal of real stellar signal, potentially
affecting later readiness and candidate decisions. The probe demonstrates premature convergence,
not a full-pipeline false-candidate rate.

**Fix direction:** monitor changes in all fitted frame terms, preferably their predicted
magnitude corrections at the measured star positions, and consider objective/weight stability.
Expose whether convergence was reached or the iteration limit was exhausted. Merely increasing
the iteration limit does not fix this early exit.

### 3. Crossing interpolation uses frame indices instead of available timestamps

Source: `src/main/java/io/github/ppissias/jtransient/photometry/VariableStarAnalyzer.java:662`,
`src/main/java/io/github/ppissias/jtransient/photometry/VariableStarAnalyzer.java:688`.

**In plain language:** a mover's missing position is predicted as if captures were equally
spaced, even when their actual capture times say otherwise.

**Observing example:** an asteroid is detected before and after it overlaps a star. The later
exposure was delayed by a camera pause. During the overlap the extractor reports only the
combined stationary-looking blob, so track interpolation is the protection against contamination.

| Sequence index | Capture time, seconds | Constant-speed mover X |
| --- | --- | --- |
| 0 | 0 | 30 |
| 1 | 1 | 50, missing from the track |
| 2 | 10 | 230 |

All exposures can be 100 milliseconds long. The correct middle position is X=50. The function
uses `(1 - 0) / (2 - 0) = 0.5` and predicts **X=130** instead of using the timestamp fraction 0.1.

**Verified component result:** the star at X=50 receives flags 0; the unrelated star at X=130
receives `CROSSING` (8). A regular-cadence control with the middle capture at five seconds flags
the correct midpoint and passes.

**Trigger:** unequal capture intervals, a missing track point, and interpolation being needed
because direct detection footprints do not already flag the contaminated measurement. Equal
exposure lengths and all-or-none timestamp availability do not remove this case.

**Impact:** missed contamination at one star and unnecessary exclusion at another. The example
proves the incorrect crossing mask; shape checks or outlier cleaning may still catch a particular
affected light-curve point later.

**Fix direction:** use capture-time interpolation whenever valid times exist. With equal
exposure lengths, a common start-to-midpoint offset cancels from the interpolation fraction.
Retain sequence-index interpolation for untimed inputs, with an explicit cadence assumption.

### 4. A fragmented same-frame track does not flag all its known positions

Source: `src/main/java/io/github/ppissias/jtransient/photometry/VariableStarAnalyzer.java:654`,
`src/main/java/io/github/ppissias/jtransient/photometry/VariableStarAnalyzer.java:680`,
`src/main/java/io/github/ppissias/jtransient/photometry/VariableStarAnalyzer.java:686`.

**In plain language:** a track can contain several pieces in one image, but the crossing mask
uses only the first piece's center.

`interpolate` stops on the first point whose source frame is at or after the requested frame.
When several points share that frame, it returns the first point's coordinates. `flagTrack`
then uses the largest individual FWHM/major extent, not the distance between those fragments.

**Observing example:** a satellite trail is extracted as two pieces. Its second piece overlaps
a star. That merged detection is near a master star and is skipped by the direct-detection
stationarity test. The returned streak track already contains the second piece, but the track
mask checks only the first piece, far away.

**Verified component reproduction:** a frame-0 streak track contains centers at X=40 and X=240.
A selected/master star is at X=240. Both detections are supplied to crossing analysis. The
direct branch skips the second detection because its centroid matches the master star, and
the track branch uses X=40 with a six-pixel radius. The star at X=240 retains flags **0**.

**Trigger:** multiple same-frame track points, an overlapping fragment far from the first one,
and the direct footprint path not flagging it. The ordinary one-point-per-frame case is not
the failure being reported.

**Impact:** known tracked contamination can be omitted from the photometry mask.

**Fix direction:** flag every observed track point and footprint in its source frame. Use
interpolation only for missing frames, and handle a same-frame streak's projected coverage
explicitly. A streak should not be deemed stationary solely because its centroid is near a star.

### 5. Matching aperture amplitudes do not establish matching light curves

Source: `src/main/java/io/github/ppissias/jtransient/photometry/VariabilityScorer.java:263`,
`src/main/java/io/github/ppissias/jtransient/photometry/VariabilityScorer.java:268`.

**In plain language:** the two apertures can disagree about whether the star is brightening or
fading, yet pass because the total sizes of their changes happen to match.

The APERTURE gate compares only the 5th-to-95th percentile ranges. It does not compare the time
patterns, their signs, their correlation or whether a unit-slope relation fits their residuals.

**Observing example:** a faint unresolved neighbour contributes different light to the larger
aperture, or a position/sky error changes the contributions differently. The small aperture
brightens while the large one fades. That does not support a clean measurement of one isolated
star's brightness change, even if both ranges are 0.3 mag. Many real cases will be caught by
earlier crowding or shape checks; this gate itself does not establish agreement.

**Verified scorer reproduction:** the main and small curves are a 0.15-mag semi-amplitude sine
wave, while the large curve is its exact negative. With 80 quiet comparison stars:

- Aperture amplitude difference: **0**.
- Failed gates: **none**.
- Tier: **HIGH_CONFIDENCE**.

The coherent-aperture control also receives HIGH_CONFIDENCE, as it should.

**Trigger:** inconsistent aperture time patterns with sufficiently similar robust amplitudes,
while the other gates pass. This is a scorer-level acceptance weakness, not a demonstrated
full-image false positive from an actual blended star.

**Fix direction:** require temporal agreement as well as amplitude agreement, allowing for
measurement noise. Compare only the intersection of frames with finite main, small and large
residuals: the current mask checks only whether the main residual is finite, and percentile
calculations can otherwise silently use different subsets for the two apertures.

### 6. Missing observations are collapsed into apparently persistent events

Source: `src/main/java/io/github/ppissias/jtransient/photometry/VariabilityScorer.java:473`,
`src/main/java/io/github/ppissias/jtransient/photometry/VariabilityScorer.java:514`,
`src/main/java/io/github/ppissias/jtransient/photometry/VariabilityScorer.java:537`,
`src/main/java/io/github/ppissias/jtransient/photometry/VariabilityScorer.java:562`.

**In plain language:** five separated excursions can become a five-point persistent event
when the measurements between them are unusable.

Outlier support searches for the previous/next finite value; Stetson J pairs consecutive finite
values; persistence skips non-finite values without resetting its run; and split-half pairing
also skips gaps. None of these functions uses capture-time separation or original frame indices.
The timestamps affect the DATA span, but not these continuity tests.

**Observing example:** a star has a normal baseline followed by isolated contaminated exposures
separated by clouds, crossings or masked measurements. There is no observation establishing
that the bright state lasted continuously between them, but the surviving bright points support
one another as if they were adjacent exposures.

**Verified scorer reproduction:** among 100 timestamped frames at two-minute cadence, the target
has 20 usable baseline points and excursions of +0.2 mag at indices **20, 40, 60, 80 and 99**.
All intervening target measurements are unusable and marked CROSSING. Eighty comparison stars
are measured throughout. Main, small and large apertures agree on the supplied usable points.

Result: **25 usable points**, **longest run 5**, **no failed gates**, **HIGH_CONFIDENCE**.
A separate helper probe likewise returns a run of 5 for five separated excursions.

**Classification caveat:** the implementation explicitly defines consecutive *usable* points,
so this is not a contradiction of that narrow definition. It is a limitation of using that
definition as evidence that a change persisted across several consecutive exposures. The probe
demonstrates that gap bridging can survive all scorer gates; it does not simulate the images or
prove that earlier shape/crossing checks would miss every real-world excursion.

**Fix direction:** distinguish adjacent retained measurements from continuous temporal support.
Preserve original sequence indices and, for timed data, use a configurable maximum pairing/support
gap. Missing observations need not rule out a variable, but should not silently strengthen its
persistence. Document the regular-cadence assumption for untimed inputs.

### 7. An unavailable response measurement can still receive PASS

Source: `src/main/java/io/github/ppissias/jtransient/photometry/PhotometricReadinessChecker.java:233`,
`src/main/java/io/github/ppissias/jtransient/photometry/PhotometricReadinessChecker.java:236`,
`src/main/java/io/github/ppissias/jtransient/photometry/PhotometricReadinessChecker.java:280`.

**In plain language:** "could not measure the response" can be reported as "response passed".

A degenerate weighted line fit returns NaN for its slope. `fails` is then false, the per-frame
status is PASS, and the frame still contributes to the solved-frame count. If the zero-point
range is large enough, the session status can also be PASS despite unavailable slopes.

**Observing example:** after exclusion, the remaining reference stars have insufficient
magnitude leverage to determine whether bright and faint stars respond differently. Changes
in transparency alone do not provide the missing brightness comparison.

**Verified component reproduction:** 60 equal-mean-magnitude stars in 40 solved frames with a
0.1-mag zero-point range produce **NaN slopes** but **PASS** for check D.

**Scope:** this is a degenerate readiness-component reproduction. Default check B normally
requires a broad magnitude range before the ensemble, so the test is not proof that an ordinary
equal-brightness field can pass the complete default pipeline. Changes in the usable reference
population, or permissive configuration, are the conditions requiring additional investigation.

**Fix direction:** report non-finite/underdetermined fits as INCONCLUSIVE, require enough finite
slopes and sufficient magnitude leverage, and do not equate a failed-to-measure result with a
passed physical test.

### 8. Five similar bright peaks can cause a false saturation estimate

Source: `src/main/java/io/github/ppissias/jtransient/photometry/PhotometryStarSelector.java:146`,
`src/main/java/io/github/ppissias/jtransient/photometry/PhotometryStarSelector.java:151`.

**In plain language:** the brightest values occurring several times are not necessarily the
camera's clipping ceiling.

The estimator treats five pixels within 0.1% of a frame's maximum as a saturation pile-up,
wherever that maximum lies. Without such a pile-up its fallback is 62258.25 in the shifted domain.

**Observing example:** several similarly bright, unsaturated stars have nearly identical peaks
around 10000 ADU in a camera whose clipping level is much higher. Integer encoding or broad
cores can make several top pixels fall within the ten-ADU tolerance.

**Verified estimator reproduction:** five values of 10000 on a 1000-ADU background produce a
session saturation estimate of **10000**, hence a default working threshold of **8500**.
Selected stars with peaks above 8500 can consequently be rejected as saturated without reaching
the actual sensor ceiling.

**Classification caveat:** the code correctly implements its documented heuristic. The input
histogram alone cannot prove whether those equal peaks were genuine clipping or a coincidence.
The probe deliberately supplies the latter and demonstrates the ambiguity; it does not establish
a universal alternative ceiling or measure false-rejection frequency on real cameras.

**Fix direction:** allow a known saturation limit in the correctly calibrated/shifted pixel
domain. For automatic estimation, require stronger evidence of clipping than an absolute count
of five similar maxima. Check B can still provide a separate safeguard against roll-off.

### 9. Fifty selected stars cannot satisfy the default shape-check minimum

Source: `src/main/java/io/github/ppissias/jtransient/photometry/VariableStarAnalyzer.java:151`,
`src/main/java/io/github/ppissias/jtransient/photometry/PhotometricReadinessChecker.java:109`,
`src/main/java/io/github/ppissias/jtransient/photometry/PhotometricReadinessChecker.java:164`,
`src/main/java/io/github/ppissias/jtransient/photometry/VariableStarAnalyzer.java:249`.

**In plain language:** a caller can provide the nominal minimum number of excellent stars and
still have every frame diagnosed as non-linear simply because the test discards its faintest tenth.

**Observing example:** to reduce processing cost in a rich field, a caller sets
`photometryMaxStars=50`, matching the default `linearityMinStars=50`. The initial selection
check accepts that number. Check B subsequently counts only stars up to its 90% faint bound,
so at most 45 of those 50 count towards the required 50 linear stars.

**Verified shape-check reproduction:** 50 measurements have identical concentration index 0.75,
small magnitude errors and a four-mag input range. The counted linear range is **3.591837 mag**,
well above the two-mag minimum, but there are only **45 counted stars**. The result is **FAIL**.

With the current rounding, at least **55 measurable stars** are needed even in the ideal
no-roll-off case, because `ceil(0.9 * 55) = 50`. Additional exclusions can require more.

**Classification caveat:** requiring 50 stars *inside* the retained linear range is a legitimate
policy. The problems are the misleading initial minimum/cap combination and the failure message,
which can tell the user that bright stars are flatter or the data is stretched when constant CI
and insufficient sample count are the actual reason. This is not evidence of a false candidate.

**Fix direction:** validate the cap against the effective minimum, or clarify the distinction
between selected and counted linear-reference stars. Report insufficient samples separately
from measured non-linearity, preferably as INCONCLUSIVE with a specific reason.

## Additional limitations and source-inspection concerns

These are not additional confirmed image-level defects:

- **Readiness is not proof of linearity.** The documented pure-power-law blind spot is real as
  an identifiability limitation, not a newly discovered bug. Keep the requirement for original
  linear data. `READY` means the implemented checks found no problem.
- **Quantisation samples only the middle frame.** This leaves a theoretical mixed-processing
  coverage gap, but I do not count the tested quantised-frame scenario as a surviving bug:
  checking the original sequence index showed that upstream quality filtering removed that
  frame before photometry. The corrected control passes. A middle-frame PASS must not be
  interpreted as a per-frame quantisation certificate; reusing the saturation histograms for
  per-frame distinct-level diagnostics would be inexpensive in image-pass terms.
- **Formal errors and SNR.** The sky-only `magError` is explicitly a lower bound. It omits source
  photon noise, detector gain and other error terms. The empirical scoring model helps, but
  the selection SNR and the shape-check 0.02-mag error threshold still use that incomplete error.
  A zero sky MAD gives error zero; the analyzer then treats SNR as unavailable rather than high.
  Validate these regimes before interpreting the thresholds as physical SNR guarantees.
- **Reference-population assumptions.** The median neighbouring scatter assumes enough quiet
  stars at each brightness. If most nearby-in-magnitude stars are variable or systematically
  affected, the empirical baseline can be inflated. A spatial plane can also absorb patterns
  that resemble its basis; this is an inherent reference-model ambiguity, not automatically
  evidence of an implementation error.
- **Detection completeness.** Requiring positive serial coherence, persistence and split-pair
  correlation favours changes slower than the cadence. Fast alternating variables, short
  eclipses and single-frame flares can be missed or rejected intentionally. The same is true
  of sparse fields lacking three local quiet references. Published variability-index comparisons
  likewise distinguish scatter-sensitive indices from indices sensitive to variations longer
  than typical observation intervals.
  [Sokolovsky et al., 2017](https://arxiv.org/abs/1609.01716).
- **Score interpretation.** `scatterZ` and `stetsonJZ` are robust empirical comparisons, not
  calibrated Gaussian false-alarm probabilities. They also share the same residuals and noise
  baseline, so "two independent scores" should not be interpreted as statistical independence.
  HIGH_CONFIDENCE is the library's gate classification, not catalogue confirmation.
- **A single response re-solve.** Check D is not repeated after failing frames are removed and
  the ensemble changes. This is documented, but a validation of the final solution would be
  stronger. No second-solve failure was established by this review.
- **Scalability.** Measurement and solver matrices scale with stars times frames; the three
  aperture solves, repeated residual allocations, local candidate scans and per-frame pixel
  work can become costly in large sessions. Star capping rounds per-region quotas independently,
  which can underfill a cap or truncate later regions. No runtime/memory benchmark was performed;
  profile before redesigning these parts.
- **Metadata boundary cases.** Mid-exposure dates treat timestamps at or below zero as unknown,
  whereas the frame API names only -1 as unknown. Unix-epoch/pre-epoch dates therefore deserve
  an API-consistency test if they are supported. This does not affect normal modern observing
  timestamps and is not a mixed-timestamp finding.

## Verification and reproducibility

- **9 existing photometry tests pass.**
- **99 tests in the complete existing suite pass**, after removing the temporary source-tree
  review class. No permanent failing tests were left in the project.
- **15 archived review checks run: 4 pass and 11 fail proposed correctness/stronger-acceptance
  assertions.** These are not 11 independent bugs. Registration and gap handling each have
  two probes, and several assertions deliberately test limitations of documented heuristics.
- The four passing controls cover coherent aperture curves, regular-cadence crossing prediction,
  a real steady trend with irregular cadence in the full pipeline, and upstream rejection of
  the tested quantised frame. The steady-trend concern was not counted as a reproduced defect.
- IntelliJ logpoints capture **104 events**, with **no evaluation errors in the final run**.
  The debugger pauses at the end of the probe runner to retain events; the inspected JUnit
  result is **15 runs / 11 failures**. Review breakpoints and the session are removed afterward;
  the user's disabled exception breakpoints are preserved.
- The archived runner was independently compiled and rerun after leaving the source tree;
  the same 15/11 outcome reproduced. Its expected exit code is 1 until the proposed assertions
  are satisfied. It uses the existing test fixture reflectively for its full-image scenarios.

Artifacts are under `build/review/variable-star-assessment-20261009/`:

- [VariableStarAlgorithmReviewProbe.java](build/review/variable-star-assessment-20261009/VariableStarAlgorithmReviewProbe.java): exact inputs, component checks and full-pipeline scenarios.
- [RunProbes.ps1](build/review/variable-star-assessment-20261009/RunProbes.ps1): rebuild with `gradlew.bat testClasses` and run this script from the repository, using the local Java setup from `setpaths.bat`.
- [probe-output.txt](build/review/variable-star-assessment-20261009/probe-output.txt): independently rerun outcomes and measured values.
- [debugger-events.json](build/review/variable-star-assessment-20261009/debugger-events.json): final captured values and exact source locations.
- [baseline test report](build/review/variable-star-assessment-20261009/baseline/html/index.html): original photometry tests.
- [existing-suite test report](build/review/variable-star-assessment-20261009/existing-suite/html/index.html): all 99 existing tests.
- [assessed-probes test report](build/review/variable-star-assessment-20261009/assessed-probes/html/index.html): the final 15 review checks, including passing controls.

These tests establish specific behavior, not completeness or false-positive rates over real
astronomical populations. Those require multiple noise seeds, realistic PSFs/blends/masks,
different cadences and representative image datasets with known constant and variable sources.

## Recommended order

1. Correct the registration coordinate mismatch and add its full-pipeline regression.
2. Fix ensemble convergence to monitor spatial terms as well as zero points.
3. Make crossing masks timestamp-aware and cover every same-frame track fragment.
4. Strengthen aperture agreement and define gap-aware temporal support, while measuring the
   trade-off in genuine-variable recovery.
5. Make undefined readiness fits explicit, clarify the effective star minimum, and allow a
   caller-supplied saturation limit.
6. Validate detection rates on representative data before tuning score thresholds or expanding
   the variability types the algorithm promises to recover.
