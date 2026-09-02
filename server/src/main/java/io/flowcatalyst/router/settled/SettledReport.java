package io.flowcatalyst.router.settled;

import java.util.List;

/// One `ackBuffered` event: the untried `BLOCK_ON_ERROR` siblings a pool just
/// ACKed off the broker because their group's head failed terminally, plus
/// context for logging on either side.
///
/// Only [#reason] and [#jobs] cross the wire
/// ([HttpSettledReporter]); [#poolCode] and [#group] are carried for the
/// reporter implementation's own diagnostics.
///
/// @param poolCode the pool the group belonged to
/// @param group    the message group, never the router's `""` ungrouped
///                 sentinel — a `BLOCK_ON_ERROR` head that produced this
///                 report was, by construction, in a real group
/// @param reason   why the siblings settled — `"head failed under
///                 BLOCK_ON_ERROR"` in production ([io.flowcatalyst.router.pool.Pool]
///                 builds the report)
/// @param jobs     the siblings that had a platform-signed auth token, in
///                 the FIFO order they were buffered — never empty; the
///                 caller does not report an empty batch
public record SettledReport(String poolCode, String group, String reason, List<SettledJob> jobs) {

    public SettledReport {
        jobs = List.copyOf(jobs);
    }
}
