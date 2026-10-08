# JTransient Auto-Tuner Guide

JTransient has two auto-tuners. Both derive a `DetectionConfig` for a session from a sample of its frames, take an `AutoTuneProfile` and return a `JTransientAutoTuner.AutoTunerResult`:

| Tuner | Entry point | How it decides |
| --- | --- | --- |
| Calibrated (measured) | `CalibratedAutoTuner.tune(...)` | Measures false positives and sensitivity directly for every combination of settings, then picks the most sensitive combination within the profile's false-positive budget |
| Legacy (score-based) | `JTransientAutoTuner.tune(...)` | Scores combinations by how well they separate stationary stars from possible transients; kept for comparison |

SpacePixels uses the calibrated tuner by default and offers the legacy one as an option.

The profiles are `CONSERVATIVE` (low sensitivity), `BALANCED` (medium), `AGGRESSIVE` (high) and `MAXIMUM` (as sensitive as possible, for small sensors or targeted searches for a faint object). The legacy tuner treats `MAXIMUM` like `AGGRESSIVE`.

For the broader library surface, see [PIPELINE.md](PIPELINE.md). For individual `DetectionConfig` fields, see [CONFIG.md](CONFIG.md).

# Calibrated Auto-Tuner (`CalibratedAutoTuner`)

## Idea

Instead of scoring proxies, the calibrated tuner tries a grid of settings on crops of the session's own frames and measures, for each combination:

- **false positives**: detections that a setting would report although nothing is there
- **sensitivity**: how many faint objects of known brightness it would find
- **mask coverage**: how much sky the star mask hides

Each profile is a false-positive budget per megapixel per frame. Among the combinations within budget, the tuner takes the one that recovers the most synthetic sources (near-ties go to the one that keeps more bright sources). A more permissive profile can therefore never be less sensitive.

| Profile | Budget (false positives per MPix per frame) |
| --- | --- |
| `CONSERVATIVE` | 0.05 |
| `BALANCED` | 0.2 |
| `AGGRESSIVE` | 0.6 |
| `MAXIMUM` | 3.5 |

Because the budget is per megapixel per frame, the number of false detections a profile allows grows with the sensor size and the number of frames. On a 61-megapixel sensor with 33 frames, `MAXIMUM` allows several thousand; it is meant for small sensors and targeted searches.

## Entry points

```java
JTransientAutoTuner.AutoTunerResult CalibratedAutoTuner.tune(
        List<ImageFrame> frames,
        DetectionConfig baseConfig,
        JTransientAutoTuner.AutoTuneProfile profile,
        TransientEngineProgressListener listener)

CalibratedAutoTuner.Calibration CalibratedAutoTuner.calibrate(
        List<ImageFrame> frames,
        DetectionConfig baseConfig,
        TransientEngineProgressListener listener)

DetectionConfig CalibratedAutoTuner.configFor(
        CalibratedAutoTuner.Calibration calibration,
        DetectionConfig baseConfig,
        JTransientAutoTuner.AutoTuneProfile profile)
```

`tune(...)` returns:

- the chosen settings in `optimizedConfig`;
- a short `summary`;
- the full `telemetryReport`;
- the whole measurement in `calibration`.

`calibrate(...)` returns:

- every measured combination;
- the choice for every profile (`chosen`, `withinBudget`, `budgetVerifiable`);
- the measured FWHM, jitter and measured area.

Because one measurement covers all profiles, a caller can switch profiles without re-running: `configFor(...)` applies one profile's choice to a clone of a base config. SpacePixels uses this for the profile table on its Detection Settings Overview page.

It sets `detectionSigmaMultiplier`, `growSigmaMultiplier`, `minDetectionPixels`, `masterSigmaMultiplier`, `masterGrowSigmaMultiplier`, `masterMinDetectionPixels`, `maxMaskOverlapFraction` and `maxStarJitter`. Everything else comes from the base config.

## Preparation

