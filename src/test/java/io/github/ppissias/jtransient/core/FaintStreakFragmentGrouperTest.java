package io.github.ppissias.jtransient.core;

import io.github.ppissias.jtransient.config.DetectionConfig;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
    public void extendsAStreakAlongAConnectedChainAndStopsAtAGap() {
        TrackLinker.Track streak = streak(piece(100, 100, 0), piece(300, 100, 0));
        SourceExtractor.DetectedObject first = roundPiece(360, 100.3);
        SourceExtractor.DetectedObject second = roundPiece(430, 100.2);
        SourceExtractor.DetectedObject tooFar = roundPiece(700, 100.0);
        SourceExtractor.DetectedObject offLine = roundPiece(360, 110);
        SourceExtractor.DetectedObject pointingElsewhere = fragment(370, 100.1, Math.PI / 2.0);
        SourceExtractor.DetectedObject tooSmall = roundPiece(340, 100.2);
        tooSmall.pixelArea = 3;

        FaintStreakFragmentGrouper.assemble(
                new ArrayList<>(List.of(streak)),
                List.of(new ArrayList<>(List.of(first, second, tooFar, offLine, pointingElsewhere, tooSmall))),
                new ArrayList<>(),
                new HashSet<>(),
                new DetectionConfig());

        assertTrue(streak.points.contains(first));
        assertTrue(streak.points.contains(second));
        assertFalse(streak.points.contains(tooFar));
        assertFalse(streak.points.contains(offLine));
        assertFalse(streak.points.contains(pointingElsewhere));
        assertFalse(streak.points.contains(tooSmall));
    }

    @Test
    public void anAnomalyOnAStreakJoinsItAndAFarFlashStaysAnAnomaly() {
        List<SourceExtractor.DetectedObject> line = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            line.add(fragment(100 + i * 80, 100, 0.0));
        }
        SourceExtractor.DetectedObject onPath = bright(250, 104);
        SourceExtractor.DetectedObject farAway = bright(250, 220);
        line.add(onPath);
        line.add(farAway);

        List<List<SourceExtractor.DetectedObject>> frames = new ArrayList<>();
        frames.add(line);
        frames.add(new ArrayList<>());
        frames.add(new ArrayList<>());

        TrackLinker.TrackingResult result = TrackLinker.findMovingObjects(
                frames, new ArrayList<>(), new DetectionConfig(), null, 800, 800);

        assertTrue(result.tracks.get(0).points.contains(onPath));
        assertEquals(1, result.anomalies.size());
        assertTrue(result.anomalies.get(0).object == farAway);
    }

    @Test
    public void anAnomalyJoinsAStreakInAFrameItSkipped() {
        // A streak moving +x by 100 px per minute, seen in frames 0, 1 and 3; each frame's trail is 60 px long.
        TrackLinker.Track streak = streak(trail(0, 100, 160), trail(1, 200, 260), trail(3, 400, 460));
        SourceExtractor.DetectedObject inTheGap = anomaly(2, 330, 101);
        SourceExtractor.DetectedObject offTheLine = anomaly(2, 320, 115);
        SourceExtractor.DetectedObject pastTheNextFrame = anomaly(2, 560, 100);

        Set<SourceExtractor.DetectedObject> joined = FaintStreakFragmentGrouper.joinAnomaliesOnStreakPaths(
                List.of(streak), List.of(inTheGap, offTheLine, pastTheNextFrame));

        assertEquals(Set.of(inTheGap), joined);
        assertTrue(streak.points.contains(inTheGap));
    }

    @Test
    public void aStreakThroughAStarJoinsAlthoughTheStarPullsItsCentreOffTheLine() {
        TrackLinker.Track streak = streak(trail(0, 100, 160), trail(1, 200, 260), trail(3, 400, 460));
        SourceExtractor.DetectedObject star = anomaly(2, 330, 109);
        star.rawPixels = new ArrayList<>();
        for (int y = 101; y <= 117; y++) {
            star.rawPixels.add(new SourceExtractor.Pixel(330, y, 500));
        }
        SourceExtractor.DetectedObject nearbyStar = anomaly(2, 340, 112);
        nearbyStar.rawPixels = new ArrayList<>();
        for (int y = 105; y <= 119; y++) {
            nearbyStar.rawPixels.add(new SourceExtractor.Pixel(340, y, 500));
        }

        Set<SourceExtractor.DetectedObject> joined = FaintStreakFragmentGrouper.joinAnomaliesOnStreakPaths(
                List.of(streak), List.of(star, nearbyStar));

        assertEquals("only the star the line passes through", Set.of(star), joined);
    }

    @Test
    public void aStreakTakesTheFrameAfterItsLastAndThenTheNextOne() {
        TrackLinker.Track streak = streak(trail(0, 100, 160), trail(1, 200, 260));
        SourceExtractor.DetectedObject frameTwo = anomaly(2, 330, 100);
        SourceExtractor.DetectedObject frameThree = anomaly(3, 420, 100);
        SourceExtractor.DetectedObject tooFarAhead = anomaly(2, 700, 100);
        SourceExtractor.DetectedObject behindTheMotion = anomaly(2, 120, 100);
        SourceExtractor.DetectedObject twoFramesOn = anomaly(5, 630, 100);

        Set<SourceExtractor.DetectedObject> joined = FaintStreakFragmentGrouper.joinAnomaliesOnStreakPaths(
                List.of(streak), List.of(frameThree, frameTwo, tooFarAhead, behindTheMotion, twoFramesOn));

        assertEquals(Set.of(frameTwo, frameThree), joined);
    }

    @Test
    public void withoutTimestampsTheFrameOrderGivesTheMotion() {
        SourceExtractor.DetectedObject[] parts = {trail(0, 100, 160), trail(1, 200, 260), trail(3, 400, 460)};
        for (SourceExtractor.DetectedObject part : parts) {
            part.timestamp = -1L;
        }
        TrackLinker.Track streak = streak(parts);
        SourceExtractor.DetectedObject inTheGap = anomaly(2, 330, 100);
        inTheGap.timestamp = -1L;
        SourceExtractor.DetectedObject frameFour = anomaly(4, 530, 100);
        frameFour.timestamp = -1L;

        Set<SourceExtractor.DetectedObject> joined = FaintStreakFragmentGrouper.joinAnomaliesOnStreakPaths(
                List.of(streak), List.of(inTheGap, frameFour));

        assertEquals(Set.of(inTheGap, frameFour), joined);
    }

    @Test
    public void aSingleFrameStreakTakesOnlyItsOwnFrame() {
        TrackLinker.Track streak = streak(trail(1, 200, 260));
        SourceExtractor.DetectedObject sameFrame = anomaly(1, 300, 100);
        SourceExtractor.DetectedObject nextFrame = anomaly(2, 330, 100);

        Set<SourceExtractor.DetectedObject> joined = FaintStreakFragmentGrouper.joinAnomaliesOnStreakPaths(
                List.of(streak), List.of(sameFrame, nextFrame));

        assertEquals(Set.of(sameFrame), joined);
    }

    /** A streak piece in {@code frame} along y = 100 from {@code x0} to {@code x1}, taken a minute per frame. */
    private static SourceExtractor.DetectedObject trail(int frame, int x0, int x1) {
        SourceExtractor.DetectedObject part = piece((x0 + x1) / 2.0, 100, 0);
        part.sourceFrameIndex = frame;
        part.timestamp = frame * 60_000L;
        part.rawPixels = new ArrayList<>();
        for (int x = x0; x <= x1; x++) {
            part.rawPixels.add(new SourceExtractor.Pixel(x, 100, 100));
        }
        return part;
    }

    private static SourceExtractor.DetectedObject anomaly(int frame, double x, double y) {
        SourceExtractor.DetectedObject object = bright(x, y);
        object.sourceFrameIndex = frame;
        object.timestamp = frame * 60_000L;
        return object;
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

    private static TrackLinker.AnomalyDetection anomalyFor(TrackLinker.TrackingResult result,
                                                           SourceExtractor.DetectedObject object) {
        for (TrackLinker.AnomalyDetection anomaly : result.anomalies) {
            if (anomaly.object == object) {
                return anomaly;
            }
        }
        throw new AssertionError("missing anomaly");
    }

    private static SourceExtractor.DetectedObject roundPiece(double x, double y) {
        SourceExtractor.DetectedObject object = fragment(x, y, Math.PI / 2.0);
        object.elongation = 1.2;
        object.pixelArea = 8;
        return object;
    }

    private static SourceExtractor.DetectedObject bright(double x, double y) {
        SourceExtractor.DetectedObject object = fragment(x, y, 0.0);
        object.pixelArea = 20;
        object.elongation = 1.2;
        object.peakSigma = 10.0;
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
