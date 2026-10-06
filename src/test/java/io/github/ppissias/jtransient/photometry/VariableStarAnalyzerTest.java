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

import io.github.ppissias.jtransient.config.DetectionConfig;
import io.github.ppissias.jtransient.core.PixelEncoding;
import io.github.ppissias.jtransient.engine.ImageFrame;
import io.github.ppissias.jtransient.engine.JTransientEngine;
import io.github.ppissias.jtransient.engine.PipelineResult;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.function.DoubleUnaryOperator;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class VariableStarAnalyzerTest {

    private static final int SIZE = 400;
    private static final int FRAMES = 40;
    private static final double SKY = 1000.0;
    private static final double READ_NOISE = 5.0;
    private static final long FRAME_INTERVAL_MS = 120_000L;
    private static final long EXPOSURE_MS = 60_000L;

    /** Injected variable: sinusoid with 0.15 mag semi-amplitude. */
    private static final double VARIABLE_AMPLITUDE_MAG = 0.15;
    /** Asteroid moving through a constant star; it is exactly on the star in frame 20. */
    private static final int CROSSING_FRAME = 20;
    private static final double ASTEROID_STEP_PIXELS = 3.0;

    private static JTransientEngine engine;

    private static final class Star {
        final double x;
        final double y;
        final double flux;
        boolean variable;

        Star(double x, double y, double flux) {
            this.x = x;
            this.y = y;
            this.flux = flux;
        }
    }

    private static final class Session {
        final List<Star> stars = new ArrayList<>();
        Star variable;
        Star crossed;
        List<ImageFrame> frames;
    }

    @BeforeClass
    public static void setUpEngine() {
        engine = new JTransientEngine();
    }

    @AfterClass
    public static void tearDownEngine() {
        engine.shutdown();
    }

    @Test
    public void linearSessionFindsInjectedVariableAndNoFalsePositives() throws Exception {
        Session session = createSession(DoubleUnaryOperator.identity());

        PipelineResult result = engine.runPipeline(session.frames, testConfig(), null, null);
        VariableStarAnalysis analysis = result.variableStarAnalysis;

        assertNotNull(result.telemetry.photometryTelemetry);
        String report = result.telemetry.generateReport();
        assertEquals(report, PhotometricReadiness.Verdict.READY, analysis.readiness.verdict);
        assertEquals(PhotometricReadiness.CheckStatus.PASS, analysis.readiness.quantisationCheck);
        assertEquals(PhotometricReadiness.CheckStatus.PASS, analysis.readiness.shapeLinearityCheck);
        assertEquals(PhotometricReadiness.CheckStatus.PASS, analysis.readiness.responseCheck);
        assertEquals(FRAMES, analysis.frames.size());
        assertTrue("most stars should be measured", analysis.stars.size() > 150);

        StarLightCurve variable = nearest(analysis, session.variable);
        assertEquals(variable.failedGates.toString(), VariabilityTier.HIGH_CONFIDENCE, variable.tier);
        assertEquals(2 * VARIABLE_AMPLITUDE_MAG, variable.amplitude, 0.05);

        List<StarLightCurve> highConfidence = analysis.highConfidenceCandidates();
        assertEquals("only the injected variable may be high confidence", 1, highConfidence.size());

        // The transparency change is absorbed by the zero points, not by the stars.
        double zMin = Double.MAX_VALUE;
        double zMax = -Double.MAX_VALUE;
        for (int j = 0; j < analysis.frames.size(); j++) {
            zMin = Math.min(zMin, analysis.frames.get(j).zeroPoint);
            zMax = Math.max(zMax, analysis.frames.get(j).zeroPoint);
        }
        assertEquals(0.30, zMax - zMin, 0.05);
    }

    /**
     * A variable that brightens steadily while transparency also drifts steadily correlates almost
     * perfectly with the frame zero point. Its change is far larger than any constant star's response
     * to that drift, so the systematics gate must not reject it.
     */
    @Test
    public void steadyTrendCorrelatedWithTransparencyDriftIsNotRejectedAsSystematic() throws Exception {
        Session session = createSession(DoubleUnaryOperator.identity(),
                j -> 0.1 * j / (FRAMES - 1.0),
                j -> 0.15 - 0.3 * j / (FRAMES - 1.0));

        PipelineResult result = engine.runPipeline(session.frames, testConfig(), null, null);
        StarLightCurve variable = nearest(result.variableStarAnalysis, session.variable);

        assertTrue("trend must correlate with the zero point: " + variable.maxSystematicsCorrelation,
                variable.maxSystematicsCorrelation > testConfig().variableMaxSystematicsCorrelation);
        assertTrue("amplitude must exceed the systematics limit: " + variable.systematicsLimitMag,
                variable.amplitude > variable.systematicsLimitMag);
        assertEquals(variable.failedGates.toString(), VariabilityTier.HIGH_CONFIDENCE, variable.tier);
        assertEquals(1, result.variableStarAnalysis.highConfidenceCandidates().size());
    }

    /**
     * A constant star whose measured brightness follows the seeing (as a blend leaking light does)
     * tracks every FWHM fluctuation, so it must fail the systematics gate however large its change.
     */
    @Test
    public void seeingCoupledBlendIsRejectedBySystematicsGate() throws Exception {
        Session session = createSession(DoubleUnaryOperator.identity(),
                j -> 0.15 * Math.sin(2 * Math.PI * j / FRAMES),
                j -> 0.0,
                3.0);

        PipelineResult result = engine.runPipeline(session.frames, testConfig(), null, null);
        StarLightCurve blend = nearest(result.variableStarAnalysis, session.variable);

        assertTrue("blend must be a candidate first: " + blend.tier,
                blend.tier != VariabilityTier.CONSTANT && blend.tier != VariabilityTier.NOT_SCORED);
        assertTrue("frame-to-frame FWHM correlation expected: " + blend.maxFrameToFrameSystematicsCorrelation,
                blend.maxFrameToFrameSystematicsCorrelation > testConfig().variableMaxSystematicsCorrelation);
        assertTrue(blend.failedGates.toString(), blend.failedGates.contains("SYSTEMATICS"));
        assertTrue(result.variableStarAnalysis.highConfidenceCandidates().isEmpty());
    }

    @Test
    public void asteroidCrossingAStarIsFlaggedAndNotReported() throws Exception {
        Session session = createSession(DoubleUnaryOperator.identity());

        PipelineResult result = engine.runPipeline(session.frames, testConfig(), null, null);
        StarLightCurve crossed = nearest(result.variableStarAnalysis, session.crossed);

        assertTrue("the crossing frame must be flagged",
                (crossed.flags[CROSSING_FRAME] & PhotometryFlags.CROSSING) != 0);
        assertFalse(crossed.tier == VariabilityTier.HIGH_CONFIDENCE);
    }

    @Test
    public void stretchedSessionIsRefusedByStarShapeCheck() throws Exception {
        double beta = 1000.0;
        double scale = 65535.0 / asinh(65535.0 / beta);
        Session session = createSession(v -> scale * asinh(v / beta));

        PipelineResult result = engine.runPipeline(session.frames, testConfig(), null, null);
        PhotometricReadiness readiness = result.variableStarAnalysis.readiness;

        assertEquals(PhotometricReadiness.CheckStatus.FAIL, readiness.shapeLinearityCheck);
        assertEquals(PhotometricReadiness.Verdict.NOT_READY, readiness.verdict);
        assertTrue(result.variableStarAnalysis.candidates.isEmpty());
        assertFalse(readiness.messages.isEmpty());
    }

    @Test
    public void quantisedSessionIsRefusedByQuantisationCheck() throws Exception {
        Session session = createSession(v -> Math.floor(v / 64.0) * 64.0);

        PipelineResult result = engine.runPipeline(session.frames, testConfig(), null, null);
        PhotometricReadiness readiness = result.variableStarAnalysis.readiness;

        assertTrue(readiness.distinctPixelLevels < 1024);
        assertEquals(PhotometricReadiness.CheckStatus.FAIL, readiness.quantisationCheck);
        assertEquals(PhotometricReadiness.Verdict.NOT_READY, readiness.verdict);
    }

    @Test
    public void disabledPhotometryReturnsEmptyAnalysis() throws Exception {
        Session session = createSession(DoubleUnaryOperator.identity());
        DetectionConfig config = testConfig();
        config.enableVariableStarDetection = false;

        PipelineResult result = engine.runPipeline(session.frames, config, null, null);

        assertEquals(PhotometricReadiness.Verdict.NOT_RUN, result.variableStarAnalysis.readiness.verdict);
        assertTrue(result.variableStarAnalysis.stars.isEmpty());
        assertEquals(null, result.telemetry.photometryTelemetry);
    }

    @Test
    public void apertureRecoversGaussianFlux() {
        double sigma = 1.3;
        double fwhm = 2.355 * sigma;
        double flux = 100_000.0;
        double[][] image = new double[64][64];
        for (double[] row : image) {
            java.util.Arrays.fill(row, SKY);
        }
        renderStar(image, 32.3, 31.6, flux, sigma);
        short[][] pixels = toPixels(image, DoubleUnaryOperator.identity());

        List<PhotometryStarSelector.Star> stars = Collections.singletonList(new PhotometryStarSelector.Star(32.3, 31.6, flux));
        DetectionConfig config = new DetectionConfig();
        ApertureMeasurer.FrameMeasurement m = ApertureMeasurer.measure(pixels, fwhm, stars, null, 70_000, SKY, config);

        double radius = m.radii[ApertureMeasurer.R_MAIN];
        double expected = flux * (1.0 - Math.exp(-radius * radius / (2 * sigma * sigma)));
        assertEquals(expected, m.flux[ApertureMeasurer.R_MAIN][0], 0.01 * expected);
        assertEquals(SKY, m.sky[0], 1.0);
        assertEquals(0, m.flags[0]);
    }

    // =================================================================
    // Synthetic session
    // =================================================================

    private static DetectionConfig testConfig() {
        DetectionConfig config = new DetectionConfig();
        config.enableVariableStarDetection = true;
        config.enableSlowMoverDetection = false;
        // Synthetic seeing and transparency changes must not trigger the session outlier filter.
        config.starCountSigmaDeviation = 100.0;
        config.fwhmSigmaDeviation = 100.0;
        config.eccentricitySigmaDeviation = 100.0;
        config.backgroundSigmaDeviation = 100.0;
        config.enableBrightStarEccentricityFilter = false;
        return config;
    }

    /**
     * Builds a 40-frame session: about 200 isolated Gaussian stars spanning 6.4 mag, Poisson and read
     * noise, a 0.3 mag transparency wave, random seeing, one sinusoidal variable, and one asteroid that
     * passes exactly over a constant star. {@code response} transforms every pixel (identity = linear).
     */
    private static Session createSession(DoubleUnaryOperator response) {
        return createSession(response,
                j -> 0.15 * Math.sin(2 * Math.PI * j / FRAMES),
                j -> VARIABLE_AMPLITUDE_MAG * Math.sin(2 * Math.PI * j / (FRAMES / 1.5)));
    }

    /**
     * @param extinctionMag transparency loss in mag for frame j
     * @param variableMag brightness change of the variable in mag for frame j (positive = fainter)
     */
    private static Session createSession(DoubleUnaryOperator response,
                                         java.util.function.IntToDoubleFunction extinctionMag,
                                         java.util.function.IntToDoubleFunction variableMag) {
        return createSession(response, extinctionMag, variableMag, 0.0);
    }

    /**
     * @param seeingCouplingMag change of the variable, in mag, per unit fractional change of the seeing
     *                          (positive = fainter in worse seeing), imitating a blend that leaks with seeing
     */
    private static Session createSession(DoubleUnaryOperator response,
                                         java.util.function.IntToDoubleFunction extinctionMag,
                                         java.util.function.IntToDoubleFunction variableMag,
                                         double seeingCouplingMag) {
        Random random = new Random(42);
        Session session = new Session();
        double spacing = 22.0;
        for (double y = 30; y < SIZE - 30; y += spacing) {
            for (double x = 30; x < SIZE - 30; x += spacing) {
                double flux = 3000.0 * Math.pow(400_000.0 / 3000.0, random.nextDouble());
                session.stars.add(new Star(x + 3 * (random.nextDouble() - 0.5), y + 3 * (random.nextDouble() - 0.5), flux));
            }
        }
        // Variable and crossed star: mid-brightness, chosen away from the field centre and from each other.
        session.variable = closestTo(session.stars, 120, 140);
        session.variable.variable = true;
        session.crossed = closestTo(session.stars, 270, 250);
        Star variable = session.variable;
        Star crossed = session.crossed;
        session.stars.set(session.stars.indexOf(variable), session.variable = withFlux(variable, 30_000.0, true));
        session.stars.set(session.stars.indexOf(crossed), session.crossed = withFlux(crossed, 30_000.0, false));

        double[] seeingSigma = new double[FRAMES];
        for (int j = 0; j < FRAMES; j++) {
            seeingSigma[j] = seeingCouplingMag == 0.0
                    ? (2.8 + 0.6 * random.nextDouble()) / 2.355
                    // Smooth, quasi-periodic seeing so a seeing-coupled star varies coherently.
                    : (3.1 + 0.3 * Math.sin(2 * Math.PI * j / 13.0) + 0.05 * random.nextGaussian()) / 2.355;
        }

        session.frames = new ArrayList<>();
        long start = 1_760_000_000_000L;
        for (int j = 0; j < FRAMES; j++) {
            double transparency = Math.pow(10, -0.4 * extinctionMag.applyAsDouble(j));
            double[][] image = new double[SIZE][SIZE];
            for (double[] row : image) {
                java.util.Arrays.fill(row, SKY);
            }
            for (Star star : session.stars) {
                double flux = star.flux * transparency;
                if (star.variable) {
                    flux *= Math.pow(10, -0.4 * variableMag.applyAsDouble(j));
                    flux *= Math.pow(10, -0.4 * seeingCouplingMag * (seeingSigma[j] * 2.355 - 3.1) / 3.1);
                }
                renderStar(image, star.x, star.y, flux, seeingSigma[j]);
            }
            double asteroidX = session.crossed.x + ASTEROID_STEP_PIXELS * (j - CROSSING_FRAME);
            double asteroidY = session.crossed.y + 0.5 * ASTEROID_STEP_PIXELS * (j - CROSSING_FRAME);
            renderStar(image, asteroidX, asteroidY, 20_000.0 * transparency, seeingSigma[j]);

            for (int y = 0; y < SIZE; y++) {
                for (int x = 0; x < SIZE; x++) {
                    double v = image[y][x];
                    image[y][x] = v + random.nextGaussian() * Math.sqrt(v + READ_NOISE * READ_NOISE);
                }
            }
            session.frames.add(new ImageFrame(j, "frame" + j + ".fits", toPixels(image, response),
                    start + j * FRAME_INTERVAL_MS, EXPOSURE_MS));
        }
        return session;
    }

    private static Star withFlux(Star star, double flux, boolean variable) {
        Star copy = new Star(star.x, star.y, flux);
        copy.variable = variable;
        return copy;
    }

    private static Star closestTo(List<Star> stars, double x, double y) {
        Star best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Star star : stars) {
            double d = Math.hypot(star.x - x, star.y - y);
            if (d < bestDistance) {
                bestDistance = d;
                best = star;
            }
        }
        return best;
    }

    /** Adds a Gaussian star whose pixel values sum exactly to {@code flux}. */
    private static void renderStar(double[][] image, double cx, double cy, double flux, double sigma) {
        int r = (int) Math.ceil(5 * sigma);
        int x0 = (int) Math.round(cx);
        int y0 = (int) Math.round(cy);
        double[][] kernel = new double[2 * r + 1][2 * r + 1];
        double total = 0;
        for (int dy = -r; dy <= r; dy++) {
            for (int dx = -r; dx <= r; dx++) {
                double px = x0 + dx - cx;
                double py = y0 + dy - cy;
                double value = Math.exp(-(px * px + py * py) / (2 * sigma * sigma));
                kernel[dy + r][dx + r] = value;
                total += value;
            }
        }
        for (int dy = -r; dy <= r; dy++) {
            for (int dx = -r; dx <= r; dx++) {
                int x = x0 + dx;
                int y = y0 + dy;
                if (x >= 0 && y >= 0 && x < image[0].length && y < image.length) {
                    image[y][x] += flux * kernel[dy + r][dx + r] / total;
                }
            }
        }
    }

    private static short[][] toPixels(double[][] image, DoubleUnaryOperator response) {
        short[][] pixels = new short[image.length][image[0].length];
        for (int y = 0; y < image.length; y++) {
            for (int x = 0; x < image[0].length; x++) {
                double v = response.applyAsDouble(Math.max(0, image[y][x]));
                int clamped = (int) Math.max(0, Math.min(65535, Math.round(v)));
                pixels[y][x] = PixelEncoding.fromShiftedPositiveInt(clamped);
            }
        }
        return pixels;
    }

    private static StarLightCurve nearest(VariableStarAnalysis analysis, Star star) {
        StarLightCurve best = null;
        double bestDistance = Double.MAX_VALUE;
        for (StarLightCurve curve : analysis.stars) {
            double d = Math.hypot(curve.x - star.x, curve.y - star.y);
            if (d < bestDistance) {
                bestDistance = d;
                best = curve;
            }
        }
        assertNotNull(best);
        assertTrue("star must be measured (nearest at " + bestDistance + " px)", bestDistance < 2.0);
        return best;
    }

    private static double asinh(double v) {
        return Math.log(v + Math.sqrt(v * v + 1));
    }
}
