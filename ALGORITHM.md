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
10. same-frame faint-streak fragment assembly
11. anomaly rescue and streak consolidation
12. common-motion filtering of unclassified observations
13. optional residual analysis: local rescue, then activity clustering
14. optional stationary-star photometry and variable-star scoring
15. output assembly and maximum-stack export

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
- common-motion filtering:
  - separated compact sources with matching displacement on at least three shared frames are set aside before local rescue and clustering; ambiguous associations are kept
- residual-transient analysis:
  - remaining unclassified non-streak point detections can be mined for weak local rescue candidates and optional broader local activity clusters

## 1. Border Drift Diagnostics

This stage measures where registration created empty borders so later detection can avoid treating edge artifacts as real objects.

Before extracting sources, `JTransientEngine` delegates border-padding measurement to `FrameDriftAnalyzer.analyze(...)`.

For each frame, `FrameDriftAnalyzer` measures how deep the alignment padding reaches from each edge:

1. It considers a pixel to be valid if its value is above a low threshold (`DRIFT_VALID_PIXEL_THRESHOLD`).
2. **Usability check.** A frame where less than half of the pixels are valid is blank or a failed registration. It is left out of the drift trajectory and of the data areas, and its sequence index is reported in `failedRegistrationFrames` (telemetry: `driftExcludedFrames`). The engine later rejects such frames at the session quality stage, even if they keep enough stars to pass the session statistics.
3. **Edge depths.** On every row of the central half of the frame height it counts the padded pixels from the left and from the right edge; on every column of the central half of the width, from the top and from the bottom. Fully padded rows and columns are skipped. Using only the central band keeps the padding of the neighbouring edges out of each measurement at the corners.
4. **Translation.** The median depth of each edge is its depth at the middle of the edge, so frames that registration also rotated (wedge-shaped padding) still give the right shift, and a few stray valid pixels in the padding do not change it:
   - `dx = median(left depth) - median(right depth)`
   - `dy = median(top depth) - median(bottom depth)`
5. **Padding extent.** The deepest padding within the central bands is the frame's padding extent.

The vectors of the usable frames are exported as `PipelineResult.driftPoints` (each point's `value` is the frame's sequence index).

The analyzer also records each usable frame's coverage (`FrameCoverage`): the first and last data pixel of every row and column, where data start at the first 3 pixels above the fill level (8 ADU). A few stray pixels inside the fill therefore don't end it, and cold pixels inside the image don't punch holes into it. From these the engine builds two areas (`DataArea`), each the intersection of the frames' coverages kept 3 px away from the fill and the image edge:

- the **quality area**, over every usable frame. A frame that alone shrinks it by 5 % of the frame or more is left out and rejected ("Shifted away from the other frames"), one at a time and at most a tenth of the frames. The sky and the quality metrics are measured here.
- the **data area**, over the frames kept by quality control (section 5). Detection, slow movers and photometry keep to it, and it is exported as `PipelineResult.dataArea` with the frames that limit it most (`PipelineTelemetry.dataAreaLimitingFrames`).

The engine never changes the caller's configuration.

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

`calculateBackgroundSigmaClipped(...)` builds a 16-bit histogram from the shifted pixel values (`pixel + 32768`) inside the quality area, so registration fill doesn't widen it, and iteratively estimates the background:

1. compute the median within the current histogram bounds
2. compute sigma around that median
3. clamp the histogram bounds to `median +/- bgClippingFactor * sigma`
4. repeat for `bgClippingIterations`

The extraction thresholds are then:

- `seedThreshold = bg.median + bg.sigma * sigmaMultiplier`
- `growThreshold = bg.median + bg.sigma * growSigmaMultiplier`

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
- `integratedSigma` (both assume uncorrelated pixel noise; registration resamples the frames, so neighbouring pixels share noise and these significances are optimistic)
- elongation from second moments
- dominant angle
- approximate `fwhm`, computed as `2.355 * sqrt(λ1 + λ2)` from the second moments: for a round star that is √2 times the Gaussian FWHM, and the footprint is also cut at the grow threshold. Session rejection (`minFwhmEnvelope`, `fwhmSigmaDeviation`) and `maxFwhmRatio` are tuned in this unit. Photometry (`ApertureMeasurer`) and the SpacePixels Night & Equipment section measure star size separately, as a true FWHM.

This is the first stage where object shape is measured. At this point, shape is primarily used to classify detections into streak-like versus point-like objects.

An object is marked as a streak only when:

- `elongation > streakMinElongation`
- blob size `>= streakMinPixels`

Otherwise it stays alive as a point-like detection.

### 3.4 Per-object filtering

The extractor keeps an object only inside a search area: every pixel of a point source, and the centroid of a streak (a trail may continue where only this frame has data). The frame pass searches the whole frame less 3 px; after quality control the objects of the kept frames are filtered by the data area, because outside it some kept frame has no data and the median stack and star mask are wrong there.

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

The sky is measured, and stars are counted and measured, only inside the quality area, so frames with a wider registration border are compared on the same sky as the others.

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

The background envelope is also never smaller than the session's median per-frame sky noise.

The star count has no absolute floor: its limit is the median minus a multiple of the MAD sigma. Since the counts are measured on the same sky in every frame (the quality area), they vary little between frames, so the limit can sit close to the median; on Africano a frame with 26 % fewer stars than the median is rejected. A relative floor was considered (code review QC-1) and left for later.

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
- no position limit: master stars are kept up to the image edge, so the star mask also covers stars cut by the frame edge

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

Both stacks measure their sky inside the data area. Maximum-stack candidates must lie inside it; median-stack stars are kept up to the image edge so the mask also covers stars that straddle the area's edge.

1. extracts connected objects from the maximum stack using:
   - `masterSlowMoverSigmaMultiplier`
   - `masterSlowMoverMinPixels`
   - stage-local `growSigmaMultiplier = masterSlowMoverGrowSigmaMultiplier`
2. extracts comparison objects from the median master stack with the same slow-mover thresholds and paints their raw pixels into an undilated boolean mask
3. rejects maximum-stack objects below `masterSlowMoverMinPixels`, outside the `slowMoverMinAxisRatio` to `slowMoverMaxAxisRatio` geometric window, or below `slowMoverMinFillFactor` when that filter is enabled
4. measures the fraction of each remaining object's raw pixels inside the median mask, rejecting values below `slowMoverMedianSupportOverlapFraction` or above `slowMoverMedianSupportMaxOverlapFraction`
5. when the original quality-filtered frames are available, searches a compact aperture inside each surviving maximum-stack footprint in every usable frame. It records the percentage of frames with significant localized signal, then the percentage of supported frame positions clustered at one location. `slowMoverMinFrameSupport` (default `40`) rejects low frame support and `slowMoverMaxStationaryLikelihood` (default `60`) rejects stationary-looking sources when their measurements are available. Values `0` and `100` disable the respective vetoes while keeping their measurements.

The geometric axis ratio uses oriented raw-pixel extents; it is distinct from intensity-weighted moment elongation. The default minimum fill factor is `0.5`; `0.0` disables that veto. The default lower mask-overlap bound is also `0.0`, so a single-frame artifact can pass it. The upper bound defaults to `0.80` and rejects footprints mostly explained by persistent sources. The stacks are not subtracted.

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

## 13. Faint Fragments And Anomaly Rescue

Before anomaly rescue, `FaintStreakFragmentGrouper.assemble(...)` joins same-frame pieces onto confirmed streaks and can discover a new single-frame line. Its switch is `enableFaintStreakFragments`, with perpendicular tolerance `faintStreakLineTolerance`.

