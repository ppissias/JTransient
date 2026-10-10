# JTransient DetectionConfig Guide

`DetectionConfig` is the runtime configuration object for extraction, frame rejection, slow-mover analysis, tracking, and variable-star photometry.

All fields are public and mutable. A few implementation details matter when you use it:

- `JTransientEngine` may raise `voidProximityRadius` during drift diagnostics if the data demands it
- the engine uses stage-local config overrides for master-star and slow-mover extraction so the caller's config object is not left mutated afterward
- the auto-tuners (`CalibratedAutoTuner.tune(...)`, and the legacy `JTransientAutoTuner.tune(...)`) clone configs while searching and return an optimized clone on success; fallback paths may return the base config

This document tracks the fields that actually exist in `src/main/java/io/github/ppissias/jtransient/config/DetectionConfig.java`.

## 1. Extraction And Blob Classification

### `detectionSigmaMultiplier` (default `5.0`)

Primary seed threshold for `SourceExtractor.extractSources(...)`.

- seed threshold = `backgroundMedian + backgroundSigma * detectionSigmaMultiplier`
- higher values reduce sensitivity and false positives
- lower values detect fainter structure but increase noise
- both auto-tuners search this field

### `growSigmaMultiplier` (default `3.0`)

Secondary hysteresis threshold used while the BFS expands a blob.

- lower than the seed threshold by design
- controls how much faint edge structure is retained
- too low can leak into noise
- master-star extraction uses a stage-local `growSigmaMultiplier = masterSigmaMultiplier`
- slow-mover extraction uses a stage-local `growSigmaMultiplier = masterSlowMoverGrowSigmaMultiplier`
- both auto-tuners search this field

### `minDetectionPixels` (default `10`)

Minimum blob size required before shape analysis.

- rejects hot pixels and tiny noise islands
- applies to the main extraction pass
- both auto-tuners search this field

### `edgeMarginPixels` (default `15`)

Dead zone around the physical image border.

- only applies to non-streak detections
- streaks bypass this filter
- the engine temporarily sets it to `5` for master-star extraction

### `voidThresholdFraction` (default `0.5`)

Threshold fraction used to define black registration voids.

- extraction computes `voidValueThreshold = backgroundMedian * voidThresholdFraction`
- border drift diagnostics use a separate signed-short padding threshold when scanning inward from the image edges

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
- unmatched single-frame streaks below this significance stay as preserved standalone streak detections instead of becoming one-point streak tracks

### `enableBinaryStarLikeStreakShapeVeto` (default `true`)

Enables the binary-star-like shape veto for unmatched single-frame streaks.

- when enabled, short wide single-frame streaks that look like merged double stars can be rejected before promotion to one-point streak tracks
- only applies to unmatched single-frame streak candidates
- disabling it preserves compatibility with cases where such shapes are expected real streaks

### `bgClippingIterations` (default `3`)

Number of sigma-clipping passes for the histogram-based background estimate.

- more iterations better isolate the sky background
- too many iterations can overfit unusual histograms

### `bgClippingFactor` (default `3.0`)

Sigma width used to clamp the histogram bounds during background estimation.

- lower values clip bright structure more aggressively
- higher values preserve more of the original histogram

### `strictExposureKinematics` (default `true`)

Optional physical speed limit for point-like detections.

- used in time-based point linking when timestamps and exposure durations exist
- not used by the geometric fallback linker
- if enabled, a point source is not allowed to jump farther than its footprint and exposure time imply
- useful when long exposures should physically blur fast movers into streaks
- not ideal for short glints or intermittent flashes

## 2. Master Stack And Slow-Mover Extraction

### `masterSigmaMultiplier` (default `2.25`)

Seed threshold for extracting stationary objects from the median master stack.

- usually lower than the main detection sigma
- used with `masterMinDetectionPixels`
- the calibrated auto-tuner searches this field; the legacy tuner preserves it
- master stars are grown at `masterGrowSigmaMultiplier` (`0` = the master sigma itself)

### `masterMinDetectionPixels` (default `3`)

Minimum size for master-stack objects.

- lower values produce a more complete stationary-star map
- can also increase mask density if set too low
- the calibrated auto-tuner searches this field; the legacy tuner preserves it

### `masterGrowSigmaMultiplier` (default `0.0`)

Hysteresis threshold used to grow master-map stars after they are seeded at `masterSigmaMultiplier`.

