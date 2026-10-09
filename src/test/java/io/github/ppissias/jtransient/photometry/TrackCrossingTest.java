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
import io.github.ppissias.jtransient.core.SourceExtractor;
import io.github.ppissias.jtransient.core.TrackLinker;
import io.github.ppissias.jtransient.engine.ImageFrame;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

public class TrackCrossingTest {

    /** A track point as flagTrack stores it: {sequence, x, y, angle, half length, half width, time}. */
    private static double[] point(int sequence, double x, double time) {
        return new double[]{sequence, x, 100.0, 0.0, 0.0, 2.0, time};
    }

    @Test
    public void interpolationUsesCaptureTimesAcrossAPause() {
        // Frame 1 was taken 1 s after frame 0, frame 2 only after a 9 s pause.
        List<double[]> points = List.of(point(0, 30.0, 0.0), point(2, 230.0, 10_000.0));

        assertArrayEquals(new double[]{50.0, 100.0},
                VariableStarAnalyzer.interpolate(points, 1, 1_000.0), 1e-9);
    }

    @Test
    public void interpolationFallsBackToSequenceWithoutTimestamps() {
        List<double[]> points = List.of(point(0, 30.0, Double.NaN), point(2, 230.0, Double.NaN));

        assertArrayEquals(new double[]{130.0, 100.0},
                VariableStarAnalyzer.interpolate(points, 1, Double.NaN), 1e-9);
    }

    @Test
    public void everyFragmentOfAStreakFlagsItsStars() {
        // One frame, a streak extracted as two pieces: the second piece lies on a star far from the first.
        List<PhotometryStarSelector.Star> stars = new ArrayList<>();
        stars.add(new PhotometryStarSelector.Star(40.0, 100.0, 1000.0));
        stars.add(new PhotometryStarSelector.Star(240.0, 100.0, 1000.0));
        stars.add(new PhotometryStarSelector.Star(140.0, 300.0, 1000.0));
        VariableStarAnalyzer.PointGrid grid = new VariableStarAnalyzer.PointGrid(16.0);
        for (int i = 0; i < stars.size(); i++) {
            grid.add(stars.get(i).x, stars.get(i).y, i);
        }

        TrackLinker.Track track = new TrackLinker.Track();
        track.points.add(fragment(40.0));
        track.points.add(fragment(240.0));
        List<ImageFrame> frames = List.of(new ImageFrame(0, "frame0.fits", new short[1][1], -1, -1));
        ApertureMeasurer.FrameMeasurement measurement = new ApertureMeasurer.FrameMeasurement(3.0, stars.size());
        measurement.offsetX = 0;
        measurement.offsetY = 0;

        VariableStarAnalyzer.flagTrack(track, frames, stars, grid,
                new ApertureMeasurer.FrameMeasurement[]{measurement}, new DetectionConfig());

        assertEquals(PhotometryFlags.CROSSING, measurement.flags[0] & PhotometryFlags.CROSSING);
        assertEquals(PhotometryFlags.CROSSING, measurement.flags[1] & PhotometryFlags.CROSSING);
        assertEquals(0, measurement.flags[2] & PhotometryFlags.CROSSING);
    }

    private static SourceExtractor.DetectedObject fragment(double x) {
        SourceExtractor.DetectedObject d = new SourceExtractor.DetectedObject(x, 100.0, 5000.0, 30);
        d.sourceFrameIndex = 0;
        d.isStreak = true;
        d.angle = 0.0;
        d.majorExtent = 30.0;
        d.minorExtent = 3.0;
        d.fwhm = 3.0;
        return d;
    }
}
