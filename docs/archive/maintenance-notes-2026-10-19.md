> Historical record, archived 2026-10-19. This preserves the review and evidence from its original dates, including superseded statements and decisions. It is not the current specification or work queue. See [AGENTS.md](../../AGENTS.md) for current guidance. Source line numbers and temporary harness paths below refer to the original session and may no longer exist.

# Notes for coding agents

Working notes for AI coding agents (Claude Code and others) on JTransient. Read this first. The SpacePixels repository (the desktop app that uses this library) has a matching `AGENTS.md`, including the rules from the maintainer and the real-data regression method.

## Project layout

- JTransient is the detection engine: per-frame extraction, frame quality and session rejection, the master stack and star mask, streaks, track linking, anomaly rescue, slow movers, residual analysis, and optional variable-star photometry.
- SpacePixels builds against the published JTransient by default; with `useLocalJTransientBuild=true` (the maintainer sets it in the gitignored SpacePixels gradle.properties) it uses this checkout through a composite build, and an engine change is live as soon as it compiles.
- Docs:
  - `ALGORITHM.md`: the pipeline phases.
  - `PIPELINE.md`: the public entry points.
  - `CONFIG.md`: every `DetectionConfig` field.
  - `AUTOTUNER.md`: the two auto-tuners.
  - `SlowMoverAlgorithm.md` and `VariableStarAlgorithm.md`.
  - `code-review.md`: the full engine review (2026-10-17, includes the former TrackLinker assessment), with a comment block per issue for the maintainer's implementation decisions.
  - `VariableStar-ASSESSMENT.md`: variable-star review findings and their status.

## Build and test

- `./gradlew compileJava`, `./gradlew test`, `./gradlew jar` (diagnostic classpaths use `build/libs/jtransient-<version>.jar`).
- After an engine change, also run the SpacePixels tests and the real-data regression described in the SpacePixels `AGENTS.md`.

## Working rules (summary; the full list is in SpacePixels)

- Discuss engine fixes before implementing them, one at a time, and show the real-data impact.
- Code commit messages are descriptive. Documentation commits are generic ("Update docs"). No private details or locations in commits. Don't rewrite history.
- Refer to the maintainer and other people as they/them.
- The library prints nothing by default: every console print sits behind `JTransientEngine.DEBUG` or `JTransientAutoTuner.DEBUG` (both false since 2026-10-17). SpacePixels sets them from `-Dspacepixels.debug=true`.

## Changing DetectionConfig

- SpacePixels saves `DetectionConfig` with Gson as the user's detection profile. Unknown keys are ignored on load. When the key set differs from the current class, SpacePixels offers a one-time profile migration that keeps a backup.
- When adding, removing or renaming a field, update all of these:
  - `CONFIG.md`.
  - SpacePixels `src/dist/config/default_detection_profile.json`.
  - The SpacePixels settings panel (`DetectionConfigurationPanel`), if the field has a row.
- Many fields are deliberately hidden in the SpacePixels panel but still read here. "Hidden" does not mean unused.

## Engine facts worth knowing

- **Data areas (since 2026-10-18, code review XC-4):** there are no edge or void settings any more (`edgeMarginPixels`, `voidThresholdFraction`, `voidProximityRadius` and the drift analyzer's recommended radius are gone).
  - `FrameDriftAnalyzer` records each usable frame's `FrameCoverage`: first and last data pixel of every row and column; data start at the first 3 pixels above the fill level (8 ADU, stored `Short.MIN_VALUE + 8`).
  - `DataArea`: intersection of coverages, eroded by a 3 px square margin, stored as row runs (`of`, `fullFrame`, `wholeImage` without margin, `ofFrame(short[][])`, `limitingFrames`, `outline`, `contains`, `containsDisc`).
  - Quality area = intersection over registered frames minus coverage outliers (a frame that alone costs 5 % or more is rejected, "Shifted away from the other frames", at most a tenth). The sky and the QC metrics are measured there.
  - Data area = intersection over the kept frames (`PipelineResult.dataArea`). Objects of kept frames are filtered by it after QC (every pixel of a point source, the centroid of a streak). Slow movers measure the sky and keep candidates inside it; photometry keeps stars whose outer radius + 2 px lies inside it. Master stars use `wholeImage` (the star mask must cover stars cut by the area edge; an edge rule there flooded the first step-3 run with edge anomalies).
  - Tuners: crops cut with a 3 px margin, the calibrated tuner drops crops outside the pool area, rates per searchable pixel, injection margin 30.
  - The engine never writes to the caller's config.
- **Same-frame anomaly groups (AN-1 option 2):** rescued anomalies of one frame on a line (3 or more within 6 px) are offered to `consolidateStreakTracks` (line tolerance max(6, 2 × `predictionTolerance`)). A group not merged into a confirmed streak stays as separate anomalies (private `Track.anomalyGroup`). Suspected streaks no longer exist.
- **Input rules (IN-2):** `engine/InputRules` at every entry point and in both tuners; `TrackLinker.checkUniformTimestamps`. Timestamp -1 = missing, 0 is a real time.
- **Session rejection (`SessionEvaluator`):**
  - Frames are compared with session medians using MAD sigmas for star count, FWHM, eccentricity, bright-star eccentricity and the sky level.
  - The MAD envelopes have absolute floors (`min*Envelope`, `minBackgroundDeviationADU`).
  - Many datasets come from DeepSkyStacker, which equalises the sky across frames, so the background MAD is about 0 and the floor alone sets the threshold.

## Open work and recent changes (as of 2026-10-18)

- **Code review implementation (2026-10-18):** every decision in `code-review.md` is done; its "Implementation status" table lists the outcome and commits per issue. Engine commits 3468ac1 … 1a9fa69, SpacePixels fd98a9a … e752869, all pushed on 2026-10-18 after the maintainer saw the results (another session's 0553e58 went out with them; that session also had uncommitted work in MANUAL.md, TODO.md, AutoTuneOverviewPanel, DetectionConfigurationPanel, ImageProcessing and two tests: never stage those).
- Real-data results per step (19 datasets, each against the previous step):
  - Step 2 (quality area): sky noise ×0.72 Eagle-noisy, ×0.95 klangwolke-1, others 0.96–1.00; frames recovered (Eagle-noisy 43, dc-light-2 59).
  - AN-1: standalone same-frame groups became anomalies (Eagle-noisy +187); klangwolke-2 trails intact.
  - Step 3 (detection in the data area): new findings in the old 20 px border band; Africano frames 2 and 14 now rejected, comet track 13 → 11 frames (maintainer: keep as is).
  - Step 4a (photometry): stars −10 % Eagle-noisy, −7 % dc-light-2, −5 % M81-kumar; M81-kumar candidates 30 → 28.
  - Step 4b (tuners): calibrated tune shifts mostly master settings; whynot tracks 10 → 3.
  - Step 5, ST-2, ST-3: no change. RS-2: Africano gains LR1 (1318, 488).
  - AT-5 (legacy tuner, star mask grown by max(1, round(jitter / 2)) instead of ceil(jitter)): only whynot changes (jitter 1.32: radius 2 → 1; min pixels 12 → 9, mask overlap 0.65 → 0.70) and gets two tracks of single hot pixels walking with the drift, plus one local rescue. The maintainer kept AT-5.
- Regression kit in the SpacePixels session scratchpad ffcaa988…/reg: `reg.sh <label> base|change [datasets]` (19 datasets; base = worktrees in ../base, JTransient at 48d7a9b and SpacePixels at e752869; build the base SpacePixels with -PuseLocalJTransientBuild=true and processResources), `tune.sh` (auto-tune; `TUNER=legacy` for the legacy tuner), `tunekeep.sh` (keeps the report images), `cmp.mjs`, `band.mjs`, `frames.mjs`, `noise.mjs`. Edit helpers `jedit.mjs` (spec files of [old, new] pairs; files mix LF and CRLF, check `git ls-files --eol` before committing) and `delmethod.mjs`.
- Open from the review: RS-1 left as it is for now; the deferred items are listed in `code-review.md`.
- **Two-pass master stars (2026-10-18, step 1 of the M81-kumar fix; committed ffa60aa + docs fd23737, pushed):** `core/SkyBackground` (512 px boxes, 3-sigma clipped medians, 3 x 3 median filter with linear continuation at the grid edges, bilinear with linear extrapolation, boxes with more than 10 % fill filled from neighbours by linear continuation) and `MasterReferenceAnalyzer.extractStars`: pass 1 as before (one sky level), pass 2 on the flattened copy seeded at max(master sigma, 5) and grown as pass 1, added where it shares no pixel with pass 1. Used by the pipeline, `analyzeFromMasterStack` (SpacePixels Star Mask Explorer) and both tuners; photometry keeps its own one-pass star list. Why both passes: the sky map alone absorbed the klangwolke-1 nebula (frame-1 leftovers 94 → 2,051, 4,022 tracks); why the 5-sigma seed: the master sigma on the real noise added noise objects (Apophis 234 → 799 stars, one on the Apophis path in frames 10-11). Results: regression profile identical on 18 of 19 datasets (M81-kumar one anomaly fewer), photometry identical; M81-kumar with the user's HIGH tune 274 LR / 52 CM → 84 / 5; calibrated tunes unchanged except helix (master sigma 2.5 → 5). The 84 left are mostly hot pixels moving with the dither between frame triplets (no signal in the stack), not caught by common motion (shift tolerance 1 px, shifts differ by a few px across the field). Experiments: scratchpad ffcaa988…/m81 (`MaskExperiment`, `SeedExperiment`, `LeftoverCheck`), runs reg/s10b (base), s13, m81tb/m81tc13, t11b/t13c.
- **Two-pass master stars committed and pushed (ffa60aa, docs fd23737, 2026-10-18).** Old engine (09f0e69, worktree scratchpad/old) with the M81-kumar HIGH tune: 231 LR / 42 CM, so most leftovers predate the data area (the void radius raised to 168 px cut about 13 % of the frame); the 2026-10-11 report showed none because residual analysis was off in that profile and common motion did not exist yet.
- **Option A, sensor-fixed test (study 2026-10-19, nothing implemented yet; scratchpad ffcaa988…/sensor, `SensorStudy.java`, `extract.mjs`):** each frame's shift and rotation fitted to its fill edges (small-angle linear model; rms 0.3-1.1 px on all 19 datasets; M81-kumar frames before the meridian flip are turned 3.6-4.5 deg, after it up to 0.4 deg; a flipped frame maps to mirrored camera coordinates, so each pier side matches only itself). Findings mapped to camera coordinates: M81-kumar 68 of 84 LR are 11 hot pixels (camera spread 0.4-1.1 px, sky spread 40-73 px), whynot both tracks, Eagle-noisy 24 of 74 tracks (camera spread 0.7-1.8 px; every other track 21 px or more); no real object flagged (Apophis, Africano comet, klangwolke-2 asteroids, klangwolke-1, helix, m5, IC 5070). Proposed design: transforms in FrameDriftAnalyzer (exact rotation model), a filter on the frames' point detections before the star-mask veto and the tracker (same camera position within 2 px in at least 3 frames whose sky positions spread at least 4 px), groups exported for the report.
- **Camera-fixed rejection (2026-10-19; JTransient 4b443d7 + docs f084ab9, SpacePixels 8ce5d99 + manual ec13f01, all pushed):** `core/FrameRegistration` (Gauss-Newton fit of a turned rectangle to the fill edges from `FrameCoverage`, sides without fill keep 0, rms over 2 px = not usable; `DriftAnalysisResult.frameRegistrations`), `core/CameraFixedRejector` (one detection per frame within 2 px of the median camera position, at least 3 frames, sky spread at least 4 px), applied in `TrackLinker.findMovingObjects(..., registrations)` after the veto, removing from point transients and `allTransients`; `TrackingResult`/`PipelineResult.cameraFixedGroups`, `TrackerTelemetry.cameraFixedGroups/Detections`; setting `enableCameraFixedRejection` (true). SpacePixels: "Reject Camera-Fixed Sources" (basic row, Hot Pixels And Dust section of the Track Linking page), report section "Camera-Fixed Groups" (FC ids, nav, overview card, In report checkbox, `ReportInclusion.cameraFixed`, crops reuse the common-motion helpers in ResidualReviewSectionWriter). Regression (base fd23737 + SpacePixels 1e51bd7, runs reg/s14b vs s14): 16 datasets identical; whynot T1/T2 → FC1/FC2; klangwolke-2 adds FC1/FC2 near (8810, 2008), nothing else; Eagle-noisy T 74 → 7, A 502 → 156, LR 7 → 4, CM 30 → 0, FC 90, and one new POSSIBLE variable (fewer track crossings); every lost Eagle track had camera-fixed points (55 entirely). M81-kumar with the user's HIGH tune: LR 84 → 10, CM 5 → 3, FC 19 (m81tb14/m81tc14). whynot legacy tune: T 2 → 0, FC 4.
- **Variable-star findings:** 1, 3, 4 and 7 are fixed; 2, 5, 6, 8 and 9 are deferred by the maintainer. See `VariableStar-ASSESSMENT.md`.
