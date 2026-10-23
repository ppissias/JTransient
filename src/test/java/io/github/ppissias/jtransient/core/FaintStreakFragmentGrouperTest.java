package io.github.ppissias.jtransient.core;

import io.github.ppissias.jtransient.model.AnomalyDetection;
import io.github.ppissias.jtransient.model.DetectedObject;
import io.github.ppissias.jtransient.model.Pixel;
import io.github.ppissias.jtransient.model.Track;
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
        Track streak = streak(piece(100, 100, 0), piece(300, 100, 0));
        DetectedObject onLine = fragment(200, 100.4, 0.0);
        DetectedObject alsoOnLine = fragment(250, 99.2, 0.05);
        DetectedObject offLine = fragment(200, 110, 0.0);
        DetectedObject pastTheEnd = fragment(500, 100, 0.0);
        List<DetectedObject> frame = new ArrayList<>(List.of(onLine, alsoOnLine, offLine, pastTheEnd));

        FaintStreakFragmentGrouper.assemble(new ArrayList<>(List.of(streak)), List.of(frame), new ArrayList<>(), new HashSet<>(), new DetectionConfig());

        assertTrue(streak.points.contains(onLine));
        assertTrue(streak.points.contains(alsoOnLine));
        assertFalse(streak.points.contains(offLine));
        assertFalse(streak.points.contains(pastTheEnd));
    }

    @Test
    public void singlePartStreakDoesNotAbsorbADistantCollinearPiece() {
        DetectedObject body = piece(100, 100, 0);
        body.rawPixels = new ArrayList<>();
        for (int x = 90; x <= 110; x++) {
            body.rawPixels.add(new Pixel(x, 100, 100));
        }
        Track streak = streak(body);
        DetectedObject nearby = fragment(125, 100.2, 0.0);
        DetectedObject distant = fragment(400, 100, 0.0);

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
        List<DetectedObject> good = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            good.add(fragment(100 + i * 80, 50 + i * 0.2, 0.0));
        }
        DetectedObject roundOnTheLine = fragment(260, 50.1, 0.0);
        roundOnTheLine.elongation = 1.2;
        good.add(roundOnTheLine);

        List<Track> tracks = new ArrayList<>();
        FaintStreakFragmentGrouper.assemble(tracks, List.of(good), List.of(), new HashSet<>(), new DetectionConfig());

        assertEquals(1, tracks.size());
        assertTrue(tracks.get(0).isStreakTrack);
        assertEquals(6, tracks.get(0).points.size());
        assertTrue(tracks.get(0).points.contains(roundOnTheLine));

        List<DetectedObject> gapped = List.of(
                fragment(0, 10, 0.0),
                fragment(10, 10, 0.0),
                fragment(20, 10, 0.0),
                fragment(1000, 10, 0.0));
        List<Track> rejected = new ArrayList<>();
        FaintStreakFragmentGrouper.assemble(rejected, List.of(gapped), List.of(), new HashSet<>(), new DetectionConfig());
        assertTrue(rejected.isEmpty());
    }

    @Test
    public void roundKnotsFillGapsButDoNotFormALineByThemselves() {
        List<DetectedObject> gapped = new ArrayList<>();
        for (int x : new int[]{0, 100, 200, 800}) {
            gapped.add(fragment(x, 40, 0.0));
        }
        List<Track> withoutFillers = new ArrayList<>();
        FaintStreakFragmentGrouper.assemble(withoutFillers, List.of(gapped), List.of(), new HashSet<>(), new DetectionConfig());
        assertTrue(withoutFillers.isEmpty());

        List<DetectedObject> filled = new ArrayList<>(gapped);
        for (int x : new int[]{350, 500, 650}) {
            DetectedObject knot = fragment(x, 40.2, Math.PI / 2.0);
            knot.elongation = 1.2;
            knot.pixelArea = 8;
            filled.add(knot);
        }
        List<Track> tracks = new ArrayList<>();
        FaintStreakFragmentGrouper.assemble(tracks, List.of(filled), List.of(), new HashSet<>(), new DetectionConfig());
        assertEquals(1, tracks.size());
        assertEquals(7, tracks.get(0).points.size());

        List<DetectedObject> onlyRound = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            DetectedObject knot = fragment(20 + i * 80, 10, 0.0);
            knot.elongation = 1.2;
            onlyRound.add(knot);
        }
        List<Track> rejected = new ArrayList<>();
        FaintStreakFragmentGrouper.assemble(rejected, List.of(onlyRound), List.of(), new HashSet<>(), new DetectionConfig());
        assertTrue(rejected.isEmpty());
    }

    @Test
    public void absorbsARoundPieceOntoAnExistingStreakDespiteItsAngle() {
        Track streak = streak(piece(100, 100, 0), piece(300, 100, 0));
        DetectedObject round = fragment(200, 100.3, Math.PI / 2.0);
        round.elongation = 1.2;
        DetectedObject pointingElsewhere = fragment(240, 100.2, Math.PI / 2.0);

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
        List<DetectedObject> crossed = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            crossed.add(fragment(50 + i * 100, 80, Math.PI / 2.0));
        }
        List<Track> tracks = new ArrayList<>();
        FaintStreakFragmentGrouper.assemble(tracks, List.of(crossed), List.of(), new HashSet<>(), new DetectionConfig());
        assertTrue(tracks.isEmpty());
    }

    @Test
    public void extendsAStreakAlongAConnectedChainAndStopsAtAGap() {
        Track streak = streak(piece(100, 100, 0), piece(300, 100, 0));
        DetectedObject first = roundPiece(360, 100.3);
        DetectedObject second = roundPiece(430, 100.2);
        DetectedObject tooFar = roundPiece(700, 100.0);
        DetectedObject offLine = roundPiece(360, 110);
        DetectedObject pointingElsewhere = fragment(370, 100.1, Math.PI / 2.0);
        DetectedObject tooSmall = roundPiece(340, 100.2);
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
        List<DetectedObject> line = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            line.add(fragment(100 + i * 80, 100, 0.0));
        }
        DetectedObject onPath = bright(250, 104);
        DetectedObject farAway = bright(250, 220);
        line.add(onPath);
        line.add(farAway);

        List<List<DetectedObject>> frames = new ArrayList<>();
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
        Track streak = streak(trail(0, 100, 160), trail(1, 200, 260), trail(3, 400, 460));
        DetectedObject inTheGap = anomaly(2, 330, 101);
        DetectedObject offTheLine = anomaly(2, 320, 115);
        DetectedObject pastTheNextFrame = anomaly(2, 560, 100);

        Set<DetectedObject> joined = FaintStreakFragmentGrouper.joinAnomaliesOnStreakPaths(
                List.of(streak), List.of(inTheGap, offTheLine, pastTheNextFrame));

        assertEquals(Set.of(inTheGap), joined);
        assertTrue(streak.points.contains(inTheGap));
    }

    @Test
    public void aStreakThroughAStarJoinsAlthoughTheStarPullsItsCentreOffTheLine() {
        Track streak = streak(trail(0, 100, 160), trail(1, 200, 260), trail(3, 400, 460));
        DetectedObject star = anomaly(2, 330, 109);
        star.rawPixels = new ArrayList<>();
        for (int y = 101; y <= 117; y++) {
            star.rawPixels.add(new Pixel(330, y, 500));
        }
        DetectedObject nearbyStar = anomaly(2, 340, 112);
        nearbyStar.rawPixels = new ArrayList<>();
        for (int y = 105; y <= 119; y++) {
            nearbyStar.rawPixels.add(new Pixel(340, y, 500));
        }

        Set<DetectedObject> joined = FaintStreakFragmentGrouper.joinAnomaliesOnStreakPaths(
                List.of(streak), List.of(star, nearbyStar));

        assertEquals("only the star the line passes through", Set.of(star), joined);
    }

    @Test
    public void aStreakTakesTheFrameAfterItsLastAndThenTheNextOne() {
        Track streak = streak(trail(0, 100, 160), trail(1, 200, 260));
        DetectedObject frameTwo = anomaly(2, 330, 100);
        DetectedObject frameThree = anomaly(3, 420, 100);
        DetectedObject tooFarAhead = anomaly(2, 700, 100);
        DetectedObject behindTheMotion = anomaly(2, 120, 100);
        DetectedObject twoFramesOn = anomaly(5, 630, 100);

        Set<DetectedObject> joined = FaintStreakFragmentGrouper.joinAnomaliesOnStreakPaths(
                List.of(streak), List.of(frameThree, frameTwo, tooFarAhead, behindTheMotion, twoFramesOn));

        assertEquals(Set.of(frameTwo, frameThree), joined);
    }

    @Test
    public void withoutTimestampsTheFrameOrderGivesTheMotion() {
        DetectedObject[] parts = {trail(0, 100, 160), trail(1, 200, 260), trail(3, 400, 460)};
        for (DetectedObject part : parts) {
            part.timestamp = -1L;
        }
        Track streak = streak(parts);
        DetectedObject inTheGap = anomaly(2, 330, 100);
        inTheGap.timestamp = -1L;
        DetectedObject frameFour = anomaly(4, 530, 100);
        frameFour.timestamp = -1L;

        Set<DetectedObject> joined = FaintStreakFragmentGrouper.joinAnomaliesOnStreakPaths(
                List.of(streak), List.of(inTheGap, frameFour));

        assertEquals(Set.of(inTheGap, frameFour), joined);
    }

    @Test
    public void aSingleFrameStreakTakesOnlyItsOwnFrame() {
        Track streak = streak(trail(1, 200, 260));
        DetectedObject sameFrame = anomaly(1, 300, 100);
        DetectedObject nextFrame = anomaly(2, 330, 100);

        Set<DetectedObject> joined = FaintStreakFragmentGrouper.joinAnomaliesOnStreakPaths(
                List.of(streak), List.of(sameFrame, nextFrame));

        assertEquals(Set.of(sameFrame), joined);
    }

    /** A streak piece in {@code frame} along y = 100 from {@code x0} to {@code x1}, taken a minute per frame. */
    private static DetectedObject trail(int frame, int x0, int x1) {
        DetectedObject part = piece((x0 + x1) / 2.0, 100, 0);
        part.sourceFrameIndex = frame;
        part.timestamp = frame * 60_000L;
        part.rawPixels = new ArrayList<>();
        for (int x = x0; x <= x1; x++) {
            part.rawPixels.add(new Pixel(x, 100, 100));
        }
        return part;
    }

    private static DetectedObject anomaly(int frame, double x, double y) {
        DetectedObject object = bright(x, y);
        object.sourceFrameIndex = frame;
        object.timestamp = frame * 60_000L;
        return object;
    }

    @Test
    public void staysQuietWhenTheSwitchIsOff() {
        DetectionConfig config = new DetectionConfig();
        config.enableFaintStreakFragments = false;
        List<DetectedObject> pieces = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            pieces.add(fragment(10 + i * 90, 20, 0.0));
        }
        List<Track> tracks = new ArrayList<>();
        FaintStreakFragmentGrouper.assemble(tracks, List.of(pieces), List.of(), new HashSet<>(), config);
        assertTrue(tracks.isEmpty());
    }

    @Test
    public void findMovingObjectsKeepsAFaintLineAsASingleFrameStreak() {
        List<DetectedObject> pieces = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            pieces.add(fragment(40 + i * 70, 80, 0.0));
        }
        List<List<DetectedObject>> frames = new ArrayList<>();
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

    private static Track streak(DetectedObject... parts) {
        Track track = new Track();
        track.isStreakTrack = true;
        for (DetectedObject part : parts) {
            track.addPoint(part);
        }
        return track;
    }

    private static DetectedObject piece(double x, double y, double angle) {
        DetectedObject object = fragment(x, y, angle);
        object.pixelArea = 40;
        object.elongation = 8;
        object.peakSigma = 12;
        object.isStreak = true;
        return object;
    }

    private static AnomalyDetection anomalyFor(TrackLinker.TrackingResult result,
                                                           DetectedObject object) {
        for (AnomalyDetection anomaly : result.anomalies) {
            if (anomaly.object == object) {
                return anomaly;
            }
        }
        throw new AssertionError("missing anomaly");
    }

    private static DetectedObject roundPiece(double x, double y) {
        DetectedObject object = fragment(x, y, Math.PI / 2.0);
        object.elongation = 1.2;
        object.pixelArea = 8;
        return object;
    }

    private static DetectedObject bright(double x, double y) {
        DetectedObject object = fragment(x, y, 0.0);
        object.pixelArea = 20;
        object.elongation = 1.2;
        object.peakSigma = 10.0;
        return object;
    }

    private static DetectedObject fragment(double x, double y, double angle) {
        DetectedObject object = new DetectedObject(x, y, 10, 10);
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
