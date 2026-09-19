package io.flowcatalyst.platform.sdksync.api;

import io.flowcatalyst.platform.dispatchpool.operations.SyncDispatchPoolInput;
import io.flowcatalyst.platform.dispatchpool.operations.SyncDispatchPoolsCommand;

import java.util.List;
import java.util.Set;

/// `SyncDispatchPoolsRequest` (lockfile): `{pools[]: {code, name, description,
/// rateLimit, concurrency}}`. `concurrency` travels as-is (`null` = absent);
/// the operation applies the default.
public record SyncDispatchPoolsRequest(List<Input> pools) {

    public record Input(String code, String name, String description, Integer rateLimit, Integer concurrency) {
        SyncDispatchPoolInput toInput() {
            return new SyncDispatchPoolInput(code, name, description, rateLimit, concurrency);
        }
    }

    /// @param protectedIds pool ids a function owns (`function-invocation.md`
    ///                      §4.2), read by the handler from
    ///                      `TriggerObjectRepository` — this DTO carries no
    ///                      `fn_` knowledge of its own.
    SyncDispatchPoolsCommand toCommand(String applicationId, String applicationCode, boolean removeUnlisted,
                                        Set<String> protectedIds) {
        return new SyncDispatchPoolsCommand(applicationId, applicationCode,
                pools == null ? List.of() : pools.stream().map(Input::toInput).toList(), removeUnlisted,
                protectedIds);
    }
}
