# Design improvements and review decisions

Consolidated and checked against the source tree on 2026-10-19, at engine commit `f084ab9`. This is the single active decision list for engine improvements and review limitations. Current implementation is described in [ALGORITHM.md](ALGORITHM.md) (detection, slow movers, photometry and tuning), [PIPELINE.md](PIPELINE.md) (API contracts), and [CONFIG.md](CONFIG.md) (fields/defaults).

Original evidence and maintainer comments are preserved in the [October engine review](docs/archive/code-review-2026-10.md), [variable-star assessment](docs/archive/variable-star-assessment-2026-10.md), [slow-mover design](docs/archive/slow-mover-design-2026-10.md), and [maintenance/regression notes](docs/archive/maintenance-notes-2026-10-19.md). Original line numbers, suggested orders and statements about missing features describe those earlier checkouts.

## Status and scope

**Proposed** is an idea without an implementation decision; **Accepted** requires an explicit decision; **In progress** means implementation has started; **Deferred** is postponed work; **Declined** preserves a decision to keep current behavior; **Implemented** records a completed change; **Retired** means an old finding no longer applies. A source concern or synthetic reproduction is not a measured real-data incidence. This documentation cleanup changes no algorithm or review authorization.

Existing issue IDs are retained. Variable-star assessment numbers use a `VS-` prefix here to avoid ambiguity. No deferred or declined issue becomes Accepted by being listed in this plan. Discuss a concrete engine fix, implement one at a time, and report real-data differences under [AGENTS.md](AGENTS.md).

## Deferred work and optional ideas

| ID | Status | Current scope and condition for revisiting |
|---|---|---|
| QC-1 | Deferred | Star-count rejection has no absolute/relative envelope floor. Counts now share a quality area, but small count changes can still matter. A new floor needs a recovery/false-retention study. |
| ST-1 | Deferred | Missing exposure duration prevents assigning streak endpoint times even when capture timestamps exist. A fallback must represent what is actually known. |
| ST-5 | Deferred | A one-frame streak track can lie on the line of a longer streak track in the same frame and stay separate. On klangwolke-2, with a second profile, a satellite tracked through frames 8-14 also left a separate 12-piece streak in frame 8 and a one-piece streak in frame 11. Establish why the consolidation leaves them apart before changing it. |
| PT-2 | Deferred | A small timed-link step can extend a moving seed with nearly stationary detections. Establish real-data impact before imposing a displacement/velocity gate. |
| PT-4 | Deferred | The geometric linker's fixed 2-degree direction tolerance can limit short steps. Evaluate uncertainty-scaled alternatives on timed and untimed controls. |
| PT-5 | Proposed | `maxStarJitter` serves several tolerances. The maintainer preferred a future registration-quality assessment over splitting tolerances immediately. The fill-edge registration used by camera-fixed rejection is not a general residual-registration QC measurement. |
| PT-6 | Deferred | Greedy continuation/assignment can select a locally favorable association. Compare ambiguous synthetic crossings and real tracks before changing ownership. |
| PT-7 | Deferred | Dense leftover sets can make point linking expensive. Profile representative high-density inputs before adding indices or pruning rules. |
| PT-8 | Deferred | Strict exposure-motion limits infer a scale from footprint area. Assess a PSF/width alternative before changing kinematics. |
| AN-2 | Deferred; reduced scope | Private same-frame anomaly groups do not retain their seed line. They are only offered to confirmed-streak consolidation; they are no longer independently exported. Revisit only if a concrete consolidation error is demonstrated. |
| RS-1 | Deferred; discuss first | Common-motion grouping can include two stationary leftover sources. The maintainer retained current behavior because this filter seeks instrumental patterns. Camera-fixed rejection now addresses a major dither/hot-pixel population independently; it does not change the common-motion acceptance rule. |
| RS-3 | Deferred | Two-point LOCAL_REPEAT lacks a calibrated chance-alignment check. Begin with diagnostics under measured source density rather than an uncalibrated new veto. |
| SM-2 | Deferred | A maximum stack's sigma is not a single-frame sigma. Compare empirical maximum-stack nulls across frame counts before relating this threshold to per-frame Auto-Tune settings. |
| AT-3 | Deferred | The negative image `2 * master - frame` can contain residual star rings under changing seeing. Quantify post-veto ring leakage first; selecting only easy median-seeing frames would hide relevant leakage. |
| AT-4 | Deferred; limitation documented | False-positive budgets count surviving single-frame detections, not final false tracks/anomalies. Track-level calibration needs multi-frame nulls and a justified runtime budget. |
| XC-2 | Deferred | No shared chance-alignment model covers linking, local repeats and common motion. Start with stage-specific null diagnostics; a uniform independent-pixel formula is not a calibrated candidate probability. |
| XC-3 | Partly implemented; frame work deferred | Master-star extraction now adds a local-sky pass. Per-frame extraction still uses one sky level/noise estimate. Preserve extended emission and propagate local noise consistently before proposing a frame-sky redesign. A later median-stack check of leftovers remains an unimplemented idea, not an accepted next step. |
| VS-2 | Deferred | Ensemble convergence still watches zero points only, not plane coefficients. A future fix should monitor changes in predicted spatial corrections and report convergence/iteration exhaustion; increasing the iteration cap alone is insufficient. |
| VS-5 | Deferred | APERTURE compares percentile ranges, not temporal agreement. Any stronger check should use shared finite frames and test recovery under measurement noise and blending. |
| VS-6 | Deferred | Persistence and adjacent-pair statistics bridge missing observations. Preserve indices/times and define maximum support gaps before treating surviving points as continuous temporal evidence. |
| VS-8 | Deferred | Five similar unsaturated maxima can resemble a clipping ceiling. Consider a caller-supplied ceiling in the correct pixel domain and stronger automatic evidence; no new ceiling option currently exists. |
| VS-9 | Deferred | The nominal selected-star minimum differs from check B's counted linear-range minimum. With the default 50 required references and a 90% faint bound, an ideal sample needs at least 55 before further exclusions. Improve cap validation and distinguish insufficient samples from measured nonlinearity. |
| VS-10 | Deferred | In a frame a streak track skips, photometry flags the stars along one piece's length around a position interpolated between two pieces, not along the streak's predicted path in that frame. On klangwolke-2 a satellite's path in its missing frame 3 (x about 7270-7750) was only partly flagged, and which part depended on the order of the pieces. Flagging the whole predicted path must be weighed against losing good measurements when a flashing satellite left no trail in that frame. |
| SM-F1 | Proposed | Three-bin temporal stacks or a measured slow-mover path, described in the old design, are not implemented. Compare real-mover recovery, false positives and runtime before introducing either; current frame-support/stationarity screens do not establish motion. |

