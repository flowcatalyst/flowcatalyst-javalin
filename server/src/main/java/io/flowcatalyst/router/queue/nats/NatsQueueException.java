package io.flowcatalyst.router.queue.nats;

/// Unchecked: a [NatsQueue] failure that is genuinely exceptional rather than
/// an expected outcome (CONVENTIONS §8) — connecting, provisioning the
/// stream/durable consumer, or fetching failed outright.
///
/// `ack`/`nack` never throw this — see [NatsQueue#ack] and [NatsQueue#nack].
/// A malformed payload never throws this either — it is termed in [#poll],
/// not surfaced as a failure (`docs/spec/router.md` §7.1, §7.4).
public final class NatsQueueException extends RuntimeException {

    public NatsQueueException(String message, Throwable cause) {
        super(message, cause);
    }
}
