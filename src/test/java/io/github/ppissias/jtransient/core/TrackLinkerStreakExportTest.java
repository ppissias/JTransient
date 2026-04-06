package io.github.ppissias.jtransient.core;

import io.github.ppissias.jtransient.config.DetectionConfig;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Regression tests for how streak detections are exported once streak tracks are formed.
 */
public class TrackLinkerStreakExportTest {

    /**
     * Tracked streaks should still be present in the broad all-transients export so callers can
     * review the full post-veto detection population.
     */
    @Test
    public void filterTransientsIncludesTrackedStreaksInAllTransients() {
        DetectionConfig config = new DetectionConfig();
        List<List<SourceExtractor.DetectedObject>> frames = new ArrayList<>();
        SourceExtractor.DetectedObject streak1 = createStreak(10.0, 10.0, 0);
        SourceExtractor.DetectedObject streak2 = createStreak(20.0, 10.0, 1);
        frames.add(List.of(streak1));
        frames.add(List.of(streak2));
        frames.add(new ArrayList<>());

        TrackLinker.TransientsFilterResult result = TrackLinker.filterTransients(
                frames,
                new ArrayList<>(),
                config,
                null,
                64,
                64
        );

        assertEquals(1, result.streakTracks.size());
        assertEquals(2, result.streakTracks.get(0).points.size());
        assertEquals(1, result.allTransients.get(0).size());
        assertEquals(1, result.allTransients.get(1).size());
        assertTrue(result.allTransients.get(0).contains(streak1));
        assertTrue(result.allTransients.get(1).contains(streak2));
        assertTrue(result.allTransients.get(2).isEmpty());
    }

    @Test
    public void filterTransientsPreservesLowSigmaSingleStreakInAllTransients() {
        DetectionConfig config = new DetectionConfig();
        SourceExtractor.DetectedObject lowSigmaStreak = createStreak(10.0, 10.0, 0);
        lowSigmaStreak.peakSigma = config.singleStreakMinPeakSigma - 1.0;

        List<List<SourceExtractor.DetectedObject>> frames = new ArrayList<>();
        frames.add(List.of(lowSigmaStreak));
        frames.add(new ArrayList<>());
        frames.add(new ArrayList<>());

        TrackLinker.TransientsFilterResult result = TrackLinker.filterTransients(
                frames,
                new ArrayList<>(),
                config,
                null,
                64,
                64
        );

        assertTrue(result.streakTracks.isEmpty());
        assertEquals(1, result.allTransients.get(0).size());
        assertTrue(result.allTransients.get(0).contains(lowSigmaStreak));
    }

    private static SourceExtractor.DetectedObject createStreak(double x, double y, int frameIndex) {
        SourceExtractor.DetectedObject obj = new SourceExtractor.DetectedObject(x, y, 500.0, 20);
        obj.isStreak = true;
        obj.angle = 0.0;
        obj.peakSigma = 9.0;
        obj.sourceFrameIndex = frameIndex;
        obj.rawPixels = new ArrayList<>();
        obj.rawPixels.add(new SourceExtractor.Pixel((int) x, (int) y, (short) 200));
        return obj;
    }
}
