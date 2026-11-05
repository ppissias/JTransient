# Joint SM-F1/SM-F3 first controlled milestone: conclusion

Historical investigation snapshot. Statements about implementation, pending work or publication describe the experiment at its date. Current behavior is documented in [ALGORITHM.md](../../../../ALGORITHM.md); current decisions remain in [FEATURE-REQUESTS.md](../../../../FEATURE-REQUESTS.md). Local artifact names in the snapshot are not promises that every external cache or harness is bundled. See this study's [inventory](README.md).

Completed the predeclared 19-policy mechanism study: 2052 cases planned on18 existing observations;1910 evaluated. Native missing-exposure timing excludes114 cases and28 camera paths leave their fixed crops. Two new fixed tile locations and new source histories, subpixels, fluxes and seeds were used. These are existing observing sessions, not independent new nights or final validation of a selected policy. The five development sessions and other cohort are separate; the other cohort has12 evaluable datasets out of13 planned.

The new association definition requires3 active-frame positions,80% of reported positions in illuminated frames and2px RMS, without assuming40% support. Counts are injection-associated accepted histories, not confirmed physical motion, new astronomical discoveries, completeness or calibrated false-positive rates. Short or limited-span histories can be position-compatible without resolving motion or source dominance.

## Measured trade-offs

- Baseline other-cohort moving histories91/864. Removing the support floor gives156; a20% floor gives144. Gapped cases6/96 ->36/96 without support or34/96 at20%; sparse cases0/96 ->12/96 or2/96. Both relaxations increase candidate/control load. This data domain starts at9 frames and cannot validate the3-8-frame behavior.
- Upper axis5 gives148 moving histories but27/96 shuffled survivors versus8 currently. Removing upper axis and fill gives234 moving and38 shuffled histories. Trail crossings improve from0/96 to14 when removing upper axis alone and40 with the pair removal; fill removal alone still yields0. Neither blanket shape relaxation nor an unchanged construction policy can be called uniformly successful.
- Median-upper removal gives110 moving histories, stationarity removal104, and the pair130. Their shared purpose does not establish redundancy: pair removal admits19 associated stationary-round,16 elongated stationary and19 camera-fixed controls versus1/0/1 currently.
- SNR3 gains6 moving histories and loses18; grow2 gains47 and loses39. Seed3 gives no incremental associated recovery; minimum pixels5 gains6 and admits an elongated stationary control. All current defaults remain.

## Temporal-bin prototype

The three-bin median-residual fits are available on only23/91 baseline-associated other-cohort proposals. A fail-open empirical compatibility veto loses one correctly associated curved Moffat history (ic-5070 case103) and removes no associated shuffled history. Raw RMS .802px and bin-reference RMS .814px support source agreement, while the middle-point prediction error2.343px exceeds the fixed2px review tolerance. This prototype is not adopted. Requiring strict available compatibility leaves643/864 positive cases partly unassessed;28 observed associations cannot be interpreted as a complete recovery measurement. Withheld clocks disable bins and preserve all non-temporal selections in136 paired contrasts. This result does not show that every temporal-stack or trajectory method fails.

## Source attribution and targeted negative control

A post-hoc matched-blank diagnostic found no other-cohort moving-history association also present on its corresponding blank under the same non-temporal policy. That does not prove source dominance or motion. The primary flash-control ROI counts largely match repeated blank-background counts and therefore cannot themselves be attributed to the injected flash.

A separately frozen follow-up evaluates170/180 planned cases on the same immutable caches with brighter elongated flashes at nominal SNR24/60. Ten cases lack native exposure timing. Flash origin uses at least one position in the illuminated frame,80% active fraction and2px RMS there, intentionally distinct from a3-position moving history. Removing support admits30 attributed bright-flash cases in the other cohort; current40% and alternative20% admit zero of those particular flashes. Support therefore prevents an explicit failure mode, while its percentage is not established as optimal. This is a targeted mechanism check after the primary study, not independent final validation.

## Decision and remaining evidence

Keep production code, gates, default axis2.8, settings and reports unchanged. No scientific filter is justified for removal yet; the repeated analyzer size check is a structural duplicate with no demonstrated scientific benefit. Source separation, support/availability on faint/gapped paths, calibration of position errors and construction through overlaps remain important. The next decision work should include short sequences and absolute support-count alternatives without choosing a threshold from this reviewed grid, plus the broader SM-F6 candidate/control validation before SM-F7 comparisons. Full criteria and pending work are in the existing engine backlog; SM-F1/P1/P2/P3 remain open.

## Evidence and verification

PLAN.md and frozen-manifest.json preserve the35 original artifacts, compiled code, libraries, profile,744 source hashes and frame manifests. Every native tile/metadata cache was hashed before case execution. verify_study.py validates baseline parity (zero unassessed baseline components),71583 reviewed proposals, selection-set monotonicity, SNR position subsets,136 withheld-clock contrasts and all818 unchanged original-image hashes. Supplementary code/classes/plan and primary result preservation pass supplement-validation.json. Thirteen added local documentation links, numerical tables, exact three-document diff scope, whitespace and privacy pass. Runtime source/settings remain untouched; no redundant full unit/report suites were rerun.

Primary preparation/evaluation took36.8/282.8 seconds with2 processors and2g/1536m heap limits. Supplemental evaluation took38.7 seconds.99 late-run observations reached about579MiB working set, a partial observation rather than an all-run/per-policy peak. These are diagnostic workload observations, not production overhead measurements.

summary.json,paired-cases.csv,gain-loss-cases.json,background-audit.json and bright-flash/summary.json retain all policies/limitations. gate-comparison.png/svg and representative-cases.png/svg were inspected visually; the main figure was checked at original resolution. Three representative examples cover a curved-veto loss, a gapped25%-support history and an elongated stationary source admitted by paired removal. Figures contain pixel/reference coordinates, not observing-site metadata.

QA: analysis.log retains the initial missing-matplotlib exception after successful numeric outputs; plotting was completed with a pinned scratch-only plotting environment and plot manifests. Documentation verification corrected a draft statement about upper-axis-only trail recoveries to the actual14/96. No frozen cases, thresholds or primary numerical outputs were changed by either correction.

Tracked changes remain only JTransient ALGORITHM.md/FEATURE-REQUESTS.md and SpacePixels FEATURE-REQUESTS.md, extending the prior review documentation; unstaged/uncommitted. No branch, delegation, commit or push in this research turn. All owned evaluation/analysis/monitor sessions completed. JPlateSolve unchanged.
