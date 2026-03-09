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

    /** The primary strict threshold to start detecting a new object. */
    public double detectionSigmaMultiplier = 7.0;

    /** Dual-Thresholding (Hysteresis): The lower threshold used to trace the faint edges. */
    public double growSigmaMultiplier = 1.2;

    /** The absolute minimum number of pixels a blob must have to even be evaluated. */
    public int minDetectionPixels = 10;

    /** The "dead zone" in pixels around the edge of the sensor. */
    public int edgeMarginPixels = 15;

    /** Fraction of the background median below which a pixel is considered artificial padding. */
    public double voidThresholdFraction = 0.5;

    /** Distance (in pixels) to look ahead for a void edge to defeat interpolation gradients. */
    public int voidProximityRadius = 3;

    /** Minimum elongation ratio (length/width) required to classify a blob as a fast-moving streak. */
    public double streakMinElongation = 5.0;

    /** Minimum number of pixels required to classify an elongated blob as a streak. */
    public int streakMinPixels = 10;

    /** Minimum number of pixels to classify a blob as a point source (star/asteroid). */
    public int pointSourceMinPixels = 4;

    /** Number of passes used to exclude bright stars when calculating the background sky noise. */
    public int bgClippingIterations = 3;

    /** Threshold (in standard deviations) used to chop off stars during background calculation. */
    public double bgClippingFactor = 3.0;


    // =================================================================
    // 2. FRAME QUALITY ANALYZER PARAMETERS
    // =================================================================

    /** Sigma multiplier used to extract strong, distinct stars specifically for frame quality evaluation. */
    public double qualitySigmaMultiplier = 5.0;

    /** Minimum number of contiguous pixels a source must have to be evaluated for frame quality. */
    public int qualityMinDetectionPixels = 5;

    /** Maximum elongation ratio a star can have to be included in the FWHM (focus) calculation. */
    public double maxElongationForFwhm = 1.5;

    /** Fallback metric value assigned when a frame is completely devoid of valid stars. */
    public double errorFallbackValue = 999.0;


    // =================================================================
    // 3. TRACK LINKER PARAMETERS
    // =================================================================

    /** Maximum distance (in pixels) stars can wobble between frames due to seeing. */
    public double maxStarJitter = 3.0;

    /** Allowable radius (in pixels) for the predicted path of a moving object. */
    public double predictionTolerance = 3.0;

    /** Maximum angle difference (in degrees) allowed between streak trajectory and orientation. */
    public double angleToleranceDegrees = 5.0;

    /** Max movement (in pixels) allowed for a streak to be considered a stationary sensor defect. */
    public double stationaryDefectThreshold = 5.0;

    /** How many frames an object must appear in the exact same spot to be classified as a stationary star. */
    public int requiredDetectionsToBeStar = 2;

    /** Multiplier for star jitter to account for long-term atmospheric wobble over the entire session. */
    public double starJitterExpansionFactor = 1.5;

    /** Denominator used to calculate minimum points required (e.g., 20 frames / 3.0 = ~7 points required). */
    public double trackMinFrameRatio = 3.0;

    /** Hard cap on the minimum points required so the algorithm doesn't demand impossible lengths for huge batches. */
    public int absoluteMaxPointsRequired = 5;

    /** Absolute maximum distance (in pixels) an object can travel between frames. */
    public double maxJumpPixels = 400.0;

    /** Morphological Filter: Maximum allowable ratio in pixel area between two linked objects. */
    public double maxSizeRatio = 3.0;

    /** Max allowed pixel deviation from the expected speed to still be considered a "steady rhythm". */
    public double rhythmAllowedVariance = 5.0;

    /** Minimum percentage of jumps (e.g., 0.70 = 70%) that must strictly follow the expected speed. */
    public double rhythmMinConsistencyRatio = 0.70;

    /** If the median jump is smaller than this, the object isn't actually moving (it's an artifact). */
    public double rhythmStationaryThreshold = 0.5;

    /** Photometric Filter: Maximum allowable ratio in total flux (brightness) between two linked objects. */
    public double maxFluxRatio = 3.0;
// =================================================================
    // 4. SESSION EVALUATOR PARAMETERS
    // =================================================================

    /** Minimum number of frames required in a session to perform meaningful statistical analysis. */
    public int minFramesForAnalysis = 3;

    /** How many standard deviations (Sigma) a frame's star count can drop below the median. */
    public double starCountSigmaDeviation = 2.0;

    /** How many standard deviations a frame's FWHM can spike above the session median. */
    public double fwhmSigmaDeviation = 2.5;

    /** How many standard deviations a frame's eccentricity can spike above the session median. */
    public double eccentricitySigmaDeviation = 3.0;

    /** How many standard deviations the background sky brightness can deviate. */
    public double backgroundSigmaDeviation = 3.0;

    /** Fallback value used if all frames in a session are mathematically identical (Sigma = 0). */
    public double zeroSigmaFallback = 0.001;
}