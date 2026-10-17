# JTransient code review

Reviewed on 2026-10-17 against commit `2f58ccf` (release 1.0.3). Re-assessed on the same day against
the SpacePixels import rules described below. This file replaces `Tracklinker-ASSESSMENT.md`
(2026-10-09), whose findings are merged here. `VariableStar-ASSESSMENT.md` stays separate.

## How to use this file

Each issue has an ID (for example `EX-1`), a short status line, a description, an example and a
fix direction. At the end of each issue there is a **Your comments** block:

```
**Your comments:**

- Decision: <!-- implement / discuss first / defer / won't fix -->
- Notes:
```

Write your decision and any implementation notes there. When you hand the file back, I will read
the blocks and work through the issues marked *implement* one at a time. As `AGENTS.md` requires,
each engine change gets a real-data regression, which I report to you before committing. Issues
marked *discuss first* get a proposal before any code.

Status line fields:

- **Priority:** High, Medium or Low, for the SpacePixels workflow.
- **Evidence:**
  - *Probe:* reproduced with a small synthetic program that calls the real library classes. The
    probes are listed in [Evidence](#evidence).
  - *Source:* found by reading the code, not reproduced.
  - *Calculation:* worked out on paper.
  - *Doc:* the documentation disagrees with the code.

  None of the probes use real observing data, so how often each issue occurs on real datasets is
  still to be measured.
- **Import rules:** what the SpacePixels import rules change for this issue:
  - *No effect:* the issue is unchanged.
  - *Conditional:* it occurs only for inputs the rules still allow.
  - *Excluded:* SpacePixels can no longer trigger it.
- **Where:** the main code locations, as of commit `2f58ccf`.
- **Was:** the issue's number in the first version of this review (CR-n) or in
  `Tracklinker-ASSESSMENT.md` (TL-n).

## The SpacePixels import rules

SpacePixels refuses to import a folder unless all of the following hold. The FITS checks have
existed since 2026-10-08 (`FitsSequenceValidator`). The XISF checks and the `BZERO`/`BSCALE`
comparison were added on 2026-10-17 and are not committed yet.

| Rule | FITS | XISF |
|---|---|---|
| Same file type | one extension for every file, so FITS and XISF can't be mixed (`.fit` and `.fits` also count as different) | same |
| Same resolution | same `NAXIS1`/`NAXIS2` | same geometry width and height |
| Same colour layout | same axes, so mono and colour can't be mixed | same channel count and colour space |
| Same sample type | same `BITPIX`, `BZERO`, `BSCALE` and compression | same `sampleFormat` |
| Timestamps | every file has a usable observation time, or none has; an unparseable date counts as none | same, from the XISF FITS keywords |

- All three import paths apply these checks: the GUI import, the batch API with input preparation,
  and the batch API without it.
- They run before any conversion, so converting to 16-bit mono can't hide a mixed sequence.
- Timed sequences are sorted by time, untimed ones by file name.
- All 20 reference dataset folders pass the new checks.
- **Unsigned 16-bit only (2026-10-17, not committed yet).** What reaches the engine is always 16-bit
  stored as unsigned (`BZERO` 32768, `BSCALE` 1), which is what the engine's `+ 32768` reading
  assumes. The GUI offers to convert signed or scaled 16-bit frames (as it does 32-bit ones), the
  batch command refuses them, and input preparation converts them. Pixels below zero get the same
  offset in every frame (recommended, recorded as `SPOFFSET`) or are set to 0, as the user chooses;
  registration padding stays at 0 either way. Before this, a `BZERO` 0 sequence was read 32768 ADU
  too high: on Africano the drift padding went unseen (13 single-frame anomalies instead of 2, false
  suspected streak, slow movers and local rescue at the top edge), and on m5-reg every star looked
  saturated to Night & Equipment.

**What the rules don't guarantee.** This matters for several issues below:

- **Exposure metadata.** `EXPTIME`/`EXPOSURE` may be missing, in which case the exposure duration
  is -1.
- **Equal exposure lengths.** A session can mix exposure lengths.
- **Regular cadence.** Pauses, meridian flips and dropped frames create uneven gaps.
- **At least three frames after quality control.** Quality control can reduce the session below
  three frames, and nothing checks for it.
- **Calibration, pedestal and registration quality.**
- **Engine-side checks.** JTransient itself checks none of the rules. Other users of the library
  are not protected (see IN-2).

## Overall assessment

The design is sound. A median-stack star veto, per-frame detections, linkers from strict to loose,
then rescue passes for what is left is the right structure for this problem. The photometry is
careful and conservative.

The import rules remove three of the old TrackLinker findings for SpacePixels (X-1 to X-3).
Everything else is unchanged by them, apart from three issues that only occur for inputs the rules
still allow (ST-1, PT-9, IN-3). PT-9 was previously excluded because equal exposure lengths were
assumed, but the rules don't enforce that.

## All issues at a glance

| ID | Issue | Priority | Import rules | Was |
|---|---|---|---|---|
| [IN-1](#in-1) | Manual Transient Inspection changes the live settings | High | No effect | CR-1 |
| [IN-2](#in-2) | The engine doesn't check its own input rules | Medium | Excluded for SpacePixels; open for other callers | new |
| [IN-3](#in-3) | Fewer than three frames discards every result | Low | Conditional | TL-10 |
| [IN-4](#in-4) | A point cap below three defeats the three-point minimum | Low | No effect | TL extra |
| [EX-1](#ex-1) | Background estimate breaks above about 11 % padding | High | No effect | CR-2 |
| [EX-2](#ex-2) | Void test misfires when the sky is close to zero | High | No effect | CR-3 |
| [EX-3](#ex-3) | Extraction FWHM reads about √2 too large | Low | No effect | CR-12 |
| [QC-1](#qc-1) | Star count has no floor in session rejection | Medium | No effect | CR-6 |
| [ST-1](#st-1) | Streak times dropped when the exposure length is unknown | Low | Conditional | TL-3 |
| [ST-2](#st-2) | Streak motion compared in pixel² instead of pixels | Medium | No effect | TL-4 |
| [ST-3](#st-3) | A streak that doesn't move passes the motion check | Medium | No effect | TL-5 |
| [ST-4](#st-4) | Streak chains have no common-line check and no distance limit | Medium | No effect | TL-8 |
| [PT-1](#pt-1) | Rhythm check ignores real frame gaps | Medium | No effect | TL-6 |
| [PT-2](#pt-2) | Stationary detections can extend a time-based track | Medium | No effect | TL-7.1 |
| [PT-3](#pt-3) | Time-based tracks can bend without a final straightness check | Medium | No effect | TL-7.2 |
| [PT-4](#pt-4) | Geometric linker uses a fixed 2° direction tolerance | Low | No effect | CR-12 |
| [PT-5](#pt-5) | `maxStarJitter` does too many jobs | Medium | No effect | CR-10 |
| [PT-6](#pt-6) | Greedy continuation and assignment | Low | No effect | TL extra |
| [PT-7](#pt-7) | Linker work grows very fast with leftover detections | Low | No effect | TL extra |
| [PT-8](#pt-8) | Exposure motion limit uses the footprint area | Low | No effect | TL extra |
| [PT-9](#pt-9) | Start-of-exposure times with mixed exposure lengths | Low | Conditional (was excluded) | TL extra |
| [AN-1](#an-1) | Suspected streaks form from chance alignments | High | No effect | CR-5 |
| [AN-2](#an-2) | Suspected streak groups lose their seed line | Low | No effect | TL-9 |
| [AN-3](#an-3) | Suspected streak grouping allocates by largest frame index | Low | No effect | TL extra |
| [RS-1](#rs-1) | Two stationary leftovers count as common motion | High | No effect | CR-4 |
| [RS-2](#rs-2) | Residual analysis mixes two frame-numbering units | Medium | No effect | CR-7 |
| [RS-3](#rs-3) | Two-point LOCAL_REPEAT has no check against chance | Medium | No effect | CR-8 |
| [SM-1](#sm-1) | Slow movers aren't checked against the drift path | Medium | No effect | CR-9 |
| [SM-2](#sm-2) | Sigma on the maximum stack is on a different scale | Medium | No effect | CR-9 |
| [PH-1](#ph-1) | Slow-mover candidates remove nearby stars from photometry | Medium | No effect | CR-9 |
| [AT-1](#at-1) | Tuner rates use the full crop area | Medium | No effect | CR-11 |
| [AT-2](#at-2) | Tuner injections near crop edges can't be recovered | Low | No effect | CR-11 |
| [AT-3](#at-3) | Negative image matches noise only under equal seeing | Low | No effect | CR-11 |
| [AT-4](#at-4) | Tuner budget counts detections, not what users see | Low | No effect | CR-11 |
| [AT-5](#at-5) | Legacy tuner differs from the pipeline | Low | No effect | CR-12 |
| [XC-1](#xc-1) | Significance numbers assume uncorrelated noise | Low | No effect | CR-12 |
| [XC-2](#xc-2) | No common estimate of chance alignments (design) | Design | No effect | big picture |
| [XC-3](#xc-3) | One global background per frame (design) | Design | No effect | big picture |
| [DOC-1](#doc-1) | Documentation that disagrees with the code | Medium | No effect | CR docs |
| [HY-1](#hy-1) | Dead code and leftover output | Low | No effect | CR-12 / docs |
| [X-1](#x-1) | One timestamp disables the untimed fallback | — | Excluded | TL-1 |
| [X-2](#x-2) | The "no timestamp" value used as a real time | — | Excluded | TL-2 |
| [X-3](#x-3) | Direct callers leave timestamps at 0 | — | Excluded | TL extra |

---

## Input and API

<a id="in-1"></a>
### IN-1 · Manual Transient Inspection changes the live settings

**Priority:** High · **Evidence:** Probe P3 and Source · **Import rules:** No effect ·
**Where:** `JTransientEngine.java:567`; SpacePixels `MainApplicationPanel.java:302–308`,
`ManualTransientInspectionTask.java:53` · **Was:** CR-1

**What happens.**

- `analyzeDitherAndDrift` writes the drift-based void radius into the caller's config:
  `config.voidProximityRadius = driftAnalysis.recommendedVoidProximityRadius`.
- `runPipeline` and `detectTransients` both reach it through `extractSourcesFromFrames` (`:352`).
- `generateMasterStackWithDetails` copies the config first (`:156`), so the API behaves differently
  depending on the entry point.
- The SpacePixels detection runs pass a copy
  (`DetectionPipelineSupport.createEffectiveDetectionConfig`, `StarMaskStackTask`).
- Manual Transient Inspection passes the live object from the settings panel
  (`DetectionConfigurationPanel.java:706`).
- `voidProximityRadius` is a hidden setting, so the change is invisible.
- `SpacePixelsDetectionProfileIO.write` saves the whole config, so the next Save stores the raised
  value in the profile.

**Example.**

- *Scenario:* a dithered session whose registered frames have a 40–60 px padding band. The user
  opens Manual Transient Inspection to look at a candidate, closes it, then runs Auto-Tune and
  saves the profile.
- *What happens:* the inspection's `detectTransients` raises the void radius from 20 to 70 px in
  the live settings. Probe P3 shows exactly this. Auto-Tune, every later detection run and the
  saved profile all use 70 px.
- *Consequence:*
  - Every detection within 70 px of any edge is dropped. On a 4000 × 3000 frame that is 8 % of the
    image instead of 2 %.
  - An asteroid entering from the edge is found later, or not at all.
  - The raised value travels with the profile to other datasets, including ones without padding.

**Fix direction.** Copy the config at the start of `runPipeline` and `detectTransients`, as
`generateMasterStackWithDetails` already does. A copy in the SpacePixels caller would also fix the
visible symptom, but the engine copy protects every API user. No change in detections is expected
for the normal detection runs, which already pass a copy.

**Your comments:**

- Decision: <!-- implement / discuss first / defer / won't fix -->
- Notes: **Codex assessment:** Confirmed from the call chain; this is the clearest first fix. Clone once at each public processing boundary and use that copy throughout the run, so effective drift settings still reach every stage. Check that inspection leaves the live config and saved profile unchanged and that ordinary detection results remain identical.

<a id="in-2"></a>
### IN-2 · The engine doesn't check its own input rules

**Priority:** Medium · **Evidence:** Source · **Import rules:** Excluded for SpacePixels; open for
other callers · **Where:** `JTransientEngine` entry points; `TrackLinker.java:31` (Javadoc only) ·
**Was:** new, replaces the engine side of TL-1, TL-2 and the direct-call timestamp concern

**What happens.**

- The engine assumes frames of equal size, 16-bit data, and timestamps on all frames or on none.
- Only the `TrackLinker` Javadoc states this, and nothing checks it.
- SpacePixels now checks it at import. Any other program using JTransient is not protected.

**Example.**

- *Scenario:* a script uses JTransient directly on a folder where three frames lost `DATE-OBS`
  during a conversion. Or its own extractor objects keep the Java default timestamp 0.
- *What happens:* the TrackLinker behaviours in X-1 to X-3 return:
  - untimed movers lose the geometric fallback;
  - -1 or 0 is used as a real capture time;
  - all time differences become zero.
  Frames of different sizes would fail somewhere in stacking or masking with an index error,
  rather than with a clear message.
- *Consequence:* missed or wrong tracks with no error message, in a library that is published for
  other users.

**Fix direction.** Check the rules once, at the public entry points (`runPipeline`,
`detectTransients`, `generateMasterStack*`, `TrackLinker.findMovingObjects`). Throw
`IllegalArgumentException` with a clear message for:

- mixed frame sizes;
- mixed timestamp availability;
- timestamps of 0 where -1 is meant.

This costs almost nothing and doesn't change results for SpacePixels.

**Your comments:**

- Decision: <!-- implement / discuss first / defer / won't fix -->
- Notes: **Codex assessment:** Valid library-boundary issue despite SpacePixels validation. Validate nonempty rectangular dimensions, supplied master dimensions, configuration invariants and uniform timestamp availability. Define the missing-time contract explicitly: zero is also the Unix epoch, so initializing omitted metadata to -1 is safer than treating every zero as malformed. No new 16-bit check is needed for an already typed short[][] payload.

<a id="in-3"></a>
### IN-3 · Fewer than three frames discards every result

**Priority:** Low · **Evidence:** Probe (TL review) and Source · **Import rules:** Conditional:
import doesn't guarantee three frames after quality control · **Where:** `TrackLinker.java:489`;
`JTransientEngine` has no minimum after rejection · **Was:** TL-10

**What happens.**

- `findMovingObjects` returns an empty result as soon as it gets fewer than three frames.
- That result has no tracks, no anomalies, no single-frame streaks and no per-frame transients.
- Only point tracks actually need three frames. Filtering, single-streak promotion and anomaly
  rescue don't.

**Example.**

- *Scenario:* a 6-frame session on a night with passing clouds. Quality control at Strict rejects
  4 frames. One of the 2 remaining frames contains a bright satellite trail.
- *What happens:* the TL review reproduced this with two frames and one strong streak
  (peak sigma 12). The result was completely empty, returned before any filtering.
- *Consequence:* the report shows no detections, and nothing explains that too few frames
  survived. The trail, which needs only one frame, is lost.

**Fix direction.** Apply the three-frame minimum to point-track discovery only, and keep the other
categories. Alternatively, have the engine report clearly that too few frames survived quality
control, so SpacePixels can show it.

**Your comments:**

- Decision: <!-- implement / discuss first / defer / won't fix -->
- Notes: **Codex assessment:** The early return does discard every tracker output, although separate slow-mover and photometry stages can still produce pipeline results. Preserve valid single-frame categories and expose an explicit insufficient-frames diagnostic. A one/two-frame fixture should cover strong streaks, anomalies and the absence of confirmed point tracks before changing this behavior.

<a id="in-4"></a>
### IN-4 · A point cap below three defeats the three-point minimum

**Priority:** Low · **Evidence:** Source · **Import rules:** No effect · **Where:**
`TrackLinker.java:510–513` · **Was:** TL "configuration invariants"

**What happens.** The required point count is `max(3, ceil(frames / trackMinFrameRatio))`, then
capped by `absoluteMaxPointsRequired`. A cap below 3 lowers the minimum below three.

**Example.**

- *Scenario:* a saved profile sets `absoluteMaxPointsRequired = 2` during an experiment with sparse
  detections.
- *What happens:* any two detections that satisfy the speed and direction gates form a confirmed
  point track.
- *Consequence:* many false tracks from pairs of noise detections. The default cap of 5 is
  unaffected.

**Fix direction.** Clamp or reject unsupported values when the config is loaded or used, for
example a cap of at least 3, and ratios greater than 0.

**Your comments:**

- Decision: <!-- implement / discuss first / defer / won't fix -->
- Notes: **Codex assessment:** Confirmed: the cap is applied after the three-point minimum, and the time-based branch can accept its two-point seed. Prefer a clear validation error for caps below three and nonpositive/nonfinite ratios over silently accepting an invalid profile. The default cap is unaffected.

---

## Source extraction

<a id="ex-1"></a>
### EX-1 · Background estimate breaks above about 11 % padding

**Priority:** High · **Evidence:** Probe P1 and Source · **Import rules:** No effect (padding comes
from registration) · **Where:** `SourceExtractor.calculateBackgroundSigmaClipped`
(`SourceExtractor.java:342–394`) · **Was:** CR-2

**What happens.**

- Every pixel goes into the background histogram, including registration padding at the floor
  value.
- With 3σ clipping, once the padding is more than about 1/3² ≈ 11 % of the frame, the zeros widen σ
  so much that they are never clipped.
- The result is a cliff, not a gradual loss. Probe P1 (sky 1000, noise 20, 300 stars):

  | Padding | Measured σ | Objects found at 5σ |
  |---|---|---|
  | 0 % | 20.2 | 283 |
  | 10 % | 21.1 | 280 |
  | 12 % | 349 | 135 |
  | 15 % | 389 | 108 |
  | 25 % | 499 | 57 |

- `FrameDriftAnalyzer` accepts frames with up to 50 % padding.
- The same background drives the thresholds, `peakSigma`, flux and the void test (EX-2).

**Example.**

- *Scenario:* a session where the field drifts steadily during the night. After registration, the
  last frames have a 15 % padding band on one side.
- *What happens:* in those frames σ jumps from about 20 to about 390. The detection threshold rises
  about 20-fold, and only a third of the stars are found.
- *Consequence:*
  - The session evaluator rejects these frames for a low star count. The report gives that as the
    reason, which hides the real cause.
  - Frames kept anyway contribute almost nothing.
  - A mover's track loses its last frames, exactly where the drift was largest.

**Fix direction.** Leave floor-valued pixels out of the histogram, using the same padding
definition as `FrameDriftAnalyzer` (value ≤ `Short.MIN_VALUE + 8`). Frames without padding are
unchanged.

**Your comments:**

- Decision: <!-- implement / discuss first / defer / won't fix -->
- Notes: **Codex assessment:** Confirmed contamination of the background histogram; high priority. The approximate 11% transition follows the median-centred clipping model and is not universal across sky levels or clipping settings. Reuse a consistent padding definition, guard empty samples, and check genuinely clipped low-background pixels before claiming all unpadded frames are unchanged.

<a id="ex-2"></a>
### EX-2 · Void test misfires when the sky is close to zero

**Priority:** High · **Evidence:** Probe P2 (`SourceExtractor`); Source (the other two places) ·
**Import rules:** No effect · **Where:** `SourceExtractor.java:208, 285–312`;
`ApertureMeasurer.java:209`; `SlowMoverFrameEvidence.isMostlyVoid` (`:202`) · **Was:** CR-3

**What happens.**

- A detection is dropped when any one of 8 single-pixel probes, on a square of radius
  `voidProximityRadius`, is ≤ 0.5 × the sky median. A probe outside the image also counts.
- When the sky is only a few σ above zero, ordinary noise pixels qualify.
- Probe P2 (300 isolated stars, noise 20 ADU, no padding at all):

  | Sky level | Sky in σ above zero | Stars kept |
  |---|---|---|
  | 1000 ADU | 50σ | 300 / 300 |
  | 200 ADU | 10σ | 300 / 300 |
  | 100 ADU | 5σ | 277 / 300 |
  | 60 ADU | 3σ | 168 / 300 |

- The same rule appears in two more places:
  - `ApertureMeasurer` flags a star `EDGE_OR_VOID` when any pixel of its main aperture is below half
    the frame background.
  - `SlowMoverFrameEvidence` uses the median the same way.
- There are two padding definitions:
  - `FrameDriftAnalyzer`: absolute, ≤ `Short.MIN_VALUE + 8`.
  - The three classes above: a fraction of the median.
- The `voidProximityRadius` Javadoc (`DetectionConfig.java:70–74`) describes a test on streaks
  touching padding "in a significant portion". In fact it applies to every object, and one probe
  is enough to reject it.

**Example.**

- *Scenario:* calibrated narrowband frames without a pedestal. The sky is about 60 ADU with
  20 ADU of noise.
- *What happens:*
  - About 44 % of the stars are dropped, at random, because one probe 20 px away landed on a low
    noise pixel.
  - In photometry, most faint stars get `EDGE_OR_VOID`, and slow-mover evidence marks frames as
    void.
- *Consequence:*
  - Fewer master stars make a weaker veto mask, which lets more leaking stars through as false
    transients.
  - Faint mover detections disappear in random frames.
  - Variable-star analysis loses comparison stars.
  - `FieldDepth` in SpacePixels already had to reset the void radius for its cut-outs, which is
    another symptom.

**Fix direction.** One shared, noise-aware padding test used by all four classes, for example "at
or near the floor value", or "below median − k·σ over a small patch".

**Your comments:**

- Decision: <!-- implement / discuss first / defer / won't fix -->
- Notes: **Codex assessment:** Confirmed extraction/aperture concern; use spatial and noise evidence rather than a single faint pixel to identify padding. The slow-mover check requires at least half the footprint to be low, so the quoted star-loss rate does not establish the same loss there. FieldDepth's cut-out workaround addresses edge geometry, rather than independently proving this low-sky failure.

<a id="ex-3"></a>
### EX-3 · Extraction FWHM reads about √2 too large

**Priority:** Low · **Evidence:** Source · **Import rules:** No effect · **Where:**
`SourceExtractor.java:470` · **Was:** CR-12

**What happens.**

- The extractor computes `fwhm = 2.355 * sqrt(λ1 + λ2)`. For a round star that is √2 × the true
  value.
- `ApertureMeasurer` notes this at `:22`.
- Session rejection, `minFwhmEnvelope` and `maxFwhmRatio` all use the inflated value.

**Example.**

- *Scenario:* a user compares the FWHM in the SpacePixels report with PixInsight's measurement of
  the same frames.
- *What happens:* SpacePixels says 4.2 px where PixInsight says 3.0 px.
- *Consequence:*
  - Confusion, and the thresholds are tuned in this inflated unit.
  - If someone later "fixes" the formula, frame rejection and the FWHM gates silently shift.

**Fix direction.** Document the unit, or rename the field, rather than changing the formula. If the
formula is changed, scale the dependent thresholds by 1/√2 in the same change.

**Your comments:**

- Decision: <!-- implement / discuss first / defer / won't fix -->
- Notes: **Codex assessment:** The Gaussian normalization issue is real, with additional bias from threshold-truncated footprints. Document the legacy extraction metric first; photometry and Night & Equipment have separate star-size measurements, so the example does not apply to every report FWHM. A formula change needs an audit of all consumers and profile/threshold compatibility, not just rescaling one setting.

---

## Frame quality control

<a id="qc-1"></a>
### QC-1 · Star count has no floor in session rejection

**Priority:** Medium · **Evidence:** Source · **Import rules:** No effect · **Where:**
`SessionEvaluator.java:100` (star count) versus `:110–134` (floors for the others) · **Was:** CR-6

**What happens.**

- Each limit is the median ± k × a MAD-based σ.
- FWHM, eccentricity and background have absolute floors; the background floor was added on
  2026-10-10.
- Star count has none. In a very stable session the MAD is tiny, so the limit sits just below the
  median.

**Example.**

- *Scenario:* a stable 100-frame session with about 1500 ± 10 stars per frame. Strictness is
  Strict (1.5σ).
- *What happens:* the limit becomes 1485 stars. A frame with 1480 stars, under slight haze, is
  rejected.
- *Consequence:*
  - Good frames are lost.
  - A mover's track gets a gap. In untimed data this interacts with PT-1, which can then reject
    the track.

**Fix direction.** A relative floor, as for the background. For example, never reject a frame for
being less than 10 % below the median star count.

**Your comments:**

- Decision: <!-- implement / discuss first / defer / won't fix -->
- Notes: **Codex assessment:** Source confirms the missing floor, but a 10% allowance is a policy choice rather than an established correct threshold. Measure rejection changes on stable, cloudy and sparse sessions before choosing it, and preserve a meaningful distinction between Strict and Lenient. Fix EX-1 first so distorted star counts do not drive that calibration.

---

## Track linking: streaks

<a id="st-1"></a>
### ST-1 · Streak times dropped when the exposure length is unknown

**Priority:** Low · **Evidence:** Probe (TL review) and Source · **Import rules:** Conditional:
timed frames without `EXPTIME`/`EXPOSURE` are still allowed · **Where:**
`TrackLinker.buildStreakFrameSamples` (`:1557`) · **Was:** TL-3

**What happens.**

- The streak frame samples keep a timestamp only when `exposureDuration > 0`.
- Without an exposure length, the streak linker forgets the capture times and compares only
  spatial jumps.
- The exposure length isn't otherwise used for streak timing.

**Example.**

- *Scenario:* timed frames from software that doesn't write `EXPTIME`, with a pause in the session
  (a meridian flip, for example). Three similarly oriented trails at X = 40, 60 and 80 are captured
  at 0 s, 1 s and 11 s; the third could be a different satellite.
- *What happens:* the steps are 20 px in 1 s, then 20 px in 10 s, a tenfold change of speed. With
  the times dropped, the equal 20 px steps pass the rhythm check, and the three trails are linked.
- *Consequence:* two different objects are reported as one track with a wrong speed.

**Fix direction.** Change the guard from `point.timestamp != -1L && point.exposureDuration > 0L`
to `point.timestamp != -1L`. Alternatively, also require `EXPTIME` at import, which is your
decision.

**Your comments:**

- Decision: <!-- implement / discuss first / defer / won't fix -->
- Notes: **Codex assessment:** Confirmed: the guard discards usable capture times solely because exposure duration is unavailable. Retain those times independently of duration, keep exposure-dependent checks conditional, and test irregular cadence with unknown exposure. Requiring extra import metadata would unnecessarily exclude otherwise usable observations.

<a id="st-2"></a>
### ST-2 · Streak motion compared in pixel² instead of pixels

**Priority:** Medium · **Evidence:** Probe (TL review) and Source · **Import rules:** No effect ·
**Where:** `TrackLinker.projectionAlongLine` (`:2210–2217`), used at `:1294`, `:1402`, `:1588`,
`:1601` · **Was:** TL-4

**What happens.**

- `projectionAlongLine` returns an unnormalized dot product, in pixel² units.
- `minimumProjectedStep` is in pixels.
- The longer the reference line, the larger the apparent motion.
- Final consolidation also compares only frame-mean centres. So it can undo the chronological
  check that initial chaining made.

**Example.**

- *Scenario:* a moving object's trail is extracted as two pieces in frame 0, centred at X = 40 and
  X = 240. In frame 1, an unrelated elongated source appears at X = 140.1.
- *What happens:*
  - Initial chaining correctly rejects the new source, because it would reverse direction.
  - Final consolidation then compares the frame-0 mean (X = 140) with X = 140.1. The real 0.1 px
    step, multiplied by the 200 px axis, becomes 20, which passes the 0.75 px minimum.
  - The two events are merged.
- *Consequence:* one track mixing two objects, with a meaningless motion. The second object
  disappears as a separate detection.

**Fix direction.**

- Divide by `hypot(dx, dy)` for physical motion checks, and guard against zero length.
- Make consolidation check forward continuation against the per-frame fragment ranges, not only
  the means. A detection at X = 150 would still lie inside the earlier 40–240 span.
- Add regressions for forward motion in both directions and for fragmented streaks first.

**Your comments:**

- Decision: <!-- implement / discuss first / defer / won't fix -->
- Notes: **Codex assessment:** Confirmed dimensional mismatch where projection differences are compared with pixel thresholds. Normalize the axis for physical-distance comparisons and explicitly handle degenerate axes; ordering alone does not need normalization. Treat fragment-range continuation as a separate behavioral change, with forward/reverse and same-frame-fragment fixtures, so its impact can be distinguished from the unit correction.

<a id="st-3"></a>
### ST-3 · A streak that doesn't move passes the motion check

**Priority:** Medium · **Evidence:** Probe (TL review) and Source · **Import rules:** No effect ·
**Where:** `TrackLinker.passesStreakTimeConsistency` (`:1666–1678`),
`determineStreakLineEndpoints` (`:1761`) · **Was:** TL-5

**What happens.** When no two streak centres are at least 1 px apart, there are no line endpoints,
and `passesStreakTimeConsistency` returns `true`. Being unable to measure motion counts as
passing.

**Example.**

- *Scenario:* an intermittent sensor artifact makes a short horizontal mark at the same position in
  3 frames. It is missing in most frames, so the median master doesn't mask it.
- *What happens:* the TL review reproduced this with three horizontal streaks at (40, 40). They
  became one confirmed streak track.
- *Consequence:* a "moving object" with zero motion appears in the report.

**Fix direction.** Insufficient displacement across frames must fail the motion check. Keep
same-frame fragments mergeable separately, and judge a single strong streak through the existing
single-streak path.

**Your comments:**

- Decision: <!-- implement / discuss first / defer / won't fix -->
- Notes: **Codex assessment:** Confirmed fail-open branch for a degenerate axis. Multi-frame motion needs positive evidence, while same-frame fragments and individually significant streaks should remain representable through their existing paths. Test those cases separately so rejecting a stationary track does not silently remove its constituent detections.

<a id="st-4"></a>
### ST-4 · Streak chains have no common-line check and no distance limit

**Priority:** Medium · **Evidence:** Probe (TL review) and Source · **Import rules:** No effect ·
**Where:** streak chaining, `TrackLinker.java:336–424` · **Was:** TL-8 (the missing distance limit
is from CR)

**What happens.**

- Initial chaining checks each step's direction against a 2° tolerance. It never checks that the
  whole chain lies on one line.
- Over long jumps, 2° allows a large sideways error.
- There is also no limit on the distance between chained streaks.

**Example.**

- *Scenario:* two satellites cross the field on nearly parallel paths in successive frames:
  (40, 40) and (540, 40) for one, then a similar trail at (1040, 56).
- *What happens:* the second step is 500 px along and 16 px sideways, an angle of 1.83°, so it is
  accepted. The middle point is 8 px from the line through the ends; consolidation alone would
  have rejected anything beyond 6 px.
- *Consequence:* two satellites reported as one track, and the second object lost.

**Fix direction.** One whole-track check for streak tracks, initial and consolidated: a line fit
with a pixel tolerance, plus a speed-consistent distance limit.

**Your comments:**

- Decision: <!-- implement / discuss first / defer / won't fix -->
- Notes: **Codex assessment:** The missing whole-track geometry check is real. Prefer a residual limit tied to positional uncertainty and a time-aware continuation test; a fixed maximum jump would also reject genuine fast satellites. Keep initial linking and consolidation consistent, and measure how a straight-line assumption behaves on long or visibly curved trails.

---

## Track linking: point movers

<a id="pt-1"></a>
### PT-1 · Rhythm check ignores real frame gaps

**Priority:** Medium · **Evidence:** Probe (TL review) and Source · **Import rules:** No effect.
It applies to untimed datasets, and to timed datasets whenever the geometric pass runs; the shipped
SpacePixels profile turns it on (`enableGeometricTrackLinking: true`) · **Where:**
`TrackLinker.hasSteadyRhythm` (`:2253`) · **Was:** TL-6

**What happens.**

- The check compares raw jump lengths with integer multiples of their median.
- It ignores `sourceFrameIndex`, and so guesses missed frames from the distances.

**Examples.**

- *Missed mover:*
  - Scenario: an asteroid moving 20 px per frame is not detected in frame 2, because of a cloud, a
    masked star, or a frame rejected by quality control.
  - What happens: the detections at frames 0, 1 and 3 (X = 40, 60, 100) give jumps of 20 and 40 px.
    The median is 40, so the 20 px jump misses by 20 px, more than the allowed 8. Consistency is
    0.5, below 0.7.
  - Consequence: the track is rejected and the asteroid is missed.
- *Wrong blob accepted:*
  - Scenario: in frames 0–3, detections at X = 40, 60, 80 and 120. The real mover would be at
    X = 100 in the last frame, but a similar blob at X = 120 is chosen.
  - What happens: the 40 px step is accepted as a skipped frame, although no frame was skipped.
  - Consequence: a wrong point is added to the track.

**Fix direction.** Use the real frame-index differences to get the displacement per frame, and
don't invent missing frames from distance ratios.

**Your comments:**

- Decision: <!-- implement / discuss first / defer / won't fix -->
- Notes: **Codex assessment:** Confirmed: distance multiples substitute for known frame gaps. Normalize by original frame-index differences for the untimed regular-cadence model, and use elapsed time when available; unequal capture intervals remain possible even without rejected frames. Test both a genuinely skipped observation and an incorrect doubled step in consecutive frames.

<a id="pt-2"></a>
### PT-2 · Stationary detections can extend a time-based track

**Priority:** Medium · **Evidence:** Probe (TL review) and Source · **Import rules:** No effect
(timed datasets) · **Where:** time-based linker, `TrackLinker.java:556–749` · **Was:** TL-7.1

**What happens.** A small first step starts a motion hypothesis. The speed tolerance plus the
jitter allowance then admits zero-length steps. Unlike the geometric linker, the time-based linker
has no pruning of short steps and no final comparison with a stationary model.

**Example.**

- *Scenario:* a faint residual of an imperfectly masked star is detected intermittently. Its first
  centroid is 2 px off because of noise: X = 40 in frame 0, nothing in frame 1, then X = 42 in
  frames 2–5. Frames are 1 s apart.
- *What happens:* the baseline of 2 px in 2 s starts the hypothesis. The jitter allowance of
  1.5 px/s accepts the zero-motion steps. A five-point track is confirmed, four points of it
  stationary.
- *Consequence:* a false slow mover in the report.

**Fix direction.** At acceptance, test whether the whole track gives significant evidence of
motion over a stationary model, using the total motion and the fit uncertainty. Don't require
every single step to exceed the jitter, because that would reject real slow movers.

**Your comments:**

- Decision: <!-- implement / discuss first / defer / won't fix -->
- Notes: **Codex assessment:** The acceptance path lacks a whole-track motion-significance gate, so the concern is well founded. Compare moving and stationary fits using an empirical positional-error floor, rather than imposing a minimum on every step. Validate on known slow movers and noisy stationary residuals to avoid trading these false tracks for lost slow objects.

<a id="pt-3"></a>
### PT-3 · Time-based tracks can bend without a final straightness check

**Priority:** Medium · **Evidence:** Probe (TL review) and Source · **Import rules:** No effect
(timed datasets) · **Where:** time-based linker, `TrackLinker.java:556–749` · **Was:** TL-7.2

**What happens.**

- Each new point is checked against the line from the first point to the current anchor. That line
  turns as points are added, and earlier points are never re-checked.
- The score penalizes the final line error, but only for ranking, and length ranks before score.

**Example.**

- *Scenario:* a mover crosses a crowded Milky Way field. Nearby stars pull successive centroids
  slightly to one side. In the TL review, 11 points 10 px apart in X drifted from Y = 40 to
  Y = 78.6.
- *What happens:* every point passes its local check. In the final track, an inner point is 6.3 px
  from the end-to-end line; the tolerance is 3 px.
- *Consequence:* the track contains wrong detections and gives a wrong position and speed, which
  matters for reporting an asteroid.

**Fix direction.** Before acceptance, fit a line and position against time to the whole track, and
reject points or tracks beyond the tolerance.

**Your comments:**

- Decision: <!-- implement / discuss first / defer / won't fix -->
- Notes: **Codex assessment:** Confirmed that earlier points are not revalidated against the final trajectory and the fit score only ranks proposals. A robust whole-track position-versus-time fit is appropriate; prune/refit a supported subset before discarding an entire track. Specify residual tolerances and preserve genuinely slow or mildly nonlinear motion in the validation set.

<a id="pt-4"></a>
### PT-4 · Geometric linker uses a fixed 2° direction tolerance

**Priority:** Low · **Evidence:** Probe P4 · **Import rules:** No effect (untimed datasets, or
timed ones with the geometric pass on) · **Where:** `TrackLinker.java:830`; compare the time-based
linker at `:677` · **Was:** CR-12

**What happens.** The time-based linker widens the angle tolerance for short steps, using
`atan2(maxStarJitter + 1, step)`. The geometric linker keeps a fixed 2°, so centroid noise on short
steps breaks the direction test.

**Example.**

- *Scenario:* an untimed dataset with a slow asteroid at 3 px per frame and 0.25 px of centroid
  noise.
- *What happens:* in Probe P4 it is found in 154 / 200 trials, 195 at 6 px per frame, and at least
  198 at 10 px or more. With timestamps, 200 / 200 at every speed.
- *Consequence:* about one slow asteroid in four is missed in untimed data.

**Fix direction.** Use the same dynamic tolerance as the time-based linker.

**Your comments:**

- Decision: <!-- implement / discuss first / defer / won't fix -->
- Notes: **Codex assessment:** The fixed-angle sensitivity difference is supported by source; the 25% loss is specific to the cited synthetic setup. Relaxing the angle at short baselines can admit many chance links, so do not copy the dynamic formula without a bounded tolerance and whole-track validation. Compare completeness and false tracks together.

<a id="pt-5"></a>
### PT-5 · `maxStarJitter` does too many jobs

**Priority:** Medium · **Evidence:** Source · **Import rules:** No effect · **Where:**
`MasterVetoMask.java:25`, the linkers (for example `TrackLinker.java:607, 644, 677`), the
stationary tests · **Was:** CR-10

**What happens.**

- One setting controls:
  - the veto mask growth;
  - the minimum motion and the speed slack in both linkers;
  - the exposure-motion limit;
  - the dynamic angle tolerance;
  - the step for anti-hijack pruning;
  - the stationary radii.
- `CalibratedAutoTuner` sets it to 2 × the 90th percentile of the master-to-frame star distances,
  to get a good mask.

**Example.**

- *Scenario:* a session with poor registration. The tuner measures a 2 px registration error and
  sets `maxStarJitter = 4`.
- *What happens:*
  - The mask improves.
  - Steps shorter than 4 px now count as stationary. The geometric linker prunes them, and the
    time-based linker needs a longer baseline.
  - A 3 px/frame asteroid can only be seeded from frames further apart.
- *Consequence:* slow movers become harder to link, as a side effect of a better mask.

**Fix direction.** Separate the registration error (for the mask and the stationary tests) from
the minimum mover speed, even if the second defaults to a multiple of the first.

**Your comments:**

- Decision: <!-- implement / discuss first / defer / won't fix -->
- Notes: **Codex assessment:** The coupling is real but partly intentional: registration uncertainty also limits measurable motion. Separate a policy minimum speed from the uncertainty model, rather than simply allowing motion below the measured jitter. Discuss defaults and profile compatibility before adding settings; keep existing hidden settings hidden.

<a id="pt-6"></a>
### PT-6 · Greedy continuation and assignment

**Priority:** Low · **Evidence:** Source · **Import rules:** No effect · **Where:** time-based
proposals and assignment (`TrackLinker.java:556–749`), streak and geometric linkers · **Was:** TL
extra

**What happens.**

- Each seed grows one local continuation per frame.
- Proposals are ranked, but a proposal that shares even one detection with an accepted proposal is
  rejected as a whole.

**Example.**

- *Scenario:* two asteroids pass close to each other in a crowded field. One frame has a blob that
  fits both.
- *What happens:* the first accepted track takes the blob, and the second proposal is rejected
  entirely, even though its other detections still support it.
- *Consequence:* the second asteroid can be lost or fragmented. Other seeds may still recover it,
  so this isn't certain at every crossing.

**Fix direction.** When a proposal conflicts, retry it without the shared detection before
rejecting it. A global assignment step is possible later.

**Your comments:**

- Decision: <!-- implement / discuss first / defer / won't fix -->
- Notes: **Codex assessment:** This is an assignment trade-off, not an unconditional correctness bug. Retrying a conflicting proposal after removing used points is a reasonable limited experiment, provided it is rescored and still passes point-count, motion and whole-track gates. Benchmark crossing cases before considering a substantially more expensive global assignment.

<a id="pt-7"></a>
### PT-7 · Linker work grows very fast with leftover detections

**Priority:** Low · **Evidence:** Source · **Import rules:** No effect · **Where:** time-based seed
loops; same-frame anomaly grouping · **Was:** TL extra

**What happens.** With F frames and D detections per frame, the time-based seed-and-extend loops
can approach O(F³ D³) work, and stored proposals O(F³ D²). Many baselines describe the same mover.

**Example.**

- *Scenario:* a deep, noisy session run at a sensitive threshold, with hundreds of leftovers per
  frame and only a few real movers.
- *What happens:* time and memory grow far faster than the number of tracks found. This was not
  benchmarked.
- *Consequence:* very long runs or memory pressure on large sessions, alongside the 11 GB limit of
  the batch regressions.

**Fix direction.** Spatial lookups for the predicted region, and removing duplicate proposals.

**Your comments:**

- Decision: <!-- implement / discuss first / defer / won't fix -->
- Notes: **Codex assessment:** The loop structure supports a severe worst-case scaling concern, but the quoted complexity is not a measured runtime diagnosis. Profile proposal counts, allocation and extension scans on noisy sessions first. Spatial indexing and deduplication of equivalent proposals are promising, provided deterministic ranking and the accepted detections are preserved.

<a id="pt-8"></a>
### PT-8 · Exposure motion limit uses the footprint area

**Priority:** Low · **Evidence:** Source · **Import rules:** No effect (needs
`strictExposureKinematics` and a known exposure length) · **Where:** `TrackLinker.java:605–607`,
`:643–644` · **Was:** TL extra

**What happens.** With `strictExposureKinematics`, the motion allowed during one exposure is
`sqrt(pixelArea) + maxStarJitter`. That is an area scale, not the measured length of a trail.

**Example.**

- *Scenario:* a fast-ish mover leaves a moderately elongated blob in long exposures, just below the
  streak threshold, so it enters the point linker.
- *What happens:* `sqrt(area)` of a long narrow blob is much shorter than its length, so the
  allowed motion is too small.
- *Consequence:* a genuine mover is rejected by the point linker.

**Fix direction.** Use the measured major axis (or elongation × size) instead of `sqrt(area)`, and
calibrate on movers.

**Your comments:**

- Decision: <!-- implement / discuss first / defer / won't fix -->
- Notes: **Codex assessment:** This is a conservative shape heuristic, not a dimensional error: sqrt(area) is already a length. A measured major extent may better describe an elongated source, but depends on extraction threshold and seeing. Calibrate the replacement on known point/streak boundary cases instead of assuming major extent is the true exposure-motion length.

<a id="pt-9"></a>
### PT-9 · Start-of-exposure times with mixed exposure lengths

**Priority:** Low · **Evidence:** Source · **Import rules:** Conditional. It was excluded in the TL
review on the assumption of equal exposure lengths, which import doesn't enforce · **Where:**
`ImageFrame` timestamps (`DATE-OBS`, usually the exposure start) used directly by the time-based
linker · **Was:** TL extra

**What happens.** A moving object's centroid corresponds roughly to the middle of the exposure. If
timestamps are exposure starts and the exposure lengths differ, the time offset differs from frame
to frame.

**Example.**

- *Scenario:* a session that switches from 60 s to 180 s exposures halfway through.
- *What happens:* the centroids sit 30 s after the start in the first half and 90 s after it in the
  second. Across the switch, the linker's time difference is off by 60 s. For a 200 s interval,
  that is a 30 % speed error for that step.
- *Consequence:* the speed check can reject a real mover at the switch, or the reported speed is
  off.

**Fix direction.** Two options, both your decision:

- require equal `EXPTIME` at import;
- or use `timestamp + exposure / 2` when the exposure length is known.

**Your comments:**

- Decision: <!-- implement / discuss first / defer / won't fix -->
- Notes: **Codex assessment:** Valid for mixed exposure durations; the report's midpoint correction does not fix the engine's linking times. Prefer an explicit start-time versus midpoint API contract and one shared conversion, with a documented fallback for missing duration. Avoid adding half an exposure twice for external callers that already supply midpoints; equal-duration sessions should remain equivalent.

---

## Anomalies and suspected streaks

<a id="an-1"></a>
### AN-1 · Suspected streaks form from chance alignments

**Priority:** High · **Evidence:** Probe P5 and Source · **Import rules:** No effect · **Where:**
`TrackLinker.groupSuspectedStreakTracks` / `buildBestSuspectedStreakTrack` (`:1152–1262`) ·
**Was:** CR-5

**What happens.** Any 3 same-frame anomalies within 6 px of a line form a suspected streak, with no
limit on gaps or span. Probe P5 placed anomalies at random in one 4000 × 3000 frame:

| Anomalies in the frame | Frames with a suspected streak |
|---|---|
| 4 | 11 / 300 (4 %) |
| 6 | 34 / 300 (11 %) |
| 10 | 146 / 300 (49 %) |
| 20 | 300 / 300 (100 %) |

**Example.**

- *Scenario:* a noisy session where each frame has about 10 rescued anomalies: hot pixels, cosmic
  rays and faint residuals.
- *What happens:* about half the frames produce a suspected streak made of unrelated points
  scattered across the frame.
- *Consequence:*
  - The report lists many suspected streaks that are random, and the user has to check each one.
  - A real faint streak gets lost among them.

**Fix direction.** Apply the rules that `FaintStreakFragmentGrouper` already uses: the largest gap
at most 40 % of the span, plus a minimum span. Or require the expected chance count for the frame's
anomaly density to be small (see XC-2).

**Your comments:**

- Decision: <!-- implement / discuss first / defer / won't fix -->
- Notes: **Codex assessment:** The permissive search creates a credible multiple-trials false-alignment problem; the quoted rates are specific to uniform random points. Do not copy the 40% gap rule unchanged: three collinear points necessarily have a largest gap of at least 50% of their span. Choose criteria for three-fragment cases explicitly and validate known faint streaks as well as random fields.

<a id="an-2"></a>
### AN-2 · Suspected streak groups lose their seed line

**Priority:** Low · **Evidence:** Probe (TL review) and Source · **Import rules:** No effect ·
**Where:** `TrackLinker.java:1152–1262`, endpoint ordering · **Was:** TL-9

**What happens.** The group is formed around a seed line, but that line isn't kept. Later ordering
uses the farthest pair, which can be a different line.

**Example.**

- *Scenario:* one frame with five bright fragments in a narrow strip: (40, 40), (50, 40), (20, 46),
  (120, 46) and (70, 34).
- *What happens:* all five are within 6 px of the seed line Y = 40. The exported endpoints define
  Y = 46, and from that line (70, 34) is 12 px away.
- *Consequence:* a viewer, or a later merge, works with different geometry from the geometry that
  justified the group.

**Fix direction.** Keep the accepted line, or fit one canonical line before export, and use it for
ordering, consolidation and display.

**Your comments:**

- Decision: <!-- implement / discuss first / defer / won't fix -->
- Notes: **Codex assessment:** Confirmed loss of the geometry that justified membership. Fit one canonical line, recheck every member against its tolerance, and use that same geometry for ordering, merging and display. Keeping an arbitrary seed line alone would preserve consistency but can retain a poorer fit.

<a id="an-3"></a>
### AN-3 · Suspected streak grouping allocates by largest frame index

**Priority:** Low · **Evidence:** Source · **Import rules:** No effect · **Where:**
`TrackLinker.java:1155–1167` · **Was:** TL extra

**What happens.** One list is created for every integer up to the largest `sourceFrameIndex`,
instead of one per frame present.

**Example.**

- *Scenario:* a program processes 50 frames but keeps their original indices, 100000 to 100049.
- *What happens:* 100050 lists are created.
- *Consequence:* wasted memory. In SpacePixels the indices are at most the session's frame count,
  so this is negligible there.

**Fix direction.** Use a map keyed by frame index.

**Your comments:**

- Decision: <!-- implement / discuss first / defer / won't fix -->
- Notes: **Codex assessment:** Confirmed sparse-index memory issue; low impact for normal SpacePixels sequences. A TreeMap or equivalent ordered sparse grouping avoids allocation by the maximum index while preserving deterministic frame order. Cover widely spaced original indices and the existing handling of unknown/negative indices.

---

## Residual analysis

<a id="rs-1"></a>
### RS-1 · Two stationary leftovers count as common motion

**Priority:** High · **Evidence:** Probe P6 and Source · **Import rules:** No effect · **Where:**
`CommonMotionRejector.agrees` (`:245–272`) · **Was:** CR-4

**What happens.** Two sources agree when their displacements over the same frames match. Two
sources that don't move both have zero displacement, so they always agree. A group needs sources
at least 30 px apart, which unrelated sources easily are.

**Example.**

- *Scenario:* a galaxy field (M31, M101) with a new stationary source in frames 2–6, such as a nova,
  a supernova or a flare. Somewhere else, an unrelated star leaks through the mask in the same
  frames.
- *What happens:* Probe P6, with the two sources 800 px apart: without the filter there are 2 local
  rescue candidates. With it, 1 common-motion group is formed and 0 candidates remain.
- *Consequence:* a genuine stationary transient disappears. That is exactly the kind of event the
  local rescue exists to find.

**Fix direction.** Require the shared displacement to exceed the star jitter before two sources can
agree, or handle zero-motion groups separately.

**Your comments:**

- Decision: <!-- implement / discuss first / defer / won't fix -->
- Notes: **Codex assessment:** Confirmed: matching displacement differences also accepts two zero-motion sources. Require statistically meaningful shared motion or preserve stationary groups for transient assessment. The source is removed from local rescue, although it can still appear in the common-motion report section; describing it as completely absent from every output overstates the scope.

<a id="rs-2"></a>
### RS-2 · Residual analysis mixes two frame-numbering units

**Priority:** Medium · **Evidence:** Source · **Import rules:** No effect · **Where:**
`ResidualTransientAnalyzer.java:226–238, 278–283` (list position) versus `:462–499` (sequence
index) · **Was:** CR-7

**What happens.**

- Chains are built using the position in the frame list, and `maxFrameGap` is checked against it.
- The metrics use `sourceFrameIndex`, which still counts frames rejected by quality control.
- The same mix appears in `filterExcludedDetections` (`:107` versus `:116`). There it is mostly
  harmless, because identity matching runs first and the fallback also checks the file name.

**Example.**

- *Scenario:* frame 5 of 20 is rejected by quality control. A local candidate is detected in frames
  3, 4, 6 and 7.
- *What happens:* the chain is built over 4 consecutive list positions. The metrics see a span of
  5 frames, so coverage is 4 / 5 = 0.8 instead of 1.0.
- *Consequence:* the candidate can fail the coverage or span gates, or be ranked lower, only
  because a frame inside it was rejected.

**Fix direction.** Use one unit: the list position for gaps and coverage, and the sequence index
only for reporting.

**Your comments:**

- Decision: <!-- implement / discuss first / defer / won't fix -->
- Notes: **Codex assessment:** Confirmed inconsistent notions of an available frame. Use retained-frame ordinals for opportunity-based coverage and gap gates, while retaining original indices/timestamps for physical motion fits and provenance. Changing original indices to report-only information would also lose elapsed-gap information; define the intended units for each metric before fixing them.

<a id="rs-3"></a>
### RS-3 · Two-point LOCAL_REPEAT has no check against chance

**Priority:** Medium · **Evidence:** Source and Calculation · **Import rules:** No effect ·
**Where:** `ResidualTransientAnalyzer.java:718` (`minRepeatPoints = 2`) · **Was:** CR-8

**What happens.**

- Two detections within about 3 px in nearby frames make a candidate.
- The signal gate (at least 5 on the larger of peak and integrated sigma) passes almost every
  detection that survived extraction.
- Chance pairs grow with the square of the leftover density, and nothing estimates how many to
  expect.
- The pair search is O(N²).

**Example.**

- *Scenario:* a noisy 50-frame session with 1000 leftovers per 4000 × 3000 frame.
- *What happens:* expected chance pairs per pair of adjacent frames are
  1000 × 1000 × π × 3² / 12 000 000 ≈ 2.4, so about 115 over the session. This is a calculation,
  not a probe. With 200 leftovers it is about 0.1 per frame pair, about 5 in total.
- *Consequence:* the number of false LOCAL_REPEAT candidates depends strongly on how noisy the
  dataset is, and can swamp the report on bad data.

**Fix direction.** Compare the number of pairs found with the number expected from the leftover
density (see XC-2), or require a third point unless the pair is very significant.

**Your comments:**

- Decision: <!-- implement / discuss first / defer / won't fix -->
- Notes: **Codex assessment:** The density dependence is real, but the calculation estimates raw chance pairs under uniform independence, not the final accepted-candidate count after other gates. Measure the residual population and existing candidate filters first. Requiring three observations would sacrifice genuine brief events, so prefer a calibrated score/diagnostic before a blanket minimum increase.

---

## Slow movers

<a id="sm-1"></a>
### SM-1 · Slow movers aren't checked against the drift path

**Priority:** Medium · **Evidence:** Source · **Import rules:** No effect · **Where:**
`SlowMoverAnalyzer`, `SlowMoverFrameEvidence` · **Was:** CR-9

**What happens.** In uncalibrated, drifting data, hot pixels move along the registration drift on
the registered frames. In the maximum stack each one leaves a short smear that is:

- elongated;
- missing from the median;
- supported in many frames.

`driftPoints` are available, but nothing compares a candidate's per-frame centroids with them.

**Example.**

- *Scenario:* an uncalibrated session that drifts 30 px during the night, like
  whynot-nocalibration-wrongdrift.
- *What happens:* every hot pixel becomes a 30 px dotted line in the maximum stack, and some pass
  all the slow-mover gates. This was found by reading the code, not observed.
- *Consequence:* false slow movers, and each one also removes nearby stars from photometry (PH-1).

**Fix direction.** Reject a candidate whose per-frame positions follow the inverse of the drift
path within the jitter.

**Your comments:**

- Decision: <!-- implement / discuss first / defer / won't fix -->
- Notes: **Codex assessment:** A plausible artifact mechanism, not a demonstrated failure on the named dataset. First retain the per-frame centroids and original frame mapping; match coordinate/sign conventions to the tuner's existing sensor-recurrence logic. Drift agreement is evidence of an artifact, not proof, so initially flag it rather than vetoing real movers with coincidentally similar motion.

<a id="sm-2"></a>
### SM-2 · Sigma on the maximum stack is on a different scale

**Priority:** Medium · **Evidence:** Calculation · **Import rules:** No effect · **Where:**
`SlowMoverAnalyzer.analyze`, which extracts on the per-pixel maximum with the global background ·
**Was:** CR-9

**What happens.** Each pixel of the maximum stack is the largest of N frame values. Its noise is
skewed, with a heavy upper tail, and its measured spread is compressed. For 20 frames:

- the median sits about 1.8 single-frame σ above the sky;
- the spread is about 0.52σ.

A 3.5σ threshold on the maximum stack therefore sits at about 3.7σ of a single frame. Any of the 20
frames can cross it, so about 10 times more noise pixels seed candidates than a 3.5σ threshold on
Gaussian noise would give. This assumes a plain maximum of independent Gaussian frames.

**Example.**

- *Scenario:* the open idea of tying the slow-mover sigma to the tuned detection sigma. The tuner
  picks 3.5σ for single frames, and the slow-mover sigma is set to 3.5 as well.
- *What happens:* on the maximum stack, 3.5 means something else, and the number of noise seeds
  grows with the number of frames.
- *Consequence:* more raw slow-mover candidates than expected on long sessions, and a link that
  isn't like-for-like.

**Fix direction.** If the two are linked, convert between the scales using the frame count. Or
measure the maximum stack's noise empirically, for example from the negative-image approach.

**Your comments:**

- Decision: <!-- implement / discuss first / defer / won't fix -->
- Notes: **Codex assessment:** The scale mismatch is real under the stated maximum-of-Gaussians model; the numerical false-seed ratio is not a calibration of the actual clipped background estimator or registered data. Keep the sigmas independently tuned until empirical null-stack tests cover frame count, varying noise and correlations. A frame-count-only conversion is insufficient in general.

---

## Variable-star photometry

<a id="ph-1"></a>
### PH-1 · Slow-mover candidates remove nearby stars from photometry

**Priority:** Medium · **Evidence:** Source · **Import rules:** No effect · **Where:**
`VariableStarAnalyzer.java:1004–1021` (comment at `:67`) · **Was:** CR-9

**What happens.**

- Each slow-mover candidate flags every star within 5 FWHM as CROSSING in every frame.
- Tracks and frame detections also feed the crossing flags.
- So photometry depends on the detection profile, although the comment at `:67` says it is
  independent.

**Example.**

- *Scenario:* in an M31 field, a false slow-mover candidate (see SM-1 or SM-2) lies within 5 FWHM
  of a catalogued variable.
- *What happens:* that star is flagged CROSSING in all frames and drops out of the variable-star
  analysis.
- *Consequence:* a missed variable, and variable-star results that change when detection settings
  change.

**Fix direction.** Flag only the frames in which the candidate is actually near the star, using
its per-frame positions. Or let photometry use its own conservative crossing test, independent of
the detection profile.

**Your comments:**

- Decision: <!-- implement / discuss first / defer / won't fix -->
- Notes: **Codex assessment:** Confirmed conservative masking of the whole candidate footprint in every frame; a false candidate can therefore remove useful photometry. Per-frame positions/support are the right prerequisite for narrowing it, with conservative treatment of genuinely unobserved intervals. Compare affected stars and contamination rates before relaxing these flags, and correct the independence claim in the documentation.

The other photometry findings stay in `VariableStar-ASSESSMENT.md`; findings 2, 5, 6, 8 and 9 there
remain deferred. Low-sky `EDGE_OR_VOID` flags are part of EX-2.

---

## Auto-tuners

<a id="at-1"></a>
### AT-1 · Tuner rates use the full crop area

**Priority:** Medium · **Evidence:** Source · **Import rules:** No effect · **Where:**
`CalibratedAutoTuner` rate computation (`ownedMpix`) · **Was:** CR-11

**What happens.** False-positive rates per MPix per frame divide by the full crop area. But the
void probes reject every detection within `voidProximityRadius` of a crop edge, because probes
outside the crop count as void.

**Example.**

- *Scenario:* crops of about 1000 px and a budget of 0.4 false positives per MPix per frame.
- *What happens:* the band at the edges is about 8 % of the area at 20 px, and about 25 % at 70 px
  (see IN-1). The measured rates are low by that share.
- *Consequence:* the tuner picks a looser setting than the budget intended, so real runs give more
  false detections than the budget the user chose.

**Fix direction.** Divide by the area where detections are possible, the crop minus the void band.

**Your comments:**

- Decision: <!-- implement / discuss first / defer / won't fix -->
- Notes: **Codex assessment:** The ownership denominator includes crop interiors where edge/void gates prevent detections, producing an extra crop-boundary bias relative to full-frame extraction. Intersect ownership with the actual searchable region and define whether the budget is per total or searchable megapixel. Include overlap ownership and edgeMarginPixels, rather than subtracting an approximate border area independently.

<a id="at-2"></a>
### AT-2 · Tuner injections near crop edges can't be recovered

**Priority:** Low (High while IN-1 is open) · **Evidence:** Source · **Import rules:** No effect ·
**Where:** `CalibratedAutoTuner.java:1026` (injection margin `max(30, edge + 10)`) · **Was:** CR-11

**What happens.** Injections can be placed 30 px from a crop edge. With a void radius above 30 px,
the void probes reject them.

**Example.**

- *Scenario:* the void radius has been raised to 70 px (IN-1).
- *What happens:* injections between 30 and 70 px from an edge are never recovered.
- *Consequence:* the measured recovery is too low, and the tuner may lower sigma to reach its
  target, which adds false detections.

**Fix direction.** Make the injection margin at least the void radius plus a few pixels.

**Your comments:**

- Decision: <!-- implement / discuss first / defer / won't fix -->
- Notes: **Codex assessment:** Confirmed mismatch between injection placement and the inherited void radius. Size the injection margin from all active exclusion distances plus the source footprint, and explicitly report crops with no usable injection interior. This remains relevant for deliberately enlarged void settings even after IN-1 stops accidental mutations.

<a id="at-3"></a>
### AT-3 · Negative image matches noise only under equal seeing

**Priority:** Low · **Evidence:** Source · **Import rules:** No effect · **Where:**
`CalibratedAutoTuner`, the negative image `2 × master − frame` · **Was:** CR-11

**What happens.** The null image removes stars only when the frame's seeing matches the master's.
When it differs, positive and negative rings stay around stars.

**Example.**

- *Scenario:* the tuner samples a frame with worse seeing than the median master.
- *What happens:* the rings around bright stars are detected as false positives in the null image.
- *Consequence:* the tuner overestimates the false-positive rate and picks a less sensitive setting
  than needed.

**Fix direction.** Mask master stars in the null image with the same veto mask the pipeline uses,
or choose tuner frames with seeing close to the median.

**Your comments:**

- Decision: <!-- implement / discuss first / defer / won't fix -->
- Notes: **Codex assessment:** Seeing mismatch can affect the null residuals, but the proposed star-mask veto already runs on negative-image detections in the tuner's evaluate method. Quantify the survivors outside/partly overlapping that mask before changing it. Equal seeing alone is insufficient: flux/background differences and master-stack noise matter too, and selecting only median-seeing frames could hide genuine difficult-frame leakage.

<a id="at-4"></a>
### AT-4 · Tuner budget counts detections, not what users see

**Priority:** Low · **Evidence:** Source · **Import rules:** No effect · **Where:**
`CalibratedAutoTuner` budgets · **Was:** CR-11

**What happens.** The budget limits false single-frame detections. Users see tracks, anomalies and
residual candidates.

**Example.**

- *Scenario:* setting A gives 100 isolated false detections that never link. Setting B gives 100
  that link into 5 false tracks.
- *What happens:* the tuner treats them as equal.
- *Consequence:* the tuner optimizes a quantity that only loosely follows what the user sees.

**Fix direction.** Later, also run the linker on the null image and count the false outputs, or add
a track-level term to the budget.

**Your comments:**

- Decision: <!-- implement / discuss first / defer / won't fix -->
- Notes: **Codex assessment:** This is an explicit objective limitation rather than a miscomputed budget. Keep the current inexpensive extraction-level calibration and label its units clearly; evaluate track-level false outputs as a secondary diagnostic first. Replacing the objective would require representative multi-frame null sequences and a justified runtime budget.

<a id="at-5"></a>
### AT-5 · Legacy tuner differs from the pipeline

**Priority:** Low · **Evidence:** Source · **Import rules:** No effect · **Where:**
`JTransientAutoTuner.java:32, 679, 730` · **Was:** CR-12

**What happens.**

- The legacy tuner grows the mask by `ceil(maxStarJitter)`, while the pipeline uses
  `max(1, round(maxStarJitter / 2))` (`MasterVetoMask.java:25`).
- Its comment "Streaks bypass the veto mask in the main engine" is wrong.
- `DEBUG = true` by default.

**Example.**

- *Scenario:* the legacy tuner with `maxStarJitter = 3`.
- *What happens:* it measures with a 3 px mask growth, while detection uses 2 px.
- *Consequence:* the tuned settings were evaluated on a cleaner frame than detection sees, so they
  give more false detections in real runs. The debug output also fills the logs.

**Fix direction.** Use `MasterVetoMask`'s growth, fix the comment, and default `DEBUG` to false.

**Your comments:**

- Decision: <!-- implement / discuss first / defer / won't fix -->
- Notes: **Codex assessment:** Source confirms the mask-growth mismatch in both legacy evaluation paths, plus the stale comment and enabled debug output. Reuse MasterVetoMask.dilationRadius consistently, then compare legacy recommendations before/after; unlike the logging cleanup, changing the mask can change tuned settings. Keep this separate from changes to the calibrated tuner's objective.

---

## Cross-cutting and design

<a id="xc-1"></a>
### XC-1 · Significance numbers assume uncorrelated noise

**Priority:** Low · **Evidence:** Source · **Import rules:** No effect · **Where:**
`integratedSigma` (extractor), slow-mover aperture SNR, `magError` (photometry) · **Was:** CR-12

**What happens.**

- Registration interpolates pixels, so neighbouring pixels share noise.
- The integrated significance, the aperture SNR and the magnitude errors all assume independent
  pixels, so they are overstated.
- `magError` also has no source photon noise, as recorded in `VariableStar-ASSESSMENT.md`.

**Example.**

- *Scenario:* frames registered with Lanczos interpolation.
- *What happens:* a source reported at an integrated sigma of 7 may really be nearer 4–5. This is
  an estimate, not measured.
- *Consequence:* the report's sigma values and error bars look more certain than they are. Because
  the thresholds were set empirically, what is detected doesn't change.

**Fix direction.** Document it, or measure a correlation factor from the noise of blank apertures
and apply it.

**Your comments:**

- Decision: <!-- implement / discuss first / defer / won't fix -->
- Notes: **Codex assessment:** The usual direction needs correcting: positive pixel correlations overstate significance and understate magnitude errors. The numerical 7-to-4/5 example is unmeasured, and the correction depends on resampling and aperture size. Document the errors as lower bounds first; calibrating them requires blank-aperture tests plus a separate treatment of source photon noise.

<a id="xc-2"></a>
### XC-2 · No common estimate of chance alignments (design)

**Priority:** Design · **Evidence:** Source · **Import rules:** No effect · **Where:** AN-1, RS-1,
RS-3, the geometric linker · **Was:** big picture

**What happens.** Several stages decide that something is "too regular to be noise", each with its
own heuristic:

- suspected streaks (three points on a line);
- LOCAL_REPEAT (two points close together);
- common motion (shared displacement);
- geometric tracks (points on a line in sequence).

None of them compares what it found with what the frame's detection density would produce by
chance.

**Example.**

- *Scenario:* the same settings run on a clean dataset and on a noisy one.
- *What happens:* the clean dataset gives a handful of candidates. The noisy one gives dozens from
  chance alone (AN-1, RS-3).
- *Consequence:* the meaning of a candidate depends on the dataset, and the user can't tell which
  candidates are likely chance.

**Fix direction.** One small helper giving the expected number of chance matches for N points, a
tolerance and an area. Each stage then accepts only results well above that expectation, or
reports the expectation next to the result.

**Your comments:**

- Decision: <!-- implement / discuss first / defer / won't fix -->
- Notes: **Codex assessment:** Useful design direction, but one density/area formula cannot calibrate every stage: spatial clustering, repeated sensor defects, shared trials and correlated frames violate uniform independence. Start with stage-specific null diagnostics and expose their assumptions. A common helper can share bookkeeping without presenting expected counts as calibrated per-candidate probabilities.

<a id="xc-3"></a>
### XC-3 · One global background per frame (design)

**Priority:** Design · **Evidence:** Source · **Import rules:** No effect · **Where:**
`SourceExtractor` · **Was:** big picture

**What happens.** One median and one σ per frame drive the thresholds, `peakSigma`, flux and the
void test. EX-1, part of EX-2 and the tuner's crop mismatch follow from it. The tuner side was
discussed earlier and left as is.

**Example.**

- *Scenario:* a frame with a strong sky gradient (moonlight or light pollution on one side).
- *What happens:* the bright side produces many false detections and the dark side loses faint
  ones, because both use the same threshold.
- *Consequence:* sensitivity varies across the frame, and false detections cluster on one side.

**Fix direction.** A local background mesh, as in SExtractor, is the usual answer, but it is a
large change. EX-1 removes the worst failure first.

**Your comments:**

- Decision: <!-- implement / discuss first / defer / won't fix -->
- Notes: **Codex assessment:** A genuine limitation for gradients and structured backgrounds, but a larger redesign rather than the prerequisite for every listed fix. Address padding and void handling independently first. A later local mesh must preserve extended emission and propagate local noise consistently; photometry already uses a local sky estimate, so scope this change to the affected extraction paths.

---

## Documentation and hygiene

<a id="doc-1"></a>
### DOC-1 · Documentation that disagrees with the code

**Priority:** Medium · **Evidence:** Doc · **Import rules:** No effect · **Was:** CR docs section

**What happens.**

- **Slow-mover defaults in `CONFIG.md`:**

  | Setting | `CONFIG.md` | Code |
  |---|---|---|
  | `slowMoverMinAxisRatio` | 1.35 | 1.2 |
  | `slowMoverMaxAxisRatio` | 3.20 | 2.8 |
  | `slowMoverMinFillFactor` | 0.0 | 0.5 |
  | `slowMoverMinFrameSupport` | 0 | 40 |
  | `slowMoverMaxStationaryLikelihood` | 100 | 60 |

- `ALGORITHM.md:299` says the fill-factor default (0.0) disables that filter; the default is 0.5.
- `SlowMoverAlgorithm.md` still gives 1.35 / 3.20 (`:510–511`, `:968–969`, `:1405–1406`), and says
  the frame vetoes "default to disabled" (`:804`).
- `README.md:37` gives version 1.0.0; `build.gradle` is 1.0.3.
- `DetectionConfig` Javadoc:
  - `enableGeometricTrackLinking` says "Keep this enabled", but the default is `false`.
  - `voidProximityRadius`: see EX-2.
  - The `trackMinFrameRatio` example gives 7 points and ignores the cap of 5.
- `CalibratedAutoTuner.cropRegions` Javadoc (`:1398`) says "up to three square crops"; the code
  uses 5 or a grid.
- `CONFIG.md` describes only the code defaults. The shipped SpacePixels profile differs on purpose:
  - `detectionSigma` 3.5 and grow 2.75;
  - slow movers 10 / 3.5 / 2.75;
  - `maxStarJitter` 1;
  - `enableGeometricTrackLinking` true;
  - `autoTuneMaxCandidateFrames` exists only in the profile.
- The input rules (see IN-2) are stated only in the `TrackLinker` Javadoc, not in `PIPELINE.md`.

**Example.**

- *Scenario:* a user reads `CONFIG.md` to understand why a slow mover was rejected.
- *What happens:* they believe the fill-factor and frame-support filters are off, when they reject
  at 0.5 and 40.
- *Consequence:* they change the wrong setting, and their own documentation-based reasoning
  misleads them.

**Fix direction.** One "Update docs" commit that corrects all of the above, adds a short note on
the SpacePixels profile differences, and states the input rules in `PIPELINE.md`.

**Your comments:**

- Decision: <!-- implement / discuss first / defer / won't fix -->
- Notes: **Codex assessment:** The listed default/version mismatches are confirmed and are suitable for a separate documentation update. Distinguish engine defaults from the shipped SpacePixels profile: autoTuneMaxCandidateFrames is an active SpacePixels wrapper setting, not a DetectionConfig field. Describe current contracts without silently turning proposed algorithm or API changes into documented guarantees.

<a id="hy-1"></a>
### HY-1 · Dead code and leftover output

**Priority:** Low · **Evidence:** Source · **Import rules:** No effect · **Was:** CR-12 and the CR
docs section

**What happens.**

- `SourceExtractor.isBinaryStarAnomaly` and `isIrregularStreakShape` are never called.
- `VariableStarAnalyzer.writeSpatialComparison` (`:512–709`) is never called.
- `VariableStarAnalyzer.java:387` prints `SPATIAL_FRAMES …` to stdout whenever spatial frames are
  dropped.
- `JTransientAutoTuner.DEBUG` is `true` by default (also in AT-5).
- `TrackLinker.isTrackAlreadyFound` (`:2235`) is redundant, because `usedPoints` already prevents
  points from being reused.

**Example.**

- *Scenario:* a batch run on a variable-star dataset.
- *What happens:* `SPATIAL_FRAMES dropped=…` lines appear in the log with no context. A future
  maintainer edits the dead methods, thinking they matter.
- *Consequence:* noise in the logs and wasted effort. There is no effect on results.

**Fix direction.** Remove the dead methods, route the print through `DEBUG`, default `DEBUG` to
false, and remove `isTrackAlreadyFound`.

**Your comments:**

- Decision: <!-- implement / discuss first / defer / won't fix -->
- Notes: **Codex assessment:** Logging cleanup and genuinely unused private code are reasonable. The SourceExtractor helpers are public API, so lack of internal callers does not justify removal without a compatibility decision. isTrackAlreadyFound is still called by the geometric linker; establish its redundancy before deleting it rather than labelling it uncalled dead code.

---

## Excluded by the import rules

These findings from the TrackLinker review can no longer be triggered from SpacePixels. For other
programs using JTransient they remain possible until IN-2 is done. No separate action is needed.

<a id="x-1"></a>
### X-1 · One timestamp disables the untimed fallback

**Was:** TL-1 · **Where:** `TrackLinker.java:524–530` (the global `hasTimestamps` probe)

- **What happens:** `hasTimestamps` becomes true if the first detection of any frame has a
  timestamp, even one later removed by the veto. The geometric fallback is then off for untimed
  movers.
- **Example:**
  - Scenario: a folder of original captures mixed with converted copies that lost `DATE-OBS`.
  - What happens: three untimed detections of a 20 px/frame mover are not linked at all.
  - Consequence: a missed mover.
- **Why it's excluded:** import refuses a mix of timed and untimed frames (FITS since 2026-10-08,
  XISF since 2026-10-17).

**Your comments:**

- Decision: <!-- implement / discuss first / defer / won't fix -->
- Notes: **Codex assessment:** Excluded by current SpacePixels import validation, but still relevant to direct mixed-timestamp callers when the geometric pass is disabled. Cover it through IN-2's explicit uniform-metadata contract; keep a rejection/untimed control test so future importer changes do not reopen it.

<a id="x-2"></a>
### X-2 · The "no timestamp" value used as a real time

**Was:** TL-2 · **Where:** time-based linker loops (`TrackLinker.java:556–749`)

- **What happens:** the loops check only that time differences are positive, so -1 is subtracted
  as if it were a real time.
- **Example:**
  - Scenario: a loader gives one frame -1 and the others real times.
  - What happens: points at times [-1, 1000, 2001] form a track with an invented 1001 ms interval.
  - Consequence: wrong speeds, and false links.
- **Why it's excluded:** the same all-or-none timestamp rule as X-1.

**Your comments:**

- Decision: <!-- implement / discuss first / defer / won't fix -->
- Notes: **Codex assessment:** Excluded for validated SpacePixels sequences. The linker still needs to reject mixed known/missing timestamps at its own public boundary before calculating differences; silently treating -1 as an epoch is unsafe. Handle this with IN-2 rather than a separate algorithm change.

<a id="x-3"></a>
### X-3 · Direct callers leave timestamps at 0

**Was:** TL "direct-call metadata defaults" · **Where:** `SourceExtractor.DetectedObject`
(default `timestamp = 0`)

- **What happens:** an object's default timestamp is 0, not -1. A caller that passes extractor
  output directly to the linker without filling timestamps makes every time difference zero, and
  turns off the geometric fallback.
- **Example:**
  - Scenario: a custom program calls `SourceExtractor` and `TrackLinker` directly.
  - What happens: untimed movers are never linked.
  - Consequence: an empty result without an error.
- **Why it's excluded:** SpacePixels goes through the engine, which fills in the metadata. Other
  callers are covered by IN-2.

**Your comments:**

- Decision: <!-- implement / discuss first / defer / won't fix -->
- Notes: **Codex assessment:** Confirmed direct-call trap: DetectedObject's default timestamp contradicts the documented -1 sentinel. Initialize absent metadata explicitly and document direct SourceExtractor-to-TrackLinker usage; preserve intentionally supplied Unix-epoch timestamps. SpacePixels assigns metadata through the engine, so this is API hardening under IN-2.

---

## The bigger picture

Most issues come back to five themes:

1. **One global background per frame** (XC-3). It causes EX-1 and part of EX-2.
2. **No common estimate of chance alignments** (XC-2). It is behind AN-1, RS-1 and RS-3.
3. **Units that drift between stages:**
   - list position versus sequence index (RS-2, PT-1);
   - pixels versus pixel² (ST-2);
   - the √2 FWHM (EX-3).
4. **Two definitions of padding** (EX-2).
5. **Coupling between stages:** `maxStarJitter` has several roles (PT-5), and photometry depends on
   detection outputs (PH-1).

The import rules close the input-side gaps for SpacePixels. The remaining issues are about the
algorithm, not about malformed input.

## Suggested order

Engine fixes go one at a time, each with a real-data regression:

1. IN-1, config copy in the engine. No detection change expected.
2. EX-1, padding out of the background histogram.
3. EX-2, one noise-aware void test.
4. AN-1, gap and span rules for suspected streaks.
5. RS-1, real motion required for common motion.
6. ST-2 and ST-3, streak motion units and degenerate lines.
7. PT-1, rhythm with real frame gaps.
8. PT-2 and PT-3, whole-track checks for time-based tracks.
9. DOC-1, at any time, as one "Update docs" commit.

After those:

- QC-1, RS-2, SM-1, PH-1, AT-1;
- then IN-2 and IN-3, the remaining TrackLinker items, and the design topics.

## Evidence

### Probes from this review

`Probes.java` is in the review session's scratchpad (`scratchpad/probe/`), outside both
repositories. It uses synthetic inputs and calls the real library classes. It was compiled against
`build/classes/java/main` plus Gson 2.13.2.

| Probe | What it tests | Result | Issue |
|---|---|---|---|
| P1 | Background with a padding band (sky 1000, σ 20, 300 stars) | σ 20 → 349 between 10 % and 12 % padding; objects 283 → 135 | EX-1 |
| P2 | Void test on low-sky data without padding | 300 / 300 kept at 10σ sky, 277 at 5σ, 168 at 3σ | EX-2 |
| P3 | Does `detectTransients` change the caller's config? | `voidProximityRadius` 20 → 70; `generateMasterStackWithDetails` leaves 20 | IN-1 |
| P4 | Point mover, 12 frames, 0.25 px noise, with and without timestamps | geometric only: 154 / 200 at 3 px per frame, ≥ 198 at 10+; timed: 200 / 200 | PT-4 |
| P5 | Suspected streaks from random anomalies in one frame | 4 % of frames with 4 anomalies, 49 % with 10, 100 % with 20 | AN-1 |
| P6 | Common motion on two stationary leftovers 800 px apart | 2 local rescue candidates → 0 | RS-1 |

### Reproductions from the TrackLinker review (2026-10-09)

Twelve targeted linker checks reproduced TL-1 to TL-10 with synthetic detections. The coordinates
are kept in the examples above. The archived files (probe source, run script, outputs, debugger
events) were stored under `build/review/track-linker-assessment-20261009/`. That folder no longer
exists, because the build directory has been cleaned since. The examples are enough to rebuild the
probes as unit tests before each fix.

### Tests

- JTransient: 141 tests, 0 failures, on 2026-10-17.
- SpacePixels after the import change: 192 tests, 0 failures. That includes 6 new tests for the XISF
  checks and the `BZERO` comparison.

## Appendix: TrackLinker stages

| Stage | What it does | Method or location |
|---|---|---|
| Stationary veto | Splits points and streaks, builds the dilated master footprint mask, rejects objects overlapping it beyond the configured fraction | start of `findMovingObjects` |
| Fast streak linking | From each unmatched streak, greedily picks an angle-compatible continuation, preferring small frame gaps and then short distances; allows same-frame fragments | `:336–424` |
| Single-streak promotion | Promotes unmatched streaks that pass the binary-shape veto and have enough peak significance | after streak linking |
| Point-track threshold | `max(3, ceil(frames / trackMinFrameRatio))`, capped by `absoluteMaxPointsRequired` | `:510–513` |
| Time-based proposals | For each baseline pair, extends through later frames with speed, line, angle and morphology gates; keeps every long-enough proposal | `:556–749` |
| Time-based assignment | Sorts proposals by length, then score; accepts those that share no detection with accepted ones | end of the time-based phase |
| Geometric fallback | Runs when enabled or when no timestamps exist; extends by line error and direction, prunes short steps, checks rhythm | `:764–946` |
| Anomaly rescue | Rescues strong untracked detections; groups three or more same-frame collinear anomalies into suspected streaks | `:1152–1262` |
| Final consolidation | Merges confirmed and suspected streak tracks when frame gap, orientation, line and motion checks pass; builds the unclassified exports | consolidation methods |
