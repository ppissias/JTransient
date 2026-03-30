# JTransient DetectionConfig Guide

`DetectionConfig` is the runtime configuration object for extraction, frame rejection, slow-mover analysis, and tracking.

All fields are public and mutable. A few implementation details matter when you use it:

- `JTransientEngine` may raise `voidProximityRadius` during drift diagnostics if the data demands it
- the engine temporarily overrides some fields internally while extracting the master stack and slow-mover stack, then restores them
- `JTransientAutoTuner.tune(...)` clones the supplied config and returns an optimized clone; it does not mutate the original reference unless you reuse the returned object

This document tracks the fields that actually exist in `src/main/java/io/github/ppissias/jtransient/config/DetectionConfig.java`.

## 1. Extraction And Blob Classification

### `detectionSigmaMultiplier` (default `5.0`)

Primary seed threshold for `SourceExtractor.extractSources(...)`.

- seed threshold = `backgroundMedian + backgroundSigma * detectionSigmaMultiplier`
- higher values reduce sensitivity and false positives
- lower values detect fainter structure but increase noise
- the auto-tuner actively searches this field

### `growSigmaMultiplier` (default `3.0`)

Secondary hysteresis threshold used while the BFS expands a blob.

- lower than the seed threshold by design
- controls how much faint edge structure is retained
- too low can leak into noise
- the engine temporarily sets this to `masterSigmaMultiplier` when extracting master stars
- the engine temporarily sets this to `masterSlowMoverGrowSigmaMultiplier` during slow-mover extraction
- the auto-tuner actively searches this field

### `minDetectionPixels` (default `10`)

Minimum blob size required before shape analysis.

- rejects hot pixels and tiny noise islands
- applies to the main extraction pass
- the auto-tuner actively searches this field

### `edgeMarginPixels` (default `15`)

Dead zone around the physical image border.

- only applies to non-streak detections
- streaks bypass this filter
- the engine temporarily sets it to `5` for master-star extraction

### `voidThresholdFraction` (default `0.5`)

Threshold fraction used to define black registration voids.

- extraction computes `voidValueThreshold = backgroundMedian * voidThresholdFraction`
- border drift diagnostics also use it when scanning inward from the image edges

### `voidProximityRadius` (default `20`)

Radius of the virtual-edge test around each detected object.

- the extractor samples 8 points on a ring around the centroid
- if any point is out of bounds or below the void threshold, the object is rejected
- drift diagnostics may increase this field in place to match real border padding
- the engine temporarily sets it to `5` for master-star extraction

### `streakMinElongation` (default `6.0`)

Elongation threshold for streak classification.

- computed from image moments
- a blob becomes a streak only if this threshold and `streakMinPixels` are both satisfied
- the auto-tuner preserves the incoming value

### `streakMinPixels` (default `25`)

Minimum footprint size required for an elongated blob to be considered a streak.

- keeps tiny elongated noise from being flagged as a real streak

### `singleStreakMinPeakSigma` (default `7.0`)

Minimum `peakSigma` required for a one-point streak track.

- used after streak linking
- single-frame streaks below this significance are discarded

### `bgClippingIterations` (default `3`)

Number of sigma-clipping passes for the histogram-based background estimate.

- more iterations better isolate the sky background
- too many iterations can overfit unusual histograms

### `bgClippingFactor` (default `3.0`)

Sigma width used to clamp the histogram bounds during background estimation.

- lower values clip bright structure more aggressively
- higher values preserve more of the original histogram

### `strictExposureKinematics` (default `false`)

Optional physical speed limit for point-like detections.

- used in both time-based and geometric linking
- if enabled, a point source is not allowed to jump farther than its footprint and exposure time imply
- useful when long exposures should physically blur fast movers into streaks
- not ideal for short glints or intermittent flashes

## 2. Master Stack And Slow-Mover Extraction

### `masterSigmaMultiplier` (default `2.25`)

Seed threshold for extracting stationary objects from the median master stack.

- usually lower than the main detection sigma
- used with `masterMinDetectionPixels`
- during master extraction the engine also sets `growSigmaMultiplier` to the same value

### `masterMinDetectionPixels` (default `3`)

Minimum size for master-stack objects.

- lower values produce a more complete stationary-star map
- can also increase mask density if set too low

### `enableSlowMoverDetection` (default `true`)

Master switch for the slow-mover branch.

- when disabled, no slow-mover stack or candidate list is produced

### `slowMoverStackMiddleFraction` (default `0.75`)

Controls how the slow-mover stack is built.

- the implementation takes the upper end of the middle band of sorted per-pixel values
- it is not a plain percentile parameter, although it behaves similarly
- larger values preserve more semi-persistent bright structure

