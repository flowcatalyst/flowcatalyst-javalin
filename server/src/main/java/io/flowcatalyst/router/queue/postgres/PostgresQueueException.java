package io.flowcatalyst.router.queue.postgres;

/// Unchecked: a [PostgresQueue] failure that is genuinely exceptional rather
/// than an expected outcome (CONVENTIONS §8) — a poll/schema-init call that
/// failed outright, or (`docs/spec/router.md` §7.3, §13 Q17) a malformed
/// payload, which fails the whole poll rather than being skipped the way SQS
/// acks a malformed message or NATS terms it.
///
/// `ack`/`nack` never throw this — see [PostgresQueue#ack] and
/// [PostgresQueue#nack].
public final class PostgresQueueException extends RuntimeException {

    public PostgresQueueException(String message, Throwable cause) {
        super(message, cause);
    }
}
