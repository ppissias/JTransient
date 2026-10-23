package io.github.ppissias.jtransient.core;

import io.github.ppissias.jtransient.model.DetectedObject;
import io.github.ppissias.jtransient.model.Pixel;
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
        DetectedObject object = analyzeCapsule(0.0, 3.5, 0.0);

        assertEquals(1.0, object.axisRatio, 0.05);
        assertEquals(object.majorExtent, object.minorExtent, 0.05);
        assertTrue(object.fillFactor > 0.6);
    }

    /** Rotating the same capsule should preserve its motion-scale axis ratio within grid error. */
    @Test
    public void capsuleGeometryIsStableAcrossRotations() {
        DetectedObject horizontal = analyzeCapsule(3.0, 2.5, 0.0);
        DetectedObject vertical = analyzeCapsule(3.0, 2.5, Math.PI / 2.0);
        DetectedObject diagonal = analyzeCapsule(3.0, 2.5, Math.PI / 4.0);

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
        DetectedObject capsule = analyzeCapsule(3.0, 2.5, 0.0);
        List<Pixel> pixels = new ArrayList<>();
        for (int x = 0; x <= 11; x++) {
            pixels.add(new Pixel(x, 0, 100));
            pixels.add(new Pixel(x, 1, 100));
        }
        for (int y = 2; y <= 9; y++) {
            pixels.add(new Pixel(10, y, 100));
            pixels.add(new Pixel(11, y, 100));
        }
        DetectedObject irregular = analyze(pixels);

        assertTrue(irregular.axisRatio > 1.0);
        assertTrue(irregular.fillFactor < capsule.fillFactor);
        assertEquals(irregular.axisRatio, SourceExtractor.measureBlobGeometry(irregular).axisRatio, 1.0e-9);
    }

    @Test
    public void analyzedFootprintReportsPixelCount() {
        List<Pixel> pixels = new ArrayList<>();
        pixels.add(new Pixel(1, 2, 100));
        pixels.add(new Pixel(2, 2, 100));
        pixels.add(new Pixel(2, 3, 100));

        DetectedObject object = analyze(pixels);

        assertEquals(pixels.size(), object.pixelCount);
        assertEquals((double) pixels.size(), object.pixelArea, 0.0);
    }

    /** Rasterizes a capsule as all pixel centers within a radius of a line segment. */
    private static DetectedObject analyzeCapsule(double halfLength,
                                                                 double radius,
                                                                 double angle) {
        List<Pixel> pixels = new ArrayList<>();
        double directionX = Math.cos(angle);
        double directionY = Math.sin(angle);
        for (int y = -10; y <= 10; y++) {
            for (int x = -10; x <= 10; x++) {
                double parallel = x * directionX + y * directionY;
                double perpendicular = -x * directionY + y * directionX;
                // Beyond either segment endpoint, distance grows in both axis directions.
                double beyondEnds = Math.max(0.0, Math.abs(parallel) - halfLength);
                if (Math.hypot(beyondEnds, perpendicular) <= radius) {
                    pixels.add(new Pixel(x, y, 100));
                }
            }
        }
        return analyze(pixels);
    }

    /** Supplies stable intensity weights and attaches the footprint as the BFS extractor does. */
    private static DetectedObject analyze(List<Pixel> pixels) {
        SourceExtractor.BackgroundMetrics background = new SourceExtractor.BackgroundMetrics();
        background.median = 0.0;
        background.sigma = 1.0;
        DetectedObject object = SourceExtractor.analyzeShape(pixels, background, new DetectionConfig());
        object.rawPixels = pixels;
        return object;
    }
}
