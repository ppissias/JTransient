# Variable-Star Detection Algorithm

This document describes how the optional variable-star stage of `JTransientEngine.runPipeline(...)` works. It measures the brightness of the stationary stars in every frame, decides whether the frames can be trusted for photometry at all, and then looks for stars whose brightness changes more than stars of similar brightness in the same session.

`CONFIG.md` section 8 lists every configuration field and its default. This document explains how those fields are used, and which fixed constants are in the code.

The code is in `io.github.ppissias.jtransient.photometry`:

| Class | Role |
| --- | --- |
| `VariableStarAnalyzer` | Runs the stages in order and combines their results |
| `PhotometryStarSelector` | Picks measurable master stars; estimates the saturation level |
| `ApertureMeasurer` | Measures FWHM, registration offset, sky and forced aperture fluxes on one frame |
| `PhotometricReadinessChecker` | Readiness checks A, B and D |
| `EnsembleSolver` | Ensemble photometry solve |
| `VariabilityScorer` | Noise model, scores, candidate gates and tiers |
| `PhotometryMath` | Robust statistics, neighbour statistics, weighted line fits |
| `VariableStarAnalysis`, `StarLightCurve`, `PhotometricReadiness`, `PhotometryFlags`, `VariabilityTier` | Results |

---

## 1. Design Principles

- **A missed variable is better than a false one.** Every stage tends to exclude data or reject candidates rather than keep them. A candidate must pass two independent scores and then eight gates to be reported as high confidence.
- **Compare stars with stars of similar brightness in the same session.** The noise model is measured from the session's own stars, not taken from formal errors. A star is variable only if it scatters much more than stars of the same magnitude, measured the same way.
- **Check the data before trusting it.** Stretched, 8-bit, clipped or non-linear frames make constant stars look variable. The readiness checks look for these problems first, and when they find them nothing is scored.
- **Flag measurements instead of dropping them.** Every star keeps a value for every frame, and problems are recorded as per-measurement flags, so callers can display the full light curve and see why points were excluded.
- **Magnitudes are instrumental and differential.** No catalogue calibration, colour terms or filter transformations are applied. `deltaMag` is the change against the star's own session mean, positive when fainter.

---

## 2. Position In The Pipeline

The stage runs only when `enableVariableStarDetection` is `true`, as phase 5 of `runPipeline(...)`. It runs after track linking and residual transient analysis. `detectTransients(...)`, `generateMasterStack(...)` and the auto-tuner never run it.

It uses outputs of the earlier phases:

| Input | Source | Used for |
| --- | --- | --- |
| Clean frames | Frames that passed session rejection, chronological | Measurements |
| Frame metrics | `FrameQualityAnalyzer` | Extraction FWHM and background per frame |
| Frame detections | All extracted objects of each frame, before the stationary-star veto | Crossing flags |
| Master stars and median master stack | Phases 2–3 (or `providedMasterStack`) | Star selection and positions |
| Master veto mask | `TrackLinker` | Master-star pixels removed from sky annuli |
| Tracks | `TrackLinker` | Crossing flags |
| Slow-mover candidates | Slow-mover analysis, when enabled | Crossing flags |

The result is `PipelineResult.variableStarAnalysis`. It is `VariableStarAnalysis.empty()` (verdict `NOT_RUN`) when the stage is disabled. The same telemetry object is attached as `PipelineResult.telemetry.photometryTelemetry`.

The stage reports its progress through `VariableStarAnalyzer.Input.progress` (0 to 100: measuring the frames up to 80, then the readiness checks, the ensemble solve and the scoring). `runPipeline(...)` shows it as 93-99% of the pipeline.

---

## 3. Stage Overview

