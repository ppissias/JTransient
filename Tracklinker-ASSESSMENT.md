# TrackLinker algorithm assessment

Reviewed on 2026-10-09 against the current workspace implementation. Reassessed after confirmation
that callers validate all-or-none timestamp availability and expect equal exposure durations.
Tracking behavior is unchanged; these input expectations are documented in TrackLinker.java.

## Reassessment under the validated timestamp contract

The caller guarantees that either every frame has a capture timestamp or every frame has none,
with the latter represented as -1 on all detections. Therefore the first two findings in the
conversational review are outside the supported input contract and do not apply to validated
inputs. Their archived probes demonstrate unsupported mixed-timestamp inputs, not failures in
the intended workflow.

Equal exposure durations do not, by themselves, imply equally spaced capture times: readout,
dropped frames, and pauses can still change the interval between exposures. They also do not
guarantee that the optional exposureDuration field has been populated. Finding 3 is not reached
if the common exposure duration is supplied as a positive value on every streak detection.
With that additional metadata guarantee, seven of the ten numbered findings remain relevant;
otherwise eight remain, including the conditional finding 3. These are algorithm weaknesses
and conditional cases, not seven or eight unconditional implementation defects.

| Original conversational finding | Reassessment |
| --- | --- |
| 1. An unrelated timestamp disables fallback | Excluded by all-or-none timestamp availability. |
| 2. Unknown timestamp used as a real time | Excluded: the timed branch cannot receive -1 timestamps under this contract. |
| 3. Streak timestamps discarded without exposure metadata | Not reached when the common exposure duration is populated and positive. Equal physical exposure durations alone do not remove the unknown-metadata case. The exposure gate is unnecessary for streak timing. |
| 4. Incompatible streak projection units | Remains; final consolidation can incorrectly merge a later detection inside an earlier fragmented streak's span, even though initial chaining rejects it. |
| 5. Degenerate streak line accepted as motion | Remains; possible with fully timed or fully untimed inputs. |
| 6. Rhythm check ignores actual frame gaps | Remains for untimed linking and any enabled geometric pass. |
| 7. Insufficient final time-based validation | Remains for fully timestamped inputs; includes curved tracks and stationary tails. |
| 8. Initial streak chains lack a common-line check | Remains; independent of timestamp availability. |
| 9. Suspected streak grouping changes its line model | Remains as a model-consistency risk; independent of timestamp availability. |
| 10. Fewer than three frames loses every category | Still conditional: excluded if at least three frames are guaranteed at the TrackLinker call, including after upstream filtering. |

The source-inspection concern about direct callers leaving unknown timestamps at Java's default
0 also concerns callers violating the now-explicit metadata contract. The variable-exposure
timestamp-convention concern is excluded by the equal-duration expectation. The other
source-inspection concerns are not removed by these assumptions.

## Assessment

The staged design is reasonable, and several details are handled well: exact footprint overlap rather than centroid-only vetoing, preservation of unclassified detections, separation of orientation from directional polarity, ranked time-based proposals, and one streak-motion sample per frame.

Under the validated timestamp contract and equal-duration expectation, the remaining concrete concerns include streak projection units, degenerate streak handling, and geometric rhythm validation of real frame gaps. Streak timestamp handling remains conditional on missing exposure metadata, not on differing physical exposure durations. Final track validation is also weaker than incremental validation suggests: a candidate can accumulate a stationary tail or bend as its baseline changes, yet be accepted because it has enough points.

These are linker-level reproductions using synthetic detections. They establish behavior and its causes, but do not measure the frequency of false detections in real images. The master-star veto may prevent some stationary examples from reaching the linker in a particular session.

## Reading the observing examples

The observing scenarios below describe plausible ways the required detections could arise. They
are illustrative, not reports of failures observed in an actual image dataset. The numerical
examples come from the archived linker reproductions; reproducing them from images also requires
the extractor to produce those detections and the stationary-star mask to let them through.

A **detection** is one extracted bright blob. Its **centroid** is its measured center in image
pixels. A **streak** is a blob classified as elongated. The **master veto** is the mask that removes
objects overlapping known stationary stars. A **frame gap** counts exposure intervals, including
frames in which an object was not detected.

Unless an example says otherwise, it assumes default linking thresholds, at least three input
frames, and detections that pass the master veto. Point detections have similar widths and surface
brightness, so morphology rejection does not hide the behavior being illustrated. Streak examples
use compatible horizontal orientations. Timestamped examples have timestamps on every frame;
untimed examples use -1 everywhere. Times in seconds below represent elapsed time from the first
capture; the actual timestamp fields are in milliseconds.

For a stationary artifact or star remnant, passing the master veto is an essential condition. A
normal, consistently detected stationary star should be removed before linking. An intermittent
artifact or a poorly represented residual is a candidate only if that earlier protection misses it.

Finding numbers below now match the ten numbered findings in the original conversational review.

## How the algorithm works

