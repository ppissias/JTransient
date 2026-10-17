# JTransient code review

Reviewed on 2026-10-17 against commit `2f58ccf` (release 1.0.3). The SpacePixels references point to
its working tree on the same day.

## Scope and method

- **Code:** the whole pipeline, stage by stage. Drift and padding analysis, `SourceExtractor`, frame
  quality and session rejection, master stack and veto mask, slow movers, `TrackLinker` (all
  passes), `CommonMotionRejector`, `ResidualTransientAnalyzer`, the variable-star photometry,
  `CalibratedAutoTuner` and the legacy `JTransientAutoTuner`.
- **Docs:** `README.md`, `PIPELINE.md`, `ALGORITHM.md`, `CONFIG.md`, `AUTOTUNER.md`,
  `SlowMoverAlgorithm.md`, `VariableStarAlgorithm.md` and the `DetectionConfig` Javadoc. I compared
  every documented default with the code and with the shipped SpacePixels profile.
- **Earlier assessments:** `Tracklinker-ASSESSMENT.md` and `VariableStar-ASSESSMENT.md`. I checked
  which of their findings are still open in the code.
- **Evidence levels used below:**
  - **Probe:** reproduced with a small synthetic program that calls the real library classes
    (`Probes.java`, see [Probes](#probes)).
  - **Source:** found by reading the code, not reproduced.
  - **Doc:** a mismatch between the documentation and the code.
- None of the probes use real observing data, so the size of each effect on the reference datasets
  is still to be measured.
- The existing test suite passes: 141 tests, 0 failures. No code or documentation was changed
  during the review.

## Overall assessment

The design is sound. A median-stack star veto, per-frame detections, linkers from strict to loose,
then rescue passes for what is left is the right structure for this problem. The photometry is
careful and conservative.

The review found:

- one confirmed bug that spans JTransient and SpacePixels (finding 1);
- two confirmed failure modes in source extraction (findings 2 and 3);
- two confirmed weaknesses in the rescue and grouping stages (findings 4 and 5);
- several smaller logic issues (findings 6 to 12);
- a set of documentation mismatches;
- earlier assessment findings that are still open in the code.

---

## Confirmed bugs

### 1. Manual Transient Inspection changes the live settings, and the next Save keeps the change

**Evidence:** Probe (P3) and Source.

- `JTransientEngine.analyzeDitherAndDrift` writes the drift-based void radius into the caller's
  config: `config.voidProximityRadius = driftAnalysis.recommendedVoidProximityRadius`
  (`JTransientEngine.java:567`).
- It runs from `extractSourcesFromFrames` (`:352`), which both `runPipeline` (`:587`) and
  `detectTransients` (`:279`) use.
- `generateMasterStackWithDetails` copies the config first (`:156`), so the API behaves
  differently depending on the entry point.
- The SpacePixels detection runs copy the config before calling the engine
  (`DetectionPipelineSupport.createEffectiveDetectionConfig` → `baseConfig.clone()`, and
  `StarMaskStackTask` gets `config.clone()`).
- Manual Transient Inspection does not copy it:
  - `MainApplicationPanel.java:302–308` passes `getDetectionConfigurationPanel().getJTransientConfig()`;
  - `DetectionConfigurationPanel.java:706` returns the live object;
  - `ManualTransientInspectionTask.java:53` calls `engine.detectTransients(framesForLibrary, config, …)`.
- **Probe P3:** on 5 dithered frames with a 40–60 px padding band, `detectTransients` changed
  `voidProximityRadius` from 20 to 70. `generateMasterStackWithDetails` left it at 20.

**Consequences:**

- `voidProximityRadius` is a hidden setting with no row in the panel, so nobody sees the change.
- Every later detection run and Auto-Tune in the same session starts from the raised value.
- `SpacePixelsDetectionProfileIO.write` serializes the whole `DetectionConfig`, so the next Save
  writes the raised value into the user's profile, where it stays.
- With a larger void radius, detections lose a wider band at every edge (see finding 11 for the
  effect on the tuner).

**Fix direction:** copy the config inside the engine at the start of `runPipeline` and
`detectTransients`, as `generateMasterStackWithDetails` already does. A copy in the SpacePixels
caller would also fix the visible symptom, but the engine copy protects every API user. No change
in detections is expected for the normal detection runs, which already pass a copy.

### 2. The background estimate breaks once a frame has more than about 11 % padding

**Evidence:** Probe (P1) and Source.

- `SourceExtractor.calculateBackgroundSigmaClipped` (`SourceExtractor.java:342–394`) puts every
  pixel into the histogram, including registration padding at the floor value.
- The clipping uses 3σ. Once the zero-valued padding is more than about 1/3² ≈ 11 % of the frame,
  it inflates σ enough that the zeros sit inside the clipping window and are never removed.
- **Probe P1:** sky 1000 ADU, noise 20 ADU, 300 stars, padding as a band on the left edge.

  | Padding | Measured σ | Objects found at 5σ |
  |---|---|---|
  | 0 % | 20.2 | 283 |
  | 10 % | 21.1 | 280 |
  | 12 % | 349 | 135 |
  | 15 % | 389 | 108 |
  | 25 % | 499 | 57 |

- The result is a cliff, not a gradual loss.
- `FrameDriftAnalyzer` still accepts frames with up to 50 % padding (`MIN_VALID_AREA_FRACTION` 0.5).
  Frames between 11 % and 50 % padding lose most of their detections, or are rejected by the
  session evaluator for a low star count instead of being used.
- The same global estimate drives the thresholds, `peakSigma`, flux and the void test (finding 3),
  so the error spreads to every stage that uses those values.

**Fix direction:** leave floor-valued pixels out of the histogram, using the same padding
definition as `FrameDriftAnalyzer` (value ≤ `Short.MIN_VALUE + 8`). Datasets without padding would
be unchanged.

### 3. The void test misfires when the sky is close to zero

**Evidence:** Probe (P2) for `SourceExtractor`; Source for the other two places.

- `SourceExtractor` drops a detection when any one of 8 single-pixel probes on a square of radius
  `voidProximityRadius` is ≤ `bg.median * voidThresholdFraction` (0.5 × the sky median). A probe
  outside the image also counts as void (`SourceExtractor.java:208`, `:285–312`).
- On calibrated data without a pedestal (narrowband, short exposures, dark-sky sites), the sky is
  only a few σ above zero, and ordinary noise pixels fall below half the median.
- **Probe P2:** 300 isolated stars, noise 20 ADU, **no padding at all**.

  | Sky level | Sky in σ above zero | Stars kept |
  |---|---|---|
  | 1000 ADU | 50σ | 300 / 300 |
  | 200 ADU | 10σ | 300 / 300 |
  | 100 ADU | 5σ | 277 / 300 |
  | 60 ADU | 3σ | 168 / 300 |

- The same rule appears in two more places (not probed):
  - `ApertureMeasurer.java:209` flags a star `EDGE_OR_VOID` when any pixel of its main aperture is
    below half the frame background. On low-pedestal data this probably removes many faint stars
    from photometry.
  - `SlowMoverFrameEvidence.isMostlyVoid` (`:202`) uses the median the same way.
- There are now two different definitions of padding:
  - `FrameDriftAnalyzer`: an absolute value, ≤ `Short.MIN_VALUE + 8`.
  - `SourceExtractor`, `ApertureMeasurer` and `SlowMoverFrameEvidence`: a fraction of the sky median.
- The `DetectionConfig` Javadoc for `voidProximityRadius` (`:70–74`) describes the test as
  applying to streaks when "a significant portion" touches padding. In fact it applies to every
  object, and one probe is enough to reject it.

**Fix direction:** one shared, noise-aware padding test, for example "at or near the floor value"
or "below median − k·σ", used by all four classes. SpacePixels' `FieldDepth` already had to reset
the void radius for its patch cut-outs, which is another sign the test is too aggressive.

---

## Logic issues in the algorithm

### 4. Two stationary leftovers count as "common motion"

**Evidence:** Probe (P6) and Source.

- `CommonMotionRejector.agrees` (`:245–272`) compares the displacement of two sources over the same
  frames. Two sources that do not move have the same displacement (zero), so they always agree.
- A group needs sources at least 30 px apart, which two unrelated stationary leftovers easily are.
- **Probe P6:** a stationary 5-frame source (for example a flare or nova) and an unrelated leaking
  star 800 px away, both in frames 2–6. Without the filter, the residual analysis finds 2 local
  rescue candidates. With it, 1 common-motion group is formed and 0 candidates remain.
- Common motion is meant to catch shared registration errors, which move. Stationary residuals are
  exactly what the local rescue looks for.

**Fix direction:** require the shared displacement to be larger than the star jitter before two
sources can agree, or handle zero-motion groups separately.

### 5. Suspected streaks form from chance alignments

**Evidence:** Probe (P5) and Source.

- `groupSuspectedStreakTracks` / `buildBestSuspectedStreakTrack` (`TrackLinker.java:1152–1262`,
  builder at `:1214`) accept any 3 same-frame anomalies that lie within 6 px of a line. There is
  no limit on the gaps between them or on the span.
- **Probe P5:** anomalies placed at random in one 4000 × 3000 frame, 300 trials each.

  | Anomalies in the frame | Frames with a suspected streak |
  |---|---|
  | 4 | 11 / 300 (4 %) |
  | 6 | 34 / 300 (11 %) |
  | 10 | 146 / 300 (49 %) |
  | 20 | 300 / 300 (100 %) |

- The chance rate grows roughly with the cube of the anomaly count, so busy frames (noisy data,
  satellites, hot pixels) almost always produce one.
- `FaintStreakFragmentGrouper` already has the missing rules: the largest gap at most 40 % of the
  span, and a minimum span.

**Fix direction:** apply the same gap and span rules to suspected streaks, or require the expected
chance count for the frame's anomaly density to be small.

### 6. Star count has no floor in session rejection

**Evidence:** Source.

- `SessionEvaluator` builds each limit as median ± k × MAD-based σ.
- FWHM, eccentricity and background have absolute floors (`SessionEvaluator.java:110–134`); the
  background floor was added on 2026-10-10.
- Star count has none (`:100`). In a very stable session the MAD becomes small. At Normal (2σ) or
  Strict (1.5σ), frames only a few percent below the median star count are then rejected.

**Fix direction:** a relative floor, for example never reject for fewer than 10 % below the median
star count. This is the same idea as the background floor.

### 7. The residual analysis mixes two frame-numbering units

**Evidence:** Source.

- Chain growth in `collectNodes` uses the position in the frame list as the frame index
  (`ResidualTransientAnalyzer.java:226–238`), and `maxFrameGap` is checked against it (`:278–283`).
- The metrics use `sourceFrameIndex`, the original sequence index (`:462–499`), which still counts
  rejected frames.
- When a frame inside a candidate was rejected by session quality control, the candidate's frame
  span grows by one while its point count does not. Coverage drops, and the `frameSpan` gates can
  fail even though the chain was valid when it was built.
- The same mix appears in `filterExcludedDetections` (`:107` keys by `sourceFrameIndex`, `:116`
  looks up by list position). There it is mostly harmless, because identity matching runs first
  and the fallback also checks the file name.

**Fix direction:** use one unit throughout, most naturally the list position for gaps and the
sequence index only for reporting.

### 8. Two-point LOCAL_REPEAT has no check against chance

**Evidence:** Source.

- `minRepeatPoints = 2` (`ResidualTransientAnalyzer.java:718`): two detections within about 3 px
  in nearby frames make a LOCAL_REPEAT candidate.
- The signal gate (at least 5 on the larger of peak and integrated sigma) passes almost every
  detection that survived extraction.
- The number of chance pairs grows with the square of the leftover density. Nothing estimates how
  many to expect, so the false-alarm rate is not controlled across datasets.
- The candidate search is also O(N²) per frame pair, which matters on noisy data.

**Fix direction:** compare the number of pairs found with the number expected from the leftover
density per frame, or require a third point unless the pair is very significant.

### 9. Slow movers

**Evidence:** Source and calculation.

- **No check against the drift path.** In uncalibrated, drifting data, hot pixels follow the
  registration drift. In the maximum stack they leave short smears that can pass every gate:
  elongated, filled, not in the median mask, and supported in many frames. `driftPoints` are
  already available and could be compared with a candidate's per-frame centroids.
- **The sigma means something different on a maximum stack.**
  - Each pixel of the maximum stack is the largest of N frame values. Its noise is skewed with a
    heavy upper tail, and its measured σ is compressed: for 20 frames about 0.52 × the single-frame
    σ, around a median about 1.8 single-frame σ above the sky.
  - So a 3.5σ threshold on the maximum stack sits at about 3.7σ of a single frame. Any one of the N
    frames can cross it, which gives about ten times more noise seeds than a 3.5σ threshold on
    Gaussian noise would.
  - This is a calculation for a plain maximum of independent Gaussian frames, not a probe.
  - It matters for the open idea of tying the slow-mover sigma to the tuned detection sigma: the
    two numbers are not on the same scale, so a 1:1 link would not be like-for-like.
- **Correlated noise.** The aperture SNR in `SlowMoverFrameEvidence` assumes uncorrelated pixels.
  Registered (interpolated) frames have correlated noise, so the SNR is overstated (see also
  finding 12).
- **Coupling to photometry.**
  - Every slow-mover candidate flags every star within 5 FWHM as CROSSING in **every** frame
    (`VariableStarAnalyzer.java:1004–1021`).
  - A false slow mover therefore silently removes nearby stars from the variable-star analysis.
  - Tracks and frame detections feed the crossing flags too, so photometry depends on the
    detection profile, despite the comment at `:67`.

### 10. `maxStarJitter` does too many jobs

**Evidence:** Source.

- One setting controls:
  - the veto mask growth;
  - the minimum motion for both linkers;
  - the speed slack;
  - the step for anti-hijack pruning;
  - the stationary radii;
  - the dynamic angle relaxation in the time-based linker (`TrackLinker.java:677`).
- `CalibratedAutoTuner` sets it to 2 × the 90th percentile of the master-to-frame star distances,
  to get a good mask. The same value also raises the minimum motion a mover needs to be linked.
  On data with poor registration, slow movers then become unlinkable as a side effect of a better
  mask.

**Fix direction:** separate "registration error" (for the mask and stationary tests) from "minimum
mover speed", even if the second defaults to a multiple of the first.

### 11. Calibrated auto-tuner

**Evidence:** Source.

- **Rates use the full crop area.**
  - False-positive rates per MPix per frame use the full crop area.
  - The void probes reject every detection within `voidProximityRadius` of a crop edge, because
    probes outside the crop count as void.
  - For crops of about 1000 px, that band is about 8 % of the area at the default 20 px, and about
    25 % at 70 px (the value finding 1 can leave behind).
  - The measured rates are too low by that share, and the tuner picks a looser setting than
    intended.
- **Injections near crop edges are lost.**
  - Injections are placed at least `max(30, edge + 10)` px from the crop edges
    (`CalibratedAutoTuner.java:1026`).
  - With a raised void radius, those near the edges can no longer be recovered, which lowers the
    measured recovery.
- **The negative image matches noise only under equal seeing.**
  - The null image 2 × master − frame removes stars only when the frame's seeing matches the
    master's.
  - When the seeing differs, it leaves positive and negative rings around stars.
  - The measured false-positive rate then includes structure that the real frame does not have.
- **The budget is per detection,** while users see tracks and residual candidates. A setting with
  many isolated single-frame detections costs the same as one where those detections link into
  false tracks.
- The known global-background versus crop-background mismatch was discussed earlier and left as
  is; it is not repeated here.

### 12. Smaller issues

- **The geometric linker uses a fixed 2° direction tolerance** (`TrackLinker.java:830`). The
  time-based linker widens it for short steps (`:677`).
  - **Probe P4** (12 frames, centroid noise 0.25 px per axis, 200 trials): with no timestamps
    (geometric only), a mover is found in 154 / 200 trials at 3 px per frame, 195 at 6, and at
    least 198 at 10 or more. With timestamps it is found in 200 / 200 at every speed.
  - The effect is small, but the shipped SpacePixels profile turns geometric linking **on** while
    the code default is off. So Tracklinker finding 6 (the rhythm check ignores frame gaps) also
    applies to timestamped runs in SpacePixels.
- **The extraction "FWHM" reads about √2 too large.** `SourceExtractor.java:470` computes
  `2.355 * sqrt(λ1 + λ2)`, which is √2 × the per-axis value for a round star. `ApertureMeasurer`'s
  comment (`:22`) already notes this. Session rejection, `minFwhmEnvelope` and `maxFwhmRatio` all
  use this value, so changing it would move those thresholds. Documenting it is safer than
  changing it.
- **The legacy tuner's mask growth differs from the pipeline's.**
  - The legacy tuner grows the mask by `ceil(maxStarJitter)` (`JTransientAutoTuner.java:679`); the
    pipeline uses `max(1, round(maxStarJitter / 2))` (`MasterVetoMask.java:25`). The legacy tuner
    therefore measures with a larger mask than detection uses.
  - Its comment "Streaks bypass the veto mask in the main engine" (`:730`) is wrong.
  - It has `public static boolean DEBUG = true` by default (`:32`).
- **Registered frames have correlated noise.** `integratedSigma`, the slow-mover aperture SNR and
  `magError` all assume independent pixels, so they overstate significance. The thresholds are set
  empirically, so this changes how the numbers should be read rather than what is detected.
  `magError` also has no source photon noise, which `VariableStar-ASSESSMENT.md` already records.
- **`isTrackAlreadyFound` is redundant,** because `usedPoints` already prevents points from being
  reused.

---

## Documentation that disagrees with the code

### Slow-mover defaults

`CONFIG.md` has five wrong slow-mover defaults:

| Setting | `CONFIG.md` | Code |
|---|---|---|
| `slowMoverMinAxisRatio` | 1.35 | 1.2 |
| `slowMoverMaxAxisRatio` | 3.20 | 2.8 |
| `slowMoverMinFillFactor` | 0.0 | 0.5 |
| `slowMoverMinFrameSupport` | 0 | 40 |
| `slowMoverMaxStationaryLikelihood` | 100 | 60 |

- `ALGORITHM.md:299` says the fill-factor default (0.0) disables that filter; the default is 0.5.
- `SlowMoverAlgorithm.md` still gives 1.35 / 3.20 (`:510–511`, `:968–969`, `:1405–1406`), and says
  the frame vetoes "default to disabled" (`:804`). They default to 40 and 60.

### Other mismatches

- `README.md:37` gives version 1.0.0; `build.gradle` is 1.0.3.
- `DetectionConfig` Javadoc:
  - `enableGeometricTrackLinking` (`:324–328`) says "Keep this enabled", but the default is
    `false`.
  - `voidProximityRadius` (`:70–74`): see finding 3.
  - The `trackMinFrameRatio` example (`:330–333`) gives 7 points and ignores the cap of 5.
- The `CalibratedAutoTuner.cropRegions` Javadoc (`:1398`) says "up to three square crops"; the code
  uses 5 crops or a grid.

### The shipped SpacePixels profile versus the code defaults

These differences are deliberate, but the docs describe only the code defaults, which can confuse
SpacePixels users reading `CONFIG.md`:

- `detectionSigma` 3.5 and grow 2.75;
- slow movers 10 / 3.5 / 2.75;
- `maxStarJitter` 1;
- `enableGeometricTrackLinking` true (see finding 12);
- `autoTuneMaxCandidateFrames` exists only in the profile.

### Dead code and leftover output

- `SourceExtractor.isBinaryStarAnomaly` and `isIrregularStreakShape` are never called.
- `VariableStarAnalyzer.writeSpatialComparison` (`:512–709`) is never called.
- `VariableStarAnalyzer.java:387` prints `SPATIAL_FRAMES …` to stdout whenever spatial frames are
  dropped.
- `JTransientAutoTuner.DEBUG` is `true` by default.

---

## Still open from the earlier assessments

From `Tracklinker-ASSESSMENT.md`, none of the remaining findings are fixed in the code:

| # | Finding | Location |
|---|---|---|
| 3 | Streak timestamps dropped when the exposure duration is unknown | `TrackLinker.java:1557` |
| 4 | Streak projection in pixel² instead of pixels (unnormalised direction) | `TrackLinker.java:2216` |
| 5 | A degenerate streak line passes the motion check | streak validation |
| 6 | The rhythm check ignores real frame gaps | `TrackLinker.java:2253` |
| 7 | Time-based tracks get no final check for stationary tails (7.1) or curvature (7.2) | time-based linker, `:556–749` |
| 8 | Initial streak chains have no common-line check | streak chaining, `:336–424` |
| 9 | Suspected groups lose their seed line | `:1152–1262` |

The streak chaining (`:336–424`) also has no distance limit between chained streaks.

From `VariableStar-ASSESSMENT.md`, findings 2, 5, 6, 8 and 9 remain deferred as recorded there.

---

## The bigger picture

Most of the findings come back to five themes:

1. **One global background per frame.**
   - It drives the thresholds, `peakSigma`, flux and the void test.
   - Sky gradients, the padding cliff (finding 2) and the tuner's crop mismatch all follow from it.
   - A local background (a mesh, as in SExtractor) is the usual answer, but it is a large change. A
     padding-aware global estimate removes the worst failure first.
2. **No common estimate of chance alignments.**
   - Several stages each use their own heuristic for "this is too regular to be noise":
     suspected streaks (finding 5), LOCAL_REPEAT (finding 8), common motion (finding 4) and the
     geometric linker.
   - None of them compares what was found with what the frame's detection density would produce by
     chance.
   - One shared helper (expected chance count for N points, a tolerance and an area) would make
     results comparable between sparse and busy datasets.
3. **Units that drift between stages:** list position versus sequence index (finding 7), pixels
   versus pixel² (Tracklinker 4), and the √2 FWHM (finding 12).
4. **Two definitions of padding** (finding 3).
5. **Coupling between stages.** `maxStarJitter` has several roles (finding 10), and photometry
   depends on detection outputs (finding 9).

---

## Suggested fix order

Engine fixes go one at a time, each discussed first and checked with the real-data regression from
`AGENTS.md`:

1. **Copy the config inside the engine** (finding 1). No detection change is expected; also check
   the SpacePixels Manual Transient Inspection path.
2. **Leave padding out of the background histogram** (finding 2). Datasets with heavy dither or
   drift should gain detections; others should be identical.
3. **One noise-aware void test** with a single padding definition (finding 3).
4. **Gap and span rules for suspected streaks** (finding 5).
5. **Require real motion in the common-motion filter** (finding 4).
6. **Documentation fixes**, at any time, as one "Update docs" commit.

After those: the star-count floor (6), the residual frame units (7), the drift-path check for slow
movers (9), the tuner's effective crop area (11), then the open Tracklinker findings.

---

## Probes

`Probes.java` is in the review session's scratchpad (`scratchpad/probe/`), outside both
repositories. It uses synthetic inputs only and calls the real library classes. It was compiled
against `build/classes/java/main` of this repository plus Gson 2.13.2 from the Gradle cache.

| Probe | What it tests | Result |
|---|---|---|
| P1 | Background estimate with a padding band (sky 1000, σ 20, 300 stars) | σ 20 → 349 between 10 % and 12 % padding; objects 283 → 135 (finding 2) |
| P2 | Void test on low-sky data without padding | 300 / 300 kept at 10σ sky, 277 at 5σ, 168 at 3σ (finding 3) |
| P3 | Does `detectTransients` change the caller's config? | `voidProximityRadius` 20 → 70; `generateMasterStackWithDetails` leaves it at 20 (finding 1) |
| P4 | Point mover, 12 frames, 0.25 px noise, with and without timestamps | geometric only: 154 / 200 at 3 px per frame, ≥ 198 at 10+; timed: 200 / 200 (finding 12) |
| P5 | Suspected streaks from random anomalies in one frame | 4 % of frames with 4 anomalies, 49 % with 10, 100 % with 20 (finding 5) |
| P6 | Common-motion filter on two stationary leftovers 800 px apart | 2 local rescue candidates → 0 (finding 4) |

Before any fix, the relevant probes can be turned into unit tests so that each fix comes with a
regression test.
