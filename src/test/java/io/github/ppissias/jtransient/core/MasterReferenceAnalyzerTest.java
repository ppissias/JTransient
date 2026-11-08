package io.github.ppissias.jtransient.core;

import io.github.ppissias.jtransient.model.DetectedObject;
import io.github.ppissias.jtransient.model.Pixel;
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

        java.util.List<DetectedObject> stars = MasterReferenceAnalyzer.extractStars(stack, 3, 3, config);

        boolean nebulaCovered = false;
        boolean starFound = false;
        java.util.Set<Long> seen = new java.util.HashSet<>();
        for (DetectedObject star : stars) {
            for (Pixel p : star.rawPixels) {
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

    /**
     * A master whose frames drifted far keeps a wide band of registration fill. Measured with the sky, the fill inflated
     * the noise about 240-fold on a real session and the star mask came out empty; measured in the data area, the stars
     * are found.
     */
    @Test
    public void wideRegistrationFillDoesNotEmptyTheStarMask() {
        int width = 600;
        int height = 400;
        int dataWidth = 400;  // a third of the width is fill
        int dataHeight = 320; // and a fifth of the height
        java.util.Random random = new java.util.Random(11);
        short[][] stack = new short[height][width];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                stack[y][x] = x < dataWidth && y < dataHeight
                        ? (short) (Math.round(16000 + 25 * random.nextGaussian()) - 32768)
                        : Short.MIN_VALUE;
            }
        }
        java.util.List<int[]> placed = new java.util.ArrayList<>();
        for (int i = 0; i < 12; i++) {
            int sx = 30 + (i % 4) * 100;
            int sy = 40 + (i / 4) * 100;
            placed.add(new int[]{sx, sy});
            for (int dy = -5; dy <= 5; dy++) {
                for (int dx = -5; dx <= 5; dx++) {
                    stack[sy + dy][sx + dx] += (short) Math.round(600 * Math.exp(-(dx * dx + dy * dy) / (2 * 1.5 * 1.5)));
                }
            }
        }
        DetectionConfig config = new DetectionConfig();
        config.growSigmaMultiplier = 2.5;

        assertTrue("the whole-image sky finds no star: the failure this guards against",
                SourceExtractor.extractSources(stack, 3.5, 5, config, null, DataArea.wholeImage(width, height)).objects.isEmpty());
        java.util.List<DetectedObject> stars = MasterReferenceAnalyzer.extractStars(stack, 3.5, 5, config);
        for (int[] star : placed) {
            assertTrue("star at " + star[0] + "," + star[1] + " is in the mask",
                    stars.stream().anyMatch(s -> Math.hypot(s.x - star[0], s.y - star[1]) < 2));
        }
        assertEquals(placed.size(), stars.size());
    }

    @Test
    public void aStackWithoutFillMeasuresItsSkyOnTheWholeImage() {
        short[][] stack = new short[50][60];
        assertEquals(null, MasterReferenceAnalyzer.skyArea(stack));
        for (int y = 0; y < 50; y++) {
            for (int x = 40; x < 60; x++) {
                stack[y][x] = Short.MIN_VALUE;
            }
        }
        DataArea sky = MasterReferenceAnalyzer.skyArea(stack);
        assertTrue(sky != null && sky.getPixelCount() > 0 && sky.getPixelCount() < 50L * 60);
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
