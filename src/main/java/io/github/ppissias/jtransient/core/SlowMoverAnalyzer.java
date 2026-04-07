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
import io.github.ppissias.jtransient.telemetry.PipelineTelemetry;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds and filters the optional slow-mover stack used to mine ultra-slow persistent movers.
 */
public final class SlowMoverAnalyzer {

    private SlowMoverAnalyzer() {
    }

    /**
     * Runs the slow-mover stack generation, comparison extraction, and candidate filtering.
     */
    public static SlowMoverAnalysis analyze(List<ImageFrame> cleanFrames,
                                            short[][] masterStackData,
                                            DetectionConfig config) {
        if (config == null || !config.enableSlowMoverDetection
                || cleanFrames == null || cleanFrames.isEmpty()
                || masterStackData == null || masterStackData.length == 0 || masterStackData[0].length == 0) {
            return SlowMoverAnalysis.empty();
        }

        short[][] slowMoverStackData = MasterMapGenerator.createSlowMoverMasterStack(
                cleanFrames,
                config.slowMoverStackMiddleFraction
        );

        DetectionConfig extractionConfig = config.clone();
        extractionConfig.growSigmaMultiplier = extractionConfig.masterSlowMoverGrowSigmaMultiplier;

        List<SourceExtractor.DetectedObject> rawSlowMovers = SourceExtractor.extractSources(
                slowMoverStackData,
                extractionConfig.masterSlowMoverSigmaMultiplier,
                extractionConfig.masterSlowMoverMinPixels,
                extractionConfig
        ).objects;

        List<SourceExtractor.DetectedObject> medianArtifacts = SourceExtractor.extractSources(
                masterStackData,
                extractionConfig.masterSlowMoverSigmaMultiplier,
                extractionConfig.masterSlowMoverMinPixels,
                extractionConfig
        ).objects;

        int sensorHeight = masterStackData.length;
        int sensorWidth = masterStackData[0].length;
        PipelineTelemetry.SlowMoverTelemetry telemetry = new PipelineTelemetry.SlowMoverTelemetry();
        boolean[][] medianMask = buildObjectMask(medianArtifacts, sensorWidth, sensorHeight, 0);
        List<SlowMoverCandidateResult> slowMoverCandidates = filterSlowMoverCandidates(
                rawSlowMovers,
                slowMoverStackData,
                masterStackData,
                medianMask,
                config,
                telemetry
        );

        return new SlowMoverAnalysis(
                slowMoverStackData,
                medianMask,
                slowMoverCandidates,
                new SlowMoverSummaryTelemetry(telemetry)
        );
    }

