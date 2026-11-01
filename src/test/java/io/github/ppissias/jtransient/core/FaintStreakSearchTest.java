/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 * author: Petros Pissias <petrospis at gmail.com>
 *
 */
package io.github.ppissias.jtransient.core;

import io.github.ppissias.jtransient.config.DetectionConfig;
import io.github.ppissias.jtransient.quality.FrameQualityAnalyzer;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The faint-streak search's smoothing width and smoothing: the width comes only from genuine star-size measurements
 * and stays bounded, and the smoothing never mixes registration fill into the image.
 */
public class FaintStreakSearchTest {

    @Test
    public void widthUsesOnlyGenuineStarSizes() {
        DetectionConfig config = new DetectionConfig();
        List<FrameQualityAnalyzer.FrameMetrics> metrics = new ArrayList<>();
        metrics.add(frame(3.33, 40));
        metrics.add(frame(config.errorFallbackValue, 0));
        metrics.add(frame(3.33, 40));
        metrics.add(frame(Double.NaN, 40));
        metrics.add(frame(9.0, 3));
        FaintStreakSearch.Width width = FaintStreakSearch.width(metrics, config);
        assertEquals(2, width.frames);
        assertEquals(3.33 / Math.sqrt(2) / 2.3548, width.sigma, 1e-3);
    }

    @Test
    public void widthFallsBackWithoutAnyMeasurement() {
        DetectionConfig config = new DetectionConfig();
        List<FrameQualityAnalyzer.FrameMetrics> metrics = List.of(frame(config.errorFallbackValue, 0),
                frame(config.errorFallbackValue, 0));
        FaintStreakSearch.Width width = FaintStreakSearch.width(metrics, config);
        assertEquals(0, width.frames);
        assertEquals(FaintStreakSearch.FALLBACK_SIGMA, width.sigma, 0.0);
    }

    @Test
    public void widthStaysWithinItsBounds() {
        DetectionConfig config = new DetectionConfig();
        assertEquals(FaintStreakSearch.MAX_SIGMA, FaintStreakSearch.width(List.of(frame(40.0, 50)), config).sigma, 0.0);
        assertEquals(FaintStreakSearch.MIN_SIGMA, FaintStreakSearch.width(List.of(frame(0.5, 50)), config).sigma, 0.0);
    }

    @Test
    public void smoothingKeepsFillAndAFlatSkyFlat() {
        short[][] image = new short[60][80];
        for (int y = 0; y < 60; y++) {
            for (int x = 0; x < 80; x++) {
                image[y][x] = (short) (x < 10 || y > 50 ? Short.MIN_VALUE : 1000 - 32768);
            }
        }
        short[][] smoothed = FaintStreakSearch.smooth(image, FaintStreakSearch.kernel(1.5), 1.0, 0.0);
        for (int y = 0; y < 60; y++) {
            for (int x = 0; x < 80; x++) {
                assertEquals("pixel " + x + "," + y, image[y][x], smoothed[y][x]);
            }
        }
    }

    @Test
    public void smoothingLowersNoiseByTheKernelFactorAndTheStretchKeepsTheSky() {
        Random random = new Random(7);
        short[][] image = new short[300][300];
        for (short[] row : image) {
            for (int x = 0; x < row.length; x++) {
                row[x] = (short) Math.round(1000 - 32768 + random.nextGaussian() * 20);
            }
        }
        float[] kernel = FaintStreakSearch.kernel(1.25);
        double expected = 20 * FaintStreakSearch.noiseFactor(kernel);
        assertEquals(expected, sigma(FaintStreakSearch.smooth(image, kernel, 1.0, 0.0)), 0.1 * expected);
        short[][] stretched = FaintStreakSearch.smooth(image, kernel, 3.0, 1000 - 32768);
        assertEquals(3 * expected, sigma(stretched), 0.3 * expected);
        assertEquals(1000 - 32768, mean(stretched), 0.5);
    }

    @Test
    public void insetShrinksTheAreaFromEverySide() {
        DataArea area = DataArea.fullFrame(40, 30);
        DataArea inset = area.inset(4);
        assertTrue(area.contains(3, 15));
        assertTrue(!inset.contains(6, 15) && inset.contains(7, 15));
        assertTrue(!inset.contains(20, 6) && inset.contains(20, 7));
        assertTrue(inset.contains(32, 22) && !inset.contains(33, 22) && !inset.contains(32, 23));
        assertTrue(area == area.inset(0));
    }

    private static FrameQualityAnalyzer.FrameMetrics frame(double fwhm, int stars) {
        FrameQualityAnalyzer.FrameMetrics metrics = new FrameQualityAnalyzer.FrameMetrics();
        metrics.medianFWHM = fwhm;
        metrics.fwhmStarCount = stars;
        return metrics;
    }

    private static double mean(short[][] image) {
        double sum = 0;
        long n = 0;
        for (int y = 20; y < image.length - 20; y++) {
            for (int x = 20; x < image[y].length - 20; x++) {
                sum += image[y][x];
                n++;
            }
        }
        return sum / n;
    }

    private static double sigma(short[][] image) {
        double mean = mean(image);
        double sum = 0;
        long n = 0;
        for (int y = 20; y < image.length - 20; y++) {
            for (int x = 20; x < image[y].length - 20; x++) {
                sum += (image[y][x] - mean) * (image[y][x] - mean);
                n++;
            }
        }
        return Math.sqrt(sum / n);
    }
}