1. **Unusable frames are left out.** Frames where less than half of the pixels hold image data (blank frames, failed registrations) are dropped. At least 5 usable frames are needed.
2. **Crops.** The frames are cut into square crops of up to 1024 px: the centre and four corners on ordinary frames, an even grid of up to 4 x 4 on large ones. The border kept clear of the frame edges is the measured drift padding plus 50 px (between 50 and 200 px). Where crops overlap, each pixel is counted in one crop only.
3. **Measurement frames.** Ten frames, evenly spaced; small sensors use more (up to 40) so that about 30 megapixel-frames are measured.
4. **Star size and jitter.** The star FWHM is measured from the area above half the peak of bright, isolated stars (the core width; second moments would include the wings). The residual registration jitter is measured from master-to-frame star distances.
5. **Three images per crop-frame:** the real frame; a negative image `2 x master - frame`, which has the noise of a frame but no real objects; and a copy with synthetic stars injected (see below).
6. **Streak pass.** A more sensitive extraction (2.5 / 1.5 sigma) of each real crop joins the fragments of faint satellite or meteor trails into elongated objects. Detections within one FWHM of such a streak (lying mostly outside the star mask) are treated as real objects, not false positives.

## The settings grid

- Detection sigma 2.5 to 6.0; grow sigma = detection sigma minus 0.75 or 1.25.
- Minimum pixels: 0.3, 0.5, 0.75, 1, 1.5, 2 and 3 times the star core area (pi x (FWHM/2)^2), at least 3 and at most 15 pixels (`MAX_MIN_PIXELS`). Without the upper limit, wide stars would lead to sizes of 25 pixels and more, which reject small faint movers.
- Master sigma 1.5 to 5.0; master grow = master sigma minus 0, 0.5 or 1.0; master minimum pixels 3 or 0.25, 0.5 and 1 times the core area. The star mask is grown by max(1, round(jitter / 2)) px, as in the pipeline.
- Veto overlap 0.5, 0.65, 0.75, 0.85 and 0.95.

Settings that already drown in noise before any veto are skipped. Every combination is evaluated exactly as the pipeline would: extraction on the crops, then the star-mask veto.

## What is measured

**False positives**, per megapixel per frame:

- **noise**: detections in the negative image that survive the veto
- **star leakage**: real-frame survivors that touch the star mask; or reappear within the recurrence radius (max(1.5 px, jitter)) in another frame, on the sky or on the sensor (hot pixels move with the drift across registered frames; the sensor test only applies between frames whose drift differs); or lie within one FWHM of a star and reappear beside it in another frame
- **real-frame excess**: real-frame survivors above the lowest rate any setting reaches (faint stars visible only in better frames, seeing residuals and similar artefacts)

The false-positive figure is the larger of noise + leakage and the real-frame excess. A cautious estimate, (count + 1) / measured area, is compared with the budget. A budget smaller than one event over the measured area cannot be confirmed; it is relaxed to "no events".

**Sensitivity (the artificial star test).** On in-memory copies of the crops, the tuner injects Gaussian stars shaped like the session's measured stars, 12 per brightness level per crop-frame, at known positions and peak SNR 2, 3, 4, 5, 6, 8, 10 and 15. For each combination it counts the injected stars that are detected near their position and survive the veto. This gives the completeness per level, the recovered fraction (mean over levels), the bright recovery (SNR 8 and above) and SNR50, the peak SNR at which half are found. The injected stars never reach the input files or the pipeline run.

## Reading the report

The telemetry report lists the measurement area, the star FWHM and jitter, skipped settings and crops, the choice for every profile with its measurements, the best setting per detection sigma, the completeness curve of the chosen setting, and the final configuration.

## Known limitations

- The injected stars are static point sources. A slow mover whose path crosses faint stars included only in a deep mask can be vetoed in many consecutive frames; the tuner cannot foresee that for a particular path. (Example: Apophis, which the deep 1.5-sigma mask removes while a 2.0-sigma mask keeps it; `MAXIMUM` finds it.)
- Diffuse objects such as comets are not represented by the injected stars, so the cost of a deep mask for them is underestimated.
- Calibration takes a few seconds on small sensors and about a minute on 61-megapixel frames.

# Legacy Auto-Tuner (`JTransientAutoTuner`)

The rest of this document describes the original, score-based tuner.

## What The Auto-Tuner Is For

`JTransientAutoTuner` is a configuration search tool. It does not run the full transient-detection pipeline on the whole session. Instead, it:

1. picks a small representative sample of frames
2. extracts a few interior crops from those frames
3. builds cropped median master stacks
4. sweeps a bounded set of detection parameters
5. scores each combination by how well it separates stationary stars from possible transients
6. calibrates `maxStarJitter` from the sampled stars and validates the winning config