| Stage | Actual behavior | Relevant source lines |
| --- | --- | --- |
| Stationary veto | Split points and streaks; build a dilated master footprint mask; reject objects whose footprint overlap exceeds the configured fraction. | TrackLinker.java:226, 262, 279, 302 |
| Fast streak linking | Start at each unmatched streak; greedily choose an angle-compatible continuation, preferring smaller frame gaps and then shorter centroid distance; allow same-frame fragments; check aggregated frame motion. | TrackLinker.java:329, 355, 377, 385 |
| Single-streak promotion | Promote unmatched streaks only if the binary-shape veto permits them and their peak significance is sufficient. | TrackLinker.java:419 |
| Point-track threshold | Require max(3, ceil(frameCount / trackMinFrameRatio)), then cap that value by absoluteMaxPointsRequired. | TrackLinker.java:504 |
| Time-based proposals | Enumerate baselines across frames. For each baseline, greedily extend through later frames using speed, line, angle, and morphology gates. Retain every long-enough proposal. | TrackLinker.java:562, 620, 702 |
| Time-based assignment | Sort proposals by length first, then score; accept proposals that do not share detections with already accepted proposals. | TrackLinker.java:716, 719, 2075 |
| Geometric fallback | Run when enabled or when the global timestamp probe finds no timestamps. Extend by line error and direction, prune short steps, and apply rhythm validation. | TrackLinker.java:744, 766, 854, 888 |
| Anomaly rescue | Rescue strong untracked detections; group three or more same-frame collinear anomalies into suspected streaks. | TrackLinker.java:953, 968, 1132, 1194 |
| Final consolidation | Merge confirmed and suspected streak tracks if frame-gap, orientation, line, and motion checks permit it. Build final unclassified exports. | TrackLinker.java:990, 1413, 1083 |

The time-based gate is a scalar speed comparison plus spatial and angular tests, rather than a fit of all observations to one velocity vector. Its speed allowance is currentSpeed * timeBasedVelocityTolerance + maxStarJitter / deltaTime. Updating the baseline from the first point reduces random local drift, but does not guarantee that all previous points still fit the final trajectory.

## Findings and observing examples

### 1. One unrelated timestamp disables fallback for untimed movers (outside supported contract)

Source: TrackLinker.java:517, 519, 744.

**In plain language:** one dated image can switch off the fallback needed by other, undated
images. An example would be a folder combining original captures with converted images whose
capture-date metadata was lost. This requires mixed timestamp availability and is therefore
excluded by the caller's validation; it is not a surviving issue for supported inputs.

The global hasTimestamps flag becomes true if the first detection of any input frame has a timestamp. This is checked against allFrames, before considering whether that detection survived vetoing or is eligible for point linking.

Reproduction: three untimed point detections move 20 pixels per frame. A fourth frame contains a timestamped stationary object that is completely removed by the master veto. With default enableGeometricTrackLinking=false, the untimed mover produces zero tracks. The removed stationary object has still disabled geometric fallback.

Debugger evidence: timestamp gate: available=true, geometricEnabled=false, while the mover baselines have first=-1, second=-1, delta=0.0.

Fix direction: distinguish timestamp availability for usable detections from global metadata presence. Decide explicitly how untimed detections receive fallback in a mixed session. A single timestamp does not establish a usable time-based discovery path.

### 2. The unknown timestamp sentinel is used as a real time (outside supported contract)

Source: TrackLinker.java:579 and 627.

**In plain language:** the numeric value meaning "date unknown" can accidentally be treated as
an actual capture time. A loader that assigns -1 to one image but real times to the others could
trigger it. That is another mixed-metadata input, so the confirmed all-or-none validation excludes
this case too.

The time-based loops check only whether the time difference is positive. They do not reject a detection whose timestamp is -1. The explicit sentinel checks appear in the optional exposure gate, which does not protect velocity calculation.

Reproduction: collinear points with timestamps [-1, 1000, 2001] are accepted as a time-based track. The invented first interval is 1001 milliseconds, and the second interval is also 1001 milliseconds.

Debugger evidence: baseline timestamps: first=-1, second=1000, delta=1001.0, followed by a three-point time candidate.

Fix direction: reject unknown timestamps before subtracting times for every baseline and continuation. Handle them through the chosen fallback policy.

### 3. Streak timestamps are discarded when exposure duration is unknown

Source: TrackLinker.java:1537.

**In plain language:** the tracker knows when the images were taken, but forgets those times
because a different piece of metadata, the exposure duration, is missing.

**Effect of the equal-exposure assumption:** this is not a problem caused by exposures having
different lengths. The numerical example can use identical 100-millisecond exposures throughout,
with their duration simply absent from the detection metadata. If the normal input supplies the
same positive duration on every detection, the existing gate always passes and this finding does
not affect that workflow.

**Observing scenario:** a loader reads capture times successfully but does not populate exposure
durations. The observing session includes a pause between captures. Three faint, similarly
oriented trails happen to be equally spaced across the image; the third could belong to another
object or be an unrelated elongated artifact.

