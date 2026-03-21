# ☄️ JTransient Engine

**JTransient** is a pure Java library designed for astronomical transient detection and kinematic track linking. It processes sequences of aligned, monochrome astronomical images to automatically identify moving targets such as asteroids, comets, satellites, and Kuiper Belt Objects (KBOs), while aggressively filtering out stationary stars, hot pixels, and atmospheric noise.

It is the core detection engine powering [SpacePixels](https://github.com/ppissias/SpacePixels).

## ✨ Key Features

* **Robust Source Extraction:** Custom mathematical extraction that separates true point sources and streaks from background noise using local background estimation, hysteresis, and sigma clipping.
* **Time-Based Kinematics:** Uses frame timestamps to calculate exact velocity vectors, allowing the engine to track fast-moving satellites flawlessly, even across dropped frames or variable camera delays.
* **Geometric Fallback Linking:** Time-agnostic collinear linking for slow asteroids using strict kinematic rules (angle tolerance, prediction line variance, and rhythm consistency).
* **Deep Master Star Map Veto:** Generates a highly accurate pixel-perfect Boolean Veto Mask from a dynamically generated median stack to ruthlessly purge background stars.
* **Slow Mover Detection:** Features a specialized percentile-stacking engine designed to detect ultra-slow trailing objects that barely move over the course of a session.
* **Smart Auto-Tuning:** Includes a mathematical Auto-Tuner that samples your image sequence to dynamically measure atmospheric seeing (star wobble) and optical elongation, automatically configuring the engine's tracking thresholds to prevent false positives.
* **Deep Telemetry:** Returns highly detailed telemetry for every run, allowing developers to see exactly why potential tracks were rejected (e.g., morphological mismatch, velocity limits, or erratic rhythm).
* **Modular Pipeline API:** Run the entire pipeline, manually pre-compute background stacks for UI performance, or just extract raw frame-by-frame transients for custom applications.

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

## 🚀 API Documentation & Usage

JTransient is designed to be highly modular. You can run the entire automated tracking pipeline, or directly invoke the mathematical source extractor on single images if you are building custom linking algorithms.

### Use Case 1: Full Detection Pipeline (`runPipeline`)
This is the primary entry point. It automatically handles frame quality evaluation, median deep stack generation, streak matching, stationary star Veto Masking, time-based kinematics, and slow-mover detection.

**Method Signature:**
```java
public PipelineResult runPipeline(
        List<ImageFrame> inputFrames, 
        DetectionConfig config, 
        TransientEngineProgressListener listener
) throws Exception
```

**Inputs:**
*   `inputFrames`: A chronological list of `ImageFrame` objects containing your raw `short[][]` pixel data and timestamps.
*   `config`: The `DetectionConfig` object defining thresholds, bounds, and kinematics. *(Tip: Use `JTransientAutoTuner.tune()` to generate this automatically).*
*   `listener`: (Optional) A callback interface to stream progress updates to your UI.

**Execution Example:**
```java
// Initialize the engine (spawns worker threads)
JTransientEngine engine = new JTransientEngine();

// Run the pipeline with an inline UI listener
PipelineResult result = engine.runPipeline(sequence, finalConfig, (progress, message) -> {
    System.out.println(progress + "% : " + message);
});

// CRITICAL: Shut down the engine to release thread pools when your application closes
engine.shutdown();
```

**Return Data (`PipelineResult`):**
The return payload contains highly detailed structures ideal for rendering a diagnostic UI.
*   **`tracks`**: Confirmed `TrackLinker.Track` objects. Tracks are mathematically tagged as `isTimeBasedTrack` (constant speed track based on time), `isStreakTrack` (Streak tracks), `isAnomaly` (flashes), or standard geometric moving objects.
*   **`masterStackData`**: The generated deep median `short[][]` pixel array (perfect as a clean UI background).
*   **`masterStars`**: A list of all stationary `DetectedObject`s extracted to build the mask.
*   **`slowMoverStackData`**: The specialized percentile `short[][]` pixel array used for identifying ultra-slow objects.
*   **`slowMoverCandidates`**: Highly-elongated `DetectedObject`s identified as slow-mover trails.
*   **`allTransients`**: A chronological `List<List<DetectedObject>>` containing all raw points and streaks that successfully survived the Veto Mask frame-by-frame.
*   **`telemetry`**: Exhaustive `PipelineTelemetry` detailing execution times, quality rejection reasons, and specific tracking filter drop-offs.

---

### Use Case 2: Standalone Source Extraction (`extractSources`)
If you already have your own track-linking logic but want to leverage JTransient's highly tuned Breadth-First Search (BFS) region growing, local background estimation, and Image Moments shape analysis, you can call the extractor directly on a single image.

**Method Signature:**
```java
public static List<DetectedObject> extractSources(
        short[][] image, 
        double sigmaMultiplier, 
        int minPixels, 
        DetectionConfig config
)
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