package io.github.ppissias.jtransient.core;

import io.github.ppissias.jtransient.config.DetectionConfig;
import io.github.ppissias.jtransient.engine.ImageFrame;
import io.github.ppissias.jtransient.telemetry.PipelineTelemetry;

import java.util.ArrayList;
import java.util.List;

/**
 * Finds footprints consistent with slow motion in a maximum stack, then uses an independent
 * median-stack mask to reject footprints mostly explained by persistent sources.
 * The result is a list of morphology candidates, not confirmed astrometric motion.
 */
public final class SlowMoverAnalyzer {
    private SlowMoverAnalyzer() {
    }

    /**
     * Builds a maximum stack for callers that only have aligned, quality-filtered frames.
     * The engine uses the stack overload to share its already exported maximum stack.
     *
     * @param cleanFrames aligned frames retained by quality filtering
     * @param medianStackData median stack of the same frames
     * @param config extraction and candidate-filter thresholds
     * @return candidates, stack products, and stage telemetry
     */
    public static SlowMoverAnalysis analyze(List<ImageFrame> cleanFrames,
                                            short[][] medianStackData,
                                            DetectionConfig config) {
        if (config == null || !config.enableSlowMoverDetection || cleanFrames == null || cleanFrames.isEmpty()) {
            return SlowMoverAnalysis.empty();
        }
        return analyze(MasterMapGenerator.createMaximumMasterStack(cleanFrames), medianStackData, cleanFrames, config);
    }

    /**
     * Extracts connected components from the maximum stack and exact stationary footprints
     * from the median stack. Neither stack is subtracted from the other.
     *
     * @param maximumStackData precomputed per-pixel maximum, indexed as [y][x]
     * @param medianStackData per-pixel median with matching dimensions and encoding
     * @param config extraction and candidate-filter thresholds
     * @return accepted morphology candidates and the mask used to evaluate them
     */
    public static SlowMoverAnalysis analyze(short[][] maximumStackData,
                                            short[][] medianStackData,
                                            DetectionConfig config) {
        return analyze(maximumStackData, medianStackData, null, config);
    }

    /**
     * Also measures per-frame candidate support and stationarity when the contributing frames are available.
     * The stack-only overload keeps these measurements unavailable and does not apply their gates.
     *
     * @param maximumStackData precomputed per-pixel maximum, indexed as [y][x]
     * @param medianStackData per-pixel median with matching dimensions and encoding
     * @param cleanFrames aligned quality-filtered frames that produced the maximum stack
     * @param config extraction and candidate-filter thresholds
     * @return accepted candidates, stack products, and stage telemetry
     */
    public static SlowMoverAnalysis analyze(short[][] maximumStackData,
                                            short[][] medianStackData,
                                            List<ImageFrame> cleanFrames,
                                            DetectionConfig config) {
        if (config == null || !config.enableSlowMoverDetection
                || maximumStackData == null || maximumStackData.length == 0 || maximumStackData[0].length == 0
                || medianStackData == null || medianStackData.length != maximumStackData.length
                || medianStackData[0].length != maximumStackData[0].length) {
            return SlowMoverAnalysis.empty();
        }

        // Extraction needs a stage-specific grow threshold without changing the caller's config.
        DetectionConfig extractionConfig = config.clone();
        extractionConfig.growSigmaMultiplier = config.masterSlowMoverGrowSigmaMultiplier;

        List<SourceExtractor.DetectedObject> maximumObjects = SourceExtractor.extractSources(
                maximumStackData,
                config.masterSlowMoverSigmaMultiplier,
                config.masterSlowMoverMinPixels,
                extractionConfig
        ).objects;
        List<SourceExtractor.DetectedObject> medianObjects = SourceExtractor.extractSources(
                medianStackData,
                config.masterSlowMoverSigmaMultiplier,
                config.masterSlowMoverMinPixels,
                extractionConfig
        ).objects;

        boolean[][] medianMask = buildMedianMask(
                medianObjects, maximumStackData[0].length, maximumStackData.length
        );
        PipelineTelemetry.SlowMoverTelemetry telemetry = new PipelineTelemetry.SlowMoverTelemetry();
        List<SlowMoverCandidateResult> candidates = filterCandidates(maximumObjects, medianMask,
                cleanFrames, config, telemetry);
        return new SlowMoverAnalysis(maximumStackData, medianMask, candidates, new SlowMoverSummaryTelemetry(telemetry));
    }

