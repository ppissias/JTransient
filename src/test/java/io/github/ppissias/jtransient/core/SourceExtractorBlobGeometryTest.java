package io.github.ppissias.jtransient.core;

import io.github.ppissias.jtransient.config.DetectionConfig;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Tests geometric raw-pixel measurements independently of image extraction thresholds. */
public class SourceExtractorBlobGeometryTest {
    /** A disk has equal extents in every orientation. */
    @Test
    public void circularFootprintHasUnitAxisRatio() {
        SourceExtractor.DetectedObject object = analyzeCapsule(0.0, 3.5, 0.0);

        assertEquals(1.0, object.axisRatio, 0.05);
        assertEquals(object.majorExtent, object.minorExtent, 0.05);
        assertTrue(object.fillFactor > 0.6);
    }

    /** Rotating the same capsule should preserve its motion-scale axis ratio within grid error. */
    @Test
    public void capsuleGeometryIsStableAcrossRotations() {
        SourceExtractor.DetectedObject horizontal = analyzeCapsule(3.0, 2.5, 0.0);
        SourceExtractor.DetectedObject vertical = analyzeCapsule(3.0, 2.5, Math.PI / 2.0);
        SourceExtractor.DetectedObject diagonal = analyzeCapsule(3.0, 2.5, Math.PI / 4.0);

        assertTrue(horizontal.axisRatio > 1.35);
        assertTrue(horizontal.majorExtent > horizontal.minorExtent);
        assertEquals(horizontal.axisRatio, vertical.axisRatio, 0.1);
        assertEquals(horizontal.axisRatio, diagonal.axisRatio, 0.35);
        assertEquals(horizontal.majorExtent, vertical.majorExtent, 0.1);
        assertEquals(horizontal.majorExtent, diagonal.majorExtent, 1.5);
    }

    /** A connected L-shaped footprint occupies less of its oriented box than a capsule. */
    @Test
    public void irregularConnectedFootprintHasLowerFillFactor() {
        SourceExtractor.DetectedObject capsule = analyzeCapsule(3.0, 2.5, 0.0);
        List<SourceExtractor.Pixel> pixels = new ArrayList<>();
        for (int x = 0; x <= 11; x++) {
            pixels.add(new SourceExtractor.Pixel(x, 0, 100));
            pixels.add(new SourceExtractor.Pixel(x, 1, 100));
        }
        for (int y = 2; y <= 9; y++) {
            pixels.add(new SourceExtractor.Pixel(10, y, 100));
            pixels.add(new SourceExtractor.Pixel(11, y, 100));
        }
        SourceExtractor.DetectedObject irregular = analyze(pixels);

        assertTrue(irregular.axisRatio > 1.0);
        assertTrue(irregular.fillFactor < capsule.fillFactor);
        assertEquals(irregular.axisRatio, SourceExtractor.measureBlobGeometry(irregular).axisRatio, 1.0e-9);
    }

    /** Rasterizes a capsule as all pixel centers within a radius of a line segment. */
    private static SourceExtractor.DetectedObject analyzeCapsule(double halfLength,
                                                                 double radius,
                                                                 double angle) {
        List<SourceExtractor.Pixel> pixels = new ArrayList<>();
        double directionX = Math.cos(angle);
        double directionY = Math.sin(angle);
        for (int y = -10; y <= 10; y++) {
            for (int x = -10; x <= 10; x++) {
                double parallel = x * directionX + y * directionY;
                double perpendicular = -x * directionY + y * directionX;
                // Beyond either segment endpoint, distance grows in both axis directions.
                double beyondEnds = Math.max(0.0, Math.abs(parallel) - halfLength);
                if (Math.hypot(beyondEnds, perpendicular) <= radius) {
                    pixels.add(new SourceExtractor.Pixel(x, y, 100));
                }
            }
        }
        return analyze(pixels);
    }

    /** Supplies stable intensity weights and attaches the footprint as the BFS extractor does. */
    private static SourceExtractor.DetectedObject analyze(List<SourceExtractor.Pixel> pixels) {
        SourceExtractor.BackgroundMetrics background = new SourceExtractor.BackgroundMetrics();
        background.median = 0.0;
        background.sigma = 1.0;
        SourceExtractor.DetectedObject object = SourceExtractor.analyzeShape(pixels, background, new DetectionConfig());
        object.rawPixels = pixels;
        return object;
    }
}
