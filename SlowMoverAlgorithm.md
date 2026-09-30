# Slow Mover Detection Algorithm

This document records the maximum-stack detector design and possible future enhancements. The current implementation and defaults are described in `CONFIG.md` and `ALGORITHM.md`; proposal values and future-tense steps below are not active settings.

## 1. Purpose

The slow-mover detection branch is intended to detect astronomical sources whose total displacement across an aligned image sequence is too small for normal frame-to-frame track linking, but large enough to measurably alter the source footprint.

The primary target range is approximately:

```text
total motion ≈ 0.5–2.0 source diameters
```

For motion substantially below this range, the object becomes difficult to distinguish morphologically from a stationary source.

For motion substantially above this range, the object should increasingly be detectable by the normal mover/streak pipeline.

The slow-mover branch therefore focuses on the transition region between stationary point sources and conventional moving-object tracks.

---

# 2. Core Idea

Use two different stack products for two different purposes:

```text
maximumStack = per-pixel maximum across aligned clean frames
medianStack  = per-pixel median across aligned clean frames
```

The **maximum stack** preserves the spatial envelope swept by a moving source.

The **median stack** represents persistent/common structure and is used primarily as a stationary-source mask.

The core pipeline is:

```text
aligned clean frames
        |
        +------------------+
        |                  |
        v                  v
 maximum stack        median stack
        |                  |
        v                  v
candidate blobs       stationary mask
        |
        v
geometric blob-shape filtering
        |
        v
median-mask overlap filtering
        |
        v
optional temporal confirmation
        |
        v
slow mover candidates
```

The maximum and median stacks are **not subtracted**.

The median stack is used as independent contextual information.

---

# 3. Why the Maximum Stack Works

Consider a point source with an approximately circular detected footprint of diameter `D`.

If it remains stationary throughout the sequence, its maximum-stack footprint remains approximately:

```text
     #####
   #########
  ###########
   #########
     #####
```

If it moves slowly during the sequence, the maximum stack contains the union of its footprints at all observed positions.

For approximately linear motion:

```text
     ############
   ################
  ##################
   ################
     ############
```

The resulting footprint resembles a short capsule or oval.

Approximately:

```text
minor extent ≈ D
major extent ≈ D + L
```

where:

```text
D = source footprint diameter
L = total displacement
```

Therefore:

```text
axisRatio = majorExtent / minorExtent
```

and approximately:

```text
axisRatio ≈ (D + L) / D
          ≈ 1 + L/D
```

This gives the geometric axis ratio a useful physical interpretation.

Approximate ideal relationship:

| Total Motion | Expected Axis Ratio |
|---|---:|
| 0 D | 1.0 |
| 0.25 D | 1.25 |
| 0.5 D | 1.5 |
| 1.0 D | 2.0 |
| 1.5 D | 2.5 |
| 2.0 D | 3.0 |

These are theoretical geometric values, not hard detection thresholds.

Real values depend on:

- pixel sampling
- source brightness
- seeing
- alignment accuracy
- extraction thresholds
- grow threshold
- number of frames
- motion direction relative to the pixel grid

Final thresholds should therefore be calibrated with synthetic injections and real data.

---

# 4. Important Distinction: Elongation vs Geometric Axis Ratio

`SourceExtractor.DetectedObject.elongation` already exists.

It is calculated from intensity-weighted second moments:

```text
elongation = sqrt(lambda1 / lambda2)
```

where `lambda1` and `lambda2` are the principal variances of the source intensity distribution.

This is a useful quantity and should remain unchanged.

It describes:

```text
intensity-weighted major RMS width
----------------------------------
intensity-weighted minor RMS width
```

It is particularly useful for:

- frame quality analysis
- tracking errors
- source morphology
- streak detection
- diagnostic information

However, it should **not** be interpreted directly as:

```text
(source diameter + motion) / source diameter
```

For the slow-mover algorithm, introduce a separate geometric quantity:

```text
axisRatio = majorExtent / minorExtent
```

calculated directly from the connected `rawPixels` footprint.

The two quantities measure related but different properties:

```text
elongation
    = intensity distribution shape

axisRatio
    = geometric detected-footprint shape
```

Both should be retained.

---

# 5. Source Extractor Blob Properties

Every extracted `DetectedObject` contains:

```text
rawPixels
```

representing the exact connected component detected by the BFS extractor.

The extractor uses 8-connected growth.

Therefore every pixel belonging to a detected object is connected, directly or indirectly, to the rest of the component.

An arbitrary isolated pixel cannot belong to the same object.

Possible irregularities can still include:

- connected protrusions
- L-shaped structures
- merged nearby stars
- bridges between nearby sources
- irregular artifacts

These can be handled separately using shape-consistency metrics.

---

# 6. Blob Geometry Measurement

The existing source orientation can be used to measure the geometric footprint.

For a candidate with orientation:

```text
theta = obj.angle
```

define the principal-axis vector:

```text
dx = cos(theta)
dy = sin(theta)
```

For every raw pixel:

```text
vx = pixel.x - obj.x
vy = pixel.y - obj.y
```

calculate projection parallel to the major axis:

```text
parallel = vx * dx + vy * dy
```

and perpendicular to it:

```text
perpendicular = -vx * dy + vy * dx
```

Track:

```text
minParallel
maxParallel
minPerpendicular
maxPerpendicular
```

Then:

```text
majorExtent = maxParallel - minParallel + 1
minorExtent = maxPerpendicular - minPerpendicular + 1
```

and:

```text
axisRatio = majorExtent / minorExtent
```

For safety:

```text
if majorExtent < minorExtent:
    swap majorExtent and minorExtent
```

For normal elongated candidates this should rarely be necessary because `obj.angle` already represents the principal major axis.

---

# 7. Reuse Existing Geometry Logic

`SourceExtractor` already contains almost identical projection logic in:

```text
isBinaryStarLikeStreakShape(...)
isIrregularStreakShape(...)
```

Those methods currently calculate:

```text
parallel extent
perpendicular extent
length
width
fill factor
```

The common calculation should preferably be extracted into a reusable helper rather than duplicated in `SlowMoverAnalyzer`.

For example:

```java
public static class BlobGeometry {
    public double majorExtent;
    public double minorExtent;
    public double axisRatio;
    public double fillFactor;
}
```

and:

```java
public static BlobGeometry measureBlobGeometry(DetectedObject obj)
```

The same helper can then be reused by:

- streak morphology
- binary-star morphology
- irregular-shape filtering
- slow-mover detection

---

# 8. Fill Factor

A useful additional geometric metric is:

```text
fillFactor =
    pixelArea
    -------------------------
    majorExtent * minorExtent
```

A compact oval/capsule should occupy a substantial part of its oriented bounding rectangle.

An irregular shape such as:

```text
########
       #
       #
       #####
```

may have a significant axis ratio but a much lower fill factor.

The first implementation does not need to make fill factor a strong acceptance criterion.

Recommended approach:

```text
axisRatio = primary morphology filter
fillFactor = secondary irregular-shape veto / diagnostic
```

A deliberately permissive initial minimum such as:

```text
slowMoverMinFillFactor ≈ 0.35–0.45
```

may be appropriate if enabled.

The exact value must be calibrated.

---

# 9. Approximate Motion Estimate

For a capsule-like slow mover:

```text
majorExtent ≈ D + L
minorExtent ≈ D
```

Therefore:

```text
estimatedMotionPixels =
    majorExtent - minorExtent
```

and:

```text
estimatedMotionDiameters =
    (majorExtent - minorExtent) / minorExtent
```

which is equivalent to:

```text
estimatedMotionDiameters ≈ axisRatio - 1
```

Example:

```text
majorExtent = 12 px
minorExtent = 6 px

axisRatio = 2.0

estimatedMotionPixels = 6 px
estimatedMotionDiameters ≈ 1.0
```

This is only a morphology-derived estimate.

It should not be presented as a measured astrometric displacement unless temporal validation confirms the motion.

---

# 10. Maximum-Stack Candidate Extraction

The maximum stack is the candidate image.

Recommended procedure:

1. Build `maximumStack`.
2. Run `SourceExtractor` on the maximum stack using slow-mover-specific extraction thresholds.
3. Keep the resulting connected components as raw slow-mover candidates.
4. Measure geometric blob properties for every candidate.

Each candidate should have at least:

```text
centroid
pixelArea
rawPixels

momentElongation
orientation

majorExtent
minorExtent
axisRatio
fillFactor

peakSigma
integratedSigma
```

The candidate object itself should remain the maximum-stack `DetectedObject`.

---

# 11. Primary Shape Filter

The primary morphology gate should use:

```text
axisRatio
```

rather than the existing moment-based `elongation`.

Conceptually:

```text
slowMoverMinAxisRatio
    <= candidate.axisRatio
    <= slowMoverMaxAxisRatio
```

The theoretical range corresponding to approximately 0.5–2 source diameters is:

```text
1.5 <= axisRatio <= 3.0
```

For initial real-world testing, slightly relaxed limits are preferable.

Possible initial values:

```text
slowMoverMinAxisRatio = 1.35
slowMoverMaxAxisRatio = 3.20
```

These values are intentionally permissive.

After synthetic injection tests, the final range may move closer to:

```text
1.5–3.0
```

The minimum gate should remove ordinary approximately round stars.

The maximum gate should remove longer streaks and faster movers.

---

# 12. Existing Moment Elongation

The existing:

```text
obj.elongation
```

should still be recorded.

It can be useful as:

- secondary morphology information
- telemetry
- an optional sanity filter
- comparison against normal field stars

It should not be the primary slow-mover motion-scale metric.

Possible diagnostics:

```text
geometricAxisRatio
momentElongation
```

Having both can also reveal unusual candidates.

For example:

```text
high axisRatio + moderate momentElongation
```

may indicate a faint extended envelope.

Conversely:

```text
moderate axisRatio + very high momentElongation
```

may indicate intensity strongly concentrated along a narrow ridge.

---

# 13. Median Stack

The median stack should be used as a map of common/persistent structure.

Run source extraction on:

```text
medianStack
```

and build:

```text
medianMask
```

from the exact `rawPixels` of all accepted median-stack objects.

No object-to-object matching is required.

No dilation is required for the first implementation.

Conceptually:

```text
medianMask[x][y] = true
```

if `(x,y)` belongs to any detected median-stack object.

---

# 14. Median-Mask Overlap

For every maximum-stack candidate, calculate:

```text
pixelsInsideMedianMask
```

and:

```text
medianMaskOverlapFraction =
    pixelsInsideMedianMask
    ----------------------
    candidate.pixelCount
```

Also calculate:

```text
outsideMedianMaskFraction =
    1 - medianMaskOverlapFraction
```

The overlap provides information about how much of the maximum-stack footprint is explained by stationary/common structure.

---

# 15. Interpretation of Median Overlap

Very high overlap:

```text
maximum footprint ≈ median footprint
```

is consistent with:

- stationary star
- ordinary PSF
- persistent static object

and should normally be rejected.

Intermediate overlap is expected for many slow movers because part of the swept maximum-stack footprint may overlap the persistent median footprint while part extends outside it.

Very low overlap may represent:

- a real mover
- an object suppressed by the median stack
- a one-frame transient
- cosmic ray
- artifact

Therefore:

**the upper median-overlap threshold is a strong stationary-source veto.**

The lower overlap threshold is much weaker and should not be interpreted as proof of temporal persistence.

For the first implementation, the lower limit should preferably be permissive.

Possible initial configuration:

