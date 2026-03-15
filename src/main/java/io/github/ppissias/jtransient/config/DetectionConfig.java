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
public class DetectionConfig {

    // =================================================================
    // 1. SOURCE EXTRACTOR PARAMETERS
    // =================================================================

    /** * The strict baseline requirement to even start looking at a pixel.
     * The engine calculates the background median and sigma (noise).
     * A pixel must be strictly brighter than (Median + (Sigma * detectionSigmaMultiplier)) to spawn a new object.
     */
    public double detectionSigmaMultiplier = 7.0;

    /** * Dual-Thresholding (Hysteresis): Once a bright "seed" pixel is found, the Breadth-First Search (BFS)
     * algorithm expands outward, absorbing neighbors. It stops when pixel values drop below this secondary, lower threshold.
     * This prevents "region spilling" (leaking into the noise) while capturing the faint, fading edges of real streaks.
     */
    public double growSigmaMultiplier = 1.5;

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
    public double streakMinElongation = 5.0;

    /** * Secondary size filter for streaks. To be officially tagged as a streak, the elongated object
     * must have at least this many pixels.
     */
    public int streakMinPixels = 10;


    /** * Number of passes used in the iterative histogram calculation to mathematically chop off
     * bright stars so they don't corrupt the background sky noise calculation.
     */
    public int bgClippingIterations = 3;

    /** * Threshold (in standard deviations) used to chop off pixels during the iterative background calculation.
     */
    public double bgClippingFactor = 3.0;


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

    /** * The radius within which a stationary object is allowed to wobble between frames due to atmospheric seeing.
     * If an object moves less than this, it is considered stationary.
     */
    public double maxStarJitter = 3.0;

    /** * Multiplier for maxStarJitter to account for long-term atmospheric wobble and slight field rotation
     * over the entire imaging session.
     */
    public double starJitterExpansionFactor = 1.5;

    /** * Once a baseline vector is established (Points 1 and 2), Point 3 must fall within this many pixels
     * of the infinitely projected mathematical trajectory line.
     */
    public double predictionTolerance = 3.0;

    /** * For fast streaks, this ensures the streak's physical rotation angle matches the
     * trajectory vector it is traveling on.
     */
    public double angleToleranceDegrees = 5.0;

    /** * Specifically targeting fast streaks. If a "streak" appears in different frames but its center moves
     * less than this threshold, it is identified as a hot column/sensor defect and destroyed.
     */
    public double stationaryDefectThreshold = 5.0;

    /** * Determines how many points are needed to confirm a track by dividing the total number of frames
     * by this ratio (e.g., 20 frames / 3.0 = ~7 points required).
     */
    public double trackMinFrameRatio = 3.0;

    /** * A hard ceiling on the required track length so the algorithm doesn't demand mathematically
     * impossible track lengths for massive frame batches (e.g., requiring 166 points in a 500 frame session).
     */
    public int absoluteMaxPointsRequired = 5;

    /** * The cosmic speed limit. When looking for the next point in a track, any transient located
     * further than this distance is ignored.
     */
    public double maxJumpPixels = 400.0;

    /** * Morphological Filter: When linking points, the physical pixel area cannot differ by more than this ratio.
     * Prevents linking a massive, bright asteroid to a tiny, faint noise blip.
     */
    public double maxSizeRatio = 3.0;

    /** * Kinematic Speed Check: Max allowed pixel deviation from the expected median speed to still
     * be considered part of a "steady rhythm".
     */
    public double rhythmAllowedVariance = 5.0;

    /** * Kinematic Speed Check: Minimum percentage of jumps (e.g., 0.70 = 70%) that must
     * strictly match the median track speed within the allowed variance.
     */
    public double rhythmMinConsistencyRatio = 0.70;

    /** * Kinematic Speed Check: If the median jump of a track is smaller than this, it is dismissed
     * as stationary noise that accidentally bypassed the star map.
     */
    public double rhythmStationaryThreshold = 0.5;

    /** * Photometric Filter: When linking points, the total brightness (flux) cannot differ by more than this ratio.
     */
    public double maxFluxRatio = 3.0;

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
    public double anomalyMinPeakSigma = 10.0;

    /** * The minimum physical size a single-frame point must have to be rescued.
     * Prevents single hot-pixels or cosmic rays from being flagged. */
    public int anomalyMinPixels = 25;}