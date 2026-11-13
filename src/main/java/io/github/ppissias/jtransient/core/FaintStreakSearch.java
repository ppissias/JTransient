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
import io.github.ppissias.jtransient.execution.CancellationScope;
import io.github.ppissias.jtransient.execution.WorkerPool;
import io.github.ppissias.jtransient.model.DetectedObject;
import io.github.ppissias.jtransient.model.Pixel;
import io.github.ppissias.jtransient.quality.FrameQualityAnalyzer;
import io.github.ppissias.jtransient.telemetry.FaintStreakSearchTelemetry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;

/**
 * The faint-streak search: a second, streak-only extraction of the kept frames on copies smoothed with a Gaussian the
 * size of the stars (a matched filter). A trail whose pixels are only 1-2 sigma above the sky never gives a seed in
 * the normal extraction, but the smoothing lowers the pixel noise more than the trail, so the normal extraction finds
 * it on the smoothed copy.
 *
 * <p>The search uses the normal pass's kept frames, sky areas and thresholds and does not change them; only its sky
 * clipping runs until settled ({@link #BACKGROUND_CLIPPING_ITERATIONS}). Only
 * streak-shaped objects ({@code streakMinElongation}, {@code streakMinPixels}) are kept, and only those whose
 * centroid lies inside the data area by more than the kernel radius, where the smoothing has full support. They are
 * vetoed against the stars of the median stack smoothed the same way, because smoothing reveals fainter stars and
 * joins close pairs. {@link FaintStreakLinking} then adds the candidates to the streak tracks. Every candidate is
 * marked {@link DetectedObject#fromFaintStreakSearch}; its measurements are those of the smoothed frame.</p>
 *
 * <p>Each worker smooths one frame in row bands and keeps only that frame's candidates, so no smoothed frame outlives
 * its extraction. The caller limits the workers.</p>
 */
public final class FaintStreakSearch {
    /** Smoothing sigma when no kept frame has a star-size measurement, in pixels. */
    public static final double FALLBACK_SIGMA = 1.0;
    /** Smallest smoothing sigma, in pixels. */
    public static final double MIN_SIGMA = 0.5;
    /** Largest smoothing sigma, in pixels. */
    public static final double MAX_SIGMA = 3.0;
    /** A frame's star size counts only when measured on at least this many stars. */
    static final int MIN_FWHM_STARS = 5;
    /** Smoothed-frame noise below which values are stretched about the sky before rounding, in ADU. */
    static final double MIN_SMOOTHED_NOISE_ADU = 4.0;
    /**
     * Sky clipping passes on a smoothed frame. Smoothing narrows the noise far more than the halos of the stars, so the
     * profile's few passes leave star light in the noise estimate (on a synthetic field, 3 passes gave twice the true
     * noise, 5 or more the true value); the search clips until the estimate has settled. The normal pass keeps
     * {@code bgClippingIterations}.
     */
    static final int BACKGROUND_CLIPPING_ITERATIONS = 20;
    /** Rows smoothed together; bounds a worker's float buffers to a band instead of a frame. */
    private static final int BAND_ROWS = 256;

    private FaintStreakSearch() {
    }

    /** The candidates of one run, by kept frame, with what the search did. */
    public static final class Result {
        /** Streak-shaped candidates that passed the star mask, one list per kept frame in kept-frame order. */
        public final List<List<DetectedObject>> candidates;
        /** The search's counts; {@link FaintStreakLinking} completes the linking counts. */
        public final FaintStreakSearchTelemetry telemetry;

        Result(List<List<DetectedObject>> candidates, FaintStreakSearchTelemetry telemetry) {
            this.candidates = candidates;
            this.telemetry = telemetry;
        }
    }

