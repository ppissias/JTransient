# Developer guide

How JTransient is built, how its code is organised, the principles it follows, and how to change it safely. This guide is for developers and coding agents. It describes the code as it is; planned design work is in [DESIGN-IMPROVEMENTS.md](DESIGN-IMPROVEMENTS.md) and algorithm proposals in [FEATURE-REQUESTS.md](FEATURE-REQUESTS.md).

| Read | For |
|---|---|
| [README.md](README.md) | What the library does, with usage examples |
| [ALGORITHM.md](ALGORITHM.md) | The detection, slow-mover, photometry and Auto-Tune algorithms: the single algorithm reference |
| [PIPELINE.md](PIPELINE.md) | Entry points, result contracts, progress and the stage observer |
| [CONFIG.md](CONFIG.md) | `DetectionConfig` fields and defaults |
| [DESIGN-IMPROVEMENTS.md](DESIGN-IMPROVEMENTS.md) | Pending design and refactoring work, and the latest code review |
| [FEATURE-REQUESTS.md](FEATURE-REQUESTS.md) | Algorithm proposals and the binding declined decisions |
| [PUBLISHING.md](PUBLISHING.md), [RELEASE_NOTES.md](RELEASE_NOTES.md) | Releases |

Machine-specific paths (tools, test datasets, scratch folders, memory limits) are in a git-ignored `LOCAL-ENVIRONMENT.txt` at the repository root (see [AGENTS.md](AGENTS.md)).

## Principles

The maintainer's goals for the code, in order:

1. **Readable code.** A reader should understand a class from its name, its Javadoc and its fields. Prefer plain, direct code over clever generality.
2. **A clear design with clear boundaries.** Each piece of state has one owner. Each package has one job, and its dependencies point one way.
3. **A few chosen design patterns, applied consistently.** Reuse them before introducing a new one.
4. **No abstraction or engineering without a real need.** An interface needs two implementations or a test seam that is actually used. A layer needs a responsibility of its own. A compatibility path needs a caller that still uses it. When in doubt, delete rather than wrap.

The rules that follow for the engine:

- **The engine owns detection science; the application owns everything else.** JTransient owns extraction, frame quality, the master reference and masks, streaks and tracks, camera-fixed and common-motion filtering, anomaly rescue, slow movers, residual analysis, variable-star photometry and Auto-Tune. It reads no files, shows no UI and knows no sessions; SpacePixels prepares the frames and presents the results.
- **Explicit inputs and outputs.** A stage receives what it needs and returns a result; it does not read or write shared mutable state. The caller's configuration is captured once at entry; stage-specific settings are clones that never leak back.
- **Pipeline stages as classes.** A pipeline stage is a class with a static entry and explicit inputs and outputs (for example `TransientVeto.apply`, `StreakLinker.link`), orchestrated in a fixed order by one class (`TrackLinker`, `JTransientEngine`).
- **The library prints nothing by default.** Diagnostics go through the caller's diagnostic scope, which SpacePixels enables with its debug option.
- **Cancellation reaches the loops.** Long loops call `CancellationScope.check()`; worker batches join on failure or cancellation.
- **Science changes need evidence.** Change one algorithm at a time, with recovery, false positives and run time measured on real data. A refactoring must leave the results byte-identical.
- **One coordinated source set with SpacePixels and JPlateSolve.** There is no promise to keep old signatures. Change the API and its SpacePixels callers together, and prefer deleting an old path to keeping an alias.

## Working rules

The shared working rules are in SpacePixels' [DEVELOPER.md](../SpacePixels/DEVELOPER.md#working-rules) and apply here too:
- never overwrite the user's saved SpacePixels settings;
- restore Java preferences after any GUI harness;
- discuss a fix before implementing it;
- remove the observing site from published images;
- use descriptive messages for code commits and a generic message such as "Update docs" for documentation commits;
- don't rewrite history;
- refer to people as they/them.

Engine-specific additions:

- **Engine fixes go one at a time.** Each is checked with this repository's tests, the SpacePixels tests and the real-data comparison.
- **Document every new Java type,** including nested types and tests, at type level. Give each new method and explicit constructor a concise Javadoc, private helpers included, covering ownership and mutation, units and contracts. Keep it current when behaviour changes.
- **Algorithm descriptions live in ALGORITHM.md,** in the section of the relevant branch. Do not start a separate algorithm guide.