The main goal is to derive a better `DetectionConfig` quickly enough to be practical before a real detection run.

## Public API And Result Contract

Supported entry points:

```java
JTransientAutoTuner.AutoTunerResult tune(
        List<ImageFrame> frames,
        DetectionConfig baseConfig,
        TransientEngineProgressListener listener
)

JTransientAutoTuner.AutoTunerResult tune(
        List<ImageFrame> frames,
        DetectionConfig baseConfig,
        JTransientAutoTuner.AutoTuneProfile profile,
        TransientEngineProgressListener listener
)
```

The result object contains:

- `success`: whether the tuner found a winning configuration
- `optimizedConfig`: the winning config, or the provided base config on fallback
- `telemetryReport`: the full human-readable report
- `bestStarCount`: stable-star count for the winning sweep entry
- `bestTransientRatio`: transient ratio for the winning sweep entry
- `finalValidationTelemetry`: replay of the final config on the same frozen tuning crops, or `null` when tuning falls back before a winner exists

`finalValidationTelemetry` itself includes:

- whether final validation was executed
- whether it completed without a crop-level failure
- whether the final config still passed the tuner hard gates
- object, stable-star, transient, mask, and variability metrics
- a one-line status message
- the label of a failed crop when validation aborts early

## Input Assumptions

The tuner assumes:

- all frames have the same dimensions
- frames are already aligned or registered well enough for master-stack comparisons
- frame pixels are monochrome `short[][]`
- `sequenceIndex` reflects capture order
- there are at least `AUTO_TUNE_SAMPLE_SIZE` frames available, which defaults to `5`

If fewer than `AUTO_TUNE_SAMPLE_SIZE` frames are available, tuning does not proceed and the tuner falls back to the incoming base config.

## What It Tunes, Measures, Preserves, And Ignores

The tuner does not treat all `DetectionConfig` fields equally.

| Category | Fields | How they are used |
| --- | --- | --- |
| Direct sweep variables | `detectionSigmaMultiplier`, `growSigmaMultiplier`, `minDetectionPixels`, `maxMaskOverlapFraction` | Explicitly searched in Phase 1 |
| Calibrated from data | `maxStarJitter` | Measured from sampled stars, not searched directly |
| Preserved from base config | `streakMinElongation` and most other untouched config fields | Carried through to the returned config unless temporarily overridden internally |
| Used during frame sampling | `qualitySigmaMultiplier`, `qualityGrowSigmaMultiplier`, `qualityMinDetectionPixels`, `qualityMaxElongationForFwhm`, `edgeMarginPixels`, `voidThresholdFraction`, `voidProximityRadius`, `bgClippingIterations`, `bgClippingFactor`, `streakMinElongation`, `streakMinPixels` | Affect which frames are considered good enough to tune on |
| Ignored during frame sampling | `detectionSigmaMultiplier`, `growSigmaMultiplier`, `minDetectionPixels`, `maxMaskOverlapFraction`, `maxStarJitter` | These do not affect the frame-quality pass |

The key separation is:

- detection-side thresholds are tuned in Phase 1
- quality-side thresholds are taken from the input config and used directly during frame sampling
- the tuner no longer auto-calibrates the FWHM elongation gate

## High-Level Flow

The tuner is organized into four major stages:

1. input snapshot and frame-quality sampling
2. crop selection and pre-cropping
3. pre-sweep `maxStarJitter` calibration
4. Phase 1 sweep and scoring, followed by Phase 2 validation

The rest of this document walks through each stage in detail.

## 1. Input Snapshot And Frame Sampling

The report starts with a header, profile line, and input-frame order trace. It then writes an `--- INPUT CONFIG SNAPSHOT ---` section. The snapshot exists for two reasons:

- to show what config the run started from
- to reveal whether a second tuning pass is inheriting fields from a previous tuned output

The report lists:

- the number of incoming frames, whether they arrived sorted by `sequenceIndex`, and a compact sequence-order summary
- the incoming base config
- which fields can affect frame sampling
- which carried-over fields are ignored during frame sampling
- the exact effective sampling config

### Frame-quality measurement

The tuner evaluates every frame with `FrameQualityAnalyzer.evaluateFrame(...)`.

That pass uses a dedicated sampling config, not the eventual sweep config.

The sampling score is:

```text
qualityScore = backgroundNoise * medianFWHM
```

Lower is better.