| Frame | Capture time, seconds | Streak center X, pixels | Exposure metadata |
| --- | --- | --- | --- |
| 0 | 0 | 40 | Unknown |
| 1 | 1 | 60 | Unknown |
| 2 | 11 | 80 | Unknown |

The first change is 20 pixels in one second; the second is 20 pixels in ten seconds. They do not
describe a constant-speed continuation. A target continuing at the first speed would reach
X=260 at the third capture, rather than X=80. Nevertheless, the implementation accepts the three
detections together after discarding their times and comparing only the equal spatial jumps.

**Trigger conditions:** all capture timestamps are known, exposureDuration is -1 or zero on the
streak detections, and the streaks pass the shape, angle, direction, and stationary-mask gates.
No mixed timestamps are needed. This case disappears if positive exposure durations are also
guaranteed for every timestamped frame.

buildStreakFrameSamples records a timestamp only when exposureDuration > 0. Neither projected speed calculation nor time ordering needs an exposure duration. The documented timestamp and exposure fields are independently optional.

**Does streak linking use exposure length?** Only as that metadata-presence gate. It does not
compare streak exposure lengths, derive their speed from trail length divided by exposure
duration, or adjust their capture times by half the duration. Its actual speed-consistency check
uses changes in frame-mean position divided by changes in capture timestamp. The separate point
linker does use exposure duration when strictExposureKinematics is enabled; that is not the streak
check discussed here.

Reproduction: streak centroids [40, 60, 80] at times [0, 1000, 11000] have a tenfold speed change. With exposureDuration=-1, all three are accepted together. Their real timestamps have been replaced by unknown frame-sample timestamps, so the equal spatial jumps pass the geometric rhythm check.

Debugger evidence: streak timing: available=false, samples=3, speeds=[], followed by rhythm jumps=[20.0, 20.0], consistency=1.0.

Fix direction: change the frame-sample timestamp guard from
`point.timestamp != -1L && point.exposureDuration > 0L` to `point.timestamp != -1L`.
This removes the unnecessary exposure-duration dependency without removing capture-time ordering
or speed consistency. Keep those timestamp checks: identical exposure lengths do not make a
20-pixel step after one second equivalent to a 20-pixel step after ten seconds. No behavior change
has been made as part of this reassessment.

### 4. Streak displacement is compared in incompatible units

Source: TrackLinker.java:1561, 1581, 1584, 2190.

**In plain language:** the longer the line used as a reference, the larger the apparent movement
becomes, even though the measured movement has not changed.

**Expected behavior:** with chronological, non-overlapping exposures and the intended
straight-line, one-way motion model, a streak going from A to B in one frame should not be linked
to a later detection well inside A-to-B. That is not a forward continuation. The later detection
may be another object or an artifact; mere collinearity does not establish a shared identity.

**Observing scenario:** a moving object's trail is broken into two separately extracted pieces
in the first image. In the next image, an unrelated elongated source appears between those
pieces. The two earlier pieces have centers at X=40 and X=240; the later source is at X=140.1.
All exposures have the same duration. The tracker should keep the events separate, but its final
consolidation compares only the earlier pieces' average center with the later source's center.

| Frame | Detected pieces, X coordinates | Average center used for motion |
| --- | --- | --- |
| 0 | 40 and 240 | 140 |
| 1 | 140.1 | 140.1 |

The measured frame centers differ by only 0.1 pixel. With the default jitter setting, the motion
check intends to require at least 0.75 pixel. However, its 200-pixel reference line multiplies
0.1 into 20, so the merge passes that check. This is an incorrectly accepted association, not a
complaint about the tracker correctly rejecting a later detection between A and B.

**Why the chronological check does not save it:** initial chaining does reject the later source
after joining X=40 to X=240: returning to X=140.1 reverses its established direction. The strong
later source is then retained as a standalone streak. Final consolidation evaluates the two
tracks again, aggregates frame 0 to X=140, and sees a positive change to X=140.1. That pass does
not preserve the earlier chaining decision or check continuation beyond the earlier span. It
therefore joins the events back together.

**Trigger conditions:** same-frame pieces provide a long reference line, the next frame's mean
center moves very little along it, and the geometry otherwise matches. The archived example has
a third empty frame, known timestamps and exposures, and a strong later streak that qualifies
for standalone promotion before consolidation. The resulting cross-frame merge passes despite
the small measured displacement.

projectionAlongLine returns an unnormalized dot product. Because both the displacement and the axis vector have pixel units, its result has squared-pixel units. minimumProjectedStep is expressed in pixels.

Reproduction: same-frame fragments have centroids at x=40 and x=240, giving a frame centroid of x=140. A detection in the next frame is at x=140.1. During consolidation, the 200-pixel axis multiplies the actual 0.1-pixel motion into a projectedStep of 20, which passes a 0.75-pixel threshold.