    /**
     * Applies the slow-mover morphology, median-support, and residual-core filters while recording telemetry.
     */
    private static List<SlowMoverCandidateResult> filterSlowMoverCandidates(
            List<SourceExtractor.DetectedObject> rawSlowMovers,
            short[][] slowMoverStackData,
            short[][] medianStackData,
            boolean[][] medianMask,
            DetectionConfig config,
            PipelineTelemetry.SlowMoverTelemetry telemetry
    ) {
        List<SlowMoverCandidateResult> filteredCandidates = new ArrayList<>();
        telemetry.rawCandidatesExtracted = rawSlowMovers.size();

        List<Double> elongations = new ArrayList<>(rawSlowMovers.size());
        for (SourceExtractor.DetectedObject obj : rawSlowMovers) {
            elongations.add(obj.elongation);
        }

        double medianElong = 0.0;
        double madElong = 0.1;
        if (!elongations.isEmpty()) {
            elongations.sort(Double::compareTo);
            medianElong = elongations.get(elongations.size() / 2);

            List<Double> deviations = new ArrayList<>(elongations.size());
            for (double e : elongations) {
                deviations.add(Math.abs(e - medianElong));
            }
            deviations.sort(Double::compareTo);
            madElong = Math.max(0.1, deviations.get(deviations.size() / 2));
        }

        double dynamicElongationThreshold = 3.0;
        if (elongations.size() >= 10) {
            dynamicElongationThreshold = medianElong + (madElong * config.slowMoverBaselineMadMultiplier);
        }
        boolean residualCoreFilteringEnabled = config.enableSlowMoverResidualCoreFiltering
                && slowMoverStackData != null
                && medianStackData != null;
        double minMedianSupportOverlap = Math.max(0.0, Math.min(1.0, config.slowMoverMedianSupportOverlapFraction));
        double maxMedianSupportOverlap = Math.max(minMedianSupportOverlap, Math.min(1.0, config.slowMoverMedianSupportMaxOverlapFraction));
        double minResidualCorePositiveFraction = Math.max(0.0, Math.min(1.0, config.slowMoverResidualCoreMinPositiveFraction));

        double medianSupportOverlapSum = 0.0;
        double residualCorePositiveFractionSum = 0.0;

        for (SourceExtractor.DetectedObject obj : rawSlowMovers) {
            if (obj.elongation < dynamicElongationThreshold) {
                continue;
            }
            telemetry.candidatesAboveElongationThreshold++;

            if (config.enableSlowMoverShapeFiltering) {
                if (SourceExtractor.isIrregularStreakShape(obj)) {
                    telemetry.rejectedIrregularShape++;
                    continue;
                }

                if (SourceExtractor.isBinaryStarAnomaly(slowMoverStackData, obj)) {
                    telemetry.rejectedBinaryAnomaly++;
                    continue;
                }
            }

            if (config.enableSlowMoverSpecificShapeFiltering) {
                SlowMoverShapeRejectReason shapeRejectReason = evaluateSlowMoverSpecificShapeFilter(obj);
                if (shapeRejectReason != SlowMoverShapeRejectReason.NONE) {
                    telemetry.rejectedSlowMoverShape++;
                    incrementSlowMoverShapeRejectTelemetry(telemetry, shapeRejectReason);
                    continue;
                }
            }

            double medianSupportOverlap = computeMaskOverlapFraction(obj, medianMask);

            telemetry.candidatesEvaluatedAgainstMasks++;
            medianSupportOverlapSum += medianSupportOverlap;

            if (medianSupportOverlap < minMedianSupportOverlap) {
                telemetry.rejectedLowMedianSupport++;
                continue;
            }
            if (medianSupportOverlap > maxMedianSupportOverlap) {
                telemetry.rejectedHighMedianSupport++;
                continue;
            }

            ResidualCoreMeasurement residualCoreMeasurement = computeResidualCoreMeasurement(
                    obj,
                    slowMoverStackData,
                    medianStackData,
                    config.slowMoverResidualCoreRadiusPixels
            );
            if (residualCoreFilteringEnabled) {
                if (residualCoreMeasurement.positiveFraction < minResidualCorePositiveFraction) {
                    telemetry.rejectedLowResidualCoreSupport++;
                    continue;
                }
                residualCorePositiveFractionSum += residualCoreMeasurement.positiveFraction;
            }

            filteredCandidates.add(new SlowMoverCandidateResult(
                    obj,
                    new SlowMoverCandidateDiagnostics(
                            medianSupportOverlap,
                            residualCoreMeasurement.positiveFraction,
                            residualCoreMeasurement.positivePixels,
                            residualCoreMeasurement.corePixels,
                            residualCoreMeasurement.effectiveRadiusPixels,
                            residualCoreFilteringEnabled
                    )
            ));
            telemetry.candidateMedianSupportOverlaps.add(medianSupportOverlap);
        }

        telemetry.candidatesDetected = filteredCandidates.size();
        telemetry.medianElongation = medianElong;
        telemetry.madElongation = madElong;
        telemetry.dynamicElongationThreshold = dynamicElongationThreshold;
        telemetry.medianSupportOverlapThreshold = minMedianSupportOverlap;
        telemetry.medianSupportMaxOverlapThreshold = maxMedianSupportOverlap;
        telemetry.avgMedianSupportOverlap = computeAverage(medianSupportOverlapSum, telemetry.candidatesEvaluatedAgainstMasks);
        telemetry.residualCoreMinPositiveFractionThreshold = residualCoreFilteringEnabled
                ? minResidualCorePositiveFraction
                : 0.0;
        telemetry.avgResidualCorePositiveFraction = residualCoreFilteringEnabled
                ? computeAverage(residualCorePositiveFractionSum, filteredCandidates.size())
                : 0.0;

        return filteredCandidates;
    }

    /**
     * Measures how much of the centroid-centered candidate core remains positive after subtracting the median stack.
     * This rejects candidates whose excess signal lives only in the wings or disappears entirely at the center.
     */
    private static double computeResidualCorePositiveFraction(
            SourceExtractor.DetectedObject obj,
            short[][] slowMoverStackData,
            short[][] medianStackData,
            double radiusPixels
    ) {
        return computeResidualCoreMeasurement(
                obj,
                slowMoverStackData,
                medianStackData,
                radiusPixels
        ).positiveFraction;
    }

