package io.github.ppissias.jtransient.consumer;

import io.github.ppissias.jtransient.model.DetectedObject;
import io.github.ppissias.jtransient.model.Pixel;
import io.github.ppissias.jtransient.model.Track;
import io.github.ppissias.jtransient.config.DetectionConfig;
import io.github.ppissias.jtransient.core.MasterMapGenerator;
import io.github.ppissias.jtransient.core.PixelEncoding;
import io.github.ppissias.jtransient.engine.AutoTuneProfile;
import io.github.ppissias.jtransient.engine.AutoTunerResult;
import io.github.ppissias.jtransient.engine.CalibratedAutoTuner;
import io.github.ppissias.jtransient.engine.ImageFrame;
import io.github.ppissias.jtransient.engine.JTransientEngine;
import io.github.ppissias.jtransient.engine.PipelineResult;

import java.io.DataInputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Standalone consumer compiled and executed against the selected current engine API. */
public final class ApiConsumer {
    /** Exercises current public types, detached tuning settings, stack encoding and sequential engine reuse. */
    public static void main(String[] args) throws Exception {
        try (var input = new DataInputStream(JTransientEngine.class.getResourceAsStream("JTransientEngine.class"))) {
            require(input.readInt() == 0xCAFEBABE, "Invalid classfile");
            input.readUnsignedShort();
            require(input.readUnsignedShort() == 65, "Engine must target Java 21");
        }
        DetectionConfig config = new DetectionConfig();
        var object = new DetectedObject(10, 12, 100, 5);
        object.rawPixels = new ArrayList<>(List.of(new Pixel(10, 12, 1000)));
        Track track = new Track();
        track.addPoint(object);
        require(track.points.get(0) == object, "Detection identity changed");

        List<ImageFrame> frames = frames();
        AutoTunerResult calibrated = CalibratedAutoTuner.tune(frames.subList(0, 4), config, AutoTuneProfile.MEDIUM, null);
        require(!calibrated.success && calibrated.optimizedConfig != config, "Calibrated tuning must detach fallback settings");
        require(new com.google.gson.Gson().toJson(calibrated).contains("optimizedConfig"), "Result JSON missing settings");
        require(PixelEncoding.toShiftedPositiveInt(MasterMapGenerator.createMedianMasterStack(frames)[5][5]) == 1000,
                "Median pixel encoding changed");

        try (JTransientEngine engine = new JTransientEngine()) {
            PipelineResult result = engine.runPipeline(frames, config, null);
            require(result.tracks != null && result.telemetry != null && result.masterStackData != null,
                    "Result fields missing");
            require(!result.tooFewFrames && engine.runPipeline(frames.subList(0, JTransientEngine.MIN_KEPT_FRAMES - 1),
                    config, null).tooFewFrames, "Too-few-frames result missing");
            require(engine.generateMasterStack(new ArrayList<>(frames), config, null) != null, "Sequential reuse failed");
        }
        System.out.println("Current public API consumer passed on Java 21.");
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
