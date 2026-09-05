package io.flowcatalyst.parity;

import java.util.List;

/// One step's comparison result (parity-harness spec §6).
///
/// @param diffs      every structural disagreement found (empty for `OK`/`ERROR`)
/// @param unaccepted the subset of `diffs` not covered by `expected-diffs.json` — what makes the
///                    status `DIFF` rather than `ACCEPTED`
/// @param goRecord   the raw Go response; kept on anything that is not `OK` (`null` when Go's side
///                    of the step never got a response at all)
/// @param javaRecord the raw Java response, same rule
/// @param error      the `ERROR` message (Go's, Java's, or both, joined) — `null` otherwise
public record StepResult(String stepId, StepStatus status, List<DiffEntry> diffs, List<DiffEntry> unaccepted,
                          StepRecord goRecord, StepRecord javaRecord, String error) {
    public StepResult {
        diffs = List.copyOf(diffs);
        unaccepted = List.copyOf(unaccepted);
    }
}