The current sampling pass is designed to answer:

`Which frames are clean and sharp enough to represent this session?`

It is not supposed to answer:

`Which frames look best under the previous run's tuned grow sigma?`

The quality pass now has its own dedicated thresholds:

- `qualitySigmaMultiplier`
- `qualityGrowSigmaMultiplier`
- `qualityMinDetectionPixels`
- `qualityMaxElongationForFwhm`

That separation keeps frame sampling stable even when the tuner later proposes a different detection-side `growSigmaMultiplier`.

### How the tuning frames are selected

The tuner evaluates all frames, sorts them by `qualityScore`, then selects:

- up to the best `3` frames
- a few median-ish candidates around the center of the sorted list
- a fill-in from the sorted list if needed

This is a deliberate compromise:

- only using the very best frames risks overfitting to perfect conditions
- only using median frames can make the tuner too tolerant of lower-quality data

The selected frames are reported with:

- `Noise`
- `FWHM`
- `Stars`
- `ShapeStars`
- `BrightShapeStars`
- `FwhmStars`
- `Ecc`
- `BrightEcc`
- the final `Score`

Definitions:

- `Stars`: total stars used by the quality analyzer
- `ShapeStars`: stars usable for shape metrics
- `BrightShapeStars`: bright stars usable for the dedicated bright-star eccentricity metric
- `FwhmStars`: stars that survived the FWHM-specific filters
- `Ecc`: median elongation/eccentricity for usable shape stars
- `BrightEcc`: median elongation/eccentricity for bright usable shape stars, or `n/a`

The final selected list is sorted by `sequenceIndex` before tuning continues.

## 2. Crop Selection

The tuner does not sweep on full frames. It chooses a few representative interior crops and works only there.

Current crop constants:

- crop count: `3`
- border margin target: `200 px`
- preferred large crop: `1024 x 1024`
- preferred small crop: `768 x 768`
- minimum crop size: `384 x 384`

### Crop placement strategy

If the frame is large enough, the tuner uses:

- upper-left interior crop
- center crop
- lower-right interior crop

The crops are kept away from the outer border when possible. That reduces the influence of:

- registration voids
- hard edges
- local defects that only exist near frame boundaries

### Why crops are used at all

Using crops keeps tuning practical:

- a full sweep would be much more expensive on full-resolution frames
- crop-based master stacks are faster to rebuild
- the tuner still samples several parts of the field of view instead of trusting a single patch

After crop regions are chosen, the selected tuning frames are cropped once and cached. The sweep reuses those pre-cropped arrays.

## 3. Pre-Sweep `maxStarJitter` Calibration

Before the main sweep, the tuner estimates `maxStarJitter` from actual star motion within the frozen tuning crops.

This matters because the veto mask built from master-stack stars is dilated by `maxStarJitter`. If that radius is too small, stable stars leak out of the mask and get counted as transients. If it is too large, the mask becomes bloated and starts hiding too much image area.

### Probe configuration

The jitter probe is intentionally simple and fixed:

- detection sigma: `4.0`
- grow sigma: `3.0`
- min pixels: `5`
- usable-star max elongation: `2.5`
- search radius: `4.0 px`
- minimum accepted matches: `8`
- fallback jitter: `1.5 px`
- hard floor: `1.0 px`
- percentile: `P90`
- safety multiplier: `2.0`

### Jitter measurement algorithm

For each crop:

1. build a median master stack from the cropped tuning frames
2. extract master-stack stars using the config's master-star thresholds
3. extract stars from every cropped frame using the fixed probe config
4. discard poor jitter candidates:
   - null detections
   - tiny footprints with fewer than `5` pixels
   - elongation above `2.5`
5. match each usable master star to the nearest usable star in each frame
6. reject matches farther than `4 px`
7. require reciprocal nearest-neighbor agreement back to the master stack
8. collect all accepted distances

The final estimate is:

```text
estimatedJitter = max(1.0, P90(distances) * 2.0)
```

If too few matches are found, the tuner falls back to:

```text
max(1.0, 1.5)
```

which is `1.5 px`.

### Why jitter is calibrated before the sweep

The sweep should not depend on the caller's incoming jitter guess. The sweep uses the measured jitter, not the base config's `maxStarJitter`, so every tested combination is judged against the same veto-mask dilation baseline.

## 4. Phase 1 Sweep