For VS-2/5/6/8/9 the assessment contains component reproductions and their caveats. They are retained as evidence, not presented as measured false-positive rates on the reference datasets. Secondary ideas about per-frame quantisation coverage, source-photon-noise errors, reference-population robustness, capping/performance and pre-epoch metadata remain unapproved investigations in that archive; they are not additional confirmed defects.

## Decisions to keep current behavior

| ID | Decision | Current behavior |
|---|---|---|
| IN-3 | Declined | Tracking returns no categories when fewer than three frame lists remain; slow movers and photometry are separate pipeline stages. Do not reinterpret this as an instruction to recover one/two-frame tracks. |
| ST-4 | Declined | Keep existing streak-chain linking without a new global common-line/distance gate. |
| PT-1 | Declined | Keep the geometric rhythm check's distance-based rule; it makes no capture-time assumption. |
| PT-3 | Declined | Keep timed tracks that can bend; no final straightness requirement was accepted. |
| SM-1 | Declined | No drift-path veto on maximum-stack slow-mover candidates. Camera-fixed point filtering does not implement this proposal. |
| PH-1 | Declined | Slow-mover footprints continue to flag nearby photometry stars across frames. |
| PT-9, X-1, X-2, X-3 | No separate linker change | Retain exposure-start timing and uniform metadata contracts. Engine/tuner `InputRules`, missing timestamp defaults and linker timestamp checks cover applicable boundary cases; do not schedule the old mixed-metadata examples as independent fixes. |
| EX-3 | Formula retained; documented | Extraction FWHM has its existing second-moment convention (about sqrt(2) above a round Gaussian FWHM). Photometry and SpacePixels Night & Equipment measure size separately. |
| XC-1 | Formula retained; documented | Integrated significance assumes uncorrelated pixels and photometry errors omit source photon noise. State these limits; do not insert a numerical correlation correction without blank-aperture evidence. |

## Completed and retired findings

