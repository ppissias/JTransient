# JTransient Detection Pipeline Configuration Guide

This document provides an in-depth breakdown of the `DetectionConfig` object used by the JTransient library. The engine contains two primary entry points:
1. `JTransientEngine.runPipeline`: The core image processing pipeline that performs extraction, quality evaluation, master-map vetoing, and track linking.
2. `JTransientAutoTuner.tune`: A utility that mathematically samples a subset of your images to automatically find the most optimal configuration variables for `runPipeline`.

Below is a detailed explanation of the purpose of every tuning parameter, including exactly where and how it influences the detection algorithms.

---

## 1. Source Extractor Parameters
These parameters govern the low-level pixel processing. They dictate how raw sensor data is converted into physical "objects" (stars, streaks, and transients).

### `detectionSigmaMultiplier`
* **Purpose**: Determines the absolute floor requirement for a pixel to be considered a light source rather than background sky noise.
* **Usage**:
    * **`SourceExtractor.extractSources()`**: Establishes the primary "seed" threshold (`median + (sigma * detectionSigmaMultiplier)`). A pixel must be strictly brighter than this to trigger the Breadth-First Search (BFS) blob detector.
    * **`JTransientEngine.runPipeline()`**: Passed down as the primary extraction sensitivity during Phase 1. It is also used to derive the `masterSigma` (scaled to half its value, with a floor of 1.5) for the highly sensitive Phase 0 Master Star Map generation.
    * **`JTransientAutoTuner.tune()`**: Iteratively mutated (swept through a range of test values) during Phase 1 to find the optimal signal-to-noise ratio.

### `growSigmaMultiplier`
* **Purpose**: Operates the dual-threshold (Hysteresis) blob-growing engine. Once a bright "seed" pixel is found, the algorithm absorbs neighboring pixels until their brightness drops below this secondary, more lenient threshold.
* **Usage**:
    * **`SourceExtractor.extractSources()`**: Dictates the outer boundaries of detected blobs. Prevents faint, fading edges of fast-moving streaks from being prematurely truncated, while avoiding leaking into background noise.

### `minDetectionPixels`
* **Purpose**: Acts as the fundamental morphological size limit. Filters out hot pixels, cosmic rays, and read noise.
* **Usage**:
    * **`SourceExtractor.extractSources()`**: After the BFS region-growing completes, any resulting blob with a pixel count smaller than this value is instantly rejected.
    * **`JTransientEngine.runPipeline()`**: Used as the global minimum size for transients. Also used to derive the `masterMinPix` threshold for the Master Map.
    * **`JTransientAutoTuner.tune()`**: Swept against various values alongside the `detectionSigmaMultiplier` to find the cleanest noise-free extraction baseline.

### `edgeMarginPixels`
* **Purpose**: Establishes a dead zone around the physical edges of the camera sensor to ignore optical vignetting and stacking artifacts.
* **Usage**:
    * **`SourceExtractor.extractSources()`**: Any extracted point source whose computed mathematical centroid falls within this pixel distance from the boundaries is destroyed. *Note: Objects mathematically classified as streaks completely bypass this filter, ensuring fast meteors scraping the sensor edges are still captured!*
    * **`JTransientEngine.runPipeline()` & `JTransientAutoTuner.tune()`**: Temporarily overridden to a tight `5` pixels during the generation of the Master Star Map, ensuring even edge artifacts are recorded as permanent stationary objects to be vetoed later.

### `voidThresholdFraction`
* **Purpose**: Identifies pure black artificial borders created when images are registered/aligned.
* **Usage**:
    * **`SourceExtractor.extractSources()`**: Calculates the threshold (`background median * voidThresholdFraction`). Any pixel below this brightness is considered "void space."

### `voidProximityRadius`
* **Purpose**: Destroys interpolation artifacts on the borders of aligned images.
* **Usage**:
    * **`SourceExtractor.extractSources()`**: During filtering, the engine looks around an object by this radius. If it hits "void space", the object is classified as an artifact and destroyed.
    * **`JTransientEngine.runPipeline()`**: Dynamically overridden by the **Dither & Drift Diagnostics**. Before extraction begins, the engine measures the maximum inward drift of the black alignment void and automatically increases this radius if the actual drift is larger than the configured value.
    * **`JTransientEngine.runPipeline()` & `JTransientAutoTuner.tune()`**: Overridden to `5` during the Master Stack generation to carefully map artifacts.

### `streakMinElongation`
* **Purpose**: Determines if an object is geometrically long enough to be a fast-moving satellite or meteor.
* **Usage**:
    * **`SourceExtractor.analyzeShape()`**: Computes image moments to find an object's spatial variance (ratio of eigenvalues). If the ratio exceeds this value, it's flagged as a candidate streak.
    * **`JTransientAutoTuner.tune()`**: Automatically tuned by measuring the median elongation of standard point sources in the frames and adding a buffer, ensuring typical tracking errors aren't flagged as streaks.

