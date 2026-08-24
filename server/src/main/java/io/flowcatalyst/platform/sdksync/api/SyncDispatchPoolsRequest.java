package io.flowcatalyst.platform.sdksync.api;

import io.flowcatalyst.platform.dispatchpool.operations.SyncDispatchPoolInput;
import io.flowcatalyst.platform.dispatchpool.operations.SyncDispatchPoolsCommand;

import java.util.List;

/// `SyncDispatchPoolsRequest` (lockfile): `{pools[]: {code, name, description,
/// rateLimit, concurrency}}`. `concurrency` travels as-is (`null` = absent);
/// the operation applies the default.
public record SyncDispatchPoolsRequest(List<Input> pools) {

    public record Input(String code, String name, String description, Integer rateLimit, Integer concurrency) {
        SyncDispatchPoolInput toInput() {
            return new SyncDispatchPoolInput(code, name, description, rateLimit, concurrency);
        }
    }

    SyncDispatchPoolsCommand toCommand(String applicationId, String applicationCode, boolean removeUnlisted) {
        return new SyncDispatchPoolsCommand(applicationId, applicationCode,
                pools == null ? List.of() : pools.stream().map(Input::toInput).toList(), removeUnlisted);
    }
}