```text
clean frames
    |
    v
check A: quantisation (distinct pixel levels in the middle frame)
    |
    v
session FWHM, saturation level, star selection
    |
    v
forced aperture photometry on every frame (in parallel)
    |  five apertures per star, sky annulus, registration offset, flags
    v
crossing flags (moving detections, tracks, slow movers)
    |
    v
frame exclusion: registration spread
    |
    v
check A: zero-floor clipping
    |
    v
check B on every frame: concentration index vs magnitude
    |  -> per-frame linear limit, NONLINEAR flags, frame exclusion
    v
shape check per measurement: CONTAMINATED flags (hot pixels, cosmic rays)
    |
    v
star exclusion: mostly non-linear, low SNR
    |
    v
ensemble solve  m(i,j) = M(i) + Z(j) + a(j)x + b(j)y
    |
    v
check D: residual slope vs magnitude, per frame and across the session
    |  -> exclude failing frames, solve again
    v
verdict: READY / LIMITED / NOT_READY
    |
    v
light curves for every star
    |
    v
(READY or LIMITED only) variability scoring and gates
    |
    v
tiers: HIGH_CONFIDENCE / POSSIBLE / REJECTED / CONSTANT / NOT_SCORED
```

The stage returns early with `NOT_READY` and no light curves when:

- fewer than `max(3, variableMinFrames)` clean frames exist, or the master stack or master stars are missing
- no frame has a usable FWHM (finite, above 0.5 px and below `errorFallbackValue`)
- fewer than `linearityMinStars` stars survive selection

---

## 4. Session FWHM And Saturation Level

**Session FWHM.** The session FWHM is the median of the per-frame `medianFWHM` values from the quality analysis. A frame without a valid value uses the session median. This extraction FWHM is computed as `sqrt(lambda1 + lambda2)`, which reads about 1.4 times too large for round stars. It is therefore only used for star selection, to size the moment window, and as a fallback. Apertures are sized from the FWHM that `ApertureMeasurer` measures (section 6.1).

**Saturation level.** `PhotometryStarSelector.estimateSaturationLevel` looks for a pile-up of clipped pixels at the top of each frame's histogram. It counts the pixels within 0.1% of the frame maximum, and five or more count as a pile-up. The frame maximum is then that frame's saturation level. The session level is the median over the frames with a pile-up. When no frame has one, it is 95% of 65535 (all pixel values are in the shifted non-negative domain). Check B still guards against roll-off below full well in that case.

The working threshold is `photometrySaturationFraction x saturationLevel`.

---

## 5. Star Selection

`PhotometryStarSelector.select` goes through the master stars and drops, in this order:

1. streaks (`isStreak`)
2. stars more elongated than `photometryMaxElongation`
3. stars closer to the edge than `max(edgeMarginPixels, ceil(outer annulus radius) + 1)`, where the outer annulus radius here is `photometryAnnulusOuterFwhmFactor x FWHM + 2 px` (the extra 2 px allows for residual registration offsets), and stars whose outer annulus touches void padding (any master-stack pixel at or below `voidThresholdFraction x` the master-stack sky median)
4. crowded stars: another master star within `(photometryApertureFwhmFactor + 1) x FWHM`, or a neighbour with at least 10% of the star's flux within `photometryAnnulusInnerFwhmFactor x FWHM`
5. stars whose master-stack peak (3 x 3 around the centre) is already at or above the saturation threshold

Bright stars below saturation are kept on purpose: check B needs them to find where linearity ends. Their measurements in frames where they are too bright are flagged later.

When `photometryMaxStars > 0` and more stars survive, the cap is spread across an 8 x 8 grid of field regions. Each region contributes the same fraction of its stars, taken at evenly spaced brightness ranks. Taking the brightest stars would fill a rich field with near-saturated stars and leave too few in the linear range.

The selected stars are sorted brightest first. Their master-stack positions are the forced photometry positions.

---

## 6. Forced Aperture Photometry

`ApertureMeasurer.measure` runs once per frame. When the engine has an executor, frames run in parallel.

### 6.1 Registration offset and measured FWHM

Before measuring fluxes, the frame's FWHM and residual registration offset are measured from up to 50 bright stars. The brightest tenth of the selection is skipped because those stars may be saturated. For each star:

