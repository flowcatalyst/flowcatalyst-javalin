package io.flowcatalyst.platform.function.operations;

import io.flowcatalyst.platform.function.FunctionAddress;

import java.util.Objects;

/// `POST /api/functions/{address}/versions/{v}/retire` (spec
/// `function-api.md` §5.2).
///
/// @param address the target function; from the path
/// @param version the version number to retire; from the path
public record RetireCommand(FunctionAddress address, int version) {
    public RetireCommand {
        Objects.requireNonNull(address, "address");
    }
}
