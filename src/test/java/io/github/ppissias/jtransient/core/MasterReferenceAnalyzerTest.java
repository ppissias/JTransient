package io.github.ppissias.jtransient.core;

import io.github.ppissias.jtransient.config.DetectionConfig;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class MasterReferenceAnalyzerTest {

    @Test
    public void analyzeFromMasterStackExtractsStarsWithoutMutatingCallerConfig() {
        DetectionConfig config = new DetectionConfig();
        config.masterSigmaMultiplier = 1.5;
        config.masterMinDetectionPixels = 3;
        config.growSigmaMultiplier = 7.5;
        config.edgeMarginPixels = 21;
        config.voidProximityRadius = 34;

        short[][] masterStack = new short[30][30];
        for (int y = 14; y <= 16; y++) {
            for (int x = 14; x <= 16; x++) {
                masterStack[y][x] = 1000;
            }
        }

        MasterReferenceAnalyzer.MasterReferenceAnalysis analysis =
                MasterReferenceAnalyzer.analyzeFromMasterStack(masterStack, config);

        assertEquals(30, analysis.sensorWidth);
        assertEquals(30, analysis.sensorHeight);
        assertEquals(masterStack, analysis.masterStackData);
        assertTrue(analysis.masterStars.size() >= 1);

        assertEquals(7.5, config.growSigmaMultiplier, 0.0);
        assertEquals(21, config.edgeMarginPixels);
        assertEquals(34, config.voidProximityRadius);
    }
}
