# JTransient: The Detection Algorithm

This document provides a highly detailed, chronological breakdown of the mathematical and logical steps executed when calling `JTransientEngine.runPipeline()`. 

The JTransient pipeline is a "waterfall" architecture. It progressively refines significant amounts of raw pixel data down to a handful of confirmed astrodynamical tracks by executing a series of strict statistical, morphological, and kinematic filters.

---

## 1. Pre-Extraction: Dither & Drift Diagnostics
Before extracting any stars, the engine dynamically evaluates the spatial integrity of the image sequence. 
When frames are captured using dithering and subsequently aligned (registered) by external stacking software, the shifting frames leave absolute black "voids" around the borders.

1.  **Background Estimation:** The engine calculates a fast 11x11 median of the center of the image to establish a baseline background sky value, avoiding bright stars.
2.  **Corner Raycasting:** It mathematically "walks" inward from the exact middle of the Top, Bottom, Left, and Right edges of every frame.
3.  **Void Detection:** The moment the raycast hits a pixel brighter than `voidThresholdFraction`, it records that distance as the artificial padding depth.
4.  **Translation Vector:** It subtracts opposing edges (`leftPad - rightPad`, `topPad - bottomPad`) to calculate the exact `(dx, dy)` translation vector of the dither shift.
5.  **Dynamic Safety Override:** It finds the absolute maximum drift across the entire sequence. If this drift plus a 10-pixel safety envelope exceeds the user's configured `voidProximityRadius`, it automatically overrides the configuration. This is designed to ensure that subsequent extraction algorithms will not trip over artificial black borders.

---

## 2. Phase 1: Parallel Source Extraction
The engine spawns a highly-concurrent thread pool to process every input frame simultaneously. For each frame, it executes the `SourceExtractor`.

### A. Iterative Sigma Clipped Background
To detect faint transients, the engine must robustly estimate the background noise.
1.  It builds a full 16-bit histogram of the image.
2.  It calculates the median and standard deviation (`sigma`).
3.  It mathematically chops off the top of the histogram (rejecting values higher than `bgClippingFactor * sigma`), which effectively deletes the bright stars from the math.
4.  It recalculates the median and sigma on the remaining "dark sky" pixels. It iterates this process `bgClippingIterations` times to establish an incredibly accurate, unpolluted noise floor.

### B. Breadth-First Search (BFS) Hysteresis Blob Detection
1.  **Seed Pixel:** It scans the image for any pixel brighter than `Median + (Sigma * detectionSigmaMultiplier)`.
2.  **Region Growing:** Once a seed is found, a BFS queue expands outward in 8 directions. It continues absorbing neighboring pixels until their brightness drops below the more lenient `growSigmaMultiplier`. This dual-thresholding captures the faint, fading edges of fast-moving targets.

### C. Morphological Shape Analysis (Image Moments)
For every detected blob, the engine calculates its "Image Moments" to derive continuous, sub-pixel physics:
*   **Centroid (`x`, `y`)**: The flux-weighted center of mass (highly resistant to edge flickering).
*   **FWHM**: The Full Width at Half Maximum (the optical blur of the object).
*   **Elongation & Angle**: Uses spatial variance (eigenvalues) to determine if the object is approximately circular (star) or stretched (streak), and records its travel angle.

