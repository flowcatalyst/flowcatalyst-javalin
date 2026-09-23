package io.flowcatalyst.platform.function.operations;

import io.flowcatalyst.platform.function.FunctionAddress;

import java.util.Objects;

/// `PUT /api/functions/{address}/aliases/{alias}` (spec
/// `function-zones-and-aliases.md` §2). `alias` is carried straight through
/// from the path — `live` or any name matching `fn_aliases`' check
/// constraint; anything else is 400 `ALIAS_INVALID`, decided by
/// [io.flowcatalyst.platform.function.Function#requireValidAliasName], the
/// rule's one home (`PromoteVersion`'s `validate` phase calls it before this
/// command is even built).
///
/// @param address the target function; from the path
/// @param alias   the alias to point at `version`; from the path
/// @param version the version number to promote; from the body
public record PromoteCommand(FunctionAddress address, String alias, int version) {
    public PromoteCommand {
        Objects.requireNonNull(address, "address");
        Objects.requireNonNull(alias, "alias");
    }
}
