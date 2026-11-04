package io.github.ppissias.jtransient.core;

import io.github.ppissias.jtransient.model.*;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/** Controls for same-frame correspondence, crossings and ambiguous original identities without deduplication. */
public class SlowMoverTrackMatcherTest {
    /** Matching original identities, rather than retained-list offsets, supplies the expected centroid distances. */
    @Test public void comparesTheActualCommonFrames() {
        var evidence = evidence();
        var track = track(evidence, false);
        var match = SlowMoverTrackMatcher.match(footprint(), evidence, track);
        assertEquals(5, match.overlappingTrackPoints());
        assertEquals(5, match.comparedFrames());
        assertTrue(match.frameComparisonAvailable());
        assertEquals(0, match.framePositionRmsPixels(), 0);
        assertEquals(0, match.maxFramePositionDistancePixels(), 0);
        assertEquals(5, track.points.size());
    }

    /** Reversed chronological motion traverses the same footprint but disagrees on the common frames. */
    @Test public void spatialCoincidenceDoesNotHideWrongTimeOrder() {
        var evidence = evidence();
        var reversed = SlowMoverTrackMatcher.match(footprint(), evidence, track(evidence, true));
        assertEquals(5, reversed.overlappingTrackPoints());
        assertEquals(5, reversed.comparedFrames());
        assertEquals(Math.sqrt(8), reversed.framePositionRmsPixels(), 1e-12);
        assertEquals(4, reversed.maxFramePositionDistancePixels(), 0);
    }

    /** A crossing remains a possible overlap, with its large common-frame separation visible. */
    @Test public void crossingAndParallelTracksRetainTheirDistances() {
        var evidence = evidence();
        var crossing = track(evidence, false);
        for (int i = 0; i < 5; i++) crossing.points.get(i).y += 4 * (i - 2);
        var match = SlowMoverTrackMatcher.match(footprint(), evidence, crossing);
        assertNotNull(match);
        assertTrue(match.framePositionRmsPixels() > 5);
        var parallel = track(evidence, false);
        for (var p : parallel.points) p.y += 2;
        assertEquals(2, SlowMoverTrackMatcher.match(footprint(), evidence, parallel).framePositionRmsPixels(), 0);
    }

    /** Repeated IDs and conflicting known times cannot be silently paired by nearest position. */
    @Test public void excludesAmbiguousAndInconsistentIdentities() {
        var evidence = evidence();
        var track = track(evidence, false);
        var repeated = new DetectedObject(10, 20, 100, 1);
        repeated.sourceFrameIndex = track.points.get(0).sourceFrameIndex;
        track.points.add(repeated);
        track.points.get(1).timestamp++;
        var match = SlowMoverTrackMatcher.match(footprint(), evidence, track);
        assertEquals(1, match.ambiguousFrames());
        assertEquals(1, match.timestampMismatches());
        assertEquals(3, match.comparedFrames());
        assertEquals(0, match.framePositionRmsPixels(), 0);
    }

    /** Partial tracks and unknown timing do not acquire a claim of duplicate identity. */
    @Test public void partialAndSpatialOnlyEvidenceAreExplicit() {
        var evidence = evidence();
        var track = track(evidence, false);
        track.points = new ArrayList<>(track.points.subList(1, 3));
        assertEquals(2, SlowMoverTrackMatcher.match(footprint(), evidence, track).comparedFrames());
        var spatial = SlowMoverTrackMatcher.match(footprint(), null, track);
        assertFalse(spatial.frameComparisonAvailable());
        assertEquals(0, spatial.comparedFrames());
        for (var p : track.points) p.x += 100;
        assertNull(SlowMoverTrackMatcher.match(footprint(), evidence, track));
    }

    /** Makes a five-pixel horizontal footprint, distinct from the sequence identities carried by its measurements. */
    private static DetectedObject footprint() {
        var object = new DetectedObject(12, 20, 100, 5);
        object.rawPixels = new ArrayList<>();
        for (int x = 10; x <= 14; x++) object.rawPixels.add(new Pixel(x, 20, 100));
        object.pixelArea = 5;
        return object;
    }

    /** Returns exact timed motion with noncontiguous original identities. */
    private static SlowMoverMotionEvidence evidence() {
        var frames = SlowMoverMotionEvidenceTest.frames(0, 60_000, 120_000, 180_000, 240_000);
        List<SlowMoverMotionEvidence.Position> positions = new ArrayList<>();
        for (int i = 0; i < 5; i++) positions.add(SlowMoverMotionEvidenceTest.position(frames, i, 10 + i, 20));
        return SlowMoverMotionEvidence.measure(positions, frames, 0);
    }

    /** Copies the same source IDs/times while optionally reversing the coordinates, not the chronology. */
    private static Track track(SlowMoverMotionEvidence evidence, boolean reverse) {
        Track track = new Track();
        for (int i = 0; i < evidence.positions.size(); i++) {
            var identity = evidence.positions.get(i);
            var coordinate = evidence.positions.get(reverse ? evidence.positions.size() - 1 - i : i);
            var point = new DetectedObject(coordinate.x(), coordinate.y(), 100, 1);
            point.sourceFrameIndex = identity.sourceFrameIndex();
            point.timestamp = identity.timestamp();
            track.points.add(point);
        }
        return track;
    }
}
