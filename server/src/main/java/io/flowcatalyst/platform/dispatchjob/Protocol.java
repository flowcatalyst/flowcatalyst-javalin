package io.flowcatalyst.platform.dispatchjob;

/// The delivery transport (spec §1.1). `HTTP_WEBHOOK` is the only one, and
/// the stored column is not consulted on read — every job reads as
/// `HTTP_WEBHOOK` (open question 11). The constant name is the stored and
/// wire string.
public enum Protocol {
    HTTP_WEBHOOK;

    /// Lenient reader: every stored value → `HTTP_WEBHOOK`.
    public static Protocol parse(String s) {
        return HTTP_WEBHOOK;
    }
}
