# JTransient Detection Algorithm

This document describes what the current implementation does when you call the main public entrypoints:

- `JTransientEngine.runPipeline(...)`
- `JTransientEngine.detectTransients(...)`
- `SourceExtractor.extractSources(...)`
- `JTransientAutoTuner.tune(...)`

The engine is a staged reduction pipeline. It starts from raw `short[][]` frames, removes poor-quality frames and stationary objects, then tries increasingly stricter linking strategies before exporting the remaining tracks and diagnostics.

## Entry Point Map

### `SourceExtractor.extractSources(...)`

Single-frame extraction only. Returns:

- `ExtractionResult.objects`
- `ExtractionResult.backgroundMetrics`
- `ExtractionResult.seedThreshold`
- `ExtractionResult.growThreshold`

No frame rejection, no master stack, no masking, no track linking.

### `JTransientEngine.detectTransients(...)`

Runs the same early stages as the full engine:

1. drift diagnostics
2. per-frame extraction
3. frame quality analysis and session rejection
4. master-stack generation or reuse
5. master-star extraction
6. stationary-star veto mask

It stops there and returns `FrameTransients` per retained frame.

### `JTransientEngine.runPipeline(...)`

Runs the full engine. In addition to the transient-only path it also:

1. optionally builds the slow-mover stack
2. links streaks
3. performs time-based linking when timestamps are available
4. falls back to geometric linking
5. rescues very bright single-frame anomalies
6. exports median, slow-mover, and maximum stacks

## Full Pipeline

### 1. Border Drift Diagnostics

Before extraction, `JTransientEngine` evaluates the aligned sequence for black border padding caused by dithering plus registration.

For each frame:

1. It takes an `11 x 11` sample around the image center and uses the median as a local sky reference.
2. It computes `voidThreshold = centerMedian * voidThresholdFraction`.
3. It scans inward from the middle of the left, right, top, and bottom edges until a pixel rises above that threshold.
4. It converts the padding depths into a translation vector:
   - `dx = leftPad - rightPad`
   - `dy = topPad - bottomPad`
5. It records that vector in `PipelineResult.driftPoints`.

Across the whole sequence it also keeps the maximum inward padding depth. If `maxDrift + 10` exceeds `config.voidProximityRadius`, it raises `voidProximityRadius` in place so later extraction does not accept interpolation artifacts near the alignment void.

### 2. Parallel Per-Frame Extraction

The engine sorts frames by `sequenceIndex`, then uses its worker pool to process them in parallel.

For each frame it runs two independent passes:

- `SourceExtractor.extractSources(...)`
- `FrameQualityAnalyzer.evaluateFrame(...)`

The extraction result is then annotated with:

- `sourceFrameIndex`
- `sourceFilename`
- `timestamp`
- `exposureDuration`

### 3. Source Extraction Internals

`SourceExtractor` works on a single `short[][]` image.

#### 3.1 Background model

`calculateBackgroundSigmaClipped(...)` builds a full 16-bit histogram of the shifted pixel values (`pixel + 32768`) and iteratively estimates the background:

1. compute median within the current histogram bounds
2. compute sigma around that median
3. narrow the valid histogram range to `median +/- bgClippingFactor * sigma`
4. repeat for `bgClippingIterations`

The extractor then computes:

- `seedThreshold = bg.median + bg.sigma * sigmaMultiplier`
- `growThreshold = bg.median + bg.sigma * config.growSigmaMultiplier`
- `voidValueThreshold = bg.median * config.voidThresholdFraction`

#### 3.2 Blob growth

The image is scanned pixel by pixel.

When a pixel is above `seedThreshold` and has not been visited:

1. it becomes a new seed
2. a breadth-first search grows the blob in 8 directions
3. neighbors are absorbed while they stay above `growThreshold`

This is a classic hysteresis detector: the seed threshold is strict, but growth is more permissive.

#### 3.3 Shape analysis

If the blob contains at least `minPixels`, `analyzeShape(...)` computes:

- centroid from intensity-weighted moments
- integrated flux
- `peakSigma`
- elongation from the eigenvalues of the second-moment matrix
- dominant angle
- approximate `fwhm`

The object is flagged as a streak only when both conditions hold:

- `elongation > streakMinElongation`
- `blob.size() >= streakMinPixels`

#### 3.4 Extraction filters

After shape analysis the extractor applies:

1. size rejection: blobs smaller than `minPixels` are dropped before analysis
2. physical-edge rejection: non-streaks whose centroid falls inside `edgeMarginPixels` are removed
3. virtual-edge rejection: an 8-point ring test of radius `voidProximityRadius` is run around the centroid; if any test point is out of bounds or below `voidValueThreshold`, the object is removed

Surviving objects are returned together with the background metrics and thresholds.

### 4. Frame Quality Analysis

`FrameQualityAnalyzer.evaluateFrame(...)` performs a stricter extraction pass for quality statistics, using:

- `qualitySigmaMultiplier`
- `qualityMinDetectionPixels`

From those detections it records:

- `backgroundMedian`
- `backgroundNoise`
- `starCount`
- `medianEccentricity` from all non-streak detections
- `medianFWHM` from non-streak detections whose elongation is below `maxElongationForFwhm`

If no usable values exist, the analyzer returns `errorFallbackValue` for the affected metric.

### 5. Session-Level Frame Rejection

`SessionEvaluator.rejectOutlierFrames(...)` uses robust global statistics once at least `minFramesForAnalysis` frames are available.

It computes a median and a MAD-derived sigma for:

- star count
- median FWHM
- median eccentricity
- background median

Frames are rejected when they violate the configured envelopes:

- star count too low
- FWHM too high
- eccentricity too high
- absolute background deviation too large

Three absolute floors stop the envelopes from becoming unrealistically tight on very stable nights:

- `minBackgroundDeviationADU`
- `minEccentricityEnvelope`
- `minFwhmEnvelope`

Only the retained frames move on to master-stack generation and tracking.

### 6. Median Master Stack

If a `providedMasterStack` is not supplied, `MasterMapGenerator.createMedianMasterStack(...)` builds one from the retained frames.

For each pixel coordinate:

1. gather the pixel value from every retained frame
2. shift to positive integers
3. sort
4. take the lower median index `(numFrames - 1) / 2`
5. shift back to `short`

This stack is used as the stationary-scene reference.

### 7. Master-Star Extraction

The engine extracts stationary objects from the median master stack with a temporary configuration override:

- `growSigmaMultiplier = masterSigmaMultiplier`
- `edgeMarginPixels = 5`
- `voidProximityRadius = 5`

The actual extraction call is:

```java
SourceExtractor.extractSources(
        masterStackData,
        config.masterSigmaMultiplier,
        config.masterMinDetectionPixels,
        config
)
```

The overrides are then restored. The resulting `masterStars` drive the veto mask.

### 8. Optional Slow-Mover Stack

If `enableSlowMoverDetection` is `true`, the engine creates a second reference stack with `MasterMapGenerator.createSlowMoverMasterStack(...)`.

This is not a plain percentile stack. For each pixel:

1. sort the values across frames
2. compute a middle-band size from `slowMoverStackMiddleFraction`
3. take the upper end of that middle band

This retains objects that occupy a pixel in several frames while still suppressing many one-frame flashes.

The engine then:

1. extracts objects from the slow-mover stack using `masterSlowMoverSigmaMultiplier`, `masterSlowMoverMinPixels`, and a temporary `growSigmaMultiplier = masterSlowMoverGrowSigmaMultiplier`
2. extracts a comparison set from the median master stack using the same slow-mover thresholds
3. builds a boolean mask from the median-stack comparison objects
4. computes a dynamic elongation threshold:
   - `medianElongation + MAD * slowMoverBaselineMadMultiplier`
   - fallback threshold `3.0` if there are too few candidates
