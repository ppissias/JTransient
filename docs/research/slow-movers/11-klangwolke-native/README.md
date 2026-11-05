# Klangwolke-2 native full-field test

[Collection index](../README.md) · [Conclusion](CONCLUSION.md) · [Current algorithm](../../../../ALGORITHM.md#faint-mover-complementary-proposals)

**No additional detection:** D finds 11 existing candidates, T4 finds 7 of those, and their combined selection returns the same 11 with byte-identical candidate/review data. Both searches complete 247/247 tiles on 28 retained real frames. No artificial sources are inserted.

Read [PLAN.md](PLAN.md) and [CONCLUSION.md](CONCLUSION.md) for scope, resource handling, conditional controls, reuse limitations and the distinction between candidate paths and confirmed physical sources.

| Artifact | Role |
|---|---|
| [PLAN.md](PLAN.md) | Protocol or conclusion |
| [CONCLUSION.md](CONCLUSION.md) | Protocol or conclusion |
| [effective-profile.json](effective-profile.json) | Numerical evidence, bounded pixels or provenance |
| [accepted-baseline.json](accepted-baseline.json) | Numerical evidence, bounded pixels or provenance |
| [controls.json](controls.json) | Numerical evidence, bounded pixels or provenance |
| [source-copy-check.json](source-copy-check.json) | Numerical evidence, bounded pixels or provenance |
| [summary.json](summary.json) | Numerical evidence, bounded pixels or provenance |
| [context-summary.json](context-summary.json) | Numerical evidence, bounded pixels or provenance |
| [candidate-comparison.json](candidate-comparison.json) | Numerical evidence, bounded pixels or provenance |
| [source-correspondence.json](source-correspondence.json) | Numerical evidence, bounded pixels or provenance |
| [source-correspondence.csv](source-correspondence.csv) | Numerical evidence, bounded pixels or provenance |
| [trace-summary.json.gz](trace-summary.json.gz) | Numerical evidence, bounded pixels or provenance |
| [execution-snapshot.json](execution-snapshot.json) | Numerical evidence, bounded pixels or provenance |
| [input-sequence-hashes.json](input-sequence-hashes.json) | Numerical evidence, bounded pixels or provenance |
| [external-result-manifest.json](external-result-manifest.json) | Numerical evidence, bounded pixels or provenance |
| [native-pixel-review.json.gz](native-pixel-review.json.gz) | Numerical evidence, bounded pixels or provenance |
| [native-comparison.png](native-comparison.png) | Scientific figure |
| [native-comparison.svg](native-comparison.svg) | Scientific figure |
| [native-pixel-review.png](native-pixel-review.png) | Scientific figure |
| [native-pixel-review.svg](native-pixel-review.svg) | Scientific figure |
| [domain.json](domain.json) | Numerical evidence, bounded pixels or provenance |
| [native-preparation.json](native-preparation.json) | Numerical evidence, bounded pixels or provenance |
| [upstream-findings.json.gz](upstream-findings.json.gz) | Numerical evidence, bounded pixels or provenance |
| [actual-D.json](actual-D.json) | Numerical evidence, bounded pixels or provenance |
| [D.json](D.json) | Numerical evidence, bounded pixels or provenance |
| [T4.json](T4.json) | Numerical evidence, bounded pixels or provenance |
| [D+T4.json](D+T4.json) | Numerical evidence, bounded pixels or provenance |
| [accepted-baseline-parity.json](accepted-baseline-parity.json) | Numerical evidence, bounded pixels or provenance |
| [actual-library-parity.json](actual-library-parity.json) | Numerical evidence, bounded pixels or provenance |
| [path-comparison.json](path-comparison.json) | Numerical evidence, bounded pixels or provenance |
| [D+T4-discovery.jsonl.gz](D+T4-discovery.jsonl.gz) | Numerical evidence, bounded pixels or provenance |
| [D+T4-refinement.jsonl.gz](D+T4-refinement.jsonl.gz) | Numerical evidence, bounded pixels or provenance |
| [D+T4-selection.jsonl.gz](D+T4-selection.jsonl.gz) | Numerical evidence, bounded pixels or provenance |
| [D+T4-tile.jsonl.gz](D+T4-tile.jsonl.gz) | Numerical evidence, bounded pixels or provenance |
| [D-discovery.jsonl.gz](D-discovery.jsonl.gz) | Numerical evidence, bounded pixels or provenance |
| [D-refinement.jsonl.gz](D-refinement.jsonl.gz) | Numerical evidence, bounded pixels or provenance |
| [D-selection.jsonl.gz](D-selection.jsonl.gz) | Numerical evidence, bounded pixels or provenance |
| [D-tile.jsonl.gz](D-tile.jsonl.gz) | Numerical evidence, bounded pixels or provenance |
| [T4-discovery.jsonl.gz](T4-discovery.jsonl.gz) | Numerical evidence, bounded pixels or provenance |
| [T4-refinement.jsonl.gz](T4-refinement.jsonl.gz) | Numerical evidence, bounded pixels or provenance |
| [T4-selection.jsonl.gz](T4-selection.jsonl.gz) | Numerical evidence, bounded pixels or provenance |
| [T4-tile.jsonl.gz](T4-tile.jsonl.gz) | Numerical evidence, bounded pixels or provenance |

[validation.json](validation.json) records integrity/publication checks. [provenance.json](provenance.json) links exact source and archived bytes. Gzip files use standard JSON or newline-delimited JSON as their extensions indicate. All blind discovery/refinement/selection/core ledgers are retained; the artifact inventory identifies compressed names.

Original observing-site metadata, raw FITS files, full tile caches and executable diagnostics remain external. The input/clock inventory uses original zero-based sequence IDs without source filenames. Pixel coadds reuse the fitted path and do not establish independent rediscovery, calibrated photometry or identity confidence. The earlier ten study directories are unchanged.
