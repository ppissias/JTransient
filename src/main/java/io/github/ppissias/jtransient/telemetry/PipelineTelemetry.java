/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 * author: Petros Pissias <petrospis at gmail.com>
 *
 */
package io.github.ppissias.jtransient.telemetry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Top-level telemetry bundle describing what happened during one pipeline run.
 */
public class PipelineTelemetry {

    // --- PHASE 1: Extraction ---
    /** Number of frames submitted to the pipeline before quality rejection. */
    public int totalFramesLoaded = 0;
    /** Number of raw extracted objects measured before frame rejection and stationary-star vetoing. */
    public int totalRawObjectsExtracted = 0;

    /**
     * Per-frame extraction summary captured immediately after source extraction.
     */
    public static class FrameExtractionStat {
        public int frameIndex;
        public String filename;
        public int objectCount;
        public double bgMedian;
        public double bgSigma;
        public double seedThreshold;
        public double growThreshold;
    }
    /** Per-frame extraction statistics in chronological order. */
    public List<FrameExtractionStat> frameExtractionStats = new ArrayList<>();

    // --- PHASE 2 & 3: Quality & Filtering ---
    /** Number of frames rejected by the frame-quality stage. */
    public int totalFramesRejected = 0;
    /** Number of frames retained after quality filtering. */
    public int totalFramesKept = 0;

    /**
     * Session-wide quality thresholds derived from the robust frame baseline.
     */
    public static class FrameQualityThresholds {
        public boolean available;
        public double minAllowedStarCount = Double.NaN;
        public double maxAllowedFwhm = Double.NaN;
        public double maxAllowedEccentricity = Double.NaN;
        public double maxAllowedBrightStarEccentricity = Double.NaN;
        public double backgroundMedianBaseline = Double.NaN;
        public double maxAllowedBackgroundDeviation = Double.NaN;
        public double minAllowedBackgroundMedian = Double.NaN;
        public double maxAllowedBackgroundMedian = Double.NaN;
    }
    /** Session-level thresholds derived from the quality-control baseline. */
    public FrameQualityThresholds qualityThresholds = new FrameQualityThresholds();

    /**
     * Per-frame quality summary captured after frame-quality analysis and session rejection.
     */
    public static class FrameQualityStat {
        public int frameIndex;
        public String filename;
        public double backgroundMedian;
        public double backgroundNoise;
        public double medianFWHM;
        public double medianEccentricity;
        public double brightStarMedianEccentricity = Double.NaN;
        public int starCount;
        public int usableShapeStarCount;
        public int brightStarShapeStarCount;
        public int fwhmStarCount;
        public boolean rejected;
        public String rejectionReason;
    }
    /** Per-frame quality statistics in chronological order. */
    public List<FrameQualityStat> frameQualityStats = new ArrayList<>();

    /**
     * Per-frame rejection record emitted by the quality-control stage.
     */
    public static class FrameRejectionStat {
        public int frameIndex;
        public String filename;
        public String reason;
        public double medianEccentricity;
        public double brightStarMedianEccentricity = Double.NaN;
        public int brightStarShapeStarCount;
    }
    /** Rejection records for frames removed by the quality-control stage. */
    public List<FrameRejectionStat> rejectedFrames = new ArrayList<>();

    // --- PHASE 4: Tracking ---
    /** Number of stationary master-stack stars extracted for the veto mask. */
    public int totalMasterStarsIdentified = 0;
    /** Number of returned track-like detections, including suspected streak groupings. */
    public int totalTracksFound = 0;
    /** Number of rescued standalone anomalies returned outside the track list. */
    public int totalAnomaliesFound = 0;
    /** Number of returned tracks flagged as suspected same-frame streak groupings. */
    public int totalSuspectedStreakTracksFound = 0;

    /**
     * Detailed diagnostics emitted by the track-linking stages.
     */
    public TrackerTelemetry trackerTelemetry;

    /**
     * Diagnostic summary for morphology candidates from the slow-mover branch.
     */
    public SlowMoverTelemetry slowMoverTelemetry;

