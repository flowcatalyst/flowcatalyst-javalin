package io.flowcatalyst.platform.function.operations;

import io.flowcatalyst.platform.function.FunctionAddress;

import java.util.Objects;

/// `DELETE /api/functions/{address}/aliases/{alias}` (spec
/// `function-zones-and-aliases.md` §2).
///
/// @param address the target function; from the path
/// @param alias   the alias to remove; from the path — `live` is refused by
///                [io.flowcatalyst.platform.function.Function#removeAlias]
///                itself, not filtered out here
public record RemoveAliasCommand(FunctionAddress address, String alias) {
    public RemoveAliasCommand {
        Objects.requireNonNull(address, "address");
        Objects.requireNonNull(alias, "alias");
    }
}
