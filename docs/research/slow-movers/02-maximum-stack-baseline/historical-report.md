# SM-F3 investigation and row-cache validation

Historical investigation snapshot. Statements about implementation, pending work or publication describe the experiment at its date. Current behavior is documented in [ALGORITHM.md](../../../../ALGORITHM.md); current decisions remain in [FEATURE-REQUESTS.md](../../../../FEATURE-REQUESTS.md). Local artifact names in the snapshot are not promises that every external cache or harness is bundled. See this study's [inventory](README.md).

This scratch directory holds diagnostics, copied runtime JARs and report outputs. No diagnostic program is part of a repository. Original images are read-only; positive controls modify independent tile copies only. Dataset paths in scripts are machine-local and must not be published.

## Frozen comparisons

- Baseline: SpacePixels `c3cdd0a9b08d60af5027ea220b5622f55a3d2c6a`, JTransient `8c0134fdab99ecf2f0f5b9212f353b13dde8c858`. JAR snapshot in `baseline-lib`; shipped profile in `profile.json`.
- Five complete aligned sessions: Eagle-noisy, Klangwolke-2, M81 Kumar, helix-reg and m101-reg. `run-studies.py` runs them one at a time. Photometry enabled, experimental faint search disabled, no Auto-Tune, no report export. All retained frames and full image dimensions are used.
- `Smf3Study` calls the ordinary application import/preparation and pipeline. Frame indices are mapped from the actual QC rejection list and checked against the retained count. Baseline results, stack/mask hashes and every maximum/residual component's geometry are retained.
- Widened shape experiment: only the maximum axis limit is raised to 5. Other shipped thresholds are held fixed. This is an experimental axis limit, not a new default.
- P1: projected Pearson coefficient against actual retained capture times, falling back to sequence spacing only if times are absent. The proposed absolute 0.7 limit and 199 deterministic timestamp permutations are diagnostic counters. These are not calibrated global false-alarm probabilities; candidate selection, repeated testing, registration and seeing trends remain concerns.
- P3: form `max - median` exactly in signed-int arithmetic, then encode the non-negative difference for extraction. The original median mask and other thresholds are unchanged. Raw-frame versus signed, unclipped median-subtracted frame evidence are evaluated separately. The copied `ProbeFrameEvidence` is checked candidate by candidate against the production support counts, usable counts, stationary score and availability before residual experiments. It does not change production science.
- P4: original reduction, cached source rows and loop interchange, one untimed warmup and three rotating-order repetitions. All full-size pixels are compared after each timed interval. These are **serial isolated benchmarks**: outside an engine operation, WorkerPool executes on the caller. They measure stack work, not pipeline speedup. The loop-interchange prototype was inconsistent across image sizes and was not adopted.

## Positive and negative controls

`Smf3Injections` reads three predeclared 192-pixel tiles at fractional field positions (1/4, 1/4), (1/2, 1/2), (3/4, 3/4), using every retained frame through the FITS tiler. It preserves actual time spacing and never writes image data.

- Gaussian PSF sigma 1.25 px, 0.35-radian straight path, total motion 0, 2, 4, 6, 10 or 16 px. Nominal background-only aperture SNR 10 and 20 uses the first tile frame's clipped background noise, a radius-2.5-pixel aperture and no source shot noise. It is a controlled injection amplitude, not measured/calibrated source SNR.
- 150 moving cases (5 sessions x 3 positions x 2 amplitudes x 5 nonzero motions), 30 stationary-source controls, 15 uninjected tile controls. A result is associated with the injection only if supported centroids have RMS at most 2 px from the known timed path with at least 3 and at least 40% of all frames. Existing production gates still apply. The same crops and injection pixels are shared across variants.
- This small grid does not measure survey completeness or a false-positive rate. It lacks independent labelled movers in the other fields, curved paths, reversed motion, independent observing nights, a broad PSF/brightness sweep and many randomized trajectories. Residual normalization and PSF changes can create time-correlated artifacts.
- Known catalogue paths are consulted only after the full-field blind extraction. `check-known-tracks.py` confirms existing ordinary tracking for Prokne (12 points) and Valeria (27 points), not just spatial proximity to a stack candidate. None of the three approximately magnitude-18.5 targets is recovered by the frozen maximum-stack variants.

## Reproduction

Compile `ProbeFrameEvidence.java`, `Smf3Study.java` and `Smf3Injections.java` with Java 21 and `baseline-lib/*`, keeping classes under `classes`. `build-probe.py` regenerates the instrumented scratch copy from the current unchanged frame-evidence source. Use scratch `user.home`, never the real settings.

1. `run-studies.py`, `summarize.py`.
2. `run-injections.py`, `summarize-injections.py`, `check-known-tracks.py`.
3. After building JTransient, place its replacement JAR into `final-lib`, preserving every other baseline JAR. `prepare-regression.py` / `regression.py` compare all 18 reference reports against the F26 acceptance outputs, normalizing only previously enumerated timing/generation fields.

`summary.json`, `injection-summary.json`, `known-paths.json` and `gaussian-maxima.json` retain numeric evidence. Gaussian maxima use numerical Simpson integration of the exact independent-normal maximum density, validated at N=1 and N=2; at N=30 the expectation is 2.042760844 sigma, median 1.998337679 sigma. The old sqrt(2 log N) value 2.608140097 is only a leading large-N term.

The only proposed production edit from these experiments is P4 source-row caching, preserving the same arithmetic, reduction order, row scheduler, cancellation checks and output ownership. P1/P2/P3 and all detector settings stay experimental pending broader scientific validation and a duplicate-ownership design.
