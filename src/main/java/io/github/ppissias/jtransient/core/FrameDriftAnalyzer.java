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

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Measures per-frame border padding caused by alignment drift and recommends a safe void radius.
 */
public final class FrameDriftAnalyzer {
    /** Pixels at the signed-short floor are treated as synthetic alignment padding, not real image data. */
    private static final int DRIFT_VALID_PIXEL_THRESHOLD = Short.MIN_VALUE + 8;
    private static final int VOID_RADIUS_SAFETY_PIXELS = 10;

    private FrameDriftAnalyzer() {
    }

    /**
     * Immutable result of the drift-analysis pre-pass.
     */
    public static final class DriftAnalysisResult {
        public final List<SourceExtractor.Pixel> driftPoints;
        public final int maxPaddingPixels;
        public final int recommendedVoidProximityRadius;

        public DriftAnalysisResult(List<SourceExtractor.Pixel> driftPoints,
                                   int maxPaddingPixels,
                                   int recommendedVoidProximityRadius) {
            this.driftPoints = Collections.unmodifiableList(new ArrayList<>(driftPoints));
            this.maxPaddingPixels = maxPaddingPixels;
            this.recommendedVoidProximityRadius = recommendedVoidProximityRadius;
        }
    }

    /**
     * Measures drift across the supplied frames and returns a safe void-radius recommendation.
     */
    public static DriftAnalysisResult analyze(List<ImageFrame> inputFrames, int currentVoidProximityRadius) {
        if (inputFrames == null || inputFrames.isEmpty()) {
            return new DriftAnalysisResult(Collections.emptyList(), 0, currentVoidProximityRadius);
        }

        int maxDrift = 0;
        List<SourceExtractor.Pixel> driftPoints = new ArrayList<>();
        List<ImageFrame> sortedFrames = new ArrayList<>(inputFrames);
        sortedFrames.sort(Comparator.comparingInt(frame -> frame.sequenceIndex));

        for (ImageFrame frame : sortedFrames) {
            FrameDriftMeasurement measurement = measureFrameDrift(frame.pixelData);
            driftPoints.add(new SourceExtractor.Pixel(measurement.dx, measurement.dy, frame.sequenceIndex));
            if (measurement.maxPadding() > maxDrift) {
                maxDrift = measurement.maxPadding();
            }
        }

        int recommendedVoidRadius = currentVoidProximityRadius;
        if (maxDrift > 0) {
            recommendedVoidRadius = Math.max(currentVoidProximityRadius, maxDrift + VOID_RADIUS_SAFETY_PIXELS);
        }

        return new DriftAnalysisResult(driftPoints, maxDrift, recommendedVoidRadius);
    }

    /**
     * Measures the true valid-image footprint for one aligned frame.
     * This is more robust than sampling only the central cross-section because dust lanes, dead rows,
     * or bright structures near the center cannot hide real edge padding.
     */
    private static FrameDriftMeasurement measureFrameDrift(short[][] frame) {
        int height = frame.length;
        int width = frame[0].length;
        int minX = 0;
        int maxX = width - 1;
        int minY = 0;
        int maxY = height - 1;

        // Require at least 5% of the line to contain valid pixels before treating it as real image content.
        int minValidPixelsPerColumn = height / 20;
        int minValidPixelsPerRow = width / 20;

        for (int y = 0; y < height; y++) {
            int validCount = 0;
            for (int x = 0; x < width; x++) {
                if (frame[y][x] > DRIFT_VALID_PIXEL_THRESHOLD) {
                    validCount++;
                }
            }
            if (validCount > minValidPixelsPerRow) {
                minY = y;
                break;
            }
        }

        for (int y = height - 1; y >= 0; y--) {
            int validCount = 0;
            for (int x = 0; x < width; x++) {
                if (frame[y][x] > DRIFT_VALID_PIXEL_THRESHOLD) {
                    validCount++;
                }
            }
            if (validCount > minValidPixelsPerRow) {
                maxY = y;
                break;
            }
        }

        for (int x = 0; x < width; x++) {
            int validCount = 0;
            for (int y = 0; y < height; y++) {
                if (frame[y][x] > DRIFT_VALID_PIXEL_THRESHOLD) {
                    validCount++;
                }
            }
            if (validCount > minValidPixelsPerColumn) {
                minX = x;
                break;
            }
        }

        for (int x = width - 1; x >= 0; x--) {
            int validCount = 0;
            for (int y = 0; y < height; y++) {
                if (frame[y][x] > DRIFT_VALID_PIXEL_THRESHOLD) {
                    validCount++;
                }
            }
            if (validCount > minValidPixelsPerColumn) {
                maxX = x;
                break;
            }
        }

        int leftPadding = minX;
        int rightPadding = (width - 1) - maxX;
        int topPadding = minY;
        int bottomPadding = (height - 1) - maxY;
        return new FrameDriftMeasurement(leftPadding, rightPadding, topPadding, bottomPadding);
    }

    /**
     * Frame-edge padding measurement used to recover the true per-frame translation vector.
     */
    private static final class FrameDriftMeasurement {
        private final int leftPadding;
        private final int rightPadding;
        private final int topPadding;
        private final int bottomPadding;
        private final int dx;
        private final int dy;

        private FrameDriftMeasurement(int leftPadding, int rightPadding, int topPadding, int bottomPadding) {
            this.leftPadding = leftPadding;
            this.rightPadding = rightPadding;
            this.topPadding = topPadding;
            this.bottomPadding = bottomPadding;
            this.dx = leftPadding - rightPadding;
            this.dy = topPadding - bottomPadding;
        }

        private int maxPadding() {
            return Math.max(Math.max(leftPadding, rightPadding), Math.max(topPadding, bottomPadding));
        }
    }
}