    /** Applies the size, shape, then median-mask gates in that order. */
    private static List<SlowMoverCandidateResult> filterCandidates(
            List<SourceExtractor.DetectedObject> maximumObjects,
            boolean[][] medianMask,
            List<ImageFrame> cleanFrames,
            DetectionConfig config,
            PipelineTelemetry.SlowMoverTelemetry telemetry
    ) {
        List<SlowMoverCandidateResult> candidates = new ArrayList<>();
        telemetry.rawCandidatesExtracted = maximumObjects.size();
        // Record the effective thresholds actually used after clamping invalid ranges.
        telemetry.minAxisRatioThreshold = Math.max(1.0, config.slowMoverMinAxisRatio);
        telemetry.maxAxisRatioThreshold = Math.max(telemetry.minAxisRatioThreshold, config.slowMoverMaxAxisRatio);
        telemetry.minFillFactorThreshold = Math.max(0.0, config.slowMoverMinFillFactor);
        telemetry.medianSupportOverlapThreshold = Math.max(0.0, Math.min(1.0, config.slowMoverMedianSupportOverlapFraction));
        telemetry.medianSupportMaxOverlapThreshold = Math.max(
                telemetry.medianSupportOverlapThreshold,
                Math.min(1.0, config.slowMoverMedianSupportMaxOverlapFraction)
        );
        telemetry.minFrameSupportThreshold = Math.max(0.0, Math.min(100.0, config.slowMoverMinFrameSupport));
        telemetry.maxStationaryLikelihoodThreshold = Math.max(0.0,
                Math.min(100.0, config.slowMoverMaxStationaryLikelihood));

        double acceptedAxisRatioSum = 0.0;
        double acceptedMotionPixelsSum = 0.0;
        double acceptedMotionDiametersSum = 0.0;
        double evaluatedOverlapSum = 0.0;
        telemetry.minCandidateAxisRatio = Double.POSITIVE_INFINITY;

        for (SourceExtractor.DetectedObject object : maximumObjects) {
            if (object.rawPixels == null || object.rawPixels.size() < config.masterSlowMoverMinPixels) {
                telemetry.rejectedBelowMinPixels++;
                continue;
            }

            // Shape distributions include every size-qualified maximum-stack component.
            telemetry.candidateAxisRatios.add(object.axisRatio);
            telemetry.candidateMomentElongations.add(object.elongation);
            telemetry.candidateFillFactors.add(object.fillFactor);

            if (object.axisRatio < telemetry.minAxisRatioThreshold) {
                telemetry.rejectedBelowMinAxisRatio++;
                continue;
            }
            if (object.axisRatio > telemetry.maxAxisRatioThreshold) {
                telemetry.rejectedAboveMaxAxisRatio++;
                continue;
            }
            if (object.fillFactor < telemetry.minFillFactorThreshold) {
                telemetry.rejectedLowFillFactor++;
                continue;
            }

            // The upper overlap bound is the stationary-source veto; the lower bound is optional support.
            double overlap = computeMaskOverlapFraction(object, medianMask);
            telemetry.evaluatedAgainstMedianMask++;
            telemetry.candidateMedianMaskOverlaps.add(overlap);
            evaluatedOverlapSum += overlap;

            if (overlap < telemetry.medianSupportOverlapThreshold) {
                telemetry.rejectedLowMedianSupport++;
                continue;
            }
            if (overlap > telemetry.medianSupportMaxOverlapThreshold) {
                telemetry.rejectedHighMedianSupport++;
                continue;
            }

            SlowMoverFrameEvidence frameEvidence = SlowMoverFrameEvidence.measure(object, cleanFrames, config);
            if (frameEvidence.frameSupportAvailable) {
                telemetry.evaluatedAgainstFrames++;
                if (frameEvidence.frameSupportPercentage < telemetry.minFrameSupportThreshold) {
                    telemetry.rejectedLowFrameSupport++;
                    continue;
                }
            } else {
                telemetry.frameEvidenceUnavailable++;
            }
            if (frameEvidence.stationaryLikelihoodAvailable
                    && frameEvidence.stationaryLikelihoodPercentage > telemetry.maxStationaryLikelihoodThreshold) {
                telemetry.rejectedHighStationaryLikelihood++;
                continue;
            }

            SlowMoverCandidateDiagnostics diagnostics = new SlowMoverCandidateDiagnostics(
                    object, overlap, telemetry.minAxisRatioThreshold, telemetry.maxAxisRatioThreshold,
                    telemetry.minFillFactorThreshold, telemetry.medianSupportOverlapThreshold,
                    telemetry.medianSupportMaxOverlapThreshold, frameEvidence,
                    telemetry.minFrameSupportThreshold, telemetry.maxStationaryLikelihoodThreshold
            );
            candidates.add(new SlowMoverCandidateResult(object, diagnostics));
            telemetry.candidateMedianSupportOverlaps.add(overlap);
            telemetry.candidateFrameSupportPercentages.add(diagnostics.frameSupportPercentage);
            telemetry.candidateStationaryLikelihoodPercentages.add(diagnostics.stationaryLikelihoodPercentage);
            telemetry.candidateFrameSupportAvailable.add(diagnostics.frameSupportAvailable);
            telemetry.candidateStationaryLikelihoodAvailable.add(diagnostics.stationaryLikelihoodAvailable);
            acceptedAxisRatioSum += object.axisRatio;
            acceptedMotionPixelsSum += diagnostics.estimatedMotionPixels;
            acceptedMotionDiametersSum += diagnostics.estimatedMotionDiameters;
            telemetry.minCandidateAxisRatio = Math.min(telemetry.minCandidateAxisRatio, object.axisRatio);
            telemetry.maxCandidateAxisRatio = Math.max(telemetry.maxCandidateAxisRatio, object.axisRatio);
        }

        telemetry.candidatesDetected = candidates.size();
        // Morphology and motion means describe accepted candidates; overlap mean describes mask evaluations.
        telemetry.avgCandidateAxisRatio = average(acceptedAxisRatioSum, candidates.size());
        telemetry.avgEstimatedMotionPixels = average(acceptedMotionPixelsSum, candidates.size());
        telemetry.avgEstimatedMotionDiameters = average(acceptedMotionDiametersSum, candidates.size());
        telemetry.avgMedianMaskOverlap = average(evaluatedOverlapSum, telemetry.evaluatedAgainstMedianMask);
        if (candidates.isEmpty()) {
            telemetry.minCandidateAxisRatio = 0.0;
        }
        return candidates;
    }

    /** Paints only detected median-object raw pixels; the mask is not dilated. */
    private static boolean[][] buildMedianMask(List<SourceExtractor.DetectedObject> medianObjects,
                                               int width,
                                               int height) {
        boolean[][] mask = new boolean[height][width];
        for (SourceExtractor.DetectedObject object : medianObjects) {
            if (object.rawPixels == null) {
                continue;
            }
            for (SourceExtractor.Pixel pixel : object.rawPixels) {
                mask[pixel.y][pixel.x] = true;
            }
        }
        return mask;
    }

    /** Returns the fraction of the maximum-stack candidate's raw pixels inside the median mask. */
    private static double computeMaskOverlapFraction(SourceExtractor.DetectedObject object, boolean[][] mask) {
        int overlapCount = 0;
        for (SourceExtractor.Pixel pixel : object.rawPixels) {
            if (mask[pixel.y][pixel.x]) {
                overlapCount++;
            }
        }
        return (double) overlapCount / object.rawPixels.size();
    }

    /** Empty telemetry buckets are reported as zero rather than NaN. */
    private static double average(double sum, int count) {
        return count > 0 ? sum / count : 0.0;
    }
}
