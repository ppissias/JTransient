package io.github.ppissias.jtransient.compatibility;

import io.github.ppissias.jtransient.config.DetectionConfig;
import io.github.ppissias.jtransient.core.MasterMapGenerator;
import io.github.ppissias.jtransient.core.PixelEncoding;
import io.github.ppissias.jtransient.core.SourceExtractor;
import io.github.ppissias.jtransient.core.TrackLinker;
import io.github.ppissias.jtransient.engine.CalibratedAutoTuner;
import io.github.ppissias.jtransient.engine.ImageFrame;
import io.github.ppissias.jtransient.engine.JTransientAutoTuner;
import io.github.ppissias.jtransient.engine.JTransientEngine;
import io.github.ppissias.jtransient.engine.PipelineResult;

import java.io.DataInputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Standalone source/binary canary compiled against legacy public types and executed only on the current engine. */
public final class LegacyApiConsumer {
    /** Exercises public constructors, nested types, legacy fallback aliases, stack encoding and engine entry points. */
    public static void main(String[] args) throws Exception {
        try (var input = new DataInputStream(JTransientEngine.class.getResourceAsStream("JTransientEngine.class"))) {
            require(input.readInt() == 0xCAFEBABE, "Invalid classfile");
            input.readUnsignedShort();
            require(input.readUnsignedShort() == 65, "Engine must target Java 21");
        }
        DetectionConfig config = new DetectionConfig();
        var object = new SourceExtractor.DetectedObject(10, 12, 100, 5);
        object.rawPixels = new ArrayList<>(List.of(new SourceExtractor.Pixel(10, 12, 1000)));
        TrackLinker.Track track = new TrackLinker.Track();
        track.addPoint(object);
        require(track.points.get(0) == object, "Detection identity changed");

        List<ImageFrame> frames = frames();
        var profile = JTransientAutoTuner.AutoTuneProfile.MEDIUM;
        var legacy = JTransientAutoTuner.tune(frames.subList(0, 4), config, profile, null);
        var calibrated = CalibratedAutoTuner.tune(frames.subList(0, 4), config, profile, null);
        require(!legacy.success && legacy.optimizedConfig == config, "Legacy fallback alias changed");
        require(!calibrated.success && calibrated.optimizedConfig == config, "Calibrated fallback alias changed");
        var detached = JTransientAutoTuner.tuneSnapshot(frames.subList(0, 4), config, profile, null);
        require(detached.optimizedConfig != config, "Snapshot fallback aliases caller settings");
        require(new com.google.gson.Gson().toJson(detached).contains("optimizedConfig"), "Result JSON compatibility changed");
        require(PixelEncoding.toShiftedPositiveInt(MasterMapGenerator.createMedianMasterStack(frames)[5][5]) == 1000,
                "Median pixel encoding changed");

        try (JTransientEngine engine = new JTransientEngine()) {
            PipelineResult result = engine.runPipeline(frames, config, null);
            require(result.tracks != null && result.telemetry != null && result.masterStackData != null,
                    "Legacy result fields missing");
            require(frames.get(0).sequenceIndex == 0, "Legacy in-place sorting changed");
            require(engine.generateMasterStack(new ArrayList<>(frames), config, null) != null, "Sequential reuse failed");
        }
        System.out.println("Legacy public API consumer passed on Java 21.");
    }

    /** Builds small valid mono16 descriptors in reverse order without sharing mutable frame containers. */
    private static List<ImageFrame> frames() {
        List<ImageFrame> frames = new ArrayList<>();
        for (int frameIndex = 5; frameIndex >= 0; frameIndex--) {
            short[][] pixels = new short[24][24];
            for (short[] row : pixels) Arrays.fill(row, PixelEncoding.fromShiftedPositiveInt(1000));
            frames.add(new ImageFrame(frameIndex, "frame-" + frameIndex, pixels, frameIndex * 1000L, 1000));
        }
        return frames;
    }

    /** Fails the standalone verification without requiring JUnit or adding an application dependency. */
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
