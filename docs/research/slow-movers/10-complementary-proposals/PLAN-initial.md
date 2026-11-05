# SM-F7 complementary proposals: frozen study protocol

Dated investigation snapshot. Current behavior remains in [ALGORITHM.md](../../../../ALGORITHM.md) and pending decisions in [FEATURE-REQUESTS.md](../../../../FEATURE-REQUESTS.md). Local executable/cache names describe external diagnostics; see the [retained inventory](README.md).

Prepared 2026-11-05, before new proposal/recovery results. Previous report changes are committed and pushed at SpacePixels 308ffd1; JTransient production remains 92fd212 and JPlateSolve 1383df6.

## Scope and decision

Investigate temporal-bin proposals first, then PSF-filtered frame-point proposals separately. Both use the unchanged current full-sequence refinement and selection rules. Do not introduce a production mechanism, change a scientific threshold/default, use a catalogue to generate paths, or call catalogue compatibility a confirmed identity.

Use the previously examined twelve K2 encounters only to diagnose proposal loss versus shared-verifier loss. Do not count them as independent validation or select thresholds from them. Diagnose exact-path score/support and the production refinement result, preserving convergence onto other sources and every later rejection.

## Methods fixed before the fresh cases

- Baseline D: exact current digital-tracking proposal search, coarse keep fraction 0.7, pixel/motion grid, radius-six maxima, original fine refinement, score/halves/stationary/camera/duplicate/track/side-lobe rules and candidate ceiling.
- Primary T4: three consecutive groups of searched frames, divided by frame count. Form unshifted sums of the already PSF-filtered production residuals; use a robust empirical median/MAD scale per group. Extract local maxima at score 4, with maximum radius round(FWHM), minimum one pixel. Propose from any two groups, including the outer pair; no requirement to detect a third group. Preserve the generating group/peak identities.
- Sensitivity/load diagnostic T3: identical to T4 at proposal threshold 3. This is predeclared, not a threshold selected from validation.
- Primary P3: extract maxima from each production PSF-filtered residual, using that filtered frame's empirical median/MAD scale, threshold 3 and the same FWHM-sized maxima radius. Form paths from pairs separated by at least one third of the session and require a point within one FWHM in one additional distinct frame. Missing other frames/groups are allowed. Preserve the contributing frame identities.
- Sensitivity/load diagnostic P2.5: identical to P3 at threshold 2.5. Existing raw-frame extraction sigma values are not reused.

Each non-digital proposal must start inside the same reference core, with the same reference-pixel bad mask and admitted speed interval as D. Quantize the pair's velocity to its nearest admitted production coarse motion, then apply the original fine refinement and checks. Merge only identical integer-position/coarse-motion seeds; retain all generating provenance. Report rejected pairs and proposal absence separately from refinement/selection rejections.

Report D, each standalone route, and unions D+T4 and D+P3 passed through the same complete final duplicate/track/side-lobe selection. Added proposals can also cause losses through competition; report gains and losses separately. Counts of repeated detections are not added source recovery.

## Fresh conditional comparison

Datasets: Apophis, Africano, dc-reg and m5-reg. They were not in the six-field SM-F6 digital-validation grid, but are existing observations previously used for other checks. They are not new observing nights.

Use two production-grid cores nearest fixed fractions (0.24,0.29) and (0.71,0.62), retaining actual edge dimensions and validity masks. Do not relocate a tile after seeing recovery. Search the full admitted production speed interval up to 1 px/min; keep frame/time/domain omissions explicit.

For each field: Gaussian and Moffat beta 3.5 profiles; nominal aperture signal/noise 1.75, 2.75 and 4.5; steady visibility or the middle searched-frame third absent. This gives 12 batches per field, 48 planned batches and 192 planned moving histories (four per batch).

Each batch also contains a stationary source and a one-frame flash, giving 96 planned injected control histories. Source centers use new deterministic case seeds around the fixed 0.31/0.69 core fractions. Four speeds use admitted-range fractions 0.17,0.39,0.64,0.86 and directions 0.22,1.78,3.47,5.31 radians. Inject into searched frames only. Fixed geometric checks prevent artificial sources overlapping each other within ten FWHM; native stars/blends are not excluded. If valid geometry cannot be constructed, retain the reason and unavailable truth instead of choosing a better result.

Seeing varies sinusoidally by 20%, integrated flux stays constant, and each exposure is integrated with seven time samples. Source photon noise is omitted because a consistent calibrated gain is not available. Signal/noise labels describe the injection construction, not calibrated magnitudes or output detection probabilities.

Upstream retained-frame decisions, measured seeing, registrations, ordinary tracks/streaks and data area are held at the admitted native values. Rebuild the exact median wherever injected samples change. This is conditional proposal/verifier validation, not a full-pipeline completeness test; an injected bright source might otherwise become an ordinary track.

Native controls: original pixels, negative preprocessed residuals, and three midpoint-clock permutations (seeds 2026110561, 2026110563, 2026110567). Retimestamp ordinary-track points consistently by original frame identity. Compare all routes on identical controls/domains. These are correlated controls on existing observations, not operational survey false-alarm rates.

Truth is read by evaluation/oracle diagnostics only after blind proposals and candidates have been saved. Source association stays at trajectory RMS <= one FWHM and velocity error <= 0.1 px/min on active searched frames. Flash attribution uses spatial contact at the active frame, as in SM-F6. Retain all ambiguous matches and native-before-injection associations.

## Resource and validity conditions

One dataset JVM at a time; isolated user.home; no GUI/preferences use. Shared native preprocessing/cache ownership is explicit and immutable. Record shared preparation separately from each warm proposal/verifier time; do not compare a hot prototype with cold baseline preparation as an end-to-end speedup.

The original 600-second search budget and 1000 selected-candidate ceiling remain. A prototype additionally stops at 50,000 distinct seeds per core as a resource boundary. Any time/seed/candidate ceiling, invalid input, unavailable truth or missing review evidence is recorded; incomplete coverage is not included as a clean sensitivity failure/success. No arbitrary top-N scientific ranking is introduced.

Synthetic checks: scalar score/scale, missing middle group, false pair motion, filtered noise, exact median updates, row ownership, clock permutation and point support. Before the fresh comparison require exact candidate/diagnostic/non-time-counter parity between D and the actual library on controls and a native core. Hash all production sources, inputs/configuration and experiment executables before validation.

## Evidence and handoff

Record per-route coverage, proposals, verifier failures, post-selection reasons, source recovery/ambiguity, incremental recovery versus D, control candidate load, runtime and memory. Preserve exact-path diagnostic scores/support for losses. Inspect native pixel/score evidence for any claimed extra source; confirm promising gains with an end-to-end rerun before discussing integration.

Retain existing gates/defaults unless evidence supports a concrete change. Integration still requires a demonstrated useful benefit and agreement on the engine/application contract. A negative or mixed comparison is a valid outcome. Update the existing algorithm/backlog and preserve a dated evidence collection without rewriting the nine completed study snapshots.

Background reference: Heinze, Metchev and Trollo (2015), https://arxiv.org/html/1508.01599, distinguishes digital-tracking sensitivity from checks in subsets. Here subset stacks generate proposals; they are not adopted as a universal confirmation veto.
