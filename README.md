# ☄️ JTransient Engine

**JTransient** is a pure Java library designed for astronomical transient detection and kinematic track linking. It processes sequences of aligned, monochrome astronomical images to automatically identify moving targets such as asteroids, comets, satellites, and Kuiper Belt Objects (KBOs), while aggressively filtering out stationary stars, hot pixels, and atmospheric noise.

It is the core detection engine powering [SpacePixels](https://github.com/ppissias/SpacePixels).

## ✨ Key Features

* **Robust Source Extraction:** Custom mathematical extraction that separates true point sources and streaks from background noise using local background estimation and sigma clipping.
* **Geometric Track Linking:** Utilizes time-agnostic collinear linking to connect transient detections across multiple frames, enforcing strict kinematic rules (angle tolerance, prediction line variance, and rhythm consistency).
* **Rolling Star Map Baseline:** Defeats the "slow-mover trap" by using an all-to-all spatial frame comparison, ensuring that clouds or single-frame artifacts don't ruin the master stationary star map.
* **Smart Auto-Tuning:** Includes a mathematical Auto-Tuner that samples your image sequence to dynamically measure atmospheric seeing (star wobble) and optical elongation, automatically configuring the engine's tracking thresholds to prevent false positives.
* **Deep Telemetry:** Returns highly detailed telemetry for every run, allowing developers to see exactly why potential tracks were rejected (e.g., morphological mismatch, velocity limits, or erratic rhythm).

---

## 📦 Installation

*Coming soon!!! *

```xml
<dependency>
    <groupId>io.github.ppissias</groupId>
    <artifactId>jtransient</artifactId>
    <version>1.0.0</version>
</dependency>
```
*Coming soon!!! *

---

## 🚀 Usage Guide

Using JTransient involves three main steps: packaging your image data, configuring the engine (manually or via the Auto-Tuner), and running the pipeline.

### 1. Preparing Image Frames
JTransient operates on raw 2D pixel arrays (`short[][]`). You must wrap your image data (usually extracted from FITS files) into `ImageFrame` objects.

```java
import io.github.ppissias.jtransient.engine.ImageFrame;
import java.util.ArrayList;
import java.util.List;

List<ImageFrame> sequence = new ArrayList<>();

// Assuming you have a method to extract a 2D short array from a FITS file
for (int i = 0; i < totalFiles; i++) {
    short[][] pixelData = loadFitsData(files[i]);
    // Create the frame: index, identifier string, and the raw data
    sequence.add(new ImageFrame(i, files[i].getName(), pixelData));
}
```

### 2. Configuration & Auto-Tuning
JTransient is highly configurable via the `DetectionConfig` object. Because tracking heavily depends on the telescope's focal length, mount tracking accuracy, and nightly seeing conditions, **using the `JTransientAutoTuner` is highly recommended.**

```java
import io.github.ppissias.jtransient.config.DetectionConfig;
import io.github.ppissias.jtransient.engine.JTransientAutoTuner;

// 1. Create a base configuration with default fallback parameters
DetectionConfig baseConfig = new DetectionConfig();

// 2. (Optional) Adjust Auto-Tuner constraints before running
// e.g., Set to 4.0 for perfectly registered images, or 10.0+ for unguided images
JTransientAutoTuner.SEARCH_RADIUS_PX = 4.0; 

// 3. Run the Auto-Tuner on the sequence
JTransientAutoTuner.AutoTunerResult tuneResult = JTransientAutoTuner.tune(sequence, baseConfig);

DetectionConfig finalConfig;
if (tuneResult.success) {
    System.out.println("Auto-Tune Success! " + tuneResult.telemetryReport);
    finalConfig = tuneResult.optimizedConfig;
} else {
    System.out.println("Auto-Tune Failed. Falling back to base configuration.");
    finalConfig = baseConfig;
}
```

### 3. Running the Engine
Once your configuration is ready, instantiate the engine, run the pipeline, and gracefully shut it down to free up the internal thread pools.

```java
import io.github.ppissias.jtransient.engine.JTransientEngine;
import io.github.ppissias.jtransient.engine.PipelineResult;

// Initialize the engine (spawns worker threads)
JTransientEngine engine = new JTransientEngine();

// Execute the pipeline
PipelineResult result = engine.runPipeline(sequence, finalConfig);

// CRITICAL: Shut down the engine to release threads and prevent memory leaks
engine.shutdown();

System.out.println("Found " + result.tracks.size() + " moving targets!");
```
ad
### 4. Interpreting Results
The `PipelineResult` contains a list of `Track` objects and a rich `PipelineTelemetry` object.

```java
import io.github.ppissias.jtransient.core.TrackLinker.Track;
import io.github.ppissias.jtransient.core.SourceExtractor.DetectedObject;

for (int i = 0; i < result.tracks.size(); i++) {
    Track track = result.tracks.get(i);
    System.out.println("Track " + i + " contains " + track.points.size() + " detections.");
    
    // Iterate through the chronological points of the asteroid's path
    for (DetectedObject point : track.points) {
        System.out.printf("  Frame %d -> X: %.2f, Y: %.2f (Flux: %.1f)%n", 
            point.sourceFrameIndex, 
            point.x, 
            point.y, 
            point.totalFlux);
    }
}

// Print diagnostic telemetry
System.out.println("Stars purged: " + result.telemetry.trackerTelemetry.totalStationaryStarsPurged);
System.out.println("Pipeline execution time: " + result.telemetry.processingTimeMs + " ms");
```

---

## 🧠 Architecture Overview

JTransient executes in four distinct phases:

1. **Source Extraction:** Scans raw pixel arrays to detect significant deviations above the local background using configured sigma multipliers. Extracts morphological data (centroid, flux, elongation).
2. **Fast Streak Linking:** Identifies highly elongated sources (e.g., fast-moving satellites or meteors) and links them based on trajectory angles.
3. **Master Star Map Generation:** Uses an "all-to-all" spatial comparison. If a source is found in the same spatial location across multiple frames (dictated by the `maxStarJitter` radius), it is classified as a stationary star and purged from the pool.
4. **Geometric Track Linking:** The remaining "transient" points are evaluated using kinematic rules. The engine builds trajectory vectors, calculates predicted line tolerances, evaluates photometric consistency, and ensures rhythmic movement to confirm true asteroids.

---

## 📄 License

BSD License
See the LICENSE file included in this distribution for specific terms.