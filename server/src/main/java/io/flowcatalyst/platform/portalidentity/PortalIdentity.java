package io.flowcatalyst.platform.portalidentity;

import io.flowcatalyst.platform.shared.tsid.EntityType;
import io.flowcatalyst.sdk.usecase.HasId;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/// The portal identity aggregate root (spec `auth-identity.md` §3.3, §5.7,
/// §11.8; `portal-apps.md` §2.2, §2.3): one row per (client, email) in the
/// portal plane — never gets a session cookie; the platform proves this
/// identity and hands the portal app an OAuth authorization code whose
/// subject is this row's `ptu_` id.
/// Platform-config-adjacent but client-scoped: unlike `oauthclient` there
/// **is** a per-resource dimension ([#clientId]), so by-id write operations
/// check it explicitly rather than declaring `Authorize.publicAccess()`
/// blindly — a `clientId` on the command that disagrees with the row's is
/// treated as not-found (§5.7: "cross-client hidden"), never surfaced as a
/// distinct error, so a caller cannot use this endpoint to probe whether an
/// id exists under another tenant.
///
/// Immutable record: each transition returns a copy. `email` is
/// lower-cased and trimmed at construction (spec §3.3) so every comparison
/// and every upsert key is normalised the same way no matter which
/// transition produced the copy.
///
/// @param id               `ptu_…` TSID
/// @param clientId         the tenant this identity belongs to; no FK (spec §3.3)
/// @param email             lower-cased + trimmed; unique with `clientId`
/// @param name              display name; `null` ⇔ blank (never stored as `""`)
/// @param passwordHash      `null` until an invite/reset completes, or forever for an SSO-only identity
/// @param status            `ACTIVE` \| `DISABLED`
/// @param source            `INVITE` \| `JIT`
/// @param apps              this identity's per-app grants (`portal-apps.md` §2.2); immutable, never
///                          null, ordered by [PortalAppGrant#grantedAt]
/// @param lastLoginAt       stamped best-effort on password and SSO login; `null` before the first
/// @param invitedAt         when the current invite was issued; `null` before any invite (`portal-apps.md` §2.2)
/// @param inviteExpiresAt   when the invite lapses; `null` = no expiry (an SSO invite) or never invited
/// @param createdAt         creation time
/// @param updatedAt         last change
public record PortalIdentity(
        String id,
        String clientId,
        String email,
        String name,
        String passwordHash,
        PortalIdentityStatus status,
        PortalIdentitySource source,
        List<PortalAppGrant> apps,
        Instant lastLoginAt,
        Instant invitedAt,
        Instant inviteExpiresAt,
        Instant createdAt,
        Instant updatedAt) implements HasId {

    public PortalIdentity {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(clientId, "clientId");
        Objects.requireNonNull(email, "email");
        email = normalizeEmail(email);
        name = name == null || name.isBlank() ? null : name;
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(source, "source");
        apps = apps == null ? List.of()
                : apps.stream().sorted(Comparator.comparing(PortalAppGrant::grantedAt)).toList();
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }

    /// A fresh, `ACTIVE`, password-less, grant-less identity (spec §11.8:
    /// `Ensure` on a row that does not yet exist). `name` blank ⇒ `null`.
    public static PortalIdentity create(String clientId, String email, String name, PortalIdentitySource source) {
        Objects.requireNonNull(clientId, "clientId");
        Objects.requireNonNull(email, "email");
        Objects.requireNonNull(source, "source");
        Instant now = Instant.now();
        return new PortalIdentity(EntityType.PORTAL_USER.generate(), clientId, normalizeEmail(email), name,
                null, PortalIdentityStatus.ACTIVE, source, List.of(), null, null, null, now, now);
    }

    /// Lower-cased + trimmed, the one normal form every lookup and every
    /// stored row uses (spec §3.3).
    public static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    /// `ACTIVE` **and** a non-empty hash (spec §3.3) — the password-login
    /// and admin-API gate. An SSO-only or not-yet-invited identity always
    /// answers `false` here even while `ACTIVE`.
    public boolean canSignInWithPassword() {
        return status == PortalIdentityStatus.ACTIVE && passwordHash != null && !passwordHash.isBlank();
    }

    /// `Ensure` on a row that already exists (spec §5.7, §11.8): reactivates
    /// unconditionally and overrides [#name] only when `candidateName` is
    /// non-blank — id, source, createdAt and the password are untouched by
    /// this transition (they are also excluded from the repository's
    /// upsert `SET` list, so the invariant holds even if a caller mistakenly
    /// built a `Changes`-less overwrite).
    public PortalIdentity ensureActive(String candidateName) {
        String newName = candidateName != null && !candidateName.isBlank() ? candidateName.trim() : name;
        return new PortalIdentity(id, clientId, email, newName, passwordHash,
                PortalIdentityStatus.ACTIVE, source, apps, lastLoginAt, invitedAt, inviteExpiresAt, createdAt, Instant.now());
    }

    /// Idempotent, no error either way (spec §11.8): password login 401,
    /// SSO `access_denied`, token redeem `invalid_grant`.
    public PortalIdentity activate() {
        return new PortalIdentity(id, clientId, email, name, passwordHash,
                PortalIdentityStatus.ACTIVE, source, apps, lastLoginAt, invitedAt, inviteExpiresAt, createdAt, Instant.now());
    }

    public PortalIdentity deactivate() {
        return new PortalIdentity(id, clientId, email, name, passwordHash,
                PortalIdentityStatus.DISABLED, source, apps, lastLoginAt, invitedAt, inviteExpiresAt, createdAt, Instant.now());
    }

    // ── Per-app grants (spec `portal-apps.md` §2.2) ───────────────────────────

    public boolean hasApp(String appId) {
        return apps.stream().anyMatch(g -> g.appId().equals(appId));
    }

    /// Idempotent — returns `this` (same instance, `updatedAt` untouched)
    /// when the app is already held, so a repeated grant never re-stamps
    /// `grantedAt` or the identity's `updatedAt`.
    public PortalIdentity grant(String appId, PortalAppGrantSource source) {
        Objects.requireNonNull(appId, "appId");
        Objects.requireNonNull(source, "source");
        if (hasApp(appId)) {
            return this;
        }
        List<PortalAppGrant> updated = concat(apps, new PortalAppGrant(appId, source, Instant.now()));
        return new PortalIdentity(id, clientId, email, name, passwordHash, status, this.source, updated,
                lastLoginAt, invitedAt, inviteExpiresAt, createdAt, Instant.now());
    }

    /// Idempotent — returns `this` unchanged when the app is not held.
    public PortalIdentity revoke(String appId) {
        Objects.requireNonNull(appId, "appId");
        if (!hasApp(appId)) {
            return this;
        }
        List<PortalAppGrant> updated = apps.stream().filter(g -> !g.appId().equals(appId)).toList();
        return new PortalIdentity(id, clientId, email, name, passwordHash, status, source, updated,
                lastLoginAt, invitedAt, inviteExpiresAt, createdAt, Instant.now());
    }

    private static List<PortalAppGrant> concat(List<PortalAppGrant> existing, PortalAppGrant added) {
        var merged = new java.util.ArrayList<>(existing);
        merged.add(added);
        return List.copyOf(merged);
    }

    // ── Derived state (spec `portal-apps.md` §2.3, Part A J3): never stored ──

    /// Evaluated at `now`, first match wins (spec §2.3):
    /// 1. `DISABLED` ⇒ `SUSPENDED`
    /// 2. a password, a prior login, or `source = JIT` ⇒ `ACTIVE`
    /// 3. an unexpired invite has lapsed ⇒ `INVITE_EXPIRED`
    /// 4. otherwise ⇒ `INVITED`
    public PortalUserState state(Instant now) {
        Objects.requireNonNull(now, "now");
        if (status == PortalIdentityStatus.DISABLED) {
            return PortalUserState.SUSPENDED;
        }
        if ((passwordHash != null && !passwordHash.isBlank()) || lastLoginAt != null || source == PortalIdentitySource.JIT) {
            return PortalUserState.ACTIVE;
        }
        if (inviteExpiresAt != null && !now.isBefore(inviteExpiresAt)) {
            return PortalUserState.INVITE_EXPIRED;
        }
        return PortalUserState.INVITED;
    }
}
