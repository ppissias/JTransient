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

import com.google.gson.annotations.SerializedName;

/**
 * Master configuration object for the JTransient detection pipeline.
 * This class holds all tuning parameters for extraction, quality control, and track linking.
 * It is designed to be easily serialized to/from JSON.
 */
public class DetectionConfig implements Cloneable {

    // =================================================================
    // 1. EXTRACTION, BORDER, AND STREAK PARAMETERS
    // =================================================================

    // --- Background model ---

    /** * Number of passes used in the iterative histogram calculation to mathematically chop off
     * bright stars so they don't corrupt the background sky noise calculation.
     */
    public int bgClippingIterations = 3;

    /** * Threshold (in standard deviations) used to chop off pixels during the iterative background calculation.
     */
    public double bgClippingFactor = 3.0;

    // --- Primary detection thresholds ---

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

    // --- Border and void rejection ---

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

    // --- Streak classification ---

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

    /** * Enable the Binary-Star shape veto for streaks.
     * When disabled, the engine will not reject short, wide single-frame streaks that look like merged double stars.
     */
    public boolean enableBinaryStarLikeStreakShapeVeto = true;

    // =================================================================
    // 2. MASTER-STACK AND SLOW-MOVER PARAMETERS
    // =================================================================

    // --- Master star map extraction ---

    /** * The baseline requirement for extracting stars to build the Master Star Map. 
     * Typically lower than detectionSigmaMultiplier to ensure faint halos are masked.
     */
    public double masterSigmaMultiplier = 2.25;

    /** * Minimum number of pixels a source must have to be considered a star in the Master Star Map.
     * Lower values allow capturing faint background stars to better protect against noise.
     */
    public int masterMinDetectionPixels = 3;

    // --- Slow-mover stack generation ---

    /** * Master switch to enable the generation and analysis of the specialized Slow Mover stack.
     * Set to true to actively hunt for ultra-slow moving objects like distant asteroids.
     */
    public boolean enableSlowMoverDetection = true;

    /** * The fraction of the sorted pixel values around the median to use when generating a Slow Mover Master Stack.
     * By taking the maximum of this middle band, the stack captures ultra-slow moving objects without including
     * high-energy single-frame flashes (like fast streaks or cosmic rays).
     */
    public double slowMoverStackMiddleFraction = 0.75;

    // --- Slow-mover extraction thresholds ---

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

    // --- Slow-mover shape and support filtering ---

    /** * Enables the slow-mover shape veto stage.
     * When disabled, the branch skips the shared irregular and binary slow-mover shape checks.
     */
    public boolean enableSlowMoverShapeFiltering = true;

    /** * Enables the extra slow-mover-only shape filter after the shared irregular/binary checks.
     * Disable this to keep the shared slow-mover shape filters while bypassing the targeted compact-shape veto.
     */
    public boolean enableSlowMoverSpecificShapeFiltering = true;

    /** * Minimum fraction of a slow-mover footprint that must overlap the median-stack artifact mask.
     * Higher values demand stronger support from the median stack before a candidate is kept.
     */
    public double slowMoverMedianSupportOverlapFraction = 0.00;

    /** * Maximum fraction of a slow-mover footprint that may overlap the median-stack artifact mask.
     * Lower values reject candidates that look too similar to stationary median-stack artifacts.
     */
    public double slowMoverMedianSupportMaxOverlapFraction = 0.65;

    /** * Enables the centered residual-support veto in the slow-mover branch.
     * When enabled, candidates must retain positive signal near their centroid in slowMoverStack - medianStack.
     */
    public boolean enableSlowMoverResidualCoreFiltering = true;

    /** * Radius, in pixels, of the centroid-centered footprint core evaluated against slowMoverStack - medianStack.
     * Larger values make the residual-support check more tolerant of broader compact slow movers.
     */
    public double slowMoverResidualCoreRadiusPixels = 2.0;

