package io.flowcatalyst.http;

/// The four tier-2 admission-control bulkhead groups (`docs/spec/admission.md`).
/// A registration made through `Routes.in(Group)` carries the group so the
/// adapter can apply the corresponding budget. In Phase 1 the Javalin
/// adapter records the group on the [RouteRegistry.Registration] and applies
/// no budget.
public enum Group {
    LOGIN, OIDC, DISPATCH, INGEST
}
