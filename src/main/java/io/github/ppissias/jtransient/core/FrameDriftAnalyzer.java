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
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Measures per-frame border padding caused by alignment drift (the drift and dither path), leaves out blank or
 * failed registrations, and records where each usable frame holds image data ({@link FrameCoverage}), from which
 * the engine builds the session's {@link DataArea}.
 *
 * <p>The padding depth is measured on every row (left and right edges) and every column (top and bottom
 * edges), using only the central half of each edge so that the padding of the neighbouring edges does not
 * leak in at the corners. The median depth of an edge is its depth at the middle of the edge, so frames that
 * registration also rotated (wedge-shaped padding) still give the right translation, and a few stray valid
 * pixels in the padding do not change the result.</p>
 */
public final class FrameDriftAnalyzer {
    /** Pixels at the signed-short floor are treated as synthetic alignment padding, not real image data. */
    private static final int DRIFT_VALID_PIXEL_THRESHOLD = DataArea.FILL_THRESHOLD;
    /**
     * A frame with less than this fraction of valid pixels is blank or a failed registration. It is left out
     * of the drift trajectory and of the data area (otherwise one such frame would shrink the area to almost
     * nothing, or add a false jump to the trajectory).
     */
    private static final double MIN_VALID_AREA_FRACTION = 0.5;

    private FrameDriftAnalyzer() {
    }

    /**
     * Immutable result of the drift-analysis pre-pass.
     */
    public static final class DriftAnalysisResult {
        /** Translation (dx, dy) of every usable frame; {@code value} holds the frame's sequence index. */
        public final List<SourceExtractor.Pixel> driftPoints;
        /** Deepest padding of any usable frame, measured in the central half of each edge, in pixels. */
        public final int maxPaddingPixels;
        /** Sequence indices of blank or failed-registration frames (left out of the drift analysis). */
        public final List<Integer> failedRegistrationFrames;
        /** Where each usable frame holds image data, in sequence order (failed registrations are left out). */
        public final List<FrameCoverage> frameCoverages;

        public DriftAnalysisResult(List<SourceExtractor.Pixel> driftPoints,
                                   int maxPaddingPixels,
                                   List<Integer> failedRegistrationFrames,
                                   List<FrameCoverage> frameCoverages) {
            this.driftPoints = Collections.unmodifiableList(new ArrayList<>(driftPoints));
            this.maxPaddingPixels = maxPaddingPixels;
            this.failedRegistrationFrames = Collections.unmodifiableList(new ArrayList<>(failedRegistrationFrames));
            this.frameCoverages = Collections.unmodifiableList(new ArrayList<>(frameCoverages));
        }
    }

    /**
     * Measures the drift, the failed registrations and the coverage of the supplied frames.
     */
    public static DriftAnalysisResult analyze(List<ImageFrame> inputFrames) {
        if (inputFrames == null || inputFrames.isEmpty()) {
            return new DriftAnalysisResult(Collections.emptyList(), 0, Collections.emptyList(), Collections.emptyList());
        }

        int maxDrift = 0;
        List<SourceExtractor.Pixel> driftPoints = new ArrayList<>();
        List<Integer> failedRegistrationFrames = new ArrayList<>();
        List<FrameCoverage> frameCoverages = new ArrayList<>();
        List<ImageFrame> sortedFrames = new ArrayList<>(inputFrames);
        sortedFrames.sort(Comparator.comparingInt(frame -> frame.sequenceIndex));

        for (ImageFrame frame : sortedFrames) {
            FrameDriftMeasurement measurement = measureFrameDrift(frame.pixelData);
            if (measurement.validAreaFraction < MIN_VALID_AREA_FRACTION) {
                failedRegistrationFrames.add(frame.sequenceIndex);
                continue;
            }
            driftPoints.add(new SourceExtractor.Pixel(measurement.dx, measurement.dy, frame.sequenceIndex));
            frameCoverages.add(FrameCoverage.measure(frame.sequenceIndex, frame.pixelData));
            if (measurement.maxPaddingExtent > maxDrift) {
                maxDrift = measurement.maxPaddingExtent;
            }
        }

        return new DriftAnalysisResult(driftPoints, maxDrift, failedRegistrationFrames, frameCoverages);
    }