Debugger evidence: projected=20.0, minimum=0.75, axisX=200.0, axisY=0.0.

Fix direction: normalize the axis for physical motion checks, dividing by hypot(dx, dy), and guard a zero-length axis. Unnormalized projections remain acceptable for sorting; their scale is not acceptable for pixel thresholds or physical speed units. Relative speed ratios happen to cancel this scale within a single check, which can conceal the bug.

Normalization rejects this particular 0.1-pixel example, but is not a complete fix for the A-to-B
continuation rule. A later detection at X=150 still lies inside [40, 240], yet its 10-pixel change
from the earlier mean of X=140 would exceed a correctly normalized 0.75-pixel threshold. That is
a separate limitation of mean-only motion validation, rather than another demonstration of the
unit error. This X=150 variant is an illustration from the code, not an additional archived
runtime reproduction.

Consolidation should also check chronological continuation using the projected per-frame
fragment ranges and a consistent motion direction, with tolerance for measurement uncertainty.
Same-frame fragments should remain mergeable. A streak's shape angle describes an axis, not an
arrow, so an arbitrary ordering of same-frame fragments must not be treated as independent proof
of physical direction. A range-based rule should be covered by regressions for genuine forward
motion, both directions, and fragmented streaks before changing acceptance behavior.

### 5. A degenerate streak line is treated as successful motion validation

Source: TrackLinker.java:1652, 1654, 1759, 411.

**In plain language:** being unable to measure a direction of travel is treated as evidence that
the travel is acceptable.

**Observing scenario:** an intermittent detector artifact produces a short horizontal mark in
several images at the same sensor location. It is absent in most images, so it may not be
represented in the median master mask. If extraction classifies those marks as streaks, the
linker receives several elongated detections with no centroid movement.

The exact reproduction uses three frames, each with a horizontal streak centered at (40,40)
and increasing capture times. The implementation returns one confirmed streak track containing
all three detections. Repeated marks at the same location are evidence of a stationary defect,
not measured cross-frame movement, even though each individual blob has an elongated shape.

**Trigger conditions:** the marks survive the master veto, are classified as streaks, and their
centers coincide. Use horizontal orientations for the exact zero-motion example: the code
obtains angle zero from atan2(0,0), which agrees with a horizontal streak. A consistently present
bright mark already covered by the master mask will not reach this failure path.

If no pair of streak centroids is separated by at least one pixel, determineStreakLineEndpoints returns null. passesStreakTimeConsistency then returns true. This bypasses the minimum projected motion check.

Reproduction: three horizontal streak detections at exactly (40, 40), in different frames with increasing timestamps, become one confirmed streak track. atan2(0, 0) also supplies the zero direction that allows horizontal candidates into this path.

Debugger evidence: degenerate streak accepted: points=2 and points=3.

Fix direction: insufficient cross-frame displacement must not count as successful motion validation. Preserve intentional same-frame fragmentation separately, and evaluate standalone streak significance through the existing single-streak path.

### 6. Rhythm validation does not use actual frame gaps

Source: TrackLinker.java:2233, 2245, 2252.

**In plain language:** the tracker guesses how many frames were missed from the distances, even
though the real frame numbers are available. Its guess can reject a real mover or excuse an
incorrect match.

**Observing scenario A: an asteroid is hidden in one image.** Suppose regularly spaced captures
show a compact object moving 20 pixels per frame. It is not detected in one frame because of a
cloud, insufficient signal, or overlap with a masked background star.

| Frame | Expected X, pixels | Detection passed to the linker |
| --- | --- | --- |
| 0 | 40 | X=40 |
| 1 | 60 | X=60 |
| 2 | 80 | No detection |
| 3 | 100 | X=100 |

The detected steps are 20 pixels over one frame interval and 40 pixels over two intervals.
Both represent the same speed. The geometric rhythm check nevertheless rejects this three-point
track with default settings. The same problem can arise if frame 2 is removed by quality
filtering and the original frame indices remain 0, 1, and 3.

**Observing scenario B: a wrong blob is substituted.** In four consecutive frames, detections
at X=40,60,80,120 create steps of 20,20,40 pixels. A mover maintaining the earlier pace should
instead be at X=100 in the last frame. If that detection is missing and a similar-looking blob
at X=120 is selected, the rhythm check accepts the doubled step as though a frame had been
skipped, although no frame was skipped.

**Trigger conditions:** geometric point linking is active, the detections are collinear and
morphologically compatible, and the jumps pass the jitter and maximum-jump limits. The missing
observation example has no timestamps on any frame, so geometric linking runs even when its
configuration flag is false. Its detections may still be exported individually or as anomalies;
the failure is the missing confirmed point track. The doubled-step example demonstrates a
permissive heuristic, rather than establishing that every accelerating trajectory is invalid.

The rhythm check compares raw distances with integer multiples of the median distance. It ignores sourceFrameIndex entirely and uses the upper middle value for an even number of jumps.

