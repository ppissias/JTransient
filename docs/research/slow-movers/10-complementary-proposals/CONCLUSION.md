# SM-F7 complementary proposals with the current verifier

Dated investigation snapshot. Current behavior remains in [ALGORITHM.md](../../../../ALGORITHM.md) and pending decisions in [FEATURE-REQUESTS.md](../../../../FEATURE-REQUESTS.md). Local executable/cache names describe external diagnostics; see the [retained inventory](README.md).

Investigation date: 2026-11-05. Research and decision evidence; no production change.

The primary temporal-bin route supplies a small, targeted complement to digital tracking in the controlled close-pair tests. It is a poor replacement for digital tracking on the general grid. The PSF-filtered point route cannot yet support a broad sensitivity comparison because it repeatedly reaches its seed resource boundary. These results do not support removing a scientific gate or changing a default.

## Frozen comparison and scope

The four existing observations are Apophis, Africano, dc-reg and m5-reg, outside the six-field SM-F6 grid but previously used elsewhere. Two fixed production cores per field retain the native masks and the full admitted motion range through 1 pixel/minute. D is the current digital search. T4/T3 use three time groups with proposal thresholds 4/3 and any two groups allowed; P3/P2.5 use empirical PSF-filtered per-frame maxima and an additional distinct-frame contact. Every route uses the unchanged full-sequence refinement, score/halves/stationary/camera checks and final duplicate/track/side-lobe selection. The primary unions reselect D+T4 and D+P3 together; they do not concatenate final candidate lists.

There are 48 general batches (192 moving histories, 48 stationary controls and 48 one-frame flashes) and 24 separately preregistered close-pair batches (48 moving histories). All 240 moving histories and 96 source controls are available. The frozen protocol's wording about a control in “each batch” refers to the general batches: the pair generator contains the two moving sources only, consistent with the predeclared total of 96 controls. There are also four native, twelve newly shuffled-clock and four negative-residual contexts. Seven saved result families give 644 rows across 92 contexts.

These are conditional faint-stage experiments: native QC, seeing, registrations, tracks, streaks and data area are fixed while changed pixels receive an exact rebuilt median. Gaussian and Moffat profiles, constant pre-quantization integrated flux, ±20% seeing and seven exposure samples are specified before testing. Source photon noise is omitted. The aperture-SNR construction is not a calibrated magnitude or discovery probability. A source association requires active-path RMS within one original search FWHM and velocity error at most 0.1 pixel/minute. Existing backgrounds, repeated artificial histories and targeted pairs are correlated, not independent discoveries.

## Coverage before recovery

The denominator in each recovery cell is the number of available histories in complete contexts for that method. A resource-incomplete context is excluded from clean sensitivity comparisons while its raw selected paths remain in the ledgers.

| Route | Complete contexts / 92 | General associated / complete | Pair associated / complete |
|---|---:|---:|---:|
| D | 92 | 87 / 192 | 21 / 48 |
| T4 | 92 | 13 / 192 | 25 / 48 |
| T3 | 73 | 29 / 144 | 25 / 36 |
| P3 | 24 | 1 / 48 | 3 / 12 |
| P2.5 | 23 | 1 / 48 | 3 / 12 |
| D+T4 | 92 | 87 / 192 | 27 / 48 |
| D+P3 | 24 | 1 / 48 | 3 / 12 |

The [comparison figure](proposal-comparison.png), `summary.json` and `field-summary.csv` retain these denominators. T4 alone loses 74 general histories and two pair histories that D associates, and gains six pair histories. Its union retains all 108 D-associated histories and adds those six, with no general-grid increment or paired selection loss. Five added weak companions are in dc-reg and one in m5-reg. No matching native-before-injection faint path is present. The lower-threshold T3 standalone route gains ten paired histories on its smaller complete domain; D+T3 is not tested, so ten must not be quoted as a union gain or integration result.

T3 reaches the 50,000-distinct-seed boundary in 19 cores, P3 in 136 and P2.5 in 138. There are no discovery partials before that boundary in the accepted ledgers. These are prototype resource limits, not scientific rejections. P3/P2.5 complete no injected context on the three fields after Apophis, so their 4/60 associated histories in complete contexts cannot be compared with D's full 108/240 as a sensitivity ranking.

All 20 native/shuffle/negative contexts complete for D, T4 and D+T4, with zero reported candidates. The other routes' smaller control coverage is explicit in `summary.json`. No stationary/flash source is associated in any complete injected context; their comparable denominators also differ by route. These are selected, correlated controls on observations containing real sources, not operational false-alarm rates.

