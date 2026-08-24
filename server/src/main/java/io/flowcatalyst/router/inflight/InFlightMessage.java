package io.flowcatalyst.router.inflight;

import java.time.Duration;
import java.time.Instant;

/// A snapshot of one message this process currently owns
/// (`docs/spec/router.md` §2.3). Surfaced on the monitoring API.
///
/// @param messageId       the application id — the tracker's primary key
/// @param brokerMessageId the broker's id for the delivery; `""` when the
///                        backend does not supply one, in which case the
///                        entry is not indexed by it
/// @param poolCode        as it arrived on the message, **unresolved** — an
///                        empty pool code stays empty here, so an operator
///                        can see that the message named no pool rather than
///                        seeing the fallback and assuming it did
/// @param queueIdentifier which consumer delivered it
/// @param startedAt       when this process took ownership
/// @param lastSeenAt      refreshed on every redelivery. The reaper ages on
///                        this, not on `startedAt`: a message being
///                        redelivered is alive, however long ago it started
/// @param messageGroupId  `""` when ungrouped
/// @param batchId         the poll batch it arrived in
/// @param receiptHandle   the **freshest** handle, swapped on redelivery.
///                        Acknowledging with a stale handle silently leaves
///                        the message to redeliver forever
/// @param attempts        delivery attempts made. Greater than zero means the
///                        message is legitimately retrying, which is how the
///                        reaper and the stall detector tell it from a stuck
///                        one
public record InFlightMessage(String messageId, String brokerMessageId, String poolCode,
                              String queueIdentifier, Instant startedAt, Instant lastSeenAt,
                              String messageGroupId, String batchId, String receiptHandle,
                              int attempts) {

    /// Whole seconds this message has been owned, for the monitoring surface.
    public long elapsedSeconds(Instant now) {
        return Duration.between(startedAt, now).toSeconds();
    }

    /// Whether this entry is retrying rather than potentially stuck.
    public boolean retrying() {
        return attempts > 0;
    }
}