    /**
     * Runs the search over the kept frames.
     *
     * @param keptFrames the frames quality control kept, in sequence order; borrowed, not modified
     * @param keptMetrics their quality metrics, in the same order (for the star size)
     * @param keptSky their normal extraction's sky, in the same order (for the rounding stretch)
     * @param masterStack the median stack of the kept frames
     * @param backgroundArea where the sky is measured, as in the normal extraction
     * @param dataArea where every kept frame has data, or null for the whole frame
     * @param config the run's configuration; only read
     * @return the candidates and the search's counts
     * @throws ExecutionException when a frame's work fails
     */
    public static Result search(List<ImageFrame> keptFrames,
                                List<FrameQualityAnalyzer.FrameMetrics> keptMetrics,
                                List<SourceExtractor.BackgroundMetrics> keptSky,
                                short[][] masterStack,
                                DataArea backgroundArea,
                                DataArea dataArea,
                                DetectionConfig config) throws ExecutionException {
        FaintStreakSearchTelemetry telemetry = new FaintStreakSearchTelemetry();
        Width width = width(keptMetrics, config);
        DetectionConfig smoothedConfig = config.clone();
        smoothedConfig.bgClippingIterations = Math.max(config.bgClippingIterations, BACKGROUND_CLIPPING_ITERATIONS);
        telemetry.smoothingSigma = width.sigma;
        telemetry.widthFromStars = width.frames > 0;
        telemetry.widthFrames = width.frames;
        if (keptFrames.isEmpty()) {
            return new Result(Collections.emptyList(), telemetry);
        }

        float[] kernel = kernel(width.sigma);
        int radius = kernel.length / 2;
        int height = masterStack.length;
        int sensorWidth = masterStack[0].length;
        DataArea searchArea = (dataArea != null ? dataArea : DataArea.fullFrame(sensorWidth, height)).inset(radius);

        List<DetectedObject> smoothedStars = MasterReferenceAnalyzer.analyzeFromMasterStack(
                smooth(masterStack, kernel, 1.0, 0.0), smoothedConfig,
                dataArea != null ? dataArea.excludedRectangles() : java.util.List.of()).masterStars;
        boolean[][] starMask = MasterVetoMask.build(smoothedStars, sensorWidth, height, config.maxStarJitter);

        double noiseFactor = noiseFactor(kernel);
        List<Callable<List<DetectedObject>>> tasks = new ArrayList<>();
        int[] removedByMask = new int[keptFrames.size()];
        int[] extracted = new int[keptFrames.size()];
        for (int i = 0; i < keptFrames.size(); i++) {
            int index = i;
            ImageFrame frame = keptFrames.get(i);
            SourceExtractor.BackgroundMetrics sky = keptSky.get(i);
            tasks.add(() -> {
                double sky16 = sky != null ? sky.median - PixelEncoding.SHIFT : 0.0;
                double smoothedNoise = sky != null ? sky.sigma * noiseFactor : MIN_SMOOTHED_NOISE_ADU;
                double gain = smoothedNoise > 0 && smoothedNoise < MIN_SMOOTHED_NOISE_ADU
                        ? MIN_SMOOTHED_NOISE_ADU / smoothedNoise : 1.0;
                short[][] smoothed = smooth(frame.pixelData, kernel, gain, sky16);
                // Smaller blobs cannot be streaks; leaving them out spares their shape analysis.
                List<DetectedObject> objects = SourceExtractor.extractSources(smoothed, config.detectionSigmaMultiplier,
                        Math.max(config.minDetectionPixels, config.streakMinPixels), smoothedConfig, backgroundArea,
                        searchArea).objects;
                List<DetectedObject> kept = new ArrayList<>();
                for (DetectedObject object : objects) {
                    CancellationScope.check();
                    if (!object.isStreak) {
                        continue;
                    }
                    extracted[index]++;
                    if (maskOverlap(object, starMask) > config.maxMaskOverlapFraction) {
                        removedByMask[index]++;
                        continue;
                    }
                    if (gain != 1.0) {
                        unstretch(object, gain, sky16 + PixelEncoding.SHIFT);
                    }
                    object.fromFaintStreakSearch = true;
                    object.sourceFrameIndex = frame.sequenceIndex;
                    object.sourceFilename = frame.filename;
                    object.timestamp = frame.timestamp;
                    object.exposureDuration = frame.exposureDuration;
                    kept.add(object);
                }
                return kept;
            });
        }
        List<List<DetectedObject>> candidates = new ArrayList<>(WorkerPool.invokeAll(tasks));
        for (int i = 0; i < keptFrames.size(); i++) {
            telemetry.extractedStreaks += extracted[i];
            telemetry.removedByStarMask += removedByMask[i];
        }
        return new Result(candidates, telemetry);
    }

