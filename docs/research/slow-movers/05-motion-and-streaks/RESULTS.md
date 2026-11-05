# SM-F3 frozen motion and streak-contact study

Historical investigation snapshot. Statements about implementation, pending work or publication describe the experiment at its date. Current behavior is documented in [ALGORITHM.md](../../../../ALGORITHM.md); current decisions remain in [FEATURE-REQUESTS.md](../../../../FEATURE-REQUESTS.md). Local artifact names in the snapshot are not promises that every external cache or harness is bundled. See this study's [inventory](README.md).

No review threshold changes production detection. Counterfactual rejections are reported, including losses.

| Cohort / mode | Evaluated | Max-stack associated | Strict motion-compatible | Temporal-veto retained | Camera ambiguous | Timing unavailable |
|---|---:|---:|---:|---:|---:|---:|
| development / blank | 10 | 0 | 0 | 0 | 0 | 0 |
| development / blend | 80 | 1 | 1 | 1 | 0 | 0 |
| development / camera-or-drift | 24 | 2 | 0 | 0 | 0 | 0 |
| development / curved | 160 | 36 | 33 | 33 | 0 | 0 |
| development / gapped | 80 | 26 | 18 | 18 | 0 | 0 |
| development / linear | 360 | 161 | 155 | 157 | 0 | 0 |
| development / moving-streak | 160 | 0 | 0 | 0 | 0 | 0 |
| development / shuffled | 160 | 83 | 2 | 4 | 0 | 0 |
| development / stationary | 40 | 0 | 0 | 0 | 0 | 0 |
| development / stationary-streak | 80 | 0 | 0 | 0 | 0 | 0 |
| development / stationary-width | 40 | 0 | 0 | 0 | 0 | 0 |
| heldout / blank | 24 | 0 | 0 | 0 | 0 | 0 |
| heldout / blend | 192 | 0 | 0 | 0 | 0 | 0 |
| heldout / camera-or-drift | 84 | 2 | 0 | 2 | 0 | 0 |
| heldout / curved | 384 | 123 | 98 | 112 | 0 | 18 |
| heldout / gapped | 192 | 76 | 45 | 61 | 0 | 16 |
| heldout / linear | 864 | 474 | 442 | 468 | 0 | 34 |
| heldout / moving-streak | 384 | 1 | 1 | 1 | 0 | 0 |
| heldout / shuffled | 384 | 239 | 2 | 23 | 0 | 13 |
| heldout / stationary | 96 | 0 | 0 | 0 | 0 | 0 |
| heldout / stationary-streak | 192 | 0 | 0 | 0 | 0 | 0 |
| heldout / stationary-width | 96 | 0 | 0 | 0 | 0 | 0 |

Camera/drift-degenerate histories are ambiguity controls, not independent physical labels. Blank backgrounds are not certified empty. Fresh source locations/seeds on existing observations do not establish performance on independent nights or fields. Tolerances are empirical review scales, not significance or calibrated astrometric uncertainty. See frozen PLAN.md for the predeclared rule and association conditions.