- `0` uses the master sigma itself (the previous behaviour, no hysteresis)
- a lower value extends star footprints into their faint wings without seeding new noise islands
- values above `masterSigmaMultiplier` are capped to it
- the calibrated auto-tuner searches this field; the legacy tuner preserves it

### `enableSlowMoverDetection` (default `true`)

Master switch for the slow-mover branch.

The slow-mover branch extracts candidate footprints from the maximum stack and builds an exact object-footprint mask from the median stack. It reports morphological candidates; motion is not temporally confirmed.

### `masterSlowMoverMinPixels` (default `15`)

Minimum blob size for slow-mover extraction.

- applied to both the maximum-stack candidates and median-mask objects

### `masterSlowMoverSigmaMultiplier` (default `4.0`)

Seed threshold for the slow-mover extraction pass.

- applied to the maximum stack
- also applied to the comparison extraction on the median master stack

### `masterSlowMoverGrowSigmaMultiplier` (default `3.5`)

Temporary grow threshold used only during the slow-mover extraction pass.

- slow-mover extraction uses a stage-local `growSigmaMultiplier = masterSlowMoverGrowSigmaMultiplier`

### `slowMoverMinAxisRatio` (default `1.35`)

Minimum geometric major/minor extent ratio of a maximum-stack connected footprint. The moment-based `elongation` remains available as a separate diagnostic.

The extents are measured by projecting raw pixels along and across the intensity-derived orientation, adding one pixel to each range. For a capsule-like source, `axisRatio - 1` is an approximate total displacement in source diameters.

### `slowMoverMaxAxisRatio` (default `3.20`)

Maximum geometric axis ratio, intended to exclude longer tracks handled by the normal mover pipeline.

### `slowMoverMinFillFactor` (default `0.0`)

Optional minimum raw-pixel count divided by the area of the oriented bounding rectangle. Zero disables this veto while preserving the measured fill factor in diagnostics.

### `slowMoverMedianSupportOverlapFraction` (default `0.00`)

Minimum fraction of a maximum-stack footprint that must overlap the exact median-stack object mask.

- `0.0` allows a blank-sky candidate through the lower bound
- larger values require median-mask support but may reject one-frame transients and real movers alike

### `slowMoverMedianSupportMaxOverlapFraction` (default `0.80`)

Maximum fraction of a maximum-stack footprint that may overlap the exact median-stack mask.

- lower values reject candidates that look too similar to stationary median-stack sources
- `1.0` disables this upper veto; the effective upper bound is clamped to at least `slowMoverMedianSupportOverlapFraction`

### `slowMoverMinFrameSupport` (default `0.0`)

Minimum percentage (0–100) of usable quality-filtered frames with significant localized signal inside a maximum-stack candidate's footprint. The measurement searches a small aperture within the footprint in each original frame, so the source can move between positions. It requires integrated signal-to-noise of at least `5.0`; this measurement floor is fixed. Zero disables the rejection gate but still records the measured percentage.

### `slowMoverMaxStationaryLikelihood` (default `100.0`)

Maximum allowed stationary-likelihood percentage (0–100). This heuristic is the largest fraction of supported frame positions clustering within the configured star-jitter scale of one location. It is not a calibrated probability. A high value indicates a persistent source at nearly the same position, but genuinely slow motion below the positional resolution can also score high. `100.0` disables the rejection gate but still records the measured percentage.

The frame measurements use local background subtraction independently of per-frame `SourceExtractor` detections. Frame support is unavailable with fewer than two usable frames; stationarity is unavailable unless at least three supported frames span half the retained sequence. Unavailable measurements cannot veto a candidate. No three-stage stack is built.

The former percentile-stack, elongation-baseline, and residual-footprint settings have been removed. No maximum-minus-median subtraction is used.

Accepted candidates, geometric motion estimates, overlap fractions, frame-support and stationary-likelihood percentages, and stage counters are exported through `PipelineResult.slowMoverAnalysis` and `PipelineResult.telemetry.slowMoverTelemetry`. Each candidate's diagnostics include both percentages, availability flags, and supported/usable frame counts. The aggregate telemetry also exposes aligned percentage and availability lists for accepted candidates.

`estimatedMotionPixels = majorExtent - minorExtent` is a footprint-based estimate, not a measured astrometric displacement. The telemetry shape distributions include size-qualified maximum-stack objects, while overlap distributions include objects that reach median-mask evaluation.

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

### `qualityBrightStarPeakSigmaOffset` (default `30.0`)

Additional peak sigma required above `qualitySigmaMultiplier` before a measured quality star
contributes to the bright-star eccentricity metric.

