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

    /**
     * Generates a special Master Stack specifically designed to capture ultra-slow moving objects (like asteroids).
     * Instead of a pure median (which erases movers), this takes the maximum value of the middle X% of the sorted pixels.
     * This perfectly captures objects that persist in a small area for a few frames, while still rejecting fast
     * single-frame anomalies (which are pushed to the extreme high end of the sort).
     */
    public static short[][] createSlowMoverMasterStack(List<ImageFrame> frames, double middleFraction) {
        if (frames == null || frames.isEmpty()) return null;

        int height = frames.get(0).pixelData.length;
        int width = frames.get(0).pixelData[0].length;
        int numFrames = frames.size();

        short[][] masterMap = new short[height][width];

        // Calculate the exact target index once!
        // Because the array is perfectly sorted, the "maximum of the middle fraction" is simply the upper bound index of that fraction.
        int bandSize = (int) Math.round(numFrames * middleFraction);
        int targetIndex = Math.min(numFrames - 1, ((numFrames - 1) / 2) + (bandSize / 2));

        if (JTransientEngine.DEBUG) {
            System.out.printf("\n[PHASE 0.5] Generating Slow-Mover Master Stack... (Extracting index %d of %d)%n", targetIndex, numFrames - 1);
        }

        IntStream.range(0, height).parallel().forEach(y -> {
            int[] pixelValues = new int[numFrames];
            for (int x = 0; x < width; x++) {
                for (int i = 0; i < numFrames; i++) {
                    pixelValues[i] = frames.get(i).pixelData[y][x] + 32768;
                }

                Arrays.sort(pixelValues);
                
                // Extract the pre-calculated target index
                masterMap[y][x] = (short) (pixelValues[targetIndex] - 32768);
            }
        });

        if (JTransientEngine.DEBUG) {
            System.out.println("  -> Slow-Mover Stack generation complete.");
        }

        return masterMap;
    }
}