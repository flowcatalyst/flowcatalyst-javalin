package io.flowcatalyst.router.observability;

import org.slf4j.LoggerFactory;

import java.util.Locale;

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

    /// Parses `FC_NOTIFY_MIN_SEVERITY` (alias `NOTIFICATION_MIN_SEVERITY`,
    /// spec §7.1/§10, X-04) into a [Severity], case-insensitively. `raw`
    /// being blank/`null` is the ordinary unset case and returns `WARNING`
    /// silently; a value that is *set* but does not name a [Severity] is an
    /// operator typo worth a WARN naming the bad value, degraded to
    /// `WARNING` rather than refused — an unparseable notifier floor is not
    /// a reason to fail router startup.
    ///
    /// Lives here, not on [io.flowcatalyst.server.Env], per `CONVENTIONS.md`
    /// §8 ("subsystem knobs reach the composition root through `Env`"):
    /// `Env` carries the raw string, and the composition root calls this
    /// value-taking parser — the same shape as
    /// `LeaderElection.Config#of(String)`.
    static Severity parseMinSeverity(String raw) {
        if (raw == null || raw.isBlank()) {
            return Severity.WARNING;
        }
        try {
            return Severity.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            LoggerFactory.getLogger(Warnings.class)
                    .warn("FC_NOTIFY_MIN_SEVERITY '{}' is not a valid severity; keeping the WARNING floor", raw);
            return Severity.WARNING;
        }
    }

    /// @param category coarse grouping — `ROUTING`, `POOL_CAPACITY`,
    ///                 `CONFIGURATION` — used to group and dedupe on the
    ///                 dashboard
    /// @param message  what an operator needs to read
    void raise(Severity severity, String category, String message);

    Warnings NO_OP = (severity, category, message) -> {
    };

    /// Fans one warning out to several sinks — typically the [WarningStore]
    /// the dashboard reads and a [WarningNotifier] that posts to a channel.
    ///
    /// Each sink is isolated: one that throws must not stop the others, and
    /// must not propagate into the delivery path that raised the warning. A
    /// warning is a side note about something that already went wrong, and it
    /// has no business making that worse.
    static Warnings tee(Warnings... sinks) {
        var all = java.util.List.of(sinks);
        return (severity, category, message) -> {
            for (var sink : all) {
                try {
                    sink.raise(severity, category, message);
                } catch (RuntimeException e) {
                    LoggerFactory.getLogger(Warnings.class)
                            .warn("a warning sink threw; continuing with the others", e);
                }
            }
        };
    }
}
