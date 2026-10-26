package io.github.ppissias.jtransient.engine;

import com.google.gson.ExclusionStrategy;
import com.google.gson.FieldAttributes;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.github.ppissias.jtransient.config.DetectionConfig;
import io.github.ppissias.jtransient.core.PixelEncoding;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/** Checks the stage observer contract: order, the result's own products, disabled stages and isolated failures. */
public class PipelineStageObserverTest {

    @Test
    public void stagesArriveInOrderWithTheResultsOwnProducts() {
        DetectionConfig config = new DetectionConfig();
        config.enableVariableStarDetection = true;
        List<PipelineStage> stages = new ArrayList<>();
        try (var engine = new JTransientEngine(1)) {
            PipelineResult result = engine.runPipeline(frames(), config, null, null, stages::add);
            assertEquals(List.of(PipelineStage.MasterReference.class, PipelineStage.SlowMovers.class, PipelineStage.Tracking.class,
                            PipelineStage.CommonMotion.class, PipelineStage.ResidualAnalysis.class, PipelineStage.Photometry.class),
                    stages.stream().map(Object::getClass).toList());
            var reference = (PipelineStage.MasterReference) stages.get(0);
            assertSame(result.masterStackData, reference.medianStack());
            assertSame(result.masterStars, reference.masterStars());
            var slowMovers = (PipelineStage.SlowMovers) stages.get(1);
            assertEquals(config.enableSlowMoverDetection, slowMovers.enabled());
            assertSame(result.maximumStackData, slowMovers.maximumStack());
            assertSame(result.slowMoverAnalysis.candidates, slowMovers.candidates());
            var tracking = (PipelineStage.Tracking) stages.get(2);
            assertSame(result.tracks, tracking.tracks());
            assertSame(result.anomalies, tracking.anomalies());
            assertSame(result.masterVetoMask, tracking.starMask());
            var photometry = (PipelineStage.Photometry) stages.get(5);
            assertTrue(photometry.enabled());
            assertSame(result.variableStarAnalysis, photometry.analysis());
        }
    }

    @Test
    public void switchedOffStagesStillArriveMarkedOff() {
        DetectionConfig config = new DetectionConfig();
        config.enableSlowMoverDetection = false;
        config.enableCommonMotionRejection = false;
        config.enableResidualTransientAnalysis = false;
        config.enableVariableStarDetection = false;
        List<PipelineStage> stages = new ArrayList<>();
        try (var engine = new JTransientEngine(1)) {
            engine.runPipeline(frames(), config, null, null, stages::add);
        }
        assertEquals(6, stages.size());
        var slowMovers = (PipelineStage.SlowMovers) stages.get(1);
        assertFalse(slowMovers.enabled());
        assertTrue(slowMovers.candidates().isEmpty());
        assertFalse(((PipelineStage.CommonMotion) stages.get(3)).enabled());
        assertTrue(((PipelineStage.CommonMotion) stages.get(3)).groups().isEmpty());
        assertFalse(((PipelineStage.ResidualAnalysis) stages.get(4)).enabled());
        assertFalse(((PipelineStage.Photometry) stages.get(5)).enabled());
    }

    /** No observer, a no-op observer and a throwing observer give the same result, and the run does not fail. */
    @Test
    public void observersCannotChangeOrFailTheRun() {
        DetectionConfig config = new DetectionConfig();
        config.enableVariableStarDetection = true;
        PipelineResult plain;
        PipelineResult quiet;
        PipelineResult failing;
        List<PipelineStage> seen = new ArrayList<>();
        try (var engine = new JTransientEngine(1)) {
            plain = engine.runPipeline(frames(), config, null);
            quiet = engine.runPipeline(frames(), config, null, null, stage -> { });
            failing = engine.runPipeline(frames(), config, null, null, stage -> {
                seen.add(stage);
                throw new IllegalStateException("observer failure");
            });
        }
        assertEquals(6, seen.size());
        assertEquals(snapshot(plain), snapshot(quiet));
        assertEquals(snapshot(plain), snapshot(failing));
        assertTrue(Arrays.deepEquals(plain.masterStackData, failing.masterStackData));
        assertTrue(Arrays.deepEquals(plain.masterVetoMask, failing.masterVetoMask));
    }

    /** One permission produces one snapshot, and the detection result stays the same. */
    @Test
    public void anInStageSnapshotIsBuiltOnlyWhenRequested() {
        DetectionConfig config = new DetectionConfig();
        AtomicInteger permits = new AtomicInteger(1);
        AtomicInteger updates = new AtomicInteger();
        PipelineStageObserver observer = new PipelineStageObserver() {
            @Override public void stageFinished(PipelineStage stage) { }
            @Override public boolean wantsUpdate() { return permits.getAndDecrement() > 0; }
            @Override public void liveUpdate(LiveSnapshot snapshot) { updates.incrementAndGet(); }
        };
        PipelineResult plain;
        PipelineResult watched;
        try (var engine = new JTransientEngine(1)) {
            plain = engine.runPipeline(frames(), config, null);
            watched = engine.runPipeline(frames(), config, null, null, observer);
        }
        assertEquals(1, updates.get());
        assertEquals(snapshot(plain), snapshot(watched));
    }

    /** Extraction snapshots sample across the detected objects so points cover the full field. */
    @Test
    public void sourceExtractionSnapshotSamplesAcrossTheField() {
        DetectionConfig config = new DetectionConfig();
        List<LiveSnapshot> snapshots = new ArrayList<>();
        PipelineStageObserver observer = new PipelineStageObserver() {
            @Override public void stageFinished(PipelineStage stage) { }
            @Override public boolean wantsUpdate() { return true; }
            @Override public void liveUpdate(LiveSnapshot snapshot) {
                if ("Source extraction".equals(snapshot.phase())) {
                    snapshots.add(snapshot);
                }
            }
        };
        try (var engine = new JTransientEngine(1)) {
            engine.runPipeline(frames(), config, null, null, observer);
        }
        assertFalse(snapshots.isEmpty());
        LiveSnapshot sample = snapshots.get(0);
        assertTrue(sample.caption().contains("Source extraction"));
        double minY = Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;
        for (double y : sample.y()) {
            minY = Math.min(minY, y);
            maxY = Math.max(maxY, y);
        }
        assertTrue("Snapshot points should be spread across y, range=" + (maxY - minY), maxY - minY >= 20.0);
    }

    /** The result as JSON without images, masks and timings. */
    private static String snapshot(PipelineResult result) {
        Gson gson = new GsonBuilder().serializeNulls().serializeSpecialFloatingPointValues()
                .addSerializationExclusionStrategy(new ExclusionStrategy() {
                    @Override
                    public boolean shouldSkipField(FieldAttributes field) {
                        return field.getDeclaredClass() == short[][].class || field.getDeclaredClass() == boolean[][].class
                                || field.getName().endsWith("TimeMs") || field.getName().equals("stageTimeMs");
                    }

                    @Override
                    public boolean shouldSkipClass(Class<?> type) {
                        return false;
                    }
                }).create();
        return gson.toJson(result);
    }

    /** Five noisy frames with a few stars and a source that moves, so every stage has something to do. */
    private static List<ImageFrame> frames() {
        Random random = new Random(7);
        List<ImageFrame> frames = new ArrayList<>();
        for (int index = 0; index < 5; index++) {
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
}
