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

/**
 * Detects bright connected components in a single frame and derives their morphology.
 */
public class SourceExtractor {

    // --- OPTIMIZATION: Moved directional arrays to static constants to avoid reallocation ---
    private static final int[] DX = {-1, -1, -1, 0, 0, 1, 1, 1};
    private static final int[] DY = {-1, 0, 1, -1, 1, -1, 0, 1};

    // =================================================================
    // HELPER CLASSES
    // =================================================================

    /**
     * Describes one extracted source or streak candidate.
     */
    public static class DetectedObject {
        /** Intensity-weighted centroid X coordinate. */
        public double x, y;
        /** Background-subtracted integrated flux. */
        public double totalFlux;
        /** Number of pixels used when the object was instantiated. */
        public int pixelCount;
        /** Peak signal-to-noise estimate used by anomaly rescue. */
        public double peakSigma;
        /** Integrated signal-to-noise estimate used to rescue broader high-energy anomalies. */
        public double integratedSigma;

        /** Exact blob footprint used for mask building and diagnostics. */
        public List<Pixel> rawPixels = null;

        /** Pixel footprint size used by the track-linking morphology filters. */
        public double pixelArea;

        /** Approximate full width at half maximum derived from image moments. */
        public double fwhm;

        /** Ratio between the principal axes of the detected footprint. */
        public double elongation;
        /** Dominant orientation of the footprint in radians. */
        public double angle;
        /** Whether the footprint is classified as a streak-like object. */
        public boolean isStreak;
        /** Whether the footprint should be treated as noise. */
        public boolean isNoise;

        /** Source frame index assigned by the engine after extraction. */
        public int sourceFrameIndex;
        /** Source frame label assigned by the engine after extraction. */
        public String sourceFilename;
        /** Real-world capture time in milliseconds, or {@code -1} if unknown. */
        public long timestamp;
        /** Exposure duration in milliseconds, or {@code -1} if unknown. */
        public long exposureDuration;

        /**
         * Creates a detected-object shell with the supplied centroid and flux values.
         */
        public DetectedObject(double x, double y, double flux, int count) {
            this.x = x;
            this.y = y;
            this.totalFlux = flux;
            this.pixelCount = count;
        }
    }

    /**
     * Represents one pixel belonging to an extracted footprint.
     */
    public static class Pixel {
        /** Pixel X coordinate. */
        public int x, y, value;

        /**
         * Creates a pixel coordinate and value tuple.
         */
        public Pixel(int x, int y, int value) {
            this.x = x;
            this.y = y;
            this.value = value;
        }
    }

    /**
     * Background statistics derived from sigma-clipped histogram analysis.
     */
    public static class BackgroundMetrics {
        /** Estimated background median in shifted ADU space. */
        public double median;
        /** Estimated background sigma in shifted ADU space. */
        public double sigma;
        /** Detection threshold derived from the chosen sigma multiplier. */
        public double threshold;
    }

    /**
     * Bundles the extracted objects with the thresholds used to produce them.
     */
    public static class ExtractionResult {
        /** Objects that survived the blob and artifact filters. */
        public final List<DetectedObject> objects;
        /** Background model used by the extraction pass. */
        public final BackgroundMetrics backgroundMetrics;
        /** Seed threshold used to start blob growth. */
        public final double seedThreshold;
        /** Lower hysteresis threshold used while growing blobs. */
        public final double growThreshold;

        /**
         * Creates an immutable extraction result.
         */
        public ExtractionResult(List<DetectedObject> objects, BackgroundMetrics bg, double seedThreshold, double growThreshold) {
            this.objects = objects;
            this.backgroundMetrics = bg;
            this.seedThreshold = seedThreshold;
            this.growThreshold = growThreshold;
        }
    }

    // =================================================================
    // CORE EXTRACTION PIPELINE
    // =================================================================

