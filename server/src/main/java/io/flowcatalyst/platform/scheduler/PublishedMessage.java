package io.flowcatalyst.platform.scheduler;

import io.flowcatalyst.router.wire.Message;

import java.time.Instant;
import java.util.Objects;

/// One claimed dispatch job, resolved to the exact [Message] the router's
/// wire contract requires (dispatch-seam spec §2) — `poolCode`, `authToken`,
/// `mediationTarget`, `messageGroupId` and `dispatchMode` all filled in at
/// publish time by [PendingJobPoller].
///
/// @param jobId     the claimed row's id — [Message#id()] verbatim
/// @param createdAt the row's own `created_at`, carried alongside so a
///                  publish failure's revert (spec §3, step 6) can bound the
///                  partition-pruned `UPDATE` the same way mark-QUEUED did
/// @param message   the wire payload to publish
public record PublishedMessage(String jobId, Instant createdAt, Message message) {

    public PublishedMessage {
        Objects.requireNonNull(jobId, "jobId");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(message, "message");
    }
}