| IDs | Outcome and implementation evidence |
|---|---|
| IN-1 | Implemented in SpacePixels: Manual Transient Inspection receives a configuration copy (`fd98a9a`, `475fc15` in SpacePixels). |
| IN-2 | Implemented: engine/tuner `InputRules` and uniform linker timestamps (`0f28d7d`). Missing time is -1; 0 remains a real timestamp. |
| IN-4 | Implemented: effective point minimum cannot fall below three; invalid ratios use the default (`3468ac1`). |
| XC-4, EX-1 | Implemented: coverage/quality/data areas, common sky measurement area, kept-frame search areas, tuner crop constraints, removal of edge/void settings (`0200684`, `5e99e43`, `ba7c75a`, `13f7bce`, `8dcb41b`, `bec3455`). |
| EX-2 | Retired: darkness/void probes and their configuration were removed with data areas. |
| ST-2, ST-3 | Implemented: streak motion uses a unit-axis pixel displacement; a stationary multi-frame streak fails the motion check (`b502535`, `46e1dda`). |
| AN-1, AN-3 | Implemented: standalone suspected-streak results removed; same-frame groups use present frame keys and survive only through confirmed-streak consolidation (`b92a985`). |
| RS-2 | Implemented: residual span, gaps and coverage count kept frames; the line fit retains sequence indices (`48d7a9b`). |
| AT-1, AT-2 | Implemented: calibrated crops fit inside the pool data area, rates use owned searchable pixels and injections have a safe margin (`8dcb41b`). |
| AT-5 | Implemented: legacy mask growth matches `max(1, round(jitter / 2))` (`15f63b3`). This did not make the legacy master-grow threshold identical to the calibrated tuner's configurable hysteresis. |
| DOC-1, HY-1 | Implemented: references/defaults/contracts corrected and unused code removed; output stays behind debug switches (`a6069a5`, `ed5aaea`, `e2e89d2`, `6992e9e` and subsequent documentation updates). |
| VS-1, VS-3, VS-4, VS-7 | Implemented: crossing positions use registration-corrected coordinates, interpolation uses available capture times, every same-frame track fragment is considered, and undefined response slopes are INCONCLUSIVE. See current photometry sources and reference. |
| XC-3 master pass | Implemented: global-sky stars plus nonoverlapping local-sky stars seeded at least at 5 sigma (`ffa60aa`); the first pass is retained to mask extended emission that per-frame extraction also detects. |
| Camera-fixed rejection | Implemented: fill-edge registration and camera-coordinate groups (`4b443d7`), with `enableCameraFixedRejection` and exported review groups. The earlier Option A sensor study is historical, not a pending implementation. |
| Anomalies on streak paths | Implemented: an anomaly on a confirmed streak's line in the same frame, or where the streak's motion puts it in a skipped, preceding or following frame, joins that streak; `AnomalyDetection.lowConfidence` is removed (`de1dc48`). On klangwolke-2, 17 of 39 anomalies joined (24 of 40 with a second profile), on klangwolke-1 one of six; the other 16 reference runs were identical. Joined pieces now flag the stars they cross in photometry: one borderline POSSIBLE star became CONSTANT, and with the second profile one constant star fell below the usable-frame minimum. |

The [archived engine review](docs/archive/code-review-2026-10.md#implementation-status-2026-10-18) retains full commit/decision provenance. The [maintenance notes](docs/archive/maintenance-notes-2026-10-19.md) retain the 19-dataset per-step results, tuner differences and M81-kumar experiments. In particular, the recorded HIGH-profile M81 sequence is LR 274 → 84 → 10 and CM 52 → 5 → 3 after master-star and camera-fixed changes, respectively; these are historical measurements with the recorded profiles, not new benchmarks from this cleanup.

## Verification for future changes

1. Define the observable failure and the supported input contract. Use a deterministic component reproduction and an unchanged control where possible.
2. Discuss the proposed rule and acceptance condition before changing an algorithm. Retain the decision/status here.
3. Run engine tests and the consuming SpacePixels tests with the local composite build. Compare base/change on the same reference profiles and datasets using the SpacePixels regression procedure; compare frame rejection, tracks/anomalies, camera/common-motion groups, variable-star results star by star, and timings.
4. Report differences before committing. Preserve user profiles/preferences, use scratch diagnostic code, and keep memory-heavy runs sequential.
5. Update the relevant current reference after implementation, mark the item with evidence here, and archive lengthy experiment narratives rather than growing `AGENTS.md` into a session log.