Additional reported paths cost review effort. Across all repeated contexts, D reports 113 candidates and D+T4 reports 136, while the added associated-history count is six. Extra path counts include aliases, changes within source neighborhoods and unmatched paths; they are not 23 additional physical objects or a calibrated false-positive count. All plausible source matches, including ambiguous multiple matches, remain in the source ledger. The [input-pixel](gain-input-review.png) and [score-cutout](gain-score-review.png) contact sheets cover the six primary and four additional T3-only examples without hiding the native background or bright companion.

## Diagnostic reuse and motion-grid phase

All twelve old K2 encounters replay exactly. The corrected admitted-grid diagnostic separates three selected histories, three source-compatible local seeds that survive final selection but have no blind D recovery, three local camera rejections, one local score rejection and two source-compatible local peaks removed by the side-lobe check (cases 7/8). They are diagnostic reuse, not fresh method-validation gains. The latter two cases show why an ablation with no added blind recovery does not prove a late gate harmless: a missing proposal can hide its conditional sensitivity cost.

Supplying an exact injected velocity to the oracle shifts the fine-motion lattice. The separately frozen admitted-grid correction uses the nearest actual coarse node before the original refinement. Cases 7/8 change from unquantized local misassociation to compatible grid-local peaks rejected later. The exact-velocity diagnostic is retained with its original scope, not silently substituted for the corrected analysis. Both oracles are local: a failure does not exhaust every possible source-compatible seed, and a compatible score does not prove source dominance or astrometric accuracy.

Across the 240 fresh histories, the local diagnostic labels 108 D-selected histories, 104 local score rejections, four local refinements that miss the source criterion, eighteen unmasked admitted-grid local seeds that survive final selection without a blind D recovery, and six compatible local oracles whose starting reference seed is masked. Those six are not admissible proposal-loss demonstrations; a nearby unmasked seed could behave differently. The [diagnostic figure](source-diagnostics.png) and full source ledger preserve this distinction. These counts are explanations conditional on one local seed, not gate-removal experiments.

The supplementary comparator initially stopped at dc-reg case03 because parsed and numeric `NaN` represented an unavailable half-score differently. Consistent serialize/parse comparison restores exact candidate/review/non-time-counter parity; it does not loosen the check. The stopped run and all original executables remain preserved. `accepted-diagnostic-runs.json`, the amendment and manifests distinguish accepted results from that failed comparison. Public numerical ledgers encode unavailable non-finite scores as explicit strings, never zero.

<!-- full-pipeline-results:start -->
## Targeted full-upstream confirmation

All six primary union-gain batches are reconstructed without changing the source histories. Already rendered kept-frame pixels match exactly; the same physical sources are extended to all original frames, including seven native-rejected dc-reg frames and four m5-reg frames. Extraction, QC, registrations, stacks, masks, seeing, ordinary tracks and streaks are recomputed. Every rerun D result matches the actual library before interpreting T4 or its union.

| Target weak companion | Native / rerun kept frames | Union adds a compatible faint path beyond ordinary tracks and D |
|---|---:|---|
| dc-reg case13, Gaussian parallel 3 FWHM | 52 / 51 | No |
| dc-reg case14, Gaussian parallel 4.5 FWHM | 52 / 52 | Yes |
| dc-reg case15, Gaussian crossing 3 FWHM | 52 / 52 | Yes |
| dc-reg case17, Moffat parallel 4.5 FWHM | 52 / 52 | Yes |
| dc-reg case18, Moffat crossing 3 FWHM | 52 / 52 | Yes |
| m5-reg case18, Moffat crossing 3 FWHM | 32 / 32 | Yes |

Five of six conditional gains remain additional to ordinary tracks and D after the full upstream rerun. None has a matching native ordinary track. The sixth no longer associates after QC drops one additional frame; seeing, time groups, masks and the motion grid are recomputed, so this is not attributed to a single late gate. No tolerance is relaxed to recover it. The five retained cases still include multiple plausible paths in two examples; count each injected source once. `full-pipeline-summary.json` and the source table retain every selected case, including the failure.

A separate exact upstream replay audits the enabled maximum-stack branch as well. It returns zero accepted maximum-stack candidates in each dc-reg case and eight in m5-reg; none supplies source-compatible supported positions for the target weak companion under the prior SM-F1/SM-F3 three-position/80%-active/2-pixel criterion. Morphological regions and single-frame anomalies are not equated with independent moving histories. This supplies cross-branch context, not an automatic production deduplication rule or a claim of five independent new astronomical objects.

This follow-up confirms a targeted faint-path benefit on the same selected artificial pairs in two existing observations. It does not turn the entire 240-history conditional grid into full-pipeline completeness, validate a magnitude limit, calibrate confidence, or replace independent observing examples. The two confirmation workers take about 196 and 24 seconds, with whole-process peak working sets about 4.14 and 0.96 GiB; the separate baseline audits retain their own parity/resource records.
<!-- full-pipeline-results:end -->