```text
slowMoverMedianSupportOverlapFraction = 0.0
slowMoverMedianSupportMaxOverlapFraction = 0.80
```

The maximum value should be calibrated experimentally.

---

# 16. Candidate Filter Sequence

Recommended first implementation:

```text
maximum-stack extracted component
        |
        v
minimum pixel area
        |
        v
geometric axis-ratio window
        |
        v
optional minimum fill factor
        |
        v
optional merged/binary morphology rejection
        |
        v
median-mask upper-overlap veto
        |
        v
optional median-mask lower-overlap veto
        |
        v
slow-mover candidate
```

This ordering makes cheap morphology checks happen before more specialized analysis.

---

# 17. Stationary Stars

A normal stationary star should generally have:

```text
axisRatio ≈ 1
```

in both median and maximum stacks.

Seeing variations, imperfect alignment and PSF asymmetry may push this somewhat above 1.

This is why:

```text
slowMoverMinAxisRatio
```

should initially be below the theoretical 1.5 value and later calibrated using actual fields.

If necessary, a future enhancement can derive a field-specific baseline from stationary maximum-stack objects.

For version 1, a simple absolute geometric threshold is easier to reason about and test.

---

# 18. Merged Stars and Irregular Shapes

A possible false positive is a pair of nearby stars merged into one connected maximum-stack component.

Example:

```text
   ####
  ######
    ###
     ##
      ###
    ######
   #######
```

This may produce an elongated geometric footprint.

Existing source-shape logic can help identify these cases.

Possible secondary vetoes:

```text
SourceExtractor.isBinaryStarLikeStreakShape(...)
```

or a slow-mover-specific simplified equivalent.

However, these filters should initially be conservative.

A slow mover sampled at only a small number of positions may itself produce a slightly non-uniform footprint.

Therefore the first implementation should prioritize sensitivity and record diagnostics before making these filters aggressive.

---

# 19. Why Maximum Minus Median Should Not Be Used

Do not create the candidate morphology from:

```text
maximumStack - medianStack
```

A slow mover may substantially overlap itself in the median stack.

Subtracting the median can transform a simple oval/capsule footprint into:

- crescents
- double lobes
- edge fragments
- disconnected structures

The maximum stack contains the morphology of interest directly.

The median stack should therefore remain an independent mask:

```text
maximum stack -> shape
median stack  -> contextual veto
```

---

# 20. Optional Temporal Confirmation

The current implementation measures candidate frame-support and stationary-likelihood percentages directly in the retained original frames. Their configurable vetoes default to disabled. The three-bin stacking and measured-motion validation proposed below remain future work.

Maximum-stack morphology identifies objects that **look like** a slow-moving swept source.

It does not by itself prove motion.

A later confirmation stage can inspect only the small number of surviving candidate regions in the original clean frames.

This should be optional in the initial implementation.

Possible configuration:

```text
enableSlowMoverTemporalValidation
```

The default may initially be:

```text
false
```

while the stack-based detector is calibrated.

---

# 21. Three-Bin Temporal Validation

A computationally cheap validation approach is to divide the frame sequence into:

```text
EARLY
MIDDLE
LATE
```

For each surviving candidate:

1. Extract a small ROI around the candidate.
2. Build a robust stack or estimate a source centroid independently in each temporal group.
3. Calculate:

```text
Cearly
Cmiddle
Clate
```

A real slow mover should approximately satisfy:

```text
Cearly -> Cmiddle -> Clate
```

with:

- consistent direction
- approximately monotonic displacement
- `Cmiddle` roughly between the other two positions
- total displacement compatible with the maximum-stack major axis
- motion direction compatible with `obj.angle`

The temporal displacement can then become the actual measured slow-mover motion.

---

# 22. Relationship Between Morphological and Temporal Motion

The morphology predicts approximately:

```text
predictedMotionPixels =
    majorExtent - minorExtent
```

Temporal confirmation measures:

```text
measuredMotionPixels =
    distance(Cearly, Clate)
```

These should be broadly compatible.

For example:

```text
motionConsistency =
    measuredMotionPixels / predictedMotionPixels
```

could be used later as an additional quality metric.

Do not require a very tight match initially.

Maximum stacking, temporal binning, PSF variation and extraction thresholds measure slightly different representations of the source.

---

# 23. Configuration Plan

## Active Extraction And Mask Parameters

```text
enableSlowMoverDetection

masterSlowMoverMinPixels
masterSlowMoverSigmaMultiplier
masterSlowMoverGrowSigmaMultiplier

slowMoverMedianSupportOverlapFraction
slowMoverMedianSupportMaxOverlapFraction
```

Meanings:

### `masterSlowMoverMinPixels`

Minimum connected-component size in both maximum-stack and median-stack extraction.

### `masterSlowMoverSigmaMultiplier`

Seed threshold for both stack extractions.

### `masterSlowMoverGrowSigmaMultiplier`

Grow threshold for both stack extractions.

### `slowMoverMedianSupportOverlapFraction`

Optional minimum candidate-footprint overlap with the exact median mask.

Current default:

```text
0.0
```

### `slowMoverMedianSupportMaxOverlapFraction`

Maximum permitted candidate-footprint overlap with the exact median mask.

Used primarily as the stationary-source veto.

Current default:

```text
0.80
```

---

# 24. Shape Parameters

Active parameters:

```text
slowMoverMinAxisRatio
slowMoverMaxAxisRatio
```

Current defaults:

```text
slowMoverMinAxisRatio = 1.35
slowMoverMaxAxisRatio = 3.20
```

Optional:

```text
slowMoverMinFillFactor
```

Current default:

```text
slowMoverMinFillFactor = 0.0
```

Zero disables the veto while retaining fill-factor diagnostics. A value such as `0.35` is a possible future tuning choice.

---

# 25. Legacy Parameters From the Previous Algorithm

The previous percentile-stack, elongation-baseline, and residual-footprint settings have been removed from `DetectionConfig`.

The new primary shape window is:

```text
slowMoverMinAxisRatio
slowMoverMaxAxisRatio
```

---

# 26. DetectedObject Geometry

Preferred option:

extend `DetectedObject` with reusable geometric morphology fields:

```java
public double majorExtent;
public double minorExtent;
public double axisRatio;
public double fillFactor;
```

These are useful beyond slow-mover detection and are naturally properties of the detected connected component.

Alternative:

keep them inside a separate:

```java
BlobGeometry
```

structure.

The preferred design is to calculate them once during `analyzeShape(...)` because the `rawPixels`, centroid and orientation are already available there.

This avoids recalculating the same geometry in:

- streak detection
- binary-star detection
- irregular-shape detection
- slow-mover analysis

---

# 27. Recommended SourceExtractor Refactoring

Extract the geometry currently duplicated in streak-shape methods into:

```java
public static BlobGeometry measureBlobGeometry(
        DetectedObject obj,
        List<Pixel> pixels)
```

or equivalent.

Conceptual implementation:

```java
double dx = Math.cos(obj.angle);
double dy = Math.sin(obj.angle);

double minPar = Double.MAX_VALUE;
double maxPar = -Double.MAX_VALUE;
double minPerp = Double.MAX_VALUE;
double maxPerp = -Double.MAX_VALUE;

for (Pixel p : obj.rawPixels) {
    double vx = p.x - obj.x;
    double vy = p.y - obj.y;

    double par  = vx * dx + vy * dy;
    double perp = -vx * dy + vy * dx;

    minPar = Math.min(minPar, par);
    maxPar = Math.max(maxPar, par);

    minPerp = Math.min(minPerp, perp);
    maxPerp = Math.max(maxPerp, perp);
}

double majorExtent = maxPar - minPar + 1.0;
double minorExtent = maxPerp - minPerp + 1.0;

if (minorExtent > majorExtent) {
    double tmp = majorExtent;
    majorExtent = minorExtent;
    minorExtent = tmp;
}

double axisRatio =
        majorExtent / Math.max(minorExtent, 1.0);

double fillFactor =
        obj.pixelArea /
        Math.max(1.0, majorExtent * minorExtent);
```