- the sky is the clipped median of an annulus (from the extraction FWHM)
- centroid and second moments are computed within a circular window of radius `max(3, 1.5 x extraction FWHM)`
- `FWHM = 2.355 x sqrt((Mxx + Myy) / 2)`

The frame offset is the median centroid offset from the master positions. `registrationSpread` is the robust sigma (1.4826 x MAD) of the offset distances around that median. The measured FWHM is the median over those stars. With fewer than five usable stars, the offset is zero, the spread is undefined, and the extraction FWHM is kept.

### 6.2 Apertures and sky

Each star is measured at its master position plus the frame offset, in five concentric apertures:

| Index | Radius | Used for |
| --- | --- | --- |
| `R_CI_INNER` | 0.7 x FWHM | Concentration index (check B) |
| `R_SMALL` | 1.0 x FWHM | APERTURE gate |
| `R_MAIN` | `photometryApertureFwhmFactor` x FWHM | Photometry |
| `R_LARGE` | 2.0 x FWHM | APERTURE gate |
| `R_CI_OUTER` | 2.5 x FWHM | Concentration index (check B) |

Every radius is at least 1 px. Pixels near an aperture edge (within 0.75 px) get fractional weights from 5 x 5 sub-pixel sampling.

The sky annulus runs from `max(largest aperture + 1, photometryAnnulusInnerFwhmFactor x FWHM)` to `max(inner + 2, photometryAnnulusOuterFwhmFactor x FWHM)`. Master-star pixels (the veto mask) are excluded. The sky level and noise are a three-pass, 3-sigma clipped median and MAD sigma, and at least 10 pixels are required. The share of annulus pixels at exactly zero is also recorded, for check A.

All five apertures are summed in one pass over the pixels, so the concentration index, the photometry flux and the aperture-consistency gate all come from the same data.

### 6.3 Errors and flags

The main-aperture magnitude is `-2.5 log10(flux)`. Its error includes only the sky noise:

```text
sigma_F = sigma_sky * sqrt(N_ap * (1 + N_ap / N_sky))
magError = 1.0857 * sigma_F / flux
```

It ignores the star's own photon noise and gain, so it is a lower bound. Scoring uses the empirical noise model instead (section 11).

Flags set here:

| Flag | Condition |
| --- | --- |
| `EDGE_OR_VOID` | Annulus leaves the image, fewer than 10 sky pixels, more than 50% of annulus pixels at zero, or a main-aperture pixel below `voidThresholdFraction x` frame background |
| `SATURATED` | Main-aperture peak at or above the saturation threshold |
| `BAD_FLUX` | Main-aperture flux not positive (no magnitude) |

---

## 7. Crossing Flags

A moving object passing through a star's aperture or sky annulus changes its measured brightness for a few frames. This is the classic false variable. `VariableStarAnalyzer.flagCrossings` sets `CROSSING` on every star within the outer annulus radius (`photometryAnnulusOuterFwhmFactor x` frame FWHM) of:

Frame positions (detections, their pixels and track points) are first moved into the master system with the frame's measured registration offset, the same correction the apertures use (section 6), so a residual shift does not make stationary stars look like moving objects.

1. **Moving detections.** A detection in a frame that is not within `max(maxStarJitter, 0.5 x FWHM)` of any master star is not stationary. Each of its pixels flags the stars around it in that frame.
2. **Tracks.** For every frame in a track's time span, the position is interpolated linearly by sequence index between track points. This covers frames where the object merged with a star and was not detected. Each point is treated as a segment along its footprint (centre, angle, half of `majorExtent - minorExtent`); a star is flagged when its distance to the segment is within the outer annulus radius plus the point's width (its minor extent for a streak, otherwise the larger of FWHM and minor extent). A point source is a segment of length zero, so it flags a disc; a satellite streak flags a band along its line instead of every star within its length. Interpolated frames use the shape of the nearest track point.
3. **Slow-mover candidates.** The maximum-stack footprint covers the whole session, so its pixels flag nearby stars in every frame.

### 7.1 Contaminated shape