    /** Mutable pipeline-facing view of slow-mover morphology and median-mask filtering. */
    public static class SlowMoverTelemetry {
        /** Maximum-stack components extracted before candidate filtering. */
        public int rawCandidatesExtracted;
        /** Components rejected for having too few connected raw pixels. */
        public int rejectedBelowMinPixels;
        /** Components below the geometric axis-ratio window. */
        public int rejectedBelowMinAxisRatio;
        /** Components above the geometric axis-ratio window. */
        public int rejectedAboveMaxAxisRatio;
        /** Components below the optional oriented-box fill-factor floor. */
        public int rejectedLowFillFactor;
        /** Shape-qualified components evaluated against the exact median mask. */
        public int evaluatedAgainstMedianMask;
        /** Components below the optional median-overlap floor. */
        public int rejectedLowMedianSupport;
        /** Components above the stationary-source median-overlap ceiling. */
        public int rejectedHighMedianSupport;
        /** Components with measurable frame evidence. */
        public int evaluatedAgainstFrames;
        /** Components for which no usable frame evidence was available. */
        public int frameEvidenceUnavailable;
        /** Components below the optional frame-support percentage floor. */
        public int rejectedLowFrameSupport;
        /** Components above the optional stationary-likelihood percentage ceiling. */
        public int rejectedHighStationaryLikelihood;
        /** Components retained as morphological slow-mover candidates. */
        public int candidatesDetected;
        /** Effective minimum major/minor footprint extent ratio. */
        public double minAxisRatioThreshold;
        /** Effective maximum major/minor footprint extent ratio. */
        public double maxAxisRatioThreshold;
        /** Effective minimum fill factor; zero disables the veto. */
        public double minFillFactorThreshold;
        /** Effective lower fraction of candidate raw pixels inside the median mask. */
        public double medianSupportOverlapThreshold;
        /** Effective upper fraction of candidate raw pixels inside the median mask. */
        public double medianSupportMaxOverlapThreshold;
        /** Effective minimum frame-support percentage. */
        public double minFrameSupportThreshold;
        /** Effective maximum stationary-likelihood percentage. */
        public double maxStationaryLikelihoodThreshold;
        /** Mean geometric axis ratio across accepted candidates. */
        public double avgCandidateAxisRatio;
        /** Minimum accepted geometric axis ratio, or zero for no candidates. */
        public double minCandidateAxisRatio;
        /** Maximum accepted geometric axis ratio, or zero for no candidates. */
        public double maxCandidateAxisRatio;
        /** Mean overlap across all mask-evaluated components, including vetoed ones. */
        public double avgMedianMaskOverlap;
        /** Mean footprint-derived displacement in pixels among accepted candidates. */
        public double avgEstimatedMotionPixels;
        /** Mean footprint-derived displacement in source diameters among accepted candidates. */
        public double avgEstimatedMotionDiameters;
        /** Axis ratios of size-qualified maximum-stack components. */
        public final List<Double> candidateAxisRatios = new ArrayList<>();
        /** Moment elongations of the same size-qualified components. */
        public final List<Double> candidateMomentElongations = new ArrayList<>();
        /** Fill factors of the same size-qualified components. */
        public final List<Double> candidateFillFactors = new ArrayList<>();
        /** Overlaps of components that reached median-mask evaluation. */
        public final List<Double> candidateMedianMaskOverlaps = new ArrayList<>();
        /** Overlaps of accepted candidates, ordered like the exported result list. */
        public final List<Double> candidateMedianSupportOverlaps = new ArrayList<>();
        /** Frame-support percentages of accepted candidates, in result order. */
        public final List<Double> candidateFrameSupportPercentages = new ArrayList<>();
        /** Stationary-likelihood percentages of accepted candidates, in result order. */
        public final List<Double> candidateStationaryLikelihoodPercentages = new ArrayList<>();
        /** Whether each accepted candidate has a frame-support measurement. */
        public final List<Boolean> candidateFrameSupportAvailable = new ArrayList<>();
        /** Whether each accepted candidate has a stationary-likelihood measurement. */
        public final List<Boolean> candidateStationaryLikelihoodAvailable = new ArrayList<>();
    }