No percentile clipping is necessary for the first implementation because `rawPixels` belong to a single 8-connected component.

---

# 28. SlowMoverAnalyzer Changes

Change candidate generation from the previous percentile-stack method to maximum-stack morphology.

Recommended flow:

```text
analyze(...)
    |
    +--> maximum stack
    |
    +--> median stack
    |
    +--> maximum-stack source extraction
    |
    +--> median-stack source extraction
    |
    +--> exact median mask
    |
    +--> geometric candidate filters
    |
    +--> median overlap filters
    |
    +--> candidate results
```

Detailed implementation:

1. Receive or generate `maximumStackData`.
2. Receive the existing median `masterStackData`.
3. Extract objects from maximum stack using slow-mover thresholds.
4. Extract median objects for construction of the exact median mask.
5. For every maximum-stack candidate:
   - verify minimum pixel count
   - calculate geometric morphology
   - reject below `slowMoverMinAxisRatio`
   - reject above `slowMoverMaxAxisRatio`
   - optionally reject very low `fillFactor`
   - calculate median-mask overlap
   - reject above `slowMoverMedianSupportMaxOverlapFraction`
   - optionally reject below `slowMoverMedianSupportOverlapFraction`
6. Store surviving maximum-stack object as the slow-mover candidate.
7. Record full diagnostics.

---

# 29. Maximum-Stack Extraction Thresholds

For the first implementation, the existing slow-mover extraction thresholds can be reused.

However, remember that the statistical distribution of a maximum stack differs from that of a normal or median image.

The per-pixel maximum increasingly contains positive noise excursions as frame count increases.

Therefore:

```text
masterSlowMoverSigmaMultiplier
masterSlowMoverGrowSigmaMultiplier
```

must be validated specifically on maximum stacks.

If necessary later, separate maximum-stack and median-mask extraction thresholds can be introduced.

There is no need to introduce this extra configuration complexity before real-data testing demonstrates a need.

---

# 30. Median Mask Construction

Median-stack extraction exists only to construct the mask.

For each median object:

```java
for (Pixel p : medianObject.rawPixels) {
    medianMask[p.y][p.x] = true;
}
```

Do not:

- dilate the mask initially
- match median objects to maximum objects by centroid
- require similar object dimensions
- subtract median pixels from the maximum stack

The query is simply:

```text
How much of this maximum-stack connected footprint
is already explained by detected median-stack structure?
```

---

# 31. Candidate Diagnostics

`SlowMoverCandidateDiagnostics` should contain at least:

```text
pixelCount

momentElongation
orientation

majorExtent
minorExtent
axisRatio
fillFactor

estimatedMotionPixels
estimatedMotionDiameters

medianMaskOverlapFraction
outsideMedianMaskFraction
```

Also record the thresholds used:

```text
minAxisRatioThreshold
maxAxisRatioThreshold

medianSupportMinOverlapThreshold
medianSupportMaxOverlapThreshold

minFillFactorThreshold
```

If temporal validation is later enabled:

```text
earlyCentroid
middleCentroid
lateCentroid

temporalMotionPixels
temporalMotionAngle
morphologyMotionConsistency
temporalValidationPassed
```

---

# 32. Slow-Mover Summary Telemetry

Useful aggregate counters:

```text
rawCandidatesExtracted

rejectedBelowMinAxisRatio
rejectedAboveMaxAxisRatio

rejectedLowFillFactor

evaluatedAgainstMedianMask
rejectedLowMedianSupport
rejectedHighMedianSupport

candidatesDetected
```

