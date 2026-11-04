package io.github.ppissias.jtransient.consumer;

import io.github.ppissias.jtransient.model.DetectedObject;
import io.github.ppissias.jtransient.model.Pixel;
import io.github.ppissias.jtransient.model.Track;
import io.github.ppissias.jtransient.config.DetectionConfig;
import io.github.ppissias.jtransient.core.MasterMapGenerator;
import io.github.ppissias.jtransient.core.PixelEncoding;
import io.github.ppissias.jtransient.engine.AutoTuneProfile;
import io.github.ppissias.jtransient.engine.AutoTunerResult;
import io.github.ppissias.jtransient.engine.AutoTuneObserver;
import io.github.ppissias.jtransient.engine.AutoTuneSnapshot;
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
        var slowDiagnostics = new io.github.ppissias.jtransient.core.SlowMoverCandidateDiagnostics(
                object, 0, 1.2, 2.8, .5, 0, .8);
        require(!slowDiagnostics.motionEvidence.fitAvailable && slowDiagnostics.motionEvidence.positions.isEmpty(),
                "Stack-only timed evidence must be explicitly unavailable");
        var correspondence = io.github.ppissias.jtransient.core.SlowMoverTrackMatcher.match(
                object, slowDiagnostics.motionEvidence, track);
        require(correspondence != null && !correspondence.frameComparisonAvailable(),
                "Spatial overlap alone must not imply common-frame evidence");
        new com.google.gson.Gson().toJson(slowDiagnostics.motionEvidence);

        List<ImageFrame> frames = frames();
        AutoTunerResult calibrated = CalibratedAutoTuner.tune(frames.subList(0, 4), config, AutoTuneProfile.MEDIUM, null);
        AutoTuneObserver hidden = new AutoTuneObserver() {
            /** A hidden consumer declines all display-only work. */
            @Override public boolean wantsUpdate() { return false; }
            /** No snapshot should be constructed for this observer. */
            @Override public void update(AutoTuneSnapshot snapshot) { throw new AssertionError("Unexpected preview"); }
        };
        require(!CalibratedAutoTuner.tune(frames.subList(0, 4), config, AutoTuneProfile.MEDIUM, null, hidden).success,
                "Optional observer must preserve insufficient-evidence results");
        require(!calibrated.success && calibrated.optimizedConfig != config, "Calibrated tuning must detach fallback settings");
        require(new com.google.gson.Gson().toJson(calibrated).contains("optimizedConfig"), "Result JSON missing settings");
        require(PixelEncoding.toShiftedPositiveInt(MasterMapGenerator.createMedianMasterStack(frames)[5][5]) == 1000,
                "Median pixel encoding changed");

        require(config.enableFaintStreakSearch && !object.fromFaintStreakSearch, "Faint-streak search defaults changed");
        try (JTransientEngine engine = new JTransientEngine()) {
            PipelineResult result = engine.runPipeline(frames, config, null);
            require(new io.github.ppissias.jtransient.telemetry.FaintMoverSearchTelemetry().searchedFrameIndices.isEmpty(),
                    "Faint-mover original frame identities missing from the API");
            for (var candidate : result.faintMoverCandidates) checkReviewEvidence(candidate);
            require(result.tracks != null && result.telemetry != null && result.masterStackData != null,
                    "Result fields missing");
            require(result.telemetry.faintStreakSearchTelemetry != null, "Faint-streak search telemetry missing");
            require(!result.tooFewFrames && engine.runPipeline(frames.subList(0, JTransientEngine.MIN_KEPT_FRAMES - 1),
                    config, null).tooFewFrames, "Too-few-frames result missing");
            require(engine.generateMasterStack(new ArrayList<>(frames), config, null) != null, "Sequential reuse failed");
        }
        System.out.println("Current public API consumer passed on Java 21.");
    }

    /** Compiles the additive review contract and checks owned cutout geometry for enabled-search consumers. */
    private static void checkReviewEvidence(io.github.ppissias.jtransient.core.FaintMoverCandidate candidate) {
        var review = candidate.diagnostics;
        require(review != null, "Review availability must be explicit");
        if (review.unavailableReason == null) {
            require(review.width <= 256 && review.height <= 256 && review.frames.size() <= 128, "Unbounded review pixels");
            for (var frame : review.frames) {
                require(frame.residual.length == review.width * review.height && Float.isFinite(frame.signal), "Incomplete review frame");
            }
        } else require(review.frames.isEmpty(), "Unavailable evidence retains frames");
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