    /**
     * Returns whether a frame is blank or a failed registration: less than half of its pixels hold image data.
     */
    public static boolean isBlankOrFailedRegistration(short[][] frame) {
        long valid = 0;
        for (short[] row : frame) {
            for (short value : row) {
                if (value > DRIFT_VALID_PIXEL_THRESHOLD) {
                    valid++;
                }
            }
        }
        return valid < MIN_VALID_AREA_FRACTION * frame.length * (double) frame[0].length;
    }

    /**
     * Measures the padding depth from every edge of one aligned frame.
     */
    private static FrameDriftMeasurement measureFrameDrift(short[][] frame) {
        int height = frame.length;
        int width = frame[0].length;
        long validPixels = 0;
        int[] left = new int[height];
        int[] right = new int[height];
        int rows = 0;
        for (int y = 0; y < height; y++) {
            short[] row = frame[y];
            int rowValid = 0;
            for (int x = 0; x < width; x++) {
                if (row[x] > DRIFT_VALID_PIXEL_THRESHOLD) {
                    rowValid++;
                }
            }
            validPixels += rowValid;
            if (rowValid == 0 || y < height / 4 || y >= height - height / 4) {
                continue; // fully padded row (top or bottom padding), or outside the central band
            }
            int l = 0;
            while (row[l] <= DRIFT_VALID_PIXEL_THRESHOLD) {
                l++;
            }
            int r = 0;
            while (row[width - 1 - r] <= DRIFT_VALID_PIXEL_THRESHOLD) {
                r++;
            }
            left[rows] = l;
            right[rows] = r;
            rows++;
        }

        int[] top = new int[width];
        int[] bottom = new int[width];
        int columns = 0;
        for (int x = width / 4; x < width - width / 4; x++) {
            int t = 0;
            while (t < height && frame[t][x] <= DRIFT_VALID_PIXEL_THRESHOLD) {
                t++;
            }
            if (t == height) {
                continue; // fully padded column: it belongs to the left or right padding
            }
            int b = 0;
            while (frame[height - 1 - b][x] <= DRIFT_VALID_PIXEL_THRESHOLD) {
                b++;
            }
            top[columns] = t;
            bottom[columns] = b;
            columns++;
        }

        double validAreaFraction = validPixels / ((double) width * height);
        if (rows == 0 || columns == 0) {
            return new FrameDriftMeasurement(0, 0, 0, validAreaFraction);
        }
        left = Arrays.copyOf(left, rows);
        right = Arrays.copyOf(right, rows);
        top = Arrays.copyOf(top, columns);
        bottom = Arrays.copyOf(bottom, columns);
        Arrays.sort(left);
        Arrays.sort(right);
        Arrays.sort(top);
        Arrays.sort(bottom);

        int dx = percentile(left, 0.5) - percentile(right, 0.5);
        int dy = percentile(top, 0.5) - percentile(bottom, 0.5);
        int extent = Math.max(Math.max(left[rows - 1], right[rows - 1]), Math.max(top[columns - 1], bottom[columns - 1]));
        return new FrameDriftMeasurement(dx, dy, extent, validAreaFraction);
    }

    private static int percentile(int[] sorted, double fraction) {
        return sorted[Math.min(sorted.length - 1, (int) Math.floor(fraction * (sorted.length - 1) + 0.5))];
    }

    /**
     * Frame-edge padding measurement used to recover the per-frame translation vector.
     */
    private static final class FrameDriftMeasurement {
        private final int dx;
        private final int dy;
        /** Deepest edge padding within the central bands. */
        private final int maxPaddingExtent;
        private final double validAreaFraction;

        private FrameDriftMeasurement(int dx, int dy, int maxPaddingExtent, double validAreaFraction) {
            this.dx = dx;
            this.dy = dy;
            this.maxPaddingExtent = maxPaddingExtent;
            this.validAreaFraction = validAreaFraction;
        }
    }
}