- keeps the secondary shape gate focused on genuinely bright stars
- uses the same extracted quality stars; it does not trigger a second extraction pass

### `qualityBrightStarMinStars` (default `8`)

Minimum number of bright stars required before the bright-star eccentricity metric is considered valid.

- if fewer stars qualify, the bright-star metric is reported as unavailable
- the ordinary median eccentricity metric still remains valid

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

### `minBrightStarEccentricityEnvelope` (default `0.10`)

Absolute floor on the bright-star eccentricity rejection envelope.

- prevents the bright-star gate from becoming over-sensitive on extremely stable sessions

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
- both auto-tuners measure and update this field

### `maxMaskOverlapFraction` (default `0.75`)

Fraction of an object's footprint that may overlap the veto mask before the object is purged.

- purge rule: `overlapFraction > maxMaskOverlapFraction`
- larger values are more permissive near stars
- smaller values are stricter
- both auto-tuners search this field

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

### `streakTimeConsistencyTolerance` (default `0.50`)

Relative projected-speed tolerance for multi-frame streak tracks.

- used when timestamps exist on streak detections
- evaluated on one aggregated centroid sample per frame, so same-frame streak fragments do not count as separate time steps
- intentionally looser than `timeBasedVelocityTolerance` because faint streak parts can be missed or broken up

### `enableGeometricTrackLinking` (default `false`)

Controls whether the frame-agnostic geometric point-track linker runs when timestamps are available.

- when timestamps are available, `false` uses the time-based linker without the geometric point-linking fallback
- when timestamps are missing, geometric linking still runs because it is the only point-track linker available
- only affects point-track linking; streak linking still runs separately

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

### `enableBrightStarEccentricityFilter` (default `true`)

Enables the dedicated session-level rejection gate based on bright-star eccentricity.

- the bright-star metric is still measured for telemetry even when this gate is disabled

### `brightStarEccentricitySigmaDeviation` (default `3.0`)

Controls how far bright-star median eccentricity may rise above the session median before rejection.

### `backgroundSigmaDeviation` (default `3.0`)

Controls how far the background median may deviate from the session median before rejection.

### `zeroSigmaFallback` (default `0.001`)

Fallback sigma used when a MAD-derived sigma would be exactly zero.

- prevents divide-by-zero style threshold collapse

## 6. Anomaly Rescue

### `enableAnomalyRescue` (default `true`)

Enables the final single-frame rescue pass.

- scans the exported merged transient list after tracking, so preserved standalone streak detections are eligible too
- only rescues detections that were not consumed by accepted tracks

### `anomalyMinPeakSigma` (default `8.0`)

Minimum peak significance required for anomaly rescue.

- based on `DetectedObject.peakSigma`
- sharp one-frame glints can still be rescued through this path alone

### `anomalyMinIntegratedSigma` (default `12.0`)

Minimum integrated significance required for the diffuse anomaly-rescue path.

- based on `DetectedObject.integratedSigma`
- helps rescue broader flashes whose energy is spread across more pixels

### `anomalyMinIntegratedPixels` (default `25`)

Minimum footprint size required for the integrated-sigma anomaly path.

- only applies to the diffuse integrated-sigma rescue branch
- suppresses tiny streak fragments and compact artifacts that happen to have enough total flux

### `anomalyMinPeakSigmaFloor` (default `3.0`)

Minimum local peak required before the integrated-significance rescue path is allowed.

- prevents broad low-contrast mush from being rescued just because it has enough total flux
- does not affect the main sharp-glint path governed by `anomalyMinPeakSigma`

### `anomalyMinPixels` (default `15`)

Minimum footprint size required for anomaly rescue.

- acts as the general anomaly floor before either rescue branch is considered
- protects against hot pixels and tiny defects

### `suspectedStreakLineTolerance` (default `6.0`)

Maximum perpendicular centroid error allowed when rescued same-frame anomalies are grouped into one suspected streak line.

- this is separate from `predictionTolerance`
- larger values make same-frame faint-streak grouping more permissive
- smaller values require tighter collinearity
- multiple disjoint suspected streaks can be returned from one frame when separate same-frame lines survive
- does not affect the main multi-frame point-track linker

## 7. Residual Transient Analysis

### `enableResidualTransientAnalysis` (default `true`)

Master switch for post-processing leftover non-streak point detections after normal tracking.

- runs on `PipelineResult.unclassifiedTransients`
- exports results through `PipelineResult.residualTransientAnalysis`

