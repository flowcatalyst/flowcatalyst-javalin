package io.flowcatalyst.platform.function.operations;

import java.util.Objects;

/// `MarkVersionReady`'s command (spec `function-api.md` §6.2): the resolved
/// version and the host that reported it. Not built from a request DTO —
/// `FunctionControlApi`'s heartbeat handler resolves address → function →
/// version itself (spec §6.2 step 2) and calls this once per newly-`Published`
/// entry a live host reports `ok()`.
///
/// @param versionId the `FunctionVersion` to mark ready
/// @param hostId    the reporting host — carried onto the `version:ready` event (spec §3)
public record MarkVersionReadyCommand(String versionId, String hostId) {
    public MarkVersionReadyCommand {
        Objects.requireNonNull(versionId, "versionId");
        Objects.requireNonNull(hostId, "hostId");
    }
}
