# SM-F7 complementary proposal comparison

Investigation date: 2026-11-05. Dated evidence archive.

[Collection index](../README.md) · [Current algorithm](../../../../ALGORITHM.md#faint-mover-complementary-proposals) · [Pending decisions](../../../../FEATURE-REQUESTS.md#sm-f7-complementary-high-sensitivity-moving-object-searches)

Read [CONCLUSION.md](CONCLUSION.md) and the frozen [PLAN.md](PLAN.md) before joining case tables. General histories, purpose-built close pairs, incomplete resource coverage and reused diagnostic cases have separate denominators. These are conditional experiments on existing observations, not survey sensitivity or new-object counts.

The exact-velocity oracle initially shifts the production fine-motion lattice. [GRID-DIAGNOSTIC-AMENDMENT.md](GRID-DIAGNOSTIC-AMENDMENT.md) records that limitation and the separately frozen admitted-grid correction. The first supplementary draft was stopped before any diagnostic JVM executed; it remains in provenance. A local oracle failure is not exhaustive over seeds.

## Retained artifacts

| Artifact | Role |
|---|---|
| [PLAN.md](PLAN.md) | Dated protocol or conclusion |
| [PLAN-initial.md](PLAN-initial.md) | Dated protocol or conclusion |
| [DIAGNOSTIC-AMENDMENT.md](DIAGNOSTIC-AMENDMENT.md) | Dated protocol or conclusion |
| [FRESH-DIAGNOSTIC-PLAN.md](FRESH-DIAGNOSTIC-PLAN.md) | Dated protocol or conclusion |
| [GRID-DIAGNOSTIC-AMENDMENT.md](GRID-DIAGNOSTIC-AMENDMENT.md) | Dated protocol or conclusion |
| [NONFINITE-PARITY-AMENDMENT.md](NONFINITE-PARITY-AMENDMENT.md) | Dated protocol or conclusion |
| [FULL-PIPELINE-PLAN.md](FULL-PIPELINE-PLAN.md) | Dated protocol or conclusion |
| [BASELINE-AUDIT-PLAN.md](BASELINE-AUDIT-PLAN.md) | Dated protocol or conclusion |
| [CONCLUSION.md](CONCLUSION.md) | Dated protocol or conclusion |
| [profile.json](profile.json) | Numerical evidence, source pixels or integrity record |
| [controls.json](controls.json) | Numerical evidence, source pixels or integrity record |
| [source-copy-check.json](source-copy-check.json) | Numerical evidence, source pixels or integrity record |
| [prior-diagnostic-post-summary.json](prior-diagnostic-post-summary.json) | Numerical evidence, source pixels or integrity record |
| [full-capture-source-check.json](full-capture-source-check.json) | Numerical evidence, source pixels or integrity record |
| [full-controls.json](full-controls.json) | Numerical evidence, source pixels or integrity record |
| [full-targets.json](full-targets.json) | Numerical evidence, source pixels or integrity record |
| [full-target-manifest.json](full-target-manifest.json) | Numerical evidence, source pixels or integrity record |
| [nonfinite-parity-proof.json](nonfinite-parity-proof.json) | Numerical evidence, source pixels or integrity record |
| [accepted-diagnostic-runs.json](accepted-diagnostic-runs.json) | Numerical evidence, source pixels or integrity record |
| [summary.json](summary.json) | Numerical evidence, source pixels or integrity record |
| [audit-summary.json](audit-summary.json) | Numerical evidence, source pixels or integrity record |
| [preparation-domain.json](preparation-domain.json) | Numerical evidence, source pixels or integrity record |
| [contexts.csv](contexts.csv) | Numerical evidence, source pixels or integrity record |
| [sources.csv.gz](sources.csv.gz) | Numerical evidence, source pixels or integrity record |
| [paired-comparisons.csv](paired-comparisons.csv) | Numerical evidence, source pixels or integrity record |
| [field-summary.csv](field-summary.csv) | Numerical evidence, source pixels or integrity record |
| [trace-summary.json.gz](trace-summary.json.gz) | Numerical evidence, source pixels or integrity record |
| [source-stages.json](source-stages.json) | Numerical evidence, source pixels or integrity record |
| [source-verified-peaks.json.gz](source-verified-peaks.json.gz) | Numerical evidence, source pixels or integrity record |
| [resource-summary.json](resource-summary.json) | Numerical evidence, source pixels or integrity record |
| [additional-candidate-paths.json](additional-candidate-paths.json) | Numerical evidence, source pixels or integrity record |
| [prior-grid-summary.json](prior-grid-summary.json) | Numerical evidence, source pixels or integrity record |
| [execution-snapshot.json](execution-snapshot.json) | Numerical evidence, source pixels or integrity record |
| [input-sequence-hashes.json](input-sequence-hashes.json) | Numerical evidence, source pixels or integrity record |
| [blind-results.json.gz](blind-results.json.gz) | Numerical evidence, source pixels or integrity record |
| [truth-ledger.json.gz](truth-ledger.json.gz) | Numerical evidence, source pixels or integrity record |
| [oracle-ledger.json.gz](oracle-ledger.json.gz) | Numerical evidence, source pixels or integrity record |
| [prior-diagnostics.json](prior-diagnostics.json) | Numerical evidence, source pixels or integrity record |
| [external-result-manifest.json.gz](external-result-manifest.json.gz) | Numerical evidence, source pixels or integrity record |
| [special-value-encoding.json](special-value-encoding.json) | Numerical evidence, source pixels or integrity record |
| [fixed-raw-review.json.gz](fixed-raw-review.json.gz) | Numerical evidence, source pixels or integrity record |
| [validation.json](validation.json) | Numerical evidence, source pixels or integrity record |
| [full-pipeline-summary.json](full-pipeline-summary.json) | Numerical evidence, source pixels or integrity record |
| [full-pipeline-sources.csv](full-pipeline-sources.csv) | Numerical evidence, source pixels or integrity record |
| [full-pipeline-results.json](full-pipeline-results.json) | Numerical evidence, source pixels or integrity record |
| [baseline-summary.json](baseline-summary.json) | Numerical evidence, source pixels or integrity record |
| [baseline-ledger.json](baseline-ledger.json) | Numerical evidence, source pixels or integrity record |
| [proposal-comparison.png](proposal-comparison.png) | Scientific figure |
| [proposal-comparison.svg](proposal-comparison.svg) | Scientific figure |
| [source-diagnostics.png](source-diagnostics.png) | Scientific figure |
| [source-diagnostics.svg](source-diagnostics.svg) | Scientific figure |
| [fixed-raw-review.png](fixed-raw-review.png) | Scientific figure |
| [fixed-raw-review.svg](fixed-raw-review.svg) | Scientific figure |
| [gain-input-review.json.gz](gain-input-review.json.gz) | Numerical evidence, source pixels or integrity record |
| [gain-score-review.json.gz](gain-score-review.json.gz) | Numerical evidence, source pixels or integrity record |
| [gain-input-review.png](gain-input-review.png) | Scientific figure |
| [gain-input-review.svg](gain-input-review.svg) | Scientific figure |
| [gain-score-review.png](gain-score-review.png) | Scientific figure |
| [gain-score-review.svg](gain-score-review.svg) | Scientific figure |

[provenance.json](provenance.json) records source/archive hashes and transformations. Large numerical artifacts use deterministic lossless gzip. `execution-snapshot.json` contains portable source, input-image and frozen executable hashes; executable diagnostics, full proposal ledgers, raw observations and machine-local launchers remain external. `external-result-manifest.json` identifies those retained external result bytes.

[Publication validation](publication-validation.json) records numerical, link, figure, privacy, code-history and unchanged-result checks for this extension. The collection manifest seals the final bytes.

The three raw-review epochs are source-centred fixed low-SNR examples, not independent rediscoveries. Figures and bounded pixel arrays contain no observing-site metadata. The nine earlier study directories retain their original bytes.
