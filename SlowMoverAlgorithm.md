
## Proposed Direction: Maximum-Stack Envelope With Median-Mask Overlap

A stronger starting design is to use the maximum stack as the candidate-envelope image, then use exact median-mask overlap to reject candidates that are either too unsupported by the median stack or too similar to stationary median-stack objects.

Primary stack images:

```text
maximumStack = per-pixel maximum across clean frames
medianStack = per-pixel median across clean frames
```

The maximum stack better preserves the full swept footprint of a faint mover. The median stack provides the stationary/common footprint mask.

The key change is:

```text
maximum stack proposes candidate envelopes
median-mask overlap band filters candidate envelopes
```

## Candidate Extraction

Possible extraction pass:

1. Build `maximumStack`.
2. Extract connected components from `maximumStack` using slow-mover-specific thresholds.
3. Extract objects from `medianStack` using the same slow-mover-specific extraction thresholds.
4. Build `medianMask` from the exact median-stack object footprints.
5. Measure each maximum-stack component:
   - centroid
   - area
   - major-axis length
   - minor-axis width
   - elongation
   - orientation
   - fill factor
   - peak signal
   - integrated signal
6. Measure the maximum-stack component footprint against `medianMask`.

The candidate shape should come from the maximum-stack footprint.

The median-stack extraction should not be used for object-to-object matching. 
```text
How much of this maximum-stack candidate is already explained by median-stack structure?
```

## Maximum-Stack Shape Filters

The maximum-stack shape filters should define what counts as too large or too streak-like for the slow-mover branch.

Recommended filters:

- minimum area to reject hot pixels and tiny noise islands
- minimum elongation excess above the field baseline, to reject max-stack objects with no measurable swept envelope
- maximum elongation to reject true streaks and ordinary faster movers

The shape gate should require some evidence of elongation if the branch is meant to find objects that measurably swept across the sequence. A candidate whose maximum-stack elongation is indistinguishable from ordinary stationary stars is probably not separable from a stationary source, seeing variation, focus variation, or a variable star by stack shape alone.

However, the minimum-elongation gate should be gentle. It should reject "no measurable sweep", not require a long streak. A good rule is probably one of:

```text
maxStackElongation >= baselineElongation + MAD * slowMoverMinElongationBaselineMadMultiplier
```

The baseline should come from ordinary median-stack or maximum-stack field objects extracted with the same stage config, not from already-filtered slow-mover candidates. This is different from the current `slowMoverBaselineMadMultiplier`, which acts as a strong "unusually elongated" gate.

The maximum-elongation gate can be an absolute value. Very large elongation means the object swept too far across the sequence, which puts it closer to normal transient, track, or streak detection than the slow-mover branch. In other words:

```text
maxStackElongation <= slowMoverMaxElongation
```

Roughly, if the max-stack envelope major axis is `objectDiameter + totalMotion` and the minor axis is close to `objectDiameter`, then an elongation near `3.0` corresponds to motion around two object diameters. This makes an absolute maximum elongation easier to reason about than an absolute pixel length.

The important shape window is:

```text
slightly more elongated than ordinary stars
but not so elongated or long that it is a normal mover/streak
```

## Median-Mask Filters

The median stack should be used as a stationary-source mask, not as a set of objects that must be matched one-to-one against maximum-stack objects.

Recommended median mask:

- `medianMask`: exact detected median-stack object footprints

The mask should answer how much of the maximum-stack candidate is common stationary structure. It should not be dilated for now.

Recommended median-mask logic:

1. Compute `medianMaskOverlapFraction`:
   - maximum-stack candidate pixels inside `medianMask` divided by maximum-stack candidate pixel count
2. Require that overlap to fall inside a configured band:
   - `slowMoverMedianSupportOverlapFraction <= medianMaskOverlapFraction`
   - `medianMaskOverlapFraction <= slowMoverMedianSupportMaxOverlapFraction`
3. Compute `outsideMedianMaskFraction = 1 - medianMaskOverlapFraction`.

The overlap band is useful because both extremes are suspicious:

- too little median-mask overlap can mean the maximum-stack candidate is only a one-frame transient, cosmic ray, hot pixel, or artifact
- too much median-mask overlap can mean the candidate is just an ordinary stationary median-stack object

