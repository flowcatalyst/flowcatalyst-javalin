package io.flowcatalyst.platform.shared.dispatch;

import java.util.Objects;

/// A dispatch queue's platform-composed name (settled item 1,
/// `docs/go-mirror/2026-09-12-dispatch-rulings.md`): Integral's convention,
/// `{prefix}-{tenant}-{priority}`, `.fifo`-suffixed for SQS —
/// `FC-staging-acme-DEFAULT.fifo`, `FC-staging-acme-HIGH_PRIORITY.fifo`.
///
/// **Only the tenant segment is sanitised** — `_`, `.` and space each become
/// `-`, exactly as Integral sanitises. A conforming
/// [io.flowcatalyst.platform.client.ClientIdentifier] is already a
/// lower-case slug with none of those characters, so this is a no-op in the
/// ordinary case; it is kept anyway, defensively, because that is what
/// Integral's own convention does unconditionally.
///
/// **The priority segment is never sanitised.** `HIGH_PRIORITY` keeps its
/// underscore — legal in an SQS queue name, and exactly what the settled
/// examples above show. [QueuePriority#name()] is always one of the two enum
/// constants here, never free text, so there is nothing to sanitise on that
/// side in any case.
///
/// @param value the composed name, `.fifo`-suffixed when composed for SQS
public record DispatchQueueName(String value) {

    /// SQS's hard cap on a queue name, `.fifo` included
    /// (`docs/spec/deployed-dispatch.md` §3 "Risks to pin with tests").
    public static final int SQS_MAX_LENGTH = 80;

    public DispatchQueueName {
        Objects.requireNonNull(value, "value");
    }

    /// Composes `{prefix}-{sanitise(tenant)}-{priority}`, `.fifo`-suffixed
    /// when `sqs` is true. Every deployed dispatch queue is FIFO (ruling
    /// item 2, "It must be FIFO" — per-group ordering is load-bearing), so
    /// `sqs` alone decides both the suffix and whether the length cap
    /// applies; there is no non-FIFO SQS case in this document.
    ///
    /// A blank `prefix` omits that segment entirely rather than refusing —
    /// the SQS deployment requirement that the prefix be set is enforced
    /// once, at startup, by
    /// [io.flowcatalyst.platform.dispatch.DispatchQueueSettings#resolve]
    /// (spec §3 "Risks to pin with tests"), so `sqs` is `true` here only
    /// when a prefix is already known to be present; a Postgres (dev) name
    /// with no prefix configured is a normal, if less tidy, name rather than
    /// a startup error.
    ///
    /// @throws IllegalArgumentException `tenant` is blank — a blank tenant
    ///                                  would produce a shared lane rather
    ///                                  than the intended per-client
    ///                                  isolation
    /// @throws QueueNameTooLongException the composed SQS name (with
    ///                                   `.fifo`) exceeds [#SQS_MAX_LENGTH];
    ///                                   never thrown for a Postgres name,
    ///                                   which has no such limit
    public static DispatchQueueName compose(String prefix, String tenant, QueuePriority priority, boolean sqs) {
        if (tenant == null || tenant.isBlank()) {
            throw new IllegalArgumentException("tenant is required to compose a dispatch queue name");
        }
        Objects.requireNonNull(priority, "priority");
        String prefixSegment = (prefix == null || prefix.isBlank()) ? "" : prefix + "-";
        String base = prefixSegment + sanitise(tenant) + "-" + priority.name();
        String composed = sqs ? base + ".fifo" : base;
        if (sqs && composed.length() > SQS_MAX_LENGTH) {
            throw new QueueNameTooLongException(tenant, composed);
        }
        return new DispatchQueueName(composed);
    }

    /// Integral's tenant sanitisation: `_`, `.` and space each become `-`.
    private static String sanitise(String tenant) {
        return tenant.replace('_', '-').replace('.', '-').replace(' ', '-');
    }

    /// Thrown by [#compose] when the SQS form would exceed [#SQS_MAX_LENGTH]
    /// — `tnt_clients.identifier` is `varchar(100)`, comfortably wider than
    /// SQS allows once the fixed segments (`prefix`, `-DEFAULT`/`-HIGH_PRIORITY`,
    /// `.fifo`) are added, so this is a reachable condition, not defensive
    /// dead code. The document builder catches this per tenant and omits
    /// that tenant rather than failing the whole document.
    public static final class QueueNameTooLongException extends RuntimeException {
        private final String tenant;

        QueueNameTooLongException(String tenant, String composed) {
            super("dispatch queue name \"" + composed + "\" (" + composed.length() + " chars) exceeds SQS's "
                    + SQS_MAX_LENGTH + "-character limit for tenant \"" + tenant + "\"");
            this.tenant = tenant;
        }

        public String tenant() {
            return tenant;
        }
    }
}
