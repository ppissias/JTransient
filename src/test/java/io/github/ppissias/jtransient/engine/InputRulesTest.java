package io.github.ppissias.jtransient.engine;

import io.github.ppissias.jtransient.config.DetectionConfig;
import io.github.ppissias.jtransient.core.SourceExtractor;
import io.github.ppissias.jtransient.core.TrackLinker;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The engine checks the input rules at its entry points and names the frames that break them.
 */
public class InputRulesTest {

    @Test
    public void acceptsAValidSession() {
        InputRules.checkPipelineInput(frames(4, 0L, 60_000L), null);
        InputRules.checkPipelineInput(frames(4, -1L, -1L), new short[20][30]);
    }

    @Test
    public void acceptsTimestampZeroAsARealTime() {
        List<ImageFrame> frames = new ArrayList<>();
        frames.add(new ImageFrame(0, "a.fits", new short[20][30], 0L, -1L));
        frames.add(new ImageFrame(1, "b.fits", new short[20][30], 60_000L, -1L));
        InputRules.checkFrames(frames);
    }

    @Test
    public void acceptsRecordedExposuresWithinTwoPercent() {
        List<ImageFrame> frames = new ArrayList<>();
        frames.add(new ImageFrame(0, "a.fits", new short[20][30], 1000L, 60_000L));
        frames.add(new ImageFrame(1, "b.fits", new short[20][30], 2000L, 60_100L));
        frames.add(new ImageFrame(2, "c.fits", new short[20][30], 3000L, 60_200L));
        InputRules.checkFrames(frames);
    }

    @Test
    public void rejectsAnEmptySession() {
        expectRejection(() -> InputRules.checkPipelineInput(new ArrayList<>(), null), "No frames");
    }

    @Test
    public void rejectsFramesOfDifferentSizes() {
        List<ImageFrame> frames = frames(3, -1L, -1L);
        frames.add(new ImageFrame(3, "odd.fits", new short[21][30], -1L, -1L));
        expectRejection(() -> InputRules.checkFrames(frames), "odd.fits (30 × 21)");
    }

    @Test
    public void rejectsRowsOfDifferentLengths() {
        short[][] ragged = new short[20][30];
        ragged[5] = new short[29];
        List<ImageFrame> frames = frames(2, -1L, -1L);
        frames.add(new ImageFrame(2, "ragged.fits", ragged, -1L, -1L));
        expectRejection(() -> InputRules.checkFrames(frames), "ragged.fits");
    }

    @Test
    public void rejectsASharedSequenceIndex() {
        List<ImageFrame> frames = frames(2, -1L, -1L);
        frames.add(new ImageFrame(1, "twin.fits", new short[20][30], -1L, -1L));
        expectRejection(() -> InputRules.checkFrames(frames), "both have 1");
    }

    @Test
    public void rejectsAMixOfTimedAndUntimedFrames() {
        List<ImageFrame> frames = frames(3, 0L, -1L);
        frames.add(new ImageFrame(3, "untimed.fits", new short[20][30], -1L, -1L));
        expectRejection(() -> InputRules.checkFrames(frames), "1 of 4 frames have none: untimed.fits");
    }

    @Test
    public void rejectsAMixOfFramesWithAndWithoutExposure() {
        List<ImageFrame> frames = frames(3, -1L, 60_000L);
        frames.add(new ImageFrame(3, "noexp.fits", new short[20][30], -1L, -1L));
        expectRejection(() -> InputRules.checkFrames(frames), "noexp.fits");
    }

    @Test
    public void rejectsDifferentExposureTimesAndNamesTheSmallerGroup() {
        List<ImageFrame> frames = frames(4, -1L, 60_000L);
        frames.add(new ImageFrame(4, "long1.fits", new short[20][30], -1L, 120_000L));
        frames.add(new ImageFrame(5, "long2.fits", new short[20][30], -1L, 120_000L));
        expectRejection(() -> InputRules.checkFrames(frames),
                "4 frames of 60 s and 2 frames of 120 s (long1.fits, long2.fits)");
    }

    @Test
    public void rejectsAMasterStackOfAnotherSize() {
        expectRejection(() -> InputRules.checkPipelineInput(frames(3, -1L, -1L), new short[20][31]),
                "master stack is 31 × 20");
    }

    @Test
    public void engineEntryPointsCheckTheirInput() throws Exception {
        List<ImageFrame> frames = frames(3, -1L, -1L);
        frames.add(new ImageFrame(3, "odd.fits", new short[21][30], -1L, -1L));
        try (JTransientEngine engine = new JTransientEngine()) {
            try {
                engine.runPipeline(frames, new DetectionConfig(), null);
                fail("runPipeline accepted frames of different sizes");
            } catch (IllegalArgumentException expected) {
                assertTrue(expected.getMessage().contains("odd.fits"));
            }
            try {
                engine.generateMasterStack(frames, new DetectionConfig(), null);
                fail("generateMasterStack accepted frames of different sizes");
            } catch (IllegalArgumentException expected) {
                assertTrue(expected.getMessage().contains("odd.fits"));
            }
        }
    }

    @Test
    public void trackLinkerRejectsAMixOfTimedAndUntimedDetections() {
        List<List<SourceExtractor.DetectedObject>> detections = new ArrayList<>();
        for (int frame = 0; frame < 3; frame++) {
            SourceExtractor.DetectedObject object = new SourceExtractor.DetectedObject(10 + frame, 10, 100, 5);
            object.sourceFrameIndex = frame;
            object.timestamp = frame == 1 ? -1L : frame * 1000L;
            object.exposureDuration = -1L;
            List<SourceExtractor.DetectedObject> objects = new ArrayList<>();
            objects.add(object);
            detections.add(objects);
        }
        expectRejection(() -> TrackLinker.findMovingObjects(detections, new ArrayList<>(), new DetectionConfig(),
                null, 64, 64), "without one: [1]");
    }

    private static List<ImageFrame> frames(int count, long firstTimestamp, long exposure) {
        List<ImageFrame> frames = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            long timestamp = firstTimestamp == -1L ? -1L : firstTimestamp + i * 60_000L;
            frames.add(new ImageFrame(i, "frame" + i + ".fits", new short[20][30], timestamp, exposure));
        }
        return frames;
    }

    private static void expectRejection(Runnable check, String expectedText) {
        try {
            check.run();
            fail("expected an IllegalArgumentException containing: " + expectedText);
        } catch (IllegalArgumentException expected) {
            assertTrue("message was: " + expected.getMessage(), expected.getMessage().contains(expectedText));
        }
    }
}
