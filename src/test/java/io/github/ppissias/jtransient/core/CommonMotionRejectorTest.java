package io.github.ppissias.jtransient.core;

import io.github.ppissias.jtransient.config.DetectionConfig;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Common-motion grouping removes a separated pair only when the match holds on three frames.
 */
public class CommonMotionRejectorTest {

    @Test
    public void removesTwoLocalRescuesThatShareAThreeFrameShift() {
        ResidualTransientAnalysis.LocalRescueCandidate matchingA = rescue(
                compact(100, 100, 0), compact(99.5, 99.2, 1), compact(99.2, 98.3, 2));
        ResidualTransientAnalysis.LocalRescueCandidate matchingB = rescue(
                compact(2000, 1500, 0), compact(1999.7, 1498.9, 1), compact(1999.3, 1498.1, 2));
        ResidualTransientAnalysis.LocalRescueCandidate different = rescue(
                compact(500, 500, 0), compact(510, 500, 1), compact(520, 500, 2));
        ResidualTransientAnalysis.LocalRescueCandidate twoFrames = rescue(
                compact(40, 40, 0), compact(40.4, 43.6, 1));

        ResidualTransientAnalysis result = CommonMotionRejector.apply(
                new ResidualTransientAnalysis(List.of(matchingA, matchingB, different, twoFrames), List.of()),
                new ArrayList<>(),
                new DetectionConfig());

        assertEquals(2, result.localRescueCandidates.size());
        assertTrue(result.localRescueCandidates.contains(different));
        assertTrue(result.localRescueCandidates.contains(twoFrames));
        assertEquals(1, result.commonMotionGroups.size());
        assertEquals(2, result.commonMotionGroups.get(0).sources.size());
        assertEquals(3, result.commonMotionGroups.get(0).sharedFrameCount);
    }

    @Test
    public void removesTheSamePatternFromUnclassifiedAndKeepsATwoFramePair() {
        List<List<SourceExtractor.DetectedObject>> frames = new ArrayList<>();
        SourceExtractor.DetectedObject a0 = compact(100, 100, 0);
        SourceExtractor.DetectedObject a1 = compact(99.5, 99.2, 1);
        SourceExtractor.DetectedObject a2 = compact(99.2, 98.3, 2);
        SourceExtractor.DetectedObject b0 = compact(800, 600, 0);
        SourceExtractor.DetectedObject b1 = compact(799.6, 599.1, 1);
        SourceExtractor.DetectedObject b2 = compact(799.4, 598.2, 2);
        SourceExtractor.DetectedObject c0 = compact(20, 20, 0);
        SourceExtractor.DetectedObject c1 = compact(20.2, 23.4, 1);
        frames.add(new ArrayList<>(List.of(a0, b0, c0)));
        frames.add(new ArrayList<>(List.of(a1, b1, c1)));
        frames.add(new ArrayList<>(List.of(a2, b2)));

        ResidualTransientAnalysis result = CommonMotionRejector.apply(
                ResidualTransientAnalysis.empty(), frames, new DetectionConfig());

        assertEquals(1, result.commonMotionGroups.size());
        assertEquals(List.of(c0, c1), flat(frames));
    }

    @Test
    public void keepsEverythingWhenTheSwitchIsOff() {
        ResidualTransientAnalysis.LocalRescueCandidate matchingA = rescue(
                compact(100, 100, 0), compact(99.5, 99.2, 1), compact(99.2, 98.3, 2));
        ResidualTransientAnalysis.LocalRescueCandidate matchingB = rescue(
                compact(2000, 1500, 0), compact(1999.7, 1498.9, 1), compact(1999.3, 1498.1, 2));
        ResidualTransientAnalysis residual = new ResidualTransientAnalysis(List.of(matchingA, matchingB), List.of());
        DetectionConfig config = new DetectionConfig();
        config.enableCommonMotionRejection = false;

        assertSame(residual, CommonMotionRejector.apply(residual, new ArrayList<>(), config));
    }

    @Test
    public void doesNotGroupAnElongatedRescue() {
        ResidualTransientAnalysis.LocalRescueCandidate compactRescue = rescue(
                compact(100, 100, 0), compact(99.5, 99.2, 1), compact(99.2, 98.3, 2));
        SourceExtractor.DetectedObject elongated = compact(2000, 1500, 0);
        elongated.elongation = 6.0;
        ResidualTransientAnalysis.LocalRescueCandidate streakLike = rescue(
                elongated, compact(1999.7, 1498.9, 1), compact(1999.3, 1498.1, 2));

        ResidualTransientAnalysis result = CommonMotionRejector.apply(
                new ResidualTransientAnalysis(List.of(compactRescue, streakLike), List.of()),
                new ArrayList<>(),
                new DetectionConfig());

        assertEquals(2, result.localRescueCandidates.size());
        assertTrue(result.commonMotionGroups.isEmpty());
    }

    private static List<SourceExtractor.DetectedObject> flat(List<List<SourceExtractor.DetectedObject>> frames) {
        List<SourceExtractor.DetectedObject> all = new ArrayList<>();
        for (List<SourceExtractor.DetectedObject> frame : frames) {
            all.addAll(frame);
        }
        return all;
    }

    private static ResidualTransientAnalysis.LocalRescueCandidate rescue(SourceExtractor.DetectedObject... points) {
        return new ResidualTransientAnalysis.LocalRescueCandidate(
                ResidualTransientAnalysis.LocalRescueKind.MICRO_DRIFT,
                List.of(points),
                new ResidualTransientAnalysis.LocalTransientMetrics(
                        points.length, points.length, points.length, 0, 1, 1, 1, 0, 1, 1, 1, 1, 1),
                1.0);
    }

    private static SourceExtractor.DetectedObject compact(double x, double y, int frame) {
        SourceExtractor.DetectedObject object = new SourceExtractor.DetectedObject(x, y, 8, 8);
        object.pixelArea = 8;
        object.elongation = 1.3;
        object.peakSigma = 6;
        object.sourceFrameIndex = frame;
        return object;
    }
}