## What this changes in the filter review

| Mechanism | Critical assessment from the combined investigations |
|---|---|
| Input/session/QC and motion eligibility | Held fixed in the 240-history comparison. Targeted upstream reruns provide a narrow check, not evidence to remove eligibility rules or admit every short/low-drift sequence. Missing input coverage remains distinct from rejection. |
| Fill, median/star/streak masks and clipping | Shared unchanged here. The earlier matched ablations measure sensitivity costs and substantial native/shuffled/flash load when relaxed. New close-pair gains show that some losses can be addressed before changing those masks. No blanket removal is justified. |
| Coarse-motion and spatial-maximum proposals | A demonstrated bottleneck for some compatible paths. Extra temporal proposals help selected nearby sources while leaving verification intact. Seeing-dependent pixel neighborhoods, motion-grid phase and absent proposals deserve direct diagnosis; a smaller universal maximum radius is not validated by this comparison. |
| Full score, usable halves and their coverage | The lower T3/P2.5 numbers are proposal thresholds, not lowered verifier cutoffs. Local oracle cuts are conditional, not independent ablations; no new confidence calibration or reason to remove the current score/half checks follows. |
| Stationary and camera comparisons | Remain unchanged. Three old local-oracle camera rejections show the ambiguity, not a calibrated real-source false-rejection rate. Earlier camera constructions did not cleanly test steady drift in the admitted domain. Those missing tests remain necessary before simplification. |
| Duplicate, ordinary-track and side-lobe selection | Source counts after reselection matter more than repeated paths. Full-upstream confirmation explicitly checks ordinary-track recovery. The two old compatible grid-local side-lobe losses are concrete acceptance counterexamples; the earlier no-side-lobe ablation also adds non-source-associated paths. Joint proposal/selection tests are needed before changing this gate. |
| Maximum-stack morphology, median overlap, measured support and stationarity | They are a separate construction path, reviewed in the [joint SM-F1/SM-F3 evidence](../08-joint-gates/CONCLUSION.md). Its support/artifact, shape and median/stationarity trade-offs still apply. The present time bins generate proposals and do not support a compulsory temporal-bin veto for that path. |

The [SM-F6 evidence](../09-digital-validation/CONCLUSION.md) and the new admitted-grid diagnostics are complementary: an inactive late cut on a proposal-starved set is neither proof that the cut is redundant nor proof that it has no sensitivity cost. The current recommendation preserves that uncertainty and the explicit counterexamples instead of declaring every gate validated.

## Costs, preservation and decision boundary

The first four complete comparison workers take approximately 35, 1550, 1612 and 956 seconds respectively, including upstream preparation, actual-library parity, all routes and serialization. Observed whole-process peak working sets are about 0.91–3.81 GiB under an 8 GiB heap limit. Each route's warm telemetry and common preparation are recorded separately. Fixed D/T4/T3/P3/P2.5 order, JIT, garbage collection and shared resident tiles prevent a cold standalone benchmark claim. Union cost includes D, its primary route, shared preparation and reselection; reselection time alone is not a union runtime.

Exact source-copy checks preserve nine preparation/verifier method bodies. Synthetic controls test filtered-noise scaling, missing groups, third-frame evidence, exact median updates and borrowed-row ownership; all four native fields match the actual library and accepted upstream stack/mask/track products. The frozen execution covers 65 experiment artifacts, 757 production/build/test artifacts and 161 original images. Accepted replay checks, the separate confirmation and publication validation retain their own manifests. Production code/libraries, saved settings, original image files and reports are unchanged; production test suites are not rerun for this research/documentation-only work.

Retain the current gates/defaults. Temporal-bin proposals merit further investigation as a complement for nearby movers, with native-source attribution, independent observing examples and an agreed engine/application contract before integration. Retain the concrete side-lobe counterexamples for joint proposal/selection tests; do not infer either universal necessity or redundancy from conditional rejection counts. The lower-threshold temporal route needs coverage/load work, and frame-point linking needs proposal-density/cost work before broader scientific comparison. This is a bounded SM-F7 milestone; broader SM-F1/SM-F3 uncertainty/support and SM-F6 validation remain open.

Heinze, Metchev and Trollo's [digital-tracking study](https://arxiv.org/html/1508.01599) distinguishes sensitivity from checks in independent image subsets. The time bins here generate additional proposals with missing bins allowed; they are not a universal confirmation veto. The [collection inventory](README.md) preserves protocols, numerical evidence, bounded input pixels, figures and portable hashes. Full image/proposal caches and executable diagnostics remain external under repository policy.