### `enableLocalRescueCandidates` (default `true`)

Enables the object-like local rescue candidate pass.

- returns weak heuristic candidates as `MICRO_DRIFT`, `SPARSE_LOCAL_DRIFT`, or `LOCAL_REPEAT`
- these are kept separate from ordinary confirmed tracks

### `enableLocalActivityClusters` (default `false`)

Enables the broader leftover-point activity clustering pass after accepted local rescue candidates are removed.

- uses the same per-cluster metrics structure as local rescue candidates
- never reuses points already consumed by accepted local rescue candidates

### `localActivityClusterRadiusPixels` (default `10.0`)

Linkage radius used for broad residual activity clustering.

- larger values merge nearby leftovers into bigger review clusters
- smaller values keep the review bucket more fragmented

### `localActivityClusterMinFrames` (default `3`)

Minimum number of unique frames required before a broad local activity cluster is exported.

### Residual Rescue Heuristics

The detailed local-rescue thresholds are now engine-internal rather than public `DetectionConfig` fields.

- this keeps the public config surface small
- the three rescue kinds still use the same underlying heuristics and metrics
- if we need more control later, the better direction is a small profile-style knob rather than exposing 20+ raw thresholds again

## 8. Variable-Star Photometry

Optional stage that runs after track linking on the frames that passed session rejection. It measures forced aperture photometry of isolated master stars in every frame, checks whether the frames respond linearly to light, solves an ensemble model `m(i,j) = M(i) + Z(j) + a(j)x + b(j)y`, and scores each star against stars of similar brightness. Results are in `PipelineResult.variableStarAnalysis`; session and per-frame diagnostics are in `PipelineResult.telemetry.photometryTelemetry`.

Readiness verdicts:

- `READY`: checks A, B and D passed
- `LIMITED`: no check failed, but sky pixels are clipped at zero, the linear range is under `linearityMinRangeMag + 1`, or check D was inconclusive; only amplitudes of at least `variableLimitedMinAmplitudeMag` reach high confidence
- `NOT_READY`: a check failed or too few frames remain; light curves are still returned for diagnostics but nothing is scored

A pure power-law stretch applied from zero passes all checks, so `READY` means no non-linearity was detected, not that linearity is proven.

Apertures use a FWHM measured in this stage from second moments of bright stars. The extraction FWHM (`sqrt(lambda1 + lambda2)`) reads about 1.4 times larger for round stars and is only used to size the moment window and as a fallback.

### `enableVariableStarDetection` (default `false`)

Master switch for the whole stage.

### `photometryMaxStars` (default `0`)

Maximum number of stars measured; `0` measures every usable star. When a cap is set, each of an 8 x 8 grid of field regions contributes its share, taken at evenly spaced brightness ranks so the whole magnitude range is covered. Stars whose master-stack peak is already above `photometrySaturationFraction` of the saturation level are never measured.

### `photometryMinSnr` (default `10.0`)

Stars whose median per-frame signal-to-noise ratio (main aperture, sky noise only) is below this are still measured and exported, but kept out of the ensemble and the variability scoring. `0` disables the cut.

### `photometryMaxElongation` (default `1.5`)

Master stars more elongated than this are not measured.

### `photometryApertureFwhmFactor` (default `1.5`)

Main aperture radius in units of each frame's FWHM. Each brightness range of stars is scored with whichever of 0.7, 1.0, this value or 2.0 x FWHM gives its constant stars the least scatter (radius at least 2 px); this value stays unless another lowers the scatter by more than 3%.

### `photometryAnnulusInnerFwhmFactor` / `photometryAnnulusOuterFwhmFactor` (defaults `3.0` / `5.0`)

Sky annulus radii in FWHM. Pixels of photometry's own star list (extracted from the median stack at 3 sigma, independent of the detection settings) are masked in the annulus. A star is not measured when its neighbours add more than 2% light to its main aperture, a neighbour lies within 1 FWHM, or a neighbour with at least 10% of its flux lies within the inner radius. A frame detection marks a star as crossed only when it peaks at 5 sigma or more and reaches the inner radius.

### `photometrySaturationFraction` (default `0.85`)

A measurement is flagged saturated when its peak exceeds this fraction of the session saturation level. The level is the pile-up of clipped pixels at the top of the frame histograms, or 95% of 65535 when no frame shows one.

### `photometryFitPlane` (default `true`)

Fits the per-frame plane term `a(j)x + b(j)y`. Static spatial patterns (vignetting, missing flats) are absorbed by the star magnitudes `M(i)` either way.

