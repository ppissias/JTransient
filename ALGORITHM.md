# JTransient Detection Algorithm

This document describes the internal phases of `JTransientEngine.runPipeline(...)`.

It does not document the public API surface in general. For that, see `PIPELINE.md`. This file is specifically about how the full detection run works once you choose the full pipeline entrypoint.

## Input Assumptions

The pipeline expects an ordered set of aligned images, and it uses timestamps when available to link motion more accurately.

The implementation expects:

- all frames to share the same dimensions
- aligned or registered monochrome frames
- pixel data as signed `short[][]`
- `sequenceIndex` to represent chronological order
- timestamps in milliseconds when time-based linking should be enabled

`runPipeline(...)` sorts frames by `sequenceIndex` before the main extraction work. If timestamps are missing, the engine still runs, but point-like mover discovery must rely on geometric linking. When timestamps are available, the time-based linker runs first and the geometric linker becomes an optional secondary pass controlled by `enableGeometricTrackLinking`.

## High-Level Flow

The pipeline measures image health, builds reference stacks, removes stationary structure, links moving detections, rescues strong leftovers, and packages the results.

The full run is organized into these major phases:

1. border drift diagnostics
2. parallel extraction and frame-quality measurement
3. session-level frame rejection
4. median master-stack generation
5. master-star extraction
6. maximum-stack generation and optional slow-mover candidate analysis
7. stationary-star veto filtering and streak linking
8. time-based point linking
9. optional geometric point linking
10. anomaly rescue, suspected streak grouping, and streak consolidation
11. residual-transient analysis
12. output assembly and maximum-stack export

## Filtering Overview

The pipeline applies different filters at different stages so bad pixels, bad frames, stationary stars, weak tracks, and leftover anomalies are handled by the stage best suited to them.

Filtering happens at several different levels of the pipeline, and not all of it is shape-based.

- extraction-level object filtering:
  - blobs below `minPixels` never reach shape analysis
  - surviving blobs get per-object measurements such as `peakSigma`, `integratedSigma`, elongation, angle, and `fwhm`
  - shape is first used here to classify an object as streak-like or point-like
  - non-streaks are filtered against physical sensor edges
  - all surviving objects are filtered against registration voids
- frame-level filtering:
  - `FrameQualityAnalyzer` computes shape-derived frame statistics such as median eccentricity and median `fwhm`
  - `SessionEvaluator` uses those statistics to reject bad frames from the run
- slow-mover candidate filtering:
  - maximum-stack footprints are filtered by geometric axis ratio, optional fill factor, exact median-stack mask overlap bounds, and optional frame-support/stationarity gates
- streak filtering:
  - streak detections are first filtered by the stationary-star veto mask
  - fast streak discovery then filters by angle consistency, directional consistency, streak time consistency, `singleStreakMinPeakSigma`, and the binary-star-like streak-shape veto for one-point track promotion
- point-track filtering:
  - point detections are first filtered by the stationary-star veto mask
  - both time-based and geometric point linking require morphology consistency
  - time-based linking can additionally reject a pair through `strictExposureKinematics`
- anomaly filtering:
  - peak-sigma and integrated-sigma rescue filter by object area and significance
- suspected streak grouping is the anomaly-stage same-frame line pass, using rescued anomaly collinearity within one frame
- residual-transient analysis:
  - leftover unclassified non-streak point detections can be mined for weak local rescue candidates and optional broader local activity clusters

## 1. Border Drift Diagnostics

This stage measures where registration created empty borders so later detection can avoid treating edge artifacts as real objects.

Before extracting sources, `JTransientEngine` delegates border-padding measurement to `FrameDriftAnalyzer.analyze(...)`.

For each frame, `FrameDriftAnalyzer` determines the valid image footprint by finding the bounding box of real image data:

1. It considers a pixel to be valid if its value is above a low threshold (`DRIFT_VALID_PIXEL_THRESHOLD`).
2. It finds the first and last rows containing a significant number of valid pixels (at least 5% of the frame width). This determines the top (`minY`) and bottom (`maxY`) of the valid area.
3. It finds the first and last columns containing a significant number of valid pixels (at least 5% of the frame height). This determines the left (`minX`) and right (`maxX`) of the valid area.
4. It calculates the padding on each side from this bounding box (e.g., `leftPadding = minX`).
5. It derives a translation vector:
   - `dx = leftPadding - rightPadding`
   - `dy = topPadding - bottomPadding`

