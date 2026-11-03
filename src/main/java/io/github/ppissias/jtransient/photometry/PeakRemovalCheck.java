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
import io.github.ppissias.jtransient.execution.CancellationScope;
import io.github.ppissias.jtransient.telemetry.PipelineTelemetry;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Finds star peaks that were removed before registration and flags the measurements they spoil.
 *
 * <p>Hot-pixel removal replaces a pixel that stands far above its neighbours with their level. On undersampled frames
 * the peak pixel of a star centred on a pixel looks the same, so the star loses that pixel's light while the sky stays
 * as it was. Dithering puts the stars on other pixels at the next pointing, so the loss lasts exactly one pointing:
 * the light curve shows a brief fading.</p>
 *
 * <p>For every scored star, the frames more than {@link #EPISODE_SIGMA} robust sigmas and more than the smallest
 * reported amplitude fainter (or brighter) than its median that lie in one pointing form a fading (or brightening). It is compared with each of the star's nearest
 * pointings without such frames (at most {@link #MAX_REFERENCES}): the change is the reference's mean cutout minus the
 * fading's, and its concentration is the largest single-pixel share of the change over the largest single-pixel share
 * of the reference's light. A real change in brightness scales the whole profile and gives about 1; a removed peak
 * gives about 2 to 4. A change is concentrated when the concentration is at least {@link #CONCENTRATION_RATIO}
 * against every reference.</p>
 *
 * <p>Noise, cosmic rays and hot pixels make concentrated changes in both directions; only removal makes fadings far
 * more common. The session counts as having had its star peaks removed when at least
 * {@link #MIN_CONCENTRATED_FADINGS} fadings are concentrated, and at least {@link #MIN_FADING_EXCESS} times as many
 * as brightenings plus one. Only then are the measurements of the concentrated fadings flagged
 * {@link PhotometryFlags#PEAK_REMOVED}: kept for display and left out of scoring.</p>
 */
final class PeakRemovalCheck {

    /** Frames this many robust sigmas from the star's median form a brief fading or brightening. */
    static final double EPISODE_SIGMA = 4.0;
    /** A change at least this concentrated against every reference is concentrated in one pixel. */
    static final double CONCENTRATION_RATIO = 1.8;
    /** Fewest concentrated fadings for a session to count as having had its star peaks removed... */
    static final int MIN_CONCENTRATED_FADINGS = 20;
    /** ...when they also outnumber the concentrated brightenings (plus one) this many times. */
    static final int MIN_FADING_EXCESS = 5;
    /** The comparison uses the pixels within this many FWHM of the star, and at least {@link #MIN_RADIUS_PIXELS}. */
    static final double RADIUS_FWHM = 2.0;
    static final double MIN_RADIUS_PIXELS = 3.0;
    /** Consecutive frames whose camera position moved by more than this belong to different pointings. */
    static final double POINTING_TOLERANCE_PIXELS = 1.0;
    /** Nearest clean pointings a change is compared with. */
    static final int MAX_REFERENCES = 2;

    private PeakRemovalCheck() {
    }

    /**
     * Numbers the pointings of the frames: consecutive frames share one while the camera position of the image centre
     * moves by at most {@link #POINTING_TOLERANCE_PIXELS}. A frame without a usable registration starts its own.
     *
     * @param frames analysed frames, chronological
     * @param registrations how registration moved each frame, by sequence index; may be null
     */
    static int[] pointings(List<ImageFrame> frames, Map<Integer, FrameRegistration> registrations) {
        int[] pointing = new int[frames.size()];
        double[] previous = null;
        int current = 0;
        for (int j = 0; j < frames.size(); j++) {
            ImageFrame frame = frames.get(j);
            FrameRegistration registration = registrations == null ? null : registrations.get(frame.sequenceIndex);
            double[] camera = null;
            if (registration != null && registration.usable) {
                camera = registration.toCamera(frame.pixelData[0].length / 2.0, frame.pixelData.length / 2.0);
            }
            if (j > 0 && (camera == null || previous == null
                    || Math.hypot(camera[0] - previous[0], camera[1] - previous[1]) > POINTING_TOLERANCE_PIXELS)) {
                current++;
            }
            pointing[j] = current;
            previous = camera;
        }
        return pointing;
    }

    /**
     * Runs the check on the scored light curves and, in a session whose star peaks were removed, flags the measurements
     * of the concentrated fadings and takes them out of {@code usable}.
     *
     * @param curves light curves with the residuals of their measuring aperture
     * @param usable measurements eligible for scoring [star][frame]; flagged ones become false
     * @param measurements frame measurements (sky per star, registration offsets, FWHM)
     * @param stars master positions of the measured stars, same order as {@code curves}
     * @param frames analysed frames, same order as {@code measurements}
     * @param pointing pointing number of each frame ({@link #pointings})
     * @param minChangeMag smallest change that counts, in mag: the smallest amplitude photometry reports
     */
    static void run(List<StarLightCurve> curves, boolean[][] usable, ApertureMeasurer.FrameMeasurement[] measurements,
                    List<PhotometryStarSelector.Star> stars, List<ImageFrame> frames, int[] pointing, double minChangeMag,
                    PhotometricReadiness readiness, PipelineTelemetry.PhotometryTelemetry telemetry) {
        int nFrames = measurements.length;
        double[] fwhm = new double[nFrames];
        for (int j = 0; j < nFrames; j++) {
            fwhm[j] = measurements[j].fwhm;
        }
        double radius = Math.max(MIN_RADIUS_PIXELS, RADIUS_FWHM * PhotometryMath.median(fwhm));
        if (!Double.isFinite(radius)) {
            return;
        }
        int reach = (int) Math.ceil(radius);
        List<int[]> disc = new ArrayList<>();
        for (int dy = -reach; dy <= reach; dy++) {
            for (int dx = -reach; dx <= reach; dx++) {
                if (Math.hypot(dx, dy) <= radius) {
                    disc.add(new int[]{dx, dy});
                }
            }
        }

        List<int[]> concentratedFadings = new ArrayList<>();   // {star, frame, frame, ...}
        double[] r = new double[nFrames];
        for (int i = 0; i < curves.size(); i++) {
            CancellationScope.check();
            StarLightCurve curve = curves.get(i);
            int n = 0;
            for (int j = 0; j < nFrames; j++) {
                r[j] = usable[i][j] ? curve.deltaMag[j] : Double.NaN;
                if (Double.isFinite(r[j])) {
                    n++;
                }
            }
            if (n < 5) {
                continue;
            }
            double median = PhotometryMath.median(r);
            double sigma = PhotometryMath.robustSigma(r);
            if (!(sigma > 0)) {
                continue;
            }
            int[] side = new int[nFrames];          // +1 faint, -1 bright, 0 quiet or unusable
            boolean[] disturbed = new boolean[pointing[nFrames - 1] + 1];
            boolean any = false;
            for (int j = 0; j < nFrames; j++) {
                if (!Double.isFinite(r[j])) {
                    continue;
                }
                double dev = r[j] - median;
                double limit = Math.max(EPISODE_SIGMA * sigma, minChangeMag);
                side[j] = dev > limit ? 1 : dev < -limit ? -1 : 0;
                if (side[j] != 0) {
                    disturbed[pointing[j]] = true;
                    any = true;
                }
            }
            if (!any) {
                continue;
            }
            PhotometryStarSelector.Star star = stars.get(i);
            int cx = (int) Math.round(star.x);
            int cy = (int) Math.round(star.y);
            for (int sign : new int[]{1, -1}) {
                for (int p = 0; p <= pointing[nFrames - 1]; p++) {
                    List<Integer> episode = new ArrayList<>();
                    for (int j = 0; j < nFrames; j++) {
                        if (pointing[j] == p && side[j] == sign) {
                            episode.add(j);
                        }
                    }
                    if (episode.isEmpty()) {
                        continue;
                    }
                    List<List<Integer>> references = references(r, pointing, disturbed, p);
                    if (references.isEmpty()) {
                        continue;
                    }
                    double concentration = concentration(episode, references, sign, i, cx, cy, disc, measurements, frames);
                    if (sign > 0) {
                        telemetry.peakCheckFadings++;
                    }
                    if (concentration >= CONCENTRATION_RATIO) {
                        if (sign > 0) {
                            telemetry.peakCheckConcentratedFadings++;
                            int[] entry = new int[episode.size() + 1];
                            entry[0] = i;
                            for (int k = 0; k < episode.size(); k++) {
                                entry[k + 1] = episode.get(k);
                            }
                            concentratedFadings.add(entry);
                        } else {
                            telemetry.peakCheckConcentratedBrightenings++;
                        }
                    }
                }
            }
        }

        int fadings = telemetry.peakCheckConcentratedFadings;
        int brightenings = telemetry.peakCheckConcentratedBrightenings;
        telemetry.peakRemovalFound = fadings >= MIN_CONCENTRATED_FADINGS && fadings >= MIN_FADING_EXCESS * (brightenings + 1);
        if (!telemetry.peakRemovalFound) {
            return;
        }
        int flagged = 0;
        for (int[] entry : concentratedFadings) {
            StarLightCurve curve = curves.get(entry[0]);
            for (int k = 1; k < entry.length; k++) {
                curve.flags[entry[k]] |= PhotometryFlags.PEAK_REMOVED;
                usable[entry[0]][entry[k]] = false;
                flagged++;
            }
        }
        readiness.messages.add(String.format(Locale.US,
                "In %d brief fadings of single stars, the lost light sits in the brightest pixel, against %d brightenings of that kind: "
                        + "the star peaks were probably removed before registration, for example by hot-pixel removal on undersampled frames. "
                        + "Those %d measurements are left out of the variability scores; processing the frames without hot-pixel removal would keep them.",
                fadings, brightenings, flagged));
    }

    /** The star's nearest pointings with usable measurements and no fading or brightening, at most {@link #MAX_REFERENCES}. */
    private static List<List<Integer>> references(double[] r, int[] pointing, boolean[] disturbed, int p) {
        List<List<Integer>> references = new ArrayList<>();
        for (int distance = 1; distance <= pointing[pointing.length - 1] && references.size() < MAX_REFERENCES; distance++) {
            for (int q : new int[]{p - distance, p + distance}) {
                if (q < 0 || q > pointing[pointing.length - 1] || disturbed[q] || references.size() >= MAX_REFERENCES) {
                    continue;
                }
                List<Integer> frames = new ArrayList<>();
                for (int j = 0; j < r.length; j++) {
                    if (pointing[j] == q && Double.isFinite(r[j])) {
                        frames.add(j);
                    }
                }
                if (!frames.isEmpty()) {
                    references.add(frames);
                }
            }
        }
        return references;
    }

    /**
     * The smallest concentration of the change against the references (0 when a cutout is missing or the change does
     * not go the expected way).
     */
    private static double concentration(List<Integer> episode, List<List<Integer>> references, int sign, int star,
                                        int cx, int cy, List<int[]> disc,
                                        ApertureMeasurer.FrameMeasurement[] measurements, List<ImageFrame> frames) {
        double[] changed = meanCutout(episode, star, cx, cy, disc, measurements, frames);
        if (changed == null) {
            return 0;
        }
        double smallest = Double.POSITIVE_INFINITY;
        for (List<Integer> reference : references) {
            double[] normal = meanCutout(reference, star, cx, cy, disc, measurements, frames);
            if (normal == null) {
                return 0;
            }
            double change = 0;
            double largestChange = Double.NEGATIVE_INFINITY;
            double flux = 0;
            double peak = Double.NEGATIVE_INFINITY;
            for (int k = 0; k < disc.size(); k++) {
                double d = sign * (normal[k] - changed[k]);
                change += d;
                largestChange = Math.max(largestChange, d);
                flux += normal[k];
                peak = Math.max(peak, normal[k]);
            }
            if (!(change > 0) || !(flux > 0) || !(peak > 0)) {
                return 0;
            }
            smallest = Math.min(smallest, (largestChange / change) / (peak / flux));
        }
        return smallest;
    }

    /** Mean sky-subtracted cutout of the star over the frames, pixel by pixel over {@code disc}; null when one is missing. */
    private static double[] meanCutout(List<Integer> frameList, int star, int cx, int cy, List<int[]> disc,
                                       ApertureMeasurer.FrameMeasurement[] measurements, List<ImageFrame> frames) {
        double[] mean = new double[disc.size()];
        for (int j : frameList) {
            short[][] pixels = frames.get(j).pixelData;
            double sky = measurements[j].sky[star];
            if (!Double.isFinite(sky)) {
                return null;
            }
            for (int k = 0; k < disc.size(); k++) {
                int x = cx + disc.get(k)[0];
                int y = cy + disc.get(k)[1];
                if (y < 0 || y >= pixels.length || x < 0 || x >= pixels[0].length) {
                    return null;
                }
                mean[k] += (PixelEncoding.toShiftedPositiveInt(pixels[y][x]) - sky) / frameList.size();
            }
        }
        return mean;
    }
}
