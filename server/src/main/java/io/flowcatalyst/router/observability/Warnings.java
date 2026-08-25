package io.flowcatalyst.router.observability;

/// Operator-visible conditions the router wants someone to know about
/// (`docs/spec/router.md` §2.7).
///
/// An interface so the router's decisions can be asserted without a warning
/// store, and so the pieces that raise warnings do not depend on the surface
/// that displays them.
///
/// Lives beside [WarningStore] rather than in `manager`: the raisers are now
/// spread across `manager`, `pool` and `lifecycle`, and a shared contract that
/// sits inside one of its callers' packages makes every other caller depend on
/// that package for no reason.
public interface Warnings {

    enum Severity { INFO, WARNING, ERROR, CRITICAL }

    /// @param category coarse grouping — `ROUTING`, `POOL_CAPACITY`,
    ///                 `CONFIGURATION` — used to group and dedupe on the
    ///                 dashboard
    /// @param message  what an operator needs to read
    void raise(Severity severity, String category, String message);

    Warnings NO_OP = (severity, category, message) -> {
    };
}