The vectors are exported as `PipelineResult.driftPoints`.

Across the whole sequence, the analyzer also returns the maximum inward padding depth and a recommended safe `voidProximityRadius`. `JTransientEngine` applies that recommendation only if it is larger than the current config value. That keeps the measurement logic pure while preserving the existing conservative extraction behavior.

## 2. Parallel Extraction And Quality Measurement

Each retained input frame is scanned for objects and scored for quality at the same time.

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

This stage turns bright connected pixels in one image into measured objects with positions, brightness, size, and shape.

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
- `integratedSigma`
- elongation from second moments
- dominant angle
- approximate `fwhm`

This is the first stage where object shape is measured. At this point, shape is primarily used to classify detections into streak-like versus point-like objects.

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

This stage asks whether each frame looks sharp, stable, and well exposed enough to trust.

`FrameQualityAnalyzer.evaluateFrame(...)` runs a stricter extraction pass using:

- `qualitySigmaMultiplier`
- `qualityGrowSigmaMultiplier`
- `qualityMinDetectionPixels`

It computes:

- `backgroundMedian`
- `backgroundNoise`
- `starCount`
- `medianEccentricity`
- `brightStarMedianEccentricity`
- `medianFWHM`

Important detail:

- eccentricity uses all non-streak detections
- bright-star eccentricity uses only non-streak detections whose `peakSigma` clears the configured bright-star cutoff
- FWHM uses only non-streak detections whose elongation is below `qualityMaxElongationForFwhm`
- the bright-star metric is reported as unavailable if too few bright stars qualify

These are frame-quality filters, not object-track filters. Shape is used here only to decide whether an entire frame is trustworthy enough to keep.

If the analyzer cannot compute a meaningful median, it falls back to `errorFallbackValue`.

## 5. Session-Level Frame Rejection

This stage compares frames against the rest of the session and removes frames that look like outliers.

Once all frames have been measured, `SessionEvaluator.rejectOutlierFrames(...)` decides which frames remain in the run.

It computes a median and a MAD-derived sigma for:

- star count
- median FWHM
- median eccentricity
- bright-star median eccentricity, when enough frames provide a valid value
- background median

Frames are rejected when:

- star count falls too far below the session median
- FWHM rises too far above the median
- eccentricity rises too far above the median
- bright-star eccentricity rises too far above the median
- background median deviates too much from the median

Four absolute floors keep the rejection envelopes from becoming too tight:

- `minBackgroundDeviationADU`
- `minEccentricityEnvelope`
- `minBrightStarEccentricityEnvelope`
- `minFwhmEnvelope`

Rejected frames are recorded in `PipelineTelemetry.rejectedFrames`. Only the retained frames participate in stacking and tracking.

## 6. Median Master Stack

This stage builds a stable sky reference by taking the median value at every pixel across the good frames.

If the caller did not pass a `providedMasterStack`, the engine builds one from the retained frames with `MasterMapGenerator.createMedianMasterStack(...)`.

For each pixel coordinate:

1. collect the pixel value from every retained frame
2. shift values into a positive integer domain
3. sort them
4. select the lower median index `(numFrames - 1) / 2`
5. shift back to a signed `short`

This erases many transient or moving features while preserving the stationary sky.

## 7. Master-Star Extraction

In plain English, this stage finds the stationary objects in the median stack so they can be masked out later.

The engine next extracts stationary objects from the median master stack using a stage-local extraction config:

- `growSigmaMultiplier = masterSigmaMultiplier`
- `edgeMarginPixels = 5`
- `voidProximityRadius = 5`

Then it runs:

```java
SourceExtractor.extractSources(
        masterStackData,
        extractionConfig.masterSigmaMultiplier,
        extractionConfig.masterMinDetectionPixels,
        extractionConfig
)
```

The resulting `masterStars` are the stationary reference objects used for veto masking. The caller's live `DetectionConfig` is left unchanged by this stage.

## 8. Optional Slow-Mover Analysis

The engine builds one per-pixel maximum stack from the quality-filtered, aligned frames with `MasterMapGenerator.createMaximumMasterStack(...)`. It uses that stack for candidate extraction and exports the same stack as `PipelineResult.maximumStackData`. The median master stack supplies a separate mask of persistent sources. A maximum-stack footprint is a shape candidate, not a measured track or confirmation of motion.

