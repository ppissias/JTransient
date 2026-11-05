# JTransient Algorithms

This is the current algorithm reference for detection, slow movers, variable-star photometry and the auto-tuner. Reviewed against the implementation on 2026-10-19. [PIPELINE.md](PIPELINE.md) owns API signatures/result contracts, [CONFIG.md](CONFIG.md) owns `DetectionConfig` fields/defaults, and [DESIGN-IMPROVEMENTS.md](DESIGN-IMPROVEMENTS.md) owns proposed work and decisions.

The [dated slow-mover investigation archive](docs/research/slow-movers/README.md) retains protocols, numerical evidence, figures and code-delivery history behind the research summaries below. Historical experiment reports are separate from this current algorithm reference.

| Topic | Sections |
|---|---|
| Inputs, registration, extraction and reference images | [Input assumptions](#input-assumptions), [pipeline flow](#high-level-flow), [coverage](#1-border-drift-diagnostics), [extraction](#3-single-frame-extraction-internals), [quality](#5-session-level-frame-rejection), [master stars](#7-master-star-extraction) |
| Slow-mover candidates | [Maximum-stack method, geometry and frame evidence](#slow-movers) |
| Tracks, instrumental patterns and residuals | [Veto/streaks/camera-fixed sources](#9-stationary-star-veto-filtering-and-streak-linking), [timed linking](#10-time-based-point-linking), [geometric linking](#11-geometric-point-linking), [anomalies](#13-faint-fragments-and-anomaly-rescue), [common motion](#14-common-motion-filtering), [residual analysis](#15-residual-transient-analysis) |
| Variable stars | [Photometry](#variable-star-photometry), [readiness](#photometry-8), [ensemble](#photometry-9), [scores and gates](#photometry-11), [limitations](#photometry-14) |
| Auto-Tune | [Calibrated tuner](#calibrated-auto-tuning) |

<!-- root-toc:start -->
## Contents

<details>
<summary>Show sections</summary>

- [Input Assumptions](#input-assumptions)
- [High-Level Flow](#high-level-flow)
- [1. Border Drift Diagnostics](#1-border-drift-diagnostics)
- [2. Parallel Extraction And Quality Measurement](#2-parallel-extraction-and-quality-measurement)
- [3. Single-Frame Extraction Internals](#3-single-frame-extraction-internals)
  - [3.1 Background estimation](#31-background-estimation)
  - [3.2 Hysteresis blob detection](#32-hysteresis-blob-detection)
  - [3.3 Shape analysis](#33-shape-analysis)
  - [3.4 Per-object filtering](#34-per-object-filtering)
- [4. Frame Quality Analysis](#4-frame-quality-analysis)
- [5. Session-Level Frame Rejection](#5-session-level-frame-rejection)
- [6. Median Master Stack](#6-median-master-stack)
- [7. Master-Star Extraction](#7-master-star-extraction)
- [8. Optional Slow-Mover Analysis](#8-optional-slow-mover-analysis)
  - [Purpose and inputs](#purpose-and-inputs)
  - [Two stacks with different roles](#two-stacks-with-different-roles)
  - [Extraction and data areas](#extraction-and-data-areas)
  - [Geometry and motion estimates](#geometry-and-motion-estimates)
  - [Candidate gates](#candidate-gates)
  - [Frame evidence](#frame-evidence)
  - [Temporal review and track correspondence](#temporal-review-and-track-correspondence)
  - [Settings and interpretation](#settings-and-interpretation)
  - [Maximum-stack investigation](#maximum-stack-investigation)
  - [Outputs and ownership](#outputs-and-ownership)
- [9. Stationary-Star Veto Filtering And Streak Linking](#9-stationary-star-veto-filtering-and-streak-linking)
  - [9.1 Streak separation](#91-streak-separation)
  - [9.2 Stationary-star veto mask](#92-stationary-star-veto-mask)
  - [9.3 Fast streak linking](#93-fast-streak-linking)
  - [9.4 Camera-fixed sources](#94-camera-fixed-sources)
- [10. Time-Based Point Linking](#10-time-based-point-linking)
- [11. Geometric Point Linking](#11-geometric-point-linking)
- [12. Anti-Hijack Pruning And Rhythm Validation](#12-anti-hijack-pruning-and-rhythm-validation)
  - [12.1 Anti-hijack pruning](#121-anti-hijack-pruning)
  - [12.2 Rhythm validation](#122-rhythm-validation)
- [13. Faint Fragments And Anomaly Rescue](#13-faint-fragments-and-anomaly-rescue)
  - [13.1 Peak-sigma rescue](#131-peak-sigma-rescue)
  - [13.2 Integrated-sigma rescue](#132-integrated-sigma-rescue)
  - [13.3 Final streak consolidation](#133-final-streak-consolidation)
  - [13.4 Anomalies on a streak's path](#134-anomalies-on-a-streaks-path)
  - [13.5 Faint-streak search](#135-faint-streak-search)
- [14. Common-Motion Filtering](#14-common-motion-filtering)
- [15. Residual Transient Analysis](#15-residual-transient-analysis)
- [16. Variable-Star Photometry](#16-variable-star-photometry)
  - [Photometry in plain English](#photometry-in-plain-english)
  - [16.1 Design Principles](#161-design-principles)
  - [16.2 Position In The Pipeline](#162-position-in-the-pipeline)
  - [16.3 Stage Overview](#163-stage-overview)
  - [16.4 Session FWHM And Saturation Level](#164-session-fwhm-and-saturation-level)
  - [16.5 Star Selection](#165-star-selection)
  - [16.6 Forced Aperture Photometry](#166-forced-aperture-photometry)
  - [16.7 Crossing Flags](#167-crossing-flags)
  - [16.8 Readiness Checks](#168-readiness-checks)
  - [16.9 Star Exclusion And The Ensemble Solve](#169-star-exclusion-and-the-ensemble-solve)
  - [16.10 Verdict](#1610-verdict)
  - [16.11 Variability Scoring](#1611-variability-scoring)
  - [16.12 Outputs](#1612-outputs)
  - [16.13 Fixed Constants](#1613-fixed-constants)
  - [16.14 Limitations](#1614-limitations)
- [17. Output Assembly](#17-output-assembly)
- [18. Runtime Diagnostics](#18-runtime-diagnostics)
- [19. Auto-Tuning](#19-auto-tuning)
  - [19.1 Calibrated tuning](#191-calibrated-tuning)
  - [19.2 Limits and intended use](#192-limits-and-intended-use)
- [20. Design Decisions And Future Work](#20-design-decisions-and-future-work)
- [21. Experimental Faint-Mover Search](#21-experimental-faint-mover-search)

</details>
<!-- root-toc:end -->

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
3. session-level frame rejection; with fewer than three retained frames the run stops here
4. median master-stack generation
5. master-star extraction
6. maximum-stack generation and optional slow-mover candidate analysis
7. optional faint-streak search: a second, streak-only extraction of the kept frames smoothed to the star size
8. stationary-star veto filtering, streak linking and camera-fixed point filtering
9. time-based point linking
10. optional geometric point linking
11. same-frame faint-streak fragment assembly
12. anomaly rescue, streak consolidation, and joining anomalies on streak paths to their streaks; then the faint-streak search's streaks join, with normal priority
13. common-motion filtering of unclassified observations
14. optional residual analysis: local rescue, then activity clustering
15. optional experimental faint-mover search ([section 21](#faint-mover-search))
16. optional stationary-star photometry and variable-star scoring
17. output assembly and maximum-stack export

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

From the same coverage, `FrameRegistration.fit` measures how registration moved each usable frame: a shift and a rotation. Registration moves the camera's rectangle of pixels and the fill marks where it no longer reaches, so the first and last data pixel of each row and column (away from the corners) lie on the rectangle's sides. A least-squares fit of the turned rectangle to these points, with points more than 2 px off dropped, gives `tx`, `ty` and `theta` (DriftAnalysisResult.frameRegistrations). Sides without fill leave their shift at 0 (the frame moved less than a pixel that way); a frame without fill keeps the camera's position; a fit worse than 2 px rms marks the frame as not usable. On the reference datasets the fits are within 0.3-1.1 px. A frame turned by 180 degrees (a meridian flip) leaves the same fill as one that is not turned, so its camera positions come out mirrored and it only matches frames of its own pier side.

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

**Too few frames.** `runPipeline` goes on to the stacks only with at least three retained frames (`JTransientEngine.MIN_KEPT_FRAMES`), the number the tracker needs for a point track. With fewer, the run stops here and returns a completed result marked `PipelineResult.tooFewFrames`: the extraction and quality-control telemetry, the drift points and the data area of the retained frames, with no stacks and no findings. `generateMasterStack` and `detectTransients` do not apply this rule, and the tracker keeps its own: it forms no tracks from fewer than three frame lists.

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

- `growSigmaMultiplier = MasterReferenceAnalyzer.effectiveMasterGrowSigma(config)`: positive `masterGrowSigmaMultiplier` capped to the master seed, otherwise the master seed
- no position limit: master stars are kept up to the image edge, so the star mask also covers stars cut by the frame edge

`MasterReferenceAnalyzer.extractStars` then finds the stars in two passes with the same grow threshold and minimum footprint:

1. on the stack with one sky level for the whole stack, as the frames are searched;
2. on a copy with the smooth sky subtracted (`SkyBackground.flatten`), seeded at 5 sigma (or the master sigma if higher) and grown as the first. An object of this pass is added when it shares no pixel with one of the first pass, so no object is listed twice and the mask covers at least what the first pass covers.

The second pass is for sky gradients. With one sky level, the noise estimate includes the gradient (5 times the real noise on M81-kumar), so the threshold is far above the sky on the dark side, where faint stars were left out of the mask and came back as hundreds of stationary leftovers in the frames (local rescue candidates, common-motion groups). The first pass stays because the frames are still searched with one sky level each: they also detect bright emission larger than a sky box, such as the nebula of klangwolke-1, and only the first pass masks it (the second pass alone left 2,000 leftovers per frame there). The second pass seeds at 5 sigma because the master sigma was chosen against a noise estimate that the gradient inflated: on the real noise it adds noise peaks (565 on Apophis, one of them on the path of Apophis itself), while a stationary source that a frame can detect stands out of the median of ten or more frames by about 5 sigma or more.

The sky map:

1. the stack is cut into boxes of about 512 px; each box's sky is the median of its pixels after clipping the stars (3 sigma, repeated);
2. a 3 x 3 median filter over the boxes removes boxes raised by a bright star or a galaxy of up to about a box across;
3. the sky at each pixel is interpolated between the box centres, continued linearly to the image edge;
4. registration fill is left out, and a box with more than a tenth of fill takes its level from its neighbours, continued linearly.

The stack itself is not changed: the report, slow movers and photometry measure on the original.

The resulting `masterStars` are the stationary reference objects used for veto masking. The caller's live `DetectionConfig` is left unchanged by this stage.

<a id="slow-movers"></a>
## 8. Optional Slow-Mover Analysis

### Purpose and inputs

This branch finds footprints that may belong to sources moving too slowly for normal frame-to-frame linking. It returns candidates, not confirmed motion. Seeing changes, elongated stars, merged neighbors, hot pixels and one-frame artifacts can mimic a suitable maximum-stack footprint.

`JTransientEngine.runPipeline(...)` supplies aligned, quality-filtered frames, their median stack and the session data area. `SlowMoverAnalyzer` also has overloads accepting maximum/median stacks directly, with optional original frames and an optional data area. Both stacks use the same signed-short representation and dimensions. Disabled or unavailable-input paths return `SlowMoverAnalysis.empty()`.

### Two stacks with different roles

- The per-pixel **maximum stack** preserves a source's swept footprint and supplies candidate connected components.
- The **median stack** supplies exact raw-pixel footprints of persistent objects. These form a separate boolean mask.

The stacks are not subtracted. Maximum-minus-median would change the footprint and introduce sensitivity to brightness/seeing differences. The slow-mover median mask is not the dilated stationary-star veto used by ordinary tracking, and does not use the two-pass `MasterReferenceAnalyzer` star list.

`MasterMapGenerator` borrows each source row once while reducing that maximum-stack row. It retains the shifted-positive integer comparison, frame reduction order, row scheduler and cancellation checks, and allocates independent output rows. This source-row caching changes no detection threshold or pixel value.

### Extraction and data areas

`SlowMoverAnalyzer` clones the supplied config and sets its grow threshold to `masterSlowMoverGrowSigmaMultiplier`. It extracts both stacks with `masterSlowMoverSigmaMultiplier` and `masterSlowMoverMinPixels`.

When an area is supplied, both stacks measure the sky there. The shared extractor requires every raw pixel of a point-classified component to be inside its search area; a streak-classified component uses its centroid instead, including in this branch. Median-mask objects use the image's normal 3 px search margin so objects crossing the session area's edge still contribute mask pixels. The mask paints their detected raw pixels without dilation. Stack-only calls without an explicit area use the extractor's default image-edge search area; they do not infer the pipeline's kept-frame intersection.

### Geometry and motion estimates

`SourceExtractor` computes intensity-weighted centroid, orientation and moment elongation. It separately projects every connected raw pixel parallel/perpendicular to that orientation. Each extent is the range of pixel-center projections plus one pixel.

```text
majorExtent = max(parallelExtent, perpendicularExtent)
minorExtent = min(parallelExtent, perpendicularExtent)
axisRatio = majorExtent / max(1, minorExtent)
fillFactor = rawPixelCount / (parallelExtent * perpendicularExtent)

estimatedMotionPixels = max(0, majorExtent - minorExtent)
estimatedMotionDiameters = estimatedMotionPixels / minorExtent  # 0 if minorExtent is not positive
```

Geometric `axisRatio` and intensity-weighted moment `elongation` describe different quantities. For an ideal capsule-shaped footprint, axis ratio minus one approximates the displacement in source diameters. These morphology estimates are separate from the descriptive original-frame centroid fit below; neither confirms a source's motion or supplies an angular velocity.

### Candidate gates

The analyzer applies these gates in order:

1. A nonempty raw footprint must meet `masterSlowMoverMinPixels`.
2. Geometric axis ratio must lie between the effective minimum and maximum.
3. Fill factor must meet the minimum.
4. The fraction of candidate raw pixels covered by the exact median mask must lie within both overlap bounds.
5. When frame evidence is available, frame support must meet its minimum and stationary likelihood must not exceed its maximum.

Effective thresholds are clamped by the analyzer and reported in telemetry: axis minimum at least 1, axis maximum at least the minimum, overlap bounds within 0–1 with upper at least lower, nonnegative fill-factor minimum, and percentages within 0–100. Passing a gate is not a physical identification.

### Frame evidence

When original retained frames are available, `SlowMoverFrameEvidence` searches for localized signal inside the candidate's maximum-stack footprint in each usable frame. The aperture radius is half the minor extent, limited to 1.5–4 px. It subtracts a local sky estimate and requires integrated SNR at least 5 for a supported position.

- **Frame support** is `100 * supported / usable` frames. It is available only with at least two usable frames.
- **Stationary likelihood** is the percentage of supported centroids in the largest cluster around one observed position. Its radius follows star jitter, limited by half the minor extent with a 0.75 px floor. It is available only when at least three supported frames span half the retained sequence.

Callers must check availability flags. Unavailable scores do not veto a candidate; numeric zero does not prove absence of signal or stationarity. A persistent star inside the footprint can dominate the position search. These are screening heuristics, not calibrated probabilities. There are no three-bin temporal stacks or motion-confirmation gates.

### Temporal review and track correspondence

`SlowMoverCandidateDiagnostics.motionEvidence` exports a copied immutable list of supported aperture positions: retained-list index, original sequence identity, capture timestamp in milliseconds, X/Y in pixels and aperture SNR. It retains no frames or pixel buffers. `SlowMoverMotionEvidence` fits X and Y independently against capture time with unweighted least squares, using relative minutes to avoid cancellation from large epoch values. It reports the drift components/magnitude, displacement over the **supported** span, two-dimensional RMS residual and signed Pearson coefficient along the footprint's moment axis. The report shows the absolute coefficient. These use the recorded capture timestamps, not an exposure-midpoint astrometric solution; differing exposures, blends, selection bias and centroid uncertainty limit interpretation.

The fit requires at least three ordered supported positions and valid, strictly increasing timestamps throughout the retained sequence. Timestamp zero is valid; missing, repeated or decreasing times have an explicit unavailable reason and never fall back to a per-frame rate disguised as px/min. Constant centroids can have a valid zero-drift fit with undefined correlation. Unavailable numeric fields are finite zeroes guarded by their availability flags. Supported/session spans and their ratio expose limited coverage; drift is not extrapolated over unsupported parts of the session. None of these values changes an acceptance gate, candidate order or configuration.

After tracks exist, `SlowMoverTrackMatcher.match(candidate.object, candidate.diagnostics.motionEvidence, track)` measures a possible correspondence without modifying either finding. It preserves the report's former spatial screen: a track point lies within `clamp(0.55 * sqrt(footprintArea/pi) + 2, 3, 10)` pixels of the exact footprint (centroid fallback if absent). This is a display screen, not a duplicate threshold. Original sequence identities pair common supported frames; repeated IDs and conflicting known capture times are excluded and counted. The returned immutable `Match` gives spatial counts/distances, common-frame count and centroid RMS/maximum separation, with explicit availability. It does not match by nearest time or extrapolated position, and an overlap alone cannot establish identity. Crossings, reversed paths, parallel/partial tracks and ambiguous IDs are covered by controls.

### Settings and interpretation

The extraction, morphology, overlap and frame-evidence controls and their engine defaults are listed once in [CONFIG.md](CONFIG.md#2-master-stack-and-slow-mover-extraction). `slowMoverMinFillFactor = 0` disables the fill veto; overlap bounds are fractions, while frame support/stationarity are percentages with availability flags. The maximum-stack sigma is not interchangeable with a per-frame sigma. Neither tuner currently tunes slow-mover extraction thresholds.

### Maximum-stack investigation

The SM-F3 review (2026-11-04) tested temporal evidence (P1), a maximum axis ratio of 5 (P2), median-subtracted extraction/evidence (P3), and row caching (P4). P4 and the non-gating temporal/correspondence review above are implemented. Temporal rejection, automatic duplicate suppression, wider default shapes and residual detection remain experiments; production gates and settings above remain unchanged.

The original proposal's 5–6 px motion window is not a general limit. Axis ratio describes a threshold-dependent footprint, not a timed displacement. In Klangwolke-2, Prokne moves about 16.1 px and passes the current gates with minor extent 11.2 px and axis ratio 2.25. A fixed 3.5 px width cannot be assumed for every source. Stationary clustering also depends on sampled centroids, their uncertainty and their distribution through time.

For independent, identically distributed Gaussian sky samples, the standardized maximum has exact CDF `P(max <= z) = Phi(z)^N`. Numerical integration gives an expectation of 2.043 sigma above sky and a median of 1.998 sigma for N = 30. The proposal's `sqrt(2 log N) = 2.608` is only a leading large-N term; normal order-statistic means require finite-sample treatment ([NIST](https://www.itl.nist.gov/div898/software/dataplot/refman2/auxillar/ordstmea.htm)). Maximum-stack extraction estimates that stack's own background/noise. These idealized values do not set a universal single-frame SNR cutoff: real backgrounds are correlated and source occupancy, seeing and footprint selection matter. Coherent integration remains the separate [faint-mover search](#faint-mover-search).

Subtracting the same static image commutes with the per-pixel maximum: `max_t(I_t - m) = max_t(I_t) - m`. It changes contrast and component geometry, but does not reduce the maximum's temporal noise selection or fix a search on original frames. The experiments therefore separate residual extraction from residual frame evidence. Residual apertures use signed, unclipped frame-minus-median values and a local ring; a scratch copy first reproduces production raw-frame support counts, usable counts, stationary scores and availability exactly for every evaluated candidate.

**Full-field comparisons.** All retained frames at native dimensions are used with the shipped SpacePixels profile, no Auto-Tune and faint search off. Each variant retains the other extraction, fill, median-overlap and support/stationarity gates. The two residual columns keep the current axis limit of 2.8. Counts are survivors to investigate, not confirmed movers or measured false positives.

| Session | Kept frames | Current | Axis limit 5 only | Residual extraction, raw evidence | Residual extraction and evidence |
|---|---:|---:|---:|---:|---:|
| Klangwolke-2 | 28 | 1 | 2 | 3 | 3 |
| Eagle-noisy | 43 | 13 | 14 | 41 | 117 |
| M81 Kumar | 39 | 0 | 0 | 0 | 108 |
| helix-reg | 39 | 4 | 4 | 12 | 15 |
| m101-reg | 106 | 0 | 0 | 0 | 0 |

Prokne is the current Klangwolke-2 survivor; raising the axis limit adds Valeria (axis ratio 2.85). Both already have ordinary tracks, with 12 and 27 measured positions respectively matching the saved predicted paths within 0.22 px. The slow-mover stage precedes tracking, so duplicate ownership needs a later association step rather than consulting a track that does not yet exist. No frozen maximum-stack variant recovers the three approximately magnitude-18.5 targets. The residual extraction produces a footprint close to Sutoku, but its fill factor 0.366 fails the retained 0.5 gate; this is not a recovered candidate.

Projecting supported centroids onto the moment axis and imposing the proposed `abs(Pearson(position, captureTime)) >= 0.70` preserves Prokne/Valeria and removes the 13 Eagle and four helix current survivors. Their removal does not establish that they are false positives. Six residual-evidence M81 candidates also have high correlation without confirmed moving identities. There are 199 deterministic time-label permutations per candidate; these diagnose temporal ordering, not a global false-alarm probability after searching many footprints. Seeing/registration trends can also correlate with time.

**Injected controls.** Three fixed 192 px tiles per session, centred at quarter/half/three-quarter field coordinates, retain every kept frame. Independent pixel copies receive a Gaussian PSF with sigma 1.25 px moving along a straight 0.35-radian path at the actual capture-time spacing. Total displacements are 2, 4, 6, 10 and 16 px at nominal background-only aperture SNR 10 and 20, giving 150 moving cases. The amplitude uses the first tile frame's noise and a 2.5 px aperture; it is not a calibrated measured SNR. Thirty stationary injections and 15 uninjected tiles supply controls. Association requires supported centroids within 2 px RMS of the known path, with at least three and at least 40% of all frames. The same input pixels and association rule apply to each variant.

| Extraction / frame evidence | Recovered, axis limit 2.8 | Recovered, axis limit 5 |
|---|---:|---:|
| Original / original | 38 / 150 | 54 / 150 |
| Residual / original | 25 / 150 | 45 / 150 |
| Residual / residual | 35 / 150 | 60 / 150 |

No variant passes an injection-associated stationary or uninjected control. None recovers a 2 px moving injection. All 38/54 original-route recoveries survive the proposed correlation limit, but two residual-route recoveries fail it (35 becomes 33, 60 becomes 58). These few positions, one PSF/direction and straight trajectories do not establish completeness or false-positive rates. Residual subtraction loses some original-route recoveries and can suppress the source itself at low displacement. It is not justified as a replacement by these results.

**Broader temporal controls.** A second frozen experiment uses the same five complete retained sequences and three fixed tiles per field, with PSF sigma 1.25/2.0 px and nominal aperture SNR 10/20. Straight motions span 4/10/16 px in three directions (-1.1, 0.35, 2.0 radians); curved motions span 10/16 px with a 3/6 px normal midpoint bow. Controls permute injected source positions among real frames/timestamps, vary stationary PSF width by 30% at approximately fixed flux, or leave the tile uninjected. Amplitude and association rules are as above; only the shape maximum is optionally widened to 5. This gives 975 cases: 780 moving and 195 controls, with no residual route in this experiment.

| Cases | Count | Current shape: associated survivors | With `abs(r) >= 0.70` | Axis limit 5: associated survivors | With `abs(r) >= 0.70` |
|---|---:|---:|---:|---:|---:|
| Straight movers | 540 | 116 | 116 | 201 | 201 |
| Curved movers | 240 | 48 | 47 | 51 | 50 |
| Shuffled source positions | 120 | 21 | 0 | 48 | 0 |
| Stationary, changing width | 60 | 0 | 0 | 0 | 0 |
| Uninjected backgrounds | 15 | 0 | 0 | 0 | 0 |

The lost curved case is in M81 Kumar: 31 supported positions cover 84% of the session, with coefficient 0.696, fit residual 1.44 px and injection-path RMS 1.67 px. Lowering a threshold just to keep this known case would tune on the validation example. High correlation remains useful inspection evidence, but these results do not justify a universal veto or calibrated confidence. The controls are a small prescribed grid rather than independent survey truth; full-field backgrounds still include unverified sources and instrumental trends. Shape relaxation remains available through the existing setting, without a new default or automatic duplicate policy.

**Held-out broader-shape validation (2026-11-04).** A third, predeclared experiment fixes the maximum geometric axis ratio at either 2.8 or 5, leaving all other extraction, fill, overlap and frame-evidence gates unchanged. The 13 reference datasets outside the five development sessions supply new backgrounds and injections; all 18 complete native fields are also evaluated. These are held-out datasets for this SM-F3 comparison, not independent survey fields: the M31 bands share a target. Every original QC-retained frame and its capture cadence are preserved, including Klangwolke-1's recorded multi-session time gap. Both configurations run explicitly on the same pixels, with alternating order for injection cases. Every baseline candidate, geometry, median mask and diagnostic is preserved exactly; only the deliberately different upper-shape threshold in its diagnostics may differ.

Two 256 px tiles per held-out dataset, at field fractions (0.29, 0.37) and (0.63, 0.71), receive copied-pixel injections at new subpixel locations. Profiles are Gaussian sigma 1.1 px and Moffat FWHM 4 px/beta 3.5, with 15% sinusoidal seeing variation at approximately fixed flux. Nominal amplitude SNR is 7.5/15, defined from the first tile frame's clipped noise and a 2.5 px aperture. Straight paths span 6/12/20/28 px in three directions; curved paths span 12/20 px with midpoint bows of 3/6 px. Gapped paths omit every fifth injection and the middle 16% of its time span without deleting native frames. Controls shuffle injected positions using three fixed new seeds in two directions, inject stationary sources with fixed/changing width, add one-frame flashes, or leave tiles unchanged. The 3,666 cases complete without saturation exclusions. Association requires active-frame centroid RMS at most 2 px, at least `max(3, ceil(0.4*N))` active positions for the full retained frame count N, and at least 80% of supported positions in active frames.

| Cases | Count | Axis limit 2.8: associated survivors | Axis limit 5: associated survivors |
|---|---:|---:|---:|
| Straight movers | 1,248 | 257 | 467 |
| Curved movers | 416 | 58 | 86 |
| Gapped movers | 416 | 56 | 117 |
| Shuffled source positions | 1,248 | 201 | 554 |
| Stationary, fixed width | 104 | 0 | 0 |
| Stationary, changing width | 104 | 0 | 0 |

Moving recovery rises from 371/2,080 (17.8%) to 670/2,080 (32.2%), with no baseline loss. A descriptive 10,000-replicate target-cluster bootstrap groups the M31 bands together: its percentile interval for the paired increase is 8.6–19.0 percentage points, conditional on this selected grid. The 104 flash cases and 26 uninjected tiles have identical total candidate loads under both limits (12 and 3 respectively); these counts include pre-existing background findings. Requiring three active frames makes flash association impossible by construction, and uninjected tiles are not certified empty. Shuffled-position survivors expose a temporal failure mode; they are not a survey false-positive calibration. The injections add no source photon noise or exposure-integrated trail, so their nominal SNR and recovery are not physical completeness estimates.

Full-field candidates rise from 36 to 42. Additions are one each in Klangwolke-1, Apophis, Eagle-noisy and Klangwolke-2, plus two in whynot; the other 13 datasets are unchanged. The added Klangwolke-2 footprint is compatible with Valeria's existing ordinary track: 27 common original frames out of 28 supported positions, RMS 0.146 px, maximum 0.302 px, no ambiguous frames or conflicting timestamps. It does not add a distinct object. Native maximum/median/source crops show trail contamination in the Klangwolke-1 and Apophis footprints, while Eagle's patchy footprint has no established moving identity. Klangwolke-1's nearby streak has ambiguous same-frame points and supplies spatial overlap only; it is not a duplicate association.

The two added whynot candidates have absolute time correlations above 0.999 but are consistent with camera-fixed artifacts. Across 59 usable fill-derived registrations, their RMS radii about the median registered position are 9.56/8.91 px; mapping the same centroids into camera coordinates reduces those radii to 0.289/0.488 px (maximum 0.913/1.195 px). The maximum registration-fit RMS is 0.322 px. This comparison is diagnostic: steady drift can be degenerate with a real sky path, so no camera-coordinate veto or physical classification is adopted. It demonstrates why correlation alone is insufficient even when a fitted path looks smooth.

**Outcome.** The wider limit offers additional injection recovery but also admits more nonchronological controls and native contamination; this review establishes no newly confirmed untracked mover. Keep the default at 2.8 and retain 5 as an existing manual setting. Before a broader default or automatic deduplication, validate candidate-local temporal/camera evidence, centroid uncertainty, coverage and competing-track ambiguity on fresh held-out cases, preserving curved/gapped movers and star crossings. All 18 accepted baseline science snapshots and maximum/median/veto-mask hashes reproduce exactly, and all 818 original image hashes remain unchanged. No production source, setting, finding or report was changed. The largest observed study process used 8.78 GiB working set; single paired stage timings are retained as resource observations, not a controlled performance comparison.

**Streak provenance and motion-confirmation follow-up (2026-11-04).** The bright streaks in the wider-shape review are real streak detections, not simulated faint movers. A native-frame audit reproduces both saved maximum and median crops exactly. Inside the measured oriented candidate rectangle, selecting max-minus-median pixels above five times the median native-frame crop noise gives 147 bright review pixels in Klangwolke-1, all contributed by original frame 2, and 92 in Apophis, all contributed by frame 5. This review region is not the exact detector footprint. The saved production streak tracks already contain the intersecting frame-2 and frame-5 footprints. Other exposures can contain nearby trails; the attribution identifies which exposure formed the bright candidate region. At the other supported aperture positions, the tracked streak footprint does not establish a continuation of that bright region.

These are spurious maximum-stack slow-mover interpretations of streak-contaminated footprints, not confirmed faint point-source paths. The slow-mover stage runs before final linking and does not apply a per-frame streak mask or record this causal association. The existing track comparison screens a track's point centroid against the candidate footprint; a long streak may overlap through its raw pixels while its centroid lies far away. Multiple streak pieces in one frame also make an ordinary one-point-per-frame comparison ambiguous. Explicit per-frame footprint contact is therefore useful provenance, but overlap is not a reason to reject an entire candidate: a real mover can cross a trail in one exposure. Nor does a streak-like maximum-stack shape by itself establish a per-frame streak identity.

A frozen scratch review then fits straight/quadratic X/Y paths to the existing supported centroids at exposure midpoints. Each of three equal-time bins is held out in turn; at least six positions, two per bin and four training points per fold are required. The lower model prediction RMS is compared with the empirical `max(2px, 2*maxStarJitter)` tolerance, with at least 2 px fitted separation across the supported span. Full-frame fill registrations separately compare camera-coordinate radius with an empirical jitter/registration scale. Missing exposures, inadequate bins, rank problems and ambiguous camera/sky histories remain explicit. No ordinal-time fallback, significance estimate, rejection gate or physical classification is introduced.

Fresh tiles/seeds on all 18 existing observations provide 4,356 predeclared cases, including exposure-integrated Gaussian/Moffat moving sources, curvature, gaps, blends, one/two-frame bright trails, shuffled positions and camera/drift ambiguity controls. Of these, 4,086 are evaluated. Whynot's missing exposure lengths exclude all 242 of its midpoint-based cases; another 28 camera-path cases leave their fixed crops and are excluded rather than repositioned. The five development sessions and other 13 datasets are reported separately. These are new cases on existing observations, not independent new observing nights.

| Cohort and associated cases | Existing maximum-stack recoveries | After hypothetical temporal-mismatch veto | Strict motion-compatible review |
|---|---:|---:|---:|
| Development movers, 840 evaluated | 224 | 209 | 207 |
| Held-out movers, 2,016 evaluated | 674 | 642 | 586 |
| Held-out shuffled positions, 384 evaluated | 239 | 23 | 2 |

The hypothetical veto preserves unavailable/ambiguous evidence, yet loses 32 held-out moving recoveries: six straight, eleven curved and fifteen gapped. Requiring a strict compatible review loses still more. The frozen rule is therefore not adopted. Its unavailable states matter on native findings too: Klangwolke-1 and Apophis lack enough positions in all three time bins, while whynot has camera-compatible positions but unavailable exposure-midpoint timing. Valeria remains compatible with timed motion; Eagle's extra candidate fails the tested trajectory models, without that failure proving a physical classification.

Most difficult positives fail before this review: none of 192 evaluated held-out blends and only one of 384 moving-plus-streak cases has an injection-associated maximum-stack recovery. This test cannot establish safe confirmation through those overlaps. Centroid lock-on, candidate construction, calibrated position uncertainty and per-frame contamination handling need further work; neither a correlation cutoff nor a blanket streak-overlap veto is justified. Local streak re-extraction/contact and a secondary review omitting contact positions are diagnostic only and can differ from full-field extraction. All production gates, defaults and findings remain unchanged.

**Candidate-local position measurements (2026-11-05).** The next frozen experiment keeps the maximum-stack proposals and the manual upper axis ratio of 5 unchanged. It compares the current strongest raw-frame aperture with a signed temporal-median residual: choose a fixed Gaussian matched peak inside the same raw-pixel footprint, then fit amplitude, X/Y and constant sky in a 9-by-9 window at fixed sigma 1.5 px. A fitted amplitude SNR of 5 supplies an alternative position; failed, displaced, low-SNR or void-contaminated fits stay unavailable. Neither the fit nor its formal covariance uses trajectory, catalogue or injection truth. Size/shape/fill/median gates are audited against production, and raw acceptance reproduces exactly for every reviewed proposal.

New tile locations and source histories provide 2,052 declared cases on the same 18 observations: 1,910 evaluated, 114 excluded for unavailable exposure-midpoint timing in whynot and 28 for camera paths leaving their fixed crops. Gaussian/Moffat profiles, exposure integration, seeing changes, curved/gapped paths, blends, one/two-frame trails, shuffled positions and stationary/camera controls are preserved. The original five development datasets and the other 13 are reported separately; these are fresh copied-pixel cases on previously used observations, not new independent observing nights. All copied tiles have zero registration-fill pixels.

The following counts require the existing history association rule on the same proposals that pass the unchanged size/shape/fill/median screens. They compare measurement histories before frame-support/stationarity rejection, **not production detection recovery**; for example, production accepts no injection-associated blend candidate in the 192 evaluated other-cohort blend cases.

| Cohort and cases | Evaluated | Raw-aperture associated histories | Residual-fit associated histories |
|---|---:|---:|---:|
| Development moving cases, including overlaps | 360 | 63 | 72 |
| Other-cohort straight movers | 288 | 159 | 157 |
| Other-cohort curved movers | 96 | 30 | 28 |
| Other-cohort gapped movers | 96 | 29 | 26 |
| Other-cohort movers crossing a stationary source | 192 | 3 | 75 |
| Other-cohort movers crossing bright trails | 192 | 0 | 0 |
| Other-cohort shuffled-position controls | 192 | 105 | 110 |

Across 864 evaluated other-cohort moving cases, associated histories rise from 221 to 286, with 91 gains and 26 losses. Eighteen of the 213 cases accepted with an associated source by the existing manual-5 configuration no longer yield an associated residual-fit history. Most gains are blends; ordinary straight, curved and gapped histories still suffer losses. On truth-covering geometry-qualified proposals, stationary-source lock positions in the blend controls fall from 3,412 to 444; total reported active positions across the moving cases also fall from 10,219 to 8,497. The methods therefore have different availability, and smaller lock counts alone do not establish uniformly better measurements. All reviewed trail-crossing components touching at least 40% of the injected path fail shape/fill qualification before measurement. Tile-local streak-pixel provenance remains diagnostic; this study provides no demonstrated safe recovery through those crossings.

The conditional covariance assumes a fixed PSF and independent constant pixel noise. Its algebra passes an exact Gaussian/noise control (381/399 positions inside the nominal 95% ellipse), but only 2,880/8,497 active positions on other-cohort truth-covering geometry proposals lie inside it (33.9%; development 2,000/5,650, 35.4%). This coverage does not select positions by their measured error. Source confusion, PSF mismatch, shared-median/correlated noise and selection are not captured by that formal covariance; it is not calibrated astrometric uncertainty. The native Prokne replay reproduces all 28 original raw centroids within 1.6e-13 px. The alternative retains 28 positions, with 0.088 px RMS against 12 common ordinary-track positions versus about 0.077 px for the raw measurements. Its median formal X precision is about 0.0018 px while its fit residual/noise ratio is about 18; the comparison establishes no astrometric superiority or trustworthy error bar. Exact expanded-shape footprints for the other reviewed native examples were not cached, so no substitute footprint is claimed as an exact replay.

**Decision.** Keep the production measurement and all gates/defaults unchanged. Residual-based source separation merits further work for blends, with the current raw evidence preserved; it is not a validated replacement or motion-confirmation rule. Candidate construction through trails, PSF/noise modelling, measurement availability and competing-source ambiguity remain prerequisites. The shuffled-control increase and the lost moving histories preclude choosing a new confidence/veto policy from this trial. All 18 application baselines and the full Klangwolke-2 faint-search run retain scientific results, stacks and existing assets; the separate known-object report presentation changes do not enter detection.

**Current-filter evidence review (2026-11-05).** A code audit of [the analyzer](src/main/java/io/github/ppissias/jtransient/core/SlowMoverAnalyzer.java), [frame evidence](src/main/java/io/github/ppissias/jtransient/core/SlowMoverFrameEvidence.java) and [shared extraction](src/main/java/io/github/ppissias/jtransient/core/SourceExtractor.java), together with the completed experiments, supplies the following assessment. This is an assessment of current behavior, not a new filter or an adopted threshold change. Engine defaults are listed below; the shipped application profile and position study use seed/grow/minimum-size values 3.5/2.75/10 instead of the engine's 4/3.5/15, and the position study explicitly uses the manual upper axis ratio of 5.

| Current selection or measurement | Evidence and present assessment |
|---|---|
| Retained frames and common data area | QC and coverage determine the input population before slow-mover extraction. They protect a valid comparison domain; the copied tiles have no registration fill and do not independently test these rules. The extractor's point/streak inclusion difference also affects edge selection. No relaxation is supported by these experiments. |
| Clipped stack background, seed/grow thresholds and minimum pixels | Both stacks share these extraction settings but have different noise distributions. Changing a threshold also changes the stationary mask and component merging, not just faint-source sensitivity. Missing proposals cannot be recovered by later temporal confirmation. Neither the current values nor their removal have an independent paired validation here. |
| Repeated analyzer size check | Its private caller receives objects whose non-null raw pixels already passed the identical extractor size threshold. This second check is redundant on that current path; removing it would be structural cleanup, with no demonstrated detection benefit. It is not evidence that the extractor's size gate is unnecessary. |
| Minimum axis ratio, 1.2 | Excludes round footprints, including possible low-displacement movers. The wider-shape experiment changed the upper bound only; it does not validate removing or lowering this bound. |
| Maximum axis ratio, 2.8 | The paired 2.8/5 comparison measures more moving recoveries and substantially more shuffled survivors/native contamination at 5. No distinct newly confirmed untracked mover establishes a default improvement. Retain the current default on this evidence. |
| Minimum fill factor, 0.5 | Rejects sparse, bent and merged footprints as well as unwanted structure. On the later manual-5 pool, all 33 truth-covering curved components rejected before measurement in the other cohort fail fill. Trail-crossing losses also precede measurement. These are reasons to investigate construction and the gate's cost, not evidence that admitting all such components would recover them safely. |
| Minimum median overlap, 0 | Inactive at the current default. A positive lower bound can reject sources absent from the median, including true movers. It is not mandatory temporal evidence; an inactive default is not a reason by itself to remove a supported profile control. |
| Maximum median overlap, 0.8 | Rejects persistent elongated sources, but can also reject movers blended with them or substantially retained in the median. The retrospective inventory below measures losses as well as control suppression; it does not establish that the stationarity gate makes this one redundant. |
| Best-aperture localization and integrated SNR at least 5 | The selected aperture may follow a stationary neighbor or noise anywhere in the footprint. Radius 1.5-4 px, local-ring sky/noise, the one-ADU noise floor, positive-flux centroid and maximization over positions all affect support. SNR here is not calibrated significance. Residual fitting improves blends but loses other histories and has overconfident formal errors; it does not justify replacing this measurement. |
| Usable-frame and evidence-availability rules | A footprint must fit the frame, have at least 16 ring samples and have less than half its pixels at the absolute fill level. Support needs two usable frames; stationarity needs three supported frames spanning half the retained index range. Unavailable evidence does not veto. The ring and partially filled apertures are not independently fill-masked, and index span is not elapsed-time coverage. These assumptions remain explicit; fill-free tiles cannot validate their behavior near padding. |
| Minimum frame support, 40% of usable frames | Rejects isolated artifacts but may lose faint/gapped signals or be dominated by a persistent star. The history association criterion already demands support on 40% of all retained frames; apparent absence of associated losses at this gate is therefore built into this particular measurement, not evidence of optimality or redundancy. |
| Maximum stationary clustering, 60% | Rejects stable positions and extended stationary sources. It shares the aperture's source-confusion and jitter assumptions and can reject a genuinely moving source with limited displacement or a star crossing. Its marginal losses below merit comparison, but its distinct stationary-source rejection has not been replaced by validated temporal evidence. |
| Temporal fit, camera-coordinate diagnostic and track correspondence | Descriptive fits/common-frame distances do not currently reject or merge these candidates. The known curved/gapped losses and camera/sky degeneracy argue against treating high correlation, straight-path prediction, camera compactness or one streak contact as a sufficient veto. |

A retrospective re-inventory of the saved 1,910 evaluated position cases counts cases with an injection-associated **raw** measurement history at each existing ordered stage. It reruns no image processing and uses the same manual-5 proposals and association definition. For the other 13 datasets:

| Source histories | Cases | After size/shape/fill | After median overlap | After existing frame/stationarity gates |
|---|---:|---:|---:|---:|
| Straight | 288 | 170 | 159 | 156 |
| Curved | 96 | 31 | 30 | 29 |
| Gapped | 96 | 30 | 29 | 28 |
| Blended with a stationary source | 192 | 5 | 3 | 0 |
| Crossing bright trails | 192 | 0 | 0 | 0 |
| Shuffled-position controls | 192 | 115 | 105 | 103 |

Among the moving cases, the median stage reduces associated histories from 236 to 221; stationarity then reduces them to 213. The analogous shuffled counts fall from 115 to 105 to 103. These are conditional counts, not independent gate-removal effects, calibrated false-positive rates or newly recovered detections. All 182 other-cohort trail components covering at least 80% of an injected path fail before measurement: 114 first fail the upper axis bound and 68 first fail fill. Footprint contact alone does not establish successful source measurement. The surviving-history association requires at least `ceil(0.4*N)` active positions among all N retained frames, which already implies the 40%-of-usable frame-support floor; this selection cannot assess that gate's missed-source cost independently. Ordered counters also hide downstream failures that might still reject a source if an earlier gate were removed.

The [digital-tracking route](#faint-mover-search) has a separate set of filters. Its current evidence supports artifact suppression but not universal calibration of each cutoff. The initial critical assessment is:

| Digital-tracking selection | Evidence and present assessment |
|---|---|
| Session/timing, motion domain, proposal grid and resource/candidate limits | Define which data and paths are searched; exclusions or truncation cannot count as clean negative results. Existing time/heap guards earn their resource role, while selection completeness remains conditional on the searched domain. |
| Residual background/noise, clipping, PSF filtering and star/fill/streak masks | Real recovery is lost in masks; neighboring signal can spread into masked centers during filtering. Structured residuals still survive. Maximum-stack failures do not quantify the cost of these different masks or justify removing them. |
| Coarse peak/refinement and final empirical score | Early proposal cuts can lose objects before later evidence is available. Existing selected-grid recovery does not calibrate score as significance or establish each proposal cut's marginal cost. |
| Half-session support and its availability rules | Suppress isolated contributions, but both halves use the shared median/processed images; they are not fully independent evidence. An untestable half can be retained when the other passes. Masked halves, cadence and gaps limit what this demonstrates. |
| Stationary and camera-fixed score comparisons | Protect against actual stationary/sensor artifacts, with real sky motion potentially degenerate with drift. The empirical camera fraction and score margins need their own recovery/control assessment; the maximum-stack camera diagnostics do not validate transferring a cutoff. |
| Peak duplicate and ordinary-track removal | Avoid repeated findings, but nearby distinct/parallel paths and limited or ambiguous track coverage remain possible failure modes. Similar distance tests in the report-only maximum-stack review do not justify changing this route's acceptance. |
| Single-frame side-lobe proximity | Rejects structured peaks from bright objects, but a real distinct mover can also cross a stronger path. That is a plausible loss mechanism requiring matched crossing controls and raw evidence, not a measured defect established by the maximum-stack trail experiment. |

**Assessment outcome.** No scientific filter removal or retuning is justified from the combined evidence yet. Construction/measurement losses and the marginal costs of fill, median overlap and stationary clustering are now explicit priorities, rather than assumptions that all existing gates are necessary or that more temporal gates are automatically better. The joint SM-F1/SM-F3 investigation and SM-F6 acceptance criteria retain the option to keep every filter if controlled comparisons support that outcome.

**Joint gate comparisons and temporal-bin trial (2026-11-05).** The next frozen mechanism study evaluates the unchanged shipped-profile baseline and 18 alternatives, including individual changes, two paired removals, and three temporal-bin policies. Two new fixed 256 px tiles per observation, centred at field fractions (0.18, 0.58) and (0.68, 0.21), preserve the original QC-retained identities, exposures and native pixels. The 2,052 declared cases yield 1,910 evaluations: 114 lack native exposure-midpoint timing and 28 camera paths leave the fixed crop. All evaluated tiles are free of fill-level pixels. The original five development observations remain separate; the other cohort has 12 evaluable observations out of its planned 13. These are new cases on existing observing sessions, not new independent nights or final validation of a selected policy.

Each tile has a blank control and fourteen histories for each Gaussian/Moffat profile and nominal aperture-amplitude SNR 6/14. They cover 4/12/24 px straight paths, curved and gapped paths, sparse illumination, stationary-source blends, two bright-trail crossings, shuffled positions, stationary round/elongated sources, single-frame flashes, deliberately withheld clocks and native camera-fixed paths. Seeing varies by 20%; seven samples integrate the actual exposure. No source photon noise is added. The baseline uses the shipped 3.5/2.75/10 extraction and axis limit 2.8. Seed, grow and minimum-pixel variants rebuild both extraction and the median mask. Every measurable predicate is evaluated even after an earlier failure, with explicit research work bounds. All independently audited baseline acceptances agree with the actual analyzer; none of its ROI components is unassessed. The four extraction pools contain 71,583 reviewed ROI components in total.

Raw-history association now requires at least three supported positions on illuminated original frames, at least 80% of all reported positions in those active frames and RMS at most 2 px, **without a fraction-of-retained-frame support requirement**. This allows the 40% support gate's cost to be measured rather than assuming it in success. It remains positional source association, not motion confirmation: a short or limited-span history can agree without resolving motion or establishing which source supplied all its flux. Continuous position/support/span evidence is retained. The following other-cohort counts compare identical cases; the first two numeric columns count cases with an associated accepted raw history, while the final two count all accepted ROI components in the named controls.

| Frozen variant | Moving histories / 864 | Shuffled histories / 96 | Flash-control ROI load / 96 cases | Blank ROI load / 24 cases |
|---|---:|---:|---:|---:|
| Current baseline | 91 | 8 | 0 | 0 |
| Remove minimum axis bound | 95 | 8 | 0 | 0 |
| Upper axis bound 5 | 148 | 27 | 0 | 0 |
| Remove upper axis bound | 166 | 27 | 0 | 0 |
| Remove fill bound | 124 | 13 | 0 | 0 |
| Remove median upper bound | 110 | 11 | 0 | 0 |
| Remove frame-support floor | 156 | 10 | 44 | 11 |
| Frame-support floor 20% | 144 | 10 | 8 | 2 |
| Remove stationary ceiling | 104 | 9 | 12 | 3 |
| Remove median upper and stationary ceiling | 130 | 13 | 143 | 36 |
| Remove upper axis and fill bounds | 234 | 38 | 4 | 1 |
| Require median lower overlap 0.1 | 58 | 1 | 0 | 0 |
| Aperture SNR floor 3 | 79 | 3 | 16 | 4 |
| Extraction seed 3 | 91 | 8 | 0 | 0 |
| Extraction grow 2 | 99 | 13 | 0 | 0 |
| Minimum pixels 5 | 97 | 8 | 0 | 0 |

These are selected-grid source histories and unverified candidate loads, not completeness or false-positive rates. Blank fields can contain native sources, and their load is a different quantity from injected-source association. In particular, much of the primary flash-control ROI load matches repeated blank-background load; those columns alone do not attribute a candidate to the injected flash. A post-hoc interpretation check applies the same positional criterion to each corresponding blank field under the same non-temporal policy: none of the other-cohort associated moving cases is also associated on that blank. This does not prove unique source attribution or motion. The frozen primary counts are unchanged by the check. Conditional 10,000-replicate target-cluster intervals group the M31 bands; they describe dependence within this grid, not independent survey truth.

The independent support comparison changes the earlier picture: gapped histories rise from 6/96 to 34/96 at 20% and 36/96 without the floor; sparse histories rise from 0/96 to 2/96 and 12/96. The accompanying flash/blank loads show support-sensitive candidate load, with attribution limits as above. This study does not cover the engine's 3-8-frame domain, so even the 20% alternative is not justified as a replacement default. Median overlap and stationary clustering are also not interchangeable: removing both raises stationary-round source associations from 1 to 19, elongated stationary associations from 0 to 16 and camera-fixed associations from 1 to 19. The current single gates hide some of each other's rejection. Conversely, removing upper-axis and fill together admits 40/96 associated trail-crossing histories, versus 14/96 when removing only upper-axis and zero when removing only fill. Construction gates can therefore obstruct a real signal before temporal review, but the additional shuffled/other load still matters.

A separately declared negative-control follow-up addresses the primary flash-attribution limitation. It preserves every primary case/result and uses the same fixed cached fields and frozen code with brighter single-frame elongated flashes at nominal round-aperture SNR 24/60. Of 180 planned cases, 170 evaluate; ten lack native midpoint timing. Flash origin requires at least one measured position in the one illuminated frame, at least 80% of the candidate's reported positions in that frame and RMS within 2 px there. This deliberately differs from the three-position moving-history association. In the other cohort's 96 bright-flash cases, removing support admits 30 flash-attributed cases, versus zero for the current floor and the 20% alternative. This directly tests a failure mode the support gate prevents; it does not establish 40% as optimal or 20% as safe. The targeted follow-up uses reviewed observations and is not independent final validation.

Lower thresholds are not uniformly better measurements. The SNR-3 aperture variant gains six moving histories and loses eighteen; lower grow gains 47 and loses 39 despite its net increase of eight. Reducing minimum pixels gains six but admits one elongated stationary control. The tested seed change gives no incremental associated moving recovery. The size check repeated inside the analyzer remains a structural duplicate of the extractor guarantee; none of these results makes the underlying extraction size criterion redundant.

For SM-F1, three equal-elapsed-time groups each need at least two known-midpoint frames. Each lower-median bin stack is measured against the full-sequence median with the existing fixed-sigma 1.5 residual Gaussian fit inside the same component footprint. The empirical compatibility review requires endpoint displacement at least `max(2px, 2*maxStarJitter)`, a middle point within that tolerance of time-linear endpoint interpolation, no segment reversal and direction within 45 degrees of the footprint's moment axis. Missing groups/clocks/fits stay unavailable; shared reference noise and the conditional fit covariance are not calibrated confidence.

Only 23 of the 91 baseline-associated other-cohort proposals have all three fitted bin positions available. An added veto that retains unavailable cases loses one moving history (91 to 90) and removes no associated shuffled history (8 to 8). The loss is an ic-5070 Moffat curved injection: raw RMS 0.802 px and bin-reference RMS 0.814 px both agree with its source history, but the straight-middle geometry veto fails. Applying that veto after removing median/stationarity still leaves 129 moving versus 13 shuffled histories, with the same stationary/camera and flash/blank contamination as the pair removal. Requiring strict available compatibility observes 28 associated moving cases but leaves 643 of 864 moving cases partly unassessed; its smaller count cannot be presented as a complete recovery or rejection measurement. Withholding capture clocks in 136 otherwise identical cases preserves every non-temporal selection while correctly disabling the bin measurement.

**Combined decision.** Do not adopt this temporal-bin veto, remove a scientific gate, or retune defaults from this study. The support percentage has a measured sensitivity cost, while its artifact rejection, median/stationarity interactions and shape/fill trade-offs rule out a blanket simplification. Smaller changes with a few gains still lack the necessary independent source/uncertainty and control evidence. SM-F1's general temporal-confirmation question remains open; this one median-residual/straight-middle prototype is not evidence that every bin or trajectory method fails. The bounded gate-comparison milestone is complete, with remaining acceptance work in SM-F1/SM-F3 and the broader SM-F6 validation.

All 818 original image hashes, 744 production/build/test source hashes, frozen artifacts and the 18 tile caches remain unchanged. Mathematical controls, baseline acceptance, selection monotonicity, aperture-SNR subset consistency and withheld-clock contrasts pass. The all-policy diagnostic workload used about 36.8 s for preparation and 282.8 s for evaluation with two JVM processors, 2 GiB preparation/1.5 GiB evaluation heap limits. A late-run sample observed at most about 579 MiB working set; it is not an all-run peak or per-policy memory comparison. These timings measure the diagnostic workload, not production overhead. Paired plots and three representative source-history/control cases were inspected without observing-site metadata.

**Stack work.** Serial isolated full-size benchmarks use one warmup and the median of three rotating-order repetitions, comparing every output pixel after each timed interval. Outside an engine operation the row scheduler runs on the caller. These figures measure stack generation, not end-to-end application speed; pipeline worker limits, memory pressure and other stages differ.

| Session | Dimensions | Frames | Original seconds | Cached-row seconds |
|---|---:|---:|---:|---:|
| Klangwolke-2 | 9544 x 6361 | 28 | 2.593 | 1.655 |
| Eagle-noisy | 1600 x 1200 | 43 | 0.156 | 0.087 |
| M81 Kumar | 6248 x 4176 | 39 | 1.360 | 0.616 |
| helix-reg | 1542 x 1116 | 39 | 0.120 | 0.073 |
| m101-reg | 2439 x 1569 | 106 | 0.866 | 0.336 |

A separate four-worker benchmark of the built production reducer matches the frozen maximum-stack hashes on all five complete sessions; Klangwolke-2 takes 0.619 s with the former reduction and 0.318 s with cached rows. All 18 reference datasets retain identical scientific results, report data, 5,310 non-HTML assets and report HTML after normalizing generation timestamps and measured durations. These comparisons use faint search off and the usual photometry batch; the five investigation runs separately used photometry on.

A loop-interchange prototype was inconsistent across dimensions (2.726 s on Klangwolke-2), so only source-row caching was adopted. Tests cover every 16-bit intensity value, rotating which frame holds each maximum, independent output ownership and unchanged empty-input results; existing worker/cancellation checks remain applicable. The subsequent temporal/correspondence delivery passes engine checks/API consumer and application checks/both API consumers (364 engine tests; 763 application tests, seven existing skips), plus 46 report JavaScript tests. Its 18 full-report comparisons preserve every old scientific field, report JSON, the same 5,310 assets and all unrelated HTML; the deliberate additions are 36 centroid CSVs/inspection panels and revised wording/common-frame metrics in four overlap notes. On Klangwolke-2, the 28 supported Prokne centroids give 0.203 px/min descriptive drift and 0.073 px fit RMS; the 12 common ordinary-track detections differ by 0.077 px RMS (0.116 px maximum). These are measured agreement/residual values, not calibrated astrometric uncertainties. Scientific follow-ups and their acceptance requirements stay in [SM-F3](FEATURE-REQUESTS.md#sm-f3-improvements-to-maximum-stack-slow-mover-detection).

### Outputs and ownership

`SlowMoverAnalysis` exports the maximum stack, exact `medianMask`, accepted `SlowMoverCandidateResult` values and `SlowMoverSummaryTelemetry`. Each candidate holds its `DetectedObject` and `SlowMoverCandidateDiagnostics`: geometry, mask overlap, estimated motion, measured frame evidence, copied temporal review positions/fit, availability flags and effective thresholds.

Telemetry includes extraction counts, per-gate rejection counters, shape/overlap distributions, frame-evidence availability and accepted-candidate summaries. Shape distributions cover size-qualified maximum objects; accepted motion summaries describe survivors. Do not interpret every telemetry list as the same population.

`PipelineResult.slowMoverAnalysis`, `slowMoverCandidates` and `telemetry.slowMoverTelemetry` expose these products. `slowMoverStackData` and `slowMoverMedianVetoMask` are compatibility aliases for the same maximum-stack/mask arrays. The candidate list is copied and unmodifiable; image/mask arrays are shared.

Slow-mover candidates are separate from point/streak tracks and rescued anomalies. Their footprints can flag nearby variable-star measurements across frames. The camera-fixed point-detection filter does not run on these stack candidates.

There are no three-bin temporal stacks or temporally confirmed slow-mover motion paths. Proposed, deferred and declined changes are tracked in [FEATURE-REQUESTS.md](FEATURE-REQUESTS.md). Geometry/branch coverage is in `SourceExtractorBlobGeometryTest` and `JTransientEngineSlowMoverTest`; descriptive fits and correspondences are covered by `SlowMoverMotionEvidenceTest` and `SlowMoverTrackMatcherTest`.

## 9. Stationary-Star Veto Filtering And Streak Linking

This stage removes detections that overlap known stationary stars, separates streak-like objects from point-like objects, and links compatible streak detections.

`TrackLinker.findMovingObjects(...)` runs the tracking stages in a fixed order (the `TrackLinker` class description lists them: veto, streak linking, camera-fixed rejection, the timed and geometric point linkers, faint streak fragments, anomaly rescue and the exports). It starts with `TrackLinker.filterTransients(...)`, which runs `TransientVeto` and `StreakLinker`.

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

The surviving point detections go through the camera-fixed check (section 9.4) and are then the inputs to point-track linking. The surviving streak detections are the inputs to fast streak linking and are also preserved in the merged transient export. The exported `allTransients` list contains the post-veto population carried through tracking after camera-fixed groups are removed, while `unclassifiedTransients` contains only the detections that remain after tracks and anomalies are assigned.

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

### 9.4 Camera-fixed sources

When `enableCameraFixedRejection` is on, `CameraFixedRejector` sets aside the surviving point detections that stay at one camera position while the sky moves: hot pixels, warm pixels and dust that the calibration left. Registration moves them with the dither and the drift, so in the registered frames they look like faint movers (a hot pixel moving with a steady drift forms a perfectly straight track), repeats at one spot or common motion.

Each detection is mapped back to camera coordinates with its frame's registration (section 1). A group is the detections of at least 3 frames, one per frame, within 2 px of the group's median camera position, whose positions in the frames spread over at least 4 px; with less, the frames did not move enough to tell a camera position from a position on the sky. Frames whose registration is not usable are left out, and a dataset without registration fill is left as it is.

A slow mover can also stay within 2 px on the camera when the frames move little between exposures. A group of at least 4 frames is therefore tested for motion on the sky. A straight line against time (capture times, or frame order when a member has none) is fitted to its positions twice: in camera coordinates and in the registered frames. A hot pixel fits tightly on the camera and jumps with the dither in the frames; a mover does the opposite. When the registered fit's rms per axis is below 1 px and below half the camera fit's, the group follows the sky: its detections stay for linking, and the telemetry counts it (`cameraFixedGroupsFollowingSky`, `cameraFixedDetectionsFollowingSky`). When both fit tightly, as on frames that drift steadily without dither, the geometry cannot tell a hot pixel from a mover moving with the drift, and the group stays camera-fixed. The camera positions come from the fill fit and drift by up to 3 px over a session on Eagle-noisy, so testing for motion on the camera alone would release hot pixels.

The group's detections are removed from the point detections and from `allTransients`, so they become no tracks, anomalies, common-motion groups or residual candidates. The groups are exported as `PipelineResult.cameraFixedGroups` (tracker telemetry: `cameraFixedGroups`, `cameraFixedDetections`). On the reference data: both whynot tracks were hot pixels moving with the drift (0.7-1.8 px apart on the camera, while every real track spreads 21 px or more); Eagle-noisy keeps 7 of its 74 tracks (each of the 67 others contained camera-fixed detections, 55 nothing else); on M81-kumar the local rescue candidates fall from 84 to 10, the others being hot pixels that followed the dither between sets of frames. The recorded regression also added two camera-fixed groups on klangwolke-2 without changing its other findings; 16 of the 19 datasets were identical. These are historical measurements with the recorded profiles, not a new benchmark.

Those two groups were detections of the asteroid 611 Valeria (0.27 px/min). With the sky-motion test (2026-11-03) they join its track, which grows from 16 to 27 frames; the other 17 reference datasets are unchanged with the shipped profile. With the Maximum Auto-Tune configuration on klangwolke-2, the test releases one of 76 groups: 9 detections of 4767 Sutoku (0.2 px/min). Six join Sutoku's track (14 to 20 frames) and three remain a local rescue candidate on its path. In the same measurement, Eagle-noisy's 90 groups had a median rms of 0.33 px on the camera and 10.5 px in the frames, and whynot's two drifting hot pixels 0.20–0.27 px and 0.28–0.54 px.

## 10. Time-Based Point Linking

This stage uses timestamps to connect point detections that move with consistent speed, direction, and shape.

If timestamps are available, the tracker attempts time-aware linking before any optional geometric linking.

For each proposed baseline pair `p1 -> p2`:

1. require forward time flow
2. require jump distance `>= maxStarJitter`
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

Growth proceeds only after the second seed point; it does not backfill the interval between the two seeds. The local direction test also remains active for subpixel steps. In the Lagoon review, the accepted track seeds are original frames 2 and 5, leaving frames 3 and 4 before its growth interval. Six further detections make 0.15–0.24 px steps whose directions differ by 94–130 degrees from the track direction, exceeding the dynamically relaxed 83–86 degree allowances despite passing velocity, line and profile checks. They remain available to residual rescue. SpacePixels can now show report-only possible-continuation links for these separate observations; it does not change this linking policy, assign identity or merge either finding.

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

The `PEAK_SIGMA` and `INTEGRATED_SIGMA` rescue checks are energy- and size-based. They do not apply a dedicated shape veto at this stage. Anomalies on the path of a confirmed streak are joined to that streak after the consolidation ([13.4](#134-anomalies-on-a-streaks-path)).

A streak can break into pieces that are rescued as anomalies, for example in the frames between the two ends of a satellite trail. After anomaly rescue, the tracker therefore groups the rescued anomalies of each frame that lie on a line (at least three within 6 px) and offers each group to the final streak consolidation. A group that the consolidation merges into a confirmed streak track becomes part of that track. Every other group is dropped and its anomalies stay standalone anomalies: a group is never returned on its own. (Until October 2026 such groups were returned as "suspected streak" tracks; on noisy frames random anomalies formed them too easily.) Pieces of a faint streak next to a known streak in the same frame are absorbed earlier, by the faint-fragment assembly (start of this section).

The final returned categories are mutually exclusive:

- tracks in `TrackingResult.tracks` and `PipelineResult.tracks`
- standalone anomalies (`PEAK_SIGMA` or `INTEGRATED_SIGMA`) in `TrackingResult.anomalies` and `PipelineResult.anomalies`

### 13.3 Final streak consolidation

After anomaly rescue, the tracker runs `StreakConsolidator.consolidateStreakTracks(...)` over the confirmed streak tracks, the promoted single streaks and the anomaly groups.

This pass:

1. orders confirmed streaks ahead of anomaly groups, then streaks seen in more frames, then with more points, then brighter ones
2. normalizes same-frame streak-fragment ordering along the best-fit streak axis
3. merges tracks when they pass frame-gap, angle, line-error (twice `predictionTolerance`, at least 6 px), and motion-consistency checks
4. promotes a merged track to `isStreakTrack` if it contains a confirmed streak or spans multiple frames, and drops a track made only of anomaly groups

### 13.4 Anomalies on a streak's path

A faint streak crossing a star changes the star's shape, and a tumbling satellite flashes along its path. Both are rescued as anomalies, although they are pieces of a streak. After the consolidation, `FaintStreakFragmentGrouper.joinAnomaliesOnStreakPaths(...)` therefore joins each remaining anomaly that lies on a confirmed streak's path to that streak. It is then no longer an anomaly.

- **In a frame with parts of the streak**, the anomaly lies on that frame's line, at most 80 px past the parts.
- **A streak seen in several frames** also takes an anomaly where the streak's motion puts it in the anomaly's frame. Positions are measured along the streak's line, oriented by its direction of motion. The anomaly must lie between the trails of the nearest frames before and after it, within 80 px. Without a frame on one side, the bound is one frame's motion past the other side's trail, from the median speed between the streak's frames. The frame may be one the streak skipped, or the frame just before or after it. The line is drawn through the streak's own parts in the two nearest other frames and in the anomaly's frame, because a path across the whole image bends a little.
- **On a line** means the anomaly's centre is within 6 px of it, or one of its pixels within 2 px. A streak through a star pulls the centre off the line.
- **Repeated joining.** Joining repeats until nothing more joins, so a filled frame can place the next one. Joined anomalies count where the streak was in their frame. They never draw a frame's line, because a round anomaly has no reliable direction.

Time is the frames' timestamps when every frame has one, otherwise the frame index. Until October 2026 an anomaly on a same-frame streak stayed an anomaly, marked low confidence; anomalies in frames the streak skipped were not recognised.

On the reference data the joining took 17 of the 39 anomalies of klangwolke-2 (24 of 40 with a second profile) and one of the six of klangwolke-1; the other datasets were unchanged, and no track was added or lost. The joined pieces were mostly a satellite's flashes, in frames where its trail was otherwise missing, and a trail crossing stars. Photometry flags the stars a track crosses, so a few stars lose their measurement in those frames. These are measurements with the recorded profiles, not a benchmark.

### 13.5 Faint-streak search

A trail whose pixels are only 1–2 sigma above the sky never gives a seed in the normal extraction, although the eye sees it along its length. With `enableFaintStreakSearch` on (the default), `FaintStreakSearch` extracts the kept frames a second time on copies smoothed with a Gaussian the size of the stars, and `FaintStreakLinking` adds the streaks it finds to the streak tracks. Smoothing with the star profile (a matched filter) lowers the pixel noise by the sum of the squared one-dimensional kernel weights (about a factor 4 for a sigma of 1.25 px) but a trail's cross-section much less, so the trail rises above the seed threshold. A non-linear stretch such as asinh does not do this: the extraction measures the noise again on the stretched frame, and the stretch compresses everything above the sky relative to that noise.

**The search** runs after the master reference and the slow movers, before tracking, on the frames quality control kept. It does not run quality control again and changes nothing of the normal pass.

- **Width.** Sigma is the median `medianFWHM` of the kept frames with a genuine measurement (finite, positive, below `errorFallbackValue`, from at least 5 stars), divided by √2 (the extraction FWHM convention of EX-3) and by 2.355; 1.0 px without any measurement; bounded to 0.5–3 px. The kernel reaches 3 sigma. The extraction FWHM is cut at the grow threshold, so on Integral-spacecraft-challenge the rule gave 1.01 px against 1.2 px from the stars' flux and peak. Synthetic trails injected into real frames were recovered about as well at the rule's width as at 1.25 or 1.5 times it (better on m81-reg, worse on Integral), so the rule stays.
- **Smoothing.** A normalised convolution over image pixels: registration fill neither enters the sky nor changes, and an image pixel never drops to the fill level. Where the smoothed noise would be below 4 ADU, the copy is stretched about the sky before rounding to 16 bits, and each candidate's flux and footprint values are scaled back, so the extraction's integer histogram keeps the noise. Rows are smoothed in bands of 256; each worker holds one frame's smoothed copy.
- **Extraction.** The normal extraction with the run's thresholds, on the smoothed copy. The sky clipping runs 20 passes instead of `bgClippingIterations`: smoothing narrows the noise far more than the star halos, and on a synthetic field 3 passes gave twice the true noise of the smoothed frame (5 or more gave the true value). Blobs smaller than `streakMinPixels` are not analysed. Only streak-shaped objects (`streakMinElongation`, `streakMinPixels`) whose centroid lies inside the data area shrunk by the kernel radius are kept.
- **Star mask.** The median stack is smoothed the same way and its stars extracted with the master settings; a candidate overlapping that mask more than `maxMaskOverlapFraction` is removed, because smoothing reveals fainter stars and joins close pairs.
- Every candidate is marked `DetectedObject.fromFaintStreakSearch` and keeps its frame's sequence index, file name, timestamp and exposure. All its measurements are those of the smoothed frame; its `peakSigma` and `integratedSigma` are in the smoothed frame's noise units and are not comparable with normal detections (XC-1 unchanged).

**Normal priority.** The candidates join after the normal streaks have been linked and consolidated and their anomalies joined ([13.3](#133-final-streak-consolidation), [13.4](#134-anomalies-on-a-streaks-path)), exactly as without the search:

1. A candidate that shares footprint pixels with a normal streak detection of its frame, at an angle within `angleToleranceDegrees`, measures the same piece of trail: it is dropped and the normal measurement kept. A candidate crossing a normal streak at another angle stays.
2. The other candidates are linked among themselves ([9.3](#93-fast-streak-linking)). An unlinked candidate becomes a single-frame streak without a peak gate (no `singleStreakMinPeakSigma`); the binary-star shape screen applies as for normal singles. A chain of two or more pieces needs no peak gate, as for normal streaks.
3. `StreakConsolidator.addStreakTracks` offers the resulting tracks to the consolidated tracks: each merges into the track it fits best or stays on its own, a track that took one may then merge with another it now fits, and tracks without faint pieces are never offered to each other again. A normal streak detection therefore stays in the track it has without the search; tracks only gain pieces or merge.
4. Rescued anomalies on the path of a track with faint pieces join it ([13.4](#134-anomalies-on-a-streaks-path)) and leave the anomaly list.
5. A candidate in no final track is discarded. The search's objects never reach point linking, camera-fixed rejection, fragment assembly, anomaly rescue, `allTransients`, `unclassifiedTransients`, common motion or residual analysis. Photometry flags the stars that any final track crosses, faint pieces included: a trail across a star contaminates its measurement whichever pass found it.

Faint pieces that are not streak-shaped (a faint trail broken into round pieces) are not used. `PipelineTelemetry.faintStreakSearchTelemetry` records the width, its source and the counts; `Stage.FAINT_STREAK_SEARCH` its time. `inspectTransients` and `redetectFrame` do not run the search.

**Measurements (2026-11-01, default profile, the 18 reference datasets and Integral-spacecraft-challenge).**

- With the switch off, every report matched the base apart from the new configuration line and timings.
- With it on, no normal streak observation left its track on any dataset (checked per detection on the engine results); point detections and point tracks were unchanged and anomalies only joined streaks. The Integral trail (1.3–1.5 sigma per pixel) became a streak track over frames 4, 5, 7 and 10. On 7 other datasets 23 streak findings were new or extended; each was reviewed with its pictures and is a real trail, among them a faint satellite in klangwolke-2 frames 20, 23 and 24 that is absent at the same place in frame 22.
- Photometry readiness, magnitudes and candidates were unchanged on dc-reg, m81-reg and M81-kumar; on m81-reg 44 light curves changed only which flags were set.
- Single-frame gate: on the 775 kept frames and on their negated difference frames (`2 × median − frame`), the smoothed frames' noise formed no streak-shaped object; all 9 single and 14 multi-piece detections on the negated frames were real structures that are dark in one frame (dark lines, registration edges, a frame border). This is evidence, not a proof: for zero events in 775 frames a homogeneous Poisson bound is about 3/775 per frame.
- Cost: 0.3–1.4 s on most datasets, 3.4 s on M81-kumar, 7.8 s on klangwolke-1 and about 33 s on klangwolke-2 (28 kept frames of 61 MP, two workers on a nearly full 10 GB heap), whose normal extraction takes 22 s.

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

<a id="variable-star-photometry"></a>
## 16. Variable-Star Photometry

The optional branch measures stationary stars in retained frames, checks whether they support photometry, and looks for excess variability relative to similarly bright stars. It does not mine residual common-motion lists. The implementation lives in `io.github.ppissias.jtransient.photometry`: `VariableStarAnalyzer`, `PhotometryStarSelector`, `ApertureMeasurer`, `PhotometricReadinessChecker`, `EnsembleSolver`, `SpatialFrameFilter`, `VariabilityScorer` and `PhotometryMath`. Configuration fields/defaults are in [CONFIG.md](CONFIG.md#8-variable-star-photometry).

### Photometry in plain English

Photometry measures a star's brightness. Here the aim is to find stars whose brightness changes during one observing session, after correcting for changes shared by other stars. The result is a list of candidates for inspection. The checks and their current limits are explained below; the [remaining photometry investigations](FEATURE-REQUESTS.md#vs-11-photometry-strictness-defaults-and-recovery-review) track deferred policy and sensitivity work.

**What is measured.** A star is spread across several pixels by the atmosphere, optics and sampling. This pattern is its **point-spread function (PSF)**. **FWHM**, full width at half maximum, describes the width of its central profile at half its peak height. It is a width, not an aperture radius. The algorithm measures a representative FWHM in each frame, then uses it to size circular measuring regions.

An **aperture** is the circle in which the star's light is summed. A **sky annulus** is a surrounding ring used to estimate the local background. After subtracting that background, the remaining signal is the star's **flux**. Stars near an edge, bright neighbours or a passing object can have unreliable fluxes; the algorithm either avoids them or flags the affected measurements. A flagged point remains available for display even when it is excluded from the calculations.

A **magnitude** expresses brightness on a logarithmic scale: smaller magnitudes are brighter. An **instrumental magnitude** comes from measured counts and has no catalogue calibration. A **differential magnitude** compares measurements within this session. In a light curve, `deltaMag > 0` means the star was fainter than its fitted mean. **Signal-to-noise ratio (SNR)** compares the signal with its estimated random uncertainty. The reported `magError` uses sky noise and is a lower bound on the full measurement uncertainty; the candidate detector also learns the actual scatter of comparison stars.

**How the comparison works.** An **ensemble** is a group of stars used together as the brightness reference. Its fitted **zero point** is the common magnitude shift of a frame, for example from changing transparency. An optional spatial plane models a brightness correction that changes across the field. A **residual** is what remains after subtracting the star's fitted mean and the frame correction. Real variability should remain in those residuals, while much of the shared change is removed. The ensemble method follows [Honeycutt's inhomogeneous ensemble photometry](https://articles.adsabs.harvard.edu/pdf/1992PASP..104..435H); JTransient's particular thresholds and gates are project choices.

**Can the session support photometry?** The readiness checks come before candidate scoring:

| Check | What it asks, and what the terms mean | Current consequence |
|---|---|---|
| Star selection and measurements | Are there sufficiently isolated, unsaturated stars with their aperture and sky ring inside the valid image area? **Crowding** means nearby stars contribute light to the measurement. **Saturation** means the detector or encoding has clipped a bright signal. | Unsuitable stars or measurements are excluded. |
| Check A: quantisation and floor clipping | Does the image retain enough different pixel levels? **Quantisation** is rounding into discrete brightness steps. **Floor clipping** sets negative background/noise values to zero and can bias faint fluxes. Counting levels is a suitability heuristic, not a measurement of the camera's native bit depth. | Fewer than 1,024 distinct levels fails; excessive zero-floor clipping makes the session LIMITED. |
| Registration | Do stars have consistent positions after alignment? **Residual registration spread** measures disagreement among the sampled stellar offsets after a common translation is removed. | A measured spread above 0.5 px excludes that frame from photometry. This is separate from the fill-edge camera-motion fit. |
| Check B: star shape versus brightness | Do bright stars have the same concentration of light as fainter stars? The **concentration index (CI)** is inner-aperture flux divided by larger-aperture flux. A **linear response** means proportional incident light produces proportional measured signal. Flattened bright-star cores can indicate saturation or nonlinear processing. | Determine a usable brightness range, flag brighter measurements, and exclude frames with too little verified range or too few references. The session can still fail if too many frames fail. |
| Ensemble and spatial residuals | Can enough comparison stars solve each frame? After correction, does one part of the image remain systematically brighter or fainter than the centre? | Unsolvable or sufficiently uneven frames are excluded and the ensemble is recomputed. |
| Check D: response versus magnitude | Does a change in transparency affect bright and faint stars consistently? A fitted **slope** in mag per mag measures residual dependence on stellar brightness. **Correlation** measures whether two quantities vary together: +1 is perfect agreement, -1 is opposite variation, and 0 is no linear association. | Significant slopes, or slopes tracking the zero point/sky, can fail the session. Too little transparency variation makes this test INCONCLUSIVE. A failure identifies an unresolved response problem; it does not uniquely identify its physical cause. |

There is no check C in the current implementation. **READY** means the tests found no blocking problem; it does not prove perfect linearity. **LIMITED** permits scoring with a higher amplitude requirement. **NOT_READY** stops variability scoring. In particular, too few good references or too little brightness range can prevent a conclusion even when no nonlinear shape departure was measured. A pure power-law stretch remains a documented blind spot; use original, unstretched data.

**Which stars become candidates?** The detector first requires both unusually large residual scatter and unusually strong adjacent-pair variability, relative to stars of similar brightness. **Scatter** measures the spread of the light curve. The **Stetson J index** used here measures whether consecutive usable residuals tend to have the same sign. Both standardized scores must reach the default value of 5. They are complementary statistics of the same measurements; that threshold is not a calibrated five-sigma false-alarm probability.

The following eight **gates** then look for supporting evidence or alternative explanations. These operate on candidate stars; most stars never reach this stage.

| Gate | Plain-English meaning and current default |
|---|---|
| DATA | At least 20 usable points, plus at least 30 minutes of span when all their times are available. Missing timing leaves the span test unavailable. |
| AMPLITUDE | The 95th minus 5th percentile of the residuals must be at least 0.05 mag (0.10 mag for LIMITED data) and four times the expected scatter. A **percentile** is a ranked position in the measurements; this range deliberately ignores the most extreme points and can understate a short eclipse's depth. |
| PERSISTENCE | At least five successive usable points must deviate from the median in the same direction by more than 1.5 times the expected scatter. This favors changes lasting several exposures. Usable-point adjacency can bridge missing observations. |
| SPLIT_HALF | The first and second members of consecutive, non-overlapping usable pairs must correlate by at least 0.5. These are interleaved samples, not the early and late halves of the night. It checks whether adjacent measurements support the same pattern. |
| APERTURE | Do the smaller and larger apertures show the same pattern through time? The share of the candidate's variation by which they differ must be at most 0.3, unless that difference is within twice its uncertainty. Opposite or shifted aperture curves fail even when their ranges are equal. |
| SYSTEMATICS | Could seeing, sky brightness, transparency or image-position changes explain the signal? A **systematic** is a repeatable measurement effect rather than independent random noise. This gate checks changes between frames and compares shared trends with responses of constant comparison stars. |
| LOCAL | Do nearby stars show a substantial share of the same variation? Shared local behavior can indicate clouds or an image/measurement fault. The check needs at least three suitable neighbours; an isolated true variable should retain its own signal. |
| LINEARITY | Does the star have a saturated or nonlinear measurement on a frame still used for photometry? Such a measurement counts even though it is left out of the light curve, because missing bright states can bias it. Flags on excluded frames stay visible but do not count. |

After both initial scores pass, zero failed gates gives **HIGH_CONFIDENCE**, one gives **POSSIBLE**, and two or more gives **REJECTED**. These are evidence tiers, not numerical probabilities or confirmation of an astrophysical variable. **NOT_SCORED** means the star lacked usable measurements or another prerequisite. **CONSTANT** means it did not pass both initial variability scores; a rapidly varying or poorly sampled star can receive that label too.

The current detector is consequently most sensitive to changes well sampled over several consecutive exposures. A single-frame flash, a short eclipse, or a cycle with only a few samples can be missed despite a large true change. The detailed sections below describe the calculations and exclusions; they should be read with that sensitivity limit in mind. For practical observing terminology and time-series diagnostics, see the [AAVSO VPhot guide](https://www.aavso.org/sites/default/files/publications_files/VPHOT%20Users%20Guide%20V3.2.pdf).

<a id="photometry-1"></a>
### 16.1 Design Principles

- **A missed variable is better than a false one.** Every stage tends to exclude data or reject candidates rather than keep them. A candidate must pass two complementary scores and then eight gates to be reported as high confidence.
- **Compare stars with stars of similar brightness in the same session.** The noise model is measured from the session's own stars, not taken from formal errors. A star is variable only if it scatters much more than stars of the same magnitude, measured the same way.
- **Check the data before trusting it.** Stretched, 8-bit, clipped or non-linear frames make constant stars look variable. The readiness checks look for these problems first, and when they find them nothing is scored.
- **Flag measurements instead of dropping them.** Every star keeps a value for every frame, and problems are recorded as per-measurement flags, so callers can display the full light curve and see why points were excluded.
- **Magnitudes are instrumental and differential.** No catalogue calibration, colour terms or filter transformations are applied. `deltaMag` is the change against the star's own session mean, positive when fainter.

<a id="photometry-2"></a>
### 16.2 Position In The Pipeline

The stage runs only when `enableVariableStarDetection` is `true`, after track linking and residual transient analysis. `detectTransients(...)`, `generateMasterStack(...)` and the auto-tuner never run it.

It uses outputs of the earlier phases:

| Input | Source | Used for |
| --- | --- | --- |
| Clean frames | Frames that passed session rejection, chronological | Measurements |
| Frame metrics | `FrameQualityAnalyzer` | Extraction FWHM and background per frame |
| Frame detections | All extracted objects of each frame, before the stationary-star veto | Crossing flags |
| Median master stack | Median-stack stage or `providedMasterStack` | Independent photometry star extraction, selection and positions |
| Detection master stars | `MasterReferenceAnalyzer` | Stationary-source context for crossing flags |
| Photometry sky mask | Its own star footprints, grown one pixel | Remove star pixels from sky annuli |
| Tracks | `TrackLinker` | Crossing flags |
| Slow-mover candidates | Slow-mover analysis, when enabled | Crossing flags |

The result is `PipelineResult.variableStarAnalysis`. It is `VariableStarAnalysis.empty()` (verdict `NOT_RUN`) when the stage is disabled. The same telemetry object is attached as `PipelineResult.telemetry.photometryTelemetry`.

The stage reports its progress through `VariableStarAnalyzer.Input.progress` (0 to 100: measuring the frames up to 80, then the readiness checks, the ensemble solve and the scoring). `runPipeline(...)` shows it as 93-99% of the pipeline.

<a id="photometry-3"></a>
### 16.3 Stage Overview

The stage checks quantisation, derives its star list and saturation estimate, measures all frames, flags crossings, excludes registration/shape failures, solves the ensemble, excludes spatially uneven frames, runs response check D and recomputes the ensemble if necessary. It then creates light curves and, only for READY/LIMITED data, applies variability scores and gates. The detailed order and conditions are explained below.

The stage returns early with `NOT_READY` and no light curves when:

- fewer than `max(3, variableMinFrames)` clean frames exist, or the master stack or master stars are missing
- no frame has a usable FWHM (finite, above 0.5 px and below `errorFallbackValue`)
- fewer than `linearityMinStars` stars survive selection

<a id="photometry-4"></a>
### 16.4 Session FWHM And Saturation Level

**Session FWHM.** The session FWHM is the median of the per-frame `medianFWHM` values from the quality analysis. A frame without a valid value uses the session median. This extraction FWHM is computed as `2.355 * sqrt(lambda1 + lambda2)`, which is about 1.4 times the Gaussian FWHM for an untruncated round Gaussian; thresholded footprints add their own bias. It is therefore only used for star selection, to size the moment window, and as a fallback. Apertures are sized from the FWHM that `ApertureMeasurer` measures ([registration offset and measured FWHM](#photometry-6-1)).

**Saturation level.** `PhotometryStarSelector.estimateSaturationLevel` looks for a pile-up of clipped pixels at the top of each frame's histogram. It counts the pixels within 0.1% of the frame maximum, and five or more count as a pile-up. The frame maximum is then that frame's saturation level. The session level is the median over the frames with a pile-up. When no frame has one, it is 95% of 65535 (all pixel values are in the shifted non-negative domain). Check B still guards against roll-off below full well in that case.

The working threshold is `photometrySaturationFraction x saturationLevel`.

<a id="photometry-5"></a>
### 16.5 Star Selection

Photometry does not reuse the detection profile's master star list. It extracts its own from the median master stack, at a fixed seed of 3 sigma, grow of 2 sigma and at least 3 pixels (sigmas in units of the stack's measured noise, with one sky level for the whole stack, unlike the detection's master stars, which add a pass against the local sky), so its star list, its crowding test and its sky mask do not change with the moving-object settings. The footprints of these stars, grown by one pixel, are the sky mask ([apertures and sky](#photometry-6-2)).

`PhotometryStarSelector.select` goes through these stars and drops, in this order:

1. streaks (`isStreak`)
2. stars more elongated than `photometryMaxElongation`
3. stars whose aperture and sky ring do not lie entirely inside the session data area (where every kept frame has image data), using an outer radius of `photometryAnnulusOuterFwhmFactor x FWHM + 2 px` (the extra 2 px allows for residual registration offsets). Every measured star therefore has data in every kept frame.
4. crowded stars, judged by contamination: the neighbours' light falling inside the star's main aperture, from a Gaussian profile at the session FWHM, exceeds 2% of the star's own aperture light; or a neighbour lies within 1 FWHM (centroids and apertures merge); or a neighbour with at least 10% of the star's flux lies within `photometryAnnulusInnerFwhmFactor x FWHM` (its wings reach the sky ring). A faint neighbour that adds no measurable light no longer makes a star crowded.
5. stars whose master-stack peak (3 x 3 around the centre) is already at or above the saturation threshold

Bright stars below saturation are kept on purpose: check B needs them to find where linearity ends. Their measurements in frames where they are too bright are flagged later.

When `photometryMaxStars > 0` and more stars survive, the cap is spread across an 8 x 8 grid of field regions. Each region contributes the same fraction of its stars, taken at evenly spaced brightness ranks. Taking the brightest stars would fill a rich field with near-saturated stars and leave too few in the linear range.

The selected stars are sorted brightest first. Their master-stack positions are the forced photometry positions.

<a id="photometry-6"></a>
### 16.6 Forced Aperture Photometry

`ApertureMeasurer.measure` runs once per frame. When the engine has an executor, frames run in parallel.

<a id="photometry-6-1"></a>
#### 16.6.1 Registration offset and measured FWHM

Before measuring fluxes, the frame's FWHM and residual registration offset are measured from up to 50 bright stars. The brightest tenth of the selection is skipped because those stars may be saturated. For each star:

- the sky is the clipped median of an annulus (from the extraction FWHM)
- centroid and second moments are computed within a circular window of radius `max(3, 1.5 x extraction FWHM)`
- `FWHM = 2.355 x sqrt((Mxx + Myy) / 2)`

The frame offset is the median centroid offset from the master positions. `registrationSpread` is the robust sigma (1.4826 x MAD) of the offset distances around that median. The measured FWHM is the median over those stars. With fewer than five usable stars, the offset is zero, the spread is undefined, and the extraction FWHM is kept.

<a id="photometry-6-2"></a>
#### 16.6.2 Apertures and sky

Each star is measured at its master position plus the frame offset, in five concentric apertures:

| Index | Radius | Used for |
| --- | --- | --- |
| `R_CI_INNER` | 0.7 x FWHM | Concentration index (check B) |
| `R_SMALL` | 1.0 x FWHM | APERTURE gate |
| `R_MAIN` | `photometryApertureFwhmFactor` x FWHM | Photometry (main aperture; see [variability scoring](#photometry-11) for the per-brightness choice) |
| `R_LARGE` | 2.0 x FWHM | APERTURE gate |
| `R_CI_OUTER` | 2.5 x FWHM | Concentration index (check B) |

Every radius is at least 1 px. Pixels near an aperture edge (within 0.75 px) get fractional weights from 5 x 5 sub-pixel sampling.

The sky annulus runs from `max(largest aperture + 1, photometryAnnulusInnerFwhmFactor x FWHM)` to `max(inner + 2, photometryAnnulusOuterFwhmFactor x FWHM)`. Pixels of photometry's own star mask ([star selection](#photometry-5)) are excluded. The sky level and noise are a three-pass, 3-sigma clipped median and MAD sigma, and at least 10 pixels are required. The share of annulus pixels at exactly zero is also recorded, for check A.

All five apertures are summed in one pass over the pixels, so the concentration index, the photometry flux and the aperture-consistency gate all come from the same data.

<a id="photometry-6-3"></a>
#### 16.6.3 Errors and flags

An aperture's magnitude is `-2.5 log10(flux)`. Its error includes only the sky noise, with `N_ap` that aperture's area:

```text
sigma_F = sigma_sky * sqrt(N_ap * (1 + N_ap / N_sky))
magError = 1.0857 * sigma_F / flux
```

`sigma_sky` and `N_sky` belong to the same sample: the annulus pixels left after the earlier clipping passes, from which the sky level and its MAD sigma were computed (not the unclipped annulus, and not the pixels the last pass would keep). Every aperture gets its own error from this formula, NaN when its flux is not positive. Check B and the SNR cut use the main aperture's; a light curve measured in another aperture carries that aperture's ([measuring aperture per brightness](#photometry-11)). The error ignores the star's own photon noise and gain, so it is a lower bound. Scoring uses the empirical noise model instead.

**Sky-noise factor.** The formula treats pixels as independent, but registration interpolates the frames, so neighbouring pixels share noise and an aperture sum is noisier than the formula says. Each frame therefore measures the factor on blank sky. A 32 x 32 grid of positions covers the frame. A position counts when:
- its annulus gives a sky estimate as a star's would;
- its largest aperture lies inside the image;
- that aperture is free of master-star pixels, registration fill and any pixel 5 sky sigmas above the sky.

For every aperture, `skyNoiseFactor` is the robust spread of the sky-subtracted sums over the median spread the formula predicts for them, at least 1. It stays unset with fewer than 50 positions, and such a frame takes the session median of each aperture's factor.

The factor scales only the errors reported with the light curves (`StarLightCurve.magError`): check B and the SNR cut keep the formula's error, so stars, verdicts and candidates do not depend on it. Telemetry records it: `PhotometryFrameStat.skyNoiseFactor` holds the main aperture's factor per frame, and `PhotometryTelemetry.skyNoiseFactorMedian`, `skyNoiseFactorMin`, `skyNoiseFactorMax` and `framesWithoutSkyNoiseFactor` summarise it.

On the reference data, the session median for the main aperture ranges from 1.14 (M81 Kumar, UG 6524) and 1.82 (Klangwolke-2) to 2.71 (m5-reg) and 2.89 (Eagle-noisy). It is smaller for smaller apertures; on Klangwolke-2 it is 1.47 at 0.7 FWHM. For the faintest constant stars, where sky noise dominates, the scatter was 1.14–2.9 times the unscaled error. The factors at the apertures those stars use compare as follows ([release notes](RELEASE_NOTES.md#108-unreleased)):

| Session | Factor | Faint-star scatter ÷ unscaled error |
|---|---|---|
| M81 Kumar, UG 6524 | 1.08 | 1.14–1.17 |
| Klangwolke-2 | 1.47 | 1.46–1.52 |
| m5-reg | 2.26 | 2.35–2.41 |
 Bright stars still scatter more than their error, because their own photon noise, scintillation and flat-field errors are not modelled.

Flags set here:

| Flag | Condition |
| --- | --- |
| `EDGE_OR_VOID` | Annulus leaves the image, fewer than 10 sky pixels, more than 50% of annulus pixels at zero, or a main-aperture pixel at the fill level (8 ADU or less: a dead pixel) |
| `SATURATED` | Main-aperture peak at or above the saturation threshold |
| `BAD_FLUX` | Main-aperture flux not positive (no magnitude) |

<a id="photometry-7"></a>
### 16.7 Crossing Flags

A moving object passing through a star's aperture or sky annulus changes its measured brightness for a few frames. This is the classic false variable. `VariableStarAnalyzer.flagCrossings` sets `CROSSING` on stars near the following. For frame detections the reach is the sky annulus's inner radius (`photometryAnnulusInnerFwhmFactor x` frame FWHM): the aperture and the gap before the sky ring, since the clipped sky median already ignores faint blobs in the ring itself. Tracks and slow-mover footprints reach the outer annulus radius (`photometryAnnulusOuterFwhmFactor x` frame FWHM).

Frame positions (detections, their pixels and track points) are first moved into the master system with the frame's measured registration offset, the same correction the apertures use ([forced aperture photometry](#photometry-6)), so a residual shift does not make stationary stars look like moving objects.

1. **Moving detections.** A detection in a frame that peaks at 5 sigma or more (or is a streak) and is not within `max(maxStarJitter, 0.5 x FWHM)` of any master star (of the detection or the photometry star list) is not stationary. Each of its pixels flags the stars around it in that frame. Fainter detections are left to the per-measurement checks (contaminated shape, isolated outliers), so a sensitive detection profile no longer floods the light curves with flags.
2. **Tracks.** For every frame in a track's time span: where the track was observed, every observed point of that frame is used (a streak can be extracted as several fragments); elsewhere the position is interpolated linearly between the surrounding track points, by capture time when the frame and both points have timestamps (pauses make the cadence uneven) and by sequence index otherwise. This covers frames where the object merged with a star and was not detected. Each point is treated as a segment along its footprint (centre, angle, half of `majorExtent - minorExtent`); a star is flagged when its distance to the segment is within the outer annulus radius plus the point's width (its minor extent for a streak, otherwise the larger of FWHM and minor extent). A point source is a segment of length zero, so it flags a disc; a satellite streak flags a band along its line instead of every star within its length. When a surrounding frame has several pieces, it enters the interpolation as their mean centre weighted by length in pixels (a piece shorter than one pixel weighs one), which for contiguous fragments of one trail is the centre of the whole trail. An interpolated frame takes the shape of one piece: the piece of the nearest observed frame (the earlier one on a tie) whose centre is closest to the interpolated position. Pieces are put in a fixed geometric order first, so the flags never depend on the order of the track's points. The pieces are not merged into one footprint: for a flashing satellite that would mask its whole path in a frame where it was not seen.
3. **Slow-mover candidates.** The maximum-stack footprint covers the whole session, so its pixels flag nearby stars in every frame.

<a id="photometry-7-1"></a>
#### 16.7.1 Contaminated shape

A hot pixel, cosmic ray or faint passing object inside an aperture changes the star's light by a large factor, sometimes in several frames in a row, so the isolated-outlier rule ([light-curve cleaning](#photometry-11-1)) cannot always remove it. It does change the star's shape, which real variability does not.

`VariableStarAnalyzer.flagContaminatedShapes` runs after check B:

1. Per frame, the median concentration index (flux within 0.7 FWHM over flux within 2.5 FWHM) of the clean measurements is the frame's seeing reference.
2. Each measurement's concentration is divided by that reference, and by the star's own median over the active frames, giving its fractional departure. A star needs at least 5 clean measurements on active frames; otherwise none of its measurements is judged.
3. Stars are grouped by their median magnitude on the active frames (about ten groups, at least 30 stars each). The tolerance of a group is the robust sigma of the departures of all its measurements on active frames, at least 2%. Taking it from the group rather than from the star keeps a star contaminated in many frames from widening its own tolerance.
4. A measurement departing by more than 5 tolerances is flagged `CONTAMINATED` and left out of the solve and the scoring.

"Active" means not yet excluded by the registration check or check B, which run before. A frame already excluded therefore cannot move a star's typical shape or widen a group's tolerance, but its measurements are still judged in step 4, so the report shows their contaminated measurements too. Frames that check D or the spatial filter exclude later still contribute; whether to recompute without them remains part of the [late-frame population lifecycle investigation](FEATURE-REQUESTS.md#late-frame-population-lifecycle). In a session where fewer than 5 frames remain active at this point, no measurement is flagged; such a session is also too short to be scored.

For faint stars the concentration index is noisy, so a hot pixel a few pixels off-centre can still pass; the aperture gate ([gates](#photometry-11-4)) then usually rejects the star.

<a id="photometry-8"></a>
### 16.8 Readiness Checks

There are three checks, labelled A, B and D. There is no check C in the current implementation.

<a id="photometry-8-1"></a>
#### 16.8.1 Check A: quantisation and floor clipping

- **Quantisation.** If the middle frame has fewer than `linearityMinDistinctLevels` distinct pixel values, the data is 8-bit or heavily quantised. The check is `FAIL`.
- **Floor clipping.** If the median (over frames) share of sky-annulus pixels at zero exceeds `linearityMaxFloorClippedFraction`, negative sky noise was clipped. This happens, for example, when calibrated float data is converted to unsigned 16-bit. Faint-star fluxes are then biased high, and the check is `LIMITED`.

<a id="photometry-8-2"></a>
#### 16.8.2 Registration exclusion

Frames whose `registrationSpread` exceeds `photometryMaxRegistrationSpreadPixels` are excluded. A large spread means the frame is not registered consistently across the field (for example, field rotation or distortion), so forced positions are wrong for some stars.

<a id="photometry-8-3"></a>
#### 16.8.3 Check B: star shape against brightness

**Idea.** In linear data every unsaturated star has the same profile shape whatever its brightness, so the fraction of its flux in a small central aperture does not depend on magnitude. A non-linear stretch, or saturation and roll-off near full well, flattens the cores of bright stars, which then look broader than faint ones.

The concentration index (CI) is `flux(0.7 x FWHM) / flux(2.5 x FWHM)`. An inner radius of 0.7 FWHM is used because at 1.0 FWHM a Gaussian already has 94% of its flux inside and the index barely changes.

Per frame (`PhotometricReadinessChecker.analyzeShape`):

1. Use stars without `EDGE_OR_VOID`, `CROSSING` or `BAD_FLUX` and with `magError <= 0.05` (SNR of about 20 or more). The cut is wide enough that background-limited data, where stars saturate soon above the sky noise, still spans a usable magnitude range; the per-bin noise allowance absorbs the extra scatter of the fainter stars. If fewer than `linearityMinStars` remain, the frame is `INCONCLUSIVE` and is excluded.
2. Sort by magnitude. The **reference CI** is the median CI of the stars ranked between 50% and 90% of the brightness order (the faint half, without the faintest and noisiest tenth). The **faint bound** is the magnitude at the 90% rank.
3. Divide the stars from the brightest down to the faint bound into bins of `max(15, count / 15)` stars. Each bin's tolerance is `linearityMaxConcentrationDrift + 2 x` the standard error of the bin's median CI.
4. Walk towards brighter bins, starting at the faintest bin that still holds stars brighter than the reference ranks (bins wholly inside the reference scatter around it by definition, so they cannot end the walk). The first bin whose median CI departs from the reference by more than its tolerance marks the end of linearity. The **linear limit** is the brightest magnitude of the next fainter bin. If no bin departs, the limit is the brightest star.
5. **Linear range** = faint bound − linear limit. The frame passes when the range is at least `linearityMinRangeMag` and at least `linearityMinStars` stars lie inside it. A failing frame with no departing bin is linear over everything it can measure but too short to verify; its exclusion reason and the session message say so instead of calling it non-linear.

Because only stars up to the 90% rank count, `n` usable stars of distinct magnitude give at most `ceil(0.9 n)` references: 50 usable stars give 45, and 55 are the fewest that give the default 50. Stars tied in magnitude at the faint bound all count. `DetectionConfigRules.minimumStarsForLinearityCheck` computes this bound from the same `LINEARITY_REFERENCE_FRACTION` the check uses, and the star-cap warning uses it ([configuration](CONFIG.md#photometrymaxstars-default-0)). It is a lower bound: the cap selects stars over the whole brightness range, and only the precise, unflagged ones reach this check.

In every evaluated frame, including failing ones, all measurements brighter than the frame's linear limit are flagged `NONLINEAR`. Failing frames are excluded.

Session status:

- `INCONCLUSIVE` if no frame could be evaluated
- `FAIL` if more than `linearityMaxFailingFrameFraction` of the evaluated frames failed
- `LIMITED` if the median linear range is below `linearityMinRangeMag + 1` mag
- `PASS` otherwise

The binned profile of each frame is exported as `PhotometryFrameStat.concentrationProfile`.

<a id="photometry-8-4"></a>
#### 16.8.4 Check D: response against magnitude

Check D runs on the ensemble solution ([star exclusion and the ensemble solve](#photometry-9)). In linear data, a change of transparency scales every star's flux by the same factor, so every star's magnitude shifts by the same amount. The ensemble residuals of a frame therefore do not depend on magnitude. A non-linear response compresses bright and faint stars differently, so the residuals slope against magnitude, and the slope changes as the zero point or sky changes.

Per solved frame, a weighted line (weights `1 / sigma_i^2`) is fitted to residual against `M(i) − median(M)` over the usable measurements. A frame whose slope cannot be fitted (too little magnitude leverage) is `INCONCLUSIVE`, neither passing nor failing. The frame fails when `|slope| > linearityMaxFrameSlope` and `|slope| > 3` standard errors.

Across the session (at least 3 solved frames with a measured slope are needed, otherwise `INCONCLUSIVE`; failing fractions count measured frames):

- The **zero-point range** is the 5th to 95th percentile spread of `Z(j)`. Below `linearityMinZeroPointRangeMag` there is no lever arm, and the zero-point test cannot conclude anything.
- **Slope tracking.** The per-frame slopes are correlated with the zero point and with the frame sky median. A driver "tracks" when `|r| >= linearityMaxSlopeTrackingCorrelation` and the fitted change of slope over the driver's 5–95% range is at least `linearityMaxFrameSlope`. Sky tracking is tested whatever the zero-point range.

The status is `FAIL` if the slopes track the zero point or the sky, or if more than `linearityMaxFailingFrameFraction` of frames fail. Otherwise it is `INCONCLUSIVE` when the zero-point range is too small, and `PASS` when it is not.

Frames that failed are then excluded and the ensemble is solved once more without them. Check D is not repeated on the second solution.

<a id="photometry-8-5"></a>
#### 16.8.5 What the checks cannot see

A pure power-law stretch applied from zero, `f -> f^gamma`, passes all checks. Every star's profile is changed in the same way, so CI does not depend on brightness. Magnitudes become `gamma x m`, which the ensemble model fits exactly. Amplitudes in such data are scaled by `gamma`. `READY` means no non-linearity was detected, not that linearity is proven. Use original, unstretched frames.

<a id="photometry-9"></a>
### 16.9 Star Exclusion And The Ensemble Solve

<a id="photometry-9-1"></a>
#### 16.9.1 Star exclusion

Before the solve, a star is kept out of the ensemble and the scoring when, counting only the frames still active after the registration check and check B (`VariableStarAnalyzer.starExclusions`):

- more than half of its measured frames are flagged `SATURATED` or `NONLINEAR`, or
- its median per-frame SNR (`1.0857 / magError`, main aperture) is below `photometryMinSnr` (when that is above 0); a star with no measurement on those frames has no median and is excluded.

Measurements on frames already excluded count neither for nor against a star: check B flags `NONLINEAR` on the frames it rejects as well, and a star's flags there must not exclude it from the frames that remain.

Excluded stars stay in `VariableStarAnalysis.stars` with their flags, magnitude errors and `notScoredReason`. They never constrain the solve, so they have no `meanMag`, and their `deltaMag` values are `NaN`.

A measurement is **usable** for the solve when its star is not excluded, its frame is active, it has a finite magnitude, and none of `SATURATED`, `NONLINEAR`, `EDGE_OR_VOID`, `CROSSING`, `BAD_FLUX`, `CONTAMINATED` or `FRAME_EXCLUDED` is set.

<a id="photometry-9-2"></a>
#### 16.9.2 Model

`EnsembleSolver` implements inhomogeneous ensemble photometry (Honeycutt 1992):

```text
m(i,j) = M(i) + Z(j) + a(j) * x(i) + b(j) * y(i)
```

- `M(i)`: mean instrumental magnitude of star `i`
- `Z(j)`: zero point of frame `j` (transparency, airmass, exposure)
- `a(j), b(j)`: per-frame plane over normalised positions `x, y` in [-1, 1] from the frame centre. This absorbs gradients that change between frames, such as passing thin cloud or a moving gradient. It is fitted only with `photometryFitPlane` and at least 30 usable stars in the frame; otherwise only `Z(j)` is fitted.

Static spatial patterns such as vignetting or missing flats are constant per star, so `M(i)` absorbs them either way.

Every star in the field serves as a comparison star, not a hand-picked few. The solution remains well defined when individual stars are missing from some frames.

<a id="photometry-9-3"></a>
#### 16.9.3 Iteratively reweighted least squares

Initial values: `M(i)` is the median of the star's usable magnitudes, and `sigma_i` is their robust sigma. Then, for up to 15 iterations:

1. **Frame solve.** For each active frame with at least 10 usable stars, solve the 3 x 3 weighted normal equations for `Z, a, b` (or `Z` alone), with weights `w_ij / sigma_i^2`.
2. **Gauge fix.** Subtract the median `Z` and the mean `a`, `b` over solved frames. Without this, `M` and `Z` can trade a constant.
3. **Star solve.** `M(i)` is the `w_ij`-weighted mean of `m(i,j) − Z(j) − a(j)x(i) − b(j)y(i)`.
4. **Reweight.** Each star's own robust residual sigma is compared with the expected sigma, which is the median own sigma of the 50 stars nearest in magnitude. Then `sigma_i = max(own, expected)`, with a floor of 0.001 mag. Point weights are Cauchy weights, `w_ij = 1 / (1 + (r_ij / (3 sigma_i))^2)`.

The loop stops when the predicted correction `Z(j) + a(j)x(i) + b(j)y(i)` has changed by less than 1e-5 mag since the previous iteration at every usable measurement of a solved frame (a frame solved in only one of the two iterations counts as a change). Zero points and plane terms are tested together in magnitudes, so a plane still settling under the reweighting keeps the loop going after the zero points have settled; without a plane the test is the zero-point change. Each frame solve uses the weights of the previous iteration, so a converged correction is stable under its own final weights. After 15 iterations the loop stops anyway and keeps its last result. `Solution` records whether it converged, the iterations run and the last change; telemetry reports this for the final main-aperture fit (`ensembleConverged`, `ensembleIterations`, `ensembleLastChangeMag`) and counts the 0.7, 1.0 and 2.0 x FWHM fits that reached the limit (`apertureSolvesAtIterationLimit`). Reaching the limit is shown in the report and changes no verdict.

A variable star has a large own sigma, so `1 / sigma_i^2` lowers its weight in the frame solve. The variables remove themselves from the reference without having to be identified first. The Cauchy weights also limit the effect of individual bad points that no flag caught. Using `max(own, expected)` stops an unusually quiet star from getting too much weight.

Residuals `m − model` are returned for every finite measurement on a solved frame, flagged or not, so callers can still plot flagged points. Frames that cannot be solved are excluded ("Too few usable stars for the ensemble").

<a id="photometry-9-4"></a>
#### 16.9.4 Spatial frame exclusion

After the initial ensemble solve and exclusion of unsolved frames, `SpatialFrameFilter` divides the normalised field into a 3 × 3 grid. It compares each populated cell's median usable residual with the centre cell. Both cells must contain at least 30 usable stars; an absolute offset of 0.10 mag or more excludes that frame from photometry.

The exclusion happens before response check D. If any frame is dropped, the usable matrix and ensemble are recomputed; check D then runs once on that solution. Readiness, all alternative-aperture solves and final scoring use the filtered frame set. A session with no spatial exclusion keeps its original ensemble solution. The decision uses the main aperture and fixed constants, not a new profile setting.

Frames retain `FRAME_EXCLUDED` flags and per-frame reasons such as `Spatial residual 0.13 mag`. `PhotometryTelemetry.framesExcludedSpatial` counts them separately from other exclusions. This does not remove frames from the main moving-object or slow-mover analysis. A sparse centre or comparison cell cannot be judged by this check, and a fitted plane can absorb smooth gradients; it targets residual spatial differences rather than every possible photometric fault.

<a id="photometry-10"></a>
### 16.10 Verdict

| Verdict | Condition |
| --- | --- |
| `NOT_READY` | Check A `FAIL`, check B `FAIL` or `INCONCLUSIVE`, check D `FAIL`, or fewer than `variableMinFrames` frames still active |
| `LIMITED` | Not `NOT_READY`, and check A `LIMITED`, check B `LIMITED`, or check D `INCONCLUSIVE` |
| `READY` | Otherwise |

`PhotometricReadiness.messages` explains each finding in plain language.

Light curves are built for every star whatever the verdict. `deltaMag` is the main-aperture ensemble residual and `magError` the main aperture's error until scoring chooses the measuring aperture, and the flags include `FRAME_EXCLUDED` for inactive frames. Scoring runs only for `READY` and `LIMITED`. In a `NOT_READY` session every star is `NOT_SCORED`.

<a id="photometry-11"></a>
### 16.11 Variability Scoring

`VariabilityScorer.score` works on the usable residuals of each star. Before scoring, the ensemble is solved three more times, with the 0.7, 1.0 and 2.0 x FWHM apertures, using the same rules. The 1.0 and 2.0 x FWHM solutions are also used by the APERTURE gate.

**Measuring aperture per brightness.** The scored stars are split into 8 brightness ranges of equal size. In each range the aperture whose solution gives the stars (nearly all constant) the smallest median robust scatter is chosen, and the stars of that range are scored on its residuals. Each star's scatter is measured for every aperture on the same measurements: those the main aperture can use and every aperture has, so an aperture cannot look better by lacking difficult points. A star enters the comparison when every aperture has a finite scatter there. The main aperture stays unless another lowers the scatter by more than 3%, and another aperture is eligible only when its smallest radius on the frames used is at least 1.5 px (smaller ones turn the centring errors of undersampled stars into noise; above that, the constant stars' scatter shows any such noise). A star whose chosen aperture lacks a measurement its main aperture has keeps the main aperture; telemetry counts these stars (`apertureMainFallbacks`). Faint stars are limited by sky noise and do best in a small aperture; bright stars keep a larger one. A star of a range that chose another aperture gets that aperture's residuals and errors together, and records it as `apertureFwhmFactor`, with the smallest and largest radius the aperture had on the frames used (`apertureMinRadiusPixels`, `apertureMaxRadiusPixels`; each frame's radius follows its measured FWHM). `meanMag` stays the main aperture's: it is the common brightness scale for the ranges and the magnitude neighbours. The choice is exported as `apertureChoices`, with the same radius range per brightness range, and shown in the report.

<a id="photometry-11-1"></a>
#### 16.11.1 Light-curve cleaning

- A star with fewer than `variableMinFrames` usable measurements is `NOT_SCORED`.
- **Star peaks removed before registration** (`PeakRemovalCheck`, before scoring). Hot-pixel removal replaces a pixel that stands far above its neighbours with their level. On undersampled frames, the peak pixel of a star centred on a pixel looks the same, so the star loses that pixel's light while the sky stays as it was. Dithering moves the stars to other pixels at the next pointing, so the loss lasts one pointing and the light curve shows a brief fading.
  - *Pointings.* Consecutive frames share a pointing while the camera position of the image centre ([`FrameRegistration`](#1-border-drift-diagnostics), from the fill) moves by at most 1 px. A frame without a usable registration starts its own pointing.
  - *Brief fadings and brightenings.* The frames of one pointing that are fainter (or brighter) than the star's median by more than 4 robust sigmas and more than `variableMinAmplitudeMag` form a fading (or brightening).
  - *The one-pixel test.* Each fading or brightening is compared with the star's two nearest pointings that have neither. The change is the reference's mean sky-subtracted cutout minus the episode's, within max(3 px, 2 x FWHM). Its concentration is the largest single-pixel share of the change over the largest single-pixel share of the reference's light. A real change in brightness scales the whole profile and gives about 1; a removed peak gives about 2 to 4. A change counts as concentrated when its concentration is at least 1.8 against every reference.
  - *The session decision.* Noise, cosmic rays and hot pixels make concentrated changes in both directions. The session counts as having had its star peaks removed only when at least 20 fadings are concentrated, and at least 5 times as many as brightenings plus one. Only then are the concentrated fadings flagged `PEAK_REMOVED`: left out of scoring, kept for display, and explained in a readiness message.
  - *Telemetry:* `peakCheckFadings`, `peakCheckConcentratedFadings`, `peakCheckConcentratedBrightenings`, `peakRemovalFound`, `measurementsPeakRemoved`, and `PhotometryFrameStat.peakRemoved` per frame.
  - *Limitation.* Pixel phase differs between pointings, so a real dimming within one pointing whose references caught the star on a sharper pixel position can look concentrated. This happened to 3 % of synthetic profile-shaped dimmings on M81 Kumar's real frames. The check therefore acts only in sessions with the one-sided signature.
  - *Reference data.*

    | Session | Concentrated fadings | Concentrated brightenings | Result |
    |---|---|---|---|
    | M81 Kumar (2.9"/px, FWHM 2 px) | 414 of 710 | 17 | found: 625 measurements flagged |
    | Klangwolke-2 | 393 of 1360 | 180 | not found |
    | Eagle-noisy | 8 of 47 | 40 | not found |
    | m5-reg | 12 of 27 | 3 | not found |

    On M81 Kumar, a high-confidence candidate without a catalogue match that was made entirely of such fadings becomes constant, and 13 of the 16 possible candidates of that kind become constant, as do 9 rejected ones. The other high-confidence candidates, all VSX-matched, are unchanged.
- **Isolated outliers.** A point more than 4 robust sigmas from the median is removed and flagged `OUTLIER`, unless a neighbouring usable point deviates by more than 2 sigmas on the same side. A neighbour counts only when the star has no missing measurement on a used frame between the two: deviations either side of a crossing-flagged or contaminated measurement do not keep each other, while a frame left out of the photometry does not separate them. Single-frame events (cosmic rays, hot pixels, satellite glints that were not detected) are dropped. Real dips or flares that span several frames are kept.
- From the cleaned curve: `scatter` (sample standard deviation), `amplitude` (95th minus 5th percentile), and `timeSpanMinutes` (first to last usable mid-exposure Julian date; `NaN` if any usable frame has no timestamp).

<a id="photometry-11-2"></a>
#### 16.11.2 Noise model and scores

The **noise model** is empirical. For each star, `expectedScatter` is the median scatter of the `variableNoiseModelNeighbors` stars nearest in mean magnitude (excluding the star itself). Their robust sigma is the spread. A star with no positive expected scatter is `NOT_SCORED`.

Two scores measure different things:

- **Excess scatter.** `scatterZ = (scatter − expectedScatter) / spread`, with the spread floored at 5% of the expected scatter. This measures how much more the star varies than its peers. `excessScatter = scatter / expectedScatter` is also reported.
- **Stetson J** (Stetson 1996). It is computed over consecutive usable points, using `expectedScatter` as the error of every point. It is large when consecutive measurements deviate in the same direction, which is the signature of a change spread over time rather than random noise. `stetsonJZ` is its robust z-score against the same neighbours, with the spread floored at 0.02.

A star is a **candidate** when both `scatterZ` and `stetsonJZ` are at least `variableScoreSigma`. Otherwise it is `CONSTANT`. Requiring both scores rejects noisy stars with uncorrelated scatter (high scatter, low J) and also slow drifts that are too small to matter (high J, low scatter).

<a id="photometry-11-3"></a>
#### 16.11.3 Systematic responses

Before the gates run, `SystematicsContext` measures how every scored star responds to five per-frame **drivers**:

| Driver | Series |
| --- | --- |
| zero point | `Z(j)` |
| FWHM | measured frame FWHM |
| sky | the star's own local sky level |
| x offset, y offset | registration offset |

A star's response to a driver is `|slope of its light curve against the driver| x` the driver's 5th–95th percentile range. This is the magnitude change that the driver accounts for over the session. For each candidate magnitude, the typical maximum response is the 99th percentile among the 200 `CONSTANT` stars nearest in magnitude.

<a id="photometry-11-4"></a>
#### 16.11.4 Gates

Each candidate is tested against eight gates:

| Gate | Fails when | Guards against |
| --- | --- | --- |
| `DATA` | Fewer than `variableMinFrames` usable points after outlier removal, or (with timestamps) a time span under `variableMinSpanMinutes` | Too little data to see a change |
| `AMPLITUDE` | Amplitude below `variableMinAmplitudeMag` (`variableLimitedMinAmplitudeMag` in a `LIMITED` session), or below `variableAmplitudeNoiseFactor x expectedScatter` | Statistically significant but tiny changes |
| `PERSISTENCE` | The longest run of consecutive points deviating on the same side by more than 1.5 x expected scatter is shorter than `variableMinPersistenceFrames` | Short events, a few bad frames |
| `SPLIT_HALF` | The correlation between the first and second points of consecutive non-overlapping pairs is below `variableMinSplitHalfCorrelation` | Noise; white noise gives about zero |
| `APERTURE` | On the frames where the scored, 1.0 x and 2.0 x FWHM curves are all finite, the slope of (2.0 x − 1.0 x) against the scored curve, `aperturePatternDifference` (0 when the apertures agree, −2 for opposite curves, −0.5 when the large aperture shows half), exceeds `variableMaxApertureAmplitudeDifference` in size and 2 of its regression standard errors (`aperturePatternDifferenceError`, from the residual scatter of the difference curve, which includes noise the overlapping apertures share); or it cannot be measured | Light from a neighbour leaking in, opposite or shifted aperture curves, or a wrong position. Real variability is the same in every aperture |
| `SYSTEMATICS` | See below | Changes caused by seeing, transparency, sky or registration |
| `LOCAL` | Fewer than 3 sufficiently precise comparison stars (including other candidates) are found within `variableLocalRadiusPixels`, or, in a sparse field, within 3 times that radius; or the median absolute correlation with the nearest 10 of them exceeds `variableMaxLocalCorrelation` and they also carry at least 30% of the candidate's pattern (median regression slope of their residuals on the candidate's, `localSharedFraction`). References are selected by their expected scatter, no larger than the greater of the candidate's expected scatter and its amplitude divided by `max(1, variableAmplitudeNoiseFactor)`. Selecting only quiet stars would remove the strongest evidence when a local fault makes neighbouring stars candidates too. A systematic shifts neighbours by about the same amount; a faint copy (light from a bright variable leaking into a neighbour's aperture) or a slight common trend correlates without sharing the amplitude | A local pattern the plane term cannot model (a dew patch, a local gradient) |
| `LINEARITY` | A measurement of the star on a frame without `FRAME_EXCLUDED` is flagged `SATURATED` or `NONLINEAR`, usable or not | Stars that come near the linear limit, for example when they brighten |

**SYSTEMATICS** runs two tests for each driver. The gate fails if either test fails for any driver:

1. **Frame to frame.** The correlation between consecutive differences of the light curve and of the driver exceeds `variableMaxSystematicsCorrelation`. A blend that leaks with seeing jumps whenever the FWHM jumps and fails here, whatever the size of the change. A smooth real variable barely changes between consecutive frames, so it does not correlate here.
2. **Trend.** The raw correlation exceeds `variableMaxSystematicsCorrelation` and the star's amplitude is at most `variableSystematicsResponseFactor` times the typical maximum response of constant stars. Raw correlation alone is not enough to fail a star. Any variable that brightens or fades steadily correlates with any steady drift in the session, such as airmass changing the zero point. The star fails only when such a drift could plausibly have produced its change.

`maxSystematicsSource`, `maxSystematicsCorrelation`, `maxFrameToFrameSystematicsCorrelation` and `systematicsLimitMag` record the details.

<a id="photometry-11-5"></a>
#### 16.11.5 Tiers

| Tier | Meaning |
| --- | --- |
| `HIGH_CONFIDENCE` | Candidate, every gate passed |
| `POSSIBLE` | Candidate, exactly one gate failed |
| `REJECTED` | Candidate, two or more gates failed |
| `CONSTANT` | Did not reach `variableScoreSigma` on both scores |
| `NOT_SCORED` | Excluded star, too few measurements, no noise model, or session not ready |

`VariableStarAnalysis.candidates` holds the `HIGH_CONFIDENCE`, `POSSIBLE` and `REJECTED` stars, best tier first, then by descending `scatterZ`. `highConfidenceCandidates()` returns the first tier only. `failedGates` lists the gates each candidate failed, in the order of `VariableStarAnalysis.GATE_NAMES`.

**Brief and fast variation.** A change must last about `variableMinPersistenceFrames` consecutive frames (5) to pass `PERSISTENCE`, so briefer dips and flares are at best `POSSIBLE`, and a change in one frame is removed as an outlier. Variation that alternates within three or four frames gives a negative Stetson J and does not become a candidate. In scorer-level injections with 28 and 40 frames and 9-18 mmag noise, dips lasting 6 frames or more and pulsations with a period of 12 frames were mostly `HIGH_CONFIDENCE` from 0.1 mag. A search for briefer events would first need checks of the brief excursions that constant stars show in real sessions; this is deferred under the [brief/fast variability investigation](FEATURE-REQUESTS.md#brief-and-fast-variability).

<a id="photometry-12"></a>
### 16.12 Outputs

Frame/light-curve indexing, result fields, candidate ordering and telemetry ownership are documented in [PIPELINE.md](PIPELINE.md#photometry-outputs-and-indexing). `deltaMag` is positive when fainter; timestamps are mid-exposure Julian dates. Disabling the stage returns verdict `NOT_RUN`.

<a id="photometry-13"></a>
### 16.13 Fixed Constants

These values are in the code and are not configurable:

| Constant | Value | Where |
| --- | --- | --- |
| Saturation pile-up tolerance / minimum pixels | 0.1% of frame max / 5 | `PhotometryStarSelector` |
| Saturation level without pile-up | 0.95 x 65535 | `PhotometryStarSelector` |
| Registration allowance in selection margin | 2 px | `PhotometryStarSelector` |
| Bright-neighbour flux ratio | 0.1 | `PhotometryStarSelector` |
| Photometry star list: seed / grow sigma / minimum pixels | 3 / 2 / 3 | `VariableStarAnalyzer` |
| Largest aperture contamination by neighbours | 2% | `PhotometryStarSelector` |
| Crossing detection minimum peak | 5 sigma | `VariableStarAnalyzer` |
| Aperture choice: ranges / minimum gain / smallest actual radius | 8 / 3% / 1.5 px | `VariableStarAnalyzer` |
| APERTURE gate significance | 2 standard errors | `VariabilityScorer` |
| Local gate: minimum shared share / widest radius | 30% / 3 x `variableLocalRadiusPixels` | `VariabilityScorer` |
| Selection grid | 8 x 8 | `PhotometryStarSelector` |
| Stars for FWHM and offset | 50, skipping the brightest 10%; minimum 5 | `ApertureMeasurer` |
| Moment window | max(3, 1.5 x extraction FWHM) | `ApertureMeasurer` |
| CI radii | 0.7 and 2.5 x FWHM | `ApertureMeasurer` |
| Gate apertures | 1.0 and 2.0 x FWHM | `ApertureMeasurer` |
| Sky clipping | 3 passes, 3 sigma, minimum 10 pixels | `ApertureMeasurer` |
| Floor share treated as void | 50% of annulus | `ApertureMeasurer` |
| Check B star error limit | 0.05 mag | `PhotometricReadinessChecker` |
| Check B reference ranks | 50%–90% | `PhotometricReadinessChecker` |
| Check B bins | max(15 stars, count / 15) | `PhotometricReadinessChecker` |
| Check B bin noise allowance | 2 standard errors | `PhotometricReadinessChecker` |
| Check D slope significance | 3 standard errors | `PhotometricReadinessChecker` |
| `LIMITED` linear-range margin | 1 mag | `VariableStarAnalyzer` |
| Mostly non-linear star | more than 50% of measurements | `VariableStarAnalyzer` |
| Shape check threshold / spread floor | 5 tolerances / 2% | `VariableStarAnalyzer` |
| Shape check groups / minimum stars per group / minimum measurements per star | 10 / 30 / 5 | `VariableStarAnalyzer` |
| Ensemble minimum stars per frame / for plane | 10 / 30 | `EnsembleSolver` |
| Spatial grid / minimum stars per compared cell / offset limit | 3 × 3 / 30 / 0.10 mag | `SpatialFrameFilter` |
| Ensemble iterations / convergence | 15 / 1e-5 mag | `EnsembleSolver` |
| Ensemble sigma floor / noise neighbours / Cauchy scale | 0.001 mag / 50 / 3 sigma | `EnsembleSolver` |
| Outlier / support threshold | 4 / 2 robust sigma | `VariabilityScorer` |
| Persistence threshold | 1.5 x expected scatter | `VariabilityScorer` |
| Scatter / J spread floors | 5% of expected / 0.02 | `VariabilityScorer` |
| Systematics neighbours / percentile | 200 constant stars / 99th | `VariabilityScorer` |
| Local gate stars / minimum | 10 / 3 | `VariabilityScorer` |

<a id="photometry-14"></a>
### 16.14 Limitations

- **Single session, instrumental magnitudes.** There is no catalogue cross-match, no colour or extinction terms, and no transformation to a standard system. Amplitudes are differential within the session.
- **Variability must show within the session.** A star that changes on timescales much longer than the session looks constant. A star that changes only during the gap between sessions is not visible at all.
- **Crowded, bright and edge stars are not measured.** A variable whose neighbours add more than 2% light to its aperture, one saturated in the master stack, or one whose sky ring reaches outside the session data area is never selected.
- **Power-law stretches are not detected** ([what the checks cannot see](#photometry-8-5)).
- **`magError` is a lower bound** (sky noise, including the measured noise neighbouring pixels share). Scoring uses the empirical `expectedScatter`; it is not a calibrated uncertainty on the measured amplitude.
- **Candidates are not confirmed variables.** Even `HIGH_CONFIDENCE` candidates should be checked against the images and a variable-star catalogue.
- **Aperture agreement assumes white noise.** The APERTURE gate's standard error treats successive frames as independent; noise correlated in time, for example from seeing changes, makes a difference look more significant than it is. A real variable blended with a neighbour that dilutes it in the large aperture can fail.
- **Usable-point adjacency can bridge gaps.** Persistence and serial statistics do not prove a state continued through missing observations.
- **Saturation is estimated heuristically.** Similar unsaturated maxima can resemble a clipping pile-up; there is no caller-supplied saturation limit.
- **Reference counts have different meanings.** Check B's default 50 references are counted inside its retained brightness range. An ideal selected sample needs at least 55 before further exclusions; selecting exactly 50 cannot satisfy that default check (VS-9).

These are limitations of the current implementation. The remaining proposals are listed in [FEATURE-REQUESTS.md](FEATURE-REQUESTS.md); closing a proposal does not change the documented behaviour. Earlier crossing-coordinate, timestamp/fragment interpolation and undefined-response findings are implemented fixes, not open proposals.

## 17. Output Assembly

The engine assembles stacks, masks, the data area, retained-frame detections, tracks, anomalies, camera-fixed/common-motion groups, residual candidates and optional photometry into `PipelineResult`. The maximum stack is shared with the slow-mover analysis. The field inventory, aliases, light-curve indexing and ownership are in [PIPELINE.md](PIPELINE.md#jtransientenginerunpipeline).

## 18. Runtime Diagnostics

The engine records non-overlapping elapsed wall time for major phases, plus tracking substep timing. Parallel worker CPU time is not summed. Engine time excludes application loading, Auto-Tune and report export. [PIPELINE.md](PIPELINE.md#timing-and-frame-exclusion-telemetry) defines timing fields and exclusions; its [progress contract](PIPELINE.md#progress-reporting) defines listener ranges. Diagnostics remain behind the debug switches.

<a id="auto-tuning"></a>
## 19. Auto-Tuning

JTransient supplies a measured, calibrated tuner (`CalibratedAutoTuner`). It takes a base config and a profile, and returns an `AutoTunerResult`; signatures, reusable calibration and fallback contracts are in [PIPELINE.md](PIPELINE.md#calibratedautotunertune). The earlier score-based tuner was removed on 2026-10-23.

Profiles are `LOW`, `MEDIUM`, `HIGH` and `MAXIMUM`. `AutoTuneProfile.parse(...)` also accepts conservative, balanced and aggressive as aliases for the first three. The current methods are described here; the configurable detection fields/defaults are documented once in [CONFIG.md](CONFIG.md).

<a id="calibrated-auto-tuning"></a>
### 19.1 Calibrated tuning

#### Idea

Instead of scoring proxies, the calibrated tuner tries a grid of settings on crops of the session's own frames and measures, for each combination:

- **false positives**: detections that a setting would report although nothing is there
- **sensitivity**: how many faint objects of known brightness it would find
- **mask coverage**: how much sky the star mask hides

Each profile is a false-positive budget per megapixel per frame. The budget counts false single-frame detections, not what a user finally sees (tracks, anomalies and residual candidates); a run within budget can still show a few false tracks. Among the combinations within budget, the tuner takes the one that recovers the most synthetic sources (near-ties go to the one that keeps more bright sources). A more permissive profile can therefore never be less sensitive.

| Profile | Budget (false positives per MPix per frame) |
| --- | --- |
| `LOW` | 0.05 |
| `MEDIUM` | 0.4 |
| `HIGH` | 1.5 |
| `MAXIMUM` | 3.5 |

Because the budget is per megapixel per frame, the number of false detections a profile allows grows with the sensor size and the number of frames. On a 61-megapixel sensor with 33 frames, `MAXIMUM` allows several thousand; it is meant for small sensors and targeted searches.

#### Tuned values

One calibration measures all profiles. `configFor(...)` applies a chosen profile to a base-config clone without repeating the measurement. The calibrated tuner supplies per-frame sigma/grow/minimum pixels, master sigma/grow/minimum pixels, mask overlap and measured jitter; other fields retain their base values. The API/result contract is in [PIPELINE.md](PIPELINE.md#calibratedautotunertune).

#### Preparation

1. **Unusable frames are left out.** Frames where less than half of the pixels hold image data (blank frames, failed registrations) are dropped. At least 5 usable frames are needed.
2. **Crops.** The frames are cut into square crops of up to 1024 px: the centre and four corners on ordinary frames, an even grid of up to 4 x 4 on large ones. The border kept clear of the frame edges is the measured drift padding plus 50 px (between 50 and 200 px). Crops outside the intersection of usable-frame coverages are dropped. Where crops overlap, each searchable pixel is counted in one crop only, excluding the 3 px crop search margin.
3. **Measurement frames.** Ten frames, evenly spaced; small sensors use more (up to 40) so that about 30 megapixel-frames are measured.
4. **Star size and jitter.** The star FWHM is measured from the area above half the peak of bright, isolated stars (the core width; second moments would include the wings). The residual registration jitter is measured from master-to-frame star distances.
5. **Three images per crop-frame:** the real frame; a negative image `2 x master - frame`, which has the noise of a frame but no real objects; and a copy with synthetic stars injected (see below).
6. **Streak pass.** A more sensitive extraction (2.5 / 1.5 sigma) of each real crop joins the fragments of faint satellite or meteor trails into elongated objects. Detections within one FWHM of such a streak (lying mostly outside the star mask) are treated as real objects, not false positives.

#### The settings grid

- Detection sigma 2.5 to 6.0; grow sigma = detection sigma minus 0.75 or 1.25.
- Minimum pixels: 0.3, 0.5, 0.75, 1, 1.5, 2 and 3 times the star core area (pi x (FWHM/2)^2), at least 3 and at most 15 pixels (`MAX_MIN_PIXELS`). Without the upper limit, wide stars would lead to sizes of 25 pixels and more, which reject small faint movers.
- Master sigma 1.5 to 5.0; master grow = master sigma minus 0, 0.5 or 1.0; master minimum pixels 3 or 0.25, 0.5 and 1 times the core area. The star mask is grown by max(1, round(jitter / 2)) px, and its stars are found in the pipeline's two passes (one sky level, then the local sky), both as in the pipeline.
- Veto overlap 0.5, 0.65, 0.75, 0.85 and 0.90.

Settings that already drown in noise before any veto are skipped. Crop evaluation uses the pipeline's extraction, master-star and star-mask routines. It does not execute the downstream linkers, anomaly/residual stages, slow-mover branch or photometry.

#### What is measured

**False positives**, per megapixel per frame:

- **noise**: detections in the negative image that survive the veto
- **star leakage**: real-frame survivors that touch the star mask; or reappear within the recurrence radius (max(1.5 px, jitter)) in another frame, on the sky or on the sensor (hot pixels move with the drift across registered frames; the sensor test only applies between frames whose drift differs); or lie within one FWHM of a star and reappear beside it in another frame
- **real-frame excess**: real-frame survivors above the lowest rate any setting reaches (faint stars visible only in better frames, seeing residuals and similar artefacts)

The false-positive figure is the larger of noise + leakage and the real-frame excess. A cautious estimate, (count + 1) / measured area, is compared with the budget. A budget smaller than one event over the measured area cannot be confirmed; it is relaxed to "no events".

**Sensitivity (the artificial star test).** On in-memory copies of the crops, the tuner injects Gaussian stars shaped like the session's measured stars, 12 per brightness level per crop-frame, at known positions at least 30 px inside the crop and peak SNR 2, 3, 4, 5, 6, 8, 10 and 15. For each combination it counts the injected stars that are detected near their position and survive the veto. This gives the completeness per level, the recovered fraction (mean over levels), the bright recovery (SNR 8 and above) and SNR50, the peak SNR at which half are found. The injected stars never reach the input files or the pipeline run.

#### Reading the report

The telemetry report lists the measurement area, the star FWHM and jitter, skipped settings and crops, the choice for every profile with its measurements, the best setting per detection sigma, the completeness curve of the chosen setting, and the final configuration.

#### Known limitations

- The injected stars are static point sources. A slow mover whose path crosses faint stars included only in a deep mask can be vetoed in many consecutive frames; the tuner cannot foresee that for a particular path. (Example: Apophis, which the deep 1.5-sigma mask removes while a 2.0-sigma mask keeps it; `MAXIMUM` finds it.)
- Diffuse objects such as comets are not represented by the injected stars, so the cost of a deep mask for them is underestimated.
- Runtime depends on image dimensions, crop/frame counts, the search grid and hardware; old session timings are not a performance guarantee.

### 19.2 Limits and intended use

The tuners provide a starting extraction configuration for a subsequent full pipeline run. They do not link tracks, run slow movers or photometry, export `PipelineResult`, or guarantee an optimum for every frame/downstream stage. False-positive budgets count surviving single-frame detections, not final false tracks or anomalies. Negative controls can retain structural residuals under changing seeing. Review telemetry and downstream results.

## 20. Design Decisions And Future Work

[FEATURE-REQUESTS.md](FEATURE-REQUESTS.md) records algorithm proposals, deferred limitations and maintainer decisions, including the declined ones. This reference combines the former detection, slow-mover, variable-star and auto-tuner guides; public entry points remain in [PIPELINE.md](PIPELINE.md) and field defaults in [CONFIG.md](CONFIG.md).

<a id="faint-mover-search"></a>
## 21. Experimental Faint-Mover Search

When `enableFaintMoverSearch` is on (it is off by default), `FaintMoverSearch` looks for point-like movers too faint for the single frames by digital tracking: the kept frames are shifted along trial motions and added, so a mover's light adds up while the noise averages down. It runs after residual analysis and before photometry, so it can mask the run's streaks and recognise its moving tracks. Its candidates are a separate list, `PipelineResult.faintMoverCandidates`, strongest first; no track, anomaly or other result changes. A candidate is unverified: its score is empirical, not a calibrated significance.

**Frames and times.** Every kept frame needs a capture time; mid-exposure times are used. A gap of over 120 minutes between consecutive kept frames separates sessions, since one constant motion does not hold across nights, and only the session with the most frames is searched (`framesOutsideSession` counts the others). At least 8 frames are needed.

**Search domain.** The star size is the kept frames' median measured FWHM converted to a Gaussian FWHM, as for the [faint-streak search](#135-faint-streak-search). Motions range from `faintMoverMinDisplacementFwhm` FWHM over the session to `faintMoverMaxSpeedPixelsPerMinute`, on a square grid whose step is the FWHM divided by the largest time from the session's mean mid-exposure, so that half a step moves the end frames by at most half the FWHM. The data area is searched in tiles of 512 px, each read with a margin for the largest shift.

**Residuals.** Per tile and frame:
1. the frame minus the median stack (the static field);
2. minus its local background: a 41 px running mean of the residual clipped to 3 robust sigmas about its centre, over unmasked pixels (the sky level changes during a session);
3. divided by the residual's robust sigma and clipped to ±5, so that one exceptional pixel or frame cannot dominate;
4. set to zero where masked: outside the data area or registration fill in any frame (grown 4 px), star cores of the median stack (more than 8 sky sigmas above the tile's sky, grown 3 px), and in its own frame each of the run's streaks (normal streak detections, faint-streak search candidates and streak-track detections), grown by one FWHM;
5. filtered with a Gaussian of the star size. Filtering commutes with whole-pixel shifts, so adding shifted filtered residuals gives the filtered stack of each motion.

**Score and peaks.** A motion's score at a position is its stack over the robust spread of such stacks in the tile (taken at a motion halfway through the speed range). The coarse search keeps each position's best motion; local maxima within 6 px that reach 0.7 × `faintMoverMinScore` are refined on a grid four times finer, one coarse step to either side and within 2 px, and must then reach `faintMoverMinScore`.

**Checks.** A refined peak is reported only when:
1. *both halves of the session support it.* The first and second half of the frames (in time) are stacked along its motion and must each reach 3 on the full-stack scale, normalised by the count of unmasked path centres. Masks are zeroed before PSF filtering; that filtering can spread neighbouring residual signal into a masked centre, and the half stack sums those processed samples too. A half with fewer than a third of its frames (at least 3) unmasked cannot be tested, as when the path crosses a bright star; at least one half must be tested.
2. *it moves on the sky.* Its score exceeds the best stationary stack within 4 px by at least 2.
3. *it is not fixed on the camera.* Each frame is read where its [registration](#1-border-drift-diagnostics) puts one camera pixel, within 4 px of the median camera position of the peak's path. The score must exceed that camera-fixed stack by at least 2, and the camera-fixed stack must stay below half the score. A hot pixel or defect adds up best at one camera position; it can add up along a straight sky path only partly, when the frames moved steadily. Needs usable registrations for at least half of the frames.
4. *it is not a duplicate, a tracked object or a side lobe.* A weaker peak within 2.5 FWHM and 1.5 coarse steps of another repeats it (neighbouring tiles). A peak whose path passes a point track's timed detections within 2 FWHM (median) is that track's object. A peak whose path comes within 2 FWHM of a point track's detection, or of a peak at least twice as strong, at the same time in any frame is a side lobe: that frame's light makes it.

Each candidate carries its position at the session's mean mid-exposure time, its motion, its score, half scores (NaN when a half cannot be tested), stationary and camera-fixed scores and a 31 × 31 px cut-out of its filtered stack. `PipelineTelemetry.faintMoverSearchTelemetry` records the search domain and, in order, how many peaks each check removed; the text report shows them.

**Bounded review evidence.** After a peak passes the tile's score and artifact checks, small residual rectangles are copied while that tile is resident. A run-owned, synchronized 32 MiB review pool prioritises stronger peaks using deterministic score/position/motion/tile ordering. Global candidate selection remains unchanged and transfers only selected candidates' evidence; no full tile or input image is retained. Reviews are limited to 128 frames and 256 pixels per side. Budget/geometry limits produce `diagnostics.unavailableReason` and never reject a candidate. `diagnosticsAvailable` and `diagnosticBytes` describe the selected evidence. Collection adds bounded work within the existing cooperative search budget; it does not repeat tile preprocessing after selection.

**Live work and phase progress.** Joined tile workers account for estimated preparation, coarse-motion and peak-check work, with globally throttled notices and completed-tile counts during long tiles. Only a requested observer snapshot copies active-tile corners and trial-path endpoints on an example original frame; there are no copied residual pixels, score images or implied detections. The application keeps its shared three-second gate, coalesces pending work and renders a bounded source crop. No mask, arithmetic, ordering or selection rule uses progress or preview values. Notices respect the existing cooperative deadline; synchronous observer delays consume run time like other observer callbacks. Skipped, completed and truncated outcomes remain distinct.

Each review frame preserves the original sequence index and midpoint, the actual whole-pixel scoring-path sample, its centre-mask test and locally normalised PSF-filtered residual signal. Masks are zeroed before filtering, so neighbouring signal can spread into a masked centre; the centre flag remains explicit. Samples are neither independent detections nor photometry, uncertainties or calibrated S/N. The first/second-half maps use the original tile noise, mask count, time order and full-stack normalisation. Their central refinement-area maxima reproduce the reported half scores; a half without sufficient support has no map. Candidate positions retain their original subpixel refinement. These additive diagnostics change no threshold, score or candidate-selection rule.

**Resources.** Tiles are searched in parallel in batches of 32. One tile's work holds every searched frame's residual of the tile with its margin, about (512 + 2 × margin)² × (4 × frames + 12) bytes, and the engine limits the workers to fit. The search is not run when it would need more than 100,000 coarse motions or a quarter of the heap for one tile, and it stops after `faintMoverMaxSeconds`, reporting `truncated` and the tiles searched. The run time grows with coarse motions × frames × pixels, about 7 × 10⁻¹¹ s per unit on a 16-core desktop: for a fixed cadence and speed range, with the cube of the session length.

**Reference data (2026-11-03, shipped profile with the search on).**

| Session | Frames, span | Motions | Search time | Candidates |
|---|---|---|---|---|
| Klangwolke-2 | 28, 75 min | 720 | 74 s | 11 |
| Klangwolke-1 | 9 kept; 7 in the largest session | - | - | not searched: fewer than 8 frames |
| Africano | 12, 155 min | 7,140 | 10 s | 0; shuffled times: 2 |
| Apophis | 14, 17 min | 16 | 0 s | 0; shuffled times: 2 |
| Eagle-noisy | 43, 117 min | 1,988 | 8 s | 1; shuffled times: 1 |
| Iwamoto-huge | 14, 20 min | 64 | 1 s | 0; shuffled times: 0 |
| dc-reg | 52, 87 min | 2,140 | 18 s | 0; shuffled times: 0 |
| helix-reg | 39, 115 min | 2,072 | 11 s | 1; shuffled times: 0 |
| ic-5070-reg | 45, 118 min | 1,308 | 8 s | 0; shuffled times: 0 |
| m101-reg | 106, 233 min | 10,116 | 313 s | 1; shuffled times: 0 |
| m31-b-reg | 32, 89 min | 1,824 | 7 s | 0; shuffled times: 0 |
| m31-g-reg | 33, 96 min | 2,864 | 13 s | 0; shuffled times: 0 |
| m31-red-reg | 43, 94 min | 1,612 | 14 s | 0; shuffled times: 0 |
| m5-reg | 32, 94 min | 1,604 | 9 s | 0; shuffled times: 0 |
| m81-reg | 97, 189 min | 5,700 | 201 s | 0; shuffled times: 0 |
| M81 Kumar | 39, 353 min | 39,340 | 878 s, before within-tile time-limit checks | 6 in that earlier run |
| whynot (uncalibrated) | 59, 87 min | 1,004 | 17 s | 2; shuffled times: 2 |

On Klangwolke-2, the search finds all five known asteroids of predicted magnitude 18.5-19.0 that the profile misses, without using their predictions: 74326 (score 22.7), 25729 (21.4), 43283 Robinbock (14.5), 77716 (13.9) and 26239 (13.5). It also finds 4767 Sutoku (17.7 mag, 47.5), which this profile does not track; 611 Valeria matches its track. Five candidates match no known object above the 19.0 mag limit of the session's catalogue fetch; one of them (score 15.3) moves visibly in the early, middle and late frames.

<a id="faint-mover-validation"></a>
**Deeper candidate review (2026-11-05).** A new full-field production replay reproduces all 11 Klangwolke-2 candidates and their numeric decision fields. The five previously unmatched paths now have compatible known-object predictions in separately recorded, candidate-local 256 px field queries to predicted V magnitude 23. Detection remains blind; these predictions are compared only after the original candidates are fixed. The comparisons use all 28 searched mid-exposure positions and the admitted corrected WCS, with the existing 2 FWHM position and 0.1 px/min motion tolerances.

| Original finding | Compatible predicted object | Predicted V | Path RMS (px) | Motion discrepancy (px/min) |
|---|---|---:|---:|---:|
| FM4 | 68067 (2000 YM72) | 19.13 | 0.589 | 0.0255 |
| FM8 | 180044 (2003 AJ88) | 19.68 | 0.458 | 0.0161 |
| FM9 | 98339 (2000 SG296) | 19.15 | 0.324 | 0.0073 |
| FM10 | 222169 (2000 AR152) | 20.20 | 0.877 | 0.0370 |
| FM11 | 11579 Tsujitsuka (1994 JN) | 19.40 | 0.630 | 0.0237 |

Each path has one compatible returned identity; FM4's other returned object, 531365 (2012 RE42), is incompatible at about 169 px RMS. The known positive FM2/74326 comparison reproduces at 0.186 px RMS. Thus all 11 original paths have compatible predictions after the deeper review, rather than six under the earlier magnitude-19 inventory. Initial failed requests remain recorded as unavailable; only successful responses without reported fetch problems support these comparisons. The observer comes from the native headers, with no invented site fallback.

This is catalogue compatibility, not a calibrated identity probability or new-object claim. The returned formal orbit uncertainties are 3-sigma predictions and do not include the detector's centroid or WCS error; no total astrometric confidence is inferred. Predicted V is not a calibration of the native image passband. The [JPL field-search API](https://ssd-api.jpl.nasa.gov/doc/sb_ident.html) can exclude objects with unknown magnitudes, and these small field queries do not establish full-field completeness to V=23. The [Horizons observer-ephemeris documentation](https://ssd.jpl.nasa.gov/horizons/manual.html#observer-table) describes the prediction quantities.

Bounded original moving/stationary patches and blinks cover all 11 paths in all 28 retained frames. Disjoint early/middle/late raw coadds and the actual scoring-path samples show the star-wing/mask context, including changing availability on several faint paths. Their full-data fitted paths and scoring reference are shared: these views are descriptive, not independent detections or significance tests. A compatible asteroid can still have contaminated measurements. The previous unmatched list must not be treated as negative truth for tuning residual rejection.

**Matched-domain validation milestone (2026-11-05).** A separately frozen study completes 462 searches: 77 per field on Klangwolke-2, M81 Kumar, helix, ic-5070, Africano and UG 6524. Each native/null/injection/policy comparison uses the same two fixed native 512 px grid tiles, with full-size native arrays and coordinates. All selected tiles finish, including M81 at the unchanged 1 px/min speed limit. Prepared retained identities, median/maximum/mask hashes and ordinary tracks agree with accepted production results. The study search reproduces production candidates, review pixels and scientific counters exactly; all 77 K2 cases also agree exactly between the original and computation-sharing research implementations. Only elapsed time differs. This reuse is confined to identical complete preprocessing/coarse arrays; every policy still performs its own refinement, checks and global selection.

The baseline finds zero native candidates in these 12 selected tiles (2,930,220 searched pixels), zero across all 18 shuffled-time controls from three fixed seeds, and two across six sign-reversed-residual controls, one each in helix and ic-5070. These are restricted-domain, unverified control counts. They do not establish a survey false-positive rate or assess the unsearched field. In particular, M81's completed two-tile evaluation supplies matched controls and injections without revalidating the six candidates from its older run, whose original candidate evidence was not recovered.

Faint-stage injections rebuild the exact changed-pixel median while preserving native encoding, registration fill and saturation. Six batches cross Gaussian/Moffat profiles with nominal per-frame aperture SNR 1.5/2.5/4, actual exposure integration and 20% seeing variation. The upstream QC, streaks and ordinary tracks remain fixed; this is not end-to-end injection recovery. Of 180 planned moving histories, 170 are available: six star crossings have no qualifying fixed-domain star and four touch saturation. Baseline associations are 82/170: 20 slow, 23 fast, 13 gapped, 26 mildly curved and zero of 26 available star crossings. These are source histories, not independent objects or trials. By profile/nominal SNR, the counts are:

| Profile | 1.5 | 2.5 | 4 |
|---|---:|---:|---:|
| Gaussian | 6 / 29 | 15 / 29 | 22 / 28 |
| Moffat | 5 / 28 | 12 / 28 | 22 / 28 |

Source association uses path RMS within one search FWHM and motion discrepancy within 0.1 px/min, with the predefined mean motion for curves. A post-hoc same-policy native-blank check finds no matching source contacts; that supports attribution without proving injected light dominates every measurement. Nominal SNR is neither physical magnitude nor calibrated significance, and the omitted source-photon noise, quantization, mask/PSF mismatch and reused backgrounds limit generalisation. The strong field dependence and shared sources preclude treating these counts as independent binomial trials.

**Individual and paired gates.** Every policy runs on native data, the first shuffled seed, Gaussian nominal SNR 2.5 and a bright first-exposure flash batch. The moving column below has 29 available paired histories; the flash column has 36 available source controls. Native/shuffled counts describe all reported candidates in the selected footprints, not classified false detections.

| Policy | Moving associations | Native candidates | Shuffled candidates | Attributed flashes |
|---|---:|---:|---:|---:|
| Current baseline | 15 | 0 | 0 | 0 |
| Remove half check | 15 | 0 | 0 | 0 |
| Remove stationary margin | 15 | 1 | 0 | 0 |
| Remove camera margin | 15 | 0 | 0 | 0 |
| Remove camera share limit | 15 | 0 | 0 | 0 |
| Remove both camera checks | 15 | 0 | 0 | 0 |
| Remove duplicate suppression | 15 | 0 | 0 | 0 |
| Remove ordinary-track matching | 15 | 0 | 0 | 0 |
| Remove side-lobe rejection | 15 | 0 | 0 | 0 |
| Remove track and side-lobe checks | 15 | 0 | 0 | 0 |
| Remove star-core mask | 15 | 409 | 292 | 2 |
| Remove streak mask | 15 | 0 | 0 | 0 |
| Remove final clipping | 14 | 101 | 150 | 34 |
| Coarse keep fraction 0.35 | 15 | 0 | 0 | 0 |
| Final score 6 | 20 | 126 | 104 | 17 |
| Peak radius 3 px | 15 | 0 | 0 | 0 |
| Remove half check and final clipping | 14 | 336 | 509 | 35 |

Removing the star mask gains two star-crossing histories (Africano/helix) but loses the helix fast and K2 slow histories, so the unchanged total is not an unchanged source set. It also widens native searched coverage to 3,084,851 pixels and increases candidate load substantially. Removing clipping loses the helix fast history without a moving-source gain. Lowering the score adds five moving associations but also more native/shuffled candidates and 17 attributed flashes. These comparisons do not justify either change. Preprocessing changes both the proposal pool and empirical noise scale; removing a mask or clip need not monotonically improve recovery.

Half-check removal alone has no measured benefit here. With clipping removed, its removal raises flash-case candidate load from 1,022 to at least 2,913 across the six fields. The paired removal reaches the production 1,000-candidate ceiling in the M81 and UG first-flash cases, making candidate loads lower bounds. All six planned flash positions already have associations in each capped field; the one unassociated position is in uncapped ic-5070, so the observed source count is still 35/36 under the fixed criterion. Candidate ceilings are resource limits, not scientific rejections; production's `belowScore` counter can include ceiling drops. Separately, 54 case results lack some bounded review pixels. Missing diagnostics do not reject their candidates or establish that they are artifacts.

**Availability and conditional redundancy.** All 36 camera-fixed injections have usable construction metadata, but none is an eligible full-path control for this association rule and motion domain: K2, M81, Africano and UG camera trajectories depart from their best straight paths by more than one FWHM, while helix and ic-5070 drift below the nominal speed floor. Their zero associations cannot validate or invalidate the camera gates. A separate code/predicate audit does establish that, for a finite score `S >= 4`, the retained share condition `camera < S/2` already implies the 2-point camera margin. That explains the margin-only ablation's inactivity at the studied floors 8 and 6. The configuration accepts positive score floors below 4, where this implication fails (for example, score 3 and camera 1.25 pass the share condition but fail the margin). There is no basis for deleting that lower-score behavior. Stationary, streak, duplicate and camera checks with little or no effect in these footprints remain insufficiently tested for removal; quiet controls or shared purposes are not proofs of global redundancy. The local background model/3-sigma background clip, upstream QC and input-validity rules were not individually ablated.

**Separate subset and encounter checks.** Three blind full-field searches use disjoint 9/9/10-frame K2 thirds, each with its own rebuilt median, actual seeing measurements and corresponding streak/track points. All 247 tiles finish in each third. They return 2/2/3 candidates and 0/2/3 strict associations to the original full-session paths: FM2/FM6 in the middle and FM1/FM2/FM6 late. One early candidate lies only 0.31 px from FM2 at its reference epoch but misses the fixed motion tolerance (0.134 px/min discrepancy); that criterion is not loosened after seeing it. Several slow reference paths fall below the subsets' nominal coarse-motion floor. These are conditional image-subset checks with shared upstream QC/data area, not independent nights, calibrated confidence, or evidence that an unmatched/absent subset object is false.

A separately frozen targeted follow-up injects 12 distinct perpendicular crossings/near passes of the native longest ordinary point track, at two encounter epochs, separations 0/3/8 FWHM and two flux levels. Baseline, no-track, no-side-lobe and paired removal all associate the same three injected histories, all at 8 FWHM separation; none has a matching native-blank contact. Across the repeated injected cases, candidate load is 3/3/28/40 respectively. On the single distinct native control tile it is 0/0/2/3. Thus the extra candidates do not demonstrate extra source recovery, and the unresolved closer-pass losses cannot be assigned to the removed gates alone. Proposal construction/refinement, masking and other selection mechanisms need diagnosis before changing a late veto; close/parallel/blended paths remain an acceptance priority.

**Decision and scope.** Retain the current scientific gates/defaults. Star masking, clipping and the tested score relaxation show material sensitivity/control trade-offs; no blanket removal earns adoption. Investigate local treatment of star crossings and source/proposal separation, preserve explicit availability and test steady camera drift and boundary/short sequences before stronger conclusions. The joint SM-F1/SM-F3 support and centroid-uncertainty findings remain applicable as separate evidence: they do not calibrate digital-tracking thresholds. Planning checks on 3/5/8 frames confirm eligibility behavior only, with no claimed pixel sensitivity for the skipped short cases. SM-F1 temporal confirmation, broader SM-F6 validation and any scientific rule change remain open; this bounded milestone supplies the baseline for an independent SM-F7 proposal-method comparison.

All frozen sources/classes/libraries/configurations, 744 production/build/test source files and 818 original image hashes remain unchanged; native array/median ownership and exact bounded-crop/coadd checks pass. These sequential diagnostic JVMs use four processors and an 11 GiB heap limit. Whole-job times include preparation, parity checks, reuse and serialization: about 72 minutes for M81 and 24 for UG, with recorded process working-set peaks across the six jobs about 1.88-8.33 GiB. They are research-workload observations, not production benchmarks or per-policy memory costs. Standalone candidate, gate, injection and supplementary figures were inspected without observing-site metadata. Production code, settings, reports and classification policies are unchanged.

<a id="faint-mover-complementary-proposals"></a>

**SM-F7 matched-verifier comparison (2026-11-05).** A separately frozen [four-field study](docs/research/slow-movers/10-complementary-proposals/CONCLUSION.md) compares digital tracking D with temporal-bin proposals T4/T3 and PSF-filtered frame-point proposals P3/P2.5. All retain the current complete-sequence verifier and final selection. The four existing observations, Apophis, Africano, dc-reg and m5-reg, were outside the six-field SM-F6 grid; they are not new observing nights. Two fixed native cores per field, the full admitted motion range through 1 px/min, 48 general and 24 targeted pair batches supply 240 available moving histories, 96 stationary/flash controls and 20 native/shuffle/negative contexts. Seven result families produce 644 rows.

| Route | Complete contexts / 92 | General associated / complete histories | Pair associated / complete histories |
|---|---:|---:|---:|
| D | 92 | 87 / 192 | 21 / 48 |
| T4 | 92 | 13 / 192 | 25 / 48 |
| T3 | 73 | 29 / 144 | 25 / 36 |
| P3 | 24 | 1 / 48 | 3 / 12 |
| P2.5 | 23 | 1 / 48 | 3 / 12 |
| D+T4 | 92 | 87 / 192 | 27 / 48 |
| D+P3 | 24 | 1 / 48 | 3 / 12 |

T4 is a complement in these targeted pairs, not a replacement for D: the union adds six weak companions, five in dc-reg and one in m5-reg, with no general-grid increment or selection loss. Recomputing all upstream processing on the six full injected sequences retains five gains beyond ordinary tracks and D; one loses association after QC changes the kept-frame set. A separate exact upstream replay finds no compatible maximum-stack history for those target companions under the prior SM-F1/SM-F3 criterion. These are selected artificial histories on existing backgrounds, not five independent astronomical discoveries or full-pipeline completeness for the entire grid. Source photon noise is omitted and nominal aperture SNR is not calibrated magnitude.

All 20 D/T4/union native/null contexts complete with zero candidates. Candidate load across repeated cases increases from 113 D paths to 136 union paths for six conditional source-history gains; repeated/ambiguous paths and unmatched findings remain explicit. T3 reaches the 50,000-seed prototype boundary in 19 cores, P3 in 136 and P2.5 in 138. Incomplete contexts are excluded from clean sensitivity comparisons; they do not establish a scientific miss. The lower-threshold T3 standalone gains are diagnostic: a D+T3 union was not tested. Warm route timings and shared preparation are separate, with fixed-order/JIT and whole-process-memory limits stated in the archive.

All 72 accepted diagnostic batch replays match D candidates, review hashes and non-time counters. A corrected admitted-motion-grid oracle diagnoses the old twelve K2 encounters without treating them as new validation: three blind recoveries, three admissible local seeds surviving selection without blind recovery, three local camera cuts, one local score cut and two compatible local peaks removed as side lobes. Exact-velocity oracle grid phase and an unavailable-half-score comparator correction are preserved as explicit amendments. The [combined filter assessment](docs/research/slow-movers/10-complementary-proposals/CONCLUSION.md#what-this-changes-in-the-filter-review) retains current gates/defaults while preserving concrete masking and side-lobe counterexamples. Neither oracle failure nor an inactive late-cut ablation exhausts possible source-compatible paths.

Production code, settings and reports remain unchanged; 757 production/build/test artifacts and 161 original images retain their frozen hashes. Temporal proposals merit independent close-pair/source-attribution examples before an integration contract is agreed. Frame-point proposal density/cost, short sequences, steady-camera controls and broader validation remain open. The archive contains protocols, all denominators, bounded input/score evidence, source/frame provenance and separate confirmation/validation records.

**Native Klangwolke-2 follow-up (2026-11-05).** The requested [uninjected full-field application](docs/research/slow-movers/11-klangwolke-native/CONCLUSION.md) completes all 247 production tiles in D and T4 over the same 28 retained frames and 49,540,332 unmasked reference pixels, through maximum speed 1 px/min. D reproduces the existing eleven candidates exactly; T4 finds seven corresponding to FM1-FM7; the globally reselected union returns the same eleven. Its full owned candidate JSON, including every cutout/review array, is byte-identical to D. There is no additional path or baseline loss. FM8-FM11 have no compatible verified T4 peak reaching final selection; ordered proposal/cut counts do not establish filter redundancy. The four conditional null contexts are not triggered because the union adds no path, so this is not false-alarm calibration.

Native stack/mask/track products, the actual-library baseline, recorded candidate/review hashes and non-time counters match. Forced-eviction/streaming controls preserve the frozen method; 757 production artifacts and all 33 input images retain their hashes. Both searches complete well within the original 600-second ceiling despite a separately recorded 3600-second research allowance; no seed/candidate limit is reached. The archive includes real-frame/coadd views, all streamed proposal/rejection ledgers and source correspondences without site metadata. This real-data result supplies no K2 sensitivity gain and does not convert the earlier selected close-pair injection gains into observed new objects. K2 is reused development data, not an independent validation night. Current scientific gates/defaults and production integration remain unchanged.

**Earlier injection/control reference (2026-11-03).** Injected movers (352 on Klangwolke-2, Gaussian of the star size, flux scaled from Sutoku's, 0.15-0.5 px/min in any direction) were recovered at a score of 8 or more in 89-95 % at magnitude 18.0, 91-93 % at 18.5, 93 % at 19.0 and 80-82 % at 19.5. Most misses lie mainly in star masks. Null controls (shuffled frame times, and residuals mirrored about the median stack) left 0-2 candidates per session at 8 or more on the other sessions; on Klangwolke-2, 2-4 per control, all but one next to Sutoku, Valeria or 194 Prokne, whose light a shuffled or mirrored control turns into peaks that real frame times do not make.

**Application acceptance (2026-11-04).** The agreed first delivery is integrated in [SpacePixels Faint Movers](../SpacePixels/MANUAL.md#faint-movers-experimental), off by default, with settings, resource guidance, unverified FM report evidence, maps/CSV/JSON, catalogue comparisons and searched-frame viewer overlays. The final full-size acceptance runs measured the following search-stage results; times depend on available workers and heap and exclude loading and report export.

| Session | Frames in selected session | Completed tiles | Search time | Result |
|---|---|---|---|---|
| Klangwolke-2 | 28 | 247 / 247 | 90.1 s | 11 reference candidates reproduced; six paths compatible with saved predictions |
| Klangwolke-1 | 7 of 9 kept | not searched | — | skipped: fewer than 8 frames in the largest kept session |
| M81 Kumar | 39 | 18 / 110 | 604.8 s for a 600 s budget | truncated; 0 retained candidates in the completed coverage |
| UG 6524 | 43 | 36 / 36 | 276.2 s | completed; 0 retained candidates |

The M81 Kumar limit is cooperative and is now acceptance-tested within tiles. Its partial zero-candidate result does not assess the remaining tiles or revalidate the six candidates from the earlier, longer run above. The 18-dataset off-mode comparison preserved scientific results and exported data/images apart from generated timestamps and measured timings; ordinary detections and photometry were also unchanged in the enabled acceptance cases. These checks establish integration and coverage behavior, while broader sensitivity/control calibration remains open.

**Limitations.** One constant motion, whole-pixel shifts and one star size per run. Paths mostly inside star masks are lost. On frames that drifted steadily without dither, a mover moving with the drift cannot be told from a hot pixel. Uncalibrated data still gives a few candidates from hot pixels lined up by chance. Temporal-variance weighting near bright stars is not implemented, and structured stellar residuals can survive the current checks. The cost grows quickly with long sessions and fast speed limits. Follow-up work is tracked as [residual suppression (SM-F4)](FEATURE-REQUESTS.md#sm-f4-bright-star-residuals-in-faint-mover-searches), [runtime/memory scaling (SM-F5)](FEATURE-REQUESTS.md#sm-f5-faint-mover-runtime-and-memory-scaling), [validation (SM-F6)](FEATURE-REQUESTS.md#sm-f6-faint-mover-validation-and-candidate-review), and [complementary mechanisms (SM-F7)](FEATURE-REQUESTS.md#sm-f7-complementary-high-sensitivity-moving-object-searches).
