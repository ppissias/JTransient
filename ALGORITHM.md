# JTransient Detection Algorithm

This document describes the internal phases of `JTransientEngine.runPipeline(...)`.

It does not document the public API surface in general. For that, see `PIPELINE.md`. This file is specifically about how the full detection run works once you choose the full pipeline entrypoint.

## Input Assumptions

The implementation expects:

- all frames to share the same dimensions
- aligned or registered monochrome frames
- pixel data as signed `short[][]`
- `sequenceIndex` to represent chronological order
- timestamps in milliseconds when time-based linking should be enabled

`runPipeline(...)` sorts frames by `sequenceIndex` before the main extraction work. If timestamps are missing, the engine still runs, but it falls back to geometric linking for point-like movers.

## High-Level Flow

The full run is organized into these major phases:

1. border drift diagnostics
2. parallel extraction and frame-quality measurement
3. session-level frame rejection
4. median master-stack generation
5. master-star extraction
6. optional slow-mover analysis
7. streak linking and stationary-star veto filtering
8. time-based point linking
9. geometric fallback point linking
10. anomaly rescue
11. output assembly and maximum-stack export

## 1. Border Drift Diagnostics

Before extracting sources, `JTransientEngine` scans the sequence for black border padding introduced by dithering plus external registration.

For each frame:

1. it samples an `11 x 11` patch around the image center
2. it uses the patch median as a local background estimate
3. it computes `voidThreshold = centerMedian * voidThresholdFraction`
4. it scans inward from the middle of the left, right, top, and bottom edges until the scan rises above that threshold
5. it derives a translation vector:
   - `dx = leftPad - rightPad`
   - `dy = topPad - bottomPad`

The vectors are exported as `PipelineResult.driftPoints`.

Across the whole sequence, the engine also tracks the maximum inward padding depth. If `maxDrift + 10` is larger than the configured `voidProximityRadius`, it raises `voidProximityRadius` in place before extraction starts. That makes the later alignment-void rejection more conservative when the dataset actually needs it.

## 2. Parallel Extraction And Quality Measurement

The engine then processes frames in parallel. Each frame goes through two independent calculations:

- `SourceExtractor.extractSources(...)`
- `FrameQualityAnalyzer.evaluateFrame(...)`

The source-extraction result is annotated with sequence metadata afterward:

- `sourceFrameIndex`
- `sourceFilename`
- `timestamp`
- `exposureDuration`

At the same time, `PipelineTelemetry.frameExtractionStats` records:

- extracted object count
- background median
- background sigma
- seed threshold
- grow threshold

## 3. Single-Frame Extraction Internals

`SourceExtractor` is the detector at the heart of the pipeline.

### 3.1 Background estimation

`calculateBackgroundSigmaClipped(...)` builds a full 16-bit histogram from shifted pixel values (`pixel + 32768`) and iteratively estimates the background:

1. compute the median within the current histogram bounds
2. compute sigma around that median
3. clamp the histogram bounds to `median +/- bgClippingFactor * sigma`
4. repeat for `bgClippingIterations`

The extraction thresholds are then:

- `seedThreshold = bg.median + bg.sigma * sigmaMultiplier`
- `growThreshold = bg.median + bg.sigma * growSigmaMultiplier`
- `voidValueThreshold = bg.median * voidThresholdFraction`

### 3.2 Hysteresis blob detection

The image is scanned pixel-by-pixel. When an unvisited pixel rises above the seed threshold:

1. it becomes a new blob seed
2. a breadth-first search grows the region in 8 directions
3. neighboring pixels are absorbed while they stay above the grow threshold

The seed threshold is strict. The grow threshold is lower, which lets the blob keep faint edges once a convincing core has been found.

### 3.3 Shape analysis

Blobs that meet the `minPixels` requirement go through `analyzeShape(...)`.

The extractor computes:

- intensity-weighted centroid
- background-subtracted total flux
- `peakSigma`
- elongation from second moments
- dominant angle
- approximate `fwhm`

An object is marked as a streak only when:

- `elongation > streakMinElongation`
- blob size `>= streakMinPixels`

Otherwise it stays alive as a point-like detection.

### 3.4 Per-object filtering

After shape analysis the extractor applies:

1. physical-edge rejection for non-streaks using `edgeMarginPixels`
2. virtual-edge rejection using an 8-point ring at `voidProximityRadius`

If any virtual-edge test point is out of bounds or falls below the void threshold, the object is rejected as an alignment-border artifact.

## 4. Frame Quality Analysis

