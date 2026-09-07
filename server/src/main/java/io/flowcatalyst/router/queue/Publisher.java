package io.flowcatalyst.router.queue;

import io.flowcatalyst.router.wire.Message;

import java.util.List;

/// A queue backend's write side (`docs/spec/router.md` §7.1) — the
/// counterpart to [Consumer]. Used by the router's own `POST /messages` /
/// `POST /api/seed/messages` (§9.1) to publish onto the same broker a
/// [Consumer] built for the same queue reads from.
///
/// Only the Postgres backend implements this today
/// ([io.flowcatalyst.router.queue.postgres.PostgresQueue]) — SQS and NATS
/// publishing is out of scope; a queue whose backend has no [Publisher]
/// answers 503 "no publisher" rather than silently degrading.
public interface Publisher {

    /// Same identity a [Consumer] for the same queue reports — the key that
    /// selects this backend for `pool_code` resolution (§5 #61) and the
    /// `queue_identifier` on the wire response.
    String identifier();

    /// Publishes one message. `message.id()` must already be set — callers
    /// default an absent request id to a fresh UUID before calling this, the
    /// same as Go's handler.
    ///
    /// @return the broker's id for the delivery (Postgres: `message.id()`
    ///         itself, even when a row with that id already existed —
    ///         §7.3 "Publish")
    /// @throws PublishException the publish failed; the caller reports 502
    String publish(Message message) throws PublishException;

    /// Publishes a batch. Partial results are the general contract (§7.1);
    /// the Postgres backend specifically aborts the whole batch on any
    /// failure (§7.3 "PublishBatch": "loop of Publish; error aborts and
    /// returns nil ids") rather than returning what succeeded, so a thrown
    /// [PublishException] here means **nothing** in `messages` was durably
    /// published.
    ///
    /// @return one broker id per input message, in the same order, on success
    List<String> publishBatch(List<Message> messages) throws PublishException;

    /// The publish failed. Carries the underlying cause; the caller (§9.1)
    /// reports 502.
    final class PublishException extends Exception {
        public PublishException(String message, Throwable cause) {
            super(message, cause);
        }

        public PublishException(String message) {
            super(message);
        }
    }
}