The lower bound should be configurable. A value like `0.0` allows max-stack candidates with no median support. A value like `0.10` requires at least some common support, which may help enforce that the candidate is present in multiple frames.

Interpretation examples:

```text
median-mask overlap below minimum:
    likely one-frame transient, cosmic ray, hot pixel, or artifact

median-mask overlap inside configured band + mild max-stack elongation:
    plausible slow mover candidate

median-mask overlap above maximum:
    likely stationary star or stack artifact
```

## Filter Mapping From Current Algorithm

### Keep Or Reuse

`enableSlowMoverDetection`

Keep this as the master switch for the slow-mover branch.

`masterSlowMoverMinPixels`

Keep this name for the maximum-stack extraction pass.

`masterSlowMoverSigmaMultiplier`

Keep this name for the maximum-stack extraction seed threshold, but retune it if needed.

`masterSlowMoverGrowSigmaMultiplier`

Keep this name for the maximum-stack extraction grow threshold, but retune it if needed.

`slowMoverMedianSupportOverlapFraction`

Keep this as the lower bound for median-mask overlap. The name is already present in `DetectionConfig`; in the maximum-stack branch it should mean the minimum fraction of a maximum-stack candidate footprint covered by the exact median-stack object mask.

`slowMoverMedianSupportMaxOverlapFraction`

Keep this as the upper bound for median-mask overlap. The name is already present in `DetectionConfig`; in the maximum-stack branch it should mean the maximum fraction of a maximum-stack candidate footprint covered by the exact median-stack object mask.

Median-mask overlap

Keep the concept, but avoid object matching. Median-stack objects should create masks, and maximum-stack candidates should be measured against those masks. The algorithm should use the existing support overlap fields as a configurable minimum/maximum band.

### Drop, Retire, Or Replace

`slowMoverStackMiddleFraction`

Retire if the P87.5-style stack is removed. Keep only as a legacy branch parameter if both algorithms coexist temporarily.

`slowMoverBaselineMadMultiplier`

Retire as the primary strong elongation gate. Replace it with a gentler maximum-stack minimum-elongation rule if needed. The new rule should reject candidates with no measurable swept envelope, not demand extreme elongation.

`enableSlowMoverResidualFootprintFiltering`

Retire for the first maximum-stack implementation. Residual comparison can be reconsidered later if the overlap-band approach is not selective enough.

`slowMoverResidualFootprintMinFluxFraction`

Retire for the first maximum-stack implementation. Residual thresholds can be added later if needed.

## Relationship To Existing Pipeline

The maximum-stack data already exists at the end of `JTransientEngine.runPipeline(...)`, but for slow-mover analysis it would need to be generated earlier or shared so the slow-mover branch can use it.

Possible architecture:

1. Keep median stack generation unchanged.
2. Generate maximum stack before slow-mover analysis when maximum-stack slow movers are enabled.
3. Pass clean frames, median stack, and maximum stack into `SlowMoverAnalyzer`.
4. Return:
   - current legacy slow-mover stack, if retained
   - maximum stack and median-mask diagnostics
   - validated slow-mover candidates
   - telemetry explaining rejection reasons

## DetectionConfig Parameter Plan

The current `DetectionConfig` slow-mover section should probably be split into legacy percentile-stack parameters and new maximum-stack slow-mover parameters during the transition.

### Parameters To Keep

```text
enableSlowMoverDetection
masterSlowMoverMinPixels
masterSlowMoverSigmaMultiplier
masterSlowMoverGrowSigmaMultiplier
slowMoverMedianSupportOverlapFraction
slowMoverMedianSupportMaxOverlapFraction
```

Keep `enableSlowMoverDetection` as the general branch switch.

Keep the existing `masterSlowMover...` extraction names for compatibility. In the new algorithm they apply to maximum-stack candidate extraction instead of the old percentile slow-mover stack.

Keep the existing `slowMoverMedianSupport...` names for compatibility. In the new algorithm they define the minimum and maximum overlap band against the exact median-stack object mask.

### Parameters To Remove Or Mark Legacy

```text
slowMoverStackMiddleFraction
slowMoverBaselineMadMultiplier
enableSlowMoverResidualFootprintFiltering
slowMoverResidualFootprintMinFluxFraction
```

