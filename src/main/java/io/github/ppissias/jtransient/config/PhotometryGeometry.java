package io.github.ppissias.jtransient.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Carries immutable effective photometry radii in pixels, shared by selection, measurement and crossing checks.
 * Resolve configured factors through {@link #resolve} rather than duplicating pixel floors or sky margins.
 * Resolution never rewrites the supplied configuration.
 *
 * @param concentrationInnerRadius concentration-index aperture, nominally 0.7 FWHM
 * @param smallRadius small comparison aperture, nominally 1 FWHM
 * @param mainRadius configurable main aperture
 * @param largeRadius large comparison aperture, nominally 2 FWHM
 * @param concentrationOuterRadius concentration-index comparison aperture, nominally 2.5 FWHM
 * @param annulusInnerRadius inner sky boundary outside every measuring aperture
 * @param annulusOuterRadius outer sky boundary, at least 2 pixels beyond the inner boundary
 */
public record PhotometryGeometry(double concentrationInnerRadius, double smallRadius, double mainRadius,
                                 double largeRadius, double concentrationOuterRadius,
                                 double annulusInnerRadius, double annulusOuterRadius) {
    /** Minimum measuring-aperture radius, including for finite nonpositive legacy factors. */
    public static final double MIN_RADIUS_PIXELS = 1.0;
    /** Fixed inner concentration aperture in units of FWHM before applying the pixel floor. */
    public static final double CONCENTRATION_INNER_FACTOR = 0.7;
    /** Largest fixed comparison aperture in units of FWHM; the configurable main aperture can be larger. */
    public static final double LARGEST_COMPARISON_FACTOR = 2.5;

    /**
     * Describes runtime expansion of one configured radius without proposing a persisted factor change.
     *
     * @param ruleId effective-geometry rule identifier
     * @param field configuration factor whose pixel radius expanded
     * @param configuredPixels unadjusted factor times the supplied FWHM
     * @param effectivePixels resolved radius after pixel floors and sky clearance
     */
    public record RadiusAdjustment(DetectionConfigRules.RuleId ruleId, String field,
                                   double configuredPixels, double effectivePixels) {}

    /**
     * Returns the aperture that determines the minimum inner-sky clearance.
     *
     * @return largest effective measuring-aperture radius in pixels
     */
    public double largestApertureRadius() { return Math.max(mainRadius, concentrationOuterRadius); }

    /**
     * Resolves the existing 1-pixel aperture floor, 1-pixel inner clearance and 2-pixel minimum sky width.
     * Finite crossed or nonpositive legacy factors remain usable through runtime expansion.
     *
     * @param config settings supplying the main-aperture and annulus factors, never modified
     * @param fwhm finite, nonnegative guide or measured FWHM in pixels
     * @return effective radii for the supplied settings and FWHM
     * @throws IllegalArgumentException if FWHM is negative/non-finite or a geometry factor is non-finite
     */
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

    /**
     * Explains which configured main/annulus radii differ from this resolved geometry.
     * Supply the same configuration and FWHM used to resolve this instance.
     *
     * @param config original settings whose factors remain unchanged
     * @param fwhm FWHM used for this resolution, in pixels
     * @return immutable pixel-radius adjustments, empty when no configured radius expanded
     */
    public List<RadiusAdjustment> adjustments(DetectionConfig config, double fwhm) {
        List<RadiusAdjustment> adjustments = new ArrayList<>();
        add(adjustments, "photometryApertureFwhmFactor", config.photometryApertureFwhmFactor * fwhm, mainRadius);
        add(adjustments, "photometryAnnulusInnerFwhmFactor", config.photometryAnnulusInnerFwhmFactor * fwhm, annulusInnerRadius);
        add(adjustments, "photometryAnnulusOuterFwhmFactor", config.photometryAnnulusOuterFwhmFactor * fwhm, annulusOuterRadius);
        return List.copyOf(adjustments);
    }

    /** Adds a structured expansion only when the configured and effective pixel radii differ. */
    private static void add(List<RadiusAdjustment> adjustments, String field, double configured, double effective) {
        if (Double.compare(configured, effective) != 0) {
            adjustments.add(new RadiusAdjustment(DetectionConfigRules.RuleId.PHOTOMETRY_EFFECTIVE_GEOMETRY, field, configured, effective));
        }
    }
}
