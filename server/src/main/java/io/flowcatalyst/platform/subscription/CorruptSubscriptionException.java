package io.flowcatalyst.platform.subscription;

import io.flowcatalyst.platform.shared.CorruptRowException;

/// A row read from `msg_subscriptions` whose `status` or `source` column
/// holds a value [SubscriptionStatus#parse] / [SubscriptionSource#parse]
/// does not recognise (X-06: never a silent default). Carries the offending
/// row's id.
public final class CorruptSubscriptionException extends CorruptRowException {

    public CorruptSubscriptionException(String subscriptionId, Throwable cause) {
        super("subscription", subscriptionId, cause);
    }
}