    /** The smoothing sigma and how many kept frames' star sizes set it (none: the fallback). */
    static final class Width {
        final double sigma;
        final int frames;

        Width(double sigma, int frames) {
            this.sigma = sigma;
            this.frames = frames;
        }
    }

    /**
     * The smoothing sigma from the kept frames' star sizes: the median of the genuine measurements (finite, positive,
     * below {@code errorFallbackValue}, from at least {@link #MIN_FWHM_STARS} stars), converted from the extraction's
     * second-moment FWHM, about sqrt(2) above a Gaussian FWHM. Without a measurement, {@link #FALLBACK_SIGMA}.
     */
    static Width width(List<FrameQualityAnalyzer.FrameMetrics> metrics, DetectionConfig config) {
        List<Double> sizes = new ArrayList<>();
        if (metrics != null) {
            for (FrameQualityAnalyzer.FrameMetrics frame : metrics) {
                if (frame != null && Double.isFinite(frame.medianFWHM) && frame.medianFWHM > 0
                        && frame.medianFWHM < config.errorFallbackValue && frame.fwhmStarCount >= MIN_FWHM_STARS) {
                    sizes.add(frame.medianFWHM);
                }
            }
        }
        if (sizes.isEmpty()) {
            return new Width(FALLBACK_SIGMA, 0);
        }
        Collections.sort(sizes);
        int n = sizes.size();
        double median = n % 2 == 1 ? sizes.get(n / 2) : 0.5 * (sizes.get(n / 2 - 1) + sizes.get(n / 2));
        double sigma = median / Math.sqrt(2.0) / (2.0 * Math.sqrt(2.0 * Math.log(2.0)));
        return new Width(Math.max(MIN_SIGMA, Math.min(MAX_SIGMA, sigma)), n);
    }

    /** A normalised one-dimensional Gaussian of radius ceil(3 sigma). */
    static float[] kernel(double sigma) {
        int radius = (int) Math.ceil(3.0 * sigma);
        float[] kernel = new float[2 * radius + 1];
        double sum = 0;
        for (int i = -radius; i <= radius; i++) {
            sum += Math.exp(-i * i / (2.0 * sigma * sigma));
        }
        for (int i = -radius; i <= radius; i++) {
            kernel[i + radius] = (float) (Math.exp(-i * i / (2.0 * sigma * sigma)) / sum);
        }
        return kernel;
    }

    /** How much the smoothing lowers independent pixel noise: sqrt of the sum of the 2-D kernel's squares. */
    static double noiseFactor(float[] kernel) {
        double sumSquares = 0;
        for (float k : kernel) {
            sumSquares += (double) k * k;
        }
        return sumSquares;
    }