    /**
     * Main method to run the detection pipeline on a single frame.
     * Allows overriding the primary thresholds (used by FrameQualityAnalyzer).
     *
     * @param image input frame
     * @param sigmaMultiplier seed threshold multiplier used for this extraction pass
     * @param minPixels minimum footprint size required to keep an object
     * @param config pipeline configuration containing grow and artifact filters
     * @return extracted objects and background statistics
     */
    public static ExtractionResult extractSources(short[][] image, double sigmaMultiplier, int minPixels, DetectionConfig config) {
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

                        //we always have this
                        obj.rawPixels = currentBlob;

                        // ==========================================================
                        // FILTER 1: PHYSICAL SENSOR EDGE
                        // ==========================================================
                        if (!obj.isStreak) {
                            if (obj.x < config.edgeMarginPixels || obj.x >= (width - config.edgeMarginPixels) ||
                                    obj.y < config.edgeMarginPixels || obj.y >= (height - config.edgeMarginPixels)) {
                                statRejectedEdge++;
                                continue;
                            }
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

        return new ExtractionResult(detectedObjects, bg, seedThreshold, growThreshold);
    }

    /**
     * Fast Histogram-based background estimation using Iterative Sigma Clipping.
     *
     * @param image frame pixels to analyze
     * @param width frame width
     * @param height frame height
     * @param sigmaMultiplier multiplier used to derive the reported threshold
     * @param config configuration containing clipping settings
     * @return background median, sigma, and derived threshold
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
     *
     * @param blob extracted footprint pixels
     * @param bg background metrics used for intensity weighting
     * @param config configuration containing streak classification thresholds
     * @return analyzed object populated with morphology metrics
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
        double sigmaFloor = Math.max(bg.sigma, 1.0e-6);
        obj.integratedSigma = m00 / (sigmaFloor * Math.sqrt(Math.max(blob.size(), 1)));

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
        if (obj.elongation > config.streakMinElongation && blob.size() >= config.streakMinPixels) {
            // It is long enough and large enough. It's a real streak.
            obj.isStreak = true;
            obj.isNoise = false;
        } else {
            // It is relatively round, OR it is an elongated small anomaly (like a cosmic ray). 
            // Since it passed the outer minPixels check, we keep it alive as a standard point source.
            obj.isStreak = false;
            obj.isNoise = false;
        }

        return obj;
    }

    /**
     * Analyzes the pixel profile of a highly elongated object to determine if it is a
     * merged pair of distinct stars (binary) rather than a continuous slow-mover streak.
     *
     * @param originalImage source image used to sample the raw profile
     * @param obj candidate elongated object
     * @return {@code true} when the profile is more consistent with a merged binary star
     */
    public static boolean isBinaryStarAnomaly(short[][] originalImage, SourceExtractor.DetectedObject obj) {
        if (obj.rawPixels == null || obj.rawPixels.size() < 10) return false;

        int maxVal = -32768;
        int minVal = 32767;
        int maxX = -1, maxY = -1;

        // Find the absolute peak, and the background edge (minVal) of the footprint
        for (SourceExtractor.Pixel p : obj.rawPixels) {
            int val = originalImage[p.y][p.x];
            if (val > maxVal) {
                maxVal = val;
                maxX = p.x;
                maxY = p.y;
            }
            if (val < minVal) minVal = val;
        }

        if (maxVal <= minVal) return false;

        int minSeparation = 4; // Stars closer than this are too tightly merged to split mathematically
        int secondMaxVal = -32768;
        int sMaxX = -1, sMaxY = -1;

        // Find the second highest peak that is at least a few pixels away
        for (SourceExtractor.Pixel p : obj.rawPixels) {
            double dist = Math.hypot(p.x - maxX, p.y - maxY);
            if (dist >= minSeparation) {
                int val = originalImage[p.y][p.x];
                if (val > secondMaxVal) {
                    secondMaxVal = val;
                    sMaxX = p.x;
                    sMaxY = p.y;
                }
            }
        }

        if (sMaxX == -1) return false;

        double primarySignal = maxVal - minVal;
        double secondarySignal = secondMaxVal - minVal;

        // If the secondary peak is very faint, it's just a tail or noise, not a distinct star
        if (secondarySignal < primarySignal * 0.3) return false;

        double minSaddleSignal = primarySignal;
        int steps = (int) Math.round(Math.hypot(sMaxX - maxX, sMaxY - maxY));
        if (steps <= 1) return false;

        // Walk the straight line between the two peaks to find the deepest point (saddle)
        for (int i = 1; i < steps; i++) {
            int px = (int) Math.round(maxX + i * (sMaxX - maxX) / (double) steps);
            int py = (int) Math.round(maxY + i * (sMaxY - maxY) / (double) steps);

            if (py >= 0 && py < originalImage.length && px >= 0 && px < originalImage[0].length) {
                double signal = originalImage[py][px] - minVal;
                if (signal < minSaddleSignal) {
                    minSaddleSignal = signal;
                }
            }
        }

        // If the saddle point drops below 80% of the secondary peak, it's two separate Gaussian curves
        // A true streak would maintain near-constant brightness along its central ridge
        return minSaddleSignal < (secondarySignal * 0.80);
    }

    /**
     * Rejects short dumbbell / peanut streak footprints that are more consistent with two nearby stars
     * than with a continuously illuminated moving streak. This is shape-only and scale-aware.
     *
     * @param obj streak candidate to inspect
     * @return {@code true} when the footprint looks like a close binary-star pair
     */
    public static boolean isBinaryStarLikeStreakShape(SourceExtractor.DetectedObject obj) {
        if (obj.rawPixels == null || obj.rawPixels.size() < 10) return false;

        double dx = Math.cos(obj.angle);
        double dy = Math.sin(obj.angle);

        double minPar = Double.MAX_VALUE;
        double maxPar = -Double.MAX_VALUE;
        double minPerp = Double.MAX_VALUE;
        double maxPerp = -Double.MAX_VALUE;
        for (SourceExtractor.Pixel p : obj.rawPixels) {
            double vx = p.x - obj.x;
            double vy = p.y - obj.y;
            double par = vx * dx + vy * dy;
            double perp = -vx * dy + vy * dx;
            if (par < minPar) minPar = par;
            if (par > maxPar) maxPar = par;
            if (perp < minPerp) minPerp = perp;
            if (perp > maxPerp) maxPerp = perp;
        }

        double length = maxPar - minPar + 1.0;
        double width = maxPerp - minPerp + 1.0;
        if (length < 6.0 || width < 2.0) return false;

        double fillFactor = obj.pixelArea / Math.max(1.0, length * width);
        if (fillFactor < 0.35) return false;

        int bins = Math.max(6, Math.min(8, (int) Math.round(length)));
        double binSpan = Math.max(1.0, length / bins);
        int[] counts = new int[bins];
        double[] binMinPerp = new double[bins];
        double[] binMaxPerp = new double[bins];
        for (int i = 0; i < bins; i++) {
            binMinPerp[i] = Double.MAX_VALUE;
            binMaxPerp[i] = -Double.MAX_VALUE;
        }

        for (SourceExtractor.Pixel p : obj.rawPixels) {
            double vx = p.x - obj.x;
            double vy = p.y - obj.y;
            double par = vx * dx + vy * dy;
            double perp = -vx * dy + vy * dx;

            int bin = (int) ((par - minPar) / binSpan);
            if (bin < 0) bin = 0;
            if (bin >= bins) bin = bins - 1;

            counts[bin]++;
            if (perp < binMinPerp[bin]) binMinPerp[bin] = perp;
            if (perp > binMaxPerp[bin]) binMaxPerp[bin] = perp;
        }

        int firstOccupied = -1;
        int lastOccupied = -1;
        for (int i = 0; i < bins; i++) {
            if (counts[i] > 0) {
                if (firstOccupied == -1) {
                    firstOccupied = i;
                }
                lastOccupied = i;
            }
        }

        if (firstOccupied == -1 || lastOccupied - firstOccupied < 4) {
            return false;
        }

        int span = lastOccupied - firstOccupied + 1;
        int middleStart = firstOccupied + (span / 3);
        int middleEnd = lastOccupied - (span / 3);
        int leftEnd = middleStart - 1;
        int rightStart = middleEnd + 1;
        if (leftEnd < firstOccupied || rightStart > lastOccupied) {
            return false;
        }

        int leftPeakCount = 0;
        int rightPeakCount = 0;
        double leftPeakWidth = 0.0;
        double rightPeakWidth = 0.0;
        int middleMinCount = Integer.MAX_VALUE;
        double middleMinWidth = Double.MAX_VALUE;

        for (int i = firstOccupied; i <= leftEnd; i++) {
            if (counts[i] > leftPeakCount) {
                leftPeakCount = counts[i];
            }
            if (counts[i] > 0) {
                double binWidth = binMaxPerp[i] - binMinPerp[i] + 1.0;
                if (binWidth > leftPeakWidth) {
                    leftPeakWidth = binWidth;
                }
            }
        }

        for (int i = rightStart; i <= lastOccupied; i++) {
            if (counts[i] > rightPeakCount) {
                rightPeakCount = counts[i];
            }
            if (counts[i] > 0) {
                double binWidth = binMaxPerp[i] - binMinPerp[i] + 1.0;
                if (binWidth > rightPeakWidth) {
                    rightPeakWidth = binWidth;
                }
            }
        }

        for (int i = middleStart; i <= middleEnd; i++) {
            middleMinCount = Math.min(middleMinCount, counts[i]);
            if (counts[i] > 0) {
                middleMinWidth = Math.min(middleMinWidth, binMaxPerp[i] - binMinPerp[i] + 1.0);
            } else {
                middleMinWidth = 0.0;
            }
        }

        if (leftPeakCount == 0 || rightPeakCount == 0) {
            return false;
        }

        int minPeakCount = Math.min(leftPeakCount, rightPeakCount);
        int maxPeakCount = Math.max(leftPeakCount, rightPeakCount);
        if (maxPeakCount > Math.max(4, minPeakCount * 2.8)) {
            return false;
        }

        double minPeakWidth = Math.min(leftPeakWidth, rightPeakWidth);
        double maxPeakWidth = Math.max(leftPeakWidth, rightPeakWidth);
        if (maxPeakWidth > Math.max(4.0, minPeakWidth * 2.8)) {
            return false;
        }

        boolean middleCountSuppressed = middleMinCount == 0 || middleMinCount < (minPeakCount * 0.55);
        boolean middleWidthSuppressed = middleMinWidth == 0.0 || middleMinWidth < (minPeakWidth * 0.72);

        return middleCountSuppressed && middleWidthSuppressed;
    }

    /**
     * Analyzes the morphological boundaries of a streak to ensure it forms a consistent,
     * straight capsule shape. Throws out "L", "V", and highly asymmetric blob shapes.
     *
     * @param obj elongated candidate to inspect
     * @return {@code true} when the object shape is too irregular to trust as a streak
     */
    public static boolean isIrregularStreakShape(SourceExtractor.DetectedObject obj) {
        if (obj.rawPixels == null || obj.rawPixels.size() < 10) return false;

        double dx = Math.cos(obj.angle);
        double dy = Math.sin(obj.angle);

        double minPar = Double.MAX_VALUE;
        double maxPar = -Double.MAX_VALUE;
        double minPerp = Double.MAX_VALUE;
        double maxPerp = -Double.MAX_VALUE;

        // Project every pixel onto the primary and perpendicular axes
        for (SourceExtractor.Pixel p : obj.rawPixels) {
            double vx = p.x - obj.x;
            double vy = p.y - obj.y;
            double par = vx * dx + vy * dy;       // Distance along the streak
            double perp = -vx * dy + vy * dx;     // Distance away from the center line (thickness)

            if (par < minPar) minPar = par;
            if (par > maxPar) maxPar = par;
            if (perp < minPerp) minPerp = perp;
            if (perp > maxPerp) maxPerp = perp;
        }

        double length = maxPar - minPar + 1;
        double width = maxPerp - minPerp + 1;

        // 1. Fill Factor Check (Catches L-shapes, V-shapes, and diagonal crosses)
        // A true streak (capsule) fills roughly 78% (PI/4) to 100% of its oriented bounding box.
        // Relaxed to 35% to account for ragged edges on faint targets.
        double fillFactor = obj.pixelArea / (length * width);
        if (fillFactor < 0.35) {
            return true;
        }

        if (length < 10) return false; // Too short to reliably segment without pixel-grid aliasing

        // 2. Thickness consistency check (5 segments, analyzing middle 3 to ignore natural tapering)
        double segmentLength = length / 5.0;
        double[] sMin = {Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE};
        double[] sMax = {-Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
        int[] sCount = new int[5];

        for (SourceExtractor.Pixel p : obj.rawPixels) {
            double vx = p.x - obj.x;
            double vy = p.y - obj.y;
            double par = vx * dx + vy * dy;
            double perp = -vx * dy + vy * dx;

            int seg = (int) ((par - minPar) / segmentLength);
            if (seg > 4) seg = 4;
            if (seg < 0) seg = 0;

            if (perp < sMin[seg]) sMin[seg] = perp;
            if (perp > sMax[seg]) sMax[seg] = perp;
            sCount[seg]++;
        }

        double w1 = sCount[1] > 0 ? (sMax[1] - sMin[1] + 1) : 0;
        double w2 = sCount[2] > 0 ? (sMax[2] - sMin[2] + 1) : 0;
        double w3 = sCount[3] > 0 ? (sMax[3] - sMin[3] + 1) : 0;

        // Skip only if ALL middle segments are completely empty
        if (w1 == 0 && w2 == 0 && w3 == 0) return true;

        // Peanut check: middle is severely thinner than its neighbors
        // Relaxed to 40% to account for standard pixelation variance on faint targets
        if (w2 < w1 * 0.40 && w2 < w3 * 0.40) {
            return true;
        }

        // Bowling pin / Teardrop check: one side of the middle is massively thicker than the other
        // Relaxed to 3.0x to prevent false positives on thin streaks (e.g., width fluctuating between 1px and 3px)
        double midMaxW = Math.max(w1, Math.max(w2, w3));
        double midMinW = Math.min(w1, Math.min(w2, w3));
        if (midMinW > 0 && midMaxW > Math.max(4.0, midMinW * 3.0)) {
            return true;
        }

        return false;
    }

}