A new line needs four pieces, a span of at least 80 pixels, no centroid gap larger than 40% of the span, and at least three stable elongated pieces whose angles agree within 25 degrees, with a strict majority of the stable pieces supporting the line. Stable pieces have at least six pixels and elongation at least 2; all candidate pieces have at least four pixels. Rounder pieces may fill gaps but cannot establish a line alone.

An existing streak can grow through connected pieces with gaps up to 80 pixels. Growth stays in the same frame and checks the angles of stable elongated pieces. The ordinary streak gates are unchanged. This discovery search is intentionally unchanged by the timing instrumentation; long fragment chains can be expensive. Its elapsed cost is `TrackerTelemetry.faintStreakFragmentTimeMs`, included in the tracking phase.


This stage gives strong untracked detections one more chance as standalone flashes.

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

The `PEAK_SIGMA` and `INTEGRATED_SIGMA` rescue checks are energy- and size-based. They do not apply a dedicated shape veto at this stage. An anomaly within six pixels of a confirmed same-frame streak axis, allowing 80 pixels past its ends, is retained with `AnomalyDetection.lowConfidence = true`. It is not automatically reclassified as a streak fragment.

A streak can break into pieces that are rescued as anomalies, for example in the frames between the two ends of a satellite trail. After anomaly rescue, the tracker therefore groups the rescued anomalies of each frame that lie on a line (at least three within 6 px) and offers each group to the final streak consolidation. A group that the consolidation merges into a confirmed streak track becomes part of that track. Every other group is dropped and its anomalies stay standalone anomalies: a group is never returned on its own. (Until October 2026 such groups were returned as "suspected streak" tracks; on noisy frames random anomalies formed them too easily.) Pieces of a faint streak next to a known streak in the same frame are absorbed earlier, by the faint-fragment assembly (start of this section).

The final returned categories are mutually exclusive:

- tracks in `TrackingResult.tracks` and `PipelineResult.tracks`
- standalone anomalies (`PEAK_SIGMA` or `INTEGRATED_SIGMA`) in `TrackingResult.anomalies` and `PipelineResult.anomalies`

### 13.3 Final streak consolidation

After anomaly rescue, the tracker runs `consolidateStreakTracks(...)` over the confirmed streak tracks, the promoted single streaks and the anomaly groups.

This pass:

1. orders confirmed streaks ahead of anomaly groups, then streaks seen in more frames, then with more points, then brighter ones
2. normalizes same-frame streak-fragment ordering along the best-fit streak axis
3. merges tracks when they pass frame-gap, angle, line-error (twice `predictionTolerance`, at least 6 px), and motion-consistency checks
4. promotes a merged track to `isStreakTrack` if it contains a confirmed streak or spans multiple frames, and drops a track made only of anomaly groups

## 14. Common-Motion Filtering

After the main tracker has assigned streaks, moving tracks and anomalies, `CommonMotionRejector.reject(trackResult.unclassifiedTransients, config)` processes the remaining observations. It runs independently of the residual-analysis switch, when `enableCommonMotionRejection` is true.

The input has one representation of each observation. Compact sources are linked in chronological order, looking up to six sequence-index steps ahead within 25 pixels. The first frame containing a possible match takes priority over a closer point in a later frame. A match must be uniquely nearest in both directions; a tied or conflicting association ends that source rather than forcing a choice. Unassigned observations can start later sources, and each observation belongs to at most one source.

A group requires two or more sources with centroids at least 30 pixels apart. Every pair's displacement relative to a shared reference frame must agree within `commonMotionShiftTolerance`, and every group member must share at least three of the same frames. Compact observations have area 1–40 pixels and elongation no greater than 2.5.

All observations of an accepted group are removed from the remaining unclassified lists. They stay in `allTransients` and in `residualTransientAnalysis.commonMotionGroups` for report review. The group's `sharedFrameCount` counts frames common to every member. Common displacement is evidence for hot pixels or registration residuals, not proof that an astrophysical source is false.

