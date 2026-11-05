# Reading the archived evidence

## Identity, units and denominators

Use the investigation directory together with `dataset`, `cohort`, `case` and `policy` to identify a row. A case number or a count such as 1,910 can occur in different studies with different source positions, seeds, parameters and association definitions. Never combine those rows by case number alone.

Positions, displacement, path RMS and shape extents are in pixels unless explicitly labelled otherwise. Digital-tracking motion is in pixels/minute; retained frame timestamps are UTC milliseconds. Formal covariance and empirical residuals are different quantities. Nominal aperture SNR, peak-to-pixel-noise ratios, filtered-frame noise units and the digital-tracking score are not interchangeable or calibrated detection significance.

“Associated” describes predefined source/path error conditions after blind search. It does not establish independent physical motion, discovery, identity probability or source dominance. “Candidate load” counts reported proposals and can repeat the same source or background across cases. A gain/loss is meaningful only within the paired comparison and its available denominator.

The five development datasets and the other thirteen are separated where required. “Held out” means held out from that earlier study, not that the observation never appeared in another investigation. M31 bands share a target; later experiments reuse existing sessions. Reported intervals apply to their stated selected grid/target clustering and are not survey-wide uncertainty bounds.

## Construction, measurement and rejection

Some tables count components before frame gates; others count measured histories or final associated cases. Ordered first-failure counts are conditional populations and cannot prove that a later gate is redundant. Extraction/mask changes rebuild proposals; rescoring old survivors cannot recover a missing component.

The residual-position study's association definition embeds 40% support. The later joint gate study instead requires at least three active-frame positions, at least 80% of reported positions in illuminated frames and at most 2 px RMS. It therefore investigates the support floor without assuming it in association. Neither definition is calibrated motion confidence, especially for short or partially observed paths.

SM-F6 uses path RMS within one search FWHM and motion discrepancy within 0.1 px/min, with predefined mean motion for curves. Each source counts once, irrespective of repeated candidates. Its same-policy native-blank contact audit is an additional post-hoc attribution check. Source-photon noise is omitted, and upstream QC/streak/ordinary-track context is frozen: these are faint-stage sensitivity tests, not full-pipeline injection recovery.

## Unavailable and bounded evidence

Keep timing, exposure, fill/crop, saturation, fit failure, missing bins and model eligibility explicit. An excluded source is not a detector rejection. Lack of a candidate does not establish that an unsearched or out-of-model source is false. Camera sources in the latest grid either depart from a constant-motion locus by too much or drift below the nominal coarse-speed range, so zero associations do not cleanly test camera rejection.

Coverage completion and candidate completeness are separate. Paired half/clipping removal reaches the 1,000-candidate ceiling in two SM-F6 flash cases, making total candidate load a lower bound. All six planned flash positions are associated in each capped field; the one unassociated flash is in an uncapped field. The observed 35/36 source count remains determined for that specific grid. Production's `belowScore` can include ceiling drops. Missing bounded review pixels do not reject a candidate.

Blind image-third searches rebuild median references, conditional on full-session QC/data-area context. Several full-session motions fall below the subsets' nominal coarse-motion floors; refinement must not be equated with a strict hard lower-speed acceptance boundary. A positionally close early FM2 result misses the fixed velocity tolerance. Such failures are retained rather than tuning the criterion after observing them.

## Catalogue and privacy

The final candidate-local V=23 comparisons are post-detection compatibility checks with the admitted corrected WCS. Initial request failures remain unavailable. Formal orbit 3-sigma values omit centroid/WCS uncertainty, native passband is not calibrated to predicted V, and unknown-magnitude omissions limit completeness. The earlier five unmatched K2 labels are not negative truth; all eleven original paths have compatible predictions after deeper review.

Absolute machine paths become portable artifact identifiers in selected numerical evidence. No observing-site headers, saved application settings or observer-containing request URLs are bundled. Finite scientific values are preserved; non-finite JSON values would be explicitly encoded as `NaN`, `Infinity` or `-Infinity` strings and counted in provenance, rather than replaced by zero. Current selected evidence contains no such conversion. CSV boolean/unavailable fields retain their textual representations.

## Compression and integrity

`.json.gz` and `.csv.gz` use deterministic, lossless gzip. Decompress with a standard gzip reader, then parse JSON/CSV. They contain detailed case/history tables behind the smaller summaries. Text uses LF under the local `.gitattributes`, making `archive-manifest.json` hashes stable across checkouts. `provenance.json` separately records original artifact hashes and serialization/newline/portable-path changes.

PNG figures retain their original bytes. Generated SVG trailing whitespace is normalized for repository checks; parsed vector geometry, attributes and text tokens are compared with the originals. This formatting change does not redraw a figure or change its numerical content.

Source hashes and original-image integrity records describe their recorded snapshots. They do not imply every historical test suite was rerun for this documentation-only publication. Validation for code deliveries is listed with its commit in [CODE-HISTORY.md](CODE-HISTORY.md).
