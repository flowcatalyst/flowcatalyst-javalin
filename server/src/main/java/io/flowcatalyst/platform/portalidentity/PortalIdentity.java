package io.flowcatalyst.platform.portalidentity;

import io.flowcatalyst.platform.shared.tsid.EntityType;
import io.flowcatalyst.sdk.usecase.HasId;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;

/// The portal identity aggregate root (spec `auth-identity.md` §3.3, §5.7,
/// §11.8): one row per (client, email) in the portal plane — never gets a
/// session cookie; the platform proves this identity and hands the portal
/// app an OAuth authorization code whose subject is this row's `ptu_` id.
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
/// @param id           `ptu_…` TSID
/// @param clientId     the tenant this identity belongs to; no FK (spec §3.3)
/// @param email        lower-cased + trimmed; unique with `clientId`
/// @param name         display name; `null` ⇔ blank (never stored as `""`)
/// @param passwordHash `null` until an invite/reset completes, or forever for an SSO-only identity
/// @param status       `ACTIVE` \| `DISABLED`
/// @param source       `INVITE` \| `JIT`
/// @param lastLoginAt  stamped best-effort on password and SSO login; `null` before the first
/// @param createdAt    creation time
/// @param updatedAt    last change
public record PortalIdentity(
        String id,
        String clientId,
        String email,
        String name,
        String passwordHash,
        PortalIdentityStatus status,
        PortalIdentitySource source,
        Instant lastLoginAt,
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
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }

    /// A fresh, `ACTIVE`, password-less identity (spec §11.8: `Ensure` on a
    /// row that does not yet exist). `name` blank ⇒ `null`.
    public static PortalIdentity create(String clientId, String email, String name, PortalIdentitySource source) {
        Objects.requireNonNull(clientId, "clientId");
        Objects.requireNonNull(email, "email");
        Objects.requireNonNull(source, "source");
        Instant now = Instant.now();
        return new PortalIdentity(EntityType.PORTAL_USER.generate(), clientId, normalizeEmail(email), name,
                null, PortalIdentityStatus.ACTIVE, source, null, now, now);
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
                PortalIdentityStatus.ACTIVE, source, lastLoginAt, createdAt, Instant.now());
    }

    /// Idempotent, no error either way (spec §11.8): password login 401,
    /// SSO `access_denied`, token redeem `invalid_grant`.
    public PortalIdentity activate() {
        return new PortalIdentity(id, clientId, email, name, passwordHash,
                PortalIdentityStatus.ACTIVE, source, lastLoginAt, createdAt, Instant.now());
    }

    public PortalIdentity deactivate() {
        return new PortalIdentity(id, clientId, email, name, passwordHash,
                PortalIdentityStatus.DISABLED, source, lastLoginAt, createdAt, Instant.now());
    }
}
