package io.github.ppissias.jtransient.core;

import io.github.ppissias.jtransient.config.DetectionConfig;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Same-frame faint streak pieces stay below the normal streak gates and are still joined.
 */
public class FaintStreakFragmentGrouperTest {

    @Test
    public void extendsAnExistingStreakAndLeavesOffLinePieces() {
        TrackLinker.Track streak = streak(piece(100, 100, 0), piece(300, 100, 0));
        SourceExtractor.DetectedObject onLine = fragment(200, 100.4, 0.0);
        SourceExtractor.DetectedObject alsoOnLine = fragment(250, 99.2, 0.05);
        SourceExtractor.DetectedObject offLine = fragment(200, 110, 0.0);
        SourceExtractor.DetectedObject pastTheEnd = fragment(500, 100, 0.0);
        List<SourceExtractor.DetectedObject> frame = new ArrayList<>(List.of(onLine, alsoOnLine, offLine, pastTheEnd));

        FaintStreakFragmentGrouper.assemble(new ArrayList<>(List.of(streak)), List.of(frame), new ArrayList<>(), new HashSet<>(), new DetectionConfig());

        assertTrue(streak.points.contains(onLine));
        assertTrue(streak.points.contains(alsoOnLine));
        assertFalse(streak.points.contains(offLine));
        assertFalse(streak.points.contains(pastTheEnd));
    }

    @Test
    public void singlePartStreakDoesNotAbsorbADistantCollinearPiece() {
        SourceExtractor.DetectedObject body = piece(100, 100, 0);
        body.rawPixels = new ArrayList<>();
        for (int x = 90; x <= 110; x++) {
            body.rawPixels.add(new SourceExtractor.Pixel(x, 100, 100));
        }
        TrackLinker.Track streak = streak(body);
        SourceExtractor.DetectedObject nearby = fragment(125, 100.2, 0.0);
        SourceExtractor.DetectedObject distant = fragment(400, 100, 0.0);

        FaintStreakFragmentGrouper.assemble(
                new ArrayList<>(List.of(streak)),
                List.of(new ArrayList<>(List.of(nearby, distant))),
                new ArrayList<>(),
                new HashSet<>(),
                new DetectionConfig());

        assertTrue(streak.points.contains(nearby));
        assertFalse(streak.points.contains(distant));
    }

    @Test
    public void buildsANewStreakFromSubGatePiecesAndRejectsAGappedLine() {
        List<SourceExtractor.DetectedObject> good = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            good.add(fragment(100 + i * 80, 50 + i * 0.2, 0.0));
        }
        SourceExtractor.DetectedObject roundOnTheLine = fragment(260, 50.1, 0.0);
        roundOnTheLine.elongation = 1.2;
        good.add(roundOnTheLine);

        List<TrackLinker.Track> tracks = new ArrayList<>();
        FaintStreakFragmentGrouper.assemble(tracks, List.of(good), List.of(), new HashSet<>(), new DetectionConfig());

        assertEquals(1, tracks.size());
        assertTrue(tracks.get(0).isStreakTrack);
        assertEquals(6, tracks.get(0).points.size());
        assertTrue(tracks.get(0).points.contains(roundOnTheLine));

