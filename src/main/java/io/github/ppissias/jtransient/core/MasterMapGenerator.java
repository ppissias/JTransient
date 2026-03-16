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

import io.github.ppissias.jtransient.engine.ImageFrame;
import io.github.ppissias.jtransient.engine.JTransientEngine;

import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;

public class MasterMapGenerator {

    /**
     * Generates a Deep Median Master Stack from the sequence to erase transients and perfectly isolate the star map.
     * Uses parallel CPU streams to perform millions of array sorts instantly.
     */
    public static short[][] createMedianMasterStack(List<ImageFrame> frames) {
        if (frames == null || frames.isEmpty()) return null;

        int height = frames.get(0).pixelData.length;
        int width = frames.get(0).pixelData[0].length;
        int numFrames = frames.size();

        short[][] masterMap = new short[height][width];

        if (JTransientEngine.DEBUG) {
            System.out.println("\n[PHASE 0] Generating Median Master Stack across " + numFrames + " frames...");
        }

        // Multi-thread the row processing for maximum speed!
        IntStream.range(0, height).parallel().forEach(y -> {
            short[] pixelValues = new short[numFrames];
            for (int x = 0; x < width; x++) {
                // Collect this specific pixel's value from every frame
                for (int i = 0; i < numFrames; i++) {
                    pixelValues[i] = frames.get(i).pixelData[y][x];
                }

                // Sort to find the mathematical median
                Arrays.sort(pixelValues);
                masterMap[y][x] = pixelValues[numFrames / 2];
            }
        });

        if (JTransientEngine.DEBUG) {
            System.out.println("  -> Master Stack generation complete.");
        }

        return masterMap;
    }
}