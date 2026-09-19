package io.flowcatalyst.fnhost.http;

import io.flowcatalyst.platform.function.FunctionAddress;

import java.time.Duration;

/// What [FnHttpServer] tells an interested caller about the request
/// pipeline — kept free of any metrics-library type
/// (`docs/spec/function-host-process.md` §2: "keep `FnHttpServer` free of
/// Prometheus types"); [io.flowcatalyst.fnhost.metrics.FnMetrics] is the one
/// real implementation, wired in through [FnHttpServer.Options] by
/// [io.flowcatalyst.fnhost.FnHost]. Every method defaults to a no-op — an
/// ordinary "nobody is listening" callback contract, not a sealed outcome
/// type `CONVENTIONS.md` §8 warns against defaulting.
///
/// `outcome` strings are the spec §2 table's own vocabulary: `ok`,
/// `client_error`, `retry`, `error`, `timeout`, `busy`, `unauthorized`,
/// `unavailable` — plus `not_found`, this host's own reading (flagged in the
/// slice's handback report) for a 404 that reveals nothing about whether an
/// address/version exists at all (spec §2 step 3's unversioned
/// `FUNCTION_NOT_FOUND`, and §4's deliberately-indistinguishable
/// `VERSION_NOT_AVAILABLE`) — always reported with `address = null`
/// (rendered as the label value `-`) so an attacker probing addresses can
/// never turn `/metrics` into an oracle for which ones exist.
public interface InvocationObserver {

    InvocationObserver NOOP = new InvocationObserver() {
    };

    /// Once, from [FnHttpServer#start], the moment the per-listener
    /// [Permits] exists — spec §2's `fc_fn_permits_available` needs it for
    /// its scrape-time callback and [Permits] is otherwise owned entirely by
    /// [FnHttpServer].
    default void permitsReady(Permits permits) {
    }

    /// The host refused the call before the function was ever entered:
    /// `busy` (a permit was refused — `address` is always known, since
    /// permits are only checked once an entry has resolved), `unauthorized`
    /// (webhook/platform/versioned auth failed — `address` known for an
    /// unversioned call, `null` for a versioned one, spec §4's anti-leak
    /// requirement), `unavailable` (an unversioned lazy load failed —
    /// `address` known) or `not_found` (`address` always `null`, see above).
    default void refused(String outcome, FunctionAddress address) {
    }

    /// The function was entered (permits + load succeeded; the worker
    /// thread is starting) — `fc_fn_active` rises.
    default void entered(FunctionAddress address) {
    }

    /// The worker thread that ran the invocation has actually returned,
    /// thrown, or had its `Function#handle` frame unwind after an interrupt
    /// — `fc_fn_active` falls. For a timed-out call this fires later than
    /// [#completed] (spec §2 step 9, H7: the gauge shows the truth until the
    /// worker really stops, not at the moment the deadline fires).
    default void exited(FunctionAddress address) {
    }

    /// The outcome the CALLER received for an entered invocation —
    /// `fc_fn_invocations_total{address,version,outcome}` and
    /// `fc_fn_duration_seconds{address}` (observed only here — spec §2 P3:
    /// "only when the function was entered").
    default void completed(FunctionAddress address, int version, String outcome, Duration elapsed) {
    }
}
