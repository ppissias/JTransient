# Faint-mover paths beside stars (SM-F10)

**Decision (made).** Restore the local-background faint-mover star mask published in JTransient 1.1.1 under version 1.1.2. The maintainer accepted losing (11579) Tsujitsuka from the blind Klangwolke-2 report. It is a real asteroid, and the maintainer judged that light leaking outside the tile-only mask likely tipped its detection score above the cutoff. The reduction in false paths on Andromeda and Austral Kiwi carried more weight than that marginal recovery. The pixel replay measures the effect of masking those residuals. The asteroid and star contributions to the overlapping pixels remain unresolved.

The local rule was introduced in [`e7837da`](https://github.com/ppissias/JTransient/commit/e7837da) and briefly rolled back in [`935340c`](https://github.com/ppissias/JTransient/commit/935340c) to recover FM10. The restored implementation keeps the tile-sky rule, adds cores above the clipped local 41 × 41 px background and uses 3 px growth. See the [current algorithm](../../../../ALGORITHM.md#faint-mover-search). The registration quality check and minimum-displacement refinement fix were present in the published 1.1.1 sources and are included in 1.1.2.

## How the investigation started

On November 8, a 32-frame, three-hour sequence on a 1.4 MP sensor yielded 131 faint-mover candidates, about 90 per megapixel. After the few-frame check, 82 remained. Of those, 95% lay within 5 px of the star mask, 73% overlapped it, and 56% shared a direction and a rate of about 20 px over the session. The median path spent 97% of its frames within 1.5 FWHM of the star mask. At least 80% of frames were that close for 76% of the candidates and 7% of random equal-velocity paths. The stationary comparison had little signal because the relevant stars were masked in the stationary stack. An earlier blanket expansion lost three of twelve injected movers crossing bright stars. [Related frame-based study](../../../../FEATURE-REQUESTS.md#sm-f8-frame-check-for-runs-of-leaky-frames)

On November 20, all 21 Austral Kiwi candidates shared a time profile with stronger contributions early and late in the session. Eleven followed bright-star mask edges, six crossed faint unmasked stars, and two lay at the data-area edge. Gradient and nebulosity broadened the tile sky estimate and raised the star threshold by factors of two to four. The follow-up also identified registration creep as a contributor in wide-field edge regions.

## Matched full-session results

The November 21 paired runs use the same saved profiles and frame rejection on both sides. The score cutoff was 8.0 and the minimum displacement was 2 FWHM. A separate B1m test at 4 FWHM checked that fine refinement respects the configured floor.

| Field | Tile-only mask | Local-background mask | Paired result |
|---|---:|---:|---|
| Andromeda | 33 | 15 | 10 paths matched, 23 dropped and 5 appeared. |
| Austral Kiwi | 21 | 4 | All four surviving paths matched. |
| Klangwolke-2 | 10 | 9 | The nine other catalogue-matched faint movers were recovered. FM10 was the loss. |
| B1m | 30 | 30 | All 30 matched by path. |
| Flame | 2 | 2 | Both matched by path. |
| M101 | 1 | 1 | The candidate matched by path. |

Frame review covered all 40 paths added by the tile-only mask on Andromeda and Kiwi. Changing light in fixed-star wings reached beyond their masks and added coherently along many fitted paths. The [paired run records](paired-full-runs.json) include the unmatched paths and search times. The [sorted trajectories](field-paths.json.gz) preserve the 54 reported paths in the two tile-only runs, their FWHM lengths and membership in the review. Candidate IDs are local to the saved run.

## Tsujitsuka (Klangwolke-2 FM10)

The fitted trajectory traveled 9.91 px (3.78 FWHM) over 74.7 minutes and agreed with the saved ephemeris to 0.63 px RMS in 28 retained exposures. SkyBoT placed the prediction 2.1 arcsec from the reported midpoint. The last 14 exposures show a compact motion-aligned source with aperture signal-to-noise of 9.0, after the object has moved away from a merged two-star mask. The fitted start is about 3 px from the measured center of the nearest star (Gaia G 16.67). The surrounding diffuse reference background is nearly flat across the fitted track. The tile-only mask grew 36 threshold-selected pixels into a connected 123-pixel region around the pair. It covered about 97% of the sky-subtracted reference counts within 6 px of either star center. Faint wings and nearby sources remained outside it.

Both masks covered the same six fitted sample centers. The local mask also zeroed nearby pixels used by the Gaussian filter. Two pixels near the mask edge, at registered coordinates (5284, 3407) and (5283, 3408), accounted for about 90% of the 8.08-to-7.19 score drop. The additional mask affected the first part of the track. Later contributions were almost identical. Swapping only the locally weighted reference scored 7.96, swapping only its recomputed star mask scored 7.26, and swapping both scored 7.12. The detailed replay reproduced the original tile-mask path signal to within 0.000001 per frame.

- [Fitted path, stars and the two contributing pixels](01-path-and-star-masks.png)
- [Per-exposure scores and the weighted-reference comparison](02-score-by-exposure.png)
- [Registered frames, residuals and motion-aligned stacks](03-registered-frames-and-stacks.png)
- [Tile-only mask examined before the restoration](04-current-star-mask.png) and [what its Gaussian filter received](05-current-mask-residuals.png)
- [Per-frame measurements and replay scores](tsujitsuka-measurements.json)

In these figures, “current mask” means the tile-only mask used at the time of the comparison.

## Other approaches tested

| Trial | Result |
|---|---|
| Growth of 2 px for extra local cores | A full Kiwi run reported 11 candidates, between 4 for the restored 3 px mask and 21 for the tile-only mask. Tsujitsuka scored 8.083 in a targeted tile probe. |
| Fitting light from fixed stars | Stationary-source fits weakened several Kiwi paths and also reduced Tsujitsuka's diagnostic signal. A per-frame 5 × 5 star-shape correction moved 18 of 25 Andromeda paths and 4 of 8 Kiwi paths below cutoff on recalibrated tiles. It strengthened one Kiwi false path from 8.25 to 12.34 and dropped Tsujitsuka to 7.80. |
| Locally weighted reference | Local sky matching and inverse-variance weighting brought 25 of 33 saved Andromeda paths and 16 of 21 Kiwi paths below 8.0. Blind searches on one tile per field went from 8 to 4 and 5 to 5 at the original cutoff. At the recalibrated cutoffs the counts were 10 and 13. The test lost Tsujitsuka and recovered (49264) 1998 UC in the Flame field at both cutoffs. |
| Local seed and grow thresholds for the ordinary master star map | Auto-Tune across six paired crops gave lower estimated false-detection rates in three and higher rates in three. Mean injected-source recovery fell from 58.2% to 56.6%. In Kiwi nebulosity the estimated false rate rose from 0.99 to 2.73 per megapixel per frame. This test measured the ordinary master veto, which is separate from the faint-mover mask. |
| Measured per-frame PSF | An elliptical Gaussian fitted to nearby stars put Tsujitsuka at 7.65 on the stock score scale. Using its elongation at the original width gave 8.02. All six sampled Andromeda and Kiwi false paths stayed above cutoff. |
| Gaussian widths, Moffat profiles and compensated Gaussians | Narrower Gaussians raised Tsujitsuka from 8.08 to 8.36 and the Flame asteroid from 12.06 to 12.50 in blind tile searches with the tile-only mask. All ten profiles failed to improve the median calibrated score across six held-out known movers. A 25% wider Gaussian with the local mask readmitted one of the false paths on two Andromeda tiles and left the two Kiwi tiles at one candidate. With the local mask all ten profiles missed Tsujitsuka at both tested cutoffs. |
| Lower-score blind search with support in both exposure halves | Recovered Tsujitsuka and added two unmatched Klangwolke-2 candidates at 7.53 and 7.28. Both had aperture signal-to-noise below 5 in each half and remain unconfirmed. Kiwi rose from 4 to 8, with no catalogue matches among the additions. |
| Forcing saved prediction paths to magnitude 21 | Of 55 in-frame tracks sampled in 28 Klangwolke-2 frames, 11 scored at least 8.0 and all were already in the blind report. The highest with no blind counterpart was (44728) 1999 TT15 at 6.92. Tsujitsuka scored 7.51 on its predicted trajectory and 8.08 on its fitted path. |

## Evidence to reuse

The local-background mask has the strongest matched full-session evidence. Selected-tile replays and Auto-Tune crops address narrower questions. The blind tile harness lacked the full-session track, streak and camera veto inputs. Near-star recovery remains uncertain for short, faint tracks. If the case is revisited, compare the original 3 px and extra-core 2 px growth on paired full sessions, keeping the same inputs, score cutoff and motion grid. Compare named-mover recovery and performance as well as candidate counts, and include the existing bright-star crossing injections and similarly faint paths away from masks.

The earlier idea of vetoing paths within about 1.5 FWHM of a star mask for at least 80% of their frames remains a historical proposal. It needs separate recovery evidence for short paths and for real movers crossing stars.