Phase 1 is the main search stage.

The tuner explores these parameter grids:

| Parameter | Values |
| --- | --- |
| Detection sigma | `3.5`, `4.0`, `5.0`, `6.0`, `7.0` |
| Minimum pixels | `3`, `5`, `7`, `9`, `12` |
| Grow delta | `0.75`, `1.25`, `1.75` |
| Mask overlap fraction | `0.65`, `0.70`, `0.75` |

Grow sigma is not searched directly. It is derived from sigma and grow delta:

```text
growSigma = max(1.0, sigma - growDelta, masterSigmaMultiplier + 0.5)
```

That keeps grow threshold meaningfully below seed threshold without letting it collapse down to the master-map grow level. If the floor would meet or exceed the seed sigma, that sweep candidate is skipped as invalid hysteresis.

### What happens for each tested combination

For every `(sigma, growSigma, minPix, overlap)` combination, the tuner:

1. clones the base config
2. applies the tested detection sigma
3. applies the derived grow sigma
4. applies the tested minimum pixel count
5. applies the tested mask overlap threshold
6. overwrites `maxStarJitter` with the pre-sweep calibrated jitter

Then, for each crop, it performs the exact same evaluation logic.

### Per-crop evaluation

For one crop, the tuner does this:

1. build a median master stack from the crop's sampled frames
2. extract master stars from that crop stack using:
   - `masterSigmaMultiplier`
   - `masterMinDetectionPixels`
   - temporary `growSigmaMultiplier = masterSigmaMultiplier`
   - temporary `edgeMarginPixels = 5`
   - temporary `voidProximityRadius = 5`
3. reject the tested combination if no master stars are found
4. create a veto mask from all master-star pixels
5. dilate the veto mask by `ceil(maxStarJitter)`
6. reject the combination if that crop's mask coverage exceeds `25%`
7. extract objects from every cropped frame using the tested configuration
8. ignore streaks during stable-vs-transient classification
9. measure each non-streak object's overlap with the veto mask
10. classify the object as:
    - stable if overlap `>= maxMaskOverlapFraction`
    - transient otherwise

This is the core idea of the tuner:

- stationary stars should strongly overlap the master-star mask
- everything that escapes the mask is candidate transient leakage

### Why streaks are skipped in this classification

The tuner does not want a real meteor-like event in a sample frame to be interpreted as ordinary noise leakage. Streaks bypass this stable/transient crop scoring pass for that reason.

### Hard rejections before scoring

A tested combination is rejected before scoring if any of these happen:

- any crop fails to extract master stars
- any crop's mask coverage exceeds `25%`
- total extracted objects across all crop-frames are outside bounds:
  - below `10`
  - above `500000`
- transient ratio exceeds the profile hard limit
- stable-star count is below `15`

Rejected combinations appear in the report with `[REJECTED]` or a skip reason.

## 5. How Phase 1 Scoring Works

A tested combination that survives the hard gates is scored. Higher score is better.

The tuner computes:

- `cropFrameCount = tuningFrames * cropRegions`
- `avgStableStars = totalStableStars / cropFrameCount`
- `avgTransientsPerCropFrame = totalTransients / cropFrameCount`
- `transientRatio = totalTransients / totalObjectsExtracted`
- `stableCv = coefficientOfVariation(stableCountsPerCropFrame)`
- `transientCv = coefficientOfVariation(transientCountsPerCropFrame)`

The score is:

```text
score =
    + stableWeight * stableYieldScore
    - overflowWeight * transientOverflowPenalty
    - sweetSpotWeight * transientSweetSpotPenalty
    - varianceWeight * variancePenalty
    - maskWeight * maskCoveragePenalty
    - harshnessWeight * harshnessPenalty
    - guardWeight * lowSigmaMinPixGuardPenalty
```

### Stable yield term

The stable yield term rewards combinations that preserve enough stationary stars:

```text
stableYieldScore = clamp01(avgStableStars / 100.0)
```

`100` is the current `OPTIMAL_STAR_COUNT` anchor. More stable stars are good, but this term saturates rather than increasing without limit.

### Transient overflow term

This is the main anti-noise penalty:

```text
transientOverflowPenalty = clamp01(transientRatio / profile.maxTransientRatio)
```

The closer the transient ratio gets to the profile limit, the larger the penalty.

### Transient sweet-spot term

