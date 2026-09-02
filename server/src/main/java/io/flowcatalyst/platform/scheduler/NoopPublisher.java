package io.flowcatalyst.platform.scheduler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/// The degraded-mode [DispatchPublisher]: claimed jobs are marked `QUEUED`
/// and then simply drop — never delivered, and never reverted, since this
/// implementation never fails. [PendingJobPoller] still runs, and a claimed
/// row is only recovered by
/// [StaleQueuedJobPoller] once it has sat `QUEUED` past the stale-recovery
/// window. `Server` wires this in only when no real broker is configured
/// (`FC_DEFAULT_BROKER != "postgres"`, or no database URL) — matching Go's
/// `schedulerPublisher` fallback (`internal/server/subsystems.go:85-117`),
/// "explicitly called out as unsafe for production."
public final class NoopPublisher implements DispatchPublisher {

    private static final Logger LOG = LoggerFactory.getLogger(NoopPublisher.class);

    @Override
    public void publish(List<PublishedMessage> batch) {
        if (batch.isEmpty()) return;
        LOG.warn("scheduler NOOP publisher: {} dispatch job(s) claimed but NOT delivered; "
                        + "set FC_DEFAULT_BROKER=postgres (with a database URL) or wire a real publisher "
                        + "before enabling FC_SCHEDULER_ENABLED in production",
                batch.size());
    }
}
