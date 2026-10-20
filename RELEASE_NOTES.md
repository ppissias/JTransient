# JTransient Release Notes

## 1.0.5 (unreleased)

In preparation: changes since 1.0.4 are collected here and completed at release. Used by SpacePixels 2026.10-04.

### Detection changes

- **Anomalies on a streak's path join that streak.** A faint streak crossing a star changes the star's shape, and a tumbling satellite flashes along its path. Both were rescued as single-frame anomalies. An anomaly on a confirmed streak's line in the same frame, or where the streak's motion puts it in a frame the streak skipped or in the frame just before or after it, is now a piece of that streak. See [Anomalies on a streak's path](ALGORITHM.md#134-anomalies-on-a-streaks-path).
  - On the reference data, klangwolke-2 kept 22 of its 39 anomalies. The joined pieces were mostly a satellite's flashes and a trail crossing stars. The other datasets were unchanged, apart from one anomaly on klangwolke-1.
  - Photometry flags the stars a track crosses, so the stars these pieces cross lose their measurement in those frames.

### API changes

- `TrackLinker.AnomalyDetection.lowConfidence` is removed. The anomalies it marked, those on a streak in the same frame, are now pieces of that streak's track in `PipelineResult.tracks` instead of entries in `PipelineResult.anomalies`.