False negative: observations at frames [0, 1, 3] and x=[40, 60, 100] describe constant 20-pixel/frame motion with one missing observation. Jumps [20, 40] produce median=40. The 20-pixel jump rounds to multiplier=1 and differs by 20 pixels, above the default variance of 8. Consistency is 0.5, below the required 0.7, so the mover is missed.

False positive under a constant-speed interpretation: consecutive-frame positions [40, 60, 80, 120] produce jumps [20, 20, 40]. The doubled final step is accepted as multiplier=2 despite no skipped frame. Debugger consistency is 1.0.

The first example is a practical failure of the intended missing-frame behavior. The second illustrates the permissiveness of the documented heuristic rather than a violation of its implementation rules.

Fix direction: use actual frame-index differences to estimate displacement per frame when assuming regular cadence. Do not invent missing frames from distance ratios. If cadence is irregular and timestamps are absent, describe the limited physical confidence explicitly.

### 7. Time-based tracks lack sufficient final validation

This finding includes two related weaknesses: stationary detections can extend a weak motion
hypothesis, and a series of locally acceptable matches can form a globally curved track.

#### 7.1 Stationary detections can extend a weak motion hypothesis

Source: TrackLinker.java:652, 689, 702; geometric-only pruning is at 854.

**In plain language:** one small position change starts a motion hypothesis, then repeated
detections at the same place make that hypothesis look well supported.

**Observing scenario:** a faint residual survives the stationary mask intermittently. Its center
is measured two pixels to one side in the first image, then remains at the same location in four
later images. The initial offset could be a centroid measurement error or a nearby unrelated
blob, rather than actual travel.

| Frame | Capture time, seconds | Center X, pixels |
| --- | --- | --- |
| 0 | 0 | 40 |
| 1 | 1 | No detection |
| 2 | 2 | 42 |
| 3 | 3 | 42 |
| 4 | 4 | 42 |
| 5 | 5 | 42 |

The first usable baseline moves two pixels in two seconds. On the next one-second interval,
the permitted speed difference includes 1.5 pixels per second of seeing slack, plus the relative
tolerance. That allowance is enough to accept zero movement. The repeated centers then extend
the track, and it is accepted with five points despite four of them being stationary.

**Trigger conditions:** every frame has a timestamp, the initial baseline exceeds the 1.5-pixel
jitter floor, later stationary detections have compatible shape and brightness, and they survive
the master veto. The six-frame table is the verified linker reproduction. An illustrative longer
session could contain this pattern in just a few frames while the residual is absent in the rest;
that makes the master-mask prerequisite easier to understand, but its actual mask behavior
would still need checking on image data.

The jitter allowance can admit a zero-length continuation for a sufficiently slow baseline. The time-based path has no equivalent of geometric anti-hijack pruning and no final comparison against a stationary model.

Reproduction: a point at x=40 in frame 0, an empty frame 1, and points at x=42 in frames 2 through 5 become a five-point time-based track. Four identical stationary positions provide most of its support. The debugger reports points=5, required=3, firstX=40.0, lastX=42.0.

This is a false-association risk if stationary remnants survive the master veto. It also exposes a sensitivity tradeoff: blindly pruning every sub-jitter step would reject genuine slow movers.

Fix direction: validate whether the complete sequence provides significant evidence for motion over a stationary alternative. Use overall motion and fit uncertainty, rather than relying only on point count or requiring every individual step to exceed seeing jitter.

#### 7.2 Small local deviations can accumulate into a curved track

Source: TrackLinker.java:661, 692, 702.

**In plain language:** every new point is close to the current prediction, but the prediction
keeps turning. The completed track can be much less straight than any individual decision suggests.

**Observing scenario:** a compact moving source passes through a crowded part of an image.
Nearby blobs can shift the measured centers or offer competing matches slightly to one side of
the trajectory. If successive accepted centers keep shifting toward that side, the evolving
prediction can follow a bend instead of rejecting it.

The following fully timestamped sequence reproduces the behavior. Captures are one second apart;
the archived probe uses short, known 100-millisecond exposures and compatible point profiles.
Coordinates are rounded here for readability; the archived probe generates the full precision.

| Frame / elapsed seconds | Center X, pixels | Center Y, pixels |
| --- | --- | --- |
| 0 | 40 | 40.0000 |
| 1 | 50 | 40.0000 |
| 2 | 60 | 42.0000 |
| 3 | 70 | 45.0000 |
| 4 | 80 | 48.6667 |
| 5 | 90 | 52.8333 |
| 6 | 100 | 57.4000 |
| 7 | 110 | 62.3000 |
| 8 | 120 | 67.4857 |
| 9 | 130 | 72.9214 |
| 10 | 140 | 78.5794 |