    private static ResidualCoreMeasurement computeResidualCoreMeasurement(
            SourceExtractor.DetectedObject obj,
            short[][] slowMoverStackData,
            short[][] medianStackData,
            double radiusPixels
    ) {
        if (obj.rawPixels == null || obj.rawPixels.isEmpty()
                || slowMoverStackData == null || medianStackData == null
                || slowMoverStackData.length == 0 || medianStackData.length == 0) {
            return new ResidualCoreMeasurement(0.0, 0, 0, Math.max(0.5, radiusPixels));
        }

        double effectiveRadius = Math.max(0.5, radiusPixels);
        double radiusSquared = effectiveRadius * effectiveRadius;
        int positiveCorePixels = 0;
        int corePixels = 0;

        for (SourceExtractor.Pixel p : obj.rawPixels) {
            double dx = p.x - obj.x;
            double dy = p.y - obj.y;
            if ((dx * dx) + (dy * dy) > radiusSquared) {
                continue;
            }
            if (p.y < 0 || p.y >= slowMoverStackData.length || p.y >= medianStackData.length) {
                continue;
            }
            if (p.x < 0 || p.x >= slowMoverStackData[p.y].length || p.x >= medianStackData[p.y].length) {
                continue;
            }

            corePixels++;
            if (((int) slowMoverStackData[p.y][p.x]) - ((int) medianStackData[p.y][p.x]) > 0) {
                positiveCorePixels++;
            }
        }

        if (corePixels == 0) {
            return new ResidualCoreMeasurement(0.0, 0, 0, effectiveRadius);
        }
        return new ResidualCoreMeasurement(
                (double) positiveCorePixels / corePixels,
                positiveCorePixels,
                corePixels,
                effectiveRadius
        );
    }

    /**
     * Paints detected-object footprints into a boolean mask, with optional circular dilation.
     */
    private static boolean[][] buildObjectMask(
            List<SourceExtractor.DetectedObject> objects,
            int sensorWidth,
            int sensorHeight,
            int dilationRadius
    ) {
        boolean[][] mask = new boolean[sensorHeight][sensorWidth];
        int effectiveRadius = Math.max(0, dilationRadius);

        for (SourceExtractor.DetectedObject obj : objects) {
            if (obj.rawPixels == null) {
                continue;
            }
            for (SourceExtractor.Pixel p : obj.rawPixels) {
                if (effectiveRadius == 0) {
                    if (p.x >= 0 && p.x < sensorWidth && p.y >= 0 && p.y < sensorHeight) {
                        mask[p.y][p.x] = true;
                    }
                    continue;
                }

                for (int dx = -effectiveRadius; dx <= effectiveRadius; dx++) {
                    for (int dy = -effectiveRadius; dy <= effectiveRadius; dy++) {
                        if (dx * dx + dy * dy > effectiveRadius * effectiveRadius) {
                            continue;
                        }
                        int mx = p.x + dx;
                        int my = p.y + dy;
                        if (mx >= 0 && mx < sensorWidth && my >= 0 && my < sensorHeight) {
                            mask[my][mx] = true;
                        }
                    }
                }
            }
        }

        return mask;
    }

    /**
     * Measures what fraction of an object's footprint overlaps a precomputed boolean mask.
     */
    private static double computeMaskOverlapFraction(SourceExtractor.DetectedObject obj, boolean[][] mask) {
        if (obj.rawPixels == null || obj.rawPixels.isEmpty()) {
            return 0.0;
        }

        int overlapCount = 0;
        int sensorHeight = mask.length;
        int sensorWidth = sensorHeight > 0 ? mask[0].length : 0;
        for (SourceExtractor.Pixel p : obj.rawPixels) {
            if (p.x >= 0 && p.x < sensorWidth && p.y >= 0 && p.y < sensorHeight && mask[p.y][p.x]) {
                overlapCount++;
            }
        }
        return (double) overlapCount / obj.rawPixels.size();
    }

    /**
     * Returns zero instead of NaN when a telemetry bucket had no contributing candidates.
     */
    private static double computeAverage(double sum, int count) {
        return count > 0 ? sum / count : 0.0;
    }

    private static final class ResidualCoreMeasurement {
        private final double positiveFraction;
        private final int positivePixels;
        private final int corePixels;
        private final double effectiveRadiusPixels;