A hot pixel, cosmic ray or faint passing object inside an aperture changes the star's light by a large factor, sometimes in several frames in a row, so the isolated-outlier rule (section 11.1) cannot always remove it. It does change the star's shape, which real variability does not.

`VariableStarAnalyzer.flagContaminatedShapes` runs after check B:

1. Per frame, the median concentration index (flux within 0.7 FWHM over flux within 2.5 FWHM) of the clean measurements is the frame's seeing reference.
2. Each measurement's concentration is divided by that reference, and by the star's own median over frames, giving its fractional departure.
3. Stars are grouped by brightness (about ten groups, at least 30 stars each). The tolerance of a group is the robust sigma of the departures of all its measurements, at least 2%. Taking it from the group rather than from the star keeps a star contaminated in many frames from widening its own tolerance.
4. A measurement departing by more than 5 tolerances is flagged `CONTAMINATED` and left out of the solve and the scoring.

For faint stars the concentration index is noisy, so a hot pixel a few pixels off-centre can still pass; the aperture gate (section 11.4) then usually rejects the star.

---

## 8. Readiness Checks

There are three checks, labelled A, B and D. There is no check C in the current implementation.

### 8.1 Check A: quantisation and floor clipping

- **Quantisation.** If the middle frame has fewer than `linearityMinDistinctLevels` distinct pixel values, the data is 8-bit or heavily quantised. The check is `FAIL`.
- **Floor clipping.** If the median (over frames) share of sky-annulus pixels at zero exceeds `linearityMaxFloorClippedFraction`, negative sky noise was clipped. This happens, for example, when calibrated float data is converted to unsigned 16-bit. Faint-star fluxes are then biased high, and the check is `LIMITED`.

### 8.2 Registration exclusion

Frames whose `registrationSpread` exceeds `photometryMaxRegistrationSpreadPixels` are excluded. A large spread means the frame is not registered consistently across the field (for example, field rotation or distortion), so forced positions are wrong for some stars.

### 8.3 Check B: star shape against brightness

**Idea.** In linear data every unsaturated star has the same profile shape whatever its brightness, so the fraction of its flux in a small central aperture does not depend on magnitude. A non-linear stretch, or saturation and roll-off near full well, flattens the cores of bright stars, which then look broader than faint ones.

The concentration index (CI) is `flux(0.7 x FWHM) / flux(2.5 x FWHM)`. An inner radius of 0.7 FWHM is used because at 1.0 FWHM a Gaussian already has 94% of its flux inside and the index barely changes.

Per frame (`PhotometricReadinessChecker.analyzeShape`):

1. Use stars without `EDGE_OR_VOID`, `CROSSING` or `BAD_FLUX` and with `magError <= 0.05` (SNR of about 20 or more). The cut is wide enough that background-limited data, where stars saturate soon above the sky noise, still spans a usable magnitude range; the per-bin noise allowance absorbs the extra scatter of the fainter stars. If fewer than `linearityMinStars` remain, the frame is `INCONCLUSIVE` and is excluded.
2. Sort by magnitude. The **reference CI** is the median CI of the stars ranked between 50% and 90% of the brightness order (the faint half, without the faintest and noisiest tenth). The **faint bound** is the magnitude at the 90% rank.
3. Divide the stars from the brightest down to the faint bound into bins of `max(15, count / 15)` stars. Each bin's tolerance is `linearityMaxConcentrationDrift + 2 x` the standard error of the bin's median CI.
4. Walk towards brighter bins, starting at the faintest bin that still holds stars brighter than the reference ranks (bins wholly inside the reference scatter around it by definition, so they cannot end the walk). The first bin whose median CI departs from the reference by more than its tolerance marks the end of linearity. The **linear limit** is the brightest magnitude of the next fainter bin. If no bin departs, the limit is the brightest star.
5. **Linear range** = faint bound − linear limit. The frame passes when the range is at least `linearityMinRangeMag` and at least `linearityMinStars` stars lie inside it. A failing frame with no departing bin is linear over everything it can measure but too short to verify; its exclusion reason and the session message say so instead of calling it non-linear.