`FrameQualityAnalyzer.evaluateFrame(...)` runs a stricter extraction pass using:

- `qualitySigmaMultiplier`
- `qualityGrowSigmaMultiplier`
- `qualityMinDetectionPixels`

It computes:

- `backgroundMedian`
- `backgroundNoise`
- `starCount`
- `medianEccentricity`
- `medianFWHM`

Important detail:

- eccentricity uses all non-streak detections
- FWHM uses only non-streak detections whose elongation is below `qualityMaxElongationForFwhm`

If the analyzer cannot compute a meaningful median, it falls back to `errorFallbackValue`.

## 5. Session-Level Frame Rejection

Once all frames have been measured, `SessionEvaluator.rejectOutlierFrames(...)` decides which frames remain in the run.

It computes a median and a MAD-derived sigma for:

- star count
- median FWHM
- median eccentricity
- background median

Frames are rejected when:

- star count falls too far below the session median
- FWHM rises too far above the median
- eccentricity rises too far above the median
- background median deviates too much from the median

Three absolute floors keep the rejection envelopes from becoming too tight:

- `minBackgroundDeviationADU`
- `minEccentricityEnvelope`
- `minFwhmEnvelope`

Rejected frames are recorded in `PipelineTelemetry.rejectedFrames`. Only the retained frames participate in stacking and tracking.

## 6. Median Master Stack

If the caller did not pass a `providedMasterStack`, the engine builds one from the retained frames with `MasterMapGenerator.createMedianMasterStack(...)`.

For each pixel coordinate:

1. collect the pixel value from every retained frame
2. shift values into a positive integer domain
3. sort them
4. select the lower median index `(numFrames - 1) / 2`
5. shift back to a signed `short`

This erases many transient or moving features while preserving the stationary sky.

## 7. Master-Star Extraction

The engine next extracts stationary objects from the median master stack. During this extraction it temporarily changes the config:

- `growSigmaMultiplier = masterSigmaMultiplier`
- `edgeMarginPixels = 5`
- `voidProximityRadius = 5`

Then it runs:

```java
SourceExtractor.extractSources(
        masterStackData,
        config.masterSigmaMultiplier,
        config.masterMinDetectionPixels,
        config
)
```

The resulting `masterStars` are the stationary reference objects used for veto masking. After extraction, the temporary config changes are restored.

## 8. Optional Slow-Mover Analysis

If `enableSlowMoverDetection` is true, the engine generates a second reference stack with `MasterMapGenerator.createSlowMoverMasterStack(...)`.

This stack is built by:

1. sorting the per-pixel values across frames
2. computing a band size from `slowMoverStackMiddleFraction`
3. selecting the upper end of that middle band

This favors objects that persist in roughly the same area across several frames while suppressing many one-frame flashes.

The engine then:

1. extracts raw candidates from the slow-mover stack using:
   - `masterSlowMoverSigmaMultiplier`
   - `masterSlowMoverMinPixels`
   - temporary `growSigmaMultiplier = masterSlowMoverGrowSigmaMultiplier`
2. extracts comparison objects from the median master stack with the same slow-mover thresholds
3. builds a boolean mask from the median-stack comparison objects
4. computes a dynamic elongation threshold:
   - `medianElongation + MAD * slowMoverBaselineMadMultiplier`
   - fallback `3.0` if too few objects are available
5. rejects candidates that:
   - fail the elongation threshold
   - fail `SourceExtractor.isIrregularStreakShape(...)`
   - fail `SourceExtractor.isBinaryStarAnomaly(...)`
   - overlap the median-stack mask by more than `85%`

The survivors are exported as `PipelineResult.slowMoverCandidates`, along with slow-mover telemetry.

## 9. Streak Linking And Stationary-Star Veto Filtering

`TrackLinker.findMovingObjects(...)` starts by delegating to `TrackLinker.filterTransients(...)`.

That function performs three important tasks before point-track linking begins.

### 9.1 Streak separation

All detections are split into:

- streak-like objects
- point-like objects

### 9.2 Fast streak linking

Streaks bypass the stationary-star veto and are linked first.

For each unmatched streak:

1. start a new streak track
2. search later streaks for the closest valid continuation
3. require the streak angles to agree
4. establish a forward direction from the first valid jump
5. require later jumps to stay directionally consistent

Single-frame streaks are only kept if `peakSigma >= singleStreakMinPeakSigma`.

### 9.3 Stationary-star veto mask

Point detections are filtered against a boolean mask built from `masterStars`.

