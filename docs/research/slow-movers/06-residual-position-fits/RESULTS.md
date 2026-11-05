# Candidate-local measurement study

Historical investigation snapshot. Statements about implementation, pending work or publication describe the experiment at its date. Current behavior is documented in [ALGORITHM.md](../../../../ALGORITHM.md); current decisions remain in [FEATURE-REQUESTS.md](../../../../FEATURE-REQUESTS.md). Local artifact names in the snapshot are not promises that every external cache or harness is bundled. See this study's [inventory](README.md).

Planned 2052; evaluated 1910; excluded 142.

Counts below compare measurement histories on the same pre-frame-gate proposals. They do not replace production frame/stationarity gates.

| Cohort / mode | Evaluated | Current aperture histories | Residual-fit histories | Gains / losses |
|---|---:|---:|---:|---:|
| development / blank | 10 | 0 | 0 | 0 / 0 |
| development / linear | 120 | 44 | 39 | 2 / 7 |
| development / curved | 40 | 9 | 9 | 1 / 1 |
| development / gapped | 40 | 10 | 8 | 1 / 3 |
| development / blend | 80 | 0 | 16 | 16 / 0 |
| development / moving-streak | 80 | 0 | 0 | 0 / 0 |
| development / shuffled | 80 | 30 | 28 | 1 / 3 |
| development / stationary | 40 | 0 | 0 | 0 / 0 |
| development / stationary-width | 40 | 1 | 0 | 0 / 1 |
| development / camera-or-drift | 24 | 1 | 0 | 0 / 1 |
| heldout / blank | 24 | 0 | 0 | 0 / 0 |
| heldout / linear | 288 | 159 | 157 | 11 / 13 |
| heldout / curved | 96 | 30 | 28 | 3 / 5 |
| heldout / gapped | 96 | 29 | 26 | 2 / 5 |
| heldout / blend | 192 | 3 | 75 | 75 / 3 |
| heldout / moving-streak | 192 | 0 | 0 | 0 / 0 |
| heldout / shuffled | 192 | 105 | 110 | 11 / 6 |
| heldout / stationary | 96 | 2 | 0 | 0 / 2 |
| heldout / stationary-width | 96 | 0 | 0 | 0 / 0 |
| heldout / camera-or-drift | 84 | 22 | 5 | 0 / 17 |

Exclusions: {"Camera path leaves the fixed source crop": 28, "Unavailable midpoint clock": 114}

Formal covariance is not calibrated astrometric uncertainty. See summary.json for unconditional active-position coverage on truth-covering geometry proposals.