Useful aggregate measurements:

```text
avgCandidateAxisRatio
minCandidateAxisRatio
maxCandidateAxisRatio

avgMedianMaskOverlap

avgEstimatedMotionPixels
avgEstimatedMotionDiameters
```

For debugging/calibration, it is useful to keep the distributions of:

```text
candidateAxisRatios
candidateMomentElongations
candidateFillFactors
candidateMedianMaskOverlaps
```

---

# 33. SlowMoverAnalysis Data Products

Preferred fields:

```text
maximumStackData
medianMask
candidates
telemetry
```

Compatibility aliases may remain temporarily if required.

If:

```text
slowMoverStackData
```

continues to exist, its meaning should be clearly documented.

In the new algorithm it should either:

```text
reference maximumStackData
```

or be explicitly marked as legacy.

---

# 34. JTransientEngine Changes

Recommended pipeline order:

1. Align and quality-filter frames as today.
2. Build median master stack.
3. Build maximum stack once.
4. Reuse the same maximum stack:
   - for general exported maximum-stack data
   - for slow-mover candidate detection
5. Call `SlowMoverAnalyzer` with:
   - maximum stack
   - median stack
   - clean frames if temporal validation is enabled
   - configuration
6. Export candidate diagnostics and telemetry.

Do not generate the maximum stack twice.

---

# 35. MasterMapGenerator

Use:

```text
createMaximumMasterStack(...)
```

as the source image for slow-mover candidates.

The former percentile-stack method has been removed.

---

# 36. Suggested Version-1 Algorithm

The first implementation should deliberately remain simple.

```text
1. Build maximum stack.

2. Build / reuse median stack.

3. Extract connected objects from maximum stack.

4. Extract connected objects from median stack.

5. Build exact median mask.

6. For every maximum-stack candidate:

      geometry = measureBlobGeometry(candidate)

      if geometry.axisRatio < minAxisRatio:
          reject

      if geometry.axisRatio > maxAxisRatio:
          reject

      if fill-factor filtering enabled
         and geometry.fillFactor < minFillFactor:
          reject

      overlap =
          candidate pixels inside median mask
          / candidate pixel count

      if overlap < minMedianOverlap:
          reject

      if overlap > maxMedianOverlap:
          reject

      accept candidate

7. Return candidates + diagnostics.
```

This should be the baseline implementation before adding more complex logic.

---

# 37. Suggested Initial Configuration

For initial experimentation only:

```text
enableSlowMoverDetection = true

slowMoverMinAxisRatio = 1.35
slowMoverMaxAxisRatio = 3.20

slowMoverMedianSupportOverlapFraction = 0.0
slowMoverMedianSupportMaxOverlapFraction = 0.80
```

Optional experimental setting (current default `0.0`):

```text
slowMoverMinFillFactor = 0.35
```

The configuration values are intentionally permissive.

The objective of the first implementation is to observe candidate distributions rather than aggressively optimize precision.

---

# 38. Synthetic Injection Tests

The most important calibration method should be synthetic slow-mover injection.

Inject Gaussian-like point sources with known total motion:

```text
0.00 D
0.25 D
0.50 D
0.75 D
1.00 D
1.25 D
1.50 D
2.00 D
2.50 D
3.00 D
```

Test across:

```text
different SNR levels
different PSF sizes
different motion angles
different frame counts
different seeing levels
different extraction thresholds
```

For every injection record:

```text
true motion / D

measured majorExtent
measured minorExtent
measured axisRatio

moment elongation
fill factor
median overlap

accepted / rejected
```

This will produce the real calibration curve:

```text
true motion / D
       vs
measured geometric axis ratio
```

That curve should determine the final default values.

---

# 39. Essential Unit Tests

Add tests for the geometric helper:

### Circular footprint

Expected:

```text
axisRatio ≈ 1
```

### Horizontal capsule

Expected:

```text
axisRatio > 1
majorExtent > minorExtent
```

