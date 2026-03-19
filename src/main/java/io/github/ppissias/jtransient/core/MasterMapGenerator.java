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
            int[] pixelValues = new int[numFrames];
            for (int x = 0; x < width; x++) {
                // Collect this specific pixel's value from every frame
                for (int i = 0; i < numFrames; i++) {
                    // Shift to a positive 32-bit int domain (matching SourceExtractor logic)
                    // This makes the sort immune to Java signed-short wrap-around bugs from custom FITS files.
                    pixelValues[i] = frames.get(i).pixelData[y][x] + 32768;
                }

                // Sort the perfectly continuous positive integers
                Arrays.sort(pixelValues);
                
                // Use safe lower-median bias and shift back to the original signed short domain
                int safeMedianIndex = (numFrames - 1) / 2;
                masterMap[y][x] = (short) (pixelValues[safeMedianIndex] - 32768);
            }
        });

        if (JTransientEngine.DEBUG) {
            System.out.println("  -> Master Stack generation complete.");
        }

        return masterMap;
    }
}