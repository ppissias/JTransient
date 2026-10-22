# JTransient Release Notes

## 1.0.6 (unreleased)

- **Additive structured failure outcomes.** Engine and tuner outcome APIs distinguish completed empty/insufficient-evidence results, execution failure and joined cancellation. Stage IDs and original frame identities are captured through worker batches, listeners, photometry and tuning. Legacy exception/cause objects and calibrated failure-result behavior remain unchanged. Interruption retains its flag; fatal errors propagate. Per-stage progress/outcome events remain separate JA-09 work.

- **Independent tuner operations.** Both tuner facades capture immutable options, defensively copy grids/budgets and retain effective policy provenance. Explicit options support concurrent independent requests. Legacy per-run debug sinks stay silent by default. Existing entry points and legacy fallback aliases remain compatible; `tuneAvailableSnapshot` avoids temporarily changing sample-size globals in SpacePixels.
- **Whole-operation worker ownership.** `ExecutionPolicy` and owned/borrowed `ExecutionSession` cover engine/tuner batches, stack rows and photometry. Managed nested batches execute inline, borrowed resources remain caller-owned, and completion still joins sibling work. Heap accounting is an explicit scratch estimate, not a process-wide memory guarantee. Reference science outputs remain equivalent; the documented large-frame single sample is slower with higher pool peaks, so no performance improvement is claimed.
- **Java and compatibility gates.** Java 21 toolchain/`--release 21`, a legacy source/binary API consumer and bytecode checks for adopted config/execution boundaries run under `check`. The coordinated SpacePixels matrix verifies source-built 1.0.5/1.0.6 artifacts with composites disabled and both Gradle/POM metadata. The 1.0.5 section below describes the predecessor unreleased source baseline, not a publicly available artifact; actual Central release availability remains unverified. No publication is performed by these checks.

## 1.0.5 (unreleased)

In preparation: changes since 1.0.4 are collected here and completed at release. Used by SpacePixels 2026.10-04.

### Detection changes

- **Anomalies on a streak's path join that streak.** A faint streak crossing a star changes the star's shape, and a tumbling satellite flashes along its path. Both were rescued as single-frame anomalies. An anomaly on a confirmed streak's line in the same frame, or where the streak's motion puts it in a frame the streak skipped or in the frame just before or after it, is now a piece of that streak. See [Anomalies on a streak's path](ALGORITHM.md#134-anomalies-on-a-streaks-path).
  - On the reference data, klangwolke-2 kept 22 of its 39 anomalies. The joined pieces were mostly a satellite's flashes and a trail crossing stars. The other datasets were unchanged, apart from one anomaly on klangwolke-1.
  - Photometry flags the stars a track crosses, so the stars these pieces cross lose their measurement in those frames.

### API changes

- **JA-01: captured configuration and shared rules.** Engine operations, both tuners and direct calibration capture private settings before processing. `DetectionConfigRules` exposes metadata for all 107 fields, operation-scoped structured validation and explicit alignment with field/rule IDs and adjustment directions. Non-finite consumed values are errors; finite legacy values, sentinels and scientific-policy conflicts remain accepted with advice. No serialized config fields/defaults change.
- Both tuners add `tuneSnapshot(...[, cancellation])` with detached fallback configs. Existing `tune` overloads preserve the same-reference fallback contract. SpacePixels consumes the rules for GUI, profiles, API and iterative requests. This is a coordinated local source integration; published-pair compatibility still needs release validation.
- `PhotometryGeometry` centralizes existing effective aperture/annulus pixel margins and exposes structured radius adjustments. Selection and crossing checks now follow expanded measurement radii instead of raw factors; configured factors remain unchanged. Targeted edge/crossing/flux tests cover custom/legacy factors. On the 153-frame africano-reg reference sequence, full scientific snapshots remain byte-identical to the pre-JA-01 baseline (excluding execution timings, hashing image/mask arrays).

- `TrackLinker.AnomalyDetection.lowConfidence` is removed. The anomalies it marked, those on a streak in the same frame, are now pieces of that streak's track in `PipelineResult.tracks` instead of entries in `PipelineResult.anomalies`.
