package io.github.ppissias.jtransient.core;

import io.github.ppissias.jtransient.model.DetectedObject;
import io.github.ppissias.jtransient.model.Track;
import io.github.ppissias.jtransient.config.DetectionConfig;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class ResidualTransientAnalyzerTest {

    @Test
    public void findsCoherentMicroDriftAcrossManyNearbyFrames() {
        DetectionConfig config = new DetectionConfig();
        List<List<DetectedObject>> remaining = emptyFrameList(8);
        DetectedObject first = addPoint(remaining, 1, 100.0, 100.0, 8.0, 9.0);
        DetectedObject second = addPoint(remaining, 2, 101.1, 100.4, 7.5, 8.5);
        DetectedObject third = addPoint(remaining, 3, 102.0, 101.0, 7.0, 8.0);
        DetectedObject fourth = addPoint(remaining, 4, 102.9, 101.2, 8.2, 9.1);
        DetectedObject fifth = addPoint(remaining, 5, 103.8, 101.8, 7.8, 8.6);

        addPoint(remaining, 2, 135.0, 80.0, 5.0, 5.5);
        addPoint(remaining, 5, 70.0, 40.0, 4.0, 4.1);

        ResidualTransientAnalysis analysis = ResidualTransientAnalyzer.analyze(
                remaining,
                config
        );

        assertEquals(1, analysis.localRescueCandidates.size());
        assertEquals(ResidualTransientAnalysis.LocalRescueKind.MICRO_DRIFT, analysis.localRescueCandidates.get(0).kind);
        assertEquals(5, analysis.localRescueCandidates.get(0).points.size());
        assertSame(first, analysis.localRescueCandidates.get(0).points.get(0));
        assertSame(second, analysis.localRescueCandidates.get(0).points.get(1));
        assertSame(third, analysis.localRescueCandidates.get(0).points.get(2));
        assertSame(fourth, analysis.localRescueCandidates.get(0).points.get(3));
        assertSame(fifth, analysis.localRescueCandidates.get(0).points.get(4));
        assertTrue(analysis.localRescueCandidates.get(0).metrics.totalDisplacementPixels > 3.0);
        assertTrue(analysis.localRescueCandidates.get(0).metrics.linearityRmsePixels < 1.0);
        assertTrue(analysis.localActivityClusters.isEmpty());
    }

    @Test
    public void findsStationarySameLocationRepeat() {
        DetectionConfig config = new DetectionConfig();
        List<List<DetectedObject>> remaining = emptyFrameList(7);
        addPoint(remaining, 1, 88.0, 44.0, 7.0, 7.5);
        addPoint(remaining, 2, 88.1, 44.0, 6.8, 7.0);
        addPoint(remaining, 3, 88.0, 44.1, 6.9, 7.2);
        addPoint(remaining, 4, 88.1, 44.0, 7.1, 7.4);
        addPoint(remaining, 5, 88.0, 44.1, 6.7, 7.0);

        ResidualTransientAnalysis analysis = ResidualTransientAnalyzer.analyze(
                remaining,
                config
        );

        assertEquals(1, analysis.localRescueCandidates.size());
        assertEquals(ResidualTransientAnalysis.LocalRescueKind.LOCAL_REPEAT, analysis.localRescueCandidates.get(0).kind);
        assertTrue(analysis.localRescueCandidates.get(0).points.size() >= 2);
    }

    @Test
    public void findsSparseLocalDriftAcrossWideFrameSpan() {
        DetectionConfig config = new DetectionConfig();
        List<List<DetectedObject>> remaining = emptyFrameList(18);
        addPoint(remaining, 2, 2088.3, 3295.2, 5.0, 12.04);
        addPoint(remaining, 5, 2087.2, 3293.6, 3.96, 9.63);
        addPoint(remaining, 12, 2085.0, 3291.3, 4.75, 9.70);
        addPoint(remaining, 16, 2083.9, 3289.8, 5.56, 10.32);

        ResidualTransientAnalysis analysis = ResidualTransientAnalyzer.analyze(
                remaining,
                config
        );

        assertEquals(1, analysis.localRescueCandidates.size());
        assertEquals(ResidualTransientAnalysis.LocalRescueKind.SPARSE_LOCAL_DRIFT, analysis.localRescueCandidates.get(0).kind);
        assertEquals(4, analysis.localRescueCandidates.get(0).points.size());
        assertTrue(analysis.localRescueCandidates.get(0).metrics.totalDisplacementPixels > 6.0);
        assertTrue(analysis.localRescueCandidates.get(0).metrics.linearityRmsePixels < 0.3);
    }

    @Test
    public void filtersAlreadyClassifiedDetectionsBeforeResidualAnalysis() {
        DetectionConfig config = new DetectionConfig();
        List<List<DetectedObject>> remaining = emptyFrameList(6);
        DetectedObject first = addPoint(remaining, 1, 512.1, 300.0, 7.0, 7.8);
        DetectedObject second = addPoint(remaining, 2, 512.4, 300.2, 7.1, 7.7);

        Track existingTrack = new Track();
        existingTrack.addPoint(first);

        List<DetectedObject> excluded = ResidualTransientAnalyzer.collectAlreadyClassifiedPoints(
                Collections.singletonList(existingTrack),
                Collections.emptyList()
        );
        excluded.add(clonePoint(second, second.x + 0.2, second.y - 0.1));

        List<List<DetectedObject>> filtered = ResidualTransientAnalyzer.filterExcludedDetections(
                remaining,
                excluded
        );

        assertTrue(filtered.get(1).isEmpty());
        assertTrue(filtered.get(2).isEmpty());
        assertTrue(ResidualTransientAnalyzer.findLocalRescueCandidates(filtered).isEmpty());
    }

    @Test
    public void buildsLocalActivityClusterFromLeftoverPointsWithinConfiguredRadius() {
        DetectionConfig config = new DetectionConfig();
        config.enableLocalActivityClusters = true;
        List<List<DetectedObject>> remaining = emptyFrameList(6);
        addPoint(remaining, 1, 300.0, 300.0, 2.0, 2.5);
        addPoint(remaining, 3, 306.0, 306.0, 2.1, 2.4);
        addPoint(remaining, 5, 312.0, 309.0, 1.9, 2.2);

        ResidualTransientAnalysis analysis = ResidualTransientAnalyzer.analyze(
                remaining,
                config
        );

        assertTrue(analysis.localRescueCandidates.isEmpty());
        assertEquals(1, analysis.localActivityClusters.size());
        assertEquals(3, analysis.localActivityClusters.get(0).points.size());
        assertEquals(3, analysis.localActivityClusters.get(0).metrics.uniqueFrameCount);
        assertEquals(10.0, analysis.localActivityClusters.get(0).linkageRadiusPixels, 0.0);
    }

    @Test
    public void localActivityClustersDoNotReusePointsConsumedByAcceptedRescueCandidates() {
        DetectionConfig config = new DetectionConfig();
        List<List<DetectedObject>> remaining = emptyFrameList(8);
        addPoint(remaining, 1, 100.0, 100.0, 8.0, 9.0);
        addPoint(remaining, 2, 101.1, 100.4, 7.5, 8.5);
        addPoint(remaining, 3, 102.0, 101.0, 7.0, 8.0);
        addPoint(remaining, 4, 102.9, 101.2, 8.2, 9.1);
        addPoint(remaining, 5, 103.8, 101.8, 7.8, 8.6);
        addPoint(remaining, 6, 104.2, 102.0, 2.0, 2.2);

        ResidualTransientAnalysis analysis = ResidualTransientAnalyzer.analyze(
                remaining,
                config
        );

        assertEquals(1, analysis.localRescueCandidates.size());
        assertEquals(ResidualTransientAnalysis.LocalRescueKind.MICRO_DRIFT, analysis.localRescueCandidates.get(0).kind);
        assertTrue(analysis.localActivityClusters.isEmpty());
    }

    /**
     * Frame 3 was left out by quality control, so the kept frames are 0, 1, 2, 4, 5, 6. A repeat seen in frames 1, 2,
     * 4 and 5 was seen in every kept frame it spans: coverage 1, no gap.
     */
    @Test
    public void framesLeftOutByQualityControlAreNotCountedAsGaps() {
        DetectionConfig config = new DetectionConfig();
        int[] sequenceOfPosition = {0, 1, 2, 4, 5, 6};
        List<List<DetectedObject>> remaining = emptyFrameList(sequenceOfPosition.length);
        double[][] positions = {{88.0, 44.0}, {88.1, 44.0}, {88.0, 44.1}, {88.1, 44.1}};
        for (int i = 0; i < positions.length; i++) {
            DetectedObject point = addPoint(remaining, i + 1, positions[i][0], positions[i][1], 7.0, 7.5);
            point.sourceFrameIndex = sequenceOfPosition[i + 1];
        }

        ResidualTransientAnalysis analysis = ResidualTransientAnalyzer.analyze(remaining, config);

        assertEquals(1, analysis.localRescueCandidates.size());
        ResidualTransientAnalysis.LocalTransientMetrics metrics = analysis.localRescueCandidates.get(0).metrics;
        assertTrue("the candidate spans the left-out frame", spansSequence(analysis.localRescueCandidates.get(0).points, 3));
        assertEquals(analysis.localRescueCandidates.get(0).points.size(), metrics.frameSpan);
        assertEquals(0, metrics.totalGapFrames);
        assertEquals(1.0, metrics.frameCoverage, 1e-9);
    }

    private static boolean spansSequence(List<DetectedObject> points, int sequence) {
        int first = Integer.MAX_VALUE;
        int last = Integer.MIN_VALUE;
        for (DetectedObject point : points) {
            first = Math.min(first, point.sourceFrameIndex);
            last = Math.max(last, point.sourceFrameIndex);
        }
        return first < sequence && sequence < last;
    }

    private static List<List<DetectedObject>> emptyFrameList(int frameCount) {
        List<List<DetectedObject>> frames = new ArrayList<>();
        for (int i = 0; i < frameCount; i++) {
            frames.add(new ArrayList<>());
        }
        return frames;
    }

    private static DetectedObject addPoint(List<List<DetectedObject>> frames,
                                                           int frameIndex,
                                                           double x,
                                                           double y,
                                                           double peakSigma,
                                                           double integratedSigma) {
        DetectedObject object = new DetectedObject(x, y, 100.0, 4);
        object.sourceFrameIndex = frameIndex;
        object.sourceFilename = "frame_" + frameIndex + ".fit";
        object.peakSigma = peakSigma;
        object.integratedSigma = integratedSigma;
        object.pixelArea = 4.0;
        object.fwhm = 2.0;
        object.elongation = 1.1;
        frames.get(frameIndex).add(object);
        return object;
    }

    private static DetectedObject clonePoint(DetectedObject source,
                                                             double x,
                                                             double y) {
        DetectedObject clone = new DetectedObject(x, y, source.totalFlux, source.pixelCount);
        clone.sourceFrameIndex = source.sourceFrameIndex;
        clone.sourceFilename = source.sourceFilename;
        clone.peakSigma = source.peakSigma;
        clone.integratedSigma = source.integratedSigma;
        clone.pixelArea = source.pixelArea;
        clone.fwhm = source.fwhm;
        clone.elongation = source.elongation;
        clone.isStreak = source.isStreak;
        return clone;
    }
}
