package io.github.ppissias.jtransient.core;

import io.github.ppissias.jtransient.config.DetectionConfig;
import io.github.ppissias.jtransient.engine.ImageFrame;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Measures source persistence and positional clustering within one maximum-stack footprint.
 *
 * <p>For each usable original frame, a small aperture searches the candidate's raw-pixel
 * footprint for local-background-subtracted signal. Frame support is the percentage of usable
 * frames whose best aperture reaches the integrated signal-to-noise floor. Stationary likelihood
 * is the percentage of supported centroids in the largest cluster around one observed position.
 * These are screening heuristics, not a calibrated probability or proof of motion. The search
 * accepts any localized signal inside the footprint, so a persistent star there may dominate
 * the frame positions. This class does not build temporal stacks or fit a motion path.</p>
 *
 * <p>Frame support can have a numeric value with only one usable frame but is unavailable until
 * at least two frames are usable. Stationary likelihood is zero when unavailable. Callers must
 * check each availability flag before interpreting or applying a score.</p>
 */
final class SlowMoverFrameEvidence {
    /** Fixed integrated signal-to-noise floor for one frame to support a candidate. */
    private static final double MIN_APERTURE_SNR = 5.0;

    /** Usable frames whose strongest candidate-local aperture passes the signal floor. */
    final int supportedFrameCount;
    /** Frames containing the footprint, enough local sky samples, and no mostly void footprint. */
    final int usableFrameCount;
    /** At least two usable frames are required for a meaningful support percentage. */
    final boolean frameSupportAvailable;
    /** At least three supported frames must span half the retained sequence. */
    final boolean stationaryLikelihoodAvailable;
    /** {@code 100 * supportedFrameCount / usableFrameCount}, or zero without usable frames. */
    final double frameSupportPercentage;
    /** Largest stationary centroid cluster as a percentage of supported frames, or zero if unavailable. */
    final double stationaryLikelihoodPercentage;

    /** Stores the measured counts and derives the frame-support percentage and availability. */
    private SlowMoverFrameEvidence(int supportedFrameCount, int usableFrameCount,
                                   boolean stationaryLikelihoodAvailable, double stationaryLikelihoodPercentage) {
        this.supportedFrameCount = supportedFrameCount;
        this.usableFrameCount = usableFrameCount;
        this.frameSupportAvailable = usableFrameCount >= 2;
        this.stationaryLikelihoodAvailable = stationaryLikelihoodAvailable;
        this.frameSupportPercentage = usableFrameCount > 0
                ? 100.0 * supportedFrameCount / usableFrameCount : 0.0;
        this.stationaryLikelihoodPercentage = stationaryLikelihoodPercentage;
    }

    /** Returns a result with no measurable frame evidence. */
    static SlowMoverFrameEvidence unavailable() {
        return new SlowMoverFrameEvidence(0, 0, false, 0.0);
    }