### `streakMinPixels`
* **Purpose**: A secondary size filter exclusively for elongated objects.
* **Usage**:
    * **`SourceExtractor.analyzeShape()`**: Once an object is elongated enough to be a streak, it must also contain at least this many pixels. Otherwise, it is discarded as an elongated cosmic ray or random noise glitch.

### `singleStreakMinPeakSigma`
* **Purpose**: Protects against random sensor noise mimicking a fast streak that vanishes in a single frame.
* **Usage**:
    * **`TrackLinker.findMovingObjects()`**: During Phase 2, if a moving streak cannot be kinematically linked to another frame (i.e., its track length is exactly 1), it must have a `peakSigma` higher than this value to be officially confirmed as a transient.

### `bgClippingIterations` & `bgClippingFactor`
* **Purpose**: Used for high-speed, robust background estimation via Iterative Sigma Clipping.
* **Usage**:
    * **`SourceExtractor.calculateBackgroundSigmaClipped()`**: The engine builds a histogram of the image. It iteratively calculates the standard deviation (`sigma`) and removes bright pixels (stars) that exceed the median by `bgClippingFactor` standard deviations. This loop runs exactly `bgClippingIterations` times, ensuring massive stars don't artificially inflate the dark sky background measurements.

---

## 1.5 Master Map Extraction Parameters
These parameters specifically govern how the robust Master Map (Phase 0) and the Slow Mover Percentile Stack (Phase 0.5) are generated and evaluated.

### `masterSigmaMultiplier` & `masterMinDetectionPixels`
* **Purpose**: The baseline thresholds for extracting the Master Star Map.
* **Usage**:
    * **`JTransientEngine.runPipeline()`**: Used in Phase 0 to extract the stationary stars from the median stack. The engine explicitly ties `growSigmaMultiplier` to `masterSigmaMultiplier` here to disable fuzzy halo expansion, ensuring the generated Veto Masks are tight, crisp, and relaxed.

### `enableSlowMoverDetection`
* **Purpose**: Master switch to enable Phase 0.5.
* **Usage**:
    * **`JTransientEngine.runPipeline()`**: When true, generates a specialized percentile-based Master Stack to capture ultra-slow objects that barely move across the sequence, bypassing the standard geometric point tracker.

### `slowMoverStackMiddleFraction`
* **Purpose**: Determines the specific percentile used to build the Slow Mover stack.
* **Usage**:
    * **`MasterMapGenerator.createSlowMoverMasterStack()`**: Multiplied by the total frame count to extract a high-percentile pixel value (e.g., 85th percentile). Preserves objects that occupy a pixel for only 2-3 frames, while successfully dropping 1-frame flashes and pure background noise.

### `masterSlowMoverSigmaMultiplier`, `masterSlowMoverGrowSigmaMultiplier` & `masterSlowMoverMinPixels`
* **Purpose**: The extraction thresholds used exclusively when scanning the specialized Slow Mover stack.
* **Usage**:
    * **`JTransientEngine.runPipeline()`**: Temporarily applied to the `SourceExtractor` during Phase 0.5 to find candidates in the percentile stack. **Static Artifact Rejection**: These exact same parameters are then used to scan the *Median Stack*. If an elongated object exists in both stacks with > 85% physical overlap, it is rejected as a stationary double-star artifact!

### `slowMoverBaselineMadMultiplier`
* **Purpose**: Defines how aggressive the dynamic elongation threshold is.
* **Usage**:
    * **`JTransientEngine.runPipeline()`**: Measures the median elongation and Median Absolute Deviation (MAD) of all objects in the Slow Mover stack. The threshold for an object to be considered an asteroid trail becomes `Median + (MAD * slowMoverBaselineMadMultiplier)`. This perfectly adapts to nights with high coma or tracking errors.

---

## 2. Frame Quality Analyzer Parameters
These parameters evaluate the optical and environmental quality of each frame.

### `qualitySigmaMultiplier` & `qualityMinDetectionPixels`
* **Purpose**: Extracts a very specific, undeniable subset of standard stars purely for evaluating optical focus and tracking health, completely bypassing the main transient extraction logic.
* **Usage**:
    * **`FrameQualityAnalyzer.evaluateFrame()`**: Passed into `SourceExtractor` to ensure that the Frame Quality Analyzer only looks at the brightest and largest reference stars in the image.

