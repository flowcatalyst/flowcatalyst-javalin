package io.flowcatalyst.platform.function.operations;

import io.flowcatalyst.platform.function.FunctionOwner;

import java.util.Objects;

/// `ClaimFunctionDomain`'s command (spec `function-public-routes.md` §1):
/// `owner` absent `clientId` ⇒ platform-owned (built by the API from the raw
/// wire `clientId`, same as [CreateCommand]/[PutPolicyCommand]).
public record ClaimCommand(FunctionOwner owner, String hostname) {
    public ClaimCommand {
        Objects.requireNonNull(owner, "owner");
    }
}
