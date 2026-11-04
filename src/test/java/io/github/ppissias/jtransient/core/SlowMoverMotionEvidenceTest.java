package io.github.ppissias.jtransient.core;

import com.google.gson.Gson;
import io.github.ppissias.jtransient.engine.ImageFrame;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/** Scientific and availability controls for descriptive timed aperture-centroid fits. */
public class SlowMoverMotionEvidenceTest {
    /** Uneven capture spacing, timestamp zero and noncontiguous original IDs retain their actual units. */
    @Test public void fitsActualTimesAndCopiesScalarPositions() {
        var frames = frames(0, 60_000, 240_000, 600_000);
        var positions = positions(frames);
        var motion = SlowMoverMotionEvidence.measure(positions, frames, Math.atan2(-1, 2));
        assertTrue(motion.fitAvailable);
        assertTrue(motion.correlationAvailable);
        assertEquals(2, motion.velocityXPixelsPerMinute, 1e-12);
        assertEquals(-1, motion.velocityYPixelsPerMinute, 1e-12);
        assertEquals(Math.sqrt(5), motion.speedPixelsPerMinute, 1e-12);
        assertEquals(10 * Math.sqrt(5), motion.displacementPixels, 1e-12);
        assertEquals(0, motion.rmsPixels, 1e-12);
        assertEquals(1, motion.projectedCorrelation, 1e-12);
        assertEquals(1, motion.sessionSpanFraction, 0);
        assertEquals(130, motion.positions.get(3).sourceFrameIndex());
        positions.clear();
        frames.get(0).pixelData[0][0] = 123;
        assertEquals(4, motion.positions.size());
        assertEquals(10, motion.positions.get(0).x(), 0);
        assertThrows(UnsupportedOperationException.class, () -> motion.positions.clear());
    }

    /** Unknown, repeated or decreasing times never produce an ordinal speed disguised as pixels per minute. */
    @Test public void invalidClocksPreservePositionsWithoutATimedFit() {
        for (long[] times : new long[][]{{-1, -1, -1}, {0, 0, 60_000}, {0, 120_000, 60_000}}) {
            var frames = frames(times);
            var motion = SlowMoverMotionEvidence.measure(positions(frames), frames, 0);
            assertFalse(motion.fitAvailable);
            assertNotNull(motion.unavailableReason);
            assertEquals(3, motion.positions.size());
            assertEquals(0, motion.speedPixelsPerMinute, 0);
            assertFalse(new Gson().toJson(motion).contains("NaN"));
        }
    }

    /** A constant centroid has a valid zero-drift fit but no defined correlation coefficient. */
    @Test public void stationaryPositionsDoNotInventCorrelation() {
        var frames = frames(0, 60_000, 120_000);
        List<SlowMoverMotionEvidence.Position> positions = new ArrayList<>();
        for (int i = 0; i < frames.size(); i++) positions.add(position(frames, i, 10, 20));
        var motion = SlowMoverMotionEvidence.measure(positions, frames, 0);
        assertTrue(motion.fitAvailable);
        assertFalse(motion.correlationAvailable);
        assertEquals(0, motion.speedPixelsPerMinute, 0);
        assertEquals(0, motion.rmsPixels, 0);
        new Gson().toJson(motion);
    }

    /** Sparse early support is reported with its actual span rather than extrapolating across the session. */
    @Test public void reportsLimitedCoverageAndInsufficientSupport() {
        var frames = frames(0, 60_000, 120_000, 6_000_000);
        var sample = positions(frames).subList(0, 3);
        var motion = SlowMoverMotionEvidence.measure(sample, frames, 0);
        assertTrue(motion.fitAvailable);
        assertEquals(.02, motion.sessionSpanFraction, 1e-12);
        assertEquals(2 * Math.sqrt(5), motion.displacementPixels, 1e-12);
        var two = SlowMoverMotionEvidence.measure(sample.subList(0, 2), frames, 0);
        assertFalse(two.fitAvailable);
        assertEquals(2, two.positions.size());
    }

    /** A curved path can have high linear correlation; its residual remains visible instead of implying identity. */
    @Test public void curvatureIsNotHiddenByHighCorrelation() {
        var frames = frames(0, 60_000, 120_000, 180_000, 240_000);
        List<SlowMoverMotionEvidence.Position> positions = new ArrayList<>();
        for (int i = 0; i < frames.size(); i++) positions.add(position(frames, i, 10 + i, 20 + .5 * (i - 2) * (i - 2)));
        var motion = SlowMoverMotionEvidence.measure(positions, frames, 0);
        assertTrue(motion.fitAvailable);
        assertEquals(1, motion.projectedCorrelation, 1e-12);
        assertTrue(motion.rmsPixels > .5);
    }

    /** Builds metadata-only frames with sparse original sequence identities and no shared pixel references. */
    static List<ImageFrame> frames(long... times) {
        List<ImageFrame> frames = new ArrayList<>();
        for (int i = 0; i < times.length; i++) frames.add(new ImageFrame(10 + 40 * i, "frame-" + i, new short[1][1], times[i], 1000));
        return frames;
    }

    /** Creates a known drift of (+2,-1) pixels per minute using the actual capture times. */
    static List<SlowMoverMotionEvidence.Position> positions(List<ImageFrame> frames) {
        List<SlowMoverMotionEvidence.Position> positions = new ArrayList<>();
        for (int i = 0; i < frames.size(); i++) {
            double time = frames.get(i).timestamp / 60000.0;
            positions.add(position(frames, i, 10 + 2 * time, 20 - time));
        }
        return positions;
    }

    /** Copies one frame's identity and time into a supported centroid in pixel coordinates. */
    static SlowMoverMotionEvidence.Position position(List<ImageFrame> frames, int i, double x, double y) {
        return new SlowMoverMotionEvidence.Position(i, frames.get(i).sequenceIndex, frames.get(i).timestamp, x, y, 10);
    }
}
