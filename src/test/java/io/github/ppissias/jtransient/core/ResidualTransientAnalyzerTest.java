package io.github.ppissias.jtransient.core;

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
        List<List<SourceExtractor.DetectedObject>> remaining = emptyFrameList(8);
        SourceExtractor.DetectedObject first = addPoint(remaining, 1, 100.0, 100.0, 8.0, 9.0);
        SourceExtractor.DetectedObject second = addPoint(remaining, 2, 101.1, 100.4, 7.5, 8.5);
        SourceExtractor.DetectedObject third = addPoint(remaining, 3, 102.0, 101.0, 7.0, 8.0);
        SourceExtractor.DetectedObject fourth = addPoint(remaining, 4, 102.9, 101.2, 8.2, 9.1);
        SourceExtractor.DetectedObject fifth = addPoint(remaining, 5, 103.8, 101.8, 7.8, 8.6);

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
        List<List<SourceExtractor.DetectedObject>> remaining = emptyFrameList(7);
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
        List<List<SourceExtractor.DetectedObject>> remaining = emptyFrameList(18);
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
        List<List<SourceExtractor.DetectedObject>> remaining = emptyFrameList(6);
        SourceExtractor.DetectedObject first = addPoint(remaining, 1, 512.1, 300.0, 7.0, 7.8);
        SourceExtractor.DetectedObject second = addPoint(remaining, 2, 512.4, 300.2, 7.1, 7.7);

        TrackLinker.Track existingTrack = new TrackLinker.Track();
        existingTrack.addPoint(first);

        List<SourceExtractor.DetectedObject> excluded = ResidualTransientAnalyzer.collectAlreadyClassifiedPoints(
                Collections.singletonList(existingTrack),
                Collections.emptyList()
        );
        excluded.add(clonePoint(second, second.x + 0.2, second.y - 0.1));

        List<List<SourceExtractor.DetectedObject>> filtered = ResidualTransientAnalyzer.filterExcludedDetections(
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
        List<List<SourceExtractor.DetectedObject>> remaining = emptyFrameList(6);
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
        List<List<SourceExtractor.DetectedObject>> remaining = emptyFrameList(8);
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

    private static List<List<SourceExtractor.DetectedObject>> emptyFrameList(int frameCount) {
        List<List<SourceExtractor.DetectedObject>> frames = new ArrayList<>();
        for (int i = 0; i < frameCount; i++) {
            frames.add(new ArrayList<>());
        }
        return frames;
    }

    private static SourceExtractor.DetectedObject addPoint(List<List<SourceExtractor.DetectedObject>> frames,
                                                           int frameIndex,
                                                           double x,
                                                           double y,
                                                           double peakSigma,
                                                           double integratedSigma) {
        SourceExtractor.DetectedObject object = new SourceExtractor.DetectedObject(x, y, 100.0, 4);
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

    private static SourceExtractor.DetectedObject clonePoint(SourceExtractor.DetectedObject source,
                                                             double x,
                                                             double y) {
        SourceExtractor.DetectedObject clone = new SourceExtractor.DetectedObject(x, y, source.totalFlux, source.pixelCount);
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
