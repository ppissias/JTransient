# JTransient

JTransient is a Java library for transient extraction and moving-object track linking on aligned monochrome astronomical image sequences. It works on `short[][]` pixel matrices and can be used in three ways:

- run the full pipeline with `JTransientEngine`
- stop after transient extraction with `JTransientEngine.detectTransients(...)`
- use `SourceExtractor.extractSources(...)` directly on a single frame

It is the core detection engine powering [SpacePixels](https://github.com/ppissias/SpacePixels).

## What The Library Exposes

- `JTransientAutoTuner.tune(...)`: derives a cleaner `DetectionConfig` from a representative subset of frames
- `JTransientEngine.runPipeline(...)`: full extraction, quality filtering, master-stack masking, slow-mover detection, and track linking
- `JTransientEngine.detectTransients(...)`: same early pipeline, but stops after the stationary-star veto and returns per-frame transients
- `JTransientEngine.generateMasterStack(...)`: precomputes a reusable median master stack
- `SourceExtractor.extractSources(...)`: standalone single-frame object extraction

## Documentation

- [PIPELINE.md](PIPELINE.md): what each public entrypoint runs and returns
- [ALGORITHM.md](ALGORITHM.md): internal phases of `JTransientEngine.runPipeline(...)`
- [CONFIG.md](CONFIG.md): `DetectionConfig` field-by-field reference
- [AUTOTUNER.md](AUTOTUNER.md): detailed walkthrough of `JTransientAutoTuner.tune(...)`
- [PUBLISHING.md](PUBLISHING.md): Maven Central staging and release bundle workflow

## Build

This repository is a Gradle Java library project:

```powershell
.\gradlew.bat build
```

The project name is `JTransient` and the current library version in `build.gradle` is `1.0.0`.

To prepare a Maven Central release bundle locally:

```powershell
.\gradlew.bat mavenCentralBundle
```

See [PUBLISHING.md](PUBLISHING.md) for the required signing and Portal setup.

## Data Model

All engine entrypoints operate on `ImageFrame` objects:

```java
ImageFrame frame = new ImageFrame(
        sequenceIndex,
        "frame_001.fit",
        pixelData,          // short[][]
        timestampMillis,    // use -1 if unavailable
        exposureMillis      // use -1 if unavailable
);
```

Notes:

- frames must all have the same dimensions
- the data should already be aligned/registered to the same pixel grid
- the engine sorts the supplied `List<ImageFrame>` in place by `sequenceIndex` before processing
- time-based linking only activates when timestamps are present

## Basic Usage

The following examples are written as standalone skeletons. Any `load...()` helper
shown in an example is an application-specific placeholder that you should replace.

### 1. Auto-tune a configuration

`JTransientAutoTuner` clones the base config, evaluates a representative frame sample, and returns an `AutoTunerResult`.

```java
import io.github.ppissias.jtransient.config.DetectionConfig;
import io.github.ppissias.jtransient.engine.ImageFrame;
import io.github.ppissias.jtransient.engine.JTransientAutoTuner;
import java.util.List;

public final class AutoTuneExample {
    public static void main(String[] args) {
        List<ImageFrame> frames = loadFrames();
        DetectionConfig baseConfig = new DetectionConfig();

        JTransientAutoTuner.AutoTunerResult tuning = JTransientAutoTuner.tune(
                frames,
                baseConfig,
                JTransientAutoTuner.AutoTuneProfile.BALANCED,
                (percent, message) -> System.out.printf("%3d%% %s%n", percent, message)
        );

        DetectionConfig config = tuning.optimizedConfig;
        System.out.println("Auto-tune success: " + tuning.success);
        System.out.println(tuning.telemetryReport);
        if (tuning.finalValidationTelemetry != null) {
            System.out.println(tuning.finalValidationTelemetry.statusMessage);
        }
    }

    private static List<ImageFrame> loadFrames() {
        throw new UnsupportedOperationException("Replace with your frame-loading code.");
    }
}
```

If you do not need to pick a profile explicitly, use the three-argument overload. It defaults to `BALANCED`.

### 2. Run the full pipeline

This is the main entrypoint. It performs extraction, frame rejection, master-stack generation, optional slow-mover detection, streak linking, time-based linking when timestamps exist, geometric fallback linking, and anomaly rescue.

```java
import io.github.ppissias.jtransient.config.DetectionConfig;
import io.github.ppissias.jtransient.engine.ImageFrame;
import io.github.ppissias.jtransient.engine.JTransientEngine;
import io.github.ppissias.jtransient.engine.PipelineResult;
import java.util.List;

public final class RunPipelineExample {
    public static void main(String[] args) throws Exception {
        List<ImageFrame> frames = loadFrames();
        DetectionConfig config = new DetectionConfig();
        JTransientEngine engine = new JTransientEngine();

        try {
            PipelineResult result = engine.runPipeline(
                    frames,
                    config,
                    (percent, message) -> System.out.printf("%3d%% %s%n", percent, message)
            );

            System.out.println("Tracks found: " + result.tracks.size());
            System.out.println("Anomalies rescued: " + result.anomalies.size());
            System.out.println("Slow mover candidates: " + result.slowMoverAnalysis.candidates.size());
            System.out.println(result.telemetry.generateReport());

            result.tracks.forEach(track -> {
                System.out.println(
                        "Track points=" + track.points.size()
                                + " streak=" + track.isStreakTrack
                                + " suspectedStreak=" + track.isSuspectedStreakTrack
                                + " timeBased=" + track.isTimeBasedTrack
                );
            });
        } finally {
            engine.shutdown();
        }
    }

    private static List<ImageFrame> loadFrames() {
        throw new UnsupportedOperationException("Replace with your frame-loading code.");
    }
}
```

Key `PipelineResult` fields:

- `tracks`: returned `TrackLinker.Track` objects, including confirmed tracks and suspected same-frame streak groupings
- `anomalies`: rescued single-frame anomalies kept separate from normal tracks
- `allTransients`: per-frame export of the full post-veto transient population carried through tracking, including point detections and mobile streak detections
- `unclassifiedTransients`: the true leftover detections that remain after tracks and anomalies are exported
- `residualTransientAnalysis`: post-processing of `unclassifiedTransients` into weak local rescue candidates and broad activity clusters
- `masterStackData`: median master stack used to extract stationary stars
- `maximumStackData`: maximum stack exported for visualization/post-processing
- `masterStars`: stationary objects extracted from the master stack
- `masterVetoMask`: boolean veto mask used to purge stationary stars
- `slowMoverAnalysis`: grouped slow-mover result with per-candidate diagnostics and aggregate stage telemetry
- `slowMoverStackData`, `slowMoverMedianVetoMask`, and `slowMoverCandidates`: legacy slow-mover exports kept temporarily for compatibility
- `driftPoints`: per-frame border-drift diagnostics
- `telemetry`: pipeline and tracker counters, including nested `slowMoverTelemetry`

### 3. Reuse a precomputed master stack

If you are iterating on parameters or running UI workflows, you can precompute the median master stack once and pass it into the overloads that accept `providedMasterStack`.

```java
import io.github.ppissias.jtransient.config.DetectionConfig;
import io.github.ppissias.jtransient.engine.FrameTransients;
import io.github.ppissias.jtransient.engine.ImageFrame;
import io.github.ppissias.jtransient.engine.JTransientEngine;
import io.github.ppissias.jtransient.engine.PipelineResult;
import java.util.List;

public final class ReuseMasterStackExample {
    public static void main(String[] args) throws Exception {
        List<ImageFrame> frames = loadFrames();
        DetectionConfig config = new DetectionConfig();
        JTransientEngine engine = new JTransientEngine();

        try {
            short[][] masterStack = engine.generateMasterStack(frames, config, null);

            PipelineResult pipeline = engine.runPipeline(frames, config, null, masterStack);
            System.out.println("Tracks found: " + pipeline.tracks.size());

            List<FrameTransients> transients =
                    engine.detectTransients(frames, config, null, masterStack);
            System.out.println("Frames with exported transients: " + transients.size());
        } finally {
            engine.shutdown();
        }
    }

    private static List<ImageFrame> loadFrames() {
        throw new UnsupportedOperationException("Replace with your frame-loading code.");
    }
}
```

`generateMasterStack(...)` is lighter than a full run: it performs quality evaluation and session rejection, then stacks the retained frames, but it does not extract frame objects or link tracks.

### 4. Export transients without linking tracks

`detectTransients(...)` runs the same early stages as the full engine and returns the per-frame export produced after stationary-star filtering, with preserved streak detections included.

```java
import io.github.ppissias.jtransient.engine.FrameTransients;
import io.github.ppissias.jtransient.engine.ImageFrame;
import io.github.ppissias.jtransient.engine.JTransientEngine;
import io.github.ppissias.jtransient.config.DetectionConfig;
import java.util.List;

public final class DetectTransientsExample {
    public static void main(String[] args) throws Exception {
        List<ImageFrame> frames = loadFrames();
        DetectionConfig config = new DetectionConfig();
        JTransientEngine engine = new JTransientEngine();

        try {
            List<FrameTransients> frameTransients =
                    engine.detectTransients(frames, config, null);

            for (FrameTransients frame : frameTransients) {
                System.out.println(frame.filename + " -> " + frame.transients.size() + " transients");
                System.out.println("Seed threshold: " + frame.extractionResult.seedThreshold);
                System.out.println("Grow threshold: " + frame.extractionResult.growThreshold);
            }
        } finally {
            engine.shutdown();
        }
    }

    private static List<ImageFrame> loadFrames() {
        throw new UnsupportedOperationException("Replace with your frame-loading code.");
    }
}
```

This entrypoint is useful when you want JTransient's extraction and stationary-star filtering, but you plan to do your own higher-level linking.

### 5. Use `SourceExtractor` directly on a single frame

If you only want object detection on one image, call `SourceExtractor.extractSources(...)` directly.

```java
import io.github.ppissias.jtransient.config.DetectionConfig;
import io.github.ppissias.jtransient.core.SourceExtractor;

public final class ExtractSingleFrameExample {
    public static void main(String[] args) {
        short[][] image = loadImage();
        DetectionConfig config = new DetectionConfig();

        SourceExtractor.ExtractionResult extraction = SourceExtractor.extractSources(
                image,
                config.detectionSigmaMultiplier,
                config.minDetectionPixels,
                config
        );

        System.out.println("Objects: " + extraction.objects.size());
        System.out.println("Background median: " + extraction.backgroundMetrics.median);
        System.out.println("Background sigma: " + extraction.backgroundMetrics.sigma);

        for (SourceExtractor.DetectedObject object : extraction.objects) {
            System.out.printf(
                    "x=%.2f y=%.2f area=%.0f elongation=%.2f streak=%s%n",
                    object.x,
                    object.y,
                    object.pixelArea,
                    object.elongation,
                    object.isStreak
            );
        }
    }

    private static short[][] loadImage() {
        throw new UnsupportedOperationException("Replace with your single-frame loading code.");
    }
}
```

The extractor returns:

- `objects`: detected blobs that survived the size and artifact filters
- `backgroundMetrics`: sigma-clipped background median and sigma
- `seedThreshold`: threshold used to start a blob
- `growThreshold`: hysteresis threshold used to expand the blob

## Choosing The Right Entry Point

- use `JTransientAutoTuner.tune(...)` before production runs if the dataset changes often
- use `runPipeline(...)` when you want confirmed tracks and full telemetry
- use `detectTransients(...)` when you want frame-by-frame candidates after stationary-star masking
- use `generateMasterStack(...)` plus the overloads with `providedMasterStack` when repeated runs would otherwise spend too much time stacking
- use `SourceExtractor.extractSources(...)` when you only need single-frame object detection

## Detailed Documentation

- [PIPELINE.md](PIPELINE.md): what each public entrypoint runs and returns
- [ALGORITHM.md](ALGORITHM.md): internal phases of `JTransientEngine.runPipeline(...)`
- [CONFIG.md](CONFIG.md): `DetectionConfig` field-by-field reference
- [AUTOTUNER.md](AUTOTUNER.md): detailed walkthrough of `JTransientAutoTuner.tune(...)`

## License

BSD License. See [LICENSE](LICENSE).