In every evaluated frame, including failing ones, all measurements brighter than the frame's linear limit are flagged `NONLINEAR`. Failing frames are excluded.

Session status:

- `INCONCLUSIVE` if no frame could be evaluated
- `FAIL` if more than `linearityMaxFailingFrameFraction` of the evaluated frames failed
- `LIMITED` if the median linear range is below `linearityMinRangeMag + 1` mag
- `PASS` otherwise

The binned profile of each frame is exported as `PhotometryFrameStat.concentrationProfile`.

### 8.4 Check D: response against magnitude

Check D runs on the ensemble solution (section 9). In linear data, a change of transparency scales every star's flux by the same factor, so every star's magnitude shifts by the same amount. The ensemble residuals of a frame therefore do not depend on magnitude. A non-linear response compresses bright and faint stars differently, so the residuals slope against magnitude, and the slope changes as the zero point or sky changes.

Per solved frame, a weighted line (weights `1 / sigma_i^2`) is fitted to residual against `M(i) − median(M)` over the usable measurements. The frame fails when `|slope| > linearityMaxFrameSlope` and `|slope| > 3` standard errors.

Across the session (at least 3 solved frames are needed, otherwise `INCONCLUSIVE`):

- The **zero-point range** is the 5th to 95th percentile spread of `Z(j)`. Below `linearityMinZeroPointRangeMag` there is no lever arm, and the zero-point test cannot conclude anything.
- **Slope tracking.** The per-frame slopes are correlated with the zero point and with the frame sky median. A driver "tracks" when `|r| >= linearityMaxSlopeTrackingCorrelation` and the fitted change of slope over the driver's 5–95% range is at least `linearityMaxFrameSlope`. Sky tracking is tested whatever the zero-point range.

The status is `FAIL` if the slopes track the zero point or the sky, or if more than `linearityMaxFailingFrameFraction` of frames fail. Otherwise it is `INCONCLUSIVE` when the zero-point range is too small, and `PASS` when it is not.

Frames that failed are then excluded and the ensemble is solved once more without them. Check D is not repeated on the second solution.

### 8.5 What the checks cannot see

A pure power-law stretch applied from zero, `f -> f^gamma`, passes all checks. Every star's profile is changed in the same way, so CI does not depend on brightness. Magnitudes become `gamma x m`, which the ensemble model fits exactly. Amplitudes in such data are scaled by `gamma`. `READY` means no non-linearity was detected, not that linearity is proven. Use original, unstretched frames.

---

## 9. Star Exclusion And The Ensemble Solve

### 9.1 Star exclusion

Before the solve, a star is kept out of the ensemble and the scoring when:

- more than half of its measured frames are flagged `SATURATED` or `NONLINEAR`, or
- its median per-frame SNR (`1.0857 / magError`) is below `photometryMinSnr` (when that is above 0)

Excluded stars stay in `VariableStarAnalysis.stars` with their flags, magnitude errors and `notScoredReason`. They never constrain the solve, so they have no `meanMag`, and their `deltaMag` values are `NaN`.

A measurement is **usable** for the solve when its star is not excluded, its frame is active, it has a finite magnitude, and none of `SATURATED`, `NONLINEAR`, `EDGE_OR_VOID`, `CROSSING`, `BAD_FLUX`, `CONTAMINATED` or `FRAME_EXCLUDED` is set.

### 9.2 Model

`EnsembleSolver` implements inhomogeneous ensemble photometry (Honeycutt 1992):

```text
m(i,j) = M(i) + Z(j) + a(j) * x(i) + b(j) * y(i)
```

- `M(i)`: mean instrumental magnitude of star `i`
- `Z(j)`: zero point of frame `j` (transparency, airmass, exposure)
- `a(j), b(j)`: per-frame plane over normalised positions `x, y` in [-1, 1] from the frame centre. This absorbs gradients that change between frames, such as passing thin cloud or a moving gradient. It is fitted only with `photometryFitPlane` and at least 30 usable stars in the frame; otherwise only `Z(j)` is fitted.

