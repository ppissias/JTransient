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
import io.github.ppissias.jtransient.engine.JTransientEngine;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

public class SourceExtractor {

    // --- OPTIMIZATION: Moved directional arrays to static constants to avoid reallocation ---
    private static final int[] DX = {-1, -1, -1, 0, 0, 1, 1, 1};
    private static final int[] DY = {-1, 0, 1, -1, 1, -1, 0, 1};

    // =================================================================
    // HELPER CLASSES
    // =================================================================

    public static class DetectedObject {
        public double x, y;
        public double totalFlux;
        public int pixelCount;
        public double peakSigma; // For Anomaly Detection

        // Save the exact blob mask for UI Visualization (Populated only if DEBUG is true)
        public List<Pixel> rawPixels = null;

        // Size metric for TrackLinker Morphological Filtering
        public double pixelArea;

        // FWHM Metric
        public double fwhm;

        // Shape Fields
        public double elongation;
        public double angle; // The angle of the streak in radians
        public boolean isStreak;
        public boolean isNoise;

        public int sourceFrameIndex; // Points back to rawFrames.get(i)
        public String sourceFilename;

        public DetectedObject(double x, double y, double flux, int count) {
            this.x = x;
            this.y = y;
            this.totalFlux = flux;
            this.pixelCount = count;
        }
    }

    public static class Pixel {
        public int x, y, value;

        public Pixel(int x, int y, int value) {
            this.x = x;
            this.y = y;
            this.value = value;
        }
    }

    public static class BackgroundMetrics {
        public double median;
        public double sigma;
        public double threshold;
    }

    // =================================================================
    // CORE EXTRACTION PIPELINE
    // =================================================================