        private ResidualCoreMeasurement(double positiveFraction,
                                        int positivePixels,
                                        int corePixels,
                                        double effectiveRadiusPixels) {
            this.positiveFraction = positiveFraction;
            this.positivePixels = positivePixels;
            this.corePixels = corePixels;
            this.effectiveRadiusPixels = effectiveRadiusPixels;
        }
    }

    /**
     * Additional slow-mover-only veto for tiny hooked residuals that still slip past the shared shape filters.
     * A trustworthy deep-stack slow mover should look like one compact elongated PSF:
     * straight centerline, decent fill, and no strong side bulges along the major axis.
     */
    private enum SlowMoverShapeRejectReason {
        NONE,
        TOO_SHORT,
        LOW_FILL,
        SPARSE_BINS,
        GAPPED_BINS,
        CURVED_CENTERLINE,
        BULGED_WIDTH
    }

    private static void incrementSlowMoverShapeRejectTelemetry(
            PipelineTelemetry.SlowMoverTelemetry telemetry,
            SlowMoverShapeRejectReason reason
    ) {
        switch (reason) {
            case TOO_SHORT -> telemetry.rejectedSlowMoverShapeTooShort++;
            case LOW_FILL -> telemetry.rejectedSlowMoverShapeLowFill++;
            case SPARSE_BINS -> telemetry.rejectedSlowMoverShapeSparseBins++;
            case GAPPED_BINS -> telemetry.rejectedSlowMoverShapeGappedBins++;
            case CURVED_CENTERLINE -> telemetry.rejectedSlowMoverShapeCurvedCenterline++;
            case BULGED_WIDTH -> telemetry.rejectedSlowMoverShapeBulgedWidth++;
            case NONE -> {
                // No telemetry increment needed when the shape is accepted.
            }
        }
    }

