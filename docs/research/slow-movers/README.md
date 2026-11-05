# Slow-mover investigation archive

This collection preserves eleven dated SM-F1/SM-F2/SM-F3/SM-F6/SM-F7 investigations conducted on 3-5 November 2026. It contains historical protocols, conclusions, numerical tables, validation records and selected scientific figures. Large case tables are losslessly compressed with gzip.

[ALGORITHM.md](../../../ALGORITHM.md) remains the current algorithm reference. [FEATURE-REQUESTS.md](../../../FEATURE-REQUESTS.md) owns pending decisions and acceptance work. Historical reports describe their own experimental baseline and publication state; a statement such as “not implemented” in an early snapshot is not the current implementation status.

## Investigation sequence

| Date | Investigation and retained evidence | Main finding |
|---|---|---|
| 2026-11-03 | [Three blind proposal methods](01-three-method-prototypes/README.md) | Digital tracking, temporal-bin and PSF-filtered frame-point prototypes find the three original faint references. Digital tracking has the strongest recovery in the small injection comparison and becomes the first production delivery. The other methods remain prototypes. |
| 2026-11-04 | [Maximum-stack baseline and row caching](02-maximum-stack-baseline/README.md) | Five full-size fields establish native morphology/median/frame-evidence behavior. Row caching improves the isolated stack benchmark while preserving every pixel. |
| 2026-11-04 | [Centroid evidence and correlation controls](03-centroid-correlation/README.md) | The 975-case grid includes a genuine curved injected history lost by a proposed correlation cutoff. Descriptive centroids/fits and cautious track comparisons are delivered; no temporal veto is adopted. |
| 2026-11-04 | [Broader-shape comparison](04-broader-shapes/README.md) | On 3,666 paired cases outside the five development datasets, raising the maximum axis ratio from 2.8 to 5 gains moving histories and many shuffled controls. The default remains 2.8. |
| 2026-11-04 | [Motion confirmation and native streak provenance](05-motion-and-streaks/README.md) | Of 4,356 planned histories, 4,086 evaluate. The hypothetical temporal veto loses straight, curved and gapped movers. Original-frame evidence attributes suspect maximum-stack regions to real bright streaks; a blanket contact veto is not adopted. |
| 2026-11-05 | [Residual positions and uncertainty](06-residual-position-fits/README.md) | Of 2,052 planned cases, 1,910 evaluate. Residual fitting improves some blended-source histories and loses others; formal covariance is severely under-covered on native backgrounds. Exact Prokne replay does not demonstrate superior astrometry. |
| 2026-11-05 | [Retrospective gate inventory](07-filter-inventory/README.md) | Ordered conditional counts are not independent ablations. The old association definition already required 40% support and could not validate that threshold independently. |
| 2026-11-05 | [Joint SM-F1/SM-F3 gate comparison](08-joint-gates/README.md) | Nineteen policies on 1,910 evaluated cases expose support/morphology/median/stationarity interactions. A separate brighter-flash follow-up establishes a support-floor benefit. The tested temporal-bin veto is not adopted. |
| 2026-11-05 | [SM-F6 matched-domain validation](09-digital-validation/README.md) | All 462 searches finish on six observations, including full-speed M81. Three disjoint image-third searches and 12 controlled encounters add conditional evidence. Deeper predictions give compatible known-object paths for all 11 original K2 candidates. No filter/default change earns adoption. |
| 2026-11-05 | [SM-F7 complementary proposals](10-complementary-proposals/README.md) | Four fields, 644 result rows and 240 available moving histories compare the unchanged verifier. Temporal-bin proposals add six targeted close companions; five survive full upstream checks beyond ordinary tracks and digital tracking. Frame-point routes mostly reach resource boundaries. Gates/defaults and production integration remain unchanged. |
| 2026-11-05 | [Native Klangwolke-2 full-field application](11-klangwolke-native/README.md) | No injections: all 247 tiles complete. D finds the existing 11 candidates, T4 seven of those, and their union the same 11 with byte-identical candidate/review data. No additional native detection; current gates/defaults and integration remain unchanged. |

## Conclusions to carry forward

**Current status:** the investigation round is closed with present filters and defaults retained. Only the deferred [SM-F7 extension item](../../../FEATURE-REQUESTS.md#sm-f7-slow-and-faint-mover-extensions) remains for a concrete missed-source or resource need; no further campaign is scheduled. The dated study snapshots retain their historical proposals and publication state.

- Keep the current scientific filters/defaults. Increased selected-history recovery can accompany substantial control load, and some relaxations also lose sources through changed preprocessing or noise normalization.
- Treat missing evidence, input/model eligibility, candidate ceilings and scientific rejection separately. The camera controls in the latest grid do not cleanly test steady camera drift in the intended motion domain.
- Use raw footprint/source provenance and credible centroid/PSF/noise uncertainty before adopting temporal confirmation or more aggressive overlap rules. A streak contact, strong time correlation or catalogue absence alone is not a physical classification.
- Carry forward the first SM-F7 comparison's targeted temporal-bin benefit, source/review costs and incomplete frame-point coverage. Independent observing examples, source attribution and an agreed engine/application contract remain necessary before integration. Reviewed grids are diagnostic data, not new independent threshold-validation sets.

## Code changes and current scope

[CODE-HISTORY.md](CODE-HISTORY.md) distinguishes delivered engine/application changes from research-only alternatives and lists their commits and validation evidence. Digital tracking is integrated off by default. Maximum-stack row caching, copied centroid/time-fit evidence, cautious track comparisons, bounded faint-mover review, search progress and clearer known-object reports are delivered. Wider defaults, automatic temporal rejection and the two complementary SM-F7 methods are not introduced by this archive or the latest gate studies.

The publication containing this archive changes documentation/evidence only. Original observation pixels, saved settings and reports remain unchanged.

## Reading and reusing the evidence

Read [DATA-NOTES.md](DATA-NOTES.md) before joining tables or comparing scores. Case numbers are local to an investigation; repeated source histories and shared fields are correlated. Each study directory has an inventory and `provenance.json` with original/archived artifact SHA-256 values, portable-path transformations and available production-source snapshot hashes. [archive-manifest.json](archive-manifest.json) checks the committed archive bytes; line endings are fixed to LF for text files.

[VALIDATION.json](VALIDATION.json) records the original nine-study publication checks. The SM-F7 extensions have separate [matched-verifier scientific validation](10-complementary-proposals/validation.json), [publication checks](10-complementary-proposals/publication-validation.json) and [native K2 validation](11-klangwolke-native/validation.json): numerical comparisons, source/result integrity, local links, code-history references, figure metadata and privacy.

The archive preserves decision evidence and protocols, rather than a self-contained bitwise rerun package. Original FITS/XISF data, full image/tile caches, executable diagnostics and machine-specific launchers remain external under the repository diagnostic-program policy. Historical references to those scratch artifacts identify their original roles; only the study inventories list files included here. Local private artifact locations are maintained in ignored environment notes.

Figures contain scientific plots or bounded source pixels without observing-site metadata. JSON/CSV evidence contains portable study/dataset/case identifiers. Current behavior and pending decisions should continue to be maintained in their existing root documents rather than edited into these dated snapshots.
