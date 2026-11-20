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
                                 double qualityAreaFraction, double retainedAreaFraction,
                                 RegistrationCheck registrationCheck) {
    /** Copies the small record container; elements, thresholds and the registration check are immutable. */
    public FrameQualityResult {
        frames = List.copyOf(frames);
        java.util.Objects.requireNonNull(thresholds, "thresholds");
        java.util.Objects.requireNonNull(registrationCheck, "registrationCheck");
    }

    /**
     * The registration check ({@link io.github.ppissias.jtransient.quality.RegistrationCheck}): how far the stars move
     * between frames after registration, in star widths (FWHM). Creep is the change over the session of the whole
     * frame's position and of each of {@code zones} by {@code zones} zones (row-major from the top left, NaN with too
     * few stars); flagged above {@code creepLimitFwhm}. Frames whose stars jumped were rejected with that reason.
     * Unavailable with a {@code reason} when the check is off or could not measure.
     */
    public record RegistrationCheck(boolean available, String reason, double fwhmPixels, int framesMeasured,
                                    int referenceStars, int framesRejected, double largestKeptJumpFwhm,
                                    double overallCreepFwhm, double creepLimitFwhm, int zones, int width, int height,
                                    List<Double> zoneCreepFwhm, List<Integer> zoneStars) {
        /** Copies the zone lists. */
        public RegistrationCheck {
            zoneCreepFwhm = List.copyOf(zoneCreepFwhm);
            zoneStars = List.copyOf(zoneStars);
        }

        /** A check that did not measure, with the reason. */
        public static RegistrationCheck notMeasured(String reason) {
            return new RegistrationCheck(false, reason, Double.NaN, 0, 0, 0, Double.NaN, Double.NaN, Double.NaN, 0, 0,
                    0, List.of(), List.of());
        }

        /** Whether the whole frame's position creeps above the limit. */
        public boolean overallFlagged() {
            return available && overallCreepFwhm > creepLimitFwhm;
        }

        /** The creep of the zone at the row and column, in FWHM; NaN when not measured. */
        public double zoneCreep(int row, int column) {
            return available ? zoneCreepFwhm.get(row * zones + column) : Double.NaN;
        }

        /** Whether the zone at the row and column creeps above the limit. */
        public boolean zoneFlagged(int row, int column) {
            return zoneCreep(row, column) > creepLimitFwhm;
        }

        /** Pixel rectangle {x, y, width, height} of the zone at the row and column. */
        public int[] zoneRectangle(int row, int column) {
            return io.github.ppissias.jtransient.quality.RegistrationCheck.zoneRectangle(width, height, row, column);
        }

        /** The flagged zones as areas to exclude; vertically adjacent zones of a column are one area. */
        public List<ExcludedArea> flaggedAreas() {
            if (!available) {
                return List.of();
            }
            double[] creep = zoneCreepFwhm.stream().mapToDouble(Double::doubleValue).toArray();
            return io.github.ppissias.jtransient.quality.RegistrationCheck
                    .flaggedAreas(width, height, zones, creep, creepLimitFwhm).stream()
                    .map(r -> new ExcludedArea(r[0], r[1], r[2], r[3])).toList();
        }

        /** Whether anything was flagged. */
        public boolean anyFlagged() {
            if (overallFlagged()) {
                return true;
            }
            for (int row = 0; row < zones; row++) {
                for (int column = 0; column < zones; column++) {
                    if (zoneFlagged(row, column)) {
                        return true;
                    }
                }
            }
            return false;
        }

        /** Copies the telemetry record. */
        static RegistrationCheck of(PipelineTelemetry.RegistrationCheckTelemetry t) {
            List<Double> creep = new java.util.ArrayList<>();
            for (double c : t.zoneCreepFwhm) {
                creep.add(c);
            }
            List<Integer> stars = new java.util.ArrayList<>();
            for (int c : t.zoneStars) {
                stars.add(c);
            }
            return new RegistrationCheck(t.available, t.reason, t.fwhmPixels, t.framesMeasured, t.referenceStars,
                    t.framesRejected, t.largestKeptJumpFwhm, t.overallCreepFwhm, t.creepLimitFwhm, t.zones, t.width,
                    t.height, creep, stars);
        }
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
                telemetry.qualityAreaFraction, telemetry.dataAreaFraction,
                RegistrationCheck.of(telemetry.registrationCheck));
    }
}