    /**
     * Main method to run the detection pipeline on a single frame.
     * Allows overriding the primary thresholds (used by FrameQualityAnalyzer).
     */
    public static List<DetectedObject> extractSources(short[][] image, double sigmaMultiplier, int minPixels, DetectionConfig config) {
        int height = image.length;
        int width = image[0].length;

        // 1. Calculate Background
        BackgroundMetrics bg = calculateBackgroundSigmaClipped(image, width, height, sigmaMultiplier, config);

        if (JTransientEngine.DEBUG) {
            System.out.printf("BACKGROUND STATS -> Median: %.2f | Sigma: %.2f | Seed Thresh: %.2f | Grow Thresh: %.2f%n",
                    bg.median, bg.sigma, bg.median + (bg.sigma * sigmaMultiplier), bg.median + (bg.sigma * config.growSigmaMultiplier));
            System.out.printf("  -> Engine Config Applied [MinPix: %d | BaseSigma: %.2f | GrowSigma: %.2f]%n",
                    minPixels, sigmaMultiplier, config.growSigmaMultiplier);
        }

        double seedThreshold = bg.median + (bg.sigma * sigmaMultiplier);
        double growThreshold = bg.median + (bg.sigma * config.growSigmaMultiplier);
        double voidValueThreshold = bg.median * config.voidThresholdFraction;

        // 2. Setup for BFS Blob Detection
        List<DetectedObject> detectedObjects = new ArrayList<>();
        boolean[][] visited = new boolean[height][width];

        // --- DEBUG TELEMETRY COUNTERS ---
        int statBfsTriggers = 0;
        int statRejectedNoise = 0;
        int statRejectedEdge = 0;
        int statRejectedVoid = 0;

        // 3. Scan the image
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {

                int pixelValue = image[y][x] + 32768;

                if (pixelValue > seedThreshold && !visited[y][x]) {
                    statBfsTriggers++;

                    List<Pixel> currentBlob = new ArrayList<>();
                    Queue<Pixel> queue = new java.util.ArrayDeque<>();

                    Pixel startPixel = new Pixel(x, y, pixelValue);
                    queue.add(startPixel);
                    visited[y][x] = true;

                    // --- FAST BFS LOOP ---
                    while (!queue.isEmpty()) {
                        Pixel p = queue.poll();
                        currentBlob.add(p);

                        for (int i = 0; i < 8; i++) {
                            int nx = p.x + DX[i];
                            int ny = p.y + DY[i];

                            if (nx >= 0 && nx < width && ny >= 0 && ny < height) {
                                if (!visited[ny][nx]) {
                                    int nValue = image[ny][nx] + 32768;
                                    if (nValue > growThreshold) {
                                        visited[ny][nx] = true;
                                        queue.add(new Pixel(nx, ny, nValue));
                                    }
                                }
                            }
                        }
                    }

                    // 4. Centroiding and Filtering
                    if (currentBlob.size() >= minPixels) {

                        // Removed redundant minPixels parameter
                        DetectedObject obj = analyzeShape(currentBlob, bg, config);

                        if (obj.isNoise) {
                            statRejectedNoise++;
                            continue;
                        }
                        // --- MEMORY OPTIMIZATION ---
                        // Only attach the raw pixels if we are debugging/tuning the UI.
                        //if (JTransientEngine.DEBUG) {
                        obj.rawPixels = currentBlob;
                        //}

                        // ==========================================================
                        // FILTER 1: PHYSICAL SENSOR EDGE
                        // ==========================================================
                        if (obj.x < config.edgeMarginPixels || obj.x >= (width - config.edgeMarginPixels) ||
                                obj.y < config.edgeMarginPixels || obj.y >= (height - config.edgeMarginPixels)) {
                            statRejectedEdge++;
                            continue;
                        }

                        // ==========================================================
                        // FILTER 2: FAST VIRTUAL EDGE (ALIGNMENT VOID) CHECK
                        // ==========================================================
                        boolean nearVirtualEdge = false;
                        int vr = config.voidProximityRadius;
                        int cx = (int) obj.x;
                        int cy = (int) obj.y;

                        int[] px = {cx, cx + vr, cx + vr, cx + vr, cx, cx - vr, cx - vr, cx - vr};
                        int[] py = {cy - vr, cy - vr, cy, cy + vr, cy + vr, cy + vr, cy, cy - vr};

                        for (int i = 0; i < 8; i++) {
                            int testX = px[i];
                            int testY = py[i];

                            if (testX < 0 || testX >= width || testY < 0 || testY >= height) {
                                nearVirtualEdge = true;
                                break;
                            }

                            int vValue = image[testY][testX] + 32768;
                            if (vValue <= voidValueThreshold) {
                                nearVirtualEdge = true;
                                break;
                            }
                        }

                        if (nearVirtualEdge) {
                            statRejectedVoid++;
                            continue;
                        }

                        obj.pixelArea = currentBlob.size();
                        detectedObjects.add(obj);
                    } else {
                        statRejectedNoise++;
                    }
                }
            }
        }

        // --- PRINT FINAL FRAME TELEMETRY SUMMARY ---
        if (JTransientEngine.DEBUG) {
            System.out.printf("  -> Extracted %d valid objects. [BFS Triggers: %d | Rejected Noise: %d | Rejected Edge: %d | Rejected Void: %d]%n",
                    detectedObjects.size(), statBfsTriggers, statRejectedNoise, statRejectedEdge, statRejectedVoid);
        }

