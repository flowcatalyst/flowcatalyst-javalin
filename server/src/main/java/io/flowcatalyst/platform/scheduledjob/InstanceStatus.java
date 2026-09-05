package io.flowcatalyst.platform.scheduledjob;

/// The lifecycle state of one firing (spec §6.1): `QUEUED` → `IN_FLIGHT` →
/// `DELIVERED` → `COMPLETED` | `FAILED`; `DELIVERY_FAILED` once the delivery
/// attempts are exhausted. `DELIVERED` is terminal unless the job tracks
/// completion. The constant name is the stored and wire string.
public enum InstanceStatus {
    QUEUED, IN_FLIGHT, DELIVERED, COMPLETED, FAILED, DELIVERY_FAILED;

    /// Strict reader for STORED values (spec §6.1, X-06): never a silent
    /// default. See [ScheduledJobInstanceRepository]'s row mapper, which
    /// wraps [UnrecognisedInstanceStatusException] in
    /// [CorruptScheduledJobException] carrying the row id.
    ///
    /// This is the stored reader only. The `?status=` query filter and the
    /// completion request's `status` field ([#parseWire]) are separate,
    /// deliberately lenient wire rules, untouched by X-06.
    ///
    /// @throws UnrecognisedInstanceStatusException `s` is `null` or not one
    ///                                             of the six recognised states
    public static InstanceStatus parse(String s) {
        return switch (s) {
            case "QUEUED" -> QUEUED;
            case "IN_FLIGHT" -> IN_FLIGHT;
            case "DELIVERED" -> DELIVERED;
            case "COMPLETED" -> COMPLETED;
            case "FAILED" -> FAILED;
            case "DELIVERY_FAILED" -> DELIVERY_FAILED;
            case null, default -> throw new UnrecognisedInstanceStatusException(s);
        };
    }

    /// The pre-X-06 lenient reader, kept ONLY for the two wire call sites in
    /// `ScheduledJobApi` (the `?status=` list filter and the completion
    /// request's `status` field): unknown → `QUEUED`, so a filter/field
    /// typo never 500s. Wire-side, out of scope for X-06.
    public static InstanceStatus parseWire(String s) {
        return switch (s == null ? "" : s) {
            case "IN_FLIGHT" -> IN_FLIGHT;
            case "DELIVERED" -> DELIVERED;
            case "COMPLETED" -> COMPLETED;
            case "FAILED" -> FAILED;
            case "DELIVERY_FAILED" -> DELIVERY_FAILED;
            default -> QUEUED;
        };
    }

    /// Thrown by [#parse] for a stored value outside the recognised set —
    /// X-06: never a silent default.
    public static final class UnrecognisedInstanceStatusException extends RuntimeException {
        public UnrecognisedInstanceStatusException(String raw) {
            super("unrecognised scheduled job instance status: " + raw);
        }
    }
}
