package io.flowcatalyst.function;

import java.util.Objects;

/// An HTTP gateway call: `address`/`invocationId` per [Invocation], plus the
/// [HttpRequest] itself.
public record HttpInvocation(FunctionAddress address, String invocationId, HttpRequest request)
        implements Invocation {

    public HttpInvocation {
        Objects.requireNonNull(address, "address");
        Objects.requireNonNull(invocationId, "invocationId");
        Objects.requireNonNull(request, "request");
    }
}
