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

import io.github.ppissias.jtransient.config.DetectionConfig;
import io.github.ppissias.jtransient.telemetry.PipelineTelemetry;
import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Checks that frames already excluded by the registration and shape checks cannot change which measurements are
 * flagged as contaminated, or which stars are left out, while the same evidence on active frames still counts.
 */
public class EarlyFrameIsolationTest {

    private static final int STARS = 40;
    private static final int FRAMES = 15;
    /** Frames 0 to 5 stay; the later nine are excluded, more than half, so they could dominate pooled statistics. */
    private static final int ACTIVE_FRAMES = 6;
    /** A star with one strongly departing shape in an active frame. */
    private static final int OUTLIER_STAR = 5;
    private static final int OUTLIER_FRAME = 2;

    /**
     * Measurements with a constant concentration index and tiny scatter, one 20 % departure, and, when
     * {@code poisonLateFrames}, star shapes scattered by about 30 % in the late frames.
     */
    private static ApertureMeasurer.FrameMeasurement[] shapes(boolean poisonLateFrames) {
        ApertureMeasurer.FrameMeasurement[] frames = new ApertureMeasurer.FrameMeasurement[FRAMES];
        for (int j = 0; j < FRAMES; j++) {
            ApertureMeasurer.FrameMeasurement m = new ApertureMeasurer.FrameMeasurement(3.0, STARS);
            for (int i = 0; i < STARS; i++) {
                double flux = Math.pow(10, -0.4 * (10 + 0.05 * i));
                double ci = poisonLateFrames && j >= ACTIVE_FRAMES
                        ? 0.5 * (1 + 0.3 * Math.sin(13 * i + 5 * j))
                        : 0.5 * (1 + 0.004 * Math.sin(7 * i + 3 * j));
                if (i == OUTLIER_STAR && j == OUTLIER_FRAME) {
                    ci = 0.5 * 1.2;
                }
                m.flux[ApertureMeasurer.R_MAIN][i] = flux;
                m.flux[ApertureMeasurer.R_CI_OUTER][i] = flux;
                m.flux[ApertureMeasurer.R_CI_INNER][i] = ci * flux;
            }
            frames[j] = m;
        }
        return frames;
    }

    private static boolean[] firstFramesActive() {
        boolean[] active = new boolean[FRAMES];
        Arrays.fill(active, 0, ACTIVE_FRAMES, true);
        return active;
    }

    private static boolean contaminated(ApertureMeasurer.FrameMeasurement[] frames, int star, int frame) {
        return (frames[frame].flags[star] & PhotometryFlags.CONTAMINATED) != 0;
    }

    @Test
    public void excludedFramesCannotChangeTheShapeFlagsOfActiveFrames() {
        ApertureMeasurer.FrameMeasurement[] clean = shapes(false);
        ApertureMeasurer.FrameMeasurement[] poisoned = shapes(true);
        VariableStarAnalyzer.flagContaminatedShapes(clean, STARS, FRAMES, firstFramesActive());
        VariableStarAnalyzer.flagContaminatedShapes(poisoned, STARS, FRAMES, firstFramesActive());

        for (int j = 0; j < ACTIVE_FRAMES; j++) {
            for (int i = 0; i < STARS; i++) {
                assertEquals("star " + i + " frame " + j, contaminated(clean, i, j), contaminated(poisoned, i, j));
            }
        }
        assertTrue(contaminated(poisoned, OUTLIER_STAR, OUTLIER_FRAME));
        // The excluded frames are still judged, so their spoiled shapes stay visible.
        int flaggedLate = 0;
        for (int j = ACTIVE_FRAMES; j < FRAMES; j++) {
            for (int i = 0; i < STARS; i++) {
                if (contaminated(poisoned, i, j)) flaggedLate++;
            }
        }
        assertTrue("flagged measurements in excluded frames: " + flaggedLate, flaggedLate > STARS);
    }

    @Test
    public void equallyBadActiveFramesStillWidenTheTolerance() {
        ApertureMeasurer.FrameMeasurement[] poisoned = shapes(true);
        boolean[] allActive = new boolean[FRAMES];
        Arrays.fill(allActive, true);
        VariableStarAnalyzer.flagContaminatedShapes(poisoned, STARS, FRAMES, allActive);

        assertFalse(contaminated(poisoned, OUTLIER_STAR, OUTLIER_FRAME));
    }

    /**
     * Five stars over seven frames, of which frames 3 to 6 are excluded: the nonlinear share and the median SNR
     * count only frames 0 to 2.
     */
    @Test
    public void starExclusionsCountOnlyActiveFrames() {
        int stars = 5;
        int frames = 7;
        boolean[] active = {true, true, true, false, false, false, false};
        ApertureMeasurer.FrameMeasurement[] measurements = new ApertureMeasurer.FrameMeasurement[frames];
        double[][] mag = new double[stars][frames];
        int[][] flags = new int[stars][frames];
        for (int j = 0; j < frames; j++) {
            measurements[j] = new ApertureMeasurer.FrameMeasurement(3.0, stars);
            for (int i = 0; i < stars; i++) {
                mag[i][j] = 12.0;
                measurements[j].magError[ApertureMeasurer.R_MAIN][i] = 0.01;
            }
        }
        for (int j = 2; j < frames; j++) flags[0][j] = PhotometryFlags.NONLINEAR;   // 1 of 3 active frames
        for (int j = 0; j < 2; j++) flags[1][j] = PhotometryFlags.SATURATED;        // 2 of 3 active frames
        for (int j = 0; j < 3; j++) measurements[j].magError[ApertureMeasurer.R_MAIN][2] = 0.5;      // low SNR where active
        for (int j = 3; j < frames; j++) measurements[j].magError[ApertureMeasurer.R_MAIN][3] = 0.5; // low SNR where excluded
        for (int j = 0; j < 3; j++) mag[4][j] = Double.NaN;                          // measured only where excluded
        DetectionConfig config = new DetectionConfig();
        config.photometryMinSnr = 10.0;
        PipelineTelemetry.PhotometryTelemetry telemetry = new PipelineTelemetry.PhotometryTelemetry();

        String[] reasons = VariableStarAnalyzer.starExclusions(measurements, mag, flags, active, config, telemetry);

        assertNull(reasons[0]);
        assertEquals("Saturated or non-linear in most frames", reasons[1]);
        // The reason is formatted in the default locale, as in the analyzer.
        assertTrue(reasons[2], reasons[2].matches("Median SNR 2[.,]2 below the minimum 10[.,]0"));
        assertNull(reasons[3]);
        assertTrue(reasons[4], reasons[4].startsWith("Median SNR NaN below the minimum"));
        assertEquals(1, telemetry.starsExcludedMostlyNonlinear);
        assertEquals(2, telemetry.starsExcludedLowSnr);
    }
}