        List<SourceExtractor.DetectedObject> gapped = List.of(
                fragment(0, 10, 0.0),
                fragment(10, 10, 0.0),
                fragment(20, 10, 0.0),
                fragment(1000, 10, 0.0));
        List<TrackLinker.Track> rejected = new ArrayList<>();
        FaintStreakFragmentGrouper.assemble(rejected, List.of(gapped), List.of(), new HashSet<>(), new DetectionConfig());
        assertTrue(rejected.isEmpty());
    }

    @Test
    public void roundKnotsFillGapsButDoNotFormALineByThemselves() {
        List<SourceExtractor.DetectedObject> gapped = new ArrayList<>();
        for (int x : new int[]{0, 100, 200, 800}) {
            gapped.add(fragment(x, 40, 0.0));
        }
        List<TrackLinker.Track> withoutFillers = new ArrayList<>();
        FaintStreakFragmentGrouper.assemble(withoutFillers, List.of(gapped), List.of(), new HashSet<>(), new DetectionConfig());
        assertTrue(withoutFillers.isEmpty());

        List<SourceExtractor.DetectedObject> filled = new ArrayList<>(gapped);
        for (int x : new int[]{350, 500, 650}) {
            SourceExtractor.DetectedObject knot = fragment(x, 40.2, Math.PI / 2.0);
            knot.elongation = 1.2;
            knot.pixelArea = 8;
            filled.add(knot);
        }
        List<TrackLinker.Track> tracks = new ArrayList<>();
        FaintStreakFragmentGrouper.assemble(tracks, List.of(filled), List.of(), new HashSet<>(), new DetectionConfig());
        assertEquals(1, tracks.size());
        assertEquals(7, tracks.get(0).points.size());

        List<SourceExtractor.DetectedObject> onlyRound = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            SourceExtractor.DetectedObject knot = fragment(20 + i * 80, 10, 0.0);
            knot.elongation = 1.2;
            onlyRound.add(knot);
        }
        List<TrackLinker.Track> rejected = new ArrayList<>();
        FaintStreakFragmentGrouper.assemble(rejected, List.of(onlyRound), List.of(), new HashSet<>(), new DetectionConfig());
        assertTrue(rejected.isEmpty());
    }

    @Test
    public void absorbsARoundPieceOntoAnExistingStreakDespiteItsAngle() {
        TrackLinker.Track streak = streak(piece(100, 100, 0), piece(300, 100, 0));
        SourceExtractor.DetectedObject round = fragment(200, 100.3, Math.PI / 2.0);
        round.elongation = 1.2;
        SourceExtractor.DetectedObject pointingElsewhere = fragment(240, 100.2, Math.PI / 2.0);

        FaintStreakFragmentGrouper.assemble(
                new ArrayList<>(List.of(streak)),
                List.of(new ArrayList<>(List.of(round, pointingElsewhere))),
                new ArrayList<>(),
                new HashSet<>(),
                new DetectionConfig());

        assertTrue(streak.points.contains(round));
        assertFalse(streak.points.contains(pointingElsewhere));
    }

    @Test
    public void rejectsALineWhoseFootprintsPointElsewhere() {
        List<SourceExtractor.DetectedObject> crossed = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            crossed.add(fragment(50 + i * 100, 80, Math.PI / 2.0));
        }
        List<TrackLinker.Track> tracks = new ArrayList<>();
        FaintStreakFragmentGrouper.assemble(tracks, List.of(crossed), List.of(), new HashSet<>(), new DetectionConfig());
        assertTrue(tracks.isEmpty());
    }

    @Test
    public void staysQuietWhenTheSwitchIsOff() {
        DetectionConfig config = new DetectionConfig();
        config.enableFaintStreakFragments = false;
        List<SourceExtractor.DetectedObject> pieces = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            pieces.add(fragment(10 + i * 90, 20, 0.0));
        }
        List<TrackLinker.Track> tracks = new ArrayList<>();
        FaintStreakFragmentGrouper.assemble(tracks, List.of(pieces), List.of(), new HashSet<>(), config);
        assertTrue(tracks.isEmpty());
    }

    @Test
    public void findMovingObjectsKeepsAFaintLineAsASingleFrameStreak() {
        List<SourceExtractor.DetectedObject> pieces = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            pieces.add(fragment(40 + i * 70, 80, 0.0));
        }
        List<List<SourceExtractor.DetectedObject>> frames = new ArrayList<>();
        frames.add(pieces);
        frames.add(new ArrayList<>());
        frames.add(new ArrayList<>());

        TrackLinker.TrackingResult enabled = TrackLinker.findMovingObjects(
                frames, new ArrayList<>(), new DetectionConfig(), null, 800, 800);
        assertEquals(1, enabled.tracks.size());
        assertTrue(enabled.tracks.get(0).isStreakTrack);
        assertEquals(5, enabled.tracks.get(0).points.size());
        assertTrue(enabled.anomalies.isEmpty());
        assertTrue(enabled.unclassifiedTransients.get(0).isEmpty());

        DetectionConfig disabled = new DetectionConfig();
        disabled.enableFaintStreakFragments = false;
        TrackLinker.TrackingResult leftAlone = TrackLinker.findMovingObjects(
                frames, new ArrayList<>(), disabled, null, 800, 800);
        assertTrue(leftAlone.tracks.isEmpty());
        assertEquals(5, leftAlone.unclassifiedTransients.get(0).size());
    }

    private static TrackLinker.Track streak(SourceExtractor.DetectedObject... parts) {
        TrackLinker.Track track = new TrackLinker.Track();
        track.isStreakTrack = true;
        for (SourceExtractor.DetectedObject part : parts) {
            track.addPoint(part);
        }
        return track;
    }

    private static SourceExtractor.DetectedObject piece(double x, double y, double angle) {
        SourceExtractor.DetectedObject object = fragment(x, y, angle);
        object.pixelArea = 40;
        object.elongation = 8;
        object.peakSigma = 12;
        object.isStreak = true;
        return object;
    }

    private static SourceExtractor.DetectedObject fragment(double x, double y, double angle) {
        SourceExtractor.DetectedObject object = new SourceExtractor.DetectedObject(x, y, 10, 10);
        object.pixelArea = 10;
        object.elongation = 4.0;
        object.angle = angle;
        object.peakSigma = 4.0;
        object.integratedSigma = 5.0;
        object.sourceFrameIndex = 0;
        object.isStreak = false;
        return object;
    }
}
