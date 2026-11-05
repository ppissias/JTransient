# SM-F3 held-out broader-shape validation

Historical investigation snapshot. Statements about implementation, pending work or publication describe the experiment at its date. Current behavior is documented in [ALGORITHM.md](../../../../ALGORITHM.md); current decisions remain in [FEATURE-REQUESTS.md](../../../../FEATURE-REQUESTS.md). Local artifact names in the snapshot are not promises that every external cache or harness is bundled. See this study's [inventory](README.md).

Fixed comparison: maximum geometric axis ratio 2.8 versus 5; all other science settings preserved.

## Paired injection recovery

| Mode | Evaluated | 2.8 | 5 | Excluded |
|---|---:|---:|---:|---:|
| blank | 26 | 0 | 0 | 0 |
| curved | 416 | 58 | 86 | 0 |
| flash | 104 | 0 | 0 | 0 |
| gapped | 416 | 56 | 117 | 0 |
| linear | 1248 | 257 | 467 | 0 |
| shuffled | 1248 | 201 | 554 | 0 |
| stationary | 104 | 0 | 0 | 0 |
| stationary-width | 104 | 0 | 0 | 0 |

Counts above are injection-associated outcomes, not confirmed astronomical objects. Flash association requires three active frames and cannot pass by construction; inspect full/nearby load separately. Blank tiles are not certified empty. Shuffled-position controls test a temporal failure mode, not a survey false-positive rate.

## Complete native fields

| Dataset | Held out | 2.8 | 5 | Added |
|---|---|---:|---:|---:|
| klangwolke-1 | True | 1 | 2 | 1 |
| Iwamoto-huge | True | 0 | 0 | 0 |
| whynot-nocalibration-wrongdrift | True | 3 | 5 | 2 |
| Apophis | True | 0 | 1 | 1 |
| Africano | True | 2 | 2 | 0 |
| dc-reg | True | 0 | 0 | 0 |
| ic-5070-reg | True | 4 | 4 | 0 |
| m31-b-reg | True | 0 | 0 | 0 |
| m31-g-reg | True | 0 | 0 | 0 |
| m31-red-reg | True | 0 | 0 | 0 |
| m5-reg | True | 8 | 8 | 0 |
| m81-reg | True | 0 | 0 | 0 |
| UG 6524-kumar-L-dataset-registeed | True | 0 | 0 | 0 |
| Eagle-noisy | False | 13 | 14 | 1 |
| M81-kumar-L-dataset-registered | False | 0 | 0 | 0 |
| helix-reg | False | 4 | 4 | 0 |
| klangwolke-2 | False | 1 | 2 | 1 |
| m101-reg | False | 0 | 0 | 0 |

## Added full-field review

| Dataset / candidate | Axis ratio | Supported | Time coverage | Track comparisons |
|---|---:|---:|---:|---|
| klangwolke-1 / 0 | 4.192 | 7 | 100.0% | T2: spatial only |
| whynot-nocalibration-wrongdrift / 1 | 4.464 | 59 | 100.0% | No spatial correspondence |
| whynot-nocalibration-wrongdrift / 2 | 4.424 | 59 | 100.0% | No spatial correspondence |
| Apophis / 0 | 3.923 | 7 | 76.8% | No spatial correspondence |
| Eagle-noisy / 5 | 4.245 | 36 | 100.0% | No spatial correspondence |
| klangwolke-2 / 1 | 2.853 | 28 | 100.0% | T16: 27/28 common, RMS 0.146px, max 0.302px |

## Interpretation limits

These are prescribed injections into retained native background tiles from 13 datasets not used in prior SM-F3 development. They do not establish survey completeness, astrometric uncertainty or significance. M31 bands share a target; bootstrap groups them together. The interval describes variation in this selected grid and target grouping, not a universal gain. Gaussian/Moffat templates, two nominal amplitudes and no added source photon noise or exposure-integrated trail leave acquisition realism incomplete. Full-frame results retain the actual production data area; tiles deliberately probe a different local extraction domain.

No automatic duplicate suppression, temporal-correlation veto, residual route, new setting or default change was applied. No new reports were generated. Original image integrity, baseline science/stack reproduction and experiment artifact hashes are checked separately.