    // --- Variable-Star Photometry ---
    /** Photometry diagnostics for the whole session and every frame; null when photometry is disabled. */
    public PhotometryTelemetry photometryTelemetry;

    /**
     * Session-level photometry diagnostics: readiness checks, star selection, frame usage,
     * measurement flags, the noise model and candidate counts. Per-frame records are in {@link #frames}.
     */
    public static class PhotometryTelemetry {
        /** Readiness verdict name (READY, LIMITED, NOT_READY, NOT_RUN). */
        public String verdict = "NOT_RUN";
        /** Plain-language readiness findings. */
        public final List<String> readinessMessages = new ArrayList<>();

        /** Check A status and measurements. */
        public String quantisationCheck = "NOT_RUN";
        public int distinctPixelLevels;
        public double medianFloorClippedFraction = Double.NaN;

        /** Check B status and measurements. */
        public String shapeLinearityCheck = "NOT_RUN";
        public double medianLinearLimitMag = Double.NaN;
        public double medianLinearRangeMag = Double.NaN;
        public int framesFailingShapeLinearity;

        /** Check D status and measurements. */
        public String responseCheck = "NOT_RUN";
        public double zeroPointRangeMag = Double.NaN;
        public double slopeZeroPointCorrelation = Double.NaN;
        public double slopeSkyCorrelation = Double.NaN;
        public int framesFailingResponse;

        /** Session median FWHM used for star selection, in pixels. */
        public double sessionFwhm = Double.NaN;
        /** Estimated saturation level in the shifted non-negative pixel domain (0..65535). */
        public double saturationLevel = Double.NaN;

        /** Star selection counters. */
        public int masterStarsConsidered;
        public int starsSelected;
        public int starsRejectedStreak;
        public int starsRejectedElongated;
        public int starsRejectedEdgeOrVoid;
        public int starsRejectedCrowded;
        public int starsRejectedSaturated;
        public int starsRejectedByCap;
        public int starsExcludedMostlyNonlinear;
        public int starsExcludedLowSnr;

        /** Frame usage counters. */
        public int framesAnalyzed;
        public int framesUsed;
        public int framesExcludedRegistration;
        public int framesExcludedShapeLinearity;
        public int framesExcludedResponse;
        public int framesExcludedTooFewStars;

        /** Measurement flag counters over all stars and frames (one measurement can carry several flags). */
        public long measurementsTotal;
        public long measurementsSaturated;
        public long measurementsNonlinear;
        public long measurementsEdgeOrVoid;
        public long measurementsCrossing;
        public long measurementsOutlier;
        public long measurementsBadFlux;

        /** Variability counters. */
        public int starsScored;
        public int starsNotScored;
        public int candidates;
        public int highConfidence;
        public int possible;
        public int rejectedCandidates;
        /** Number of candidates that failed each gate, by gate name. */
        public final Map<String, Integer> gateFailureCounts = new LinkedHashMap<>();

        /** Expected scatter against magnitude (the session noise model), sorted by magnitude. */
        public final List<PhotometryNoisePoint> noiseModel = new ArrayList<>();

        /** Per-frame diagnostics in chronological order; light-curve arrays are aligned with this list. */
        public final List<PhotometryFrameStat> frames = new ArrayList<>();

        /** Wall-clock runtime of the photometry stage in milliseconds. */
        public long processingTimeMs;
    }

    /**
     * One point of the session noise model.
     */
    public static class PhotometryNoisePoint {
        /** Mean instrumental magnitude of the star. */
        public double mag;
        /** Measured scatter of the star (standard deviation of its usable residuals). */
        public double scatter;
        /** Expected scatter of a constant star at this magnitude. */
        public double expectedScatter;
        /** Tier name of the star, so candidates can be highlighted. */
        public String tier;
    }

