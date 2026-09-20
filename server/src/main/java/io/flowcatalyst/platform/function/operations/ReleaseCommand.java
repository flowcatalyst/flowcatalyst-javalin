package io.flowcatalyst.platform.function.operations;

/// `ReleaseFunctionDomain`'s command (spec `function-public-routes.md` §1).
public record ReleaseCommand(String hostname) {
}
