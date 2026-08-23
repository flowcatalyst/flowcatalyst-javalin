package io.flowcatalyst.platform.scheduledjob.cron;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

/// The poller's question to a job's schedule (spec §3.2): the latest slot
/// in a window across several expressions. Pure; the loops that ask it are
/// the data plane.
public final class Cron {

    private Cron() {
    }

    /// The **latest** slot of any of `crons` in the half-open window
    /// `(after, upTo]`, evaluated on the wall clock of `zone`, in UTC —
    /// skip-missed semantics: when several slots elapsed only the latest
    /// fires. Empty when no slot falls in the window or `after >= upTo`.
    /// Expressions that do not parse are skipped (a legacy row stored before
    /// the six-field ruling yields no slot rather than an error).
    public static Optional<Instant> latestSlotInWindow(List<String> crons, ZoneId zone, Instant after, Instant upTo) {
        return latestSlot(CronExpression.lenient(crons), zone, after, upTo);
    }

    /// [#latestSlotInWindow] for already-parsed expressions.
    public static Optional<Instant> latestSlot(List<CronExpression> crons, ZoneId zone, Instant after, Instant upTo) {
        if (!after.isBefore(upTo)) {
            return Optional.empty();
        }
        ZonedDateTime start = after.atZone(zone);
        Instant best = null;
        for (CronExpression cron : crons) {
            // Next() is strictly increasing, so the walk ends at the window's edge.
            for (var slot = cron.next(start); slot.isPresent() && !slot.get().toInstant().isAfter(upTo); slot = cron.next(slot.get())) {
                Instant at = slot.get().toInstant();
                if (best == null || at.isAfter(best)) {
                    best = at;
                }
            }
        }
        return Optional.ofNullable(best);
    }
}