    /**
     * One binned point of a frame's concentration-index profile (check B).
     */
    public static class PhotometryProfilePoint {
        /** Median instrumental magnitude of the bin. */
        public double mag;
        /** Median concentration index of the bin. */
        public double concentration;
        /** Stars in the bin. */
        public int count;
    }

    /**
     * Photometry diagnostics of a single frame.
     */
    public static class PhotometryFrameStat {
        public int frameIndex;
        public String filename;
        /** Capture timestamp in milliseconds, or -1. */
        public long timestamp = -1;
        /** Mid-exposure Julian date, or NaN without timestamps. */
        public double julianDate = Double.NaN;

        /** FWHM used for the apertures, in pixels. */
        public double fwhm = Double.NaN;
        /** Main photometry aperture radius, in pixels. */
        public double apertureRadius = Double.NaN;
        /** Median local sky level of the measured stars (shifted pixel domain). */
        public double skyMedian = Double.NaN;

        /** Median residual registration offset of bright stars, in pixels. */
        public double registrationOffsetX = Double.NaN;
        public double registrationOffsetY = Double.NaN;
        /** Robust spread of the bright-star offsets around the median, in pixels. */
        public double registrationSpread = Double.NaN;

        /** Share of sky-annulus pixels at the zero floor (check A). */
        public double floorClippedFraction = Double.NaN;

        /** Check B: faint-star reference concentration index, linear limit and range. */
        public double concentrationReference = Double.NaN;
        public double linearLimitMag = Double.NaN;
        public double linearRangeMag = Double.NaN;
        public int linearStars;
        public String shapeLinearityStatus = "NOT_RUN";
        /** Binned concentration index against magnitude, bright to faint. */
        public final List<PhotometryProfilePoint> concentrationProfile = new ArrayList<>();

        /** Ensemble solution for the frame: zero point Z(j) and plane terms (mag per normalized unit). */
        public double zeroPoint = Double.NaN;
        public double planeX = Double.NaN;
        public double planeY = Double.NaN;
        /** Check D: residual slope against magnitude and its standard error. */
        public double responseSlope = Double.NaN;
        public double responseSlopeError = Double.NaN;
        public String responseStatus = "NOT_RUN";

        /** Measurement counters for this frame. */
        public int starsMeasured;
        public int starsInEnsemble;
        public int saturated;
        public int nonlinear;
        public int edgeOrVoid;
        public int crossing;
        public int outliers;
        public int badFlux;

        /** Whether the frame was used for the ensemble and scoring. */
        public boolean used = true;
        /** Why the frame was excluded, or null. */
        public String exclusionReason;
    }

    // --- Processing ---
    /** End-to-end pipeline wall-clock runtime in milliseconds. */
    public long processingTimeMs = 0;

