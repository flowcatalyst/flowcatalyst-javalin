package io.flowcatalyst.platform.function.operations;

import io.flowcatalyst.platform.function.FunctionAddress;

/// `DeleteFunctionSecret`'s command (spec `function-context.md` §1).
public record DeleteSecretCommand(FunctionAddress address, String key) {
}
