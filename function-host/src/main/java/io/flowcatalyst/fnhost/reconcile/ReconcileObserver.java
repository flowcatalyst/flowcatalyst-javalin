package io.flowcatalyst.fnhost.reconcile;

import java.time.Instant;

/// What [Reconciler] tells an interested caller about its own runs — kept
/// free of any metrics-library type (`docs/spec/function-host-process.md`
/// §2) so [Reconciler] itself never imports Prometheus; [io.flowcatalyst.fnhost.metrics.FnMetrics]
/// is the one real implementation, wired in by [io.flowcatalyst.fnhost.FnHost].
/// Every method defaults to a no-op — an ordinary "nobody is listening"
/// callback contract (`ReconcileLoop`'s own `Runnable` listeners are the same
/// shape), not a sealed outcome type `CONVENTIONS.md` §8 warns against
/// defaulting.
public interface ReconcileObserver {

    ReconcileObserver NOOP = new ReconcileObserver() {
    };

    /// A prepare/load failure reason (`ARTIFACT:...`, `SIGNATURE:...`,
    /// `SIGNER_MISMATCH`, `UNSIGNED`, `LOAD:...`) —
    /// spec §2's `fc_fn_load_errors_total{reason}`. Deliberately NOT called
    /// for `NO_SIGNING_SECRET` (`Reconciler#checkSigningSecrets`) — that is
    /// an auth-configuration state, not a load failure (spec
    /// `function-host-listener.md` §3).
    default void loadError(String reason) {
    }

    /// One [Reconciler#reconcileOnce] cycle's fetch outcome — spec §2's
    /// `fc_fn_reconcile_total{outcome}` and
    /// `fc_fn_last_reconcile_success_timestamp_seconds`. `outcome` is
    /// `changed`, `not_modified` or `failed`; `success` is true for
    /// `changed` and `not_modified` alike (P6: "not-modified is a success —
    /// the platform answered"; only a control-plane outage is a failure).
    default void reconciled(String outcome, boolean success, Instant now) {
    }
}