    /**
     * Measures a candidate against the aligned, quality-filtered frames that formed its maximum stack.
     *
     * <p>The search aperture radius is half the candidate's minor extent, limited to 1.5–4 pixels.
     * For each supported frame, positive flux inside its best aperture supplies a centroid. The
     * stationary score uses the largest group of centroids within a radius of one observed centroid;
     * that radius is the configured star jitter limited by half the minor extent, with a 0.75-pixel
     * floor. A stable star with one exceptional frame can therefore still score highly.</p>
     *
     * @param object maximum-stack object whose raw pixels constrain each frame search
     * @param frames original aligned frames retained by quality filtering, in sequence order
     * @param config void threshold and expected star-jitter scale
     * @return frame support and stationary-likelihood measurements with availability flags
     */
    static SlowMoverFrameEvidence measure(SourceExtractor.DetectedObject object,
                                          List<ImageFrame> frames,
                                          DetectionConfig config) {
        if (frames == null || frames.isEmpty() || object.rawPixels == null || object.rawPixels.isEmpty()) {
            return unavailable();
        }

        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        for (SourceExtractor.Pixel pixel : object.rawPixels) {
            minX = Math.min(minX, pixel.x);
            maxX = Math.max(maxX, pixel.x);
            minY = Math.min(minY, pixel.y);
            maxY = Math.max(maxY, pixel.y);
        }

        double apertureRadius = Math.max(1.5, Math.min(4.0, object.minorExtent / 2.0));
        int innerMargin = (int) Math.ceil(apertureRadius) + 1;
        int outerMargin = innerMargin + Math.max(4, (int) Math.ceil(object.minorExtent));
        List<FrameMeasurement> supported = new ArrayList<>();
        int usable = 0;

        for (int frameIndex = 0; frameIndex < frames.size(); frameIndex++) {
            ImageFrame frame = frames.get(frameIndex);
            if (frame == null || frame.pixelData == null || frame.pixelData.length == 0
                    || frame.pixelData[0].length == 0 || minX < 0 || minY < 0
                    || maxY >= frame.pixelData.length || maxX >= frame.pixelData[0].length) {
                continue;
            }

            LocalBackground background = estimateBackground(frame.pixelData, minX, maxX, minY, maxY,
                    innerMargin, outerMargin);
            if (background == null || isMostlyVoid(frame.pixelData, object, background.median,
                    config.voidThresholdFraction)) {
                continue;
            }

            usable++;
            FrameMeasurement measurement = findBestAperture(frame.pixelData, object.rawPixels,
                    apertureRadius, background, frameIndex);
            if (measurement != null && measurement.signalToNoise >= MIN_APERTURE_SNR) {
                supported.add(measurement);
            }
        }

        boolean stationaryAvailable = supported.size() >= 3
                && supported.get(supported.size() - 1).frameIndex - supported.get(0).frameIndex
                >= (frames.size() - 1) / 2.0;
        double stationaryLikelihood = 0.0;
        if (stationaryAvailable) {
            double clusteringRadius = Math.max(0.75,
                    Math.min(Math.max(0.0, config.maxStarJitter), object.minorExtent / 2.0));
            int largestStationaryCluster = 0;
            for (FrameMeasurement center : supported) {
                int clusterSize = 0;
                for (FrameMeasurement measurement : supported) {
                    if (Math.hypot(center.x - measurement.x, center.y - measurement.y) <= clusteringRadius) {
                        clusterSize++;
                    }
                }
                largestStationaryCluster = Math.max(largestStationaryCluster, clusterSize);
            }
            stationaryLikelihood = 100.0 * largestStationaryCluster / supported.size();
        }

        return new SlowMoverFrameEvidence(supported.size(), usable,
                stationaryAvailable, stationaryLikelihood);
    }

    /**
     * Samples a local ring outside the candidate bounds and an inner exclusion margin.
     * Uses the ring median as sky level and scaled median absolute deviation as noise, with
     * a standard-deviation fallback and a one-ADU floor. Fewer than 16 samples make the
     * frame unusable for this candidate.
     */
    private static LocalBackground estimateBackground(short[][] image, int minX, int maxX, int minY, int maxY,
                                                       int innerMargin, int outerMargin) {
        int left = Math.max(0, minX - outerMargin);
        int right = Math.min(image[0].length - 1, maxX + outerMargin);
        int top = Math.max(0, minY - outerMargin);
        int bottom = Math.min(image.length - 1, maxY + outerMargin);
        int innerLeft = minX - innerMargin;
        int innerRight = maxX + innerMargin;
        int innerTop = minY - innerMargin;
        int innerBottom = maxY + innerMargin;
        int innerWidth = Math.max(0, Math.min(right, innerRight) - Math.max(left, innerLeft) + 1);
        int innerHeight = Math.max(0, Math.min(bottom, innerBottom) - Math.max(top, innerTop) + 1);
        int outerArea = (right - left + 1) * (bottom - top + 1);
        int[] values = new int[outerArea - innerWidth * innerHeight];
        int count = 0;
        for (int y = top; y <= bottom; y++) {
            for (int x = left; x <= right; x++) {
                if (x >= innerLeft && x <= innerRight && y >= innerTop && y <= innerBottom) {
                    continue;
                }
                values[count++] = PixelEncoding.toShiftedPositiveInt(image[y][x]);
            }
        }
        if (count < 16) {
            return null;
        }

        Arrays.sort(values, 0, count);
        double median = median(values, count);
        int[] deviations = new int[count];
        double squaredDeviationSum = 0.0;
        for (int index = 0; index < count; index++) {
            double deviation = values[index] - median;
            deviations[index] = (int) Math.round(Math.abs(deviation));
            squaredDeviationSum += deviation * deviation;
        }
        Arrays.sort(deviations);
        double sigma = Math.max(1.0, 1.4826 * median(deviations, count));
        if (sigma == 1.0) {
            sigma = Math.max(1.0, Math.sqrt(squaredDeviationSum / count));
        }
        return new LocalBackground(median, sigma);
    }

