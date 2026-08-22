/// The FlowCatalyst use-case envelope — the Java interpretation of
/// `pkg/fcsdk/{usecase,usecaseop,usecasepgx}` from the Go platform.
///
/// Every state-changing business operation is an
/// [io.flowcatalyst.sdk.usecase.op.Operation] with four named phases that
/// `Operation.run(...)` executes in a fixed order:
///
/// ```
/// Validate   command shape (presence, format, length) — pure, no DB
/// Authorize  resource-level access — REQUIRED, or Authorize.publicAccess() to declare open
/// Execute    invariant checks (loads, business rules) → returns a Plan
/// ────────── run takes over ──────────
/// apply      persist aggregate + write domain event + write audit log,
///            atomically in ONE transaction
/// ```
///
/// What is structural (not convention) here:
///
///   - Validation and authorization always run because `run` runs them; an
///     operation can leave a phase empty but cannot skip it.
///   - Authorization is never silently absent: the `Operation` constructor
///     and the staged builder both refuse a missing `Authorize`. "Intentionally
///     open" is spelled `Authorize.publicAccess()` — a visible, greppable decision.
///   - An operation cannot reach the database except by returning a
///     [io.flowcatalyst.sdk.usecase.op.Plan] — a sealed interface whose only
///     implementations are `Save`, `Delete`, `Emit`, `SaveAll`, `Sync` and
///     whose only consumer is `run`. `Execute` receives no unit of work, so
///     there is no path to a persisted aggregate that does not also write the
///     domain event and the audit log in the same transaction.
///
/// The Go code needs an unexported interface method plus an `internal/`
/// token to seal this; Java's `sealed` keyword and the absence of a unit of
/// work in `Execute` give the same guarantee with less machinery. For the same
/// reason the Go `usecase.Result` carrier is not ported: `run` returns the
/// committed event and throws [io.flowcatalyst.sdk.usecase.UseCaseException]
/// for every failure, and the sealed [io.flowcatalyst.sdk.usecase.UseCaseError]
/// is what transports switch on.
package io.flowcatalyst.sdk.usecase;
