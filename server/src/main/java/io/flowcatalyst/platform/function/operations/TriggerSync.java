package io.flowcatalyst.platform.function.operations;

import io.flowcatalyst.platform.function.Function;
import io.flowcatalyst.platform.function.FunctionVersion;
import io.flowcatalyst.sdk.usecase.jdbc.TxScopedUnitOfWork;

/// The named seam `PublishVersion` leaves for turning a version's manifest
/// triggers into subscriptions and dispatch pools (spec `function-api.md`
/// §5.1 step 8; `function-triggers.md` is the spec that will replace
/// [#none]). Runs INSIDE `PublishVersion`'s open transaction, after the
/// version itself is committed — a trigger that cannot be honoured throws
/// and rolls the whole publish back (spec §8 P7).
///
/// Deliberately minimal: only the publish-time hook exists so far. A later
/// package extends this interface as promote/retire grow their own trigger
/// consequences.
public interface TriggerSync {

    void onPublish(TxScopedUnitOfWork scoped, Function function, FunctionVersion version);

    /// This package's wiring (spec §7 slice B3): no trigger sync yet.
    static TriggerSync none() {
        return (scoped, function, version) -> {
        };
    }
}
