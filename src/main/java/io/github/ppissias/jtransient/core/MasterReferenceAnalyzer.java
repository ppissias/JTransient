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

import java.util.List;

/**
 * Builds the median master reference stack and extracts the stationary objects used for veto masking.
 */
public final class MasterReferenceAnalyzer {

    private MasterReferenceAnalyzer() {
    }

    /**
     * Immutable result of the master-reference stage.
     */
    public static final class MasterReferenceAnalysis {
        public final short[][] masterStackData;
        public final List<SourceExtractor.DetectedObject> masterStars;
        public final int sensorWidth;
        public final int sensorHeight;

        public MasterReferenceAnalysis(short[][] masterStackData,
                                       List<SourceExtractor.DetectedObject> masterStars,
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
                : MasterMapGenerator.createMedianMasterStack(cleanFrames);
        return analyzeFromMasterStack(masterStackData, config);
    }

    /**
     * Extracts stationary master-star reference objects from an existing median master stack.
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
        extractionConfig.growSigmaMultiplier = extractionConfig.masterSigmaMultiplier;
        extractionConfig.edgeMarginPixels = 5;
        extractionConfig.voidProximityRadius = 5;

        List<SourceExtractor.DetectedObject> masterStars = SourceExtractor.extractSources(
                masterStackData,
                extractionConfig.masterSigmaMultiplier,
                extractionConfig.masterMinDetectionPixels,
                extractionConfig
        ).objects;

        return new MasterReferenceAnalysis(masterStackData, masterStars, sensorWidth, sensorHeight);
    }
}