## Build and test

Use Java 21 and the Gradle wrapper.

```powershell
.\gradlew.bat compileJava
.\gradlew.bat test          # unit tests
.\gradlew.bat check         # tests plus the bytecode architecture check (verifyArchitecture)
.\gradlew.bat apiConsumer   # compiles and runs a consumer of the current public API
.\gradlew.bat jar           # build/libs/jtransient-<version>.jar, for diagnostic classpaths
```

SpacePixels builds this checkout directly when its `useLocalJTransientBuild=true` is set; no publishing step is needed during development. After an engine change, also run the SpacePixels tests. `mavenCentralBundle` runs `check` and `apiConsumer` (see [PUBLISHING.md](PUBLISHING.md)).

## The packages

All packages are under `io.github.ppissias.jtransient`.

| Package | What it holds |
|---|---|
| `engine` | The entry points (`JTransientEngine`, `CalibratedAutoTuner`), `PipelineResult`, `ImageFrame`, the stage observer, progress listener and input rules |
| `core` | Extraction (`SourceExtractor`, `SkyBackground`), frame coverage and data area, the master reference and veto mask, the tracking stages (`TrackLinker` and its stage classes), slow movers, camera-fixed and common-motion rejection, residual analysis |
| `photometry` | Variable-star photometry: star selection, aperture measurement, ensemble solution, readiness checks, scoring |
| `quality` | Per-frame quality metrics and session-level frame rejection |
| `model` | The shared detection types: `DetectedObject`, `Pixel`, `Track`, `AnomalyDetection`, `AnomalyType` |
| `config` | `DetectionConfig`, `DetectionConfigRules`, `PhotometryGeometry` |
| `execution` | `CancellationScope`, `WorkerPool`, diagnostic and failure context |
| `telemetry` | `PipelineTelemetry`, `TrackerTelemetry` |

## Changing DetectionConfig

- SpacePixels reads profiles with Gson: unknown keys are ignored, and a different key set triggers a one-time migration offer with a backup.
- Adding, removing or renaming a field requires updating `CONFIG.md`, SpacePixels' `src/dist/config/default_detection_profile.json`, and its settings panel if the field has a row.
- Many fields are deliberately hidden in the GUI but remain active in the engine. Do not restore hidden rows without asking the maintainer.
- Preserve the caller's configuration. Stage-specific extraction settings belong in clones and must not leak into the supplied profile.

## Engine invariants

- `InputRules` validates engine and tuner sequences in memory; `TrackLinker` checks uniform timestamp availability. A missing time is -1; 0 is a real timestamp. File-format and sample-encoding checks belong to the application's reader.
- `FrameCoverage` records fill boundaries. `DataArea` intersects coverages and applies a 3-pixel square margin. The quality area excludes registration and coverage outliers; the final data area uses the frames kept after quality control. Point-source footprints and streak centroids are filtered by the relevant search area. Do not reintroduce edge or void settings.
- Master-reference stars cover the whole image, because a star cut by the area's edge must still be masked. `MasterReferenceAnalyzer` uses a global-sky pass plus a local-sky pass seeded at 5 sigma or more. Photometry has its own star list; keep their purposes distinct.
- `CameraFixedRejector` uses usable fill-edge registrations to remove camera-fixed point detections after the veto and before linking. It does not replace common-motion filtering or validate slow-mover candidates.
- Same-frame groups of rescued anomalies are private consolidation inputs. An unmerged group stays separate anomalies; there is no suspected-streak result category.
- Session rejection uses medians and MAD with the configured absolute envelope floors. The background limit is at least the median frame noise; equalised sky levels alone must not produce an arbitrarily tight rejection window.

## Real-data checks

The reference datasets and the comparison method are described in SpacePixels' [DEVELOPER.md](../SpacePixels/DEVELOPER.md#real-data-regression); their location is in `LOCAL-ENVIRONMENT.txt`.
- **Structural change:** compare full `PipelineResult` snapshots before and after, with stacks and masks hashed and timings excluded. They must be byte-identical.
- **Algorithm change:** compare the rejected frames, tracks, anomalies and variable-star candidates star by star, and the run times, and report the differences to the maintainer before committing.

Run large datasets one at a time.
