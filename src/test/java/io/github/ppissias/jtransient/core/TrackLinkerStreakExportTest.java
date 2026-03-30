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
     * Streaks that already belong to an accepted streak track should not be re-exported
     * as standalone detections in the merged transient list.
     */
    @Test
    public void filterTransientsDoesNotDuplicateTrackedStreaksInMergedTransients() {
        DetectionConfig config = new DetectionConfig();
        List<List<SourceExtractor.DetectedObject>> frames = new ArrayList<>();
        frames.add(List.of(createStreak(10.0, 10.0, 0)));
        frames.add(List.of(createStreak(20.0, 10.0, 1)));
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
        assertTrue(result.mergedTransients.get(0).isEmpty());
        assertTrue(result.mergedTransients.get(1).isEmpty());
        assertTrue(result.mergedTransients.get(2).isEmpty());
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
