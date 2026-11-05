# Klangwolke-2: native full-field temporal-proposal test

Dated research snapshot. See the [inventory](README.md), current [algorithm](../../../../ALGORITHM.md) and [pending decisions](../../../../FEATURE-REQUESTS.md). Diagnostic executables remain external.

Investigation date: 2026-11-05. Requested real-data application after the paired SM-F7 experiments. No artificial sources are inserted.

**Result: the temporal-bin complement adds no detection on this dataset.** All 247 production tiles complete in D and T4. Digital tracking returns the existing eleven candidates; T4 returns seven; combined final selection returns the same eleven. The decompressed candidate JSON, including every owned cutout/review array, is byte-identical between D and D+T4. No baseline path is lost and no additional path is selected.

| Route | Completed tiles | Selected native paths | Additional paths beyond D |
|---|---:|---:|---:|
| Digital D | 247 / 247 | 11 | 0 |
| Temporal T4 | 247 / 247 | 7 | 0 |
| Combined D+T4 | 247 / 247 | 11 | 0 |

The input is the accepted 33-frame Klangwolke-2 sequence. Upstream processing retains the same 28 frames, median/maximum/master-mask hashes and ordinary tracks as the accepted baseline. Both searches examine the same 49,540,332 unmasked reference pixels, with 720 coarse motions and maximum speed 1 pixel/minute. Seeing is measured as 2.6184566195 pixels. Actual-library D, the recorded eleven-candidate D reference and the bounded research D match in candidate/review values and every non-time scientific counter.

## What T4 found

T4 independently generates proposals for the same paths labelled FM1–FM7 in the existing score-ordered digital baseline. Their path RMS differences are zero at the searched epochs; two velocity components differ only at floating-point roundoff (at most about 5.6e-17 pixel/minute). These are seven distinct existing paths, not seven new objects. The four remaining D findings, FM8–FM11, have no compatible verified T4 peak entering final selection. No T4 source-compatible seed is present for them under the descriptive full-path comparison criterion; this does not exhaust every possible local-refinement neighborhood or separate all discovery/refinement causes.

T4 proposes 422 distinct seeds, refines all of them and has 19 verified peaks before final selection. Its final cuts remove nine duplicates and three ordinary-track matches, leaving seven. D has 20 verified peaks, with one ordinary-track and eight side-lobe removals, leaving eleven. The union has 17 duplicate, three track and eight side-lobe removals. These are conditional, ordered counts on different proposal populations; they are not independent filter ablations or redundancy proofs.

The [full-field comparison](native-comparison.png), [source correspondence](source-correspondence.csv) and streamed ledgers preserve the real results. The [input-pixel review](native-pixel-review.png) shows FM1/FM4/FM7 and the D-only FM11 in unstacked original frames and shared-path moving/fixed coadds. Nearby star light remains visible. Display subtracts each local median and shares an ADU/asinh scale per row; coadds reuse the full fitted path and are not independent rediscoveries, astrometry or calibrated photometry. The earlier K2 catalogue comparisons remain post-detection compatibility evidence; no new identity query or asteroid-discovery claim follows from this run.

## Coverage, controls and resource handling

The full-field adapter bounds immutable prepared tiles/diagnostic maps and streams gzip JSONL ledgers. Synthetic steady and missing-middle controls force single-entry cache eviction and reproduce actual-library D, the frozen T4 and the frozen combined selection exactly. Nine production preparation/verifier method bodies and the proposal generator remain unchanged, apart from diagnostic logging. Active workers retain their own immutable current tiles separately from cached references.

Both searches use a 3600-second research ceiling, while the production 600-second setting is untouched. Neither search reaches 600 seconds, the 50,000-seed/core limit or the 1000-candidate ceiling. There are no incomplete tiles. The larger research allowance does not rescue truncated coverage in this result.

The frozen protocol runs matched negative/clock controls only if an additional union path appears. That trigger is not met, so those four contexts are not executed. This is not a zero-false-alarm result or confidence calibration. K2 was used in earlier development: the run supplies the requested real, uninjected full-field comparison, not independent threshold validation or a proof that the observation contains no fainter/other-motion sources.

Observed research times are 131.051 seconds for the separate actual-library baseline, 159.028 for bounded D, 40.703 for T4 and 10.504 for union reselection. The latter is reselection only; D+T4 search plus reselection accounts for 210.235 seconds. The complete diagnostic job, including native preparation, the extra parity baseline and serialization, takes 518.281 seconds with peak process working set about 8.57 GiB under an 11 GiB heap limit/four processors. Fixed order, JIT, cache reuse and diagnostic I/O prevent a controlled production-speed comparison. The preparation counter sums worker durations; it is not a wall-clock component to add or subtract.

## Decision and retained evidence

Keep current production behavior, scientific filters and defaults. The targeted injected close-pair benefit from the prior study remains specific to that experiment; this native field demonstrates no additional sensitivity. T4 remains a research complement with no production integration. Real observing examples that demonstrate a useful additional source, together with credible attribution and an agreed engine/application contract, are still needed before adoption. Do not lower a threshold on this reviewed field to force a positive result.

All 31 frozen study artifacts, 80 dependencies, 757 production/build/test artifacts and 33 input image hashes are preserved. Original reports, input files, user settings and preferences remain unchanged. No GUI or production source edits are made. The archive retains effective research configuration, source/clock/hash provenance, exact baseline checks, all blind proposal/rejection ledgers, source correspondences, resource observations and bounded original-pixel evidence. Executables and large full-image caches remain external. The ten earlier dated study directories retain their original bytes.
