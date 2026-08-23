package io.flowcatalyst.platform.loginattempt;

/// Whether the attempt succeeded (spec §1). The constant name is the stored
/// and wire string.
public enum AttemptOutcome {
    SUCCESS, FAILURE;

    /// Lenient reader for stored values: unknown → `SUCCESS` (spec §1).
    public static AttemptOutcome parse(String s) {
        return "FAILURE".equals(s) ? FAILURE : SUCCESS;
    }
}
