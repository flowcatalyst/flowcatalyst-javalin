package io.flowcatalyst.http;

/// The admission-control / connection-pool groups (`docs/spec/admission.md`
/// §11.7, "groups and pools"). A registration made through `Routes.in(Group)`
/// carries the group so the adapter can apply the corresponding budget/pool;
/// [io.flowcatalyst.platform.shared.database.Pools#forGroup] maps every group
/// to one of the four physical [io.flowcatalyst.platform.shared.database.GatedDataSource]s.
public enum Group {
    /// Every route the message router calls: processing, settled, ingest —
    /// today's (pre-§11.7) `DISPATCH` and `INGEST` merged into one group. Its
    /// own pool (`DISPATCH`, ¼ of the budget) so a slow customer webhook or a
    /// busy ingest never occupies an `API` pool slot.
    DISPATCH,
    /// `/bff/**`: what the SPA calls, declared flatly regardless of
    /// read/write — its own pool (`BFF`, ¼ of the budget).
    BFF,
    /// An `/api/**` route whose handler runs an `Operation`/`TxOperation`
    /// (a use case in a transaction): the connection is pinned for the whole
    /// request. Served by the `API` pool.
    API_WRITE,
    /// Every other `/api/**` route — the default for an ungrouped `/api/`
    /// registration (`LockfileCoverageTest`'s sibling `RouteGroupTest`
    /// treats a `null` group on an `/api/` path as `API_READ`). Served by
    /// the `API` pool.
    API_READ,
    /// Login (CPU-bound); served by the `API` pool alongside `API_READ` /
    /// `API_WRITE` / `OIDC` — not wired at any registration in this unit
    /// (§11.7's "groups and pools" half only), kept for the workers unit.
    LOGIN,
    /// OIDC (CPU-bound); same pool and same "not wired yet" note as [#LOGIN].
    OIDC,
    /// Routes that never check out a database connection (the SPA, the OpenAPI
    /// documents, the router's in-memory API, the 404 path): nothing to queue for,
    /// so the listener runs them unbounded, one virtual thread each, never behind
    /// a database-bound request (owner ruling 2026-09-06). Declared at registration
    /// because the adapter cannot know before running; the pool gate still applies
    /// if such a route does touch the pool, so a wrong declaration costs a wait,
    /// never correctness.
    NO_DB;

    /// The request's admission mode (`docs/spec/admission.md` §11.7 part B,
    /// "The pool is chosen by the request, not by the handler class"):
    /// [Admission.Mode#PINNED] for a group whose handler holds one connection
    /// for the whole request ([#API_WRITE], [#DISPATCH], [#LOGIN], [#OIDC] —
    /// and [#NO_DB], which never checks out a connection under ordinary
    /// operation and keeps the gate's original pinned behaviour as its
    /// default if it ever does), [Admission.Mode#PER_STATEMENT] for a group
    /// that borrows a connection per statement ([#API_READ], [#BFF]).
    /// Exhaustive by construction — a new [Group] value fails to compile here
    /// until it picks one, never inherits a default silently.
    public Admission.Mode mode() {
        return switch (this) {
            case API_WRITE, DISPATCH, LOGIN, OIDC, NO_DB -> Admission.Mode.PINNED;
            case API_READ, BFF -> Admission.Mode.PER_STATEMENT;
        };
    }
}