For every master-star footprint pixel, the tracker paints a disk with radius:

`round(maxStarJitter / 2.0)`, minimum `1`

Then each point-like object is checked:

1. count how many footprint pixels touch the mask
2. compute `overlapFraction = overlapCount / rawPixels.size()`
3. purge the object if `overlapFraction > maxMaskOverlapFraction`

The surviving point detections are the inputs to point-track linking. The exported `allTransients` list merges those surviving points with preserved streak detections.

## 10. Time-Based Point Linking

If timestamps are available, the tracker attempts time-aware linking before the geometric fallback.

For each proposed baseline pair `p1 -> p2`:

1. require forward time flow
2. require jump distance `> maxStarJitter`
3. require morphology consistency from `isProfileConsistent(...)`
4. optionally require `strictExposureKinematics`
5. compute velocity `distance / deltaTime`

For later points, the tracker looks for the best continuation that satisfies:

- velocity difference within:
  - `currentVelocity * timeBasedVelocityTolerance`
  - plus a slack term of `maxStarJitter / dt`
- line error within `predictionTolerance`
- directional consistency
- morphology consistency

When `strictExposureKinematics` is enabled, the allowed jump is bounded from exposure time and footprint size:

`maxAllowedJump = (((sqrt(pixelArea) + maxStarJitter) / exposureDuration) * dt * 1.5) + maxStarJitter`

Candidate time-based tracks are ranked by:

- length
- frame coverage
- span
- total distance
- line straightness
- angle stability
- speed stability

The tracker then accepts the highest-ranked non-conflicting candidates first.

## 11. Geometric Fallback Point Linking

Unused point detections go through a time-agnostic geometric linker.

The minimum track length is:

`minPointsRequired = max(3, ceil(numFrames / trackMinFrameRatio))`

and then capped by `absoluteMaxPointsRequired`.

For a candidate baseline `p1 -> p2`:

1. reject if the jump is below `maxStarJitter`
2. reject if the jump is above `maxJumpPixels`
3. reject if morphology consistency fails
4. optionally reject if `strictExposureKinematics` fails

When searching later frames, candidate points must satisfy:

- jump within `maxJumpPixels`
- line error within `predictionTolerance`
- directional consistency
- morphology consistency

The tracker keeps the best line-consistent continuation in each later frame.

## 12. Anti-Hijack Pruning And Rhythm Validation

Before a geometric track is accepted, the tracker runs two cleanup checks.

### 12.1 Anti-hijack pruning

If a step in the candidate track is `<= maxStarJitter`, that point is removed. This prevents the trajectory from stalling on a background star that happened to lie on the projected line.

After pruning, the track must still satisfy `minPointsRequired`.

### 12.2 Rhythm validation

`hasSteadyRhythm(...)` then checks whether the step sizes are consistent enough to represent a real mover.

It:

1. computes all inter-point jump distances
2. takes the median jump
3. rejects the track immediately if the median jump is below `rhythmStationaryThreshold`
4. compares each jump against an integer multiple of the median jump
5. counts a jump as consistent if the error is within `rhythmAllowedVariance * multiplier`
6. requires the fraction of consistent jumps to be at least `rhythmMinConsistencyRatio`

This allows skipped frames while still rejecting erratic or mostly stationary tracks.

## 13. Anomaly Rescue

If `enableAnomalyRescue` is enabled, the tracker scans surviving point detections that were not consumed by any track.

A single detection is rescued as an anomaly when:

- `pixelArea >= anomalyMinPixels`
- `peakSigma >= anomalyMinPeakSigma`

The resulting one-point track is exported with `isAnomaly = true`.

## 14. Output Assembly

At the end of the run, the engine assembles:

- confirmed tracks
- pipeline telemetry
- tracker telemetry
- median master stack
- master-star detections
- slow-mover stack and candidates
- merged per-frame transients
- master veto mask
- drift vectors

It also generates `masterMaximumStackData` with `MasterMapGenerator.createMaximumMasterStack(...)`. This maximum stack is exported even though the current implementation does not yet populate the reserved max-stack streak lists.

## Resulting Behavior

The full algorithm is intentionally layered:

1. detect as many plausible objects as possible
2. remove bad frames
3. learn the stationary sky from the median master stack
4. veto stationary objects
5. try the strictest track linker first
6. fall back to looser geometry when timestamps are missing or insufficient
7. rescue only very strong one-frame events at the end

That layering is what lets `runPipeline(...)` handle slow point-like movers, fast streaks, and one-frame flashes within the same overall engine.
