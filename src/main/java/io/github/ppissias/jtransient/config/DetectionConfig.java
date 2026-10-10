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
 *
 * <p>The fields are intentionally public so applications can tune the detector directly,
 * persist settings to JSON, and clone a baseline config before running auto-tuning or
 * dataset-specific overrides.</p>
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

    /** * Hysteresis threshold used to grow Master Star Map sources after they are seeded at
     * {@link #masterSigmaMultiplier}. A lower value extends star footprints into their faint wings
     * without seeding new noise islands. 0 (default) uses the master sigma itself (no hysteresis).
     */
    public double masterGrowSigmaMultiplier = 0.0;

    // --- Slow-mover candidate detection ---

    /**
     * Searches the maximum stack of quality-filtered frames for elongated shape candidates.
     * These footprints do not confirm motion or form measured tracks.
     */
    public boolean enableSlowMoverDetection = true;

    // --- Slow-mover extraction thresholds ---

    /**
     * Minimum connected raw pixels needed to keep a source in either the maximum or median stack.
     * Higher values reject small noise spots but can miss small sources.
     */
    public int masterSlowMoverMinPixels = 15;

    /**
     * Background-sigma multiplier a pixel must exceed to start a source in either stack.
     * Lower values find fainter sources but may also admit more noise.
     */
    public double masterSlowMoverSigmaMultiplier = 4;

    /**
     * Background-sigma multiplier neighboring pixels must exceed to join a seeded source.
     * Used on both stacks; lower values enlarge footprints and may merge nearby sources.
     */
    public double masterSlowMoverGrowSigmaMultiplier = 3.5;

    /**
     * Minimum length-to-width ratio of a maximum-stack footprint's oriented raw-pixel extents.
     * A ratio near one means similar length and width; raising this rejects less elongated sources.
     */
    public double slowMoverMinAxisRatio = 1.2;
    /**
     * Maximum length-to-width ratio of a maximum-stack footprint's oriented raw-pixel extents.
     * Lower values reject longer, thinner shapes from this candidate filter.
     */
    public double slowMoverMaxAxisRatio = 2.8;
    /**
     * Minimum raw-pixel count divided by the area of the oriented bounding rectangle.
     * Higher values reject sparse or bent shapes; zero disables this filter.
     */
    public double slowMoverMinFillFactor = 0.5;

    // --- Slow-mover support filtering ---

    /**
     * Minimum fraction of a maximum-stack candidate's raw pixels inside the exact median-source mask.
     * Zero does not require median overlap, so even a single-frame artifact may pass this gate.
     */
    public double slowMoverMedianSupportOverlapFraction = 0.00;

    /**
     * Maximum fraction of a maximum-stack candidate's raw pixels inside the exact median-source mask.
     * Candidates above this limit are rejected as likely stationary; one disables the limit.
     */
    public double slowMoverMedianSupportMaxOverlapFraction = 0.80;

    /**
     * Minimum percentage of usable quality-filtered frames with significant localized signal
     * in a compact aperture inside the candidate footprint. Range 0-100; zero disables the gate.
     */
    public double slowMoverMinFrameSupport = 40.0;

    /**
     * Maximum stationary-likelihood percentage allowed for a slow-mover candidate.
     * The score measures how often supported frame positions cluster in one location;
     * it is a heuristic rather than a calibrated probability. Range 0-100; 100 disables the gate.
     */
    public double slowMoverMaxStationaryLikelihood = 60.0;

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

    /** * Additional peak-sigma required above the quality seed threshold before a measured quality star
     * contributes to the bright-star eccentricity metric.
     * This keeps the secondary shape check focused on stars bright enough to reveal tracking slips clearly.
     */
    public double qualityBrightStarPeakSigmaOffset = 30.0;

    /** * Minimum number of bright quality stars required before the bright-star eccentricity metric
     * is considered valid for a frame.
     * Frames with fewer qualifying stars still keep the ordinary eccentricity metric, but the bright-star
     * metric is reported as unavailable and skipped by the dedicated rejection gate.
     */
    public int qualityBrightStarMinStars = 8;

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

    /** * An absolute floor for background deviation. The allowed deviation is also never smaller than the session's
     * median per-frame sky noise, so frames are not rejected on perfectly stable (or sky-equalised) sessions just
     * because the sky background shifted by less than the pixel noise.
     */
    public double minBackgroundDeviationADU = 10.0;

    /** * Absolute bounds for shape quality. Prevents MAD statistics from becoming hyper-sensitive,
     * ensuring minor, sub-pixel tracking shifts are ignored.
     */
    public double minEccentricityEnvelope = 0.10;

    /** * Absolute bounds for the bright-star shape quality envelope.
     * Prevents the dedicated bright-star eccentricity gate from becoming too tight on exceptionally stable runs.
     */
    public double minBrightStarEccentricityEnvelope = 0.10;

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

    /** * Relative speed tolerance used when validating multi-frame streak tracks with timestamps.
     * This is intentionally much looser than point-track velocity matching because one frame can
     * contain several fragmented streak parts and faint frames may miss parts of the true trail.
     * A value of 0.50 accepts about 50% projected-speed variation between sampled frames.
     */
    public double streakTimeConsistencyTolerance = 0.50;

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

    /** * Enables the dedicated frame-quality rejection gate based only on brighter stars.
     * The metric is still measured for telemetry even when this gate is disabled.
     */
    public boolean enableBrightStarEccentricityFilter = true;

    /** * Rejects frames where the brighter reference stars become highly elliptical compared to the session median.
     * This specifically targets tracking slips that affect only the brightest stars strongly enough to leak past
     * the master veto mask.
     */
    public double brightStarEccentricitySigmaDeviation = 3.0;

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
    public boolean enableLocalActivityClusters = false;

    /** * Linkage radius for the broad local activity cluster review pass. */
    public double localActivityClusterRadiusPixels = 10.0;

    /** * Minimum number of unique frames required before a broad local activity cluster is exported. */
    public int localActivityClusterMinFrames = 3;

    // =================================================================
    // 8. VARIABLE-STAR PHOTOMETRY PARAMETERS
    // =================================================================

    // --- Master switch and star selection ---

    /** * Master switch for stationary-star photometry and variable-star detection.
     * Runs after track linking on the frames that passed the session quality filter.
     */
    public boolean enableVariableStarDetection = false;

    /** * Maximum number of master stars measured; 0 measures every usable star. When capped, stars are
     * picked evenly across the field and across the brightness range.
     */
    public int photometryMaxStars = 0;

    /** * Stars whose median per-frame signal-to-noise ratio is below this are still measured and listed,
     * but kept out of the ensemble and the variability scoring.
     */
    public double photometryMinSnr = 10.0;

    /** * Master stars more elongated than this are not measured (blends, galaxies, trailed sources). */
    public double photometryMaxElongation = 1.5;

    // --- Apertures ---

    /** * Photometry aperture radius in units of each frame's median FWHM. */
    public double photometryApertureFwhmFactor = 1.5;

    /** * Inner radius of the sky annulus in units of the frame FWHM.
     * Stars with another master star closer than this radius are not measured.
     */
    public double photometryAnnulusInnerFwhmFactor = 3.0;

    /** * Outer radius of the sky annulus in units of the frame FWHM. */
    public double photometryAnnulusOuterFwhmFactor = 5.0;

    /** * Fraction of the session saturation level above which a star's peak pixel marks the
     * measurement as saturated.
     */
    public double photometrySaturationFraction = 0.85;

    /** * Fits a per-frame linear spatial term (a*x + b*y) in the ensemble, absorbing gradients
     * and differential extinction across the field.
     */
    public boolean photometryFitPlane = true;

    /** * Frames whose star positions scatter by more than this (robust sigma, pixels) around the
     * median registration offset are excluded from photometry (rotation or poor alignment).
     */
    public double photometryMaxRegistrationSpreadPixels = 0.5;

    // --- Photometric readiness (linearity) checks ---

    /** * Check A: minimum number of distinct pixel levels in a frame. Fewer levels suggest 8-bit
     * origin or heavy quantisation, and the session is refused.
     */
    public int linearityMinDistinctLevels = 1024;

    /** * Check A: maximum fraction of sky-annulus pixels sitting at the zero floor. More than this
     * means negative sky noise was clipped (e.g. calibrated float data), which biases faint stars;
     * the session is then limited.
     */
    public double linearityMaxFloorClippedFraction = 0.01;

    /** * Check B: largest allowed change of the concentration index (flux in 1 x FWHM divided by
     * flux in 2.5 x FWHM) from the faint-star reference inside the linear range.
     */
    public double linearityMaxConcentrationDrift = 0.03;

    /** * Check B: smallest linear magnitude range a frame must have to be used. */
    public double linearityMinRangeMag = 2.0;

    /** * Check B: minimum number of measurable stars inside a frame's linear range. */
    public int linearityMinStars = 50;

    /** * Check D: largest per-frame slope of the ensemble residual against magnitude (mag per mag). */
    public double linearityMaxFrameSlope = 0.01;

    /** * Check D: minimum spread (5th to 95th percentile) of frame zero points, in mag, for the
     * slope-tracking test to be conclusive.
     */
    public double linearityMinZeroPointRangeMag = 0.05;

    /** * Check D: the session fails when the per-frame slopes correlate with the zero point or sky
     * level at least this strongly (and the implied slope change is significant).
     */
    public double linearityMaxSlopeTrackingCorrelation = 0.7;

    /** * Checks B and D: share of frames that may fail before the whole session is refused. */
    public double linearityMaxFailingFrameFraction = 0.2;

    // --- Variability scoring and gates ---

    /** * Minimum number of usable measurements a star needs to be scored. */
    public int variableMinFrames = 20;

    /** * Minimum time span of the session, in minutes, for a high-confidence result.
     * Ignored when frames have no timestamps.
     */
    public double variableMinSpanMinutes = 30.0;

    /** * Number of stars of similar magnitude used to build each star's expected noise and robust z-scores. */
    public int variableNoiseModelNeighbors = 50;

    /** * Robust z-score that both the scatter score and the Stetson J score must reach for a star to become a candidate. */
    public double variableScoreSigma = 5.0;

    /** * Smallest robust amplitude (5th to 95th percentile), in mag, for a high-confidence result. */
    public double variableMinAmplitudeMag = 0.05;

    /** * Smallest robust amplitude, in mag, when the readiness verdict is Limited. */
    public double variableLimitedMinAmplitudeMag = 0.1;

    /** * The robust amplitude must also exceed this multiple of the expected noise at the star's magnitude. */
    public double variableAmplitudeNoiseFactor = 4.0;

    /** * Minimum run of consecutive measurements deviating on the same side by more than 1.5 x expected noise. */
    public int variableMinPersistenceFrames = 5;

    /** * Minimum correlation between consecutive measurement pairs (split-half test against white noise). */
    public double variableMinSplitHalfCorrelation = 0.5;

    /** * Largest allowed difference between the amplitudes measured with 1.0 x and 2.0 x FWHM apertures,
     * as a fraction of the main-aperture amplitude.
     */
    public double variableMaxApertureAmplitudeDifference = 0.3;

    /** * Largest allowed absolute correlation of a light curve with frame zero point, FWHM, local sky
     * or registration offsets.
     */
    public double variableMaxSystematicsCorrelation = 0.6;

    /** * A correlated systematic only explains a candidate when the candidate's amplitude is at most this
     * multiple of the largest response (99th percentile) that constant stars of similar brightness show
     * to the same systematic. Larger changes cannot come from that systematic, so the gate passes.
     */
    public double variableSystematicsResponseFactor = 2.0;

    /** * Radius, in pixels, within which nearby constant stars are compared for a shared pattern. */
    public double variableLocalRadiusPixels = 100.0;

    /** * Largest allowed median absolute correlation with nearby constant stars. */
    public double variableMaxLocalCorrelation = 0.3;

    /**
     * Creates a configuration initialized with the library defaults.
     */
    public DetectionConfig() {
    }

    /**
     * Returns a copy of this configuration.
     *
     * @return cloned configuration carrying the same primitive field values
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