### D. Baseline Rejections
*   **Noise Floor:** Rejects any blob containing fewer than `minDetectionPixels`.
*   **Physical Edge:** Rejects point-sources too close to the sensor borders (ignoring streaks so meteors aren't lost).
*   **Virtual Void Edge:** Uses the dynamically calibrated `voidProximityRadius` to trace a circle around the blob. If it touches pure black padding, it is destroyed as an alignment artifact.

---

## 3. Session Quality Evaluation
Once all frames are extracted, the `SessionEvaluator` protects the tracking pipeline from meteorological anomalies (clouds, wind, trailing).

1.  **Global MAD Statistics:** It looks at the median Star Count, median FWHM, and median Background Noise across the *entire session*. It calculates the Median Absolute Deviation (MAD).
2.  **Outlier Rejection:** 
    *   If a frame's star count plummets below the MAD limit -> **Rejected (Clouds/Dew).**
    *   If a frame's FWHM spikes -> **Rejected (Bad Focus/Wind).**
    *   If a frame's eccentricity spikes -> **Rejected (Mount Bump/Cable Snag).**

*Only frames that survive this rigorous quality check proceed to the Master Stack and Tracking phases.*

---

## 4. Phase 0: Master Map Generation
To identify transients, the engine must first learn what is *not* a transient. It generates a "Deep Master Star Map."

1.  **Median Stacking:** It takes all clean frames and physically sorts the pixel arrays using a parallel stream. By selecting the median pixel value, moving transients (asteroids, satellites, cosmic rays) are mathematically erased, leaving only the permanent, stationary stars.
2.  **Core-Only Extraction:** The engine overrides the standard configuration, strictly tying the `growSigmaMultiplier` to the `masterSigmaMultiplier`. This forces the `SourceExtractor` to only extract the tight, bright cores of the stars on the Master Stack, preventing bloated masks.
3.  **Corner Coma Protection:** It forces `streakMinElongation = 999.0` so that optically distorted stars in the corners of the telescope's field of view are correctly mapped as stars, rather than being discarded as "noise".

---

## 5. Phase 0.5: Slow Mover Detection (Optional)
Standard geometric tracking algorithms fail to track objects that move less than the atmospheric seeing variance (e.g., 2 pixels per frame). To catch these distant KBOs/asteroids, the engine runs a dedicated statistical analysis.

1.  **Percentile Stacking:** Instead of a Median stack, the engine generates a High-Percentile stack (e.g., the 85th percentile). This mathematically erases fast single-frame anomalies, but preserves slow-moving asteroids that overlap the same pixel for 2-3 frames, drawing them as a solid streak!
2.  **Dynamic Statistical Baseline:** The engine extracts all blobs from this stack. It measures the median elongation of the background stars. It calculates the MAD, and establishes a dynamic threshold: `Threshold = Median Elongation + (MAD * Multiplier)`. This dynamically adapts to the optical trailing conditions of the specific dataset.
3.  **Artifact Veto:** Slow movers are evaluated against the Median Master Stack using a pixel-perfect overlap mask. If an elongated blob has >85% overlapping pixels with an object in the Median Stack, it is definitively flagged as a stationary double-star artifact and destroyed.
4.  **Master Map Purification:** If a slow mover was so slow that its bright center baked itself into the Median Master Stack, it is identified and forcefully purged from the `masterStars` list to prevent it from casting a veto mask over its own path!

---

## 6. Track Linking: Streak Separation (Phase 1 & 2)
The processed frames and Master Stars are handed to the `TrackLinker`.

1.  **Separation:** Extracted objects are split into two lists: `validMovingStreaks` and `pointSourcesOnly`. 
2.  **Closest-Neighbor Fast Linking:** Streaks uniquely bypass the Master Star Veto Mask. The engine attempts to link streaks chronologically using a "Closest-Neighbor" geometric loop. 
3.  **Polarity and Collinearity:** A baseline trajectory vector is drawn from Streak A to Streak B. The mathematical angle of the streaks themselves must match this vector. Subsequent streaks must pass a strict `isDirectionConsistent` check to ensure the track maintains forward polarity and does not bounce backwards.

---

## 7. Track Linking: The Binary Veto Mask (Phase 3)
The engine must purge the millions of stationary background points from the individual frames before tracking.

1.  **Mask Dilation:** A highly optimized 2D boolean array is created in memory. The engine iterates over every pixel (`rawPixels`) of every `masterStar`. It draws a circle around each pixel with a radius of `maxStarJitter`. This projects a protective halo covering both the star and the space it might wobble into due to atmospheric seeing.
2.  **Fractional Veto:** The engine overlays every point source from every frame onto this boolean mask. It counts the number of physical pixels that touch the mask. If the ratio exceeds `maxMaskOverlapFraction` (e.g., 75%), the object is destroyed as a stationary star. If it is below, it is flagged as a candidate transient.

---

## 8. Track Linking: Kinematics (Phases 3.5 & 4)
The surviving pure transients are mathematically evaluated to find orbital trajectories.

### A. Time-Based Velocity Tracking (Phase 3.5)
If the images contain valid timestamps, the engine attempts highly rigid physics tracking first.
1.  It links Point 1 and Point 2, completely ignoring the `maxJumpPixels` limit.
2.  It calculates the absolute velocity vector in `pixels / millisecond`.
3.  For Point 3+, it calculates the new velocity. If the speed variation is within `timeBasedVelocityTolerance` (e.g., 25%), and the trajectory angle strictly matches, the points are linked. This is designed to reliably reconstruct high-speed LEO satellite passes across uneven frame gaps.

### B. Geometric Collinear Tracking (Phase 4)
For slow asteroids and frames without timestamps, the engine relies on purely geometric alignment.
1.  **Baseline Gates:** Point 1 and Point 2 are linked. The distance must be greater than `maxStarJitter` (otherwise it's a wobbling star) and less than `maxJumpPixels`.
2.  **Optical Fingerprint Filter (`isProfileConsistent`):** The engine compares the FWHM (optical spread) and Surface Brightness (Flux/Area) of the two points. If they do not match, it assumes a sharp cosmic ray is being linked to a diffuse noise smudge, and aborts the track.
3.  **Infinite Trajectory Projection:** A mathematical line is drawn through Point 1 and Point 2 and projected infinitely across the sensor.
4.  **Collinear Search:** When searching for Point 3+, the algorithm calculates the perpendicular error distance from the candidate point to the infinitely projected line. If the error is `<= predictionTolerance` and the point moves in the correct forward direction, it is added to the track.
5.  **Smart Point Pruning (Anti-Hijack):** The engine scans the finalized track. If a track stalls (a step distance `< maxStarJitter`), it realizes a background star temporarily hijacked the trajectory line, and prunes the bad point.

### C. The Rhythm Engine
Before confirming a Phase 4 track, the engine executes `hasSteadyRhythm`.
1.  It calculates the physical jump distance between all points in the track and finds the Median Jump.
2.  Asteroids do not accelerate wildly. The engine requires that a specific ratio (`rhythmMinConsistencyRatio`, e.g., 70%) of all jumps in the track must equal a multiple of this median jump, within an allowable variance (`rhythmAllowedVariance`). This ensures the object moved at a mathematically steady pace across the sensor.

---

## 9. Phase 5: High-Energy Anomaly Rescue
If any transient successfully bypassed the Master Veto mask but failed to form a multi-frame track, it lands in the discarded pile.

1.  The Anomaly engine scans this pile. 
2.  If an orphan point is physically massive (`anomalyMinPixels`) and incredibly bright relative to the background sky (`anomalyMinPeakSigma`), it recognizes that this is not noise.
3.  It rescues the point and exports it as a confirmed `isAnomaly` track, successfully catching single-frame tumbling satellite glints, distant meteor flashes, and localized optical phenomena!

---

## Annex A: The Auto-Tuner Algorithm
The `JTransientAutoTuner` is a heuristic optimization engine designed to mathematically sample a sequence of images, simulate the detection pipeline under various parameter combinations, and deduce the exact optical baseline of the dataset.

### 1. Quality Sampling
Running a full parameter sweep on hundreds of frames would be computationally prohibitive. 
1.  The tuner runs the `FrameQualityAnalyzer` over every frame in the sequence.
2.  It scores each frame based on its optical clarity: `Quality Score = Background Noise * Median FWHM`. (Lower is better).
3.  It selects the top 5 (configurable) highest-quality frames to use as the tuning sample and generates a Median Master Stack strictly from these 5 frames.

### 2. Phase 1: The Signal-to-Noise Sweep
The tuner iterates through a predefined grid of combinations (e.g., testing Sigma `4.0` through `7.0` against Minimum Pixels `3` through `12`).

For every parameter combination, it runs a mini-simulation of the JTransient pipeline:
1.  **Extract Simulated Master Map:** It extracts the stationary stars from the sample Master Stack using the current test parameters.
2.  **Build Simulated Veto Mask:** It dilates these stars into a boolean mask.
3.  **Evaluate Frame Transients:** It extracts sources from the individual sample frames and tests them against the mask.
    *   Objects hitting the mask are counted as **Stable Stars**.
    *   Objects missing the mask are counted as **Transients (Noise)**, since in a 5-frame sample, almost everything outside the mask is likely read-noise.
4.  **Strict Noise Gates:** The combination is immediately rejected if the ratio of Transients to Total Objects is greater than `5%`, or if the engine fails to find at least `15` stable stars.
5.  **Heuristic Scoring:** Surviving combinations are scored using the following formula:
    `Score = CappedStars - (AvgTransients * Wt) - (Sigma * Ws) - (MinPix * Wm)`
    *   *CappedStars*: The number of stable stars found (capped at 100 so the engine doesn't chase noisy, bloated star counts).
    *   *Penalties*: High noise (`Wt`), high threshold (`Ws`), and high minimum size (`Wm`) are penalized based on config weights. This forces the engine to naturally seek the **lowest possible threshold** that remains mathematically clean.

### 3. Phase 2: Optical Calibration (Self-Measurement)
Once the highest-scoring combination of Sigma and Min Pixels is found, the tuner uses the objects extracted by that configuration to measure the actual physical behavior of the telescope and atmosphere.

1.  **Stationary Object Search:** It compares the objects in Frame 1 to Frame 2. If an object is found within a 4.0-pixel search radius, it is classified as a genuine stationary background star.
2.  **Jitter Measurement (Seeing):** It collects the precise jumping distances of all these stationary stars, sorts them, and finds the **90th Percentile**. It multiplies this distance by a safety factor (e.g., 2.0x) and assigns it directly to `config.maxStarJitter`. This perfectly calibrates the tracker to the atmospheric seeing of that specific night.
3.  **Elongation Measurement (Tracking Error):** It measures the median morphological elongation (aspect ratio) of these stars to establish an optical baseline.
    *   If the mount was tracking poorly or suffering from coma, the median elongation might be `2.0` (ovals).
    *   The tuner automatically updates `config.maxElongationForFwhm` and `config.streakMinElongation` by adding a safety buffer to this median. This significantly reduces the chance that wind-shaken stars are accidentally classified as meteor streaks.

### 4. Final Output
If the tuner successfully navigated the noise gates, it overwrites the user's `DetectionConfig` with these empirically derived limits. If the image is too cloudy or noisy to pass the 5% noise gate, the tuner aborts and defaults back to the user's original configuration.