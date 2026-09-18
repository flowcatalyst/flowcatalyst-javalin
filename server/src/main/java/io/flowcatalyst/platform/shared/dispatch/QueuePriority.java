package io.flowcatalyst.platform.shared.dispatch;

import io.flowcatalyst.sdk.usecase.UseCaseException;

import java.util.Locale;
import java.util.Optional;

/// The subscription's dispatch priority (ruling R1/R1a,
/// `docs/go-mirror/2026-09-12-dispatch-rulings.md`): which of the client's
/// two queues — `…-{client}-DEFAULT` / `…-{client}-HIGH_PRIORITY` — a
/// dispatch job raised from this subscription publishes to. The constant
/// name is the stored form of `msg_subscriptions.queue` and the wire value
/// of the `queue` field on subscription create/update.
///
/// **Validation is case-insensitive (R1a, owner ruling 2026-09-12).** The
/// server embeds a Go-repo frontend build it cannot change independently,
/// and that SPA's create form already sends `queue: "default"` on every
/// create, required. Matching case-sensitively would 400 every UI create;
/// matching leniently on the two names lets the existing field work
/// unchanged. Turning the SPA's field into a two-value dropdown is a
/// tidy-up for the other repo, not a prerequisite here.
public enum QueuePriority {

    DEFAULT, HIGH_PRIORITY;

    /// Trims and upper-cases before matching (R1a), so `"default"`,
    /// `"Default"` and `"DEFAULT"` all parse to [#DEFAULT]. `null` or blank
    /// input means "no priority set" and returns `null` — never an error;
    /// the column stays nullable and create/update may omit the field
    /// entirely. Only a non-blank, unrecognised value is rejected.
    ///
    /// @throws UseCaseException validation `INVALID_QUEUE` for a non-blank
    ///                          value that is neither name, ignoring case
    public static QueuePriority parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return switch (raw.trim().toUpperCase(Locale.ROOT)) {
            case "DEFAULT" -> DEFAULT;
            case "HIGH_PRIORITY" -> HIGH_PRIORITY;
            default -> throw UseCaseException.validation("INVALID_QUEUE", "queue must be DEFAULT or HIGH_PRIORITY");
        };
    }

    /// The **read** counterpart of [#parse], for the publish path (ruling R6):
    /// which queue a stored `msg_subscriptions.queue` value routes to.
    ///
    /// Deliberately lenient where [#parse] is strict, and the asymmetry is the
    /// whole point. Write validation cannot reach rows that already exist: every
    /// row created before ruling R1 holds `NULL`, and legacy Go-era rows hold
    /// arbitrary text — staging carries Integral queue segments such as
    /// `workers-high`. Absent, blank and unrecognised all yield [#DEFAULT], and
    /// **this never throws**, because the scheduler calls it while claiming a
    /// batch: a validation exception there would strand every job on the
    /// offending subscription rather than routing it to the normal lane.
    ///
    /// The accepted cost, recorded in ruling R6: a legacy `workers-high` row is
    /// treated as normal priority with nothing announcing the downgrade. The
    /// owner chose that over warning on it or refusing to publish.
    public static QueuePriority forPublishing(String stored) {
        if (stored == null || stored.isBlank()) {
            return DEFAULT;
        }
        return switch (stored.trim().toUpperCase(Locale.ROOT)) {
            case "HIGH_PRIORITY" -> HIGH_PRIORITY;
            default -> DEFAULT;
        };
    }

    /// The **job's own** read counterpart of [#parse] (ruling R4,
    /// `docs/spec/dispatch-job-priority.md`): reads `msg_dispatch_jobs.queue`
    /// — distinct from [#forPublishing] because a caller MUST be able to
    /// tell "the job itself named a recognised priority" from "the job said
    /// nothing" — an empty [Optional] means the latter, and
    /// [io.flowcatalyst.platform.scheduler.DispatchDestinationResolver]
    /// falls through to the subscription's own priority only in that case
    /// (R4: job wins when present, subscription decides when absent, else
    /// `DEFAULT`).
    ///
    /// Never throws, for the same reason [#forPublishing] never does: an
    /// error here would strand a job that happens to carry legacy text in
    /// its own column rather than simply falling through to the
    /// subscription lookup.
    public static Optional<QueuePriority> forJob(String stored) {
        if (stored == null || stored.isBlank()) {
            return Optional.empty();
        }
        return switch (stored.trim().toUpperCase(Locale.ROOT)) {
            case "DEFAULT" -> Optional.of(DEFAULT);
            case "HIGH_PRIORITY" -> Optional.of(HIGH_PRIORITY);
            default -> Optional.empty();
        };
    }
}