    /** * Minimum fraction of core footprint pixels that must remain positive in slowMoverStack - medianStack.
     * Lower values relax the residual-core veto; higher values demand stronger centered excess.
     */
    public double slowMoverResidualCoreMinPositiveFraction = 0.50;

    // =================================================================
    // 3. FRAME QUALITY ANALYSIS PARAMETERS
    // =================================================================

    // --- Quality extraction ---

    /** * Sigma multiplier used to extract only strong, undeniable stars specifically for frame quality evaluation,
     * bypassing the standard extraction parameters.
     */
    public double qualitySigmaMultiplier = 5.0;

    /** * Secondary hysteresis threshold used only while expanding quality-analysis stars.
     * Keeps frame scoring independent from the main detection grow threshold.
     */
    public double qualityGrowSigmaMultiplier = 3.0;

    /** * Minimum number of contiguous pixels a source must have to be evaluated as a valid reference star
     * for frame quality.
     */
    public int qualityMinDetectionPixels = 5;

    // --- Quality measurements and fallback ---

    /** * Trailed stars (due to wind or mount errors) artificially inflate FWHM (focus) measurements.
     * Only stars with an elongation below this value are used to calculate the frame's median focus.
     */
    @SerializedName(value = "qualityMaxElongationForFwhm", alternate = {"maxElongationForFwhm"})
    public double qualityMaxElongationForFwhm = 1.5;

    /** * If a frame is a total washout (e.g., thick clouds, completely black) and yields zero reference stars,
     * the engine assigns this terrible score so it is guaranteed to be rejected by the session evaluator.
     */
    public double errorFallbackValue = 999.0;

    // --- Absolute minimum tolerances ---

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
    // 4. TRACKING AND STATIONARY-MASK PARAMETERS
    // =================================================================

    // --- Stationary-star veto and overlap ---

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

    /** * The cosmic speed limit. When looking for the next point in a track, any transient located
     * further than this distance is ignored.
     */
    public double maxJumpPixels = 400.0;

    /** * Once a baseline vector is established (Points 1 and 2), Point 3 must fall within this many pixels
     * of the infinitely projected mathematical trajectory line.
     */
    public double predictionTolerance = 3.0;

    /** * For fast streaks, this ensures the streak's physical rotation angle matches the
     * trajectory vector it is traveling on.
     */
    public double angleToleranceDegrees = 2;

    /** * When valid timestamps are available, this defines the maximum allowed variance in velocity (speed).
     * A value of 0.10 means a 10% change in velocity between jumps is acceptable.
     * Tracks linked using time bypass the maxJumpPixels constraint.
     */
    public double timeBasedVelocityTolerance = 0.10;

    /** * Strict Exposure Kinematics: If an object appears as a round point source in a long exposure,
     * it physically cannot be moving fast. This bounds the time-based linker using the source footprint
     * and the exposure time when valid timestamps are available.
     * NOTE: Turn this OFF if tracking tumbling/flashing LEO satellites that only glint for a fraction of the exposure.
     */
    public boolean strictExposureKinematics = true;

    /** * Controls whether the frame-agnostic geometric point-track linker runs when timestamps are available.
     * Keep this enabled unless the time-based linker is preferred exclusively.
     * If timestamps are missing, the engine still forces geometric linking because it is the only point-track fallback.
     */
    public boolean enableGeometricTrackLinking = false;

    /** * Determines how many points are needed to confirm a track by dividing the total number of frames
     * by this ratio (e.g., 20 frames / 3.0 = ~7 points required).
     */
    public double trackMinFrameRatio = 3.0;

    /** * A hard ceiling on the required track length so the algorithm doesn't demand mathematically
     * impossible track lengths for massive frame batches (e.g., requiring 166 points in a 500 frame session).
     */
    public int absoluteMaxPointsRequired = 5;

    // --- Morphology consistency ---

