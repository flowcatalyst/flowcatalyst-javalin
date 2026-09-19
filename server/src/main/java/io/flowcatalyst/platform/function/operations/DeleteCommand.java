package io.flowcatalyst.platform.function.operations;

import io.flowcatalyst.platform.function.FunctionAddress;

/// `DeleteFunction`'s command (spec `function-api.md` §4.2).
public record DeleteCommand(FunctionAddress address) {
}
