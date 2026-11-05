# SM-F3: temporal diagnostics and track correspondence

Historical investigation snapshot. Statements about implementation, pending work or publication describe the experiment at its date. Current behavior is documented in [ALGORITHM.md](../../../../ALGORITHM.md); current decisions remain in [FEATURE-REQUESTS.md](../../../../FEATURE-REQUESTS.md). Local artifact names in the snapshot are not promises that every external cache or harness is bundled. See this study's [inventory](README.md).

Baseline is the preceding validated P4 state: source-row caching, no temporal rejection, maximum axis ratio 2.8. The pre-change runtime JARs and shipped profile are frozen in `baseline-lib` and `profile.json`. Inputs are existing scratch copies/hardlinks from the F25 acceptance task; original image data is read only. No user settings or original reports are changed.

## Authorized implementation

- Export copied supported aperture positions and an unweighted timed linear fit in the engine diagnostics. Do not add a detection gate, threshold, new setting or automatic duplicate removal. Missing, repeated or nonchronological times explicitly disable timed fitting; timestamps of zero remain real. Limited temporal coverage is reported, not extrapolated.
- Move the existing report footprint/track proximity measurement into JTransient, preserving the 3–10 pixel spatial screening. Add distances between supported centroids and detections on common original frame IDs, excluding repeated IDs and conflicting known timestamps. An overlap never claims source identity or suppresses either finding.
- Present these measurements in each slow-mover report card, with a position CSV retaining original and retained frame numbers. Spatial-only evidence remains explicit. Candidate counts, selection, order, morphology/support/stationarity values and all other branches must remain unchanged.

## Frozen broader controls (before execution)

Use Eagle-noisy, Klangwolke-2, M81 Kumar, helix-reg and m101-reg. Three fixed 192-pixel tiles per session are centred on quarter/half/three-quarter fractional field positions. Include every frame retained by the preceding full-field QC. Read tiles through the FITS tiler; injection modifies independent arrays only.

Gaussian PSF sigma is 1.25 or 2.0 pixels. Nominal background-only aperture SNR is 10 or 20, calibrated to the first tile frame's clipped noise and a radius-2.5-pixel aperture. This defines amplitude only; it is not a measured/calibrated source SNR. Preserve actual capture cadence. Test the existing raw maximum-stack route with an experimental maximum axis ratio of 5, and label the subset passing the current 2.8 limit separately. All other shipped thresholds are frozen.

- 540 straight moving cases: 15 backgrounds x 2 PSFs x 2 amplitudes x total displacements 4/10/16 px x directions -1.1/0.35/2.0 radians.
- 240 curved moving cases: same backgrounds/PSFs/amplitudes, endpoint displacements 10/16 px, normal midpoint bows 3/6 px, direction 0.35 radians.
- 120 time-permuted source controls: endpoint displacements 10/16 px, direction 0.35, independently fixed-seed permutations of the injection positions among frames. Real images/timestamps are unchanged. The injected PSF does not follow a chronological physical path.
- 60 stationary controls with changing PSF width: width varies sinusoidally by 30%, at fixed centre and approximately constant total injected flux.
- 15 uninjected background controls. These are not independently certified empty sky.

This is 975 cases total (780 moving, 195 controls). Associate a candidate with an injection only if supported centroids have RMS at most 2 px from the known per-frame injection locations, with at least three positions and at least 40% of all retained frames. Evaluate the proposed absolute correlation 0.70 only as a retrospective counter on associated recoveries; unavailable coefficients are not silently equated with r=0. No p-value or survey false-positive calibration is inferred. Record full candidate load separately from injection-associated recovery.

## Validation

Unit controls cover uneven capture spacing, missing/duplicate/decreasing times, zero timestamps, limited coverage, stationary and curved positions, sparse original frame IDs, reversed tracks, crossings, parallel tracks, partial support and ambiguous IDs/timestamps. Engine and application checks, API consumers and all 18 real-data report comparisons are required. Compare every old scientific field and asset, permitting only the new diagnostic fields, position CSVs, review panels and corrected track-overlap wording. No broad HTML normalization is allowed to hide unrelated changes.

## Completed results

All 975 controls completed. Current/widened recoveries are 116/201 straight and 48/51 curved; the proposed absolute correlation 0.70 keeps 116/201 and 47/50. All 21/48 injection-associated shuffled survivors are rejected by that proposed retrospective counter. Stationary/uninjected association counts are zero. The lost curved M81 example has r 0.69624, 31 supported centroids, 84% temporal span, fit RMS 1.44 px and truth-path RMS 1.67 px. No production cutoff is adopted or tuned to this example.

All 18 complete report comparisons passed: every legacy scientific field, report-data JSON, 5,310 old assets and unrelated HTML remain unchanged. Exactly 36 position CSVs/inspection panels are added and four old spatial-overlap notes gain cautious wording and common-frame statistics. CSV identities, timestamps and finite numeric values are verified; full maximum/median/mask hashes reproduce the five investigation baselines. All 818 original image hashes are unchanged.

Engine checks and standalone API consumer pass with 364 tests. Application checks, both API consumers, installation packaging and 46 report JavaScript tests pass; 763 application tests include seven existing skips. The private offline headless browser verifies the Prokne review at 1360/420-pixel widths, valid CSV/track links and zero JavaScript exceptions. The preview PNG is cropped above site/astrometry details. `validation.json`, `control-summary.json`, `browser-audit.json`, `acceptance.log` and individual comparison files retain the evidence. All settings-loading JVMs use scratch homes; no GUI preferences-changing harness was run. Code/docs remain uncommitted together with the preceding P4 changes.
