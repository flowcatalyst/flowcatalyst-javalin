package io.flowcatalyst.platform.scheduledjob;

/// The lifecycle state of one firing (spec §6.1): `QUEUED` → `IN_FLIGHT` →
/// `DELIVERED` → `COMPLETED` | `FAILED`; `DELIVERY_FAILED` once the delivery
/// attempts are exhausted. `DELIVERED` is terminal unless the job tracks
/// completion. The constant name is the stored and wire string.
public enum InstanceStatus {
    QUEUED, IN_FLIGHT, DELIVERED, COMPLETED, FAILED, DELIVERY_FAILED;

    /// Lenient reader: unknown → `QUEUED`, so a list query never drops rows
    /// on a future schema extension (spec §6.1).
    public static InstanceStatus parse(String s) {
        return switch (s == null ? "" : s) {
            case "IN_FLIGHT" -> IN_FLIGHT;
            case "DELIVERED" -> DELIVERED;
            case "COMPLETED" -> COMPLETED;
            case "FAILED" -> FAILED;
            case "DELIVERY_FAILED" -> DELIVERY_FAILED;
            default -> QUEUED;
        };
    }
}
