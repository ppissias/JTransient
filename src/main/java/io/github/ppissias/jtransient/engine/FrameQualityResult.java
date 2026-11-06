package io.github.ppissias.jtransient.engine;

import io.github.ppissias.jtransient.core.FrameRegistration;
import io.github.ppissias.jtransient.telemetry.PipelineTelemetry;
import java.util.List;
import java.util.Map;

/**
 * Detached quality-control measurements and the exact session rejection decision. Original sequence indices identify
 * frames; the ordered immutable records retain no pixels, frame descriptors, coverage arrays or mutable telemetry.
 * FWHM and registration quantities use pixels; sky and noise use shifted unsigned16 ADU. Elongation is an axis ratio.
 */
public record FrameQualityResult(List<Frame> frames, Thresholds thresholds,
                                 double qualityAreaFraction, double retainedAreaFraction) {
    /** Copies the small record container; elements and thresholds are immutable. */
    public FrameQualityResult {
        frames = List.copyOf(frames);
        java.util.Objects.requireNonNull(thresholds, "thresholds");
    }

    /** One frame's scalar quality metrics, reference-star counts and rejection reason; registration may be absent. */
    public record Frame(int sequenceIndex, String filename, boolean rejected, String reason,
                        double backgroundMedian, double backgroundNoise, double medianFwhm,
                        double medianElongation, double brightStarMedianElongation,
                        int starCount, int usableShapeStarCount, int brightStarShapeStarCount, int fwhmStarCount,
                        Registration registration) { }

    /**
     * Fit to registration-padding edges, not residual stellar alignment. Shifts/RMS use pixels and rotation radians.
     * Zero without padding does not establish perfect stellar alignment; usability governs camera mapping, not a new QC gate.
     */
    public record Registration(double shiftX, double shiftY, double rotationRadians, double edgeFitRmsPixels,
                               boolean usable) { }

    /** Exact session limits; unavailable thresholds and insufficient evidence retain NaN instead of invented values. */
    public record Thresholds(boolean available, double minimumStarCount, double maximumFwhm,
                             double maximumElongation, double maximumBrightStarElongation,
                             double backgroundBaseline, double maximumBackgroundDeviation,
                             double minimumBackground, double maximumBackground) { }

    /** Number of input frames the engine's quality stage would retain. */
    public int keptFrames() {
        return (int) frames.stream().filter(frame -> !frame.rejected()).count();
    }

    /** Number of input frames the engine's quality stage would reject. */
    public int rejectedFrames() {
        return frames.size() - keptFrames();
    }

    /** Whether full detection would stop after QC because fewer than the engine's minimum frames remain. */
    public boolean tooFewFrames() {
        return keptFrames() < JTransientEngine.MIN_KEPT_FRAMES;
    }

    /** Copies an operation's finished quality telemetry and optional immutable padding fits into value records. */
    static FrameQualityResult capture(PipelineTelemetry telemetry, Map<Integer, FrameRegistration> registrations) {
        var frames = telemetry.frameQualityStats.stream().map(stat -> {
            FrameRegistration fit = registrations.get(stat.frameIndex);
            Registration registration = fit == null ? null
                    : new Registration(fit.tx, fit.ty, fit.theta, fit.rmsPx, fit.usable);
            return new Frame(stat.frameIndex, stat.filename, stat.rejected, stat.rejectionReason,
                    stat.backgroundMedian, stat.backgroundNoise, stat.medianFWHM, stat.medianEccentricity,
                    stat.brightStarMedianEccentricity, stat.starCount, stat.usableShapeStarCount,
                    stat.brightStarShapeStarCount, stat.fwhmStarCount, registration);
        }).toList();
        var t = telemetry.qualityThresholds;
        return new FrameQualityResult(frames, new Thresholds(t.available, t.minAllowedStarCount, t.maxAllowedFwhm,
                t.maxAllowedEccentricity, t.maxAllowedBrightStarEccentricity, t.backgroundMedianBaseline,
                t.maxAllowedBackgroundDeviation, t.minAllowedBackgroundMedian, t.maxAllowedBackgroundMedian),
                telemetry.qualityAreaFraction, telemetry.dataAreaFraction);
    }
}
