/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 * author: Petros Pissias <petrospis at gmail.com>
 *
 */
package io.github.ppissias.jtransient.config;

/**
 * Master configuration object for the JTransient detection pipeline.
 * This class holds all tuning parameters for extraction, quality control, and track linking.
 * It is designed to be easily serialized to/from JSON.
 */
public class DetectionConfig implements Cloneable {

    // =================================================================
    // 1. SOURCE EXTRACTOR PARAMETERS
    // =================================================================

    /** * The strict baseline requirement to even start looking at a pixel.
     * The engine calculates the background median and sigma (noise).
     * A pixel must be strictly brighter than (Median + (Sigma * detectionSigmaMultiplier)) to spawn a new object.
     */
    public double detectionSigmaMultiplier = 5;

    /** * Dual-Thresholding (Hysteresis): Once a bright "seed" pixel is found, the Breadth-First Search (BFS)
     * algorithm expands outward, absorbing neighbors. It stops when pixel values drop below this secondary, lower threshold.
     * This prevents "region spilling" (leaking into the noise) while capturing the faint, fading edges of real streaks.
     */
    public double growSigmaMultiplier = 3;

    /** * The absolute physical floor. If the BFS region-growing finishes and the total blob size is
     * less than this value, it is immediately discarded as read-noise or a hot pixel.
     */
    public int minDetectionPixels = 10;

    /** * A safety border (dead zone). If the calculated mathematical centroid falls within this many pixels
     * of the absolute edge of the array, the object is discarded to prevent image alignment/stacking artifacts
     * from being misclassified as streaks.
     */
    public int edgeMarginPixels = 15;

    /** * Used to combat dark, empty spaces created when images are registered/aligned.
     * Fraction of the background median below which a pixel is considered artificial padding.
     */
    public double voidThresholdFraction = 0.5;

    /** * Distance (in pixels) to look ahead for a void edge. If an object is classified as a streak, the engine
     * looks around it by this radius. If a significant portion touches the void padding, it assumes the streak
     * is an interpolation artifact and kills it.
     */
    public int voidProximityRadius = 20;

    /** * Uses Image Moments (spatial variance) to determine shape. If the square root of the ratio of its
     * eigenvalues (elongation) is greater than this value, the blob is long and thin enough to be considered
     * a fast-moving satellite/meteor streak.
     */
    public double streakMinElongation = 6.0;

    /** * Secondary size filter for streaks. To be officially tagged as a streak, the elongated object
     * must have at least this many pixels.
     */
    public int streakMinPixels = 25;

    /** * Dedicated filter for single-frame streaks to prevent elongated noise/artifacts from being flagged.
     * A streak that appears in only one frame must have a peak signal-to-noise ratio (Sigma) above this value.
     */
    public double singleStreakMinPeakSigma = 7.0;


    /** * Number of passes used in the iterative histogram calculation to mathematically chop off
     * bright stars so they don't corrupt the background sky noise calculation.
     */
    public int bgClippingIterations = 3;

    /** * Threshold (in standard deviations) used to chop off pixels during the iterative background calculation.
     */
    public double bgClippingFactor = 3.0;

    /** * Strict Exposure Kinematics: If an object appears as a round point source in a long exposure,
     * it physically cannot be moving fast. This mathematically bounds its maximum jump distance between
     * frames based on its footprint and the exposure time.
     * NOTE: Turn this OFF if tracking tumbling/flashing LEO satellites that only glint for a fraction of the exposure.
     */
    public boolean strictExposureKinematics = true;

    // =================================================================
    // 1.5 MASTER MAP EXTRACTION PARAMETERS
    // =================================================================


    /** * The baseline requirement for extracting stars to build the Master Star Map. 
     * Typically lower than detectionSigmaMultiplier to ensure faint halos are masked.
     */
    public double masterSigmaMultiplier = 2.25;

    /** * Minimum number of pixels a source must have to be considered a star in the Master Star Map.
     * Lower values allow capturing faint background stars to better protect against noise.
     */
    public int masterMinDetectionPixels = 3;

    /** * Master switch to enable the generation and analysis of the specialized Slow Mover stack.
     * Set to true to actively hunt for ultra-slow moving objects like distant asteroids.
     */
    public boolean enableSlowMoverDetection = true;

    /** * The fraction of the sorted pixel values around the median to use when generating a Slow Mover Master Stack.
     * By taking the maximum of this middle band, the stack captures ultra-slow moving objects without including
     * high-energy single-frame flashes (like fast streaks or cosmic rays).
     */
    public double slowMoverStackMiddleFraction = 0.75;

    /** * Minimum pixel area required to flag an elongated object in the master stack as a slow mover candidate. */
    public int masterSlowMoverMinPixels = 15;

    /** * The strict detection threshold multiplier used exclusively when scanning the master stack for ultra-slow movers. */
    public double masterSlowMoverSigmaMultiplier = 4;

    /** * The grow sigma multiplier (hysteresis) used exclusively when scanning the master stack for ultra-slow movers. */
    public double masterSlowMoverGrowSigmaMultiplier = 3.5;

    /** * Multiplier applied to the MAD to calculate the dynamic elongation threshold for slow movers.
     * A value of 5.0 means an object must be 5 deviations more elongated than the median to be flagged.
     */
    public double slowMoverBaselineMadMultiplier = 4.5;

    // =================================================================
    // 2. FRAME QUALITY ANALYZER PARAMETERS
    // =================================================================

    /** * Sigma multiplier used to extract only strong, undeniable stars specifically for frame quality evaluation,
     * bypassing the standard extraction parameters.
     */
    public double qualitySigmaMultiplier = 5.0;

    /** * Minimum number of contiguous pixels a source must have to be evaluated as a valid reference star
     * for frame quality.
     */
    public int qualityMinDetectionPixels = 5;

    /** * Trailed stars (due to wind or mount errors) artificially inflate FWHM (focus) measurements.
     * Only stars with an elongation below this value are used to calculate the frame's median focus.
     */
    public double maxElongationForFwhm = 1.5;

    /** * If a frame is a total washout (e.g., thick clouds, completely black) and yields zero reference stars,
     * the engine assigns this terrible score so it is guaranteed to be rejected by the session evaluator.
     */
    public double errorFallbackValue = 999.0;

    // --- Absolute Minimum Tolerances ---

    /** * An absolute floor for background deviation. Prevents frames from being rejected on perfectly stable nights
     * just because the sky background shifted by normal, microscopic read-noise amounts.
     */
    public double minBackgroundDeviationADU = 10.0;

    /** * Absolute bounds for shape quality. Prevents MAD statistics from becoming hyper-sensitive,
     * ensuring minor, sub-pixel tracking shifts are ignored.
     */
    public double minEccentricityEnvelope = 0.10;

    /** * Absolute bounds for focus quality. Prevents MAD statistics from becoming hyper-sensitive,
     * ensuring minor, sub-pixel focus fluctuations are ignored.
     */
    public double minFwhmEnvelope = 0.5;

    // =================================================================
    // 3. TRACK LINKER PARAMETERS
    // =================================================================

    /** * Expected Star Jitter (in pixels). Represents the maximum atmospheric wobble (seeing) or focus bloat
     * between perfectly aligned frames. Used to dilate the Master Star Mask and as the minimum speed limit
     * for moving objects.
     */
    public double maxStarJitter = 1.5;

    /** * Instead of a strict 1-pixel touch destroying a transient, allow it to overlap the master star mask
     * up to this fraction (e.g., 0.25 = 25%). This rescues high-energy transients or moving objects
     * that happen to graze the protective halo of a background star.
     */
    public double maxMaskOverlapFraction = 0.75;

    /** * Once a baseline vector is established (Points 1 and 2), Point 3 must fall within this many pixels
     * of the infinitely projected mathematical trajectory line.
     */
    public double predictionTolerance = 3.0;

    /** * For fast streaks, this ensures the streak's physical rotation angle matches the
     * trajectory vector it is traveling on.
     */
    public double angleToleranceDegrees = 2;

    /** * Determines how many points are needed to confirm a track by dividing the total number of frames
     * by this ratio (e.g., 20 frames / 3.0 = ~7 points required).
     */
    public double trackMinFrameRatio = 3.0;