The tuner does not always prefer literally zero transients. A tiny non-zero number can be healthy, especially in more permissive profiles, because it suggests the detector is not completely over-hardened.

The sweet-spot penalty:

- is strongest outside the profile's acceptable transient band
- is mild inside that band
- is lowest near the target transient count per crop-frame

### Variance term

The tuner dislikes configurations whose behavior is unstable across crop-frames.

The penalty is:

```text
variancePenalty =
    0.6 * clamp01(stableCv / 0.25) +
    0.4 * clamp01(transientCv / 0.75)
```

This rewards consistency:

- stable-star counts should not swing wildly from crop-frame to crop-frame
- transient leakage should also stay reasonably controlled

### Mask coverage term

Mask coverage is tolerated up to a point, then penalized.

- no soft penalty until `8%`
- full rejection at `25%`

Between those points, the penalty ramps linearly.

This prevents the tuner from winning by building an unrealistically fat stationary-star mask that simply hides too much of the scene.

### Harshness term

The tuner also applies a profile-dependent preference against overly harsh settings.

It normalizes:

- sigma harshness across the sigma grid
- minimum-pixel harshness across the min-pixel grid
- grow-delta harshness across the grow-delta grid

and combines them with profile weights.

This means two combinations with similar raw outcomes can still be separated by a preference for the less punitive one.

### Low-sigma/min-pixel guard term

Very low sigma combined with tiny blobs is a common failure mode. The tuner explicitly penalizes that regime.

Current guard behavior:

- no penalty when `sigma >= 4.0`
- below `4.0`, the desired minimum pixel count rises as sigma falls
- if the tested `minPix` is too low for that sigma, the combination is penalized

This is one reason low-sigma, low-min-pixel combinations often lose even when their raw stable-star count looks attractive.

## 6. Profile Presets

The public profiles are:

- `CONSERVATIVE`
- `BALANCED`
- `AGGRESSIVE`
- `MAXIMUM`, which this tuner treats exactly like `AGGRESSIVE`

They do not change the search grid. They change the hard limits and score weights.

### Behavior summary

| Profile | Max transient ratio | Target transients per crop-frame | Sweet-spot band | Main bias |
| --- | --- | --- | --- | --- |
| `CONSERVATIVE` | `0.05` | `0.20` | `0.05` to `0.50` | Strongly suppress leakage |
| `BALANCED` | `0.05` | `0.35` | `0.15` to `0.80` | Middle ground |
| `AGGRESSIVE` | `0.12` | `1.00` | `0.40` to `2.00` | Allows more leakage to preserve sensitivity |

### Exact score weights

| Profile | Stable | Overflow | Sweet spot | Variance | Mask | Harshness | Low-sigma guard |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `CONSERVATIVE` | `100` | `120` | `20` | `60` | `50` | `22` | `10` |
| `BALANCED` | `100` | `120` | `25` | `60` | `50` | `20` | `18` |
| `AGGRESSIVE` | `100` | `70` | `35` | `50` | `50` | `34` | `22` |

### Harshness composition weights

| Profile | Sigma weight | Min-pixel weight | Grow-delta weight |
| --- | --- | --- | --- |
| `CONSERVATIVE` | `0.40` | `0.35` | `0.25` |
| `BALANCED` | `0.55` | `0.20` | `0.25` |
| `AGGRESSIVE` | `0.90` | `0.00` | `0.15` |

The aggressive profile is notable here:

- it tolerates a higher transient ratio
- it now penalizes high sigma even more strongly than before
- it no longer gets any harshness reward from keeping `minDetectionPixels` artificially tiny
- it keeps a softer low-sigma/min-pixel guard than before, so moderate `minDetectionPixels` can still win with lower sigma
- when candidates are close in score, it breaks near-ties in favor of lower sigma across a wider score window

## 7. Phase 2: Final Validation

After the sweep winner is found, the tuner replays the final tuned config on the exact same frozen tuning crops.

This validation exists to confirm the returned configuration still behaves like the winning Phase 1 candidate when rerun end-to-end on the frozen sample.

### Validation replay

Final validation repeats the main crop evaluation logic:

- rebuild each crop's master stack
- re-extract master stars
- rebuild veto masks using the final `maxStarJitter`
- re-extract crop-frame objects with the final config
- recompute object counts, stable stars, transients, ratio, mask coverage, and CVs

### Possible outcomes