Each new center is within the allowed distance of the line defined by the first point and the
current anchor. After all eleven points are accepted, an interior point is 6.31 pixels away from
the final endpoint line, although predictionTolerance is three pixels. The table proves the
acceptance behavior; it does not establish that blending in a real image will produce this exact
sequence. A genuinely curved apparent path could also yield non-straight centers, so the concern
is consistency with a straight-track model, not a claim that all curved motion is spurious.

**Trigger conditions:** all frames have timestamps, successive local speed and angle changes stay
within their tolerances, and the centers drift progressively across the evolving line. There is
no final geometric rejection threshold to undo those earlier decisions. A score penalty alone
does not reject the track, and candidate length takes priority over score.

Point continuations are checked against a changing first-point-to-current-anchor line. Earlier observations are not revalidated when the line changes. The scoring function penalizes final line error, but it is a ranking preference, not an acceptance threshold; length also precedes score in the comparator.

Reproduction: an eleven-point curved sequence passes every incremental time-based gate and is accepted as one track, while an interior point is 6.311662 pixels from its final endpoint line, against predictionTolerance=3.

Fix direction: perform a final whole-track line fit and a whole-track position-versus-time fit before acceptance. For weak slow-motion hypotheses, also compare the evidence with a stationary alternative, rather than relying on the number of accepted points.

### 8. Initial streak chains lack a common-line check

Source: TrackLinker.java:358, 377, 411.

**In plain language:** a small angular allowance permits a large sideways error when streaks
are far apart. The initial linker checks each jump but never checks that the entire history
follows one sufficiently narrow line.

**Observing scenario:** fast trails occur in successive exposures. The first two lie on one
horizontal path. A similarly oriented trail in the third image is displaced sideways; it could
be another object or a detection whose center was affected by nearby structure.

| Frame | Capture time, seconds | Center (X,Y), pixels | Measured streak orientation |
| --- | --- | --- | --- |
| 0 | 0 | (40,40) | Horizontal |
| 1 | 1 | (540,40) | Horizontal |
| 2 | 2 | (1040,56) | Horizontal |

The first jump travels 500 pixels horizontally. The next travels 500 pixels horizontally and
16 pixels sideways, an angle of only about 1.83 degrees. That is inside the default two-degree
allowance, and the two speeds are very similar. The chain therefore passes, even though its
middle center is approximately eight pixels from the line joining the first and last centers.

**Trigger conditions:** detections are classified as streaks, their measured orientations agree,
long centroid jumps make the sideways shift appear angularly small, and time/motion checks pass.
The verified example has valid timestamps and positive exposure durations throughout. This
does not depend on missing metadata or the point-linking maximum-jump setting.

Initial fast-streak chaining checks local directions but does not enforce a common spatial line.
The numerical example's middle centroid is 7.998976 pixels from its endpoint line. Final
consolidation would reject that deviation when evaluating a new merge using the default six-pixel
line allowance, but it does not validate an already accepted track by itself.

Fix direction: apply a common whole-track geometric validation policy to initial and consolidated streak tracks. Local angular agreement alone does not enforce a pixel-distance limit over long jumps.

### 9. Suspected grouping loses its seed line

Source: TrackLinker.java:1212, 1223, 1364, 1741.

**In plain language:** the line that justified grouping the detections can differ from the line
a viewer or later merge derives from the exported track's first and last points.

**Observing scenario:** one image contains several bright fragments in a narrow strip, for
example a fragmented trail mixed with unrelated bright residuals. All five qualify for anomaly
rescue. Their centers are scattered above and below one horizontal seed line.

| Fragment | Center (X,Y), pixels | Distance from seed line Y=40 |
| --- | --- | --- |
| A | (40,40) | 0 |
| B | (50,40) | 0 |
| C | (20,46) | 6 |
| D | (120,46) | 6 |
| E | (70,34) | 6 |

The seed through A and B includes every fragment under the default six-pixel tolerance. Later,
the outermost points C and D define the ordering line at Y=46. Fragment E is then twelve pixels
away from that new line. A viewer drawing a line through the exported endpoints receives different
geometry from the geometry that originally justified the grouping.

**Trigger conditions:** anomaly rescue is enabled, all fragments are in the same frame, and at
least three meet the rescue thresholds. In the archived example, each footprint has 25 pixels
and peakSigma=12, so all five qualify; two other input frames are empty. All timestamps are
unknown. The issue concerns changing the line model after grouping; it does not mean the original
six-pixel seed test failed or that this suspected group must be rejected.

A grouping contains objects close to a selected seed line, but the seed line is not retained. Later ordering uses frame endpoints or the farthest pair, which is not necessarily the same line or a best-fit line. No final validation is performed for a standalone suspected grouping.

Reproduction: same-frame centroids (40,40), (50,40), (20,46), (120,46), and (70,34) all fit the horizontal seed at y=40 within the default six-pixel tolerance. They are exported as one suspected track. The ordered endpoint line is y=46, leaving the last point twelve pixels away.

This is a model-consistency risk, not evidence that no valid seed line existed: all points did fit the original seed. Consumers deriving a line from exported endpoints receive different geometry from the geometry used to accept the group.