## 15. Residual Transient Analysis

This final mining stage looks through leftover point detections for weak local patterns that were not strong enough to become normal tracks or anomalies.

After common-motion filtering, `JTransientEngine.runPipeline(...)` runs:

```java
ResidualTransientAnalyzer.analyze(trackResult.unclassifiedTransients, config)
```

This stage only operates when `enableResidualTransientAnalysis` is true. It looks only at the remaining unclassified non-streak point detections. Common-motion observations have already been removed, so they cannot be rescued again or cause a partly affected cluster to be discarded.

The residual analyzer can export:

- `localRescueCandidates`, when `enableLocalRescueCandidates` is true
- `localActivityClusters`, when `enableLocalActivityClusters` is true

Local rescue candidates are ranked weak local patterns classified as:

- `MICRO_DRIFT`
- `SPARSE_LOCAL_DRIFT`
- `LOCAL_REPEAT`

Accepted local rescue candidates consume their detections before optional local activity clustering runs. Local activity clusters then group remaining nearby detections by `localActivityClusterRadiusPixels` and require at least `localActivityClusterMinFrames` unique frames.

The result is exported as `PipelineResult.residualTransientAnalysis`; it does not add entries to `PipelineResult.tracks` or `PipelineResult.anomalies`.

## 16. Variable-Star Photometry

When `enableVariableStarDetection` is true, stationary master stars are measured on the quality-filtered frames. This uses the master mask, frame detections, accepted tracks and slow-mover candidates for contamination flags; it does not mine the residual common-motion lists. After the initial ensemble solve, spatially uneven frames are excluded and the ensemble is recomputed before response check D. Readiness then determines whether variability can be scored. See [VariableStarAlgorithm.md](VariableStarAlgorithm.md).

## 17. Output Assembly

This stage gathers every accepted product of the run into `PipelineResult` for callers and UIs.

At the end of the run, the engine assembles:

- confirmed tracks
- standalone anomalies
- pipeline telemetry
- tracker telemetry
- median master stack
- master-star detections
- maximum-stack slow-mover candidates and median-source mask
- merged per-frame transients
- common-motion groups and residual-transient analysis
- stationary-star photometry and its telemetry
- master veto mask
- drift vectors

The main UI-facing tracking outputs are therefore:

- `PipelineResult.tracks`
- `PipelineResult.anomalies`
- `PipelineResult.residualTransientAnalysis`

The engine exports `maximumStackData` for visualization or downstream analysis. When slow-mover detection is enabled, the detector reuses this same array.

## 18. Runtime Diagnostics

`PipelineTelemetry.stageTimeMs` is an insertion-ordered map from `PipelineTelemetry.Stage` to elapsed wall-clock milliseconds. The major phases are drift/padding, parallel extraction and frame measurements, quality filtering, the median master/star map, maximum stacking, optional slow movers, tracking, optional common-motion filtering, optional residual analysis, and optional photometry. Disabled phases are absent; a completed short phase may record zero milliseconds.

`processingTimeMs` covers the entire engine run, including residual analysis and photometry. Durations use `System.nanoTime()`. Parallel phases measure elapsed time, not the sum of worker CPU time. The total excludes application image loading, Auto-Tune and report generation, and includes small orchestration overhead beyond the recorded phases. With a provided median stack, master-reference timing covers its reuse and star extraction, not construction.

`trackerTelemetry.faintStreakFragmentTimeMs` measures faint-fragment assembly within tracking; it is already included in the tracking time and must not be added to the total. It is -1 when the pass did not run. `generateReport()` prints the timing breakdown and this substep.

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
8. set aside reliable compact common-motion groups before optional residual analysis
9. mine the remaining unclassified points for weaker local rescue and activity patterns

That layering is what lets `runPipeline(...)` handle slow point-like movers, fast streaks, faint same-frame streak fragments, and one-frame flashes within the same overall engine.
