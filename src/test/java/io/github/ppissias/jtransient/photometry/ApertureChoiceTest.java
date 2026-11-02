/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 * author: Petros Pissias <petrospis at gmail.com>
 *
 */
package io.github.ppissias.jtransient.photometry;

import io.github.ppissias.jtransient.telemetry.PipelineTelemetry;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Checks that the aperture chosen for a brightness range supplies a light curve's residuals, errors and aperture
 * record together, and that the main aperture's mean magnitude stays the common brightness scale.
 */
public class ApertureChoiceTest {

    /** Eight brightness ranges of 20 stars: the fewest the choice runs on. */
    private static final int STARS = 160;
    private static final int FRAMES = 10;
    private static final double SESSION_FWHM = 4.0;
    private static final double[] FACTORS = {ApertureMeasurer.CI_INNER_FWHM_FACTOR, 1.0, 1.5, 2.0};
    private static final int[] RADII = {ApertureMeasurer.R_CI_INNER, ApertureMeasurer.R_SMALL, ApertureMeasurer.R_MAIN, ApertureMeasurer.R_LARGE};
    private static final double[] RADIUS_FACTORS = {ApertureMeasurer.CI_INNER_FWHM_FACTOR, 1.0, 1.5, 2.0, ApertureMeasurer.CI_OUTER_FWHM_FACTOR};
    private static final int MAIN = 2;
    /** Residual pattern whose robust spread scales with the factor applied to it. */
    private static final double[] PATTERN = {-1.0, 0.8, -0.3, 0.5, -1.2, 1.1, 0.2, -0.6, 0.9, -0.4};
    /** Measured FWHM of each frame. The last frame is not used, so its large FWHM must not reach the radius ranges. */
    private static final double[] FRAME_FWHM = {3.6, 3.8, 4.0, 4.2, 4.4, 3.9, 4.1, 3.7, 4.3, 9.0};
    /** Relative scatter of each option per brightness range: tiny, tiny, a tie, too small a gain, small, small, large, large. */
    private static final double[][] SCATTER = {
            {0.5, 1, 1, 1}, {0.5, 1, 1, 1}, {1, 1, 1, 1}, {1, 0.98, 1, 1},
            {1, 0.5, 1, 1}, {1, 0.5, 1, 1}, {1, 1, 1, 0.5}, {1, 1, 1, 0.5}};
    private static final int[] EXPECTED = {0, 0, MAIN, MAIN, 1, 1, 3, 3};
    /** A small-aperture star whose flux in frame 3 is not positive: neither residual nor error exists there. */
    private static final int MISSING_STAR = 85;
    private static final int MISSING_FRAME = 3;

    /** Inputs of one aperture choice, set up as the analyzer leaves them before the choice. */
    private static final class Fixture {
        final List<StarLightCurve> curves = new ArrayList<>();
        final boolean[][] usable = new boolean[STARS][FRAMES];
        final boolean[] starExcluded = new boolean[STARS];
        final EnsembleSolver.Solution[] options = new EnsembleSolver.Solution[FACTORS.length];
        final ApertureMeasurer.FrameMeasurement[] measurements = new ApertureMeasurer.FrameMeasurement[FRAMES];
        final boolean[] frameActive = new boolean[FRAMES];
        final PipelineTelemetry.PhotometryTelemetry telemetry = new PipelineTelemetry.PhotometryTelemetry();