5. keeps only objects that:
   - exceed the dynamic elongation threshold
   - are not flagged by `SourceExtractor.isIrregularStreakShape(...)`
   - are not flagged by `SourceExtractor.isBinaryStarAnomaly(...)`
   - do not overlap the median-stack mask by more than `85%`

The survivors become `PipelineResult.slowMoverCandidates`.

### 9. Streak Separation And Fast Streak Linking

`TrackLinker.filterTransients(...)` begins by splitting every frame into:

- `validMovingStreaks`
- point-like detections

Streaks bypass the master-star veto and are linked first.

For each unmatched streak:

1. start a new streak track
2. search all later streaks for the closest valid continuation
3. require orientation agreement between the streak angles
4. establish a forward direction from the first successful jump
5. require all later jumps to stay within `angleToleranceDegrees`

A single-frame streak is only exported if its `peakSigma` is at least `singleStreakMinPeakSigma`.

### 10. Stationary-Star Veto Mask

Still inside `filterTransients(...)`, the tracker builds a boolean veto mask from `masterStars`.

For every pixel in every master-star footprint:

1. draw a circle into the mask
2. use a dilation radius of `round(maxStarJitter / 2.0)`, with a minimum of `1`

Then each point-source candidate is tested against that mask.

For a candidate object:

1. count how many footprint pixels fall on the mask
2. compute `overlapFraction = overlapCount / rawPixels.size()`
3. purge it if `overlapFraction > maxMaskOverlapFraction`

Point transients that survive this step are the inputs to the point-track linker. The export path merges them with the streaks so `PipelineResult.allTransients` contains both.

### 11. Time-Based Linking

If timestamps are present, `TrackLinker.findMovingObjects(...)` tries time-aware linking before the geometric fallback.

The algorithm:

1. proposes baseline pairs `p1 -> p2` across later frames
2. requires distance greater than `maxStarJitter`
3. applies the morphology filter `isProfileConsistent(...)`
4. optionally applies `strictExposureKinematics`
5. computes velocity `distance / deltaTime`
6. searches later frames for the best continuation using:
   - velocity difference within `currentVelocity * timeBasedVelocityTolerance + maxStarJitter / dt`
   - line error within `predictionTolerance`
   - forward direction consistency
   - morphology consistency

When `strictExposureKinematics` is enabled, the maximum allowed jump is derived from the source footprint and exposure time:

`maxAllowedJump = (((sqrt(pixelArea) + maxStarJitter) / exposureDuration) * dt * 1.5) + maxStarJitter`

Accepted candidates are ranked by length, coverage, span, line straightness, angle stability, and speed stability. The tracker then keeps only non-conflicting candidates, preferring longer and better-scored tracks.

### 12. Geometric Fallback Linking

After the time-based pass, unused point transients go through a frame-agnostic geometric linker.

The required track length is:

`minPointsRequired = max(3, ceil(numFrames / trackMinFrameRatio))`

and then capped by `absoluteMaxPointsRequired`.

For each candidate baseline `p1 -> p2`:

1. reject if the jump is below `maxStarJitter`
2. reject if the jump is above `maxJumpPixels`
3. reject if `isProfileConsistent(...)` fails
4. optionally reject via `strictExposureKinematics`
5. extend the track by searching later frames for points that satisfy:
   - jump within `maxJumpPixels`
   - line error within `predictionTolerance`
   - forward direction consistency
   - morphology consistency

After extension the track is pruned:

- any step `<= maxStarJitter` is removed as a likely stationary-star hijack

The pruned track must still meet `minPointsRequired`.

### 13. Rhythm Validation

Before a geometric track is accepted, `hasSteadyRhythm(...)` checks whether the jump distances are consistent enough to represent a real mover.

1. compute all inter-point jump distances
2. take the median jump
3. reject immediately if the median jump is below `rhythmStationaryThreshold`
4. for each jump, compare it against an integer multiple of the median jump
5. count it as consistent if the error is within `rhythmAllowedVariance * multiplier`
6. accept the track only if the ratio of consistent jumps is at least `rhythmMinConsistencyRatio`

