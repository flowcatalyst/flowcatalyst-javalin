package io.flowcatalyst.platform.scheduler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/// The degraded-mode [DispatchPublisher]: claimed jobs are marked `QUEUED`
/// and then simply drop — never delivered, and never reverted, since this
/// implementation never fails. [PendingJobPoller] still runs; nothing
/// recovers a claimed row (the stale-`QUEUED` sweep was removed, owner
/// ruling 2026-09-22 — a broker-held job is the broker's). `Server` wires
/// this in only when no real broker is configured
/// (`FC_DEFAULT_BROKER != "postgres"`, or no database URL) — matching Go's
/// `schedulerPublisher` fallback (`internal/server/subsystems.go:85-117`),
/// "explicitly called out as unsafe for production."
public final class NoopPublisher implements DispatchPublisher {

    private static final Logger LOG = LoggerFactory.getLogger(NoopPublisher.class);

    @Override
    public void publish(List<PublishedMessage> batch) {
        if (batch.isEmpty()) return;
        LOG.atWarn().setMessage("scheduler NOOP publisher: dispatch job(s) claimed but NOT delivered; "
                        + "set FC_DEFAULT_BROKER=postgres (with a database URL) or wire a real publisher "
                        + "before enabling FC_SCHEDULER_ENABLED in production")
                .addKeyValue("count", batch.size())
                .log();
    }
}
