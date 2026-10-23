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

import io.github.ppissias.jtransient.model.DetectedObject;
import io.github.ppissias.jtransient.model.Pixel;
import io.github.ppissias.jtransient.execution.OperationContext;
import io.github.ppissias.jtransient.execution.StageId;

import io.github.ppissias.jtransient.execution.CancellationScope;

import io.github.ppissias.jtransient.config.DetectionConfig;
import io.github.ppissias.jtransient.engine.ImageFrame;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

/**
 * Builds the median master reference stack and extracts the stationary objects used for veto masking.
 *
 * <p>The stars are found in two passes ({@link #extractStars}). With one sky level for the whole stack, a sky gradient
 * makes the threshold too high on the dark side, where faint stars were then left out of the mask and found again in
 * the frames; a second pass against the local sky ({@link SkyBackground}) adds them. The first pass stays because the
 * frames are still searched with one sky level each: they detect bright emission larger than a sky box (nebulae,
 * galaxy disks) as well, which only the first pass masks.</p>
 */
public final class MasterReferenceAnalyzer {
    /**
     * Lowest seed of the pass against the local sky, in units of the stack's noise. A stationary source that a frame can
     * detect stands out of the median of ten or more frames by about 5 sigma or more. A lower seed adds noise peaks once
     * the gradient no longer inflates the noise (on Apophis the master sigma of 2.25 added 565 objects, one of them on
     * the path of Apophis itself).
     */
    static final double LOCAL_PASS_MIN_SIGMA = 5.0;

    private MasterReferenceAnalyzer() {
    }

    /**
     * Immutable result of the master-reference stage.
     */
    public static final class MasterReferenceAnalysis {
        public final short[][] masterStackData;
        public final List<DetectedObject> masterStars;
        public final int sensorWidth;
        public final int sensorHeight;

        public MasterReferenceAnalysis(short[][] masterStackData,
                                       List<DetectedObject> masterStars,
                                       int sensorWidth,
                                       int sensorHeight) {
            this.masterStackData = masterStackData;
            this.masterStars = masterStars;
            this.sensorWidth = sensorWidth;
            this.sensorHeight = sensorHeight;
        }
    }

    /**
     * Generates the median master stack when needed, then extracts the stationary master-star reference objects.
     */
    public static MasterReferenceAnalysis analyze(List<ImageFrame> cleanFrames,
                                                  short[][] providedMasterStack,
                                                  DetectionConfig config) {
        short[][] masterStackData = providedMasterStack != null
                ? providedMasterStack
                : OperationContext.compute(StageId.MEDIAN_STACK, null, () -> MasterMapGenerator.createMedianMasterStack(cleanFrames));
        return analyzeFromMasterStack(masterStackData, config);
    }

    /**
     * Grow threshold used for the Master Star Map: {@code masterGrowSigmaMultiplier} when set (and not above
     * the master seed), otherwise the master seed itself.
     */
    public static double effectiveMasterGrowSigma(DetectionConfig config) {
        double grow = config.masterGrowSigmaMultiplier;
        return grow > 0 ? Math.min(grow, config.masterSigmaMultiplier) : config.masterSigmaMultiplier;
    }

    /**
     * Extracts stationary master-star reference objects from an existing median master stack
     * ({@link #extractStars}). The returned analysis holds the stack as given.
     */
    public static MasterReferenceAnalysis analyzeFromMasterStack(short[][] masterStackData,
                                                                 DetectionConfig config) {
        if (masterStackData == null || masterStackData.length == 0 || masterStackData[0].length == 0) {
            throw new IllegalArgumentException("masterStackData must be non-empty");
        }
        if (config == null) {
            throw new IllegalArgumentException("config must not be null");
        }

        int sensorHeight = masterStackData.length;
        int sensorWidth = masterStackData[0].length;
        DetectionConfig extractionConfig = config.clone();

        // Use a stage-local config so master-star extraction cannot leak temporary thresholds back to callers.
        extractionConfig.growSigmaMultiplier = effectiveMasterGrowSigma(config);

        List<DetectedObject> masterStars = extractStars(masterStackData,
                extractionConfig.masterSigmaMultiplier, extractionConfig.masterMinDetectionPixels, extractionConfig);

        return new MasterReferenceAnalysis(masterStackData, masterStars, sensorWidth, sensorHeight);
    }

    /**
     * The stationary objects of a median stack, in two passes: first with one sky level for the whole stack, then on a
     * copy with the smooth sky subtracted ({@link SkyBackground#flatten}), seeded at {@link #LOCAL_PASS_MIN_SIGMA} or
     * the given sigma if higher and grown as the first. An object of the second pass is added when it shares no pixel
     * with one of the first, so the list holds no object twice and the star mask covers at least what the first pass
     * alone covers. Objects are kept up to the image edge: the star mask has to cover every star that a frame
     * detection inside the data area can touch, including stars cut by the edge.
     *
     * @param stack median stack, stored (signed-short) pixels
     * @param sigma seed threshold in units of the measured noise
     * @param minPixels minimum object size
     * @param config extraction settings; its {@code growSigmaMultiplier} is the grow threshold
     */
    public static List<DetectedObject> extractStars(short[][] stack, double sigma, int minPixels,
                                                                    DetectionConfig config) {
        int height = stack.length;
        int width = stack[0].length;
        DataArea wholeImage = DataArea.wholeImage(width, height);
        List<DetectedObject> stars = new ArrayList<>(
                OperationContext.compute(StageId.MASTER_REFERENCE, null, () -> SourceExtractor.extractSources(stack, sigma, minPixels, config, null, wholeImage)).objects);
        short[][] flat = SkyBackground.flatten(stack);
        if (flat == stack) {
            return stars;
        }
        BitSet covered = new BitSet(width * height);
        for (DetectedObject star : stars) {
            CancellationScope.check();
            if (star.rawPixels != null) {
                for (Pixel p : star.rawPixels) {
                    covered.set(p.y * width + p.x);
                }
            }
        }
        for (DetectedObject candidate
                : OperationContext.compute(StageId.MASTER_REFERENCE, null, () -> SourceExtractor.extractSources(flat, Math.max(sigma, LOCAL_PASS_MIN_SIGMA), minPixels, config, null,
                wholeImage)).objects) {
            if (candidate.rawPixels == null) {
                continue;
            }
            boolean overlaps = false;
            for (Pixel p : candidate.rawPixels) {
                CancellationScope.check();
                if (covered.get(p.y * width + p.x)) {
                    overlaps = true;
                    break;
                }
            }
            if (!overlaps) {
                stars.add(candidate);
            }
        }
        return stars;
    }
}