### `maxElongationForFwhm`
* **Purpose**: Ensures that trailed/wind-shaken stars do not corrupt the mathematical measurement of optical focus (FWHM).
* **Usage**:
    * **`FrameQualityAnalyzer.evaluateFrame()`**: The analyzer measures the FWHM of reference stars to determine image blurriness. It ignores any star whose elongation exceeds this threshold.
    * **`JTransientAutoTuner.tune()`**: Adjusted based on the measured typical shape of stable stars.

### `errorFallbackValue`
* **Purpose**: Acts as a catastrophic failure flag.
* **Usage**:
    * **`FrameQualityAnalyzer.evaluateFrame()`**: If a frame is completely devoid of stars (e.g., solid clouds, lens cap left on), the median focus/shape lists are empty. This value (e.g., 999.0) is returned so the `SessionEvaluator` instantly recognizes and kills the frame.

### `minBackgroundDeviationADU`, `minEccentricityEnvelope`, `minFwhmEnvelope`
* **Purpose**: Implements absolute mathematical floors for outlier rejection to prevent the statistics engine from becoming hyper-sensitive on perfectly pristine nights.
* **Usage**:
    * **`SessionEvaluator.rejectOutlierFrames()`**: When calculating the Median Absolute Deviation (MAD) thresholds, the maximum allowed deviations are not allowed to shrink below these values.

---

## 3. Track Linker Parameters
These dictate the kinematic and geometric logic used to assemble points into verified moving transients.

### `maxStarJitter`
* **Purpose**: The core radius that represents optical distortion, seeing wobble, and alignment drift.
* **Usage**:
    * **`TrackLinker.findMovingObjects()`**:
        1. During Phase 3, this value acts as the dilation radius for the Binary Master Veto Mask (thickening the footprint of permanent stars).
        2. During Phase 4, if two points in subsequent frames move less than this distance, they are dismissed as a stationary wobbling star.
        3. During the anti-hijack point pruning, it breaks segments of a track that stall within this radius.
    * **`JTransientAutoTuner.tune()`**: Highly critical auto-tuning parameter. The tuner calculates the 90th percentile jitter distance of stationary objects and assigns it here with a safety multiplier.

### `maxMaskOverlapFraction`
* **Purpose**: Instead of a strict 1-pixel touch destroying a transient, allow it to overlap the master star mask up to this fraction (e.g., 0.25 = 25%).
* **Usage**:
    * **`TrackLinker.findMovingObjects()`**: During Phase 3, counts the physical overlapping pixels between the candidate object and the master mask. This rescues high-energy transients or moving objects that happen to graze the protective halo of a background star.

### `predictionTolerance`
* **Purpose**: The strictness of the geometric collinear linking filter.
* **Usage**:
    * **`TrackLinker.findMovingObjects()`**: After the engine draws a baseline vector from Point 1 and Point 2, the perpendicular distance from Point 3 (or Point 4, 5, etc.) to the infinitely projected mathematical trajectory line must be `<= predictionTolerance`.

### `angleToleranceDegrees`
* **Purpose**: The maximum angular deviation a transient is allowed to make along its travel vector.
* **Usage**:
    * **`TrackLinker.findMovingObjects()`**: Converted to radians.
        1. Used in Phase 2 to verify that a fast streak matches the progressive trajectory vector.
        2. Used in Phase 3.5 and Phase 4 with `isDirectionConsistent` to enforce strict forward polarity (so tracks cannot bounce backwards).

### `trackMinFrameRatio` & `absoluteMaxPointsRequired`
* **Purpose**: Scales the track confirmation difficulty based on the total depth of the sequence.
* **Usage**:
    * **`TrackLinker.findMovingObjects()`**: Calculates `minPointsRequired` as `Total Frames / trackMinFrameRatio`. If you have 30 frames and a ratio of 3.0, you need 10 points to confirm a track. The `absoluteMaxPointsRequired` caps this requirement so massive batches (e.g., 500 frames) do not demand mathematically improbable track lengths.

### `timeBasedVelocityTolerance`
* **Purpose**: When valid timestamps are available, this defines the maximum allowed variance in velocity (speed).
* **Usage**:
    * **`TrackLinker.findMovingObjects()`**: Used exclusively in Phase 3.5. Calculates exact `pixels / millisecond` velocity vectors. If the speed variation between jumps is `<= timeBasedVelocityTolerance` (e.g., 25%), it links the points, completely bypassing the `maxJumpPixels` constraint to effortlessly track massive LEO satellites across gaps.

### `maxJumpPixels`
* **Purpose**: The cosmic speed limit constraint.
* **Usage**:
    * **`TrackLinker.findMovingObjects()`**: In Phase 4 (Geometric Linking), the engine aborts searching for the next point in a track if the candidate point is located further than this pixel distance from the last known location.