### `photometryMaxRegistrationSpreadPixels` (default `0.5`)

Frames whose bright-star centroids scatter more than this (robust sigma, pixels) around the median offset are excluded.

### `linearityMinDistinctLevels` (default `1024`)

Check A. Fewer distinct pixel levels in the middle frame means 8-bit origin or heavy quantisation; the session is refused.

### `linearityMaxFloorClippedFraction` (default `0.01`)

Check A. When the median share of sky-annulus pixels at zero exceeds this, negative sky noise was clipped (for example calibrated float data converted to 16-bit) and the session is limited.

### `linearityMaxConcentrationDrift` (default `0.03`)

Check B. The concentration index is the flux within 0.7 x FWHM divided by the flux within 2.5 x FWHM, using stars with SNR of about 50 or more. The faint half of those stars sets the reference; walking towards brighter stars, the first magnitude bin that departs by more than this value (plus two standard errors of the bin median) marks the frame's linear limit. Brighter stars are flagged non-linear in that frame.

### `linearityMinRangeMag` (default `2.0`)

Check B. A frame whose linear range is narrower than this is excluded.

### `linearityMinStars` (default `50`)

Check B. Minimum number of high-SNR stars in a frame (and inside its linear range), and the minimum number of selected stars for the stage to run.

### `linearityMaxFrameSlope` (default `0.01`)

Check D. A frame fails when its ensemble residual slopes against magnitude by more than this (mag per mag) and by more than 3 standard errors. Failing frames are excluded and the ensemble is solved again.

### `linearityMinZeroPointRangeMag` (default `0.05`)

Check D. Below this zero-point spread (5th to 95th percentile) there is no lever arm and the slope-tracking test is inconclusive.

### `linearityMaxSlopeTrackingCorrelation` (default `0.7`)

Check D. The session fails when the per-frame slopes correlate with the zero point or the sky level at least this strongly and the implied slope change over that range exceeds `linearityMaxFrameSlope`.

### `linearityMaxFailingFrameFraction` (default `0.2`)

Checks B and D. Share of frames that may fail before the session is `NOT_READY`.

### `variableMinFrames` (default `20`)

Minimum usable measurements for a star to be scored, and minimum frames remaining for the session.

### `variableMinSpanMinutes` (default `30.0`)

DATA gate: minimum time span of a candidate's usable measurements. Ignored without timestamps.

### `variableNoiseModelNeighbors` (default `50`)

Stars nearest in magnitude used for the expected scatter and the robust z-scores.

### `variableScoreSigma` (default `5.0`)

Both the excess-scatter z-score and the Stetson J z-score must reach this for a star to become a candidate.

### `variableMinAmplitudeMag` / `variableLimitedMinAmplitudeMag` (defaults `0.05` / `0.1`)

AMPLITUDE gate: minimum robust amplitude (95th minus 5th percentile) for `READY` and `LIMITED` sessions.

### `variableAmplitudeNoiseFactor` (default `4.0`)

AMPLITUDE gate: the amplitude must also exceed this multiple of the expected scatter.

### `variableMinPersistenceFrames` (default `5`)

PERSISTENCE gate: longest run of consecutive measurements deviating on the same side by more than 1.5 x expected scatter.

### `variableMinSplitHalfCorrelation` (default `0.5`)

SPLIT_HALF gate: correlation between consecutive non-overlapping measurement pairs. This guards against noise, not against shared systematics.

### `variableMaxApertureAmplitudeDifference` (default `0.3`)

APERTURE gate: amplitudes from the 1.0 x and 2.0 x FWHM apertures may differ by at most this fraction of the main amplitude.

### `variableMaxSystematicsCorrelation` (default `0.6`)

SYSTEMATICS gate, applied to the frame zero point, FWHM, the star's local sky and the x/y registration offsets. The gate fails when either:

- the light curve's changes from one frame to the next correlate with the systematic's changes more strongly than this (the star follows it frame by frame, as a blend leaking with seeing does; smooth real variability barely changes between frames), or
- the raw correlation exceeds this and the candidate's amplitude is within `variableSystematicsResponseFactor` of what constant stars show for that systematic.

Raw correlation alone does not fail the gate, because a real variable that brightens or fades steadily correlates with any steady drift in the session.

### `variableSystematicsResponseFactor` (default `2.0`)

