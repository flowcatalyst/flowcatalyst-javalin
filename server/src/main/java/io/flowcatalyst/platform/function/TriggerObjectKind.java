package io.flowcatalyst.platform.function;

/// The kind of platform-managed object a `fn_trigger_objects` row links to a
/// function (spec `function-invocation.md` §4): the function's one dispatch
/// pool, one of its event-type subscriptions, or one of its scheduled jobs.
/// The constant name is the stored/wire string (`fn_trigger_objects.kind`).
public enum TriggerObjectKind {
    POOL, SUBSCRIPTION, SCHEDULED_JOB;

    /// Stored reader — exact constant name, never a silent default (this
    /// table is Java-only and written only by this service, so an
    /// unrecognised value is corruption, not a foreign shape to tolerate).
    ///
    /// @throws IllegalArgumentException `raw` is not one of the three constants
    public static TriggerObjectKind parse(String raw) {
        return switch (raw) {
            case "POOL" -> POOL;
            case "SUBSCRIPTION" -> SUBSCRIPTION;
            case "SCHEDULED_JOB" -> SCHEDULED_JOB;
            case null, default -> throw new IllegalArgumentException("unrecognised trigger object kind: " + raw);
        };
    }
}
