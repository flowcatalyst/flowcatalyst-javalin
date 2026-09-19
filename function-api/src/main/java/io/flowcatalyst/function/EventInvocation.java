package io.flowcatalyst.function;

import java.util.Objects;

/// An event delivery: `address`/`invocationId` per [Invocation], plus the
/// [Event] itself.
public record EventInvocation(FunctionAddress address, String invocationId, Event event) implements Invocation {

    public EventInvocation {
        Objects.requireNonNull(address, "address");
        Objects.requireNonNull(invocationId, "invocationId");
        Objects.requireNonNull(event, "event");
    }
}