    private static SlowMoverShapeRejectReason evaluateSlowMoverSpecificShapeFilter(SourceExtractor.DetectedObject obj) {
        if (obj.rawPixels == null || obj.rawPixels.isEmpty()) {
            return SlowMoverShapeRejectReason.TOO_SHORT;
        }

        double centroidX = 0.0;
        double centroidY = 0.0;
        for (SourceExtractor.Pixel p : obj.rawPixels) {
            centroidX += p.x;
            centroidY += p.y;
        }
        centroidX /= obj.rawPixels.size();
        centroidY /= obj.rawPixels.size();

        double covXX = 0.0;
        double covYY = 0.0;
        double covXY = 0.0;
        for (SourceExtractor.Pixel p : obj.rawPixels) {
            double vx = p.x - centroidX;
            double vy = p.y - centroidY;
            covXX += vx * vx;
            covYY += vy * vy;
            covXY += vx * vy;
        }

        double axisAngle = 0.5 * Math.atan2(2.0 * covXY, covXX - covYY);
        double dx = Math.cos(axisAngle);
        double dy = Math.sin(axisAngle);

        double minPar = Double.MAX_VALUE;
        double maxPar = -Double.MAX_VALUE;
        double minPerp = Double.MAX_VALUE;
        double maxPerp = -Double.MAX_VALUE;

        for (SourceExtractor.Pixel p : obj.rawPixels) {
            double vx = p.x - centroidX;
            double vy = p.y - centroidY;
            double par = vx * dx + vy * dy;
            double perp = -vx * dy + vy * dx;

            if (par < minPar) minPar = par;
            if (par > maxPar) maxPar = par;
            if (perp < minPerp) minPerp = perp;
            if (perp > maxPerp) maxPerp = perp;
        }

        double length = maxPar - minPar + 1.0;
        double width = maxPerp - minPerp + 1.0;
        double fillFactor = obj.rawPixels.size() / Math.max(1.0, length * width);

        if (length < 3.5) {
            return SlowMoverShapeRejectReason.TOO_SHORT;
        }

        if (length < 5.0) {
            if (fillFactor < 0.42) {
                return SlowMoverShapeRejectReason.LOW_FILL;
            }
            double compactAspect = length / Math.max(1.0, width);
            return compactAspect >= 1.20 ? SlowMoverShapeRejectReason.NONE : SlowMoverShapeRejectReason.TOO_SHORT;
        }

        if (fillFactor < 0.48) {
            return SlowMoverShapeRejectReason.LOW_FILL;
        }

        double compactAspect = length / Math.max(1.0, width);
        if (fillFactor >= 0.60 && compactAspect >= 1.35 && compactAspect <= 3.20) {
            return SlowMoverShapeRejectReason.NONE;
        }

        int bins = Math.max(4, Math.min(8, (int) Math.round(length)));
        double binSpan = Math.max(1.0, length / bins);
        int[] binCounts = new int[bins];
        double[] perpSums = new double[bins];
        double[] binMinPerp = new double[bins];
        double[] binMaxPerp = new double[bins];
        for (int i = 0; i < bins; i++) {
            binMinPerp[i] = Double.MAX_VALUE;
            binMaxPerp[i] = -Double.MAX_VALUE;
        }

        for (SourceExtractor.Pixel p : obj.rawPixels) {
            double vx = p.x - centroidX;
            double vy = p.y - centroidY;
            double par = vx * dx + vy * dy;
            double perp = -vx * dy + vy * dx;

            int bin = (int) ((par - minPar) / binSpan);
            if (bin < 0) bin = 0;
            if (bin >= bins) bin = bins - 1;

            binCounts[bin]++;
            perpSums[bin] += perp;
            if (perp < binMinPerp[bin]) binMinPerp[bin] = perp;
            if (perp > binMaxPerp[bin]) binMaxPerp[bin] = perp;
        }

        int firstOccupied = -1;
        int lastOccupied = -1;
        int occupiedBins = 0;
        for (int i = 0; i < bins; i++) {
            if (binCounts[i] > 0) {
                if (firstOccupied == -1) {
                    firstOccupied = i;
                }
                lastOccupied = i;
                occupiedBins++;
            }
        }

        if (occupiedBins < Math.max(3, bins - 3)) {
            return SlowMoverShapeRejectReason.SPARSE_BINS;
        }

        for (int i = firstOccupied + 1; i < lastOccupied; i++) {
            if (binCounts[i] == 0) {
                return SlowMoverShapeRejectReason.GAPPED_BINS;
            }
        }

        double minPerpMean = Double.MAX_VALUE;
        double maxPerpMean = -Double.MAX_VALUE;
        double maxPerpJump = 0.0;
        double minBinWidth = Double.MAX_VALUE;
        double maxBinWidth = -Double.MAX_VALUE;
        double maxBinWidthJump = 0.0;
        int widestBin = -1;
        double previousPerpMean = 0.0;
        double previousBinWidth = 0.0;
        boolean hasPrevious = false;

        for (int i = 0; i < bins; i++) {
            if (binCounts[i] == 0) {
                continue;
            }
            double perpMean = perpSums[i] / binCounts[i];
            double binWidth = binMaxPerp[i] - binMinPerp[i] + 1.0;
            if (perpMean < minPerpMean) minPerpMean = perpMean;
            if (perpMean > maxPerpMean) maxPerpMean = perpMean;
            if (binWidth < minBinWidth) minBinWidth = binWidth;
            if (binWidth > maxBinWidth) {
                maxBinWidth = binWidth;
                widestBin = i;
            }
            if (hasPrevious) {
                double jump = Math.abs(perpMean - previousPerpMean);
                if (jump > maxPerpJump) {
                    maxPerpJump = jump;
                }
                double widthJump = Math.abs(binWidth - previousBinWidth);
                if (widthJump > maxBinWidthJump) {
                    maxBinWidthJump = widthJump;
                }
            }
            previousPerpMean = perpMean;
            previousBinWidth = binWidth;
            hasPrevious = true;
        }

        double centerlineExcursion = maxPerpMean - minPerpMean;
        double allowedExcursion = Math.max(1.20, width * 0.80);
        double allowedJump = Math.max(1.00, width * 0.65);
        double allowedMaxBinWidth = Math.max(3.0, minBinWidth * 2.4);
        double allowedBinWidthJump = Math.max(1.45, width * 0.60);
        boolean endHeavyBulge = widestBin != -1
                && (widestBin - firstOccupied <= 1 || lastOccupied - widestBin <= 1)
                && maxBinWidth > Math.max(2.5, minBinWidth * 1.8);

        if (centerlineExcursion > allowedExcursion || maxPerpJump > allowedJump) {
            return SlowMoverShapeRejectReason.CURVED_CENTERLINE;
        }
        if (maxBinWidth > allowedMaxBinWidth
                || maxBinWidthJump > allowedBinWidthJump
                || endHeavyBulge) {
            return SlowMoverShapeRejectReason.BULGED_WIDTH;
        }
        return SlowMoverShapeRejectReason.NONE;
    }
}
