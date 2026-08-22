package io.flowcatalyst.platform.platformconfig;

import io.flowcatalyst.platform.shared.tsid.EntityType;
import io.flowcatalyst.sdk.usecase.HasId;

import java.time.Instant;
import java.util.Objects;

/// A platform-config access grant (spec §1.2): role `roleCode` may read —
/// and, when `canWrite`, write — the configs of `applicationCode`. A grant
/// always confers read; `canRead` is persisted but never `false` (open
/// question 1). Immutable; [#grant] returns a copy.
///
/// @param id              `cfa_…` TSID
/// @param applicationCode the application the grant is for
/// @param roleCode        the role the grant is for
/// @param canRead         always `true` after a grant
/// @param canWrite        whether the role may set / delete values
/// @param createdAt       first grant
public record ConfigAccess(
        String id,
        String applicationCode,
        String roleCode,
        boolean canRead,
        boolean canWrite,
        Instant createdAt) implements HasId {

    public ConfigAccess {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(applicationCode, "applicationCode");
        Objects.requireNonNull(roleCode, "roleCode");
        Objects.requireNonNull(createdAt, "createdAt");
    }

    /// A fresh read-only grant.
    public static ConfigAccess create(String applicationCode, String roleCode) {
        return new ConfigAccess(EntityType.CONFIG_ACCESS.generate(), applicationCode, roleCode, true, false, Instant.now());
    }

    // ── Transitions (spec §2) ──────────────────────────────────────────────

    /// Re-grants: read stays on, write becomes `write` (escalation and
    /// de-escalation alike).
    public ConfigAccess grant(boolean write) {
        return new ConfigAccess(id, applicationCode, roleCode, true, write, createdAt);
    }
}
