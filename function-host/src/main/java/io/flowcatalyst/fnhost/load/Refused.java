package io.flowcatalyst.fnhost.load;

import java.util.Objects;

/// The jar was rejected before (or while) loading. `detail` is a
/// human-readable specific — the offending entry name, the missing
/// entrypoint class name, or the instantiation failure's message.
public record Refused(Reason reason, String detail) implements LoadOutcome {

    public Refused {
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(detail, "detail");
    }
}
