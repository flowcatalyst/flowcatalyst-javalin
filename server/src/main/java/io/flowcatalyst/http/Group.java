package io.flowcatalyst.http;

/// The four tier-2 admission-control bulkhead groups (`docs/spec/admission.md`).
/// A registration made through `Routes.in(Group)` carries the group so the
/// adapter can apply the corresponding budget. In Phase 1 the Javalin
/// adapter records the group on the [RouteRegistry.Registration] and applies
/// no budget.
public enum Group {
    LOGIN, OIDC, DISPATCH, INGEST,
    /// Routes that never check out a database connection (the SPA, the OpenAPI
    /// documents, the router's in-memory API, the 404 path): nothing to queue for,
    /// so the listener runs them unbounded, one virtual thread each, never behind
    /// a database-bound request (owner ruling 2026-09-06). Declared at registration
    /// because the adapter cannot know before running; the pool gate still applies
    /// if such a route does touch the pool, so a wrong declaration costs a wait,
    /// never correctness.
    NO_DB
}