There are three broad cases:

1. validation passes
2. validation completes but warns that hard gates drifted
3. validation aborts early because a crop failed

Examples of crop-level early failure:

- no master stars extracted in a crop
- mask coverage in a crop exceeds `25%`

The report includes the validation status plus the full metric summary.

## How To Read The Telemetry Report

The telemetry report is the best way to understand why the tuner made a choice.

### `--- INPUT CONFIG SNAPSHOT ---`

This section tells you:

- what the run started from
- which inputs can affect frame sampling
- which carried-over fields are intentionally ignored during frame sampling
- the exact quality-side thresholds used for frame sampling

The line immediately before it, `Input Frames -> ...`, reports the incoming frame count, whether the caller supplied frames already sorted by `sequenceIndex`, and a compact order summary.

If repeated runs differ, start here.

### `Selected N tuning frames`

This tells you whether the sample changed before the sweep.

If repeated runs pick different frames, or the same frame with different measured FWHM, the divergence happened upstream of the sweep.

### `Using 3 tuning crops`

This tells you:

- crop size
- whether the border margin was honored
- where the crops were placed

### `Calibrated maxStarJitter`

This tells you:

- how many accepted jitter matches were found
- how many usable reference stars contributed
- the raw percentile estimate
- the final jitter after safety scaling

If the tuner falls back here, the sample may not have enough clean reciprocal star matches.

### `--- PHASE 1: Detection Threshold Sweep ---`

Every tested combination is logged.

Important fields:

- `Stable`
- `Transients`
- `Ratio`
- `AvgTrans/CropFrame`
- `StableCV`
- `TransCV`
- `Mask`
- `Score`

Interpret them together, not in isolation.

Examples:

- high stable count with terrible transient ratio is usually unusable
- low transient ratio with near-zero stable count is usually over-hardened
- low score with good ratio can still happen if variance or harshness is bad

### `--- PHASE 2: FINAL CONFIG VALIDATION ---`

This tells you whether the final config still matches the winning sweep behavior on the frozen tuning crops.

### `=== FINAL OPTIMIZED CONFIGURATION ===`

This is the config you normally carry into the real pipeline run.

## Repeatability And Path Dependence

The tuner is designed to be stable when rerun on the same data with the same sampling-related inputs.

That said, repeatability depends on what changes between runs.

### Repeated runs should remain stable when:

- the raw frames are the same
- the base config's sampling-related fields are the same
- the frame order is effectively the same for equal-quality ties

### Repeated runs can legitimately change when:

- `qualitySigmaMultiplier` changes
- `qualityGrowSigmaMultiplier` changes
- `qualityMinDetectionPixels` changes
- `qualityMaxElongationForFwhm` changes
- edge or void rejection settings change
- background clipping settings change
- streak settings used during sampling change
- the input frames themselves change

### What no longer should happen

The previous run's tuned detection `growSigmaMultiplier` should not change frame sampling anymore. Frame sampling now uses the quality-side grow threshold directly, so detection tuning no longer feeds back into sample selection.

## Practical Guidance

Use the tuner when:

- you have a new optical setup
- sky conditions differ materially from your previous sessions
- preprocessing or registration changed
- you need a better starting config before running the full engine

Do not treat the tuner as:

- a full substitute for `JTransientEngine.runPipeline(...)`
- a guarantee that the returned config is globally optimal for every frame in the session
- a tool that should keep improving forever when fed its own output repeatedly

The intended workflow is:

1. start from a sensible base config
2. run the auto-tuner once on the session
3. inspect the telemetry report
4. use the returned config for the real detection pipeline

If you tune again immediately on the tuned output, the result should now usually be much closer to the first answer unless a real sampling input changed.

## What The Auto-Tuner Does Not Do

The auto-tuner does not:

- process the whole session at full resolution
- link detections into tracks
- export `PipelineResult`
- run slow-mover analysis
- guarantee the best possible settings for every downstream stage

It is a bounded optimization pass focused on one thing:

`find a robust extraction configuration that separates stationary stars from likely transients on a representative sample of the session`

## Relationship To Other Documentation

- [PIPELINE.md](PIPELINE.md): which public entry point does what
- [ALGORITHM.md](ALGORITHM.md): how the full pipeline works after tuning
- [CONFIG.md](CONFIG.md): exact meaning of each `DetectionConfig` field
