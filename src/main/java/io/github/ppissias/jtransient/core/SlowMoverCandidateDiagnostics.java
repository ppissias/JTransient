package io.github.ppissias.jtransient.core;

/** Snapshot of the measurements and effective thresholds for one accepted maximum-stack candidate. */
public final class SlowMoverCandidateDiagnostics {
    /** Number of connected pixels in the candidate's maximum-stack footprint. */
    public final int pixelCount;
    /** Intensity-weighted second-moment shape ratio, distinct from geometric axisRatio. */
    public final double momentElongation;
    /** Intensity-weighted major-axis angle in radians. */
    public final double orientation;
    /** Longer oriented raw-pixel extent, in pixels. */
    public final double majorExtent;
    /** Shorter oriented raw-pixel extent, in pixels. */
    public final double minorExtent;
    /** Geometric majorExtent / minorExtent used by the primary shape filter. */
    public final double axisRatio;
    /** Connected pixel area divided by the oriented bounding-rectangle area. */
    public final double fillFactor;
    /** Footprint-derived displacement estimate, not a measured astrometric motion. */
    public final double estimatedMotionPixels;
    /** estimatedMotionPixels / minorExtent, approximately axisRatio - 1. */
    public final double estimatedMotionDiameters;
    /** Fraction of candidate raw pixels also present in the exact median-stack mask. */
    public final double medianMaskOverlapFraction;
    /** Fraction of candidate raw pixels outside the median-stack mask. */
    public final double outsideMedianMaskFraction;
    /** Effective lower geometric axis-ratio threshold used for this candidate. */
    public final double minAxisRatioThreshold;
    /** Effective upper geometric axis-ratio threshold used for this candidate. */
    public final double maxAxisRatioThreshold;
    /** Effective fill-factor threshold; zero disables the veto. */
    public final double minFillFactorThreshold;
    /** Effective optional lower median-mask overlap threshold. */
    public final double medianSupportMinOverlapThreshold;
    /** Effective upper median-mask overlap threshold for stationary-source rejection. */
    public final double medianSupportMaxOverlapThreshold;
    /** Percentage of usable frames with significant localized signal inside the candidate footprint. */
    public final double frameSupportPercentage;
    /** Percentage of supported frame positions clustered at one location; not a calibrated probability. */
    public final double stationaryLikelihoodPercentage;
    /** Number of frames with candidate-local source evidence. */
    public final int supportedFrameCount;
    /** Number of quality-filtered frames with a usable candidate region. */
    public final int usableFrameCount;
    /** Whether frameSupportPercentage was measurable. */
    public final boolean frameSupportAvailable;
    /** Whether enough supported frames spanned the sequence to measure stationarity. */
    public final boolean stationaryLikelihoodAvailable;
    /** Effective frame-support percentage floor; zero disables the veto. */
    public final double minFrameSupportThreshold;
    /** Effective stationary-likelihood percentage ceiling; 100 disables the veto. */
    public final double maxStationaryLikelihoodThreshold;

    /**
     * Captures the accepted object's morphology and the mask overlap used for filtering.
     *
     * @param object accepted maximum-stack detected object
     * @param medianMaskOverlapFraction fraction of its raw pixels inside the median mask
     * @param minAxisRatioThreshold effective lower axis-ratio threshold
     * @param maxAxisRatioThreshold effective upper axis-ratio threshold
     * @param minFillFactorThreshold effective fill-factor floor
     * @param medianSupportMinOverlapThreshold effective lower mask-overlap threshold
     * @param medianSupportMaxOverlapThreshold effective upper mask-overlap threshold
     */
    public SlowMoverCandidateDiagnostics(SourceExtractor.DetectedObject object,
                                         double medianMaskOverlapFraction,
                                         double minAxisRatioThreshold,
                                         double maxAxisRatioThreshold,
                                         double minFillFactorThreshold,
                                         double medianSupportMinOverlapThreshold,
                                         double medianSupportMaxOverlapThreshold) {
        this(object, medianMaskOverlapFraction, minAxisRatioThreshold, maxAxisRatioThreshold,
                minFillFactorThreshold, medianSupportMinOverlapThreshold, medianSupportMaxOverlapThreshold,
                SlowMoverFrameEvidence.unavailable(), 0.0, 100.0);
    }

    SlowMoverCandidateDiagnostics(SourceExtractor.DetectedObject object,
                                  double medianMaskOverlapFraction,
                                  double minAxisRatioThreshold,
                                  double maxAxisRatioThreshold,
                                  double minFillFactorThreshold,
                                  double medianSupportMinOverlapThreshold,
                                  double medianSupportMaxOverlapThreshold,
                                  SlowMoverFrameEvidence frameEvidence,
                                  double minFrameSupportThreshold,
                                  double maxStationaryLikelihoodThreshold) {
        this.pixelCount = object.rawPixels.size();
        this.momentElongation = object.elongation;
        this.orientation = object.angle;
        this.majorExtent = object.majorExtent;
        this.minorExtent = object.minorExtent;
        this.axisRatio = object.axisRatio;
        this.fillFactor = object.fillFactor;
        this.estimatedMotionPixels = Math.max(0.0, majorExtent - minorExtent);
        this.estimatedMotionDiameters = minorExtent > 0.0 ? estimatedMotionPixels / minorExtent : 0.0;
        this.medianMaskOverlapFraction = medianMaskOverlapFraction;
        this.outsideMedianMaskFraction = 1.0 - medianMaskOverlapFraction;
        this.minAxisRatioThreshold = minAxisRatioThreshold;
        this.maxAxisRatioThreshold = maxAxisRatioThreshold;
        this.minFillFactorThreshold = minFillFactorThreshold;
        this.medianSupportMinOverlapThreshold = medianSupportMinOverlapThreshold;
        this.medianSupportMaxOverlapThreshold = medianSupportMaxOverlapThreshold;
        this.frameSupportPercentage = frameEvidence.frameSupportPercentage;
        this.stationaryLikelihoodPercentage = frameEvidence.stationaryLikelihoodPercentage;
        this.supportedFrameCount = frameEvidence.supportedFrameCount;
        this.usableFrameCount = frameEvidence.usableFrameCount;
        this.frameSupportAvailable = frameEvidence.frameSupportAvailable;
        this.stationaryLikelihoodAvailable = frameEvidence.stationaryLikelihoodAvailable;
        this.minFrameSupportThreshold = minFrameSupportThreshold;
        this.maxStationaryLikelihoodThreshold = maxStationaryLikelihoodThreshold;
    }
}
