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
    }

    @Test
    public void aNebulaWiderThanASkyBoxStaysMaskedAndFaintStarsOnTheDarkSideAreAdded() {
        int width = 2048;
        int height = 1536;
        java.util.Random random = new java.util.Random(7);
        short[][] stack = new short[height][width];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                double sky = 1600 - 200.0 * x / (width - 1);
                double nebula = x < 700 && y > 500 && y < 950 ? 1200 : 0; // wider than a sky box
                stack[y][x] = (short) (Math.round(sky + nebula + 15 * random.nextGaussian()) - 32768);
            }
        }
        int starX = 1900;
        int starY = 760;
        for (int dy = -4; dy <= 4; dy++) {
            for (int dx = -4; dx <= 4; dx++) {
                stack[starY + dy][starX + dx] += (short) Math.round(150 * Math.exp(-(dx * dx + dy * dy) / (2 * 1.3 * 1.3)));
            }
        }
        DetectionConfig config = new DetectionConfig();
        config.growSigmaMultiplier = 3;

        java.util.List<SourceExtractor.DetectedObject> stars = MasterReferenceAnalyzer.extractStars(stack, 3, 3, config);

        boolean nebulaCovered = false;
        boolean starFound = false;
        java.util.Set<Long> seen = new java.util.HashSet<>();
        for (SourceExtractor.DetectedObject star : stars) {
            for (SourceExtractor.Pixel p : star.rawPixels) {
                assertTrue("pixel " + p.x + "," + p.y + " in two objects", seen.add((long) p.y * width + p.x));
                if (p.x == 350 && p.y == 725) {
                    nebulaCovered = true;
                }
            }
            if (Math.hypot(star.x - starX, star.y - starY) < 2) {
                starFound = true;
            }
        }
        assertTrue("the nebula is covered", nebulaCovered);
        assertTrue("the faint star on the dark side is found", starFound);
    }

    @Test
    public void thePassAgainstTheLocalSkyAddsNoNoisePeaks() {
        int width = 1536;
        int height = 1024;
        java.util.Random random = new java.util.Random(8);
        short[][] stack = new short[height][width];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                stack[y][x] = (short) (Math.round(1600 - 300.0 * x / (width - 1) + 15 * random.nextGaussian()) - 32768);
            }
        }
        DetectionConfig config = new DetectionConfig();
        config.growSigmaMultiplier = 2.25;

        int firstPass = SourceExtractor.extractSources(stack, 2.25, 3, config, null,
                DataArea.wholeImage(width, height)).objects.size();

        assertEquals(firstPass, MasterReferenceAnalyzer.extractStars(stack, 2.25, 3, config).size());
    }
}