These should remain only if the old percentile-stack branch is temporarily kept for comparison.

### New Shape Parameters

```text
slowMoverMinElongationBaselineMadMultiplier
slowMoverMaxElongation
```

`slowMoverMinElongationBaselineMadMultiplier` derives the minimum elongation from the session's ordinary object elongation distribution. This should be a gentle baseline-excess gate, not the old strong slow-mover MAD gate.

`slowMoverMaxElongation` is an absolute cap that rejects streak-like envelopes and objects that swept too far across the sequence.

### Existing Median-Mask Parameters

```text
slowMoverMedianSupportOverlapFraction
slowMoverMedianSupportMaxOverlapFraction
```

These existing median-support filters should be reused as the explicit overlap band measured against the exact median mask.

`slowMoverMedianSupportOverlapFraction` can be `0.0` when we do not require median support, or a small value such as `0.10` when we want to require some common multi-frame support.

`slowMoverMedianSupportMaxOverlapFraction` rejects candidates that are too close to ordinary stationary median-stack objects. A starting value might be around `0.80`.

## Implementation Change Checklist

This section maps the maximum-stack/median-mask design onto the current code. The goal is to make the implementation change explicit before editing the Java classes.

### `DetectionConfig.java`

Keep these existing fields and reuse them in the maximum-stack branch:

```text
enableSlowMoverDetection
masterSlowMoverMinPixels
masterSlowMoverSigmaMultiplier
masterSlowMoverGrowSigmaMultiplier
slowMoverMedianSupportOverlapFraction
slowMoverMedianSupportMaxOverlapFraction
```

Do not add `slowMoverMedianMaskMinOverlapFraction` or `slowMoverMedianMaskMaxOverlapFraction`. The existing `slowMoverMedianSupport...` fields should define the lower and upper median-mask overlap band.

Update the comments for `slowMoverMedianSupportOverlapFraction` and `slowMoverMedianSupportMaxOverlapFraction` so they describe overlap with the exact median-stack object mask, measured against the maximum-stack candidate footprint.

Add the new maximum-stack shape controls:

```text
slowMoverMinElongationBaselineMadMultiplier
slowMoverMaxElongation
```

`slowMoverMinElongationBaselineMadMultiplier` should be a gentle baseline-excess gate. A candidate starting point is around `1.0`, but this should be validated against real data.

`slowMoverMaxElongation` should be an absolute cap for rejecting long streak-like envelopes. A candidate starting point is around `3.0`, because that roughly corresponds to a total sweep of about two object diameters.

Mark these as legacy once the maximum-stack branch is active:

```text
slowMoverStackMiddleFraction
slowMoverBaselineMadMultiplier
enableSlowMoverResidualFootprintFiltering
slowMoverResidualFootprintMinFluxFraction
```

Keep the legacy fields temporarily for JSON/config compatibility if old configs are expected to load without migration.

### `SlowMoverAnalyzer.java`

Change the branch from percentile-stack extraction to maximum-stack envelope extraction.

Required changes:

1. Stop using `MasterMapGenerator.createSlowMoverMasterStack(...)` as the primary candidate image.
2. Use `MasterMapGenerator.createMaximumMasterStack(cleanFrames)` as the candidate-envelope image, or accept an already-built `maximumStackData` from `JTransientEngine` to avoid generating the same stack twice.
3. Extract raw candidate components from `maximumStackData` using:
   - `masterSlowMoverSigmaMultiplier`
   - `masterSlowMoverMinPixels`
   - stage-local `growSigmaMultiplier = masterSlowMoverGrowSigmaMultiplier`
4. Extract median-stack objects from `masterStackData` using the same slow-mover thresholds.
5. Build `medianMask` from the exact median-stack object footprints with no dilation for the first implementation.
6. Measure each maximum-stack candidate footprint against `medianMask`.
7. Replace the current dynamic elongation gate based on raw slow-mover candidates with a shape window:
   - lower bound from a baseline object elongation distribution:
     `baselineMedianElongation + baselineMadElongation * slowMoverMinElongationBaselineMadMultiplier`
   - upper bound from `slowMoverMaxElongation`
