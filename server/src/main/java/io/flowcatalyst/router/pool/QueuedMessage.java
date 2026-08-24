package io.flowcatalyst.router.pool;

import io.flowcatalyst.router.wire.Message;

/// A [Message] together with what the broker needs to acknowledge it.
///
/// The broker identity is kept beside the message rather than inside it: the
/// message is the queue's payload and is identical across a redelivery, while
/// the receipt handle is not. Conflating them is how a redelivered copy ends
/// up acknowledged with a stale handle.
///
/// @param message        the payload, identical across redeliveries
/// @param brokerMessageId the broker's own id for this delivery
/// @param receiptHandle   the token that acknowledges *this* delivery
/// @param queueId         which configured queue delivered it, so the right
///                        consumer acknowledges it back
/// @param attempts        delivery attempts already made by this router.
///                        Zero on first dispatch; greater than zero means the
///                        message is legitimately retrying, which is how the
///                        stall detector and the in-flight reaper tell a
///                        retrying message from a stuck one.
public record QueuedMessage(Message message, String brokerMessageId, String receiptHandle,
                            String queueId, int attempts) {

    public QueuedMessage {
        if (attempts < 0) {
            throw new IllegalArgumentException("attempts must not be negative: " + attempts);
        }
    }

    public static QueuedMessage of(Message message, String brokerMessageId, String receiptHandle, String queueId) {
        return new QueuedMessage(message, brokerMessageId, receiptHandle, queueId, 0);
    }

    /// The same delivery, one attempt further on.
    public QueuedMessage retrying() {
        return new QueuedMessage(message, brokerMessageId, receiptHandle, queueId, attempts + 1);
    }

    /// The same message with a fresher receipt handle, as a redelivery
    /// supplies. The attempt count is kept: the work already done on this
    /// message is not undone by the broker handing us a new token for it.
    public QueuedMessage withReceiptHandle(String freshHandle) {
        return new QueuedMessage(message, brokerMessageId, freshHandle, queueId, attempts);
    }

    /// The message group, or `""` when ungrouped.
    public String group() {
        return message.groupId();
    }

    /// Whether this message must be sequenced within its group.
    public boolean ordered() {
        return message.ordered();
    }

    public String id() {
        return message.id();
    }
}