        /** Builds residuals, errors and per-frame radii; the curves hold the main aperture's values. */
        Fixture() {
            for (int j = 0; j < FRAMES; j++) {
                frameActive[j] = j < FRAMES - 1;
                ApertureMeasurer.FrameMeasurement m = new ApertureMeasurer.FrameMeasurement(FRAME_FWHM[j], STARS);
                for (int r = 0; r < ApertureMeasurer.RADIUS_COUNT; r++) {
                    m.radii[r] = RADIUS_FACTORS[r] * FRAME_FWHM[j];
                    for (int i = 0; i < STARS; i++) {
                        m.magError[r][i] = 0.001 * (r + 1) + 1e-5 * j + 1e-8 * i;
                    }
                }
                measurements[j] = m;
            }
            for (int a = 0; a < FACTORS.length; a++) {
                EnsembleSolver.Solution s = new EnsembleSolver.Solution();
                s.starMag = new double[STARS];
                s.residual = new double[STARS][FRAMES];
                for (int i = 0; i < STARS; i++) {
                    s.starMag[i] = 10 + 0.01 * i;
                    for (int j = 0; j < FRAMES; j++) {
                        s.residual[i][j] = frameActive[j] ? SCATTER[i / 20][a] * 0.01 * PATTERN[j] + 0.001 * a : Double.NaN;
                    }
                }
                options[a] = s;
            }
            options[1].residual[MISSING_STAR][MISSING_FRAME] = Double.NaN;
            measurements[MISSING_FRAME].magError[ApertureMeasurer.R_SMALL][MISSING_STAR] = Double.NaN;
            for (int i = 0; i < STARS; i++) {
                StarLightCurve curve = new StarLightCurve(i, i, 0, 1, FRAMES);
                curve.meanMag = options[MAIN].starMag[i];
                curve.apertureFwhmFactor = FACTORS[MAIN];
                curve.apertureMinRadiusPixels = 1.5 * 3.6;
                curve.apertureMaxRadiusPixels = 1.5 * 4.4;
                for (int j = 0; j < FRAMES; j++) {
                    usable[i][j] = frameActive[j];
                    curve.deltaMag[j] = options[MAIN].residual[i][j];
                    curve.magError[j] = measurements[j].magError[ApertureMeasurer.R_MAIN][i];
                }
                curves.add(curve);
            }
        }

        /** Runs the choice under test. */
        void choose() {
            VariableStarAnalyzer.chooseApertures(curves, usable, starExcluded, options[MAIN], options, FACTORS, RADII,
                    measurements, frameActive, SESSION_FWHM, telemetry);
        }
    }

    @Test
    public void chosenApertureSuppliesResidualsErrorsAndRecordTogether() {
        Fixture fixture = new Fixture();
        fixture.choose();

        assertEquals(8, fixture.telemetry.apertureChoices.size());
        for (int b = 0; b < 8; b++) {
            PipelineTelemetry.PhotometryApertureChoice choice = fixture.telemetry.apertureChoices.get(b);
            assertEquals("range " + b, FACTORS[EXPECTED[b]], choice.fwhmFactor, 0);
            assertEquals("range " + b, FACTORS[EXPECTED[b]] * 3.6, choice.minRadiusPixels, 1e-12);
            assertEquals("range " + b, FACTORS[EXPECTED[b]] * 4.4, choice.maxRadiusPixels, 1e-12);
        }
        for (int i = 0; i < STARS; i++) {
            StarLightCurve curve = fixture.curves.get(i);
            int a = EXPECTED[i / 20];
            assertEquals("main magnitude stays the brightness scale", 10 + 0.01 * i, curve.meanMag, 0);
            assertArrayEquals("star " + i, fixture.options[a].residual[i], curve.deltaMag, 0);
            for (int j = 0; j < FRAMES; j++) {
                assertEquals("star " + i + " frame " + j, fixture.measurements[j].magError[RADII[a]][i], curve.magError[j], 0);
            }
            assertEquals("star " + i, FACTORS[a], curve.apertureFwhmFactor, 0);
            assertEquals("star " + i, FACTORS[a] * 3.6, curve.apertureMinRadiusPixels, 1e-12);
            assertEquals("star " + i, FACTORS[a] * 4.4, curve.apertureMaxRadiusPixels, 1e-12);
        }
        StarLightCurve missing = fixture.curves.get(MISSING_STAR);
        assertTrue(Double.isNaN(missing.deltaMag[MISSING_FRAME]) && Double.isNaN(missing.magError[MISSING_FRAME]));
        assertEquals(fixture.measurements[MISSING_FRAME + 1].magError[ApertureMeasurer.R_SMALL][MISSING_STAR],
                missing.magError[MISSING_FRAME + 1], 0);
    }

    @Test
    public void tooFewStarsKeepTheMainApertureEverywhere() {
        Fixture fixture = new Fixture();
        fixture.starExcluded[0] = true;
        fixture.choose();

        assertTrue(fixture.telemetry.apertureChoices.isEmpty());
        for (int i = 0; i < STARS; i++) {
            StarLightCurve curve = fixture.curves.get(i);
            assertArrayEquals(fixture.options[MAIN].residual[i], curve.deltaMag, 0);
            for (int j = 0; j < FRAMES; j++) {
                assertEquals(fixture.measurements[j].magError[ApertureMeasurer.R_MAIN][i], curve.magError[j], 0);
            }
            assertEquals(FACTORS[MAIN], curve.apertureFwhmFactor, 0);
        }
    }
}
