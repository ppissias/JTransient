# Feature requests and algorithm proposals

Proposed changes to what JTransient detects, measures or tunes. How the code is organised is in [DESIGN-IMPROVEMENTS.md](DESIGN-IMPROVEMENTS.md), and application features in SpacePixels' [feature requests](../SpacePixels/FEATURE-REQUESTS.md).

These are not authorisations. Keep the existing algorithms until the stated evidence is available and the maintainer decides. An algorithm change goes one at a time, with recovery, false positives and run time measured on the reference datasets (see [DEVELOPER.md](DEVELOPER.md#real-data-checks)). When a proposal is implemented, describe it in [ALGORITHM.md](ALGORITHM.md) and remove it from this file.

## Open proposals

| ID | Status | Proposal, and the condition for revisiting it |
|---|---|---|
| QC-1 | Deferred | Star-count rejection has no absolute or relative envelope floor. Counts now share a quality area, but small count changes can still matter. A new floor needs a recovery and false-retention study. |
| ST-1 | Deferred | A missing exposure duration prevents assigning streak endpoint times, even when capture timestamps exist. A fallback must represent what is actually known. |
| ST-5 | Deferred | A one-frame streak track can lie on the line of a longer streak track in the same frame and stay separate. On klangwolke-2, with a second profile, a satellite tracked through frames 8–14 also left a separate 12-piece streak in frame 8 and a one-piece streak in frame 11. Establish why the consolidation leaves them apart before changing it. |
| PT-2 | Deferred | A small timed-link step can extend a moving seed with nearly stationary detections. Establish the real-data impact before imposing a displacement or velocity gate. |
| PT-4 | Deferred | The geometric linker's fixed 2-degree direction tolerance can limit short steps. Evaluate uncertainty-scaled alternatives on timed and untimed controls. |
| PT-5 | Proposed | `maxStarJitter` serves several tolerances. The maintainer preferred a future registration-quality assessment over splitting the tolerances now. The fill-edge registration used by camera-fixed rejection is not a general registration-quality measurement. |
| PT-6 | Deferred | Greedy continuation and assignment can pick a locally favourable association. Compare ambiguous synthetic crossings and real tracks before changing ownership. |
| PT-7 | Deferred | Dense leftover sets can make point linking expensive. Profile representative high-density inputs before adding indices or pruning rules. |
| PT-8 | Deferred | Strict exposure-motion limits infer a scale from footprint area. Assess a PSF or width alternative before changing the kinematics. |
| AN-2 | Deferred; reduced scope | Private same-frame anomaly groups do not keep their seed line. They are only offered to confirmed-streak consolidation and are not exported. Revisit only if a concrete consolidation error is shown. |
| RS-1 | Deferred; discuss first | Common-motion grouping can include two stationary leftover sources. The maintainer kept the current behaviour, because this filter looks for instrumental patterns. Camera-fixed rejection now handles a large dither and hot-pixel population independently; it does not change the common-motion acceptance rule. |
| RS-3 | Deferred | Two-point LOCAL_REPEAT lacks a calibrated chance-alignment check. Begin with diagnostics under measured source density rather than an uncalibrated new veto. |
| SM-2 | Deferred | A maximum stack's sigma is not a single-frame sigma. Compare empirical maximum-stack nulls across frame counts before relating this threshold to the per-frame Auto-Tune settings (SpacePixels F01). |
| SM-F1 | Proposed | Three-bin temporal stacks, or a measured slow-mover path, are not implemented. Compare real-mover recovery, false positives and run time before introducing either; the current frame-support and stationarity screens do not establish motion. |
| AT-3 | Deferred | The negative image `2 * master - frame` can contain residual star rings under changing seeing. Quantify post-veto ring leakage first; choosing only easy median-seeing frames would hide relevant leakage. |
| AT-4 | Deferred; limitation documented | False-positive budgets count surviving single-frame detections, not final false tracks or anomalies. Track-level calibration needs multi-frame nulls and a justified run-time budget. |
| XC-2 | Deferred | No shared chance-alignment model covers linking, local repeats and common motion. Start with stage-specific null diagnostics; a uniform independent-pixel formula is not a calibrated candidate probability. |
| XC-3 | Partly implemented; frame work deferred | Master-star extraction adds a local-sky pass. Per-frame extraction still uses one sky level and noise estimate. Preserve extended emission and propagate local noise consistently before proposing a frame-sky redesign. |
| VS-2 | Deferred | Ensemble convergence watches zero points only, not the plane coefficients. A fix should monitor changes in the predicted spatial corrections and report convergence or iteration exhaustion; raising the iteration cap alone is not enough. |
| VS-5 | Deferred | The APERTURE check compares percentile ranges, not temporal agreement. A stronger check should use shared finite frames and test recovery under measurement noise and blending. |
| VS-6 | Deferred | Persistence and adjacent-pair statistics bridge missing observations. Preserve indices and times, and define maximum support gaps, before treating surviving points as continuous temporal evidence. |
| VS-8 | Deferred | Five similar unsaturated maxima can resemble a clipping ceiling. Consider a caller-supplied ceiling in the correct pixel domain and stronger automatic evidence; no ceiling option exists today. |
| VS-9 | Deferred | The nominal selected-star minimum differs from check B's counted linear-range minimum. With the default 50 required references and a 90 % faint bound, an ideal sample needs at least 55 before further exclusions. Improve cap validation, and distinguish insufficient samples from measured nonlinearity. |
| VS-10 | Deferred | In a frame a streak track skips, photometry flags the stars along one piece's length around a position interpolated between two pieces, not along the streak's predicted path. On klangwolke-2, a satellite's path in its missing frame 3 (x about 7270–7750) was only partly flagged, and which part depended on the order of the pieces. Flagging the whole predicted path must be weighed against losing good measurements when a flashing satellite left no trail in that frame. |

## Declined

These decisions are binding. Do not propose them again without new evidence.

| ID | Decision |
|---|---|
| IN-3 | Tracking returns no categories when fewer than three frame lists remain; slow movers and photometry are separate stages. Do not reinterpret this as an instruction to recover one- or two-frame tracks. |
| ST-4 | Keep the existing streak-chain linking without a new global common-line or distance gate. |
| PT-1 | Keep the geometric rhythm check's distance-based rule; it makes no capture-time assumption. |
| PT-3 | Keep timed tracks that can bend; no final straightness requirement. |
| SM-1 | No drift-path veto on maximum-stack slow-mover candidates. Camera-fixed point filtering does not implement this proposal. |
| PH-1 | Slow-mover footprints continue to flag nearby photometry stars across frames. |
| EX-3 | The extraction FWHM keeps its second-moment convention (about √2 above a round Gaussian FWHM); documented. Photometry and SpacePixels' Night & Equipment measure size separately. |
| XC-1 | Integrated significance assumes uncorrelated pixels, and photometry errors omit source photon noise; documented. Do not insert a numerical correlation correction without blank-aperture evidence. |
