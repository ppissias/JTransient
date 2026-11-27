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
import io.github.ppissias.jtransient.engine.ImageFrame;
import io.github.ppissias.jtransient.quality.FrameQualityAnalyzer;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * A tile's noise is that of a stack inside the data, also at the image edge, where the sampled paths leave the data
 * and their frames there hold 0. Before, such a tile measured a lower noise and over-scored its peaks: on real sessions
 * a band of false candidates along the last, thin tile row.
 */
public class FaintMoverEdgeNoiseTest {
    private static final int FRAMES = 12;

    @Test
    public void theNoiseAtTheEdgeIsThatOfAStackInsideTheData() {
        FaintMoverSearch.Plan plan = plan();
        int n = plan.frames.size();
        int m = plan.margin;
        int w = 64;
        int h = 64;
        int width = w + 2 * m;
        int height = h + 2 * m;
        Random random = new Random(11);
        float[][] residuals = new float[n][width * height];
        for (float[] r : residuals) {
            for (int i = 0; i < r.length; i++) r[i] = (float) random.nextGaussian();
        }
        boolean[] innerBad = new boolean[w * h];
        double inside = FaintMoverSearch.stackNoise(plan, residuals, new boolean[width * height], width, w, h, innerBad);
        double expected = 1 / Math.sqrt(n);
        assertEquals("independent unit noise: the stack of n frames", expected, inside, 0.1 * expected);

        // The image ends in the middle of the tile, on two sides: pixels there are outside and hold 0.
        boolean[] outside = new boolean[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (x >= m + w / 2 || y >= m + h / 2) {
                    outside[y * width + x] = true;
                    for (float[] r : residuals) r[y * width + x] = 0f;
                }
            }
        }
        double edge = FaintMoverSearch.stackNoise(plan, residuals, outside, width, w, h, innerBad);
        assertEquals("the same noise at the edge", expected, edge, 0.1 * expected);
        double unscaled = FaintMoverSearch.stackNoise(plan, residuals, new boolean[width * height], width, w, h, innerBad);
        assertTrue("without the scaling the edge reads too low: " + unscaled, unscaled < 0.85 * expected);
    }

    /** A plan for twelve 1-minute frames over 55 minutes, searched to 2 px/min. */
    private static FaintMoverSearch.Plan plan() {
        List<ImageFrame> frames = new ArrayList<>();
        Random random = new Random(3);
        for (int k = 0; k < FRAMES; k++) {
            short[][] pixels = new short[128][128];
            for (int y = 0; y < 128; y++) {
                for (int x = 0; x < 128; x++) {
                    pixels[y][x] = PixelEncoding.fromShiftedPositiveInt((int) Math.round(1000 + 10 * random.nextGaussian()));
                }
            }
            frames.add(new ImageFrame(k, "f" + k, pixels, 1_700_000_000_000L + k * 300_000L, 60_000L));
        }
        List<FrameQualityAnalyzer.FrameMetrics> metrics = new ArrayList<>();
        for (int k = 0; k < FRAMES; k++) {
            FrameQualityAnalyzer.FrameMetrics frame = new FrameQualityAnalyzer.FrameMetrics();
            frame.starWidthFwhm = 3.0;
            frame.starWidthStars = 50;
            metrics.add(frame);
        }
        FaintMoverSearch.Input input = new FaintMoverSearch.Input();
        input.frames = frames;
        input.frameMetrics = metrics;
        input.masterStack = MasterMapGenerator.createMedianMasterStack(frames);
        DetectionConfig config = new DetectionConfig();
        config.faintMoverAutoMaxSpeed = false;
        config.faintMoverMaxSpeedPixelsPerMinute = 2.0;
        FaintMoverSearch.Plan plan = FaintMoverSearch.plan(input, config);
        assertEquals(null, plan.skippedReason);
        return plan;
    }
}