    /** * Morphological Filter: FWHM represents the optical focus/spread. Real targets share similar optical blurring.
     * A value of 2.0 means the FWHM cannot more than double between frames. 0 to disable. */
    public double maxFwhmRatio = 2.0;

    /** * Morphological Filter: Surface Brightness (Flux / Area) identifies the density of the light.
     * Prevents linking a concentrated cosmic ray to a diffuse noise smudge. 0 to disable. */
    public double maxSurfaceBrightnessRatio = 2.0;

    // --- Speed rhythm checks ---

    /** * Kinematic Speed Check: If the median jump of a track is smaller than this, it is dismissed
     * as stationary noise that accidentally bypassed the star map.
     */
    public double rhythmStationaryThreshold = 0.5;

    /** * Kinematic Speed Check: Max allowed pixel deviation from the expected median speed to still
     * be considered part of a "steady rhythm".
     */
    public double rhythmAllowedVariance = 8.0;

    /** * Kinematic Speed Check: Minimum percentage of jumps (e.g., 0.70 = 70%) that must
     * strictly match the median track speed within the allowed variance.
     */
    public double rhythmMinConsistencyRatio = 0.70;

    // =================================================================
    // 5. SESSION REJECTION PARAMETERS
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
    // 6. SINGLE-FRAME ANOMALY RESCUE PARAMETERS
    // =================================================================

    /** * Enable the rescue of single-frame, ultra-bright point sources that failed to form a multi-frame track. */
    public boolean enableAnomalyRescue = true;

    /** * The minimum physical size a single-frame point must have to be rescued.
     * Prevents single hot-pixels or cosmic rays from being flagged. */
    public int anomalyMinPixels = 15;

    /** * The minimum Peak Signal-to-Noise ratio (Sigma) a single-frame point must have to be rescued.
     * e.g., 50.0 means the brightest pixel in the object is 50x brighter than the background noise. */
    public double anomalyMinPeakSigma = 8;

    /** * The minimum footprint size required for the integrated-sigma anomaly path.
     * Keeps small streak fragments from being rescued just because their total energy is high enough.
     */
    public int anomalyMinIntegratedPixels = 25;

    /** * The minimum integrated signal-to-noise ratio required to rescue a broader single-frame anomaly.
     * This complements peak sigma so faint but larger flashes can still be kept.
     */
    public double anomalyMinIntegratedSigma = 12;

    /** * Safety floor for the diffuse anomaly-rescue path. Even broad anomalies must still show
     * at least some local prominence to avoid rescuing low-contrast mush.
     */
    public double anomalyMinPeakSigmaFloor = 3;

    /** * Legacy compatibility field retained for existing configs.
     * Same-frame suspected streak grouping now evaluates all rescued anomalies from one frame for
     * collinearity, so this value no longer gates line formation.
     */
    public double anomalySuspectedStreakMinElongation = 3.5;

    /** * Maximum perpendicular centroid distance allowed when grouping rescued same-frame anomalies
     * into a suspected streak line. This is intentionally separate from the multi-frame
     * point-track prediction tolerance so faint streak fragments can be grouped more permissively.
     */
    public double suspectedStreakLineTolerance = 6.0;

    // =================================================================
    // 7. RESIDUAL TRANSIENT ANALYSIS PARAMETERS
    // =================================================================

    /** * Master switch for post-processing leftover non-streak point detections after normal tracking. */
    public boolean enableResidualTransientAnalysis = true;

    /** * Enables the object-like local rescue candidate pass over leftover detections. */
    public boolean enableLocalRescueCandidates = true;

    /** * Enables the broader spatial activity-cluster pass after local rescue candidates are removed. */
    public boolean enableLocalActivityClusters = true;

    /** * Linkage radius for the broad local activity cluster review pass. */
    public double localActivityClusterRadiusPixels = 10.0;

    /** * Minimum number of unique frames required before a broad local activity cluster is exported. */
    public int localActivityClusterMinFrames = 3;


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