### Vertical capsule

Should produce approximately the same axis ratio as the horizontal case.

### Diagonal capsule

Should produce approximately the same axis ratio as horizontal/vertical cases.

This verifies orientation independence.

### Connected irregular shape

Verify:

```text
axisRatio may be high
fillFactor is lower
```

### Rotation invariance

Rotate equivalent synthetic footprints and verify similar:

```text
majorExtent
minorExtent
axisRatio
```

within pixel-grid tolerance.

---

# 40. Slow-Mover Integration Tests

Required integration cases:

### Stationary star

```text
maximum shape approximately round
high median overlap
```

Expected:

```text
rejected
```

### Slow mover, 0.5 D

Expected:

```text
near lower morphology boundary
potentially accepted
```

### Slow mover, 1.0 D

Expected:

```text
strong slow-mover candidate
```

### Slow mover, 2.0 D

Expected:

```text
near upper morphology boundary
potentially accepted
```

### Fast mover > 2 D

Expected:

```text
rejected by maximum axis ratio
or handled by normal mover/streak pipeline
```

### One-frame transient

May produce:

```text
low median overlap
```

If minimum median overlap is zero, it may survive the first stage.

This is acceptable for version 1.

Temporal validation or later transient filtering should reject it.

### Stationary elongated PSF

Could pass the geometric shape gate.

Expected defense:

```text
high median-mask overlap
```

### Close binary / merged source

May pass the axis-ratio window.

Expected defense:

```text
median overlap
fill factor
binary/irregular morphology
```

depending on the specific case.

---

# 41. Important Design Principle

The stack-based detector answers:

```text
Does this source have the morphology expected
from a small swept motion?
```

It does **not necessarily prove**:

```text
Did this source physically move monotonically
through the sequence?
```

This distinction should remain explicit.

Version 1 is therefore:

```text
slow-mover candidate detection
```

rather than full astrometric motion confirmation.

Temporal validation can later promote candidates from:

```text
morphological slow-mover candidate
```

to:

```text
temporally confirmed slow mover
```

---

# 42. Recommended Implementation Order

1. Extract reusable `BlobGeometry` measurement from `SourceExtractor`.
2. Add:
   - `majorExtent`
   - `minorExtent`
   - `axisRatio`
   - `fillFactor`
3. Add unit tests for geometry.
4. Add new `DetectionConfig` axis-ratio parameters.
5. Change `SlowMoverAnalyzer` candidate image to maximum stack.
6. Build the exact median mask.
7. Implement geometric axis-ratio filtering.
8. Implement median-mask overlap filtering.
9. Update candidate diagnostics.
10. Update summary telemetry.
11. Update `JTransientEngine` to generate/reuse maximum stack once.
12. Update integration tests.
13. Run synthetic injection experiments.
14. Tune default thresholds.
15. Only then consider:
   - stronger irregular-shape filtering
   - binary-star rejection
   - temporal early/middle/late confirmation

---

# 43. Final Algorithm Summary

The new slow-mover detector is based on a simple physical observation:

```text
a slowly moving point source becomes
a short oval/capsule in the maximum stack
```

The maximum-stack footprint provides:

```text
majorExtent
minorExtent
axisRatio
```

with approximately:

```text
axisRatio ≈ 1 + totalMotion / sourceDiameter
```

The median stack independently identifies stationary/common structure.

Therefore:

```text
MAXIMUM STACK
    |
    v
connected candidate footprint
    |
    v
geometric oval/capsule shape
    |
    v
axis ratio in slow-motion range
    |
    v
not almost completely explained by median mask
    |
    v
SLOW-MOVER CANDIDATE
```

The existing intensity-weighted `elongation` remains useful and unchanged, but geometric `axisRatio` becomes the primary morphology metric for slow-mover detection.

This keeps the detector physically interpretable, computationally inexpensive, and directly tied to the total source displacement the slow-mover branch is intended to detect.
