package io.github.ppissias.jtransient.config;

import org.junit.Test;

import static org.junit.Assert.*;

public class PhotometryGeometryTest {
    @Test
    public void sharedResolutionMatchesTheExistingMeasurementPolicyAcrossFwhmAndLegacyFactors() {
        for (double fwhm : new double[]{0, 0.3, 1, 2, 4, 10}) {
            for (double aperture : new double[]{-1, 0.5, 1.5, 4}) {
                for (double inner : new double[]{-1, 1, 3, 6}) {
                    for (double outer : new double[]{0, 2, 5, 10}) {
                        DetectionConfig config = new DetectionConfig();
                        config.photometryApertureFwhmFactor = aperture;
                        config.photometryAnnulusInnerFwhmFactor = inner;
                        config.photometryAnnulusOuterFwhmFactor = outer;
                        PhotometryGeometry geometry = PhotometryGeometry.resolve(config, fwhm);
                        double largest = Math.max(Math.max(1, aperture * fwhm), Math.max(1, 2.5 * fwhm));
                        assertEquals(Math.max(largest + 1, inner * fwhm), geometry.annulusInnerRadius(), 0);
                        assertEquals(Math.max(geometry.annulusInnerRadius() + 2, outer * fwhm), geometry.annulusOuterRadius(), 0);
                        assertTrue(geometry.annulusInnerRadius() > geometry.mainRadius());
                        assertTrue(geometry.annulusInnerRadius() > geometry.concentrationOuterRadius());
                        assertEquals(aperture, config.photometryApertureFwhmFactor, 0);
                    }
                }
            }
        }
    }

    @Test
    public void adjustedPixelRadiiAreReportedWithoutRewritingConfiguredFactors() {
        DetectionConfig config = new DetectionConfig();
        config.photometryApertureFwhmFactor = 4;
        config.photometryAnnulusInnerFwhmFactor = 1;
        config.photometryAnnulusOuterFwhmFactor = 2;
        var geometry = PhotometryGeometry.resolve(config, 2);
        assertEquals(8, geometry.mainRadius(), 0);
        assertEquals(9, geometry.annulusInnerRadius(), 0);
        assertEquals(11, geometry.annulusOuterRadius(), 0);
        assertEquals(2, geometry.adjustments(config, 2).size());
        assertEquals(DetectionConfigRules.RuleId.PHOTOMETRY_EFFECTIVE_GEOMETRY, geometry.adjustments(config, 2).getFirst().ruleId());
        assertEquals(1, config.photometryAnnulusInnerFwhmFactor, 0);
        assertEquals(2, config.photometryAnnulusOuterFwhmFactor, 0);
    }
}