        return detectedObjects;
    }

    /**
     * Fast Histogram-based background estimation using Iterative Sigma Clipping.
     */
    public static BackgroundMetrics calculateBackgroundSigmaClipped(short[][] image, int width, int height, double sigmaMultiplier, DetectionConfig config) {
        BackgroundMetrics metrics = new BackgroundMetrics();
        int[] histogram = new int[65536];

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int val = image[y][x] + 32768;
                histogram[val]++;
            }
        }

        int currentMinBin = 0;
        int currentMaxBin = 65535;
        double currentMedian = 0;
        double currentSigma = 0;

        for (int iter = 0; iter < config.bgClippingIterations; iter++) {
            long count = 0;
            long validPixelCount = 0;

            for (int i = currentMinBin; i <= currentMaxBin; i++) {
                validPixelCount += histogram[i];
            }

            if (validPixelCount == 0) break;

            for (int i = currentMinBin; i <= currentMaxBin; i++) {
                count += histogram[i];
                if (count >= validPixelCount / 2) {
                    currentMedian = i;
                    break;
                }
            }

            double sumSqDiff = 0;
            for (int i = currentMinBin; i <= currentMaxBin; i++) {
                if (histogram[i] > 0) {
                    double diff = i - currentMedian;
                    sumSqDiff += (diff * diff) * histogram[i];
                }
            }
            currentSigma = Math.sqrt(sumSqDiff / validPixelCount);

            currentMinBin = (int) Math.max(0, Math.floor(currentMedian - (config.bgClippingFactor * currentSigma)));
            currentMaxBin = (int) Math.min(65535, Math.ceil(currentMedian + (config.bgClippingFactor * currentSigma)));
        }

        metrics.median = currentMedian;
        metrics.sigma = currentSigma;
        metrics.threshold = currentMedian + (currentSigma * sigmaMultiplier);

        return metrics;
    }

    /**
     * Calculates the centroid, elongation, and angle of a pixel blob using Image Moments.
     */
    public static DetectedObject analyzeShape(List<Pixel> blob, BackgroundMetrics bg, DetectionConfig config) {
        DetectedObject obj = new DetectedObject(0,0,0,0);

        // Calculate Peak Sigma for Anomaly Rescue
        double maxPixelValue = 0;
        for (Pixel p : blob) {
            if (p.value > maxPixelValue) {
                maxPixelValue = p.value;
            }
        }
        obj.peakSigma = (maxPixelValue - bg.median) / bg.sigma;

        double m00 = 0;
        double m10 = 0;
        double m01 = 0;

        for (Pixel p : blob) {
            double intensity = p.value - bg.median;
            if (intensity <= 0) intensity = 1;

            m00 += intensity;
            m10 += p.x * intensity;
            m01 += p.y * intensity;
        }

        obj.x = m10 / m00;
        obj.y = m01 / m00;
        obj.totalFlux = m00;

        double mu20 = 0, mu02 = 0, mu11 = 0;
        for (Pixel p : blob) {
            double intensity = p.value - bg.median;
            if (intensity <= 0) intensity = 1;

            double dx = p.x - obj.x;
            double dy = p.y - obj.y;

            mu20 += (dx * dx) * intensity;
            mu02 += (dy * dy) * intensity;
            mu11 += (dx * dy) * intensity;
        }

        mu20 /= m00;
        mu02 /= m00;
        mu11 /= m00;

        double delta = Math.sqrt((mu20 - mu02) * (mu20 - mu02) + 4 * mu11 * mu11);
        double lambda1 = (mu20 + mu02 + delta) / 2.0;
        double lambda2 = (mu20 + mu02 - delta) / 2.0;

        if (lambda2 < 0.001) lambda2 = 0.001;
        if (lambda1 < 0.001) lambda1 = 0.001;

        obj.elongation = Math.sqrt(lambda1 / lambda2);
        obj.angle = 0.5 * Math.atan2(2 * mu11, mu20 - mu02);

        double sigmaSq = lambda1 + lambda2;
        obj.fwhm = 2.355 * Math.sqrt(sigmaSq);

        // --- CLEANED UP MORPHOLOGICAL CLASSIFICATION ---
        if (obj.elongation > config.streakMinElongation) {
            if (blob.size() >= config.streakMinPixels) {
                // It is long enough and large enough. It's a real streak.
                obj.isStreak = true;
                obj.isNoise = false;
            } else {
                // It is elongated, but too small. It's a cosmic ray strike or glitch.
                obj.isStreak = false;
                obj.isNoise = true;
            }
        } else {
            // It is relatively round, and we already know it passed the outer minPixels check. It's a point source!
            obj.isStreak = false;
            obj.isNoise = false;
        }

        return obj;
    }
}