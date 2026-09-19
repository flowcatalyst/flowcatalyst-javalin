package io.flowcatalyst.platform.function.operations;

import io.flowcatalyst.platform.function.Function;
import io.flowcatalyst.platform.function.FunctionVersion;
import io.flowcatalyst.sdk.usecase.ExecutionContext;
import io.flowcatalyst.sdk.usecase.jdbc.TxScopedUnitOfWork;

/// The seam a function version's manifest turns into platform-managed
/// wiring (spec `function-invocation.md` §4): a dispatch pool, event-type
/// subscriptions and scheduled jobs, one `fn_trigger_objects` row each.
/// Every method runs INSIDE the caller's open transaction — a failure rolls
/// the whole publish/promote/delete/status-change back with it.
///
/// - [#onPublish] — VALIDATE ONLY (spec §4 first paragraph): event types
///   exist and are not archived, cron/timezone parse, an application signing
///   secret exists when the manifest has any subscriptions/schedules, and
///   warm capacity is not exceeded. Nothing is written — no aggregate, no
///   event — so it takes no [ExecutionContext].
/// - [#onPromote] — RECONCILE desired-from-the-new-live-manifest against
///   actual-from-`fn_trigger_objects`: create what is missing, update what
///   differs, delete what the manifest no longer lists. No difference at
///   all ⇒ no write, no event (spec §10 V3).
/// - [#onDelete] — removes every object the function owns, through each
///   object's own delete transition.
/// - [#onStatusChange] — `DISABLED` pauses every linked subscription and
///   job (never the pool); back to `ACTIVE` resumes them.
///
/// Every object write goes through that object's OWN aggregate transition
/// and event — a function's subscription is a real subscription with a
/// real audit trail, never a bespoke `fn_` write path.
public interface TriggerSync {

    void onPublish(TxScopedUnitOfWork scoped, Function function, FunctionVersion version);

    void onPromote(TxScopedUnitOfWork scoped, Function function, FunctionVersion newLive, FunctionVersion previousLive,
            ExecutionContext ec);

    void onDelete(TxScopedUnitOfWork scoped, Function function, ExecutionContext ec);

    void onStatusChange(TxScopedUnitOfWork scoped, Function function, ExecutionContext ec);

    /// No trigger sync at all — every method is a no-op. Package B3's
    /// wiring for `PublishVersion` before this package existed, and every
    /// test that exercises a function operation without caring about its
    /// wiring consequences.
    static TriggerSync none() {
        return new TriggerSync() {
            @Override
            public void onPublish(TxScopedUnitOfWork scoped, Function function, FunctionVersion version) {
            }

            @Override
            public void onPromote(TxScopedUnitOfWork scoped, Function function, FunctionVersion newLive,
                    FunctionVersion previousLive, ExecutionContext ec) {
            }

            @Override
            public void onDelete(TxScopedUnitOfWork scoped, Function function, ExecutionContext ec) {
            }

            @Override
            public void onStatusChange(TxScopedUnitOfWork scoped, Function function, ExecutionContext ec) {
            }
        };
    }
}
