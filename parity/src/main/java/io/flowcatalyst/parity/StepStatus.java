package io.flowcatalyst.parity;

/// One step's outcome in the report (parity-harness spec §6).
public enum StepStatus {
    /// Both sides agree after normalisation.
    OK,
    /// The sides disagree, but every diff is allow-listed in `expected-diffs.json`.
    ACCEPTED,
    /// The sides disagree and at least one diff is not allow-listed — this is what makes the run fail.
    DIFF,
    /// The step itself broke: an `expect.status` mismatch, an undefined `${…}`, a missing capture
    /// pointer, or a transport failure. Not a parity finding — a harness/scenario problem.
    ERROR
}
