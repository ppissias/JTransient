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
import io.github.ppissias.jtransient.telemetry.PipelineTelemetry;
import org.junit.Test;

import java.util.List;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Checks the sky-noise factors measured on blank sky: about 1 for independent pixels, about 2 for a frame shifted by
 * half a pixel with bilinear interpolation (each pixel the mean of four, so its noise is halved while an aperture sum
 * keeps the original noise), and none when the frame has too little blank sky. Only the reported errors carry them.
 */
public class SkyNoiseFactorTest {

    private static final double SKY = 1000;
    private static final double FWHM = 3.0;

    @Test
    public void independentPixelsGiveAFactorNearOne() {
        double[][] image = noise(400, 400, 20, 3);
        addStar(image, 200.3, 199.6, 200_000, FWHM / 2.355);
        ApertureMeasurer.FrameMeasurement m = measure(image, new PhotometryStarSelector.Star(200.3, 199.6, 200_000));
        assertTrue("blank apertures " + m.blankApertures, m.blankApertures >= 800);
        for (int r = 0; r < ApertureMeasurer.RADIUS_COUNT; r++) {
            // The formula slightly understates the error of a median sky, so the largest apertures read about 1.05.
            assertTrue("aperture " + r + ": " + m.skyNoiseFactor[r], m.skyNoiseFactor[r] >= 1.0 && m.skyNoiseFactor[r] < 1.12);
        }
    }

    @Test
    public void interpolatedPixelsRaiseTheFactorAndOnlyTheReportedError() {
        double[][] white = noise(401, 401, 40, 5);
        double[][] image = new double[400][400];
        for (int y = 0; y < 400; y++) {
            for (int x = 0; x < 400; x++) {
                image[y][x] = 0.25 * (white[y][x] + white[y][x + 1] + white[y + 1][x] + white[y + 1][x + 1]);
            }
        }
        addStar(image, 200.3, 199.6, 200_000, FWHM / 2.355);
        ApertureMeasurer.FrameMeasurement m = measure(image, new PhotometryStarSelector.Star(200.3, 199.6, 200_000));
        double small = m.skyNoiseFactor[ApertureMeasurer.R_CI_INNER];
        double main = m.skyNoiseFactor[ApertureMeasurer.R_MAIN];
        double large = m.skyNoiseFactor[ApertureMeasurer.R_LARGE];
        assertTrue("large " + large, large > 1.6 && large < 2.2);
        assertTrue("factors grow with the aperture: " + small + " " + main + " " + large, small < main && main <= large + 0.02);
        assertTrue(small > 1.3);

        double formal = m.magError[ApertureMeasurer.R_MAIN][0];
        assertTrue(formal > 0);
        assertEquals(formal * main, m.reportedMagError(ApertureMeasurer.R_MAIN, 0), 1e-12);
    }

    @Test
    public void aFrameWithTooLittleBlankSkyHasNoFactor() {
        // Every grid position that fits in this frame has the star inside its largest aperture.
        double[][] image = noise(34, 34, 20, 9);
        addStar(image, 17.2, 16.7, 50_000, FWHM / 2.355);
        ApertureMeasurer.FrameMeasurement m = measure(image, new PhotometryStarSelector.Star(17.2, 16.7, 50_000));
        assertTrue(m.blankApertures < ApertureMeasurer.MIN_BLANK_APERTURES);
        assertTrue(Double.isNaN(m.skyNoiseFactor[ApertureMeasurer.R_MAIN]));
        assertEquals(m.magError[ApertureMeasurer.R_MAIN][0], m.reportedMagError(ApertureMeasurer.R_MAIN, 0), 0);
    }

    @Test
    public void framesWithoutAFactorUseTheSessionMedian() {
        ApertureMeasurer.FrameMeasurement[] frames = new ApertureMeasurer.FrameMeasurement[4];
        PipelineTelemetry.PhotometryFrameStat[] stats = new PipelineTelemetry.PhotometryFrameStat[4];
        double[] mainFactors = {1.5, Double.NaN, 2.0, 1.8};
        for (int j = 0; j < 4; j++) {
            frames[j] = new ApertureMeasurer.FrameMeasurement(FWHM, 1);
            stats[j] = new PipelineTelemetry.PhotometryFrameStat();
            for (int r = 0; r < ApertureMeasurer.RADIUS_COUNT; r++) {
                frames[j].skyNoiseFactor[r] = mainFactors[j] * (1 + 0.1 * r);
            }
        }
        PipelineTelemetry.PhotometryTelemetry telemetry = new PipelineTelemetry.PhotometryTelemetry();
        VariableStarAnalyzer.resolveSkyNoiseFactors(frames, stats, telemetry);

        assertEquals(1.8 * 1.2, frames[1].skyNoiseFactor[ApertureMeasurer.R_MAIN], 1e-12);
        assertEquals(1.8 * 1.4, frames[1].skyNoiseFactor[ApertureMeasurer.R_CI_OUTER], 1e-12);
        assertTrue("the frame keeps no measured value", Double.isNaN(stats[1].skyNoiseFactor));
        assertEquals(1.5 * 1.2, stats[0].skyNoiseFactor, 1e-12);
        assertEquals(1, telemetry.framesWithoutSkyNoiseFactor);
        assertEquals(1.8 * 1.2, telemetry.skyNoiseFactorMedian, 1e-12);
        assertEquals(1.5 * 1.2, telemetry.skyNoiseFactorMin, 1e-12);
        assertEquals(2.0 * 1.2, telemetry.skyNoiseFactorMax, 1e-12);
    }

    private static ApertureMeasurer.FrameMeasurement measure(double[][] image, PhotometryStarSelector.Star star) {
        return ApertureMeasurer.measure(toPixels(image), FWHM, List.of(star), null, 70_000, new DetectionConfig());
    }

    private static double[][] noise(int width, int height, double sigma, long seed) {
        Random random = new Random(seed);
        double[][] image = new double[height][width];
        for (double[] row : image) {
            for (int x = 0; x < width; x++) {
                row[x] = SKY + sigma * random.nextGaussian();
            }
        }
        return image;
    }

    private static void addStar(double[][] image, double cx, double cy, double flux, double sigma) {
        for (int y = 0; y < image.length; y++) {
            for (int x = 0; x < image[0].length; x++) {
                double d2 = (x - cx) * (x - cx) + (y - cy) * (y - cy);
                if (d2 < 400) {
                    image[y][x] += flux / (2 * Math.PI * sigma * sigma) * Math.exp(-d2 / (2 * sigma * sigma));
                }
            }
        }
    }

    private static short[][] toPixels(double[][] image) {
        short[][] pixels = new short[image.length][image[0].length];
        for (int y = 0; y < image.length; y++) {
            for (int x = 0; x < image[0].length; x++) {
                pixels[y][x] = PixelEncoding.fromShiftedPositiveInt((int) Math.max(0, Math.min(65535, Math.round(image[y][x]))));
            }
        }
        return pixels;
    }
}
