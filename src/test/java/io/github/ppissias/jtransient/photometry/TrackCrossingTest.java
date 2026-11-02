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
import io.github.ppissias.jtransient.model.Pixel;
import io.github.ppissias.jtransient.model.Track;
import io.github.ppissias.jtransient.config.DetectionConfig;
import io.github.ppissias.jtransient.engine.ImageFrame;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Covers timestamp-aware crossing interpolation, fragmented streaks, piece-order independence and effective
 * inner/outer photometry radii.
 */
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

        Track track = new Track();
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

    /** Pieces of one frame enter the interpolation as their length-weighted centre; a point weighs one pixel. */
    @Test
    public void frameReferenceIsTheLengthWeightedCentreOfItsPieces() {
        // A 100 px piece from 0 to 100 and a 10 px fragment from 100 to 110: the whole trail is centred at 55.
        double[] longPiece = {0, 50.0, 100.0, 0.0, 50.0, 2.0, 7.0};
        double[] fragment = {0, 105.0, 100.0, 0.0, 5.0, 2.0, 7.0};
        double[] pointA = {1, 10.0, 20.0, 0.0, 0.0, 2.0, 8.0};
        double[] pointB = {1, 30.0, 40.0, 0.0, 0.0, 2.0, 8.0};
        double[] single = {2, 300.0, 100.0, 0.0, 12.0, 2.0, 9.0};

        List<double[]> references = VariableStarAnalyzer.frameReferences(List.of(longPiece, fragment, pointA, pointB, single));

        assertEquals(3, references.size());
        assertArrayEquals(new double[]{0, 55.0, 100.0}, java.util.Arrays.copyOf(references.get(0), 3), 1e-9);
        assertEquals(7.0, references.get(0)[6], 0);
        assertArrayEquals(new double[]{1, 20.0, 30.0}, java.util.Arrays.copyOf(references.get(1), 3), 1e-9);
        assertSame("a single piece is its own reference", single, references.get(2));
    }

    /** The drawn shape comes from the nearest observed frame (the earlier on a tie) and its piece closest to the position. */
    @Test
    public void footprintComesFromTheNearestFrameAndItsClosestPiece() {
        double[] early = {0, 10.0, 100.0, 0.0, 5.0, 2.0, Double.NaN};
        double[] late = {0, 40.0, 100.0, 0.0, 9.0, 2.0, Double.NaN};
        double[] other = {4, 100.0, 100.0, 0.0, 3.0, 2.0, Double.NaN};
        List<double[]> points = List.of(early, late, other);

        assertSame(late, VariableStarAnalyzer.footprint(points, 2, 30.0, 100.0));
        assertSame("equidistant pieces keep the fixed piece order", early, VariableStarAnalyzer.footprint(points, 2, 25.0, 100.0));
        assertSame(other, VariableStarAnalyzer.footprint(points, 3, 30.0, 100.0));
    }

    /**
     * A fragmented streak observed in sequences 0, 2 and 6 of frames 0, 1, 2, 5 and 6 (3 and 4 were rejected) masks the
     * same stars in the unobserved frames 1 and 5 whatever the order of its pieces, with and without timestamps.
     */
    @Test
    public void interpolatedMasksDoNotDependOnPieceOrder() {
        List<DetectedObject> pieces = new ArrayList<>(List.of(
                piece(0, 20.0, 30.0), piece(0, 50.0, 20.0), piece(0, 80.0, 30.0),
                piece(2, 220.0, 30.0), piece(2, 260.0, 30.0),
                piece(6, 620.0, 40.0), piece(6, 650.0, 10.0)));
        for (boolean timestamps : new boolean[]{true, false}) {
            int[][] reference = null;
            java.util.Random random = new java.util.Random(5);
            for (int attempt = 0; attempt < 12; attempt++) {
                List<DetectedObject> order = new ArrayList<>(pieces);
                if (attempt == 1) {
                    java.util.Collections.reverse(order);
                } else if (attempt > 1) {
                    java.util.Collections.shuffle(order, random);
                }
                int[][] flags = crossingFlags(order, timestamps);
                if (reference == null) {
                    reference = flags;
                    assertTrue("the unobserved frames mask some stars", java.util.Arrays.stream(flags[1]).sum() > 0
                            && java.util.Arrays.stream(flags[3]).sum() > 0);
                } else {
                    for (int j = 0; j < flags.length; j++) {
                        assertArrayEquals("frame " + j + " attempt " + attempt, reference[j], flags[j]);
                    }
                }
            }
        }
    }

    /** CROSSING flags [frame][star] of a track with the given piece order over frames 0, 1, 2, 5 and 6. */
    private static int[][] crossingFlags(List<DetectedObject> order, boolean timestamps) {
        int[] sequences = {0, 1, 2, 5, 6};
        List<PhotometryStarSelector.Star> stars = new ArrayList<>();
        VariableStarAnalyzer.PointGrid grid = new VariableStarAnalyzer.PointGrid(16.0);
        for (int x = 0; x <= 700; x += 7) {
            for (int dy : new int[]{-17, 0, 17}) {
                grid.add(x, 100 + dy, stars.size());
                stars.add(new PhotometryStarSelector.Star(x, 100 + dy, 1000.0));
            }
        }
        List<ImageFrame> frames = new ArrayList<>();
        ApertureMeasurer.FrameMeasurement[] measurements = new ApertureMeasurer.FrameMeasurement[sequences.length];
        for (int j = 0; j < sequences.length; j++) {
            long time = timestamps ? 1_000_000L + sequences[j] * 60_000L : -1;
            frames.add(new ImageFrame(sequences[j], "frame" + sequences[j] + ".fits", new short[1][1], time, -1));
            measurements[j] = new ApertureMeasurer.FrameMeasurement(3.0, stars.size());
        }
        Track track = new Track();
        track.points.addAll(order);
        VariableStarAnalyzer.flagTrack(track, frames, stars, grid, measurements, new DetectionConfig());
        int[][] flags = new int[sequences.length][];
        for (int j = 0; j < sequences.length; j++) {
            flags[j] = java.util.Arrays.stream(measurements[j].flags).map(f -> f & PhotometryFlags.CROSSING).toArray();
        }
        return flags;
    }

    /** A streak piece along x at y = 100 in the given frame. */
    private static DetectedObject piece(int sequence, double x, double length) {
        DetectedObject d = fragment(x);
        d.sourceFrameIndex = sequence;
        d.majorExtent = length;
        return d;
    }

    private static DetectedObject fragment(double x) {
        DetectedObject d = new DetectedObject(x, 100.0, 5000.0, 30);
        d.sourceFrameIndex = 0;
        d.isStreak = true;
        d.angle = 0.0;
        d.majorExtent = 30.0;
        d.minorExtent = 3.0;
        d.fwhm = 3.0;
        return d;
    }

    /** Verifies tracks and stacked slow-mover footprints flag stars inside the expanded outer sky boundary only. */
    @Test
    public void tracksAndSlowMoverCandidatesUseTheExpandedOuterAnnulus() {
        DetectionConfig config = expandedGeometry();
        var stars = List.of(new PhotometryStarSelector.Star(110, 100, 1000),
                new PhotometryStarSelector.Star(125, 100, 1000));
        VariableStarAnalyzer.Input input = new VariableStarAnalyzer.Input();
        input.frames = List.of(new ImageFrame(0, "frame0.fits", new short[1][1], -1, -1));
        input.masterStars = new ArrayList<>();
        DetectedObject detection = new DetectedObject(100, 100, 5000, 10);
        detection.sourceFrameIndex = 0;
        detection.fwhm = 2;
        Track track = new Track();
        track.points.add(detection);
        input.tracks = List.of(track);
        var trackMeasurement = new ApertureMeasurer.FrameMeasurement(2, stars.size());
        VariableStarAnalyzer.flagCrossings(input, stars, input.masterStars,
                new ApertureMeasurer.FrameMeasurement[]{trackMeasurement}, config);
        assertEquals(PhotometryFlags.CROSSING, trackMeasurement.flags[0] & PhotometryFlags.CROSSING);
        assertEquals(0, trackMeasurement.flags[1] & PhotometryFlags.CROSSING);
        input.tracks = List.of();
        input.slowMoverCandidates = List.of(detection);
        var slowMeasurement = new ApertureMeasurer.FrameMeasurement(2, stars.size());
        VariableStarAnalyzer.flagCrossings(input, stars, input.masterStars,
                new ApertureMeasurer.FrameMeasurement[]{slowMeasurement}, config);
        assertEquals(PhotometryFlags.CROSSING, slowMeasurement.flags[0] & PhotometryFlags.CROSSING);
        assertEquals(0, slowMeasurement.flags[1] & PhotometryFlags.CROSSING);
    }

    /** Keeps the frame-detection veto at the effective inner boundary rather than the clipped outer sky ring. */
    @Test
    public void frameDetectionsUseTheExpandedInnerAnnulusWithoutFlaggingTheOuterSkyRing() {
        DetectionConfig config = expandedGeometry();
        var stars = List.of(new PhotometryStarSelector.Star(108, 100, 1000),
                new PhotometryStarSelector.Star(111, 100, 1000));
        VariableStarAnalyzer.Input input = new VariableStarAnalyzer.Input();
        input.masterStars = new ArrayList<>();
        DetectedObject detection = new DetectedObject(100, 100, 5000, 10);
        detection.peakSigma = 10;
        detection.rawPixels = List.of(new Pixel(100, 100, (short) 100));
        input.frameDetections = List.of(List.of(detection));
        var measurement = new ApertureMeasurer.FrameMeasurement(2, stars.size());
        VariableStarAnalyzer.flagCrossings(input, stars, input.masterStars,
                new ApertureMeasurer.FrameMeasurement[]{measurement}, config);
        assertEquals(PhotometryFlags.CROSSING, measurement.flags[0] & PhotometryFlags.CROSSING);
        assertEquals(0, measurement.flags[1] & PhotometryFlags.CROSSING);
    }

    /** Supplies crossed annulus factors that must expand outside the configured large main aperture. */
    private static DetectionConfig expandedGeometry() {
        DetectionConfig config = new DetectionConfig();
        config.photometryApertureFwhmFactor = 4;
        config.photometryAnnulusInnerFwhmFactor = 1;
        config.photometryAnnulusOuterFwhmFactor = 2;
        return config;
    }
}