### `maxFwhmRatio` & `maxSurfaceBrightnessRatio`
* **Purpose**: The "Optical Fingerprint Filter". Guarantees that points being linked share the exact same physical optical properties.
* **Usage**:
    * **`TrackLinker.findMovingObjects()`**: Applied via `isProfileConsistent` during Phase 3.5 and Phase 4. Checks if the FWHM (optical spread) and Surface Brightness (Flux / Area) of consecutive points match within these ratios. Instantly prevents linking a sharp, dense cosmic ray to a diffuse, tumbling noise smudge, completely superseding fragile raw pixel/flux comparisons.

### `rhythmAllowedVariance`, `rhythmMinConsistencyRatio`, `rhythmStationaryThreshold`
* **Purpose**: The advanced Kinematic Rhythm Engine that validates orbital mechanics.
* **Usage**:
    * **`TrackLinker.findMovingObjects()`**: Before a track is finally approved, `hasSteadyRhythm` evaluates its speed. The median distance jumped between frames is calculated. At least `rhythmMinConsistencyRatio` (e.g., 70%) of all jumps in the track must equal a multiple of this median speed, within a variance of `rhythmAllowedVariance` pixels. Tracks whose median speed drops below `rhythmStationaryThreshold` are vetoed.

---

## 4. Session Evaluator Parameters
These variables control the global statistical Outlier Rejection system, protecting the pipeline from weather and equipment failures.

### `minFramesForAnalysis`
* **Purpose**: A statistical safeguard requiring enough data points to compute standard deviations reliably.
* **Usage**:
    * **`SessionEvaluator.rejectOutlierFrames()`**: If the total input frame sequence is smaller than this count, outlier rejection is skipped completely.

### `starCountSigmaDeviation`, `fwhmSigmaDeviation`, `eccentricitySigmaDeviation`, `backgroundSigmaDeviation`
* **Purpose**: Thresholds for dynamically identifying corrupted frames based on global session statistics.
* **Usage**:
    * **`SessionEvaluator.rejectOutlierFrames()`**: The engine calculates the Median Absolute Deviation (MAD) for each metric across the entire sequence.
        * A frame is rejected if its Star Count drops below the median by `starCountSigmaDeviation * sigma` (Clouds).
        * A frame is rejected if its FWHM spikes above the median by `fwhmSigmaDeviation * sigma` (Blur/Wind).
        * A frame is rejected if its Eccentricity spikes above the median by `eccentricitySigmaDeviation * sigma` (Mount Bump/Tracking Error).
        * A frame is rejected if its Background Sky Noise wildly deviates from the median by `backgroundSigmaDeviation * sigma` (Light pollution bursts/moonlight).

### `zeroSigmaFallback`
* **Purpose**: Prevents division-by-zero crashes.
* **Usage**:
    * **`SessionEvaluator.calculateMedianAndSigma()`**: If every single frame is utterly identical resulting in a MAD of exactly `0.0`, this tiny value is injected.

---

## 5. Anomaly Detection Parameters
These parameters govern the final "safety net" to catch ultra-high-energy glints, tumbling debris flashes, and single-frame meteors that fail to form a conventional multi-frame track.

### `enableAnomalyRescue`
* **Purpose**: Global toggle for the Phase 5 Anomaly Engine.
* **Usage**:
    * **`TrackLinker.findMovingObjects()`**: If true, executes the High-Energy Anomaly Rescue phase to salvage discarded transients.

### `anomalyMinPeakSigma`
* **Purpose**: The extreme photometric requirement for a single-frame point source to be considered a genuine glint.
* **Usage**:
    * **`TrackLinker.findMovingObjects()`**: During Phase 5, unlinked transients must have a maximum pixel brightness that is `anomalyMinPeakSigma` standard deviations brighter than the background sky noise.

### `anomalyMinPixels`
* **Purpose**: The extreme morphological requirement for single-frame point sources.
* **Usage**:
    * **`TrackLinker.findMovingObjects()`**: Protects against hot pixels and cosmic rays. A single-frame anomaly must possess at least this many contiguous pixels to be rescued as a genuine physical object.

---

## 6. Auto-Tuner Parameters
These weights strictly guide the heuristic math inside `JTransientAutoTuner.tune()` to score and rank different configuration combinations.

### `scoreWeightTransientPenalty`
* **Purpose**: The penalty multiplier applied for every "transient" (noise) that leaks through the Master Map during the simulation sweep.

### `scoreWeightSigmaPenalty`
* **Purpose**: The penalty applied for using a higher detection threshold. Forces the engine to favor the *lowest* (most sensitive) threshold that is still perfectly clean.

### `scoreWeightMinPixPenalty`
* **Purpose**: The penalty applied for demanding a higher minimum pixel footprint. Forces the engine to prefer finding smaller objects, penalizing configurations that "cheat" by just ignoring everything small.