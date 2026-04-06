package io.github.ppissias.jtransient.core;

import io.github.ppissias.jtransient.config.DetectionConfig;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Regression tests for the broad transient export and final unclassified export.
 */
public class TrackLinkerTransientExportTest {

    @Test
    public void findMovingObjectsExportsAllTransientsSeparatelyFromFinalUnclassifiedOnes() {
        DetectionConfig config = new DetectionConfig();

        SourceExtractor.DetectedObject track1 = createPoint(10.0, 10.0, 0);
        SourceExtractor.DetectedObject anomaly = createPoint(40.0, 40.0, 0);
        anomaly.pixelArea = 20;
        anomaly.peakSigma = 9.0;

        SourceExtractor.DetectedObject track2 = createPoint(20.0, 10.0, 1);
        SourceExtractor.DetectedObject leftover = createPoint(70.0, 70.0, 1);
        leftover.peakSigma = 2.0;
        leftover.integratedSigma = 2.5;

        SourceExtractor.DetectedObject track3 = createPoint(30.0, 10.0, 2);

        List<List<SourceExtractor.DetectedObject>> frames = List.of(
                List.of(track1, anomaly),
                List.of(track2, leftover),
                List.of(track3)
        );

        TrackLinker.TrackingResult result = TrackLinker.findMovingObjects(
                frames,
                new ArrayList<>(),
                config,
                null,
                128,
                128
        );

        assertEquals(1, result.tracks.size());
        assertEquals(1, result.anomalies.size());

        assertEquals(5, totalObjects(result.allTransients));
        assertTrue(result.allTransients.get(0).contains(track1));
        assertTrue(result.allTransients.get(0).contains(anomaly));
        assertTrue(result.allTransients.get(1).contains(track2));
        assertTrue(result.allTransients.get(1).contains(leftover));
        assertTrue(result.allTransients.get(2).contains(track3));

        assertEquals(1, totalObjects(result.unclassifiedTransients));
        assertSame(leftover, result.unclassifiedTransients.get(1).get(0));
    }

    @Test
    public void findMovingObjectsKeepsTrackedStreakPointsInAllTransientsButNotInUnclassifiedTransients() {
        DetectionConfig config = new DetectionConfig();

        SourceExtractor.DetectedObject streak1 = createStreak(10.0, 10.0, 0);
        SourceExtractor.DetectedObject streak2 = createStreak(20.0, 10.0, 1);

        List<List<SourceExtractor.DetectedObject>> frames = List.of(
                List.of(streak1),
                List.of(streak2),
                new ArrayList<>()
        );

        TrackLinker.TrackingResult result = TrackLinker.findMovingObjects(
                frames,
                new ArrayList<>(),
                config,
                null,
                64,
                64
        );

        assertEquals(1, result.tracks.size());
        assertEquals(2, totalObjects(result.allTransients));
        assertTrue(result.allTransients.get(0).contains(streak1));
        assertTrue(result.allTransients.get(1).contains(streak2));
        assertEquals(0, totalObjects(result.unclassifiedTransients));
    }

    private static int totalObjects(List<List<SourceExtractor.DetectedObject>> frames) {
        int total = 0;
        for (List<SourceExtractor.DetectedObject> frame : frames) {
            total += frame.size();
        }
        return total;
    }

    private static SourceExtractor.DetectedObject createPoint(double x, double y, int frameIndex) {
        SourceExtractor.DetectedObject obj = new SourceExtractor.DetectedObject(x, y, 100.0, 4);
        obj.sourceFrameIndex = frameIndex;
        obj.timestamp = -1L;
        obj.exposureDuration = -1L;
        obj.peakSigma = 7.0;
        obj.integratedSigma = 7.0;
        obj.pixelArea = 12.0;
        obj.fwhm = 2.0;
        obj.elongation = 1.1;
        obj.rawPixels = new ArrayList<>();
        obj.rawPixels.add(new SourceExtractor.Pixel((int) Math.round(x), (int) Math.round(y), 200));
        return obj;
    }

    private static SourceExtractor.DetectedObject createStreak(double x, double y, int frameIndex) {
        SourceExtractor.DetectedObject obj = new SourceExtractor.DetectedObject(x, y, 500.0, 20);
        obj.sourceFrameIndex = frameIndex;
        obj.isStreak = true;
        obj.angle = 0.0;
        obj.peakSigma = 9.0;
        obj.integratedSigma = 9.0;
        obj.pixelArea = 25.0;
        obj.rawPixels = new ArrayList<>();
        obj.rawPixels.add(new SourceExtractor.Pixel((int) Math.round(x), (int) Math.round(y), 200));
        return obj;
    }
}