    /** * When valid timestamps are available, this defines the maximum allowed variance in velocity (speed).
     * A value of 0.10 means a 10% change in velocity between jumps is acceptable.
     * Tracks linked using time bypass the maxJumpPixels constraint.
     */
    public double timeBasedVelocityTolerance = 0.10;

    /** * A hard ceiling on the required track length so the algorithm doesn't demand mathematically
     * impossible track lengths for massive frame batches (e.g., requiring 166 points in a 500 frame session).
     */
    public int absoluteMaxPointsRequired = 5;

    /** * The cosmic speed limit. When looking for the next point in a track, any transient located
     * further than this distance is ignored.
     */
    public double maxJumpPixels = 400.0;


    /** * Morphological Filter: FWHM represents the optical focus/spread. Real targets share similar optical blurring.
     * A value of 2.0 means the FWHM cannot more than double between frames. 0 to disable. */
    public double maxFwhmRatio = 2.0;

    /** * Morphological Filter: Surface Brightness (Flux / Area) identifies the density of the light.
     * Prevents linking a concentrated cosmic ray to a diffuse noise smudge. 0 to disable. */
    public double maxSurfaceBrightnessRatio = 2.0;

    /** * Kinematic Speed Check: Max allowed pixel deviation from the expected median speed to still
     * be considered part of a "steady rhythm".
     */
    public double rhythmAllowedVariance = 8.0;

    /** * Kinematic Speed Check: Minimum percentage of jumps (e.g., 0.70 = 70%) that must
     * strictly match the median track speed within the allowed variance.
     */
    public double rhythmMinConsistencyRatio = 0.70;

    /** * Kinematic Speed Check: If the median jump of a track is smaller than this, it is dismissed
     * as stationary noise that accidentally bypassed the star map.
     */
    public double rhythmStationaryThreshold = 0.5;



    // =================================================================
    // 4. SESSION EVALUATOR PARAMETERS
    // =================================================================

    /** * The statistical engine requires a minimum sample size to calculate standard deviations.
     * If the session has fewer frames than this, outlier rejection is skipped.
     */
    public int minFramesForAnalysis = 3;

    /** * Rejects frames where the star count drops significantly below the session median
     * (indicates passing clouds, heavy haze, or dew on the lens).
     */
    public double starCountSigmaDeviation = 2.0;

    /** * Rejects frames where the median focus (FWHM) spikes above the session median
     * (indicates bad focus, wind shaking the telescope, or terrible atmospheric seeing).
     */
    public double fwhmSigmaDeviation = 2.5;

    /** * Rejects frames where the stars become highly elliptical compared to the median
     * (indicates a tracking failure, mount bump, or cable snag).
     */
    public double eccentricitySigmaDeviation = 3.0;

    /** * Rejects frames where the sky background fluctuates wildly
     * (indicates a car driving by, moonlight entering the tube, or incoming clouds reflecting light pollution).
     */
    public double backgroundSigmaDeviation = 3.0;

    /** * A mathematical safeguard. If every single frame is utterly identical (resulting in a MAD of exactly 0.0),
     * this injects a tiny value to prevent division-by-zero crashes when calculating thresholds.
     */
    public double zeroSigmaFallback = 0.001;

    // =================================================================
    // 5. ANOMALY DETECTION PARAMETERS (Optical Flashes / Glints)
    // =================================================================

    /** * Enable the rescue of single-frame, ultra-bright point sources that failed to form a multi-frame track. */
    public boolean enableAnomalyRescue = true;

    /** * The minimum Peak Signal-to-Noise ratio (Sigma) a single-frame point must have to be rescued.
     * e.g., 50.0 means the brightest pixel in the object is 50x brighter than the background noise. */
    public double anomalyMinPeakSigma = 8;

    /** * The minimum physical size a single-frame point must have to be rescued.
     * Prevents single hot-pixels or cosmic rays from being flagged. */
    public int anomalyMinPixels = 15;


    /**
     * Returns a copy of this configuration.
     */
    @Override
    public DetectionConfig clone() {
        try {
            // Performs a highly optimized native shallow copy.
            // Safe because this class only contains primitive data types.
            return (DetectionConfig) super.clone();
        } catch (CloneNotSupportedException e) {
            throw new AssertionError("DetectionConfig could not be cloned", e);
        }
    }
}
