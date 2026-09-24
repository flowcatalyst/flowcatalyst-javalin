package io.flowcatalyst.platform.function.operations;

import java.util.List;
import java.util.Objects;

/// What promoting a manifest to `alias` would do, read from current state and
/// never applied by itself (spec `function-manifest-authoring.md` M2.1).
/// [TriggerSync#plan] computes it with no writes; [TriggerSync#apply] performs
/// EXACTLY this plan (creates/updates first, deletions last, `PUBLIC_ROUTE_TAKEN`/
/// `TRIGGER_KEY_COLLISION` behaviour unchanged) — promote and the
/// `manifest/check` dry-run route share this one computation, never two.
///
/// For a **named alias** (spec `function-zones-and-aliases.md` §2: "no wiring
/// change — HTTP-only by ruling"), [#wiring] is [Wiring.HttpOnly]: the
/// pool/subscription/schedule/public-route reconciliation this record would
/// otherwise carry never runs for anything but `live`.
///
/// @param alias           the alias being promoted (`live` or a named alias)
/// @param fromVersion     the alias's version before this promote; `null` when the alias is unset
/// @param toVersion       the version being promoted to this alias
/// @param settingsMissing config/secret keys [PromoteVersion]'s settings check would find
///                        missing, first-seen order — non-empty does NOT by itself mean
///                        [#wiring] is [Wiring.HttpOnly] or that [#conflicts] is non-empty
/// @param wiring          the pool/subscription/schedule/public-route diff, or [Wiring.HttpOnly]
///                        for a named alias
/// @param conflicts       what would make [TriggerSync#apply] of this plan fail
public record PromotePlan(String alias, Integer fromVersion, int toVersion, List<String> settingsMissing,
                           Wiring wiring, List<Conflict> conflicts) {

    public PromotePlan {
        Objects.requireNonNull(alias, "alias");
        Objects.requireNonNull(settingsMissing, "settingsMissing");
        settingsMissing = List.copyOf(settingsMissing);
        Objects.requireNonNull(wiring, "wiring");
        Objects.requireNonNull(conflicts, "conflicts");
        conflicts = List.copyOf(conflicts);
    }

    /// A reason [TriggerSync#apply] of this plan would fail — `PUBLIC_ROUTE_TAKEN`
    /// or `TRIGGER_KEY_COLLISION` (spec `function-invocation.md` §4, §6 M4).
    public record Conflict(String code, String message) {
        public Conflict {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(message, "message");
        }
    }

    /// The wiring half of the plan. [Live] carries the four reconciliation
    /// diffs; [HttpOnly] is the whole story for a named alias (spec §2).
    public sealed interface Wiring {

        record Live(PoolAction pool, List<SubscriptionAction> subscriptions, List<ScheduleAction> schedules,
                    PublicRoutesAction publicRoutes) implements Wiring {
            public Live {
                Objects.requireNonNull(pool, "pool");
                Objects.requireNonNull(subscriptions, "subscriptions");
                subscriptions = List.copyOf(subscriptions);
                Objects.requireNonNull(schedules, "schedules");
                schedules = List.copyOf(schedules);
                Objects.requireNonNull(publicRoutes, "publicRoutes");
            }
        }

        record HttpOnly() implements Wiring {
        }
    }

    // ── pool: one per function, never deleted ────────────────────────────

    public sealed interface PoolAction {
        String key();

        record Create(String key) implements PoolAction {
        }

        record Update(String key, List<String> changedFields) implements PoolAction {
            public Update {
                changedFields = List.copyOf(changedFields);
            }
        }

        record Unchanged(String key) implements PoolAction {
        }
    }

    // ── subscriptions, per event type ────────────────────────────────────

    public sealed interface SubscriptionAction {
        String triggerKey();

        String eventType();

        record Create(String triggerKey, String eventType) implements SubscriptionAction {
        }

        record Update(String triggerKey, String eventType, List<String> changedFields) implements SubscriptionAction {
            public Update {
                changedFields = List.copyOf(changedFields);
            }
        }

        record Delete(String triggerKey, String eventType) implements SubscriptionAction {
        }

        record Unchanged(String triggerKey, String eventType) implements SubscriptionAction {
        }
    }

    // ── schedules, per (cron, timezone) ──────────────────────────────────

    public sealed interface ScheduleAction {
        String triggerKey();

        String cron();

        /// `null` = no timezone named, same absence as [Manifest.ScheduleSpec#timezone].
        String timezone();

        record Create(String triggerKey, String cron, String timezone) implements ScheduleAction {
        }

        record Update(String triggerKey, String cron, String timezone, List<String> changedFields)
                implements ScheduleAction {
            public Update {
                changedFields = List.copyOf(changedFields);
            }
        }

        record Delete(String triggerKey, String cron, String timezone) implements ScheduleAction {
        }

        record Unchanged(String triggerKey, String cron, String timezone) implements ScheduleAction {
        }
    }

    // ── public routes: one set-valued diff, not one action per route ────

    public sealed interface PublicRoutesAction {
        record Replace(List<RouteKey> added, List<RouteKey> removed) implements PublicRoutesAction {
            public Replace {
                Objects.requireNonNull(added, "added");
                added = List.copyOf(added);
                Objects.requireNonNull(removed, "removed");
                removed = List.copyOf(removed);
            }
        }

        record Unchanged() implements PublicRoutesAction {
        }
    }

    /// One `public[]` entry's key, on the plan (spec `function-zones-and-aliases.md`
    /// §3: `aliasPrefixes` is part of the identity — a route that only gained or
    /// lost a prefix is still a difference).
    public record RouteKey(String hostname, String pathPrefix, List<String> aliasPrefixes) {
        public RouteKey {
            Objects.requireNonNull(hostname, "hostname");
            Objects.requireNonNull(pathPrefix, "pathPrefix");
            Objects.requireNonNull(aliasPrefixes, "aliasPrefixes");
            aliasPrefixes = List.copyOf(aliasPrefixes);
        }
    }
}