SYSTEMATICS gate: for each correlated systematic, the response of every constant star is measured (slope of its light curve against the systematic times the systematic's 5th-95th percentile range). The gate fails only when the candidate's amplitude is at most this factor times the 99th percentile of those responses among the 200 constant stars nearest in magnitude, i.e. when the systematic could plausibly have produced the change. The limit for the most correlated systematic is exported as `StarLightCurve.systematicsLimitMag`.

### `variableLocalRadiusPixels` / `variableMaxLocalCorrelation` (defaults `100.0` / `0.3`)

LOCAL gate: median absolute correlation with up to 10 nearby constant stars must not exceed this; at least 3 are required.

Candidates that pass every gate are `HIGH_CONFIDENCE`, one failure gives `POSSIBLE`, more give `REJECTED`. A LINEARITY gate also fails when the star was flagged saturated or non-linear in any frame.

## 9. Interaction With The Auto-Tuner

There are two auto-tuners. Both take an `AutoTuneProfile` (`LOW`, `MEDIUM`, `HIGH` or `MAXIMUM` = as sensitive as possible; the legacy tuner treats maximum like high) and return a `JTransientAutoTuner.AutoTunerResult`. Neither reads extra fields from `DetectionConfig`; their settings are static fields of the tuner classes.

### `CalibratedAutoTuner` (measured)

Blank or failed-registration frames are left out first. It then measures, on crops of the session frames (the centre and four corners; an even grid of up to 4 x 4 crops on large frames; the border kept clear is the measured drift padding plus 50 px, between 50 and 200 px; overlapping crops count each pixel once; small sensors use more frames, up to 40, to cover about 30 megapixel-frames), for every combination of per-frame settings, master-mask settings and veto overlap:

- noise false positives, from a negative image `2 x master - frame` (the noise of a frame, no real objects)
- star leakage: real-frame detections that survive the veto and touch the star mask, recur at the same place on the sky or on the sensor (hot pixels follow the drift across registered frames), or lie within one FWHM of a star and reappear beside it in another frame
- other real-frame detections above the lowest rate any setting reaches (faint stars visible only in better frames, seeing residuals and similar artefacts that depend on the settings)
- satellite and meteor trails, which are not charged: a more sensitive extraction of every frame (2.5 / 1.5 sigma) joins trail fragments into one object, and detections within one FWHM of a streak found that way (mostly outside the star mask) are left out
- sensitivity: the fraction of synthetic point sources, shaped like the session's measured stars (FWHM from the area above half maximum) and injected over peak SNR 2 to 15, that are detected and survive the veto
- mask coverage

A profile is a budget of false positives per megapixel per frame (noise plus star leakage, or the real-frame excess if larger) (`FALSE_POSITIVE_BUDGET_PER_MPIX_FRAME`, default 0.05 / 0.2 / 0.6 / 3.5; maximum is meant for small sensors or targeted searches, since a per-megapixel budget leaves many candidates on a large sensor). Within the budget it picks the settings that recover the most synthetic sources, so a more permissive profile is never less sensitive; near-ties go to the setting that keeps more bright sources. Budgets smaller than one event over the measured area cannot be confirmed and are relaxed to "no events". `CalibratedAutoTuner.calibrate(...)` returns every measurement and the choice for all three profiles.

It sets `detectionSigmaMultiplier`, `growSigmaMultiplier`, `minDetectionPixels`, `masterSigmaMultiplier`, `masterGrowSigmaMultiplier`, `masterMinDetectionPixels`, `maxMaskOverlapFraction` and `maxStarJitter`. Minimum-pixel candidates for frames and master are derived from the measured star FWHM. Master settings are tried both deeper and shallower than the per-frame grow threshold: a shallower mask hides less sky but lets star wings leak, and the measured leakage counts against the budget.

### `JTransientAutoTuner` (legacy, score-based)

Kept for comparison. It optimizes or measures:

- `detectionSigmaMultiplier`
- `growSigmaMultiplier`
- `minDetectionPixels`
- `maxMaskOverlapFraction`
- `maxStarJitter`

Everything else in the returned config comes from the base config you provided.

## 10. Practical Starting Point

If you do not have strong prior knowledge of the dataset:

1. start from `new DetectionConfig()`
2. run `CalibratedAutoTuner.tune(...)` (or the legacy `JTransientAutoTuner.tune(...)`)
3. use the returned `optimizedConfig` with `runPipeline(...)`
4. only hand-tune fields like `enableSlowMoverDetection`, `strictExposureKinematics`, `maxJumpPixels`, or anomaly thresholds after reviewing telemetry
