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

import io.github.ppissias.jtransient.core.FrameRegistration;
import io.github.ppissias.jtransient.core.PixelEncoding;
import io.github.ppissias.jtransient.engine.ImageFrame;
import io.github.ppissias.jtransient.telemetry.PipelineTelemetry;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Synthetic undersampled sessions (FWHM 2 px, 3 frames per pointing): removed star peaks are flagged only when they
 * outnumber one-pixel brightenings, and a real dimming of the whole star in one pointing is never flagged.
 */
public class PeakRemovalCheckTest {

    private static final int POINTINGS = 12;
    private static final int PER_POINTING = 3;
    private static final int FRAMES = POINTINGS * PER_POINTING;
    private static final int GRID = 12;                 // GRID x GRID stars
    private static final int SIZE = 40 * GRID + 40;
    private static final double SKY = 1000;
    private static final double SIGMA = 2.0 / 2.355;

    /** One synthetic session: frames, measurements and light curves, with the injected events recorded. */
    private static final class Session {
        final List<ImageFrame> frames = new ArrayList<>();
        final ApertureMeasurer.FrameMeasurement[] measurements = new ApertureMeasurer.FrameMeasurement[FRAMES];
        final List<PhotometryStarSelector.Star> stars = new ArrayList<>();
        final List<StarLightCurve> curves = new ArrayList<>();
        final boolean[][] usable = new boolean[GRID * GRID][FRAMES];
        final int[] pointing = new int[FRAMES];
        final Map<Integer, Integer> removed = new HashMap<>();    // star -> pointing
        final Map<Integer, Integer> dimmed = new HashMap<>();
    }