    /** Returns the median of the first {@code count} sorted integer samples. */
    private static double median(int[] sortedValues, int count) {
        return count % 2 == 0
                ? (sortedValues[count / 2 - 1] + sortedValues[count / 2]) / 2.0
                : sortedValues[count / 2];
    }

    /** Excludes a frame when at least half the candidate footprint lies in registration padding. */
    private static boolean isMostlyVoid(short[][] image, SourceExtractor.DetectedObject object,
                                        double background, double voidThresholdFraction) {
        int voidPixels = 0;
        for (SourceExtractor.Pixel pixel : object.rawPixels) {
            if (PixelEncoding.toShiftedPositiveInt(image[pixel.y][pixel.x])
                    <= background * voidThresholdFraction) {
                voidPixels++;
            }
        }
        return voidPixels * 2 >= object.rawPixels.size();
    }

    /**
     * Tries an aperture at every maximum-stack raw pixel and keeps the highest integrated SNR.
     * Signed background-subtracted flux determines SNR; positive excess alone weights the
     * centroid, so a negative noise pixel does not pull the reported position away from the source.
     */
    private static FrameMeasurement findBestAperture(short[][] image, List<SourceExtractor.Pixel> footprint,
                                                      double radius, LocalBackground background, int frameIndex) {
        int searchRadius = (int) Math.ceil(radius);
        double radiusSquared = radius * radius;
        double bestSignalToNoise = Double.NEGATIVE_INFINITY;
        FrameMeasurement best = null;
        for (SourceExtractor.Pixel center : footprint) {
            double signal = 0.0;
            double weightedX = 0.0;
            double weightedY = 0.0;
            double positiveSignal = 0.0;
            int count = 0;
            for (int y = Math.max(0, center.y - searchRadius);
                 y <= Math.min(image.length - 1, center.y + searchRadius); y++) {
                for (int x = Math.max(0, center.x - searchRadius);
                     x <= Math.min(image[0].length - 1, center.x + searchRadius); x++) {
                    int deltaX = x - center.x;
                    int deltaY = y - center.y;
                    if (deltaX * deltaX + deltaY * deltaY > radiusSquared) {
                        continue;
                    }
                    double excess = PixelEncoding.toShiftedPositiveInt(image[y][x]) - background.median;
                    signal += excess;
                    if (excess > 0.0) {
                        positiveSignal += excess;
                        weightedX += x * excess;
                        weightedY += y * excess;
                    }
                    count++;
                }
            }
            if (count == 0 || positiveSignal <= 0.0) {
                continue;
            }
            double signalToNoise = signal / (background.sigma * Math.sqrt(count));
            if (signalToNoise > bestSignalToNoise) {
                bestSignalToNoise = signalToNoise;
                best = new FrameMeasurement(frameIndex, weightedX / positiveSignal,
                        weightedY / positiveSignal, signalToNoise);
            }
        }
        return best;
    }

    /** Local sky level and noise in shifted-positive pixel units. */
    private static final class LocalBackground {
        private final double median;
        private final double sigma;

        private LocalBackground(double median, double sigma) {
            this.median = median;
            this.sigma = sigma;
        }
    }

    /** Best candidate-local aperture position and signal strength in one original frame. */
    private static final class FrameMeasurement {
        private final int frameIndex;
        private final double x;
        private final double y;
        private final double signalToNoise;

        private FrameMeasurement(int frameIndex, double x, double y, double signalToNoise) {
            this.frameIndex = frameIndex;
            this.x = x;
            this.y = y;
            this.signalToNoise = signalToNoise;
        }
    }
}
