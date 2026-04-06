package io.github.ppissias.jtransient.core;

import io.github.ppissias.jtransient.config.DetectionConfig;
import org.junit.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Regression coverage for shape-only rejection of close binary-star streak impostors.
 */
public class TrackLinkerBinaryStarStreakFilterTest {
    private static final String[] BINARY_STREAK_MASKS = {
            "binary-streak-shape-01.png",
            "binary-streak-shape-02.png"
    };

    @Test
    public void binaryStarLikeStreakShapeRejectsRealMasksAtNativeAndScaledSizes() throws Exception {
        for (String resourceName : BINARY_STREAK_MASKS) {
            SourceExtractor.DetectedObject obj = loadStreakObjectFromMaskResource(resourceName);
            assertTrue(SourceExtractor.isBinaryStarLikeStreakShape(obj));
            assertTrue(SourceExtractor.isBinaryStarLikeStreakShape(scaleObject(obj, 2)));
        }
    }

    @Test
    public void filterTransientsPreservesSingleFrameBinaryStarLikeStreakButRejectsItAsSingleTrack() throws Exception {
        DetectionConfig config = new DetectionConfig();
        SourceExtractor.DetectedObject obj = loadStreakObjectFromMaskResource(BINARY_STREAK_MASKS[0]);
        obj.sourceFrameIndex = 0;

        List<List<SourceExtractor.DetectedObject>> frames = new ArrayList<>();
        frames.add(List.of(obj));
        frames.add(new ArrayList<>());
        frames.add(new ArrayList<>());

        TrackLinker.TransientsFilterResult result = TrackLinker.filterTransients(
                frames,
                new ArrayList<>(),
                config,
                null,
                64,
                64
        );

        assertTrue(result.streakTracks.isEmpty());
        assertEquals(1, result.telemetry.rejectedBinaryStarStreakShape);
        assertEquals(1, result.allTransients.get(0).size());
        assertTrue(result.allTransients.get(0).contains(obj));
    }

    @Test
    public void filterTransientsKeepsSimpleSingleFrameLinearStreak() {
        DetectionConfig config = new DetectionConfig();
        SourceExtractor.DetectedObject obj = createSimpleSingleFrameStreak(10, 12, 14, 0);

        List<List<SourceExtractor.DetectedObject>> frames = new ArrayList<>();
        frames.add(List.of(obj));
        frames.add(new ArrayList<>());
        frames.add(new ArrayList<>());

        TrackLinker.TransientsFilterResult result = TrackLinker.filterTransients(
                frames,
                new ArrayList<>(),
                config,
                null,
                64,
                64
        );

        assertEquals(1, result.streakTracks.size());
        assertEquals(0, result.telemetry.rejectedBinaryStarStreakShape);
        assertFalse(result.streakTracks.get(0).points.isEmpty());
    }

    private static SourceExtractor.DetectedObject loadStreakObjectFromMaskResource(String resourceName) throws Exception {
        try (InputStream input = TrackLinkerBinaryStarStreakFilterTest.class.getClassLoader()
                .getResourceAsStream("streak-shapes/" + resourceName)) {
            if (input == null) {
                throw new IllegalArgumentException("Missing test resource: " + resourceName);
            }
            BufferedImage image = ImageIO.read(input);
            List<SourceExtractor.Pixel> pixels = new ArrayList<>();
            for (int y = 0; y < image.getHeight(); y++) {
                for (int x = 0; x < image.getWidth(); x++) {
                    int rgb = image.getRGB(x, y);
                    int r = (rgb >> 16) & 0xFF;
                    int g = (rgb >> 8) & 0xFF;
                    int b = rgb & 0xFF;
                    if (r == 0 && g == 0 && b == 0) {
                        continue;
                    }
                    pixels.add(new SourceExtractor.Pixel(x, y, (short) Math.max(r, Math.max(g, b))));
                }
            }

            if (pixels.isEmpty()) {
                throw new IllegalArgumentException("No object pixels found in test resource: " + resourceName);
            }

            SourceExtractor.DetectedObject obj = analyzePixels(pixels);
            obj.isStreak = true;
            obj.peakSigma = 9.0;
            return obj;
        }
    }

    private static SourceExtractor.DetectedObject scaleObject(SourceExtractor.DetectedObject source, int scaleFactor) {
        List<SourceExtractor.Pixel> scaledPixels = new ArrayList<>(source.rawPixels.size() * scaleFactor * scaleFactor);
        for (SourceExtractor.Pixel pixel : source.rawPixels) {
            for (int dy = 0; dy < scaleFactor; dy++) {
                for (int dx = 0; dx < scaleFactor; dx++) {
                    scaledPixels.add(new SourceExtractor.Pixel(
                            pixel.x * scaleFactor + dx,
                            pixel.y * scaleFactor + dy,
                            pixel.value
                    ));
                }
            }
        }

        SourceExtractor.DetectedObject scaled = analyzePixels(scaledPixels);
        scaled.isStreak = true;
        scaled.peakSigma = 9.0;
        return scaled;
    }

    private static SourceExtractor.DetectedObject analyzePixels(List<SourceExtractor.Pixel> pixels) {
        SourceExtractor.BackgroundMetrics bg = new SourceExtractor.BackgroundMetrics();
        bg.median = 0.0;
        bg.sigma = 1.0;
        bg.threshold = 1.0;

        SourceExtractor.DetectedObject obj = SourceExtractor.analyzeShape(pixels, bg, new DetectionConfig());
        obj.rawPixels = pixels;
        obj.pixelArea = pixels.size();
        return obj;
    }

    private static SourceExtractor.DetectedObject createSimpleSingleFrameStreak(int xStart, int y, int length, int frameIndex) {
        List<SourceExtractor.Pixel> pixels = new ArrayList<>();
        for (int x = 0; x < length; x++) {
            pixels.add(new SourceExtractor.Pixel(xStart + x, y, (short) 200));
        }

        SourceExtractor.DetectedObject obj = analyzePixels(pixels);
        obj.isStreak = true;
        obj.peakSigma = 9.0;
        obj.sourceFrameIndex = frameIndex;
        return obj;
    }
}