Static spatial patterns such as vignetting or missing flats are constant per star, so `M(i)` absorbs them either way.

Every star in the field serves as a comparison star, not a hand-picked few. The solution remains well defined when individual stars are missing from some frames.

### 9.3 Iteratively reweighted least squares

Initial values: `M(i)` is the median of the star's usable magnitudes, and `sigma_i` is their robust sigma. Then, for up to 15 iterations:

1. **Frame solve.** For each active frame with at least 10 usable stars, solve the 3 x 3 weighted normal equations for `Z, a, b` (or `Z` alone), with weights `w_ij / sigma_i^2`.
2. **Gauge fix.** Subtract the median `Z` and the mean `a`, `b` over solved frames. Without this, `M` and `Z` can trade a constant.
3. **Star solve.** `M(i)` is the `w_ij`-weighted mean of `m(i,j) − Z(j) − a(j)x(i) − b(j)y(i)`.
4. **Reweight.** Each star's own robust residual sigma is compared with the expected sigma, which is the median own sigma of the 50 stars nearest in magnitude. Then `sigma_i = max(own, expected)`, with a floor of 0.001 mag. Point weights are Cauchy weights, `w_ij = 1 / (1 + (r_ij / (3 sigma_i))^2)`.

The loop stops when no zero point moves by more than 1e-5 mag.

A variable star has a large own sigma, so `1 / sigma_i^2` lowers its weight in the frame solve. The variables remove themselves from the reference without having to be identified first. The Cauchy weights also limit the effect of individual bad points that no flag caught. Using `max(own, expected)` stops an unusually quiet star from getting too much weight.

Residuals `m − model` are returned for every finite measurement on a solved frame, flagged or not, so callers can still plot flagged points. Frames that cannot be solved are excluded ("Too few usable stars for the ensemble").

---

## 10. Verdict

| Verdict | Condition |
| --- | --- |
| `NOT_READY` | Check A `FAIL`, check B `FAIL` or `INCONCLUSIVE`, check D `FAIL`, or fewer than `variableMinFrames` frames still active |
| `LIMITED` | Not `NOT_READY`, and check A `LIMITED`, check B `LIMITED`, or check D `INCONCLUSIVE` |
| `READY` | Otherwise |

`PhotometricReadiness.messages` explains each finding in plain language.

Light curves are built for every star whatever the verdict. `deltaMag` is the main-aperture ensemble residual, and the flags include `FRAME_EXCLUDED` for inactive frames. Scoring runs only for `READY` and `LIMITED`. In a `NOT_READY` session every star is `NOT_SCORED`.

---

## 11. Variability Scoring

`VariabilityScorer.score` works on the usable residuals of each star. Before scoring, the ensemble is solved twice more, once with the 1.0 x FWHM aperture and once with the 2.0 x FWHM aperture, using the same rules. These solutions are for the APERTURE gate.

### 11.1 Light-curve cleaning

- A star with fewer than `variableMinFrames` usable measurements is `NOT_SCORED`.
- **Isolated outliers.** A point more than 4 robust sigmas from the median is removed and flagged `OUTLIER`, unless a neighbouring usable point deviates by more than 2 sigmas on the same side. Single-frame events (cosmic rays, hot pixels, satellite glints that were not detected) are dropped. Real dips or flares that span several frames are kept.
- From the cleaned curve: `scatter` (sample standard deviation), `amplitude` (95th minus 5th percentile), and `timeSpanMinutes` (first to last usable mid-exposure Julian date; `NaN` if any usable frame has no timestamp).

### 11.2 Noise model and scores

The **noise model** is empirical. For each star, `expectedScatter` is the median scatter of the `variableNoiseModelNeighbors` stars nearest in mean magnitude (excluding the star itself). Their robust sigma is the spread. A star with no positive expected scatter is `NOT_SCORED`.

Two scores measure different things:

- **Excess scatter.** `scatterZ = (scatter − expectedScatter) / spread`, with the spread floored at 5% of the expected scatter. This measures how much more the star varies than its peers. `excessScatter = scatter / expectedScatter` is also reported.
- **Stetson J** (Stetson 1996). It is computed over consecutive usable points, using `expectedScatter` as the error of every point. It is large when consecutive measurements deviate in the same direction, which is the signature of a change spread over time rather than random noise. `stetsonJZ` is its robust z-score against the same neighbours, with the spread floored at 0.02.

A star is a **candidate** when both `scatterZ` and `stetsonJZ` are at least `variableScoreSigma`. Otherwise it is `CONSTANT`. Requiring both scores rejects noisy stars with uncorrelated scatter (high scatter, low J) and also slow drifts that are too small to matter (high J, low scatter).

### 11.3 Systematic responses

Before the gates run, `SystematicsContext` measures how every scored star responds to five per-frame **drivers**:

| Driver | Series |
| --- | --- |
| zero point | `Z(j)` |
| FWHM | measured frame FWHM |
| sky | the star's own local sky level |
| x offset, y offset | registration offset |

A star's response to a driver is `|slope of its light curve against the driver| x` the driver's 5th–95th percentile range. This is the magnitude change that the driver accounts for over the session. For each candidate magnitude, the typical maximum response is the 99th percentile among the 200 `CONSTANT` stars nearest in magnitude.

### 11.4 Gates

Each candidate is tested against eight gates:

| Gate | Fails when | Guards against |
| --- | --- | --- |
| `DATA` | Fewer than `variableMinFrames` usable points after outlier removal, or (with timestamps) a time span under `variableMinSpanMinutes` | Too little data to see a change |
| `AMPLITUDE` | Amplitude below `variableMinAmplitudeMag` (`variableLimitedMinAmplitudeMag` in a `LIMITED` session), or below `variableAmplitudeNoiseFactor x expectedScatter` | Statistically significant but tiny changes |
| `PERSISTENCE` | The longest run of consecutive points deviating on the same side by more than 1.5 x expected scatter is shorter than `variableMinPersistenceFrames` | Short events, a few bad frames |
| `SPLIT_HALF` | The correlation between the first and second points of consecutive non-overlapping pairs is below `variableMinSplitHalfCorrelation` | Noise; white noise gives about zero |
| `APERTURE` | The 1.0 x and 2.0 x FWHM amplitudes, measured over the same frames, differ by more than `variableMaxApertureAmplitudeDifference x amplitude` | Light from a neighbour leaking in, or a wrong position. Real variability is the same in every aperture |
| `SYSTEMATICS` | See below | Changes caused by seeing, transparency, sky or registration |
| `LOCAL` | Fewer than 3 quiet constant stars (`scatterZ < 2`) within `variableLocalRadiusPixels`, or the median absolute correlation with the nearest 10 of them exceeds `variableMaxLocalCorrelation` | A local pattern the plane term cannot model (a dew patch, a local gradient) |
| `LINEARITY` | Any measurement of the star is flagged `SATURATED` or `NONLINEAR` | Stars that come near the linear limit, for example when they brighten |

**SYSTEMATICS** runs two tests for each driver. The gate fails if either test fails for any driver:

1. **Frame to frame.** The correlation between consecutive differences of the light curve and of the driver exceeds `variableMaxSystematicsCorrelation`. A blend that leaks with seeing jumps whenever the FWHM jumps and fails here, whatever the size of the change. A smooth real variable barely changes between consecutive frames, so it does not correlate here.
2. **Trend.** The raw correlation exceeds `variableMaxSystematicsCorrelation` and the star's amplitude is at most `variableSystematicsResponseFactor` times the typical maximum response of constant stars. Raw correlation alone is not enough to fail a star. Any variable that brightens or fades steadily correlates with any steady drift in the session, such as airmass changing the zero point. The star fails only when such a drift could plausibly have produced its change.

`maxSystematicsSource`, `maxSystematicsCorrelation`, `maxFrameToFrameSystematicsCorrelation` and `systematicsLimitMag` record the details.

