package io.github.ppissias.jtransient.engine;

import io.github.ppissias.jtransient.config.DetectionConfig;
import io.github.ppissias.jtransient.core.FrameDriftAnalyzer;
import io.github.ppissias.jtransient.core.PixelEncoding;
import io.github.ppissias.jtransient.execution.CancellationScope;
import io.github.ppissias.jtransient.execution.OperationFailedException;
import io.github.ppissias.jtransient.execution.StageId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;

/** Standalone QC must reproduce pipeline decisions without retaining or mutating caller image buffers. */
public class FrameQualityResultTest {
    /** Shared limits, metrics, rejection priority and padding fits agree for irregular original sequence indices. */
    @Test public void streamedAndInMemoryQualityExactlyMatchPipeline() {
        List<ImageFrame> frames = frames(8);
        DetectionConfig config = new DetectionConfig();
        FrameQualityResult expected;
        try (var engine = new JTransientEngine(2)) {
            PipelineResult full = engine.runPipeline(frames, config, null);
            var fits = new HashMap<Integer, io.github.ppissias.jtransient.core.FrameRegistration>();
            for (var fit : FrameDriftAnalyzer.analyze(frames).frameRegistrations) fits.put(fit.sequenceIndex, fit);
            expected = FrameQualityResult.capture(full.telemetry, fits);
            assertEquals(expected, engine.evaluateFrameQuality(frames, config, null));
            AtomicInteger reads = new AtomicInteger();
            List<Integer> progress = new ArrayList<>();
            var streamed = engine.evaluateFrameQuality(frames.size(), index -> {
                reads.incrementAndGet();
                return frames.get(index);
            }, config, (percent, message) -> progress.add(percent));
            assertEquals(expected, streamed);
            assertEquals(2 * frames.size(), reads.get());
            assertEquals(100, (int) progress.getLast());
        }
        assertEquals(8, expected.frames().size());
        assertTrue(expected.frames().stream().anyMatch(FrameQualityResult.Frame::rejected));
        assertThrows(UnsupportedOperationException.class, () -> expected.frames().clear());
    }

    /**
     * With excluded areas, standalone QC measures what detection measures with the same areas, which differs from the
     * measurement without them, and leaves the caller's pixels untouched.
     */
    @Test public void qualityWithExcludedAreasMatchesPipelineWithThem() {
        List<ImageFrame> frames = frames(8);
        List<short[][]> before = frames.stream()
                .map(f -> Arrays.stream(f.pixelData).map(short[]::clone).toArray(short[][]::new)).toList();
        DetectionConfig config = new DetectionConfig();
        List<ExcludedArea> areas = List.of(new ExcludedArea(55, 0, 20, 140), new ExcludedArea(100, 95, 40, 45));
        try (var engine = new JTransientEngine(2)) {
            PipelineResult full = engine.runPipeline(frames, config, null, null, null, areas);
            var fits = new HashMap<Integer, io.github.ppissias.jtransient.core.FrameRegistration>();
            for (var fit : FrameDriftAnalyzer.analyze(frames).frameRegistrations) fits.put(fit.sequenceIndex, fit);
            FrameQualityResult expected = FrameQualityResult.capture(full.telemetry, fits);
            assertEquals(expected, engine.evaluateFrameQuality(frames, config, null, areas));
            assertEquals(expected, engine.evaluateFrameQuality(frames.size(), frames::get, config, null, areas));
            assertNotEquals("the areas change the measurement", expected, engine.evaluateFrameQuality(frames, config, null));
        }
        for (int i = 0; i < frames.size(); i++) assertTrue(Arrays.deepEquals(before.get(i), frames.get(i).pixelData));
    }

    /** Too few inputs and absent clocks are useful quality results, with the same unavailable threshold semantics. */
    @Test public void fewFramesAndMissingClockAreReported() {
        List<ImageFrame> frames = frames(2).stream().map(f -> new ImageFrame(f.sequenceIndex, f.filename,
                f.pixelData, -1, -1)).toList();
        try (var engine = new JTransientEngine(1)) {
            var result = engine.evaluateFrameQuality(frames, new DetectionConfig(), null);
            assertTrue(result.tooFewFrames());
            assertFalse(result.thresholds().available());
            assertEquals(2, result.frames().size());
        }
    }

    /** A loader cannot silently switch the original frame metadata between coverage and measurement passes. */
    @Test public void changedSourceIsNamedAndEngineCanBeReused() {
        List<ImageFrame> frames = frames(2);
        AtomicInteger reads = new AtomicInteger();
        try (var engine = new JTransientEngine(1)) {
            var failure = assertThrows(OperationFailedException.class, () -> engine.evaluateFrameQuality(2, index -> {
                ImageFrame f = frames.get(index);
                return reads.incrementAndGet() > 2
                        ? new ImageFrame(f.sequenceIndex, "changed", f.pixelData, f.timestamp, f.exposureDuration) : f;
            }, new DetectionConfig(), null));
            assertEquals(StageId.FRAME_QUALITY, failure.failure().stage());
            assertTrue(failure.getMessage().contains("changed between quality passes"));
            assertEquals(2, engine.evaluateFrameQuality(frames, new DetectionConfig(), null).frames().size());
        }
    }

    /** Cancellation propagates and buffers stay byte-identical even when the engine is reused afterwards. */
    @Test public void cancellationAndReadOnlyInputs() {
        List<ImageFrame> frames = frames(2);
        short[][] first = Arrays.stream(frames.getFirst().pixelData).map(short[]::clone).toArray(short[][]::new);
        try (var engine = new JTransientEngine(1)) {
            try (var cancellation = CancellationScope.open(() -> true)) {
                assertThrows(CancellationException.class, () -> engine.evaluateFrameQuality(frames, new DetectionConfig(), null));
            }
            engine.evaluateFrameQuality(frames, new DetectionConfig(), null);
        }
        assertTrue(Arrays.deepEquals(first, frames.getFirst().pixelData));
    }

    /** Noisy star fields, one failed registration and one severe coverage outlier, in reverse sequence order. */
    private static List<ImageFrame> frames(int count) {
        List<ImageFrame> frames = new ArrayList<>();
        for (int index = count - 1; index >= 0; index--) {
            Random random = new Random(17 + index);
            short[][] pixels = new short[140][140];
            for (int y = 0; y < 140; y++) for (int x = 0; x < 140; x++) {
                double value = 1000 + random.nextGaussian() * 8;
                for (int sy = 20; sy < 130; sy += 22) for (int sx = 20; sx < 130; sx += 22)
                    value += 2500 * Math.exp(-((x-sx)*(x-sx) + (y-sy)*(y-sy)) / 4.0);
                if (index == 3 || x < (index == 4 ? 25 : index % 2)) value = 0;
                pixels[y][x] = PixelEncoding.fromShiftedPositiveInt((int) value);
            }
            frames.add(new ImageFrame(index * 3 + 2, "frame-" + index, pixels, index * 60_000L, 30_000));
        }
        return frames;
    }
}
