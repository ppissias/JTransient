package io.github.ppissias.jtransient.engine;

import io.github.ppissias.jtransient.config.DetectionConfig;
import io.github.ppissias.jtransient.core.PixelEncoding;
import io.github.ppissias.jtransient.telemetry.PipelineTelemetry;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.*;

/**
 * The pipeline's few-frame rule: with fewer than {@link JTransientEngine#MIN_KEPT_FRAMES} frames left after quality
 * control, {@code runPipeline} stops there and returns a completed result marked {@link PipelineResult#tooFewFrames}.
 */
public class TooFewFramesTest {

    @Test
    public void twoFramesStopAfterQualityControl() {
        DetectionConfig config = new DetectionConfig();
        config.enableSlowMoverDetection = true;
        config.enableVariableStarDetection = true;
        List<PipelineStage> stages = new ArrayList<>();
        List<Integer> progress = new ArrayList<>();
        PipelineResult result;
        try (var engine = new JTransientEngine(1)) {
            result = engine.runPipeline(frames(2), config, (percent, message) -> progress.add(percent), null, stages::add);
        }

        assertTrue(result.tooFewFrames);
        assertEquals(2, result.telemetry.totalFramesLoaded);
        assertEquals(2, result.telemetry.totalFramesKept);
        assertEquals(2, result.telemetry.frameQualityStats.size());
        assertEquals(List.of(PipelineTelemetry.Stage.DRIFT_ANALYSIS, PipelineTelemetry.Stage.SOURCE_EXTRACTION,
                PipelineTelemetry.Stage.QUALITY_FILTERING), List.copyOf(result.telemetry.stageTimeMs.keySet()));
        assertNotNull(result.dataArea);
        assertEquals(2, result.driftPoints.size());

        assertNull(result.masterStackData);
        assertNull(result.maximumStackData);
        assertNull(result.masterVetoMask);
        assertNull(result.slowMoverStackData);
        assertNull(result.slowMoverMedianVetoMask);
        assertTrue(result.tracks.isEmpty());
        assertTrue(result.anomalies.isEmpty());
        assertTrue(result.masterStars.isEmpty());
        assertTrue(result.slowMoverCandidates.isEmpty());
        assertTrue(result.slowMoverAnalysis.candidates.isEmpty());
        assertTrue(result.allTransients.isEmpty());
        assertTrue(result.unclassifiedTransients.isEmpty());
        assertTrue(result.residualTransientAnalysis.localRescueCandidates.isEmpty());
        assertTrue(result.residualTransientAnalysis.commonMotionGroups.isEmpty());
        assertTrue(result.variableStarAnalysis.candidates.isEmpty());
        assertTrue(result.cameraFixedGroups.isEmpty());
        assertNull(result.telemetry.trackerTelemetry);
        assertNull(result.telemetry.slowMoverTelemetry);
        assertNull(result.telemetry.photometryTelemetry);
        assertEquals(0, result.telemetry.totalTracksFound);
        assertEquals(0, result.telemetry.totalAnomaliesFound);

        assertTrue(stages.isEmpty());
        assertEquals(100, (int) progress.getLast());
    }

    /** The rule counts the frames quality control keeps, not the frames the caller passes. */
    @Test
    public void framesRejectedByQualityControlDoNotCount() {
        List<ImageFrame> frames = frames(3);
        frames.set(1, blankFrame(1));
        PipelineResult result;
        try (var engine = new JTransientEngine(1)) {
            result = engine.runPipeline(frames, new DetectionConfig(), null);
        }

        assertTrue(result.tooFewFrames);
        assertEquals(3, result.telemetry.totalFramesLoaded);
        assertEquals(2, result.telemetry.totalFramesKept);
        assertEquals(1, result.telemetry.totalFramesRejected);
        assertEquals(1, result.telemetry.rejectedFrames.getFirst().frameIndex);
        assertNull(result.masterStackData);
    }

    /** Without a kept frame there is no master stack to build; the run still completes with the quality results. */
    @Test
    public void noKeptFrameIsACompletedResult() {
        PipelineResult result;
        try (var engine = new JTransientEngine(1)) {
            result = engine.runPipeline(new ArrayList<>(List.of(blankFrame(0))), new DetectionConfig(), null);
        }

        assertTrue(result.tooFewFrames);
        assertEquals(0, result.telemetry.totalFramesKept);
        assertEquals(1, result.telemetry.totalFramesRejected);
        assertNull(result.dataArea);
    }

    @Test
    public void threeKeptFramesRunEveryStage() {
        List<PipelineStage> stages = new ArrayList<>();
        PipelineResult result;
        try (var engine = new JTransientEngine(1)) {
            result = engine.runPipeline(frames(3), new DetectionConfig(), null, null, stages::add);
        }

        assertFalse(result.tooFewFrames);
        assertEquals(JTransientEngine.MIN_KEPT_FRAMES, result.telemetry.totalFramesKept);
        assertNotNull(result.masterStackData);
        assertNotNull(result.maximumStackData);
        assertNotNull(result.masterVetoMask);
        assertEquals(6, stages.size());
    }

    /** Noisy frames with two stars and a source that moves. */
    private static List<ImageFrame> frames(int count) {
        Random random = new Random(11);
        List<ImageFrame> frames = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            short[][] pixels = new short[96][96];
            for (int row = 0; row < 96; row++) {
                for (int column = 0; column < 96; column++) {
                    double value = 1000 + 8 * random.nextGaussian();
                    value += 900 * Math.exp(-(Math.pow(column - 30, 2) + Math.pow(row - 40, 2)) / 4.0);
                    value += 600 * Math.exp(-(Math.pow(column - 70, 2) + Math.pow(row - 60, 2)) / 4.0);
                    value += 500 * Math.exp(-(Math.pow(column - 12 - 14 * index, 2) + Math.pow(row - 20 - 10 * index, 2)) / 3.0);
                    pixels[row][column] = PixelEncoding.fromShiftedPositiveInt((int) Math.round(value));
                }
            }
            frames.add(new ImageFrame(index, "frame-" + index, pixels, index * 60_000L, 30_000));
        }
        return frames;
    }

    /** A frame without image data, as a failed registration leaves it; the drift analysis rejects it. */
    private static ImageFrame blankFrame(int index) {
        short[][] pixels = new short[96][96];
        for (short[] row : pixels) {
            Arrays.fill(row, PixelEncoding.fromShiftedPositiveInt(0));
        }
        return new ImageFrame(index, "frame-" + index, pixels, index * 60_000L, 30_000);
    }
}
