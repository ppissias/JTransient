package io.github.ppissias.jtransient.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public record PhotometryGeometry(double concentrationInnerRadius, double smallRadius, double mainRadius,
                                 double largeRadius, double concentrationOuterRadius,
                                 double annulusInnerRadius, double annulusOuterRadius) {
    public static final double MIN_RADIUS_PIXELS = 1.0;
    public static final double CONCENTRATION_INNER_FACTOR = 0.7;
    public static final double LARGEST_COMPARISON_FACTOR = 2.5;

    public record RadiusAdjustment(DetectionConfigRules.RuleId ruleId, String field,
                                   double configuredPixels, double effectivePixels) {}

    public double largestApertureRadius() { return Math.max(mainRadius, concentrationOuterRadius); }

    public static PhotometryGeometry resolve(DetectionConfig config, double fwhm) {
        Objects.requireNonNull(config, "config");
        if (!Double.isFinite(fwhm) || fwhm < 0) throw new IllegalArgumentException("FWHM must be finite and nonnegative.");
        if (!Double.isFinite(config.photometryApertureFwhmFactor)
                || !Double.isFinite(config.photometryAnnulusInnerFwhmFactor)
                || !Double.isFinite(config.photometryAnnulusOuterFwhmFactor)) {
            throw new IllegalArgumentException("Photometry geometry factors must be finite.");
        }
        double concentrationInner = Math.max(MIN_RADIUS_PIXELS, CONCENTRATION_INNER_FACTOR * fwhm);
        double small = Math.max(MIN_RADIUS_PIXELS, fwhm);
        double main = Math.max(MIN_RADIUS_PIXELS, config.photometryApertureFwhmFactor * fwhm);
        double large = Math.max(MIN_RADIUS_PIXELS, 2.0 * fwhm);
        double concentrationOuter = Math.max(MIN_RADIUS_PIXELS, LARGEST_COMPARISON_FACTOR * fwhm);
        double inner = Math.max(Math.max(main, concentrationOuter) + 1.0, config.photometryAnnulusInnerFwhmFactor * fwhm);
        double outer = Math.max(inner + 2.0, config.photometryAnnulusOuterFwhmFactor * fwhm);
        return new PhotometryGeometry(concentrationInner, small, main, large, concentrationOuter, inner, outer);
    }

    public List<RadiusAdjustment> adjustments(DetectionConfig config, double fwhm) {
        List<RadiusAdjustment> adjustments = new ArrayList<>();
        add(adjustments, "photometryApertureFwhmFactor", config.photometryApertureFwhmFactor * fwhm, mainRadius);
        add(adjustments, "photometryAnnulusInnerFwhmFactor", config.photometryAnnulusInnerFwhmFactor * fwhm, annulusInnerRadius);
        add(adjustments, "photometryAnnulusOuterFwhmFactor", config.photometryAnnulusOuterFwhmFactor * fwhm, annulusOuterRadius);
        return List.copyOf(adjustments);
    }

    private static void add(List<RadiusAdjustment> adjustments, String field, double configured, double effective) {
        if (Double.compare(configured, effective) != 0) {
            adjustments.add(new RadiusAdjustment(DetectionConfigRules.RuleId.PHOTOMETRY_EFFECTIVE_GEOMETRY, field, configured, effective));
        }
    }
}