    /**
     * The image smoothed over its image pixels only (a normalised convolution): registration fill neither enters the
     * sky nor changes, and an image pixel never drops to the fill level. With {@code gain} above one the smoothed
     * values are stretched about {@code sky} before rounding, so a low-noise frame keeps its noise in the integer
     * histogram of the extraction.
     *
     * @param image stored (signed-short) pixels, indexed {@code [y][x]}; not modified
     * @param kernel normalised one-dimensional kernel
     * @param gain stretch about the sky, one for none
     * @param sky the stored sky level the stretch keeps fixed
     */
    static short[][] smooth(short[][] image, float[] kernel, double gain, double sky) {
        int height = image.length;
        int width = image[0].length;
        int radius = kernel.length / 2;
        short[][] out = new short[height][width];
        float[] values = new float[width + 2 * radius];
        float[] mask = new float[width + 2 * radius];
        float[] sum = new float[width];
        float[] weight = new float[width];
        int bandRows = Math.min(height, BAND_ROWS + 2 * radius);
        float[][] rowSums = new float[bandRows][width];
        float[][] rowWeights = new float[bandRows][width];
        for (int bandStart = 0; bandStart < height; bandStart += BAND_ROWS) {
            int bandEnd = Math.min(height, bandStart + BAND_ROWS);
            int from = Math.max(0, bandStart - radius);
            int to = Math.min(height, bandEnd + radius);
            for (int y = from; y < to; y++) {
                CancellationScope.check();
                short[] row = image[y];
                for (int x = 0; x < width; x++) {
                    boolean fill = row[x] <= DataArea.FILL_THRESHOLD;
                    values[x + radius] = fill ? 0f : row[x];
                    mask[x + radius] = fill ? 0f : 1f;
                }
                convolve(values, mask, kernel, rowSums[y - from], rowWeights[y - from]);
            }
            for (int y = bandStart; y < bandEnd; y++) {
                CancellationScope.check();
                java.util.Arrays.fill(sum, 0f);
                java.util.Arrays.fill(weight, 0f);
                for (int i = -radius; i <= radius; i++) {
                    int yy = y + i;
                    if (yy < from || yy >= to) {
                        continue;
                    }
                    float k = kernel[i + radius];
                    float[] rowSum = rowSums[yy - from];
                    float[] rowWeight = rowWeights[yy - from];
                    for (int x = 0; x < width; x++) {
                        sum[x] += k * rowSum[x];
                        weight[x] += k * rowWeight[x];
                    }
                }
                short[] in = image[y];
                short[] dst = out[y];
                for (int x = 0; x < width; x++) {
                    if (in[x] <= DataArea.FILL_THRESHOLD) {
                        dst[x] = in[x];
                        continue;
                    }
                    double value = weight[x] > 0 ? sum[x] / weight[x] : in[x];
                    if (gain != 1.0) {
                        value = sky + gain * (value - sky);
                    }
                    long rounded = Math.round(value);
                    dst[x] = (short) Math.max(DataArea.FILL_THRESHOLD + 1, Math.min(Short.MAX_VALUE, rounded));
                }
            }
        }
        return out;
    }

    /**
     * One row's horizontal pass over a row padded by the kernel radius on both sides: the kernel-weighted sum of its
     * image pixels and the kernel weight they carry (fill and the padding have zero value and weight). The loops run
     * tap by tap across the row, which the compiler can vectorise.
     */
    private static void convolve(float[] values, float[] mask, float[] kernel, float[] rowSum, float[] rowWeight) {
        int width = rowSum.length;
        java.util.Arrays.fill(rowSum, 0f);
        java.util.Arrays.fill(rowWeight, 0f);
        for (int i = 0; i < kernel.length; i++) {
            float k = kernel[i];
            for (int x = 0; x < width; x++) {
                rowSum[x] += k * values[x + i];
                rowWeight[x] += k * mask[x + i];
            }
        }
    }

    /** Undoes the rounding stretch on a candidate's flux and footprint values (shifted ADU about {@code sky}). */
    private static void unstretch(DetectedObject object, double gain, double sky) {
        object.totalFlux /= gain;
        if (object.rawPixels != null) {
            for (Pixel pixel : object.rawPixels) {
                pixel.value = (int) Math.round(sky + (pixel.value - sky) / gain);
            }
        }
    }

    /** Share of the object's footprint on the star mask. */
    private static double maskOverlap(DetectedObject object, boolean[][] mask) {
        if (object.rawPixels == null || object.rawPixels.isEmpty()) {
            return 0.0;
        }
        int overlap = 0;
        for (Pixel pixel : object.rawPixels) {
            if (pixel.y >= 0 && pixel.y < mask.length && pixel.x >= 0 && pixel.x < mask[pixel.y].length
                    && mask[pixel.y][pixel.x]) {
                overlap++;
            }
        }
        return (double) overlap / object.rawPixels.size();
    }
}