### `masterSlowMoverMinPixels` (default `15`)

Minimum blob size for slow-mover extraction.

- applied to both the slow-mover stack and the comparison extraction on the median master stack

### `masterSlowMoverSigmaMultiplier` (default `4.0`)

Seed threshold for the slow-mover extraction pass.

- applied to the slow-mover stack
- also applied to the comparison extraction on the median master stack

### `masterSlowMoverGrowSigmaMultiplier` (default `3.5`)

Temporary grow threshold used only during the slow-mover extraction pass.

- the engine swaps this into `growSigmaMultiplier` while extracting slow-mover candidates

### `slowMoverBaselineMadMultiplier` (default `4.5`)

Controls the dynamic elongation threshold for slow-mover candidates.

- threshold = `medianElongation + MAD * slowMoverBaselineMadMultiplier`
- larger values are stricter
- smaller values are more sensitive to elongated residuals

Slow-mover candidates now pass through a simpler artifact filter after this baseline check.

- irregular or binary-like shapes are still rejected first
- any surviving candidate must overlap the median-stack artifact mask within the configured support band
- the stage-by-stage outcome is reported through `PipelineResult.slowMoverTelemetry`

### `slowMoverMedianSupportOverlapFraction` (default `0.10`)

Minimum fraction of a slow-mover footprint that must overlap the median-stack artifact mask.

- `0.0` allows a blank-sky candidate through the lower bound
- larger values require stronger support from the median stack
- useful when faint star wobble still leaks through with only a token overlap

### `slowMoverMedianSupportMaxOverlapFraction` (default `0.65`)

Maximum fraction of a slow-mover footprint that may overlap the median-stack artifact mask.

- lower values reject candidates that look too similar to stationary median-stack artifacts
- should usually stay above `slowMoverMedianSupportOverlapFraction`
- useful when deep-stack static artifacts still survive with very high median-stack overlap

## 3. Frame Quality Analysis

### `qualitySigmaMultiplier` (default `5.0`)

Seed threshold for the quality-analysis extraction pass.

- used by `FrameQualityAnalyzer.evaluateFrame(...)`
- usually kept fairly strict so the quality metrics are based on clear stars

### `qualityGrowSigmaMultiplier` (default `3.0`)

Secondary hysteresis threshold for the quality-analysis extraction pass.

- controls how far a quality-analysis star is allowed to grow after seeding
- keeps frame scoring independent from the main detection `growSigmaMultiplier`
- only affects `FrameQualityAnalyzer.evaluateFrame(...)`

### `qualityMinDetectionPixels` (default `5`)

Minimum blob size for the quality-analysis extraction pass.

- used together with `qualitySigmaMultiplier`

### `qualityMaxElongationForFwhm` (default `1.5`)

Upper elongation limit for stars that contribute to the frame's FWHM measurement.

- elongated stars still contribute to the eccentricity metric
- they are excluded from FWHM so trailing does not look like poor focus
- preserved by the auto-tuner; it is not auto-calibrated

### `errorFallbackValue` (default `999.0`)

Fallback metric value when the analyzer cannot compute a real median.

- helps the session evaluator reject obviously unusable frames

### `minBackgroundDeviationADU` (default `10.0`)

Absolute floor on the allowed background-median deviation.

- prevents over-sensitive rejection on very stable nights

### `minEccentricityEnvelope` (default `0.10`)

Absolute floor on the eccentricity rejection envelope.

- stops the MAD threshold from collapsing too tightly

### `minFwhmEnvelope` (default `0.5`)

Absolute floor on the FWHM rejection envelope.

- stops tiny focus fluctuations from causing false rejection

## 4. Tracking And Stationary-Star Vetoing

### `maxStarJitter` (default `1.5`)

Core motion/jitter scale used across the tracker.

It affects several places:

- the veto mask dilation radius is derived from `round(maxStarJitter / 2.0)` with a minimum of `1`
- baseline pairs below this jump are treated as stationary
- anti-hijack pruning removes track steps that stall within this scale
- time-based velocity tolerance uses it as a physical slack term
- the auto-tuner actively measures and updates this field

### `maxMaskOverlapFraction` (default `0.75`)

Fraction of an object's footprint that may overlap the veto mask before the object is purged.

- purge rule: `overlapFraction > maxMaskOverlapFraction`
- larger values are more permissive near stars
- smaller values are stricter
- the auto-tuner actively searches this field

### `predictionTolerance` (default `3.0`)

Maximum perpendicular distance from a candidate point to the projected trajectory line.

- used in both time-based and geometric linking
- larger values tolerate more curvature or measurement error
- smaller values enforce straighter tracks

