# SM-F2 full-field experiments, 2026-11-03

Historical investigation snapshot. Statements about implementation, pending work or publication describe the experiment at its date. Current behavior is documented in [ALGORITHM.md](../../../../ALGORITHM.md); current decisions remain in [FEATURE-REQUESTS.md](../../../../FEATURE-REQUESTS.md). Local artifact names in the snapshot are not promises that every external cache or harness is bundled. See this study's [inventory](README.md).

The maintained request, results and pending decisions are in JTransient's `FEATURE-REQUESTS.md`, SM-F2. SpacePixels links to it. Production integration is not implemented.

## Results

All three proposal mechanisms recovered the three predicted magnitude-18.5 asteroids after blind searches of the full valid 60.7-megapixel field. Digital tracking and temporal-bin proposals recovered both further known asteroids near magnitude 19; frame-point proposals recovered one. All use shared preprocessing and verification, so agreement is correlated evidence.

| Mechanism | Real faint targets | Positive trajectories | Injected sources | Three shuffled-time positive control counts |
|---|---:|---:|---:|---|
| Digital tracking | 3/3 | 15 | 12/16 | 4, 2, 2 |
| Temporal proposals | 3/3 | 9 | 10/16 | 5, 2, 3 |
| Frame-point proposals | 3/3 | 10 | 11/16 | 5, 2, 3 |

An exploratory comparison with a stationary alternative retains every real-data trajectory in this table and the injection recoveries, while reducing each method's shuffled positive counts to 0, 1, 0. Correctly ordered negative survivors remain 59, 99 and 85. The comparison was chosen after inspecting control tails. These are not calibrated operational false-alarm rates. Unmatched positive candidates are unverified.

The small injection experiment used four sources at each reference peak-to-pixel-noise ratio 0.5, 0.8, 1.2 and 1.8. Gaussian widths vary with the saved frame seeing; constant integrated flux is scaled by the saved ensemble zero points. Source Poisson noise is omitted. Only 16 examples on one background are tested. Direct recovery is 0/4, 4/4, 4/4, 4/4; temporal proposals 0/4, 2/4, 4/4, 4/4; frame points 0/4, 3/4, 4/4, 4/4. See `injected/failure_audit.json` for proposal/linking failure information.

Shared preparation takes 843 seconds and peaks at 498 MiB process RSS. Direct full-field search takes 52 seconds including I/O/startup and both polarity extrema. Positive bin generation takes about 20 seconds; positive point generation about 52 seconds plus about 20 seconds of seeding/linking. I/O and preprocessing are shared; these timings must not be presented as end-to-end production benchmarks. The cache uses about 20 GB excluding the virtual environment and duplicate hardlinks. Process RSS excludes the OS file cache.

## Reproducibility and artifacts

- `inputs.json` contains the private local source locations. `metadata.json` is the earlier immutable inventory and original frame hashes. `frames.json` supplies only frame metadata, kept identities, times and scales to the search; `truth.json` is read only by post-search evaluation.
- `experiment_config.json` freezes the exploratory search parameters. Every complete field pass covers 247 cores with filter/motion margins. `tile_manifest.json` records them; `tiles/` holds filtered, empirically normalised residual evidence and validity masks. The files are research caches, not an engine persistence design.
- `full_prepare.py` prepares the field; `search_core.py` contains the CPU kernels and scalar/synthetic checks. `full_search.py` runs the blind grid with real or shuffled times, accumulating positive and negative extrema.
- `extract_all.py` builds every temporal-bin and frame-point proposal list. `link_proposals.py` generates/refines point trajectories without ephemeris inputs. `verify_all.py` applies the common complete-sequence verifier and preserves failed proposals and reasons.
- `search_*/candidates.json`, `candidates_*.json`, `links_*.json` and `verified_*.json` preserve all stages, polarity/time controls and measured resources. A coarse search keeps the best motion per reference pixel before spatial nonmaximum suppression; proposals are consolidated in phase space. No arbitrary top-N candidate cap is applied, but these consolidation rules can lose overlapping/ambiguous trajectories and are not a completeness proof.
- `stationary_diagnostic.py` preserves the original outputs and writes `stationary_*.json` separately. Its threshold is explicitly exploratory. `evaluate.py` matches to predictions/injections only after the candidate files have been written. `evaluation.json` and `evaluation_static.json` contain the final associations.
- `prepare_injected.py` adds artificial sources before rebuilding the reference/noise model. A zero-injection recomputation is exactly equal to the original prepared data. Unchanged injected-variant tiles are hardlinks to the baseline cache; changed tiles are separate files. All variants run the full field, not only injection regions.
- `profile_extraction.py` measures ordinary positive-only proposal stages, with an exact count comparison against the original extraction. `extraction_profile.json`, `performance_summary.json`, `preparation_result.json` and the search/link/verification summaries preserve the timings.
- `final_checks.json` verifies all 33 original FITS SHA256 values are unchanged and records package versions. The compiled accumulator agrees with its scalar reference, and trajectory refinement passes a moving-source check. An initial JIT heterogeneous-tuple typing failure was corrected before verification results were emitted; `followup_first_failure.json` preserves the failure status, and the successful verifier outputs supersede that failed attempt.
- `full_field_results.png` and `.svg` show field coverage/candidates and the injection comparison. No observing-site metadata is included. `plot_results.py` regenerates them from the completed candidate files.

Use a fresh scratch directory when repeating preparation or changing parameters: unchanged injected tiles share baseline files by hardlink, so overwriting baseline caches would invalidate those associations. Copy the scripts and frozen input inventories first. Run preparation, each search model, proposal extraction, linking and verification before evaluation. The serial controllers demonstrate the order but their completion markers are specific to this run.

## Boundaries and next decisions

No original frame/report/catalogue, saved user settings, production detector, application dependency or default was changed. The NumPy/SciPy/Numba prototypes and their isolated virtual environment live outside the repositories. Engine/application unit suites were not run because production code did not change; numerical kernel checks, real-data controls, repeated proposal-count checks and source integrity checks validate these experiments.

Digital tracking is the strongest sensitivity starting point in these tests. Temporal proposals are cheaper but can miss or poorly extrapolate weak seeds. Frame proposals have a large candidate burden and require a real comparison with the existing engine linker. Before integration, validate motion versus stationary structure, morphology/camera-fixed/common-motion checks, result provenance/deduplication, edges/cancellation, resource ownership and other fields. Neither the initial coherence cutoff nor the exploratory stationary cutoff is a production recommendation.
