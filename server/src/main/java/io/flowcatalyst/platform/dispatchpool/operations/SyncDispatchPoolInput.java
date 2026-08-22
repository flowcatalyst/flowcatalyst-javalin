package io.flowcatalyst.platform.dispatchpool.operations;

import io.flowcatalyst.platform.dispatchpool.DispatchPool;

/// One pool definition in a [SyncDispatchPoolsCommand] batch.
///
/// @param code        validated as given — no trim, no lowercase (spec §4)
/// @param name        required
/// @param description optional
/// @param rateLimit   messages per minute; `null` = concurrency-only
/// @param concurrency max concurrent dispatches; `null` = default 10 ([DispatchPool#DEFAULT_CONCURRENCY]),
///                    applied by the operation — the handler maps the wire shape verbatim
public record SyncDispatchPoolInput(String code, String name, String description, Integer rateLimit, Integer concurrency) {
}