The engine then:

1. extracts connected objects from the maximum stack using:
   - `masterSlowMoverSigmaMultiplier`
   - `masterSlowMoverMinPixels`
   - stage-local `growSigmaMultiplier = masterSlowMoverGrowSigmaMultiplier`
2. extracts comparison objects from the median master stack with the same slow-mover thresholds and paints their raw pixels into an undilated boolean mask
3. rejects maximum-stack objects below `masterSlowMoverMinPixels`, outside the `slowMoverMinAxisRatio` to `slowMoverMaxAxisRatio` geometric window, or below `slowMoverMinFillFactor` when that filter is enabled
4. measures the fraction of each remaining object's raw pixels inside the median mask, rejecting values below `slowMoverMedianSupportOverlapFraction` or above `slowMoverMedianSupportMaxOverlapFraction`
5. when the original quality-filtered frames are available, searches a compact aperture inside each surviving maximum-stack footprint in every usable frame. It records the percentage of frames with significant localized signal, then the percentage of supported frame positions clustered at one location. `slowMoverMinFrameSupport` can reject low frame support and `slowMoverMaxStationaryLikelihood` can reject stationary-looking sources; defaults `0` and `100` disable these vetoes while keeping their measurements.

The geometric axis ratio uses oriented raw-pixel extents; it is distinct from intensity-weighted moment elongation. The default minimum fill factor is `0.0`, which disables that veto. The default lower mask-overlap bound is also `0.0`, so a single-frame artifact can pass it. The upper bound defaults to `0.80` and rejects footprints mostly explained by persistent sources. The stacks are not subtracted.

The survivors are exported as `PipelineResult.slowMoverAnalysis.candidates`, with footprint-based motion estimates, per-candidate diagnostics, and aggregate telemetry. Frame support and stationary likelihood are heuristics, not temporal confirmation or calibrated probabilities; unavailable measurements do not veto candidates. The stack-only analyzer overload does not have frame evidence. `slowMoverStackData`, `slowMoverMedianVetoMask`, and `slowMoverCandidates` remain compatibility exports. The former percentile-stack and residual-footprint settings have been removed.

## 9. Stationary-Star Veto Filtering And Streak Linking

This stage removes detections that overlap known stationary stars, separates streak-like objects from point-like objects, and links compatible streak detections.

`TrackLinker.findMovingObjects(...)` starts by delegating to `TrackLinker.filterTransients(...)`.

That function performs three important tasks before point-track linking begins.

### 9.1 Streak separation

All detections are split into:

- streak-like objects
- point-like objects

This split uses the streak classification assigned earlier during extraction from elongation and blob size. No new shape measurement is performed here.

### 9.2 Stationary-star veto mask

Point detections and streak detections are both filtered against a boolean mask built from `masterStars`.

For every master-star footprint pixel, the tracker paints a disk with radius:

`round(maxStarJitter / 2.0)`, minimum `1`

Then each point-like or streak-like object is checked:

1. count how many footprint pixels touch the mask
2. compute `overlapFraction = overlapCount / rawPixels.size()`
3. purge the object if `overlapFraction > maxMaskOverlapFraction`

The surviving point detections are the inputs to point-track linking. The surviving streak detections are the inputs to fast streak linking and are also preserved in the merged transient export. The exported `allTransients` list contains the full post-veto transient population carried through tracking, while `unclassifiedTransients` contains only the detections that remain after tracks and anomalies are assigned.

### 9.3 Fast streak linking

Only post-veto streak detections are linked.

For each unmatched streak:

1. start a new streak track
2. search later streaks for the closest valid continuation, including same-frame streak fragments
3. require the streak angles to agree
4. establish a forward direction from the first valid jump
5. require later jumps to stay directionally consistent
6. require the candidate streak history to pass streak time/motion consistency

Single-frame streaks are only kept if:

- `peakSigma >= singleStreakMinPeakSigma`
- `SourceExtractor.isBinaryStarLikeStreakShape(...)` does not identify the footprint as a double-star impostor

After multi-point streak linking finishes, the unmatched single streaks are evaluated separately:

- streaks that pass both checks above become one-point `streakTracks`
- all other unmatched post-veto streaks remain preserved standalone streak detections and are merged into the exported transient list

Rejected binary-star-like streaks still increment `TrackerTelemetry.rejectedBinaryStarStreakShape`, but they are rejected only from single-streak track promotion, not from standalone export.

Streak time/motion consistency is evaluated using one centroid sample per frame. When timestamps are available, projected speeds must stay within `streakTimeConsistencyTolerance`. Without timestamps, multi-frame streak candidates use the same steady-rhythm logic as geometric point tracks.

## 10. Time-Based Point Linking

This stage uses timestamps to connect point detections that move with consistent speed, direction, and shape.

If timestamps are available, the tracker attempts time-aware linking before any optional geometric linking.

For each proposed baseline pair `p1 -> p2`:

1. require forward time flow
2. require jump distance `> maxStarJitter`
3. require morphology consistency from `isProfileConsistent(...)`
4. optionally require `strictExposureKinematics`
5. compute velocity `distance / deltaTime`

Here, morphology consistency means:

- similar `fwhm`, limited by `maxFwhmRatio`
- similar surface brightness (`totalFlux / pixelArea`), limited by `maxSurfaceBrightnessRatio`

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

## 11. Geometric Point Linking

This fallback links point detections by straight-line geometry when timestamps are missing or when geometric linking is explicitly enabled.

Unused point detections can go through a time-agnostic geometric linker.

This stage runs when either:

- timestamps are missing, in which case geometric linking is forced on because there is no time-based point-linking path
- timestamps are available and `enableGeometricTrackLinking` is `true`

If timestamps are available and `enableGeometricTrackLinking` is `false`, this stage is skipped.

The minimum track length is:

`minPointsRequired = max(3, ceil(numFrames / trackMinFrameRatio))`

and then capped by `absoluteMaxPointsRequired`.

For a candidate baseline `p1 -> p2`:

1. reject if the jump is below `maxStarJitter`
2. reject if the jump is above `maxJumpPixels`
3. reject if morphology consistency fails

As in the time-based linker, morphology consistency here compares:

- `fwhm`
- surface brightness (`totalFlux / pixelArea`)

When searching later frames, candidate points must satisfy:

- jump within `maxJumpPixels`
- line error within `predictionTolerance`
- directional consistency
- morphology consistency

The tracker keeps the best line-consistent continuation in each later frame.

## 12. Anti-Hijack Pruning And Rhythm Validation

This stage trims suspicious nearly stationary points from geometric tracks and keeps only tracks whose step pattern looks like real motion.

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

This stage gives strong untracked detections one more chance as standalone flashes or same-frame suspected streaks.

If `enableAnomalyRescue` is enabled, the tracker scans merged transient detections that were not consumed by any accepted track.

### 13.1 Peak-sigma rescue

A single detection is rescued as a `PEAK_SIGMA` anomaly when:

- `pixelArea >= anomalyMinPixels`
- `peakSigma >= anomalyMinPeakSigma`

### 13.2 Integrated-sigma rescue

A single detection is rescued as an `INTEGRATED_SIGMA` anomaly when:

- it did not already qualify as `PEAK_SIGMA`
- `pixelArea >= anomalyMinPixels`
- `pixelArea >= anomalyMinIntegratedPixels`
- `integratedSigma >= anomalyMinIntegratedSigma`
- `peakSigma >= anomalyMinPeakSigmaFloor`

These rescued anomalies are exported as standalone anomaly results through `TrackingResult.anomalies` and `PipelineResult.anomalies`, rather than as one-point tracks.

The `PEAK_SIGMA` and `INTEGRATED_SIGMA` rescue checks are energy- and size-based. They do not apply a dedicated shape veto at this stage.

### 13.3 Suspected streak grouping

After anomaly rescue, the tracker runs a same-frame grouping pass over the rescued anomalies from one frame.

Any rescued anomalies from the same frame that form a collinear group can be promoted into a suspected streak track inside the returned `tracks` list.

The grouping pass only links against other rescued anomalies from the same frame. It does not search nearby orphan blobs. The final grouped line must still fit within `suspectedStreakLineTolerance`.

Elongation and measured blob angle are not required to seed the line. Once a same-frame line is accepted, every rescued anomaly from that frame that also falls within the same collinearity tolerance is absorbed into that returned suspected streak track.