### 11.5 Tiers

| Tier | Meaning |
| --- | --- |
| `HIGH_CONFIDENCE` | Candidate, every gate passed |
| `POSSIBLE` | Candidate, exactly one gate failed |
| `REJECTED` | Candidate, two or more gates failed |
| `CONSTANT` | Did not reach `variableScoreSigma` on both scores |
| `NOT_SCORED` | Excluded star, too few measurements, no noise model, or session not ready |

`VariableStarAnalysis.candidates` holds the `HIGH_CONFIDENCE`, `POSSIBLE` and `REJECTED` stars, best tier first, then by descending `scatterZ`. `highConfidenceCandidates()` returns the first tier only. `failedGates` lists the gates each candidate failed, in the order of `VariableStarAnalysis.GATE_NAMES`.

---

## 12. Outputs

`VariableStarAnalysis`:

- `readiness`: verdict, status of each check, the measurements behind them, and `messages`
- `frames`: one `PhotometryFrameStat` per clean frame, holding FWHM, aperture radius, sky, registration offset and spread, floor-clipped share, CI profile and linear limit, response slope, zero point and plane terms, star counts, flag counts, and the exclusion reason. Every light-curve array is aligned with this list.
- `stars`: one `StarLightCurve` per selected star, holding position, master flux, `meanMag`, per-frame `deltaMag`, `magError` and `flags`, the scores, gate diagnostics, tier and `notScoredReason`
- `candidates`, `highConfidenceCandidates()`
- `telemetry`: selection and exclusion counters, measurement-flag counts, gate failure counts, the noise model (scatter against magnitude per scored star, with tier), session FWHM, saturation level, and processing time

Frame times are mid-exposure Julian dates (`timestamp + exposure / 2`). They are `NaN` without timestamps.

---

## 13. Fixed Constants

These values are in the code and are not configurable:

| Constant | Value | Where |
| --- | --- | --- |
| Saturation pile-up tolerance / minimum pixels | 0.1% of frame max / 5 | `PhotometryStarSelector` |
| Saturation level without pile-up | 0.95 x 65535 | `PhotometryStarSelector` |
| Registration allowance in selection margin | 2 px | `PhotometryStarSelector` |
| Bright-neighbour flux ratio | 0.1 | `PhotometryStarSelector` |
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
| Ensemble iterations / convergence | 15 / 1e-5 mag | `EnsembleSolver` |
| Ensemble sigma floor / noise neighbours / Cauchy scale | 0.001 mag / 50 / 3 sigma | `EnsembleSolver` |
| Outlier / support threshold | 4 / 2 robust sigma | `VariabilityScorer` |
| Persistence threshold | 1.5 x expected scatter | `VariabilityScorer` |
| Scatter / J spread floors | 5% of expected / 0.02 | `VariabilityScorer` |
| Systematics neighbours / percentile | 200 constant stars / 99th | `VariabilityScorer` |
| Local gate stars / minimum / constant z | 10 / 3 / 2 | `VariabilityScorer` |

---

## 14. Limitations

- **Single session, instrumental magnitudes.** There is no catalogue cross-match, no colour or extinction terms, and no transformation to a standard system. Amplitudes are differential within the session.
- **Variability must show within the session.** A star that changes on timescales much longer than the session looks constant. A star that changes only during the gap between sessions is not visible at all.
- **Crowded, bright and edge stars are not measured.** A variable with a close neighbour, one saturated in the master stack, or one near the edge or void padding is never selected.
- **Power-law stretches are not detected** (section 8.5).
- **`magError` is a lower bound** (sky noise only). Use `expectedScatter` as the realistic error.
- **Candidates are not confirmed variables.** Even `HIGH_CONFIDENCE` candidates should be checked against the images and a variable-star catalogue.

## Related Documents

- `CONFIG.md` section 8: configuration fields and defaults
- `PIPELINE.md`: where the stage runs and what `runPipeline(...)` returns
- `ALGORITHM.md`: the other phases of `runPipeline(...)`
