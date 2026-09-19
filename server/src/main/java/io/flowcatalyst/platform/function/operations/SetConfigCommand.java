package io.flowcatalyst.platform.function.operations;

import io.flowcatalyst.platform.function.FunctionAddress;

import java.util.Map;

/// `SetFunctionConfig`'s command (spec `function-context.md` §1): a full
/// replacement of the function's config map.
public record SetConfigCommand(FunctionAddress address, Map<String, String> values) {
    public SetConfigCommand {
        values = values == null ? Map.of() : Map.copyOf(values);
    }
}
