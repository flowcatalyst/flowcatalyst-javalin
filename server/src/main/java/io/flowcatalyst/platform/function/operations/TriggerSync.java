package io.flowcatalyst.platform.function.operations;

import io.flowcatalyst.platform.function.Function;
import io.flowcatalyst.platform.function.FunctionVersion;
import io.flowcatalyst.platform.function.Manifest;
import io.flowcatalyst.sdk.usecase.ExecutionContext;
import io.flowcatalyst.sdk.usecase.jdbc.TxScopedUnitOfWork;

import java.util.List;

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
/// - [#checkPublish] — the non-throwing sibling of [#onPublish]'s cross-checks
///   (spec `function-manifest-authoring.md` M2.2): the SAME checks, collected
///   into a list instead of thrown at the first one — the `manifest/check`
///   route's `errors`.
/// - [#plan] — reads current state (trigger objects, the pool, `fn_routes`,
///   settings) and returns what promoting would do (spec M2.1). No writes.
/// - [#apply] — performs EXACTLY a [PromotePlan] a prior [#plan] call
///   produced: create what is missing, update what differs, delete what the
///   manifest no longer lists, creates/updates first then deletions last. No
///   difference at all ⇒ no write, no event (spec §10 V3). `onPromote` (the
///   pre-M2 entry point) is `apply(plan(...))` — promote and the
///   `manifest/check` dry-run route run the SAME `plan`.
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

    /// spec M2.2: the same checks [#onPublish] throws on, collected instead
    /// of thrown — used by the `manifest/check` route (and `onPublish`
    /// itself, which throws the first one it finds, same order).
    List<io.flowcatalyst.sdk.usecase.UseCaseError> checkPublish(Function function, Manifest manifest);

    /// spec M2.1: what promoting `manifest` as version `toVersion` to
    /// `alias` would do — no writes. `function` carries the CURRENT (pre-promote)
    /// alias state, used for [PromotePlan#fromVersion].
    PromotePlan plan(Function function, Manifest manifest, int toVersion, String alias);

    /// spec M2.1: performs EXACTLY `plan` — never re-derives it. `newLive`
    /// is the version being promoted (its manifest is the plan's own input);
    /// a no-op when `plan.wiring()` is [PromotePlan.Wiring.HttpOnly].
    void apply(TxScopedUnitOfWork scoped, Function function, FunctionVersion newLive, ExecutionContext ec,
            PromotePlan plan);

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
            public List<io.flowcatalyst.sdk.usecase.UseCaseError> checkPublish(Function function, Manifest manifest) {
                return List.of();
            }

            @Override
            public PromotePlan plan(Function function, Manifest manifest, int toVersion, String alias) {
                return new PromotePlan(alias, null, toVersion, List.of(), new PromotePlan.Wiring.HttpOnly(), List.of());
            }

            @Override
            public void apply(TxScopedUnitOfWork scoped, Function function, FunctionVersion newLive,
                    ExecutionContext ec, PromotePlan plan) {
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