This allows skipped frames while still enforcing roughly steady motion.

### 14. Anomaly Rescue

If `enableAnomalyRescue` is enabled, the tracker scans the surviving point transients that were not consumed by any track.

A single detection is rescued as `isAnomaly = true` when:

- `pixelArea >= anomalyMinPixels`
- `peakSigma >= anomalyMinPeakSigma`

This catches bright one-frame flashes that are too short-lived to form a multi-frame track.

### 15. Final Export Products

`runPipeline(...)` returns a `PipelineResult` containing:

- confirmed tracks
- pipeline telemetry and tracker telemetry
- median master stack
- master-star detections
- slow-mover stack and candidates
- merged per-frame transients
- boolean master mask
- drift diagnostics
- a maximum-value stack

The full engine always generates `masterMaximumStackData` at the end, even though the current code does not yet populate the reserved max-stack streak lists.

## Auto-Tuner

`JTransientAutoTuner` is a crop-based parameter search that returns an `AutoTunerResult`.

### 1. Sampling Strategy

The tuner does not sweep the full dataset. It first evaluates all frames with `FrameQualityAnalyzer`.

Frame quality is scored as:

`qualityScore = backgroundNoise * medianFWHM`

It then selects:

- up to three best frames
- plus additional median-quality frames

The goal is to avoid tuning only on perfect frames.

### 2. Crop Selection

The tuner works on interior crops instead of full frames.

Current defaults:

- border margin: `200` px
- preferred crop sizes: `1024`, then `768`
- minimum crop size: `384`
- crop locations: upper-left interior, center, lower-right interior

Each sampled frame is cropped once per region and reused throughout the sweep.

### 3. Initial Jitter Estimate

Before the main sweep, the tuner performs a rough reciprocal nearest-neighbor match on extracted stars from adjacent cropped frames.

This produces a provisional `maxStarJitter` used only during the sweep, so Phase 1 is not overly dependent on the caller's initial guess.

### 4. Phase 1 Sweep

The tuner sweeps combinations of:

- `detectionSigmaMultiplier`
- `growSigmaMultiplier` via `sigma - growDelta`
- `minDetectionPixels`
- `maxMaskOverlapFraction`

For each crop and each tested combination it:

1. builds a crop median master stack
2. extracts crop master stars with the master-stack parameters
3. builds a veto mask using the provisional jitter
4. extracts every cropped frame with the tested parameters
5. classifies non-streak detections as stable or transient by mask overlap

Combinations are rejected early when, for example:

- no master stars are found in a crop
- mask coverage becomes too large
- too few stable stars are found
- transient leakage exceeds the selected profile's limit
- total extracted object counts become obviously unreasonable

Surviving combinations are scored using:

- stable-star yield
- transient overflow penalty
- transient "sweet spot" preference
- per-crop/frame stability
- veto-mask area penalty
- parameter harshness penalty
- a low-sigma / too-small-minPixels guard

The scoring behavior comes from `AutoTuneProfile`:

- `CONSERVATIVE`
- `BALANCED`
- `AGGRESSIVE`

### 5. Phase 2 Optical Calibration

Once the best sweep result is chosen, the tuner measures the stable stars it found across all crops.

It updates:

- `maxStarJitter` from the 90th percentile of matched star displacements, scaled by a safety factor
- `maxElongationForFwhm` from median elongation plus a buffer
- `streakMinElongation` from median elongation plus a larger buffer

The tuned config also retains the winning:

- detection sigma
- grow sigma
- minimum pixels
- mask overlap fraction

### 6. Tuner Output

On success:

- `AutoTunerResult.success = true`
- `optimizedConfig` is the tuned clone
- `telemetryReport` contains a full textual summary

On failure:

- `success = false`
- `optimizedConfig` falls back to the provided base config

The tuner does not use extra scoring fields inside `DetectionConfig`. Its sweep bounds and scoring policy live in `JTransientAutoTuner` itself.
