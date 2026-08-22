package io.flowcatalyst.platform.subscription;

/// The subscription lifecycle state: `ACTIVE` ⇄ `PAUSED` (spec §2). Only
/// `ACTIVE` subscriptions take part in fan-out. The constant name is the
/// stored and wire string.
public enum SubscriptionStatus {
    ACTIVE, PAUSED;

    /// Lenient reader for stored values: unknown → `ACTIVE` (spec §1).
    public static SubscriptionStatus parse(String s) {
        return switch (s == null ? "" : s) {
            case "PAUSED" -> PAUSED;
            default -> ACTIVE;
        };
    }
}