8. Prefer the baseline elongation distribution from median-stack comparison objects, because that represents ordinary stationary field shape. If there are too few median-stack objects, fall back to maximum-stack raw candidates and record that fallback in telemetry.
9. Apply median-mask overlap using the existing band:
   - reject below `slowMoverMedianSupportOverlapFraction`
   - reject above `slowMoverMedianSupportMaxOverlapFraction`
10. Remove the residual-footprint acceptance gate from the maximum-stack path for the first implementation.
11. Delete or isolate the residual-footprint helper methods and debug output if no legacy percentile-stack path remains.

The candidate `DetectedObject` stored in the result should be the maximum-stack component, not an object from the median stack and not an object-to-object match.

### `SlowMoverAnalysis.java`

Update the grouped analysis payload so its field names describe the new data products.

Preferred new fields:

```text
maximumStackData
medianMask
candidates
telemetry
```

For compatibility, either keep `slowMoverStackData` and `medianVetoMask` as deprecated aliases or populate the existing fields with the maximum-stack data and median mask during a transition period. If aliases are kept, document that `slowMoverStackData` now points to the candidate-envelope stack, not the old percentile stack.

Update constructor JavaDoc and `empty()` so callers can tell whether the branch was disabled or simply found zero candidates.

### `SlowMoverCandidateDiagnostics.java`

Replace residual-footprint diagnostics with maximum-stack/median-mask diagnostics.

Keep or add:

```text
medianSupportOverlap
outsideMedianMaskFraction
footprintPixelCount
candidateElongation
candidatePixelArea
```

Optional but useful diagnostics:

```text
minElongationThreshold
maxElongationThreshold
medianSupportMinOverlapThreshold
medianSupportMaxOverlapThreshold
```

Remove these from the maximum-stack path unless a legacy branch still emits them:

```text
residualFootprintFluxFraction
residualFootprintFlux
slowMoverFootprintFlux
medianFootprintFlux
residualFootprintFilteringEnabled
```

The diagnostics should describe why a maximum-stack envelope survived the shape and median-mask filters.

### `SlowMoverCandidateResult.java`

The existing structure can stay:

```text
object
diagnostics
```

The meaning of `object` should be clarified in JavaDoc: it is the accepted maximum-stack candidate envelope. No extra object-matching result is needed unless temporal validation is added later.

### `SlowMoverSummaryTelemetry.java`

Update aggregate telemetry to match the new filter stages.

Keep:

```text
rawCandidatesExtracted
rejectedLowMedianSupport
rejectedHighMedianSupport
candidatesDetected
medianSupportOverlapThreshold
medianSupportMaxOverlapThreshold
avgMedianSupportOverlap
```

Rename or replace elongation telemetry so it is clear the baseline comes from ordinary field objects:

```text
baselineMedianElongation
baselineMadElongation
minElongationThreshold
maxElongationThreshold
candidatesAboveMinElongationThreshold
rejectedBelowMinElongation
rejectedAboveMaxElongation
baselineSource
```

Remove or mark legacy:

```text
dynamicElongationThreshold
rejectedLowResidualFootprintSupport
residualFootprintMinFluxFractionThreshold
avgResidualFootprintFluxFraction
```

If public API compatibility is important, keep the old fields for one release but populate the new fields as the authoritative values.

### `PipelineTelemetry.java`

Mirror the `SlowMoverSummaryTelemetry` changes in `PipelineTelemetry.SlowMoverTelemetry`.

The nested telemetry object currently uses legacy percentile-stack terms. Update comments and fields so reports can distinguish:

- raw maximum-stack components
- components rejected by minimum elongation
- components rejected by maximum elongation
- components evaluated against the median mask
- components rejected by the median-mask lower and upper bounds
- final accepted maximum-stack slow movers

Keep `candidateMedianSupportOverlaps`, or rename it to `candidateMedianMaskOverlaps` only if the API break is acceptable. If kept, update the comment to say it is median-mask overlap for accepted maximum-stack candidates.

### `JTransientEngine.java`

Move maximum-stack generation earlier in `runPipeline(...)` when slow-mover detection is enabled.

Recommended flow:

1. Build or receive the median master stack as today.
2. Generate `maximumStackData` before calling `SlowMoverAnalyzer`.
3. Pass `maximumStackData`, `masterStackData`, clean frames if still needed, and `config` into `SlowMoverAnalyzer`.
4. Reuse the same `maximumStackData` for `PipelineResult.maximumStackData` so the engine does not generate the maximum stack twice.
5. Update progress text and debug output from "Slow Mover Master Stack" to "Maximum Stack Slow Mover Detection" or similar.
6. Update debug counters to use the new telemetry fields and remove residual-footprint debug reporting from the maximum-stack path.

If `enableSlowMoverDetection` is false, the engine can still generate `maximumStackData` at the end as it does today for visualization/export.

### `PipelineResult.java`

Keep `maximumStackData` as the general maximum-stack export.

Update slow-mover compatibility fields:

```text
slowMoverAnalysis
slowMoverStackData
slowMoverMedianVetoMask
slowMoverCandidates
```

Preferred transition behavior:

- `slowMoverAnalysis.maximumStackData` points to the maximum-stack candidate image.
- `slowMoverAnalysis.medianMask` points to the median-stack object mask used by the slow-mover branch.
- `slowMoverStackData` is either deprecated and set to the same array as `maximumStackData`, or left as a legacy-only field if the percentile branch remains available.
- `slowMoverMedianVetoMask` is deprecated in favor of `slowMoverAnalysis.medianMask`.
- `slowMoverCandidates` remains a compatibility list derived from `slowMoverAnalysis.candidates`.

Update JavaDoc so callers do not assume `slowMoverStackData` is still the old P87.5-style stack.

### `MasterMapGenerator.java`

`createMaximumMasterStack(...)` already exists and should become the image source for the new slow-mover candidate envelopes.

`createSlowMoverMasterStack(...)` should be marked legacy if the percentile-stack branch is removed. If both branches coexist during comparison, keep it unchanged and make the branch choice explicit in `SlowMoverAnalyzer` or `DetectionConfig`.

### Documentation Files

Update after implementation:

- `ALGORITHM.md`: replace the current slow-mover phase description with the maximum-stack envelope and exact median-mask overlap flow.
- `CONFIG.md`: document the new shape fields, mark legacy fields, and describe `slowMoverMedianSupport...` as the median-mask overlap band.
- `PIPELINE.md`: update exported slow-mover data products and compatibility-field meanings.
- `README.md`: update examples or summaries that describe `slowMoverStackData`.

### Tests

Update `src/test/java/io/github/ppissias/jtransient/engine/JTransientEngineSlowMoverTest.java`.

Required test changes:

1. Replace residual-footprint tests with median-mask overlap-band and max-elongation/min-elongation tests.
2. Add a test that candidates are extracted from the maximum stack rather than the percentile slow-mover stack.
3. Add a test that median-stack objects build an exact mask and are not matched object-to-object.
4. Add a test for the lower median-support bound using `slowMoverMedianSupportOverlapFraction`.
5. Add a test for the upper median-support bound using `slowMoverMedianSupportMaxOverlapFraction`.
6. Add a test that very elongated maximum-stack envelopes are rejected by `slowMoverMaxElongation`.
7. Add a test that the baseline elongation threshold can fall back cleanly when too few median-stack objects exist.
8. Update integration assertions for `PipelineResult.slowMoverAnalysis`, `PipelineResult.maximumStackData`, and any compatibility aliases.

If the filter method remains private, the current reflection-based tests can be adapted, but package-private helper methods would make the new filter stages easier to test directly.

### Migration Order

Recommended edit order:

1. Add new config fields and telemetry fields while keeping legacy fields.
2. Update `SlowMoverCandidateDiagnostics` and `SlowMoverAnalysis` to support the new data products.
3. Change `SlowMoverAnalyzer` to accept/use `maximumStackData` and implement the new filters.
4. Update `JTransientEngine` to generate and pass the maximum stack once.
5. Update `PipelineResult` compatibility JavaDoc and assignments.
6. Update tests.
7. Update `ALGORITHM.md`, `CONFIG.md`, `PIPELINE.md`, and `README.md`.
8. After validation, decide whether to remove the legacy percentile-stack branch or keep it behind an explicit compatibility switch.