    /**
     * Generates a human-readable text summary of the recorded telemetry.
     *
     * @return multiline report suitable for logs, diagnostics, or HTML embedding
     */
    public String generateReport() {
        StringBuilder sb = new StringBuilder();
        sb.append("==================================================\n");
        sb.append("          JTRANSIENT DETECTION REPORT             \n");
        sb.append("==================================================\n\n");

        sb.append("--- PIPELINE SUMMARY ---\n");
        sb.append(String.format("Total Processing Time : %.2f seconds\n", processingTimeMs / 1000.0));
        sb.append(String.format("Total Frames Processed: %d\n", totalFramesLoaded));
        sb.append(String.format("Frames Kept / Rejected: %d / %d\n", totalFramesKept, totalFramesRejected));
        sb.append(String.format("Total Raw Objects     : %d\n", totalRawObjectsExtracted));

        sb.append(String.format("Master Stars          : %d\n", totalMasterStarsIdentified));

        sb.append(String.format("Tracks Returned       : %d\n", totalTracksFound));
        sb.append(String.format("Anomalies Found       : %d\n", totalAnomaliesFound));
        sb.append(String.format("Suspected Streak Tracks: %d\n\n", totalSuspectedStreakTracksFound));

        if (!rejectedFrames.isEmpty()) {
            sb.append("--- QUALITY CONTROL: REJECTED FRAMES ---\n");
            for (FrameRejectionStat rej : rejectedFrames) {
                sb.append(String.format(
                        "  Frame %03d (%s) -> %s | Ecc: %s | BrightEcc: %s | BrightStars: %d\n",
                        rej.frameIndex + 1,
                        rej.filename,
                        rej.reason,
                        formatMetric(rej.medianEccentricity),
                        formatMetric(rej.brightStarMedianEccentricity),
                        rej.brightStarShapeStarCount
                ));
            }
            sb.append("\n");
        }

        if (qualityThresholds.available) {
            sb.append("--- QUALITY CONTROL: SESSION THRESHOLDS ---\n");
            sb.append(String.format(
                    "  Stars >= %.2f | FWHM <= %s | Ecc <= %s | BrightEcc <= %s | Bg Median in [%s, %s] (center %s, dev %s)\n\n",
                    qualityThresholds.minAllowedStarCount,
                    formatMetric(qualityThresholds.maxAllowedFwhm),
                    formatMetric(qualityThresholds.maxAllowedEccentricity),
                    formatMetric(qualityThresholds.maxAllowedBrightStarEccentricity),
                    formatMetric(qualityThresholds.minAllowedBackgroundMedian),
                    formatMetric(qualityThresholds.maxAllowedBackgroundMedian),
                    formatMetric(qualityThresholds.backgroundMedianBaseline),
                    formatMetric(qualityThresholds.maxAllowedBackgroundDeviation)
            ));
        }

        if (!frameQualityStats.isEmpty()) {
            sb.append("--- FRAME QUALITY STATISTICS ---\n");
            for (FrameQualityStat stat : frameQualityStats) {
                sb.append(String.format(
                        "  Frame %03d (%s) -> Noise: %.2f, Bg: %.2f, Stars: %d, ShapeStars: %d, BrightShapeStars: %d, FwhmStars: %d, FWHM: %s, Ecc: %s, BrightEcc: %s%s\n",
                        stat.frameIndex + 1,
                        stat.filename,
                        stat.backgroundNoise,
                        stat.backgroundMedian,
                        stat.starCount,
                        stat.usableShapeStarCount,
                        stat.brightStarShapeStarCount,
                        stat.fwhmStarCount,
                        formatMetric(stat.medianFWHM),
                        formatMetric(stat.medianEccentricity),
                        formatMetric(stat.brightStarMedianEccentricity),
                        stat.rejected ? " [REJECTED: " + stat.rejectionReason + "]" : ""
                ));
            }
            sb.append("\n");
        }

        if (photometryTelemetry != null) {
            appendPhotometryReport(sb, photometryTelemetry);
        }

        sb.append("--- EXTRACTION STATISTICS ---\n");
        for (FrameExtractionStat stat : frameExtractionStats) {
            sb.append(String.format("  Frame %03d (%s) -> %d objects extracted | Median: %.2f, Sigma: %.2f, Seed: %.2f, Grow: %.2f\n",
                    stat.frameIndex + 1, stat.filename, stat.objectCount, 
                    stat.bgMedian, stat.bgSigma, stat.seedThreshold, stat.growThreshold));
        }
        sb.append("\n");
        sb.append("==================================================\n");

        return sb.toString();
    }

