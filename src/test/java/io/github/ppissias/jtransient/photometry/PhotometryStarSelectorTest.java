/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 * author: Petros Pissias <petrospis at gmail.com>
 *
 */
package io.github.ppissias.jtransient.photometry;

import io.github.ppissias.jtransient.model.DetectedObject;
import io.github.ppissias.jtransient.config.DetectionConfig;
import io.github.ppissias.jtransient.core.DataArea;
import io.github.ppissias.jtransient.core.FrameCoverage;
import io.github.ppissias.jtransient.telemetry.PipelineTelemetry;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Checks selection boundaries, effective annulus coverage and neighbour-contamination estimates. */
public class PhotometryStarSelectorTest {

    private static final double FWHM = 3.0;

    private static DetectedObject star(double x, double y, double flux) {
        DetectedObject d = new DetectedObject(x, y, flux, 20);
        d.elongation = 1.0;
        return d;
    }

    private static List<PhotometryStarSelector.Star> select(List<DetectedObject> stars) {
        short[][] stack = new short[240][240];
        for (short[] row : stack) {
            java.util.Arrays.fill(row, (short) (1000 - 32768));
        }
        return PhotometryStarSelector.select(stars, stack, FWHM, 60000, new DetectionConfig(),
                new PipelineTelemetry.PhotometryTelemetry());
    }

    @Test
    public void starsAreMeasuredOnlyWhereTheirSkyRingIsInsideTheDataArea() {
        short[][] stack = new short[240][240];
        for (short[] row : stack) {
            java.util.Arrays.fill(row, (short) (1000 - 32768));
        }
        // Every frame has data from column 40 on: the area starts at 43.
        short[][] frame = new short[240][240];
        for (short[] row : frame) {
            java.util.Arrays.fill(row, (short) (1000 - 32768));
            java.util.Arrays.fill(row, 0, 40, Short.MIN_VALUE);
        }
        DataArea area = DataArea.of(List.of(FrameCoverage.measure(0, frame)));
        List<DetectedObject> stars = new ArrayList<>();
        stars.add(star(50, 120, 10_000));
        stars.add(star(120, 120, 10_000));
        List<PhotometryStarSelector.Star> result = PhotometryStarSelector.select(stars, stack, FWHM, 60000,
                new DetectionConfig(), new PipelineTelemetry.PhotometryTelemetry(), area);
        assertFalse(selected(result, 50, 120));
        assertTrue(selected(result, 120, 120));
    }

    private static boolean selected(List<PhotometryStarSelector.Star> result, double x, double y) {
        return result.stream().anyMatch(s -> Math.hypot(s.x - x, s.y - y) < 0.5);
    }

    /** Rejects an edge star whose raw configured sky ring fits but whose expanded measurement annulus does not. */
    @Test
    public void expandedMeasurementAnnulusAlsoControlsSelectionAtTheImageEdge() {
        DetectionConfig config = new DetectionConfig();
        config.photometryApertureFwhmFactor = 4;
        config.photometryAnnulusInnerFwhmFactor = 1;
        config.photometryAnnulusOuterFwhmFactor = 2;
        short[][] stack = new short[240][240];
        for (short[] row : stack) java.util.Arrays.fill(row, (short) (1000 - 32768));
        PipelineTelemetry.PhotometryTelemetry telemetry = new PipelineTelemetry.PhotometryTelemetry();
        var result = PhotometryStarSelector.select(List.of(star(10, 120, 10_000), star(120, 120, 10_000)),
                stack, 2, 60_000, config, telemetry);
        assertFalse(selected(result, 10, 120));
        assertTrue(selected(result, 120, 120));
        assertEquals(1, telemetry.starsRejectedEdgeOrVoid);
        assertEquals(1, config.photometryAnnulusInnerFwhmFactor, 0);
        assertEquals(2, config.photometryAnnulusOuterFwhmFactor, 0);
    }

    @Test
    public void faintNeighbourDoesNotMakeAStarCrowded() {
        // A neighbour 7.5 mag fainter at two FWHM adds far less than 2% light to the aperture.
        List<DetectedObject> stars = new ArrayList<>();
        stars.add(star(120, 120, 100_000));
        stars.add(star(120 + 2 * FWHM, 120, 100));

        assertTrue(selected(select(stars), 120, 120));
    }

    @Test
    public void neighbourAtTheApertureEdgeContaminatesTheStar() {
        // 8% of the star's flux (below the bright-neighbour ratio) at the aperture edge puts about 4% light inside it.
        List<DetectedObject> stars = new ArrayList<>();
        stars.add(star(120, 120, 50_000));
        stars.add(star(120 + 1.5 * FWHM, 120, 4_000));

        assertFalse(selected(select(stars), 120, 120));
    }

    @Test
    public void enclosedFractionOfACentredStarMatchesTheGaussianIntegral() {
        double sigma = FWHM / 2.355;
        double radius = 1.5 * FWHM;
        double[] table = PhotometryStarSelector.enclosedFractionTable(sigma, radius, 3 * FWHM);
        double expected = 1 - Math.exp(-radius * radius / (2 * sigma * sigma));
        assertEquals(expected, table[0], 1e-3);
        assertTrue("a star far outside the aperture leaves almost nothing in it", table[table.length - 1] < 1e-3);
    }
}