Fix direction: retain the accepted line model or fit and validate a canonical line before exporting. Make ordering, consolidation, and downstream interpretation use that same model.

### 10. Fewer than three frames discards every output category

Source: TrackLinker.java:482.

**In plain language:** too few images for a point-motion track also causes the code to discard
other results that do not inherently need three images.

**Observing scenario:** a short capture sequence has only two usable images, or upstream quality
checks reduce a longer sequence to two. One surviving image contains a strong standalone trail.
The caller requests full tracking and expects at least the per-frame transient export or a
significant single-streak result, even though a three-point motion track cannot be established.

The direct reproduction passes two frames to findMovingObjects, with one horizontal streak
centered at (40,40) in the first frame and peakSigma=12. It receives no tracks, no anomalies,
empty transient lists, and no veto mask. The return happens before any of the object's normal
filtering or classification checks.

**Trigger conditions:** fewer than three frames reach findMovingObjects itself. Checking that the
original session had three or more images is insufficient if later filtering can reduce that
number. Conversely, this finding is excluded if the caller guarantees at least three frames at
that exact call. It is a short-input API behavior concern; the reproduction does not prove that
an image-level two-frame master stack would successfully preserve every proposed transient.

The early return skips filtering, standalone streak promotion, and anomaly rescue as well as
point linking. filterTransients can process this input, and standalone streak promotion does not
itself require three frames.

Fix direction: apply the minimum-frame condition to point-track discovery. Keep other categories and per-frame export shape available for shorter inputs, or explicitly reject unsupported input rather than silently returning an empty result.

## Additional concerns from source inspection

These were not benchmarked or reproduced as end-to-end image failures.

- **Greedy continuation and assignment.** Each seed produces one local continuation per frame. Time-based ranking only ranks these already greedy paths, and conflict resolution rejects a whole proposal if even one point is used. Streak and geometric paths are also greedy. Crossing objects and competing detections can therefore cause fragmentation or missed alternative tracks. Ranking is an improvement over first-come acceptance, but is not global assignment optimization.

  **Observing example:** two moving sources pass close to one another in a crowded field. One
  frame has an ambiguous blob compatible with both candidate tracks. Accepting the first proposal
  consumes that blob, so the second proposal is rejected in full, even if its other detections
  might still support a separate track. Alternatively, a nearby residual can be the locally best
  continuation but lead to a dead end later. Other seeds may recover an alternative, so this is a
  possible loss of useful associations rather than a guaranteed failure at every crossing.

- **Scaling.** With F frames and D detections per frame, the exhaustive time-based seed-and-extension loops can approach O(F^3 D^3) work. Retaining proposals can require O(F^3 D^2) detection references. Many baselines reproduce the same physical trajectory or its suffixes before conflict rejection. Same-frame anomaly grouping also enumerates all pairs and scans all anomalies repeatedly. Consider prediction-region spatial queries and proposal deduplication before increasing batch sizes.

  **Observing example:** a deep, crowded session is processed with a sensitive extraction
  threshold. Hundreds of residual candidates remain in many frames, despite only a few real
  movers. The linker repeatedly tries pairs and scans later frames, while several pairs describe
  the same mover. More input frames and more residuals can therefore increase time and memory
  much faster than the number of returned tracks. The growth follows the loops; no particular
  runtime or memory consumption was benchmarked for such a dataset.

- **Sparse frame identifiers.** groupSuspectedStreakTracks allocates a list for every integer up to the maximum sourceFrameIndex, rather than only represented frames. Since the engine preserves original sequence indices, a small subset with large indices can allocate many unnecessary lists. A map keyed by frame index avoids this dependency.

  **Observing example:** an application reprocesses only fifty images but preserves their
  original sequence indices, 100000 through 100049. Once an anomaly with the last index enters
  grouping, the code creates 100050 frame lists rather than the fifty actually needed. Most are
  empty. The trigger is a high original index, not a large number of images in the current run.

- **Timestamp convention, excluded under equal exposure durations.** Capture timestamps have no explicit start/midpoint convention. If callers provide exposure-start times and exposure durations vary, centroids from uniform motion correspond approximately to timestamp + exposureDuration / 2. Matching against raw start times can manufacture speed variation. With the expected equal durations, this is a common offset and cancels from time differences, so it is not a remaining issue for this workflow.

  **Observing example:** a camera changes between one-second and ten-second exposures, while
  the loader records each shutter-opening time. For uniform motion and brightness, the measured
  center corresponds approximately to half a second after opening in the first exposure and five
  seconds after opening in the second. Treating both centers as shutter-opening positions can
  distort the inferred speed even though every frame has a timestamp. Equal exposure durations
  give a common time offset and do not create this particular problem; timestamps already denoting
  exposure midpoints avoid it too.

