package io.flowcatalyst.router.queue.nats;

import io.flowcatalyst.router.wire.Message;

/// What one JetStream-fetched message becomes, decided as a pure function
/// ([NatsQueue#classify]) so the malformed-payload rule (`docs/spec/router.md`
/// §7.1, §7.4: a malformed payload is termed — never acked, never nacked, so
/// it never redelivers) is testable without a live broker.
sealed interface FetchOutcome {

    /// The message's JetStream metadata was readable and its payload parses
    /// as [Message] — deliver it, keyed by `receipt` for later ack/nack.
    record Deliver(String receipt, String brokerMessageId, Message payload) implements FetchOutcome {
    }

    /// Metadata was unreadable (can't be tracked for ack/nack), or the
    /// payload is not valid JSON. Either way the message is termed so the
    /// broker stops redelivering it.
    record Malformed() implements FetchOutcome {

        static final Malformed INSTANCE = new Malformed();
    }
}
