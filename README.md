# JTransient

JTransient is a Java library for transient extraction, moving-object track linking and optional variable-star detection on aligned monochrome astronomical image sequences. It works on `short[][]` pixel matrices and can be used in three ways:

- run the full pipeline with `JTransientEngine`
- stop after transient extraction with `JTransientEngine.detectTransients(...)`
- use `SourceExtractor.extractSources(...)` directly on a single frame

It is the core detection engine powering [SpacePixels](https://github.com/ppissias/SpacePixels).

## What The Library Exposes

- `CalibratedAutoTuner.tune(...)`: derives a `DetectionConfig` for a session by measuring false positives and sensitivity on its own frames, within the false-positive budget of a profile (conservative, balanced, aggressive, maximum)
- `JTransientAutoTuner.tune(...)`: the original, score-based auto-tuner, kept for comparison
- `JTransientEngine.runPipeline(...)`: full extraction, quality filtering, master-stack masking, slow-mover candidate detection, track linking, and optional variable-star detection
- `JTransientEngine.detectTransients(...)`: same early pipeline, but stops after the stationary-star veto and returns per-frame transients
- `JTransientEngine.generateMasterStack(...)`: precomputes a reusable median master stack
- `SourceExtractor.extractSources(...)`: standalone single-frame object extraction

## Documentation

- [PIPELINE.md](PIPELINE.md): what each public entrypoint runs and returns
- [ALGORITHM.md](ALGORITHM.md): internal phases of `JTransientEngine.runPipeline(...)`
- [VariableStarAlgorithm.md](VariableStarAlgorithm.md): stationary-star photometry and variable-star detection
- [CONFIG.md](CONFIG.md): `DetectionConfig` field-by-field reference
- [AUTOTUNER.md](AUTOTUNER.md): how the calibrated and the legacy auto-tuners work
- [PUBLISHING.md](PUBLISHING.md): Maven Central staging and release bundle workflow

## Build

This repository is a Gradle Java library project. Install a local JDK first and make sure `java` is available on `PATH`, or that `JAVA_HOME` points to the JDK installation.

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
- `sequenceIndex` should be zero-based, contiguous, and chronological (`0..frames.size()-1`); the engine sorts the supplied `List<ImageFrame>` in place by this value, and some per-frame exports use it as a frame-list index
- time-based linking activates when timestamps are present; for reliable time-based tracking, populate timestamps consistently and in increasing capture order, or leave them as `-1` and rely on geometric linking
- variable-star detection uses the timestamp and exposure for mid-exposure Julian dates; without timestamps the light curves have no times and the minimum time-span check is skipped
- for variable-star detection, pass the original linear (unstretched) data; 8-bit frames and most stretches are detected and refused, and a sky clipped at zero limits the result

## Basic Usage

The following examples are written as standalone skeletons. Any `load...()` helper
shown in an example is an application-specific placeholder that you should replace.

### 1. Run the full pipeline

This is the main entrypoint. It performs extraction, frame rejection, master-stack generation, optional slow-mover candidate detection, streak linking, time-based linking when timestamps exist, geometric linking when timestamps are unavailable or `config.enableGeometricTrackLinking` is enabled, anomaly rescue, and, when `config.enableVariableStarDetection` is enabled, variable-star detection.

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
- `slowMoverAnalysis`: maximum-stack morphological candidates, exact median mask, geometric diagnostics, and aggregate telemetry; motion is not temporally confirmed
- `slowMoverStackData`, `slowMoverMedianVetoMask`, and `slowMoverCandidates`: compatibility exports; the stack now aliases `maximumStackData`
- `driftPoints`: per-frame border-drift diagnostics
- `variableStarAnalysis`: photometric readiness verdict, per-star light curves and variable-star candidates; empty when variable-star detection is disabled
- `telemetry`: pipeline and tracker counters, including nested `slowMoverTelemetry` and `photometryTelemetry`

Slow-mover diagnostics report `axisRatio = majorExtent / minorExtent`, an optional fill factor, exact median-mask overlap, and two 0–100 frame-based heuristics: `frameSupportPercentage` and `stationaryLikelihoodPercentage`. Their config thresholds default to `0` and `100`, so they are measured without vetoing candidates. `estimatedMotionPixels = majorExtent - minorExtent` is a shape-based estimate; motion is not temporally confirmed.

### 2. Detect variable stars

Variable-star detection is an optional stage of `runPipeline(...)`. It is off by default. When enabled, it measures forced aperture photometry of isolated master stars in every retained frame. It then checks whether the frames respond linearly to light, removes per-frame transparency and gradients with an ensemble solve, and scores each star against stars of similar brightness in the same session. See [VariableStarAlgorithm.md](VariableStarAlgorithm.md) for the details.

```java
import io.github.ppissias.jtransient.config.DetectionConfig;
import io.github.ppissias.jtransient.engine.ImageFrame;
import io.github.ppissias.jtransient.engine.JTransientEngine;
import io.github.ppissias.jtransient.engine.PipelineResult;
import io.github.ppissias.jtransient.photometry.StarLightCurve;
import io.github.ppissias.jtransient.photometry.VariableStarAnalysis;
import java.util.List;

public final class VariableStarExample {
    public static void main(String[] args) throws Exception {
        List<ImageFrame> frames = loadFrames();
        DetectionConfig config = new DetectionConfig();
        config.enableVariableStarDetection = true;
        JTransientEngine engine = new JTransientEngine();

        try {
            PipelineResult result = engine.runPipeline(frames, config, null);
            VariableStarAnalysis photometry = result.variableStarAnalysis;

            System.out.println("Photometry verdict: " + photometry.readiness.verdict);
            photometry.readiness.messages.forEach(System.out::println);

            for (StarLightCurve star : photometry.candidates) {
                System.out.printf(
                        "x=%.1f y=%.1f tier=%s amplitude=%.3f mag scatterZ=%.1f failedGates=%s%n",
                        star.x,
                        star.y,
                        star.tier,
                        star.amplitude,
                        star.scatterZ,
                        star.failedGates
                );
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

The readiness verdict decides whether stars are scored:

- `READY`: no non-linearity was detected; stars are scored
- `LIMITED`: no check failed but one was marginal; stars are scored, and only larger amplitudes can reach high confidence
- `NOT_READY`: the frames look stretched, quantised or non-linear, or too few frames remain; light curves are returned for diagnostics but nothing is scored

`candidates` holds the stars that passed both scores, best tier first: `HIGH_CONFIDENCE` (every gate passed), `POSSIBLE` (one gate failed) and `REJECTED` (two or more failed). `stars` holds every measured star. Each `StarLightCurve` has per-frame `deltaMag` (positive = fainter), `magError` and `PhotometryFlags`, all aligned with `photometry.frames`. Magnitudes are instrumental and differential: there is no catalogue calibration or matching.

### 3. Auto-tune a configuration

`CalibratedAutoTuner` measures, on crops of the session's frames, how many false detections each combination of settings produces and how many injected synthetic stars it recovers. It returns the most sensitive combination within the false-positive budget of the chosen profile.

```java
import io.github.ppissias.jtransient.config.DetectionConfig;
import io.github.ppissias.jtransient.engine.CalibratedAutoTuner;
import io.github.ppissias.jtransient.engine.ImageFrame;
import io.github.ppissias.jtransient.engine.JTransientAutoTuner;
import java.util.List;

public final class AutoTuneExample {
    public static void main(String[] args) {
        List<ImageFrame> frames = loadFrames();
        DetectionConfig baseConfig = new DetectionConfig();

        JTransientAutoTuner.AutoTunerResult tuning = CalibratedAutoTuner.tune(
                frames,
                baseConfig,
                JTransientAutoTuner.AutoTuneProfile.BALANCED,
                (percent, message) -> System.out.printf("%3d%% %s%n", percent, message)
        );

        DetectionConfig config = tuning.optimizedConfig;
        System.out.println("Auto-tune success: " + tuning.success);
        System.out.println(tuning.summary);
        System.out.println(tuning.telemetryReport);
    }

    private static List<ImageFrame> loadFrames() {
        throw new UnsupportedOperationException("Replace with your frame-loading code.");
    }
}
```

Profiles: `CONSERVATIVE` (fewest false detections), `BALANCED`, `AGGRESSIVE` (close to the noise level) and `MAXIMUM` (as sensitive as possible; on large sensors it leaves many candidates to review, so it is meant for small sensors or targeted searches for a faint object).

The legacy tuner has the same signature: `JTransientAutoTuner.tune(frames, baseConfig, profile, listener)`; its three-argument overload defaults to `BALANCED`. It treats `MAXIMUM` like `AGGRESSIVE`. See [AUTOTUNER.md](AUTOTUNER.md) for both.

### 4. Reuse a precomputed master stack

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

`generateMasterStack(...)` is lighter than a full run: it performs the drift check, quality evaluation and session rejection exactly as the pipeline does, then stacks the retained frames, but it does not extract frame objects or link tracks. `generateMasterStackWithDetails(...)` also reports which frames were kept and rejected.

### 5. Export transients without linking tracks

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

### 6. Use `SourceExtractor` directly on a single frame

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

- use `CalibratedAutoTuner.tune(...)` before production runs if the dataset changes often
- use `runPipeline(...)` when you want confirmed tracks and full telemetry
- use `detectTransients(...)` when you want frame-by-frame candidates after stationary-star masking
- use `generateMasterStack(...)` plus the overloads with `providedMasterStack` when repeated runs would otherwise spend too much time stacking
- use `SourceExtractor.extractSources(...)` when you only need single-frame object detection

## Detailed Documentation

- [PIPELINE.md](PIPELINE.md): what each public entrypoint runs and returns
- [ALGORITHM.md](ALGORITHM.md): internal phases of `JTransientEngine.runPipeline(...)`
- [VariableStarAlgorithm.md](VariableStarAlgorithm.md): stationary-star photometry and variable-star detection
- [CONFIG.md](CONFIG.md): `DetectionConfig` field-by-field reference
- [AUTOTUNER.md](AUTOTUNER.md): how the calibrated and the legacy auto-tuners work

## License

BSD License. See [LICENSE](LICENSE).
