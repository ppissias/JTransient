/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 * author: Petros Pissias <petrospis at gmail.com>
 *
 */
package io.github.ppissias.jtransient.quality;

import io.github.ppissias.jtransient.config.DetectionConfig;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The registration check on synthetic star lists: a steady session passes, creep in part of the field or of the whole
 * frame is flagged without rejecting frames, and a frame whose stars jump, everywhere or in one zone, is rejected.
 */
public class RegistrationCheckTest {
    private static final int SIZE = 1000;
    private static final int FRAMES = 30;
    private static final double FWHM = 3.0;

    /** Moves a star (x, y) of frame k; returns the new {x, y}. */
    private interface Motion {
        double[] move(int k, double x, double y);
    }

    /** A 20 x 20 grid of stars with 0.05 px centroid noise, moved per frame. */
    private static List<FrameQualityAnalyzer.FrameMetrics> session(Motion motion) {
        Random random = new Random(3);
        List<FrameQualityAnalyzer.FrameMetrics> frames = new ArrayList<>();
        for (int k = 0; k < FRAMES; k++) {
            FrameQualityAnalyzer.FrameMetrics m = new FrameQualityAnalyzer.FrameMetrics();
            // The extraction's second-moment FWHM reads about sqrt(2) above the Gaussian FWHM the check uses.
            m.medianFWHM = (FWHM + 0.01 * k) * Math.sqrt(2);
            m.fwhmStarCount = 400;
            m.filename = "f" + k;
            float[] stars = new float[2 * 400];
            int s = 0;
            for (int gy = 0; gy < 20; gy++) {
                for (int gx = 0; gx < 20; gx++) {
                    double[] p = motion.move(k, 25 + 50 * gx, 25 + 50 * gy);
                    stars[s++] = (float) (p[0] + 0.05 * random.nextGaussian());
                    stars[s++] = (float) (p[1] + 0.05 * random.nextGaussian());
                }
            }
            m.registrationStars = stars;
            frames.add(m);
        }
        return frames;
    }

    private static RegistrationCheck.Result check(List<FrameQualityAnalyzer.FrameMetrics> frames) {
        return RegistrationCheck.evaluate(frames, SIZE, SIZE, new DetectionConfig());
    }

    private static int rejected(List<FrameQualityAnalyzer.FrameMetrics> frames) {
        return (int) frames.stream().filter(f -> f.isRejected).count();
    }

    @Test
    public void aSteadySessionPasses() {
        List<FrameQualityAnalyzer.FrameMetrics> frames = session((k, x, y) -> new double[]{x, y});
        RegistrationCheck.Result r = check(frames);
        assertTrue(r.reason, r.available);
        assertEquals(400, r.referenceStars);
        assertEquals(FRAMES, r.framesMeasured);
        assertEquals(0, rejected(frames));
        assertTrue(r.overallCreepFwhm < 0.05);
        assertFalse(r.overallFlagged());
        for (int row = 0; row < RegistrationCheck.ZONES; row++) {
            for (int column = 0; column < RegistrationCheck.ZONES; column++) {
                assertFalse(r.zoneFlagged(row, column));
            }
        }
    }

    /** Stars in the right quarter drift 2 px (0.67 FWHM) over the session: that column is flagged, no frame rejected. */
    @Test
    public void creepAtOneEdgeFlagsThoseZones() {
        List<FrameQualityAnalyzer.FrameMetrics> frames = session((k, x, y) ->
                new double[]{x > 750 ? x + 2.0 * k / (FRAMES - 1) : x, y});
        RegistrationCheck.Result r = check(frames);
        assertTrue(r.available);
        assertEquals(0, rejected(frames));
        for (int row = 0; row < RegistrationCheck.ZONES; row++) {
            assertTrue("right column, row " + row, r.zoneFlagged(row, 3));
            assertEquals(2.0 / FWHM, r.zoneCreepFwhm[row][3], 0.1);
            for (int column = 0; column < 3; column++) {
                assertFalse("row " + row + " column " + column, r.zoneFlagged(row, column));
            }
        }
        assertEquals(750, r.zoneRectangle(0, 3)[0]);
        double[] creep = new double[RegistrationCheck.ZONES * RegistrationCheck.ZONES];
        for (int row = 0; row < RegistrationCheck.ZONES; row++) {
            for (int column = 0; column < RegistrationCheck.ZONES; column++) {
                creep[row * RegistrationCheck.ZONES + column] = r.zoneCreepFwhm[row][column];
            }
        }
        List<int[]> areas = RegistrationCheck.flaggedAreas(SIZE, SIZE, RegistrationCheck.ZONES, creep, r.creepLimitFwhm);
        assertEquals("the column is one area", 1, areas.size());
        assertArrayEquals(new int[]{750, 0, 250, 1000}, areas.get(0));
    }

    /** The whole field drifts 2 px: the frame's position is flagged, the zones (relative to it) are not. */
    @Test
    public void aWholeFieldDriftFlagsTheFrameNotTheZones() {
        List<FrameQualityAnalyzer.FrameMetrics> frames = session((k, x, y) -> new double[]{x + 2.0 * k / (FRAMES - 1), y});
        RegistrationCheck.Result r = check(frames);
        assertEquals(0, rejected(frames));
        assertTrue(r.overallFlagged());
        assertFalse(r.zoneFlagged(1, 1));
    }

    /** One frame is off by 2.5 px (0.83 FWHM) everywhere, another only in its top-left zone: both are rejected. */
    @Test
    public void framesWhoseStarsJumpAreRejected() {
        List<FrameQualityAnalyzer.FrameMetrics> frames = session((k, x, y) -> {
            if (k == 10) {
                return new double[]{x + 2.5, y};
            }
            if (k == 20 && x < 250 && y < 250) {
                return new double[]{x, y + 2.5};
            }
            return new double[]{x, y};
        });
        RegistrationCheck.Result r = check(frames);
        assertTrue(frames.get(10).isRejected);
        assertTrue(frames.get(10).rejectionReason, frames.get(10).rejectionReason.startsWith("Registration jump"));
        assertTrue(frames.get(20).isRejected);
        assertEquals(2, rejected(frames));
        assertEquals(2, r.framesRejected);
        assertTrue(r.largestKeptJumpFwhm < 0.1);
        assertFalse("a jump is not creep", r.zoneFlagged(0, 0));
    }

    @Test
    public void switchedOffChangesNothing() {
        List<FrameQualityAnalyzer.FrameMetrics> frames = session((k, x, y) -> new double[]{k == 10 ? x + 5 : x, y});
        DetectionConfig off = new DetectionConfig();
        off.enableRegistrationCheck = false;
        RegistrationCheck.Result r = RegistrationCheck.evaluate(frames, SIZE, SIZE, off);
        assertFalse(r.available);
        assertEquals("switched off", r.reason);
        assertEquals(0, rejected(frames));
    }

    @Test
    public void tooFewFramesAreNotMeasured() {
        List<FrameQualityAnalyzer.FrameMetrics> frames = session((k, x, y) -> new double[]{x, y}).subList(0, 4);
        RegistrationCheck.Result r = check(frames);
        assertFalse(r.available);
        assertTrue(r.reason, r.reason.startsWith("fewer than"));
    }
}