    /**
     * Appends the variable-star photometry section of the text report.
     */
    private static void appendPhotometryReport(StringBuilder sb, PhotometryTelemetry p) {
        sb.append("--- VARIABLE-STAR PHOTOMETRY ---\n");
        sb.append(String.format("Readiness Verdict     : %s\n", p.verdict));
        sb.append(String.format("  A Quantisation      : %s | Levels: %d | Floor-clipped sky: %s\n",
                p.quantisationCheck, p.distinctPixelLevels, formatPercent(p.medianFloorClippedFraction)));
        sb.append(String.format("  B Star Shape        : %s | Linear limit: %s mag | Linear range: %s mag | Failing frames: %d\n",
                p.shapeLinearityCheck, formatMetric(p.medianLinearLimitMag), formatMetric(p.medianLinearRangeMag),
                p.framesFailingShapeLinearity));
        sb.append(String.format("  D Response          : %s | Zero-point range: %s mag | r(slope,Z): %s | r(slope,sky): %s | Failing frames: %d\n",
                p.responseCheck, formatMetric(p.zeroPointRangeMag), formatMetric(p.slopeZeroPointCorrelation),
                formatMetric(p.slopeSkyCorrelation), p.framesFailingResponse));
        for (String message : p.readinessMessages) {
            sb.append("  * ").append(message).append("\n");
        }
        sb.append(String.format("Session FWHM / Saturation: %s px / %s\n",
                formatMetric(p.sessionFwhm), formatMetric(p.saturationLevel)));
        sb.append(String.format("Stars: %d master, %d selected | Rejected: streak %d, elongated %d, edge/void %d, crowded %d, saturated %d, cap %d | Mostly non-linear: %d, low SNR: %d\n",
                p.masterStarsConsidered, p.starsSelected, p.starsRejectedStreak, p.starsRejectedElongated,
                p.starsRejectedEdgeOrVoid, p.starsRejectedCrowded, p.starsRejectedSaturated, p.starsRejectedByCap, p.starsExcludedMostlyNonlinear, p.starsExcludedLowSnr));
        sb.append(String.format("Frames: %d analysed, %d used | Excluded: registration %d, star shape %d, response %d, too few stars %d\n",
                p.framesAnalyzed, p.framesUsed, p.framesExcludedRegistration, p.framesExcludedShapeLinearity,
                p.framesExcludedResponse, p.framesExcludedTooFewStars));
        sb.append(String.format("Measurements: %d | Saturated %d, non-linear %d, edge/void %d, crossing %d, outlier %d, bad flux %d\n",
                p.measurementsTotal, p.measurementsSaturated, p.measurementsNonlinear, p.measurementsEdgeOrVoid,
                p.measurementsCrossing, p.measurementsOutlier, p.measurementsBadFlux));
        sb.append(String.format("Variability: %d scored, %d not scored | Candidates: %d (high confidence %d, possible %d, rejected %d)\n",
                p.starsScored, p.starsNotScored, p.candidates, p.highConfidence, p.possible, p.rejectedCandidates));
        if (!p.gateFailureCounts.isEmpty()) {
            sb.append("Gate failures: ").append(p.gateFailureCounts).append("\n");
        }
        for (PhotometryFrameStat f : p.frames) {
            sb.append(String.format(
                    "  Frame %03d (%s) -> FWHM %s, Ap %s, Sky %s, Offset (%s, %s) spread %s, Floor %s, B %s limit %s range %s, Z %s, D %s slope %s, Stars %d/%d, Sat %d, NonLin %d, Cross %d%s\n",
                    f.frameIndex + 1, f.filename, formatMetric(f.fwhm), formatMetric(f.apertureRadius), formatMetric(f.skyMedian),
                    formatMetric(f.registrationOffsetX), formatMetric(f.registrationOffsetY), formatMetric(f.registrationSpread),
                    formatPercent(f.floorClippedFraction), f.shapeLinearityStatus, formatMetric(f.linearLimitMag),
                    formatMetric(f.linearRangeMag), formatMag(f.zeroPoint), f.responseStatus, formatMag(f.responseSlope),
                    f.starsInEnsemble, f.starsMeasured, f.saturated, f.nonlinear, f.crossing,
                    f.used ? "" : " [EXCLUDED: " + f.exclusionReason + "]"));
        }
        sb.append(String.format("Photometry Time: %.2f seconds\n\n", p.processingTimeMs / 1000.0));
    }

    private static String formatMag(double value) {
        return Double.isFinite(value) ? String.format("%.4f", value) : "n/a";
    }

    private static String formatPercent(double fraction) {
        return Double.isFinite(fraction) ? String.format("%.2f%%", 100.0 * fraction) : "n/a";
    }

    /**
     * Formats optional floating-point metrics for reports.
     */
    private static String formatMetric(double value) {
        return Double.isFinite(value) ? String.format("%.2f", value) : "n/a";
    }
}