### `angleToleranceDegrees` (default `2.0`)

Angular tolerance for forward-direction checks.

- used in streak linking
- used in time-based and geometric linking to reject backward or sharply diverging candidates

### `trackMinFrameRatio` (default `3.0`)

Controls the required number of points in a point track.

- `minPointsRequired = max(3, ceil(numFrames / trackMinFrameRatio))`
- the result is later capped by `absoluteMaxPointsRequired`

### `timeBasedVelocityTolerance` (default `0.10`)

Relative speed tolerance for time-based linking.

- used when timestamps exist
- allowed speed error also includes a jitter term based on `maxStarJitter`

### `absoluteMaxPointsRequired` (default `5`)

Upper cap for `minPointsRequired`.

- prevents very large frame sets from demanding extremely long tracks

### `maxJumpPixels` (default `400.0`)

Maximum allowed jump for the geometric fallback linker.

- does not limit the initial time-based baseline the same way
- still relevant for later geometric extension

### `maxFwhmRatio` (default `2.0`)

Morphology consistency filter for linked points.

- ratio test between consecutive detections' `fwhm`
- set to `0` to disable

### `maxSurfaceBrightnessRatio` (default `2.0`)

Morphology consistency filter based on `totalFlux / pixelArea`.

- helps reject links between mismatched profiles
- set to `0` to disable

### `rhythmAllowedVariance` (default `8.0`)

Allowed jump-size error in the steady-rhythm check.

- compared against integer multiples of the median jump

### `rhythmMinConsistencyRatio` (default `0.70`)

Minimum fraction of consistent jumps required by the rhythm check.

- larger values are stricter

### `rhythmStationaryThreshold` (default `0.5`)

Minimum median jump required for a geometric track.

- tracks whose median step falls below this are treated as stationary leakage

## 5. Session Rejection

### `minFramesForAnalysis` (default `3`)

Minimum number of frames required before session-level rejection is applied.

- below this count, the engine skips outlier rejection

### `starCountSigmaDeviation` (default `2.0`)

Controls how far star count may fall below the session median before rejection.

### `fwhmSigmaDeviation` (default `2.5`)

Controls how far median FWHM may rise above the session median before rejection.

### `eccentricitySigmaDeviation` (default `3.0`)

Controls how far median eccentricity may rise above the session median before rejection.

### `backgroundSigmaDeviation` (default `3.0`)

Controls how far the background median may deviate from the session median before rejection.

### `zeroSigmaFallback` (default `0.001`)

Fallback sigma used when a MAD-derived sigma would be exactly zero.

- prevents divide-by-zero style threshold collapse

## 6. Anomaly Rescue

### `enableAnomalyRescue` (default `true`)

Enables the final single-frame rescue pass.

- only affects detections that survived stationary-star masking but were not consumed by tracks

### `anomalyMinPeakSigma` (default `8.0`)

Minimum peak significance required for anomaly rescue.

- based on `DetectedObject.peakSigma`
- sharp one-frame glints can still be rescued through this path alone

### `anomalyMinIntegratedSigma` (default `12.0`)

Minimum integrated significance required for the diffuse anomaly-rescue path.

- based on `DetectedObject.integratedSigma`
- helps rescue broader flashes whose energy is spread across more pixels

### `anomalyMinPeakSigmaFloor` (default `3.0`)

Minimum local peak required before the integrated-significance rescue path is allowed.

- prevents broad low-contrast mush from being rescued just because it has enough total flux
- does not affect the main sharp-glint path governed by `anomalyMinPeakSigma`

### `anomalyMinPixels` (default `15`)

Minimum footprint size required for anomaly rescue.

- protects against hot pixels and tiny defects

## 7. Interaction With The Auto-Tuner

`JTransientAutoTuner` does not read extra scoring fields from `DetectionConfig`. The tuning sweep and scoring policy are implemented as static fields and `AutoTuneProfile` presets inside `JTransientAutoTuner`.

The tuner actively optimizes or measures these `DetectionConfig` fields:

- `detectionSigmaMultiplier`
- `growSigmaMultiplier`
- `minDetectionPixels`
- `maxMaskOverlapFraction`
- `maxStarJitter`

Everything else in the returned config comes from the base config you provided.

## 8. Practical Starting Point

If you do not have strong prior knowledge of the dataset:

1. start from `new DetectionConfig()`
2. run `JTransientAutoTuner.tune(...)`
3. use the returned `optimizedConfig` with `runPipeline(...)`
4. only hand-tune fields like `enableSlowMoverDetection`, `strictExposureKinematics`, `maxJumpPixels`, or anomaly thresholds after reviewing telemetry
