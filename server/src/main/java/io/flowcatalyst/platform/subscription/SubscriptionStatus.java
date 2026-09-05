package io.flowcatalyst.platform.subscription;

/// The subscription lifecycle state: `ACTIVE` ⇄ `PAUSED` (spec §2). Only
/// `ACTIVE` subscriptions take part in fan-out. The constant name is the
/// stored and wire string.
public enum SubscriptionStatus {
    ACTIVE, PAUSED;

    /// Strict reader for stored values (spec §1, X-06): never a silent
    /// default. See [SubscriptionRepository]'s row mapper, which wraps
    /// [UnrecognisedSubscriptionStatusException] in
    /// [CorruptSubscriptionException] carrying the row id.
    ///
    /// @throws UnrecognisedSubscriptionStatusException `s` is `null` or not
    ///                                                 `ACTIVE` / `PAUSED`
    public static SubscriptionStatus parse(String s) {
        return switch (s) {
            case "ACTIVE" -> ACTIVE;
            case "PAUSED" -> PAUSED;
            case null, default -> throw new UnrecognisedSubscriptionStatusException(s);
        };
    }

    /// Thrown by [#parse] for a stored value outside the recognised set —
    /// X-06: never a silent default.
    public static final class UnrecognisedSubscriptionStatusException extends RuntimeException {
        public UnrecognisedSubscriptionStatusException(String raw) {
            super("unrecognised subscription status: " + raw);
        }
    }
}