The pass can return multiple disjoint suspected streak tracks from one frame. It repeatedly removes the accepted line and searches the remaining rescued anomalies from that frame again.

If a rescued anomaly is absorbed into a suspected streak track, it is removed from the standalone anomaly list. The final returned categories are therefore mutually exclusive:

- tracks in `TrackingResult.tracks` and `PipelineResult.tracks`
- standalone suspected same-frame streak groupings are still returned through those same `tracks` lists, flagged by `Track.isSuspectedStreakTrack`
- standalone anomalies (`PEAK_SIGMA` or `INTEGRATED_SIGMA`) in `TrackingResult.anomalies` and `PipelineResult.anomalies`

### 13.4 Final streak consolidation

After suspected streak grouping, the tracker runs `consolidateStreakTracks(...)` over confirmed streak tracks and suspected streak tracks.

This pass:

1. orders stronger confirmed streaks ahead of weaker suspected fragments
2. normalizes same-frame streak-fragment ordering along the best-fit streak axis
3. merges streak-like tracks when they pass frame-gap, angle, line-error, and motion-consistency checks
4. promotes a merged track to `isStreakTrack` if it contains a confirmed streak or spans multiple frames

This means a suspected same-frame grouping can remain as a standalone suspected track, or it can be absorbed into a confirmed streak track when the geometry and motion checks support that merge.

## 14. Residual Transient Analysis

This final mining stage looks through leftover point detections for weak local patterns that were not strong enough to become normal tracks or anomalies.

After tracking and anomaly export, `JTransientEngine.runPipeline(...)` runs:

```java
ResidualTransientAnalyzer.analyze(trackResult.unclassifiedTransients, config)
```

This stage only operates when `enableResidualTransientAnalysis` is true. It looks at leftover unclassified non-streak point detections after normal tracks and standalone anomalies have already been removed.

The residual analyzer can export:

- `localRescueCandidates`, when `enableLocalRescueCandidates` is true
- `localActivityClusters`, when `enableLocalActivityClusters` is true

Local rescue candidates are ranked weak local patterns classified as:

- `MICRO_DRIFT`
- `SPARSE_LOCAL_DRIFT`
- `LOCAL_REPEAT`

Accepted local rescue candidates consume their detections before optional local activity clustering runs. Local activity clusters then group remaining nearby detections by `localActivityClusterRadiusPixels` and require at least `localActivityClusterMinFrames` unique frames.

The result is exported as `PipelineResult.residualTransientAnalysis`; it does not add entries to `PipelineResult.tracks` or `PipelineResult.anomalies`.

## 15. Output Assembly

This stage gathers every accepted product of the run into `PipelineResult` for callers and UIs.

At the end of the run, the engine assembles:

- confirmed tracks
- standalone anomalies
- standalone or consolidated suspected streak tracks folded into the returned track list
- pipeline telemetry
- tracker telemetry
- median master stack
- master-star detections
- maximum-stack slow-mover candidates and median-source mask
- merged per-frame transients
- residual-transient analysis
- master veto mask
- drift vectors

The main UI-facing tracking outputs are therefore:

- `PipelineResult.tracks`
- `PipelineResult.anomalies`
- `PipelineResult.residualTransientAnalysis`
- `Track.isSuspectedStreakTrack` on entries inside `PipelineResult.tracks`

The engine exports `maximumStackData` for visualization or downstream analysis. When slow-mover detection is enabled, the detector reuses this same array.

## Resulting Behavior

In plain English, the algorithm starts with stricter evidence, then progressively rescues weaker but still explainable moving signals.

The full algorithm is intentionally layered:

1. detect as many plausible objects as possible
2. remove bad frames
3. learn the stationary sky from the median master stack
4. veto stationary objects
5. try the strictest track linker first
6. optionally fall back to looser geometry when timestamps are available, and require it when timestamps are missing
7. rescue strong one-frame events at the end as either peak-sigma or integrated-sigma anomalies
8. regroup collinear rescued anomalies into same-frame suspected streak tracks
9. mine leftover unclassified point detections for weaker local residual patterns

That layering is what lets `runPipeline(...)` handle slow point-like movers, fast streaks, faint same-frame streak fragments, and one-frame flashes within the same overall engine.
