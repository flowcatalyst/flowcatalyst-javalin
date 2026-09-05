package io.flowcatalyst.outbox;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/// Per-group liveness (spec §5): `RUNNING` | `PAUSED` | `BLOCKED{itemId,
/// error}`. A group absent from the backing map IS `RUNNING` — only
/// `PAUSED` and `BLOCKED` ever occupy an entry, so [#groupStates] (the
/// admin `GET /outbox/groups` listing, spec §7) is exactly the groups an
/// operator would want to see, not every group id ever claimed.
public final class GroupStateManager {

    /// One group's liveness.
    public sealed interface GroupState permits GroupState.Running, GroupState.Paused, GroupState.Blocked {

        /// Never stored — its absence from the map is what this state means.
        record Running() implements GroupState {
        }

        record Paused() implements GroupState {
        }

        record Blocked(String itemId, String error) implements GroupState {
        }
    }

    /// One group's state for the admin surface (spec §7): `status` is
    /// `RUNNING` / `PAUSED` / `BLOCKED`; `blockedItemId`/`error` are `null`
    /// unless `status` is `BLOCKED`.
    public record GroupInfo(String group, String status, String blockedItemId, String error) {
    }

    private final ConcurrentHashMap<String, GroupState> states = new ConcurrentHashMap<>();

    /// A group with no entry, or explicitly [GroupState.Running], may accept
    /// new work; `PAUSED` and `BLOCKED` may not (spec §5).
    public boolean isActive(String group) {
        return switch (states.get(group)) {
            case null -> true;
            case GroupState.Running ignored -> true;
            case GroupState.Paused ignored -> false;
            case GroupState.Blocked ignored -> false;
        };
    }

    // ── the four transitions (spec §5) ──────────────────────────────────────

    /// Unconditional: a permanent failure blocks the group regardless of its
    /// prior state.
    public void block(String group, String itemId, String error) {
        states.put(group, new GroupState.Blocked(itemId, error));
    }

    /// Only from running/absent (spec §5) — a paused or already-blocked
    /// group is left alone.
    ///
    /// @return whether this call paused the group
    public boolean pause(String group) {
        var changed = new AtomicBoolean(false);
        states.compute(group, (g, cur) -> {
            if (cur == null || cur instanceof GroupState.Running) {
                changed.set(true);
                return new GroupState.Paused();
            }
            return cur;
        });
        return changed.get();
    }

    /// Only from paused (spec §5) — running or blocked is left alone.
    ///
    /// @return whether this call resumed the group
    public boolean resume(String group) {
        var changed = new AtomicBoolean(false);
        states.computeIfPresent(group, (g, cur) -> {
            if (cur instanceof GroupState.Paused) {
                changed.set(true);
                return null; // removing the entry restores RUNNING
            }
            return cur;
        });
        return changed.get();
    }

    /// Clears a block, returning what was cleared — the processor's
    /// `unblockGroup`/`skipGroup` verbs use the poison item id to decide
    /// whether to requeue it, and both answer 404 when the group was not
    /// blocked (spec §7).
    ///
    /// @return the cleared [GroupState.Blocked], or empty if the group was not blocked
    public Optional<GroupState.Blocked> clearBlock(String group) {
        var removed = new AtomicReference<GroupState.Blocked>();
        states.computeIfPresent(group, (g, cur) -> {
            if (cur instanceof GroupState.Blocked b) {
                removed.set(b);
                return null; // removing the entry restores RUNNING
            }
            return cur;
        });
        return Optional.ofNullable(removed.get());
    }

    // ── read-only views (spec §7) ───────────────────────────────────────────

    /// Every group currently tracked — i.e. not `RUNNING` — for `GET
    /// /outbox/groups`.
    public List<GroupInfo> groupStates() {
        return states.entrySet().stream().map(e -> toInfo(e.getKey(), e.getValue())).toList();
    }

    /// Only the blocked groups, for `GET /outbox/groups/blocked`.
    public List<GroupInfo> blockedGroups() {
        return states.entrySet().stream()
                .filter(e -> e.getValue() instanceof GroupState.Blocked)
                .map(e -> toInfo(e.getKey(), e.getValue()))
                .toList();
    }

    private static GroupInfo toInfo(String group, GroupState state) {
        return switch (state) {
            case GroupState.Running ignored -> new GroupInfo(group, "RUNNING", null, null);
            case GroupState.Paused ignored -> new GroupInfo(group, "PAUSED", null, null);
            case GroupState.Blocked b -> new GroupInfo(group, "BLOCKED", b.itemId(), b.error());
        };
    }
}