    /**
     * @param removals stars whose peak pixel is replaced by its neighbours' median during one pointing
     * @param hotPixels stars that gain a hot pixel at their peak during one pointing (a one-pixel brightening)
     * @param dimmings stars dimmed as a whole to 70 % during one pointing
     */
    private static Session session(int removals, int hotPixels, int dimmings, long seed) {
        Random random = new Random(seed);
        Session s = new Session();
        double[][] phase = new double[GRID * GRID][2];
        for (int i = 0; i < GRID * GRID; i++) {
            double x = 40 + 40 * (i % GRID) + random.nextDouble();
            double y = 40 + 40 * (i / GRID) + random.nextDouble();
            s.stars.add(new PhotometryStarSelector.Star(x, y, 20000 + 10000 * random.nextDouble()));
        }
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < GRID * GRID; i++) order.add(i);
        java.util.Collections.shuffle(order, random);
        int k = 0;
        for (int n = 0; n < removals; n++) s.removed.put(order.get(k++), random.nextInt(POINTINGS));
        Map<Integer, Integer> hot = new HashMap<>();
        for (int n = 0; n < hotPixels; n++) hot.put(order.get(k++), random.nextInt(POINTINGS));
        for (int n = 0; n < dimmings; n++) s.dimmed.put(order.get(k++), random.nextInt(POINTINGS));
        for (int p = 0; p < POINTINGS; p++) {
            // Each pointing puts every star on another sub-pixel position, as dithering does.
            for (int i = 0; i < GRID * GRID; i++) {
                phase[i][0] = random.nextDouble() - 0.5;
                phase[i][1] = random.nextDouble() - 0.5;
            }
            for (int f = 0; f < PER_POINTING; f++) {
                int j = p * PER_POINTING + f;
                s.pointing[j] = p;
                double[][] image = new double[SIZE][SIZE];
                for (double[] row : image) for (int x = 0; x < SIZE; x++) row[x] = SKY + 5 * random.nextGaussian();
                for (int i = 0; i < GRID * GRID; i++) {
                    PhotometryStarSelector.Star star = s.stars.get(i);
                    double scale = Integer.valueOf(p).equals(s.dimmed.get(i)) ? 0.7 : 1.0;
                    double cx = Math.round(star.x) + phase[i][0];
                    double cy = Math.round(star.y) + phase[i][1];
                    for (int y = (int) cy - 6; y <= (int) cy + 6; y++) {
                        for (int x = (int) cx - 6; x <= (int) cx + 6; x++) {
                            double d2 = (x - cx) * (x - cx) + (y - cy) * (y - cy);
                            image[y][x] += scale * star.flux / (2 * Math.PI * SIGMA * SIGMA) * Math.exp(-d2 / (2 * SIGMA * SIGMA));
                        }
                    }
                    int px = (int) Math.round(cx);
                    int py = (int) Math.round(cy);
                    if (Integer.valueOf(p).equals(s.removed.get(i))) {
                        double[] ring = {image[py - 1][px - 1], image[py - 1][px], image[py - 1][px + 1], image[py][px - 1],
                                image[py][px + 1], image[py + 1][px - 1], image[py + 1][px], image[py + 1][px + 1]};
                        java.util.Arrays.sort(ring);
                        image[py][px] = 0.5 * (ring[3] + ring[4]);
                    }
                    if (Integer.valueOf(p).equals(hot.get(i))) {
                        image[py][px] += 0.4 * star.flux;
                    }
                }
                short[][] pixels = new short[SIZE][SIZE];
                for (int y = 0; y < SIZE; y++) {
                    for (int x = 0; x < SIZE; x++) {
                        pixels[y][x] = PixelEncoding.fromShiftedPositiveInt((int) Math.max(0, Math.min(65535, Math.round(image[y][x]))));
                    }
                }
                s.frames.add(new ImageFrame(j, "f" + j, pixels, -1, 0));
                ApertureMeasurer.FrameMeasurement m = new ApertureMeasurer.FrameMeasurement(2.0, GRID * GRID);
                java.util.Arrays.fill(m.sky, SKY);
                s.measurements[j] = m;
                for (int i = 0; i < GRID * GRID; i++) {
                    m.flux[ApertureMeasurer.R_MAIN][i] = aperture(image, s.stars.get(i));
                }
            }
        }
        for (int i = 0; i < GRID * GRID; i++) {
            PhotometryStarSelector.Star star = s.stars.get(i);
            StarLightCurve curve = new StarLightCurve(i, star.x, star.y, star.flux, FRAMES);
            double[] mags = new double[FRAMES];
            for (int j = 0; j < FRAMES; j++) mags[j] = s.measurements[j].mag(ApertureMeasurer.R_MAIN, i);
            double median = PhotometryMath.median(mags);
            for (int j = 0; j < FRAMES; j++) {
                curve.deltaMag[j] = mags[j] - median;
                s.usable[i][j] = true;
            }
            s.curves.add(curve);
        }
        return s;
    }

    private static double aperture(double[][] image, PhotometryStarSelector.Star star) {
        double sum = 0;
        int cx = (int) Math.round(star.x);
        int cy = (int) Math.round(star.y);
        for (int y = cy - 4; y <= cy + 4; y++) {
            for (int x = cx - 4; x <= cx + 4; x++) {
                if (Math.hypot(x - star.x, y - star.y) <= 3.5) sum += image[y][x] - SKY;
            }
        }
        return sum;
    }

    private static PipelineTelemetry.PhotometryTelemetry run(Session s) {
        PipelineTelemetry.PhotometryTelemetry telemetry = new PipelineTelemetry.PhotometryTelemetry();
        PeakRemovalCheck.run(s.curves, s.usable, s.measurements, s.stars, s.frames, s.pointing, 0.05, new PhotometricReadiness(), telemetry);
        return telemetry;
    }

    /** Mean residual of a star over the frames of one pointing (positive = fainter). */
    private static double pointingMean(Session s, int star, int pointing) {
        double sum = 0;
        int n = 0;
        for (int j = 0; j < FRAMES; j++) {
            if (s.pointing[j] == pointing) {
                sum += s.curves.get(star).deltaMag[j];
                n++;
            }
        }
        return sum / n;
    }

    private static boolean flaggedIn(Session s, int star, int pointing) {
        for (int j = 0; j < FRAMES; j++) {
            if (s.pointing[j] == pointing && (s.curves.get(star).flags[j] & PhotometryFlags.PEAK_REMOVED) != 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * Most removed peaks are flagged. A real dimming in one pointing can look concentrated when its references caught
     * the star on a sharper pixel position, a few per cent of the time on real data; it stays rare here too.
     */
    @Test
    public void removedPeaksAreFlaggedAndRealDimmingsRarely() {
        Session s = session(40, 0, 60, 3);
        PipelineTelemetry.PhotometryTelemetry t = run(s);
        assertTrue("found: " + t.peakCheckConcentratedFadings + " of " + t.peakCheckFadings, t.peakRemovalFound);
        // A removal costs a star centred on a pixel much of its light, one between pixels little; count those that matter.
        int effective = 0;
        int caught = 0;
        for (Map.Entry<Integer, Integer> e : s.removed.entrySet()) {
            if (pointingMean(s, e.getKey(), e.getValue()) > 0.1) {
                effective++;
                if (flaggedIn(s, e.getKey(), e.getValue())) caught++;
            }
        }
        int dimmingsFlagged = 0;
        for (Map.Entry<Integer, Integer> e : s.dimmed.entrySet()) {
            if (flaggedIn(s, e.getKey(), e.getValue())) dimmingsFlagged++;
        }
        assertTrue("effective removals " + effective, effective >= 20);
        assertTrue("removed peaks flagged: " + caught + " of " + effective, caught >= 0.8 * effective);
        assertTrue("real dimmings flagged: " + dimmingsFlagged + " of 60", dimmingsFlagged <= 6);
        // Flagged measurements leave scoring, and no other measurement is flagged.
        for (int i = 0; i < s.curves.size(); i++) {
            for (int j = 0; j < FRAMES; j++) {
                if ((s.curves.get(i).flags[j] & PhotometryFlags.PEAK_REMOVED) != 0) {
                    assertFalse(s.usable[i][j]);
                    Integer p = s.pointing[j];
                    assertTrue("flag outside a removal or dimming: star " + i, p.equals(s.removed.get(i)) || p.equals(s.dimmed.get(i)));
                }
            }
        }
    }

    @Test
    public void aFewRemovedPeaksAreNotEnough() {
        Session s = session(8, 0, 0, 5);
        PipelineTelemetry.PhotometryTelemetry t = run(s);
        assertFalse(t.peakRemovalFound);
        assertTrue(t.peakCheckConcentratedFadings < PeakRemovalCheck.MIN_CONCENTRATED_FADINGS);
        for (StarLightCurve curve : s.curves) {
            for (int f : curve.flags) assertEquals(0, f & PhotometryFlags.PEAK_REMOVED);
        }
    }

    @Test
    public void oneSidedFadingsAreNeededWhenHotPixelsAreAsCommon() {
        Session s = session(30, 30, 0, 9);
        PipelineTelemetry.PhotometryTelemetry t = run(s);
        assertTrue("concentrated brightenings " + t.peakCheckConcentratedBrightenings, t.peakCheckConcentratedBrightenings >= 20);
        assertFalse(t.peakRemovalFound);
    }

    @Test
    public void pointingsChangeWhenTheCameraMoves() {
        short[][] pixels = new short[100][100];
        List<ImageFrame> frames = new ArrayList<>();
        Map<Integer, FrameRegistration> registrations = new HashMap<>();
        double[][] shifts = {{0, 0}, {0.4, 0.3}, {5, 0}, {5.2, 0.1}, {5.2, 0.1}, {9, 9}};
        for (int j = 0; j < shifts.length; j++) {
            frames.add(new ImageFrame(10 + j, "f" + j, pixels, -1, 0));
            registrations.put(10 + j, FrameRegistration.of(10 + j, shifts[j][0], shifts[j][1], 0));
        }
        registrations.remove(15);   // the last frame has no registration: a pointing of its own
        assertArrayEquals(new int[]{0, 0, 1, 1, 1, 2}, PeakRemovalCheck.pointings(frames, registrations));
        assertArrayEquals(new int[]{0, 1, 2, 3, 4, 5}, PeakRemovalCheck.pointings(frames, null));
    }
}
