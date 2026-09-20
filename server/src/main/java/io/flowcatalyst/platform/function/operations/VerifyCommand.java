package io.flowcatalyst.platform.function.operations;

/// `VerifyFunctionDomain`'s command (spec `function-public-routes.md` §1).
public record VerifyCommand(String hostname) {
}