- **Exposure limit approximation.** sqrt(pixelArea) is a footprint-area scale, not a measured trail length. Sources below the streak elongation threshold can still be moderately elongated, so the assumption that every point-category source moved less than this scale during an exposure may reject genuine movers. Glinting objects already have a documented reason to disable strictExposureKinematics; this additional shape-bound approximation deserves calibration.

  **Observing example:** a moving source leaves a moderately elongated blob in each long
  exposure, but its elongation is below the threshold for streak classification. It enters the
  point linker, where strictExposureKinematics uses its footprint area to estimate how far it
  could have moved. A long, narrow footprint can have a small square root of area relative to its
  length. An area-based bound can consequently be too restrictive for that measured shape.
  The relevant conditions are point classification, known long exposures, and an elongated
  footprint; an actual image-level rejection needs checking with the other gates as well.

- **Direct-call metadata defaults.** SourceExtractor.DetectedObject leaves timestamps at Java's default 0, although its documented unknown value is -1. The engine overwrites metadata, but a caller passing extractor objects directly to TrackLinker can accidentally disable geometric fallback. Public entrypoint preconditions should cover this, or object defaults should match the documented sentinel.

  **Integration example, outside the confirmed contract:** a custom application calls the
  extractor directly, groups the results by image, and calls the linker without filling timestamp
  fields. The default zero values are treated as supplied timestamps, but every time difference
  is zero. Properly assigning -1 to all untimed detections prevents this. The normal engine path
  that supplies validated metadata does not have this integration mistake.

- **Configuration invariants.** The three-point lower bound is applied before the absoluteMaxPointsRequired cap. A cap below three defeats that lower bound. Validate the supported parameter ranges rather than allowing invalid ratios and thresholds to silently alter confirmation semantics.

  **Configuration example:** a saved profile sets absoluteMaxPointsRequired=2, perhaps while
  experimenting with sparse detections. Although the code first calculates a minimum of three,
  the later cap reduces that minimum to two. A baseline can then satisfy the point-count condition
  without a third corroborating detection. This concern requires that unsupported setting; it
  does not occur with the default cap of five.

## Validation and artifacts

- All 36 existing TrackLinker tests passed using an isolated Gradle results directory.
- Twelve targeted review checks reproduced the behaviors above and failed their proposed correctness or consistency assertions. They are not twelve independent implementation defects; some exercise deliberately permissive heuristics and proposed stronger acceptance criteria.
- IntelliJ debugger logpoints collected 147 events with no breakpoint evaluation errors. The review paused at the end to retain events, and the observed JUnit result was 12 runs / 12 failures.
- The archived probes were rerun with positive six-sigma source peaks; the same twelve outcomes reproduced.
- The original review changed no production code, removed its temporary source-tree test class, and cleaned up review breakpoints and debugger sessions while preserving user breakpoints. The subsequent reassessments add only input-contract Javadocs to TrackLinker.java. Two archived review checks are outside the confirmed timestamp contract, and the missing-exposure check is not reached if the common positive duration is populated; the previous twelve failures should not be read as twelve applicable defects.
- The observing scenarios in this expanded document explain plausible triggers for the existing numerical reproductions. They are not additional image-based test results. Source references reflect the current Javadocs; the archived debugger events retain the line numbers from their earlier capture.

Artifacts are stored under `build/review/track-linker-assessment-20261009/`, rather than next to
this root-level assessment:

- [TrackLinkerAlgorithmReviewProbe.java](build/review/track-linker-assessment-20261009/TrackLinkerAlgorithmReviewProbe.java): standalone reproduction source with the exact coordinates and object setup.
- [RunProbes.ps1](build/review/track-linker-assessment-20261009/RunProbes.ps1): compiles the archived probes against the current production classes and runs JUnit without adding source-tree tests. Run the script by its path from the repository, or from its own directory; it uses the local Java path provided in setpaths.bat. Rebuild production classes before checking later fixes. The current expected exit status is 1 because the review assertions fail, including the two mixed-timestamp cases outside the confirmed input contract.
- [probe-output.txt](build/review/track-linker-assessment-20261009/probe-output.txt): reproduced outcomes with positive source peaks.
- [debugger-events.json](build/review/track-linker-assessment-20261009/debugger-events.json): collected values and exact source locations at the time of capture.
- [baseline/html](build/review/track-linker-assessment-20261009/baseline/html/index.html) and `baseline/xml`: existing test results.
- [probes/html](build/review/track-linker-assessment-20261009/probes/html/index.html) and `probes/xml`: original targeted-check results.

Recommended order under the confirmed contract: correct streak projection units and prevent final
consolidation from undoing chronological streak-continuation constraints; correct degenerate
motion checks; remove the unnecessary exposure-duration gate from streak timestamp collection;
replace distance-multiple rhythm with frame-gap-aware validation; then add whole-track geometric
and kinematic acceptance checks. The exposure-gate simplification is not an urgent correctness
fix if the common positive duration is always supplied. Address association strategy and scaling
after these acceptance defects are covered by focused regressions.
