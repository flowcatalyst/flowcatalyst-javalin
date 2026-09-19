package io.flowcatalyst.platform.function.operations;

import io.flowcatalyst.platform.function.FunctionAddress;

import java.util.Objects;

/// `PUT /api/functions/{address}/aliases/live` (spec `function-api.md`
/// §5.2). `alias` is carried here (not hard-coded to [io.flowcatalyst.platform.function.Function#LIVE])
/// so the API's `ALIAS_UNSUPPORTED` check for any other `{alias}` path
/// segment stays the handler's own 400, never reaching this command.
///
/// @param address the target function; from the path
/// @param alias   the path segment; always `live` by the time this command
///                is built — anything else is rejected before construction
/// @param version the version number to promote; from the body
public record PromoteCommand(FunctionAddress address, String alias, int version) {
    public PromoteCommand {
        Objects.requireNonNull(address, "address");
        Objects.requireNonNull(alias, "alias");
    }
}
