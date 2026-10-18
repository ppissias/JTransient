package io.github.ppissias.jtransient.core;

import io.github.ppissias.jtransient.config.DetectionConfig;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * The number of points a point track needs never drops below three, whatever the profile says.
 */
public class TrackLinkerRequiredPointsTest {

    @Test
    public void defaultsGiveFramesOverThreeCappedAtFive() {
        DetectionConfig config = new DetectionConfig();
        assertEquals(3, TrackLinker.requiredTrackPoints(3, config));
        assertEquals(3, TrackLinker.requiredTrackPoints(9, config));
        assertEquals(4, TrackLinker.requiredTrackPoints(10, config));
        assertEquals(5, TrackLinker.requiredTrackPoints(200, config));
    }

    @Test
    public void capBelowThreeStillNeedsThreePoints() {
        DetectionConfig config = new DetectionConfig();
        config.absoluteMaxPointsRequired = 2;
        assertEquals(3, TrackLinker.requiredTrackPoints(30, config));
        config.absoluteMaxPointsRequired = 0;
        assertEquals(3, TrackLinker.requiredTrackPoints(30, config));
    }

    @Test
    public void ratioThatIsNotPositiveCountsAsTheDefault() {
        DetectionConfig config = new DetectionConfig();
        int expected = TrackLinker.requiredTrackPoints(12, config);
        for (double ratio : new double[]{0.0, -2.0, Double.NaN}) {
            config.trackMinFrameRatio = ratio;
            assertEquals("ratio " + ratio, expected, TrackLinker.requiredTrackPoints(12, config));
        }
    }
}
