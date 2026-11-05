# Consistent non-finite representation in diagnostic parity

Dated investigation snapshot. Current behavior remains in [ALGORITHM.md](../../../../ALGORITHM.md) and pending decisions in [FEATURE-REQUESTS.md](../../../../FEATURE-REQUESTS.md). Local executable/cache names describe external diagnostics; see the [retained inventory](README.md).

The first admitted-grid diagnostic completed all twelve prior cases, Apophis and Africano, then stopped at dc-reg case03. The comparison parsed the saved special-value JSON but compared it with a numeric Gson tree: an unavailable half-score becomes a string-like parsed `NaN` on one side and a numeric `NaN` on the other. That representation mismatch caused the assertion even though all four candidate values, cutout/diagnostic hashes and non-time counters exactly match after consistent serialization. The failed output, log, resource record, frozen source and binaries remain unchanged.

Correct only the diagnostic comparison by serializing and parsing the new summary through the same path as the saved summary. Preserve unavailable values; do not replace them with zero, omit them or relax a candidate/hash/counter check. A separate source/class/controller manifest records this correction before execution. Accepted old-grid, Apophis and Africano outputs are reused with sealed result hashes. Rerun all dc-reg cases and the not-yet-run m5-reg diagnostics into a separate directory; accepted-diagnostic-runs.json identifies the accepted field outputs without overwriting the failed run.

No primary comparison, proposal/verifier math, source construction, scientific threshold, input or production source changes. As before, one isolated 8 GiB field JVM at a time, before the separately queued full-upstream confirmation.
