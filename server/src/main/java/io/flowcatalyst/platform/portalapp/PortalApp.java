package io.flowcatalyst.platform.portalapp;

import io.flowcatalyst.platform.shared.tsid.EntityType;
import io.flowcatalyst.sdk.usecase.HasId;

import java.time.Instant;
import java.util.Objects;

/// The portal-app aggregate root (spec `portal-apps.md` §1, §2.1, Part A
/// decision J1): a client's named portal entry point — a customer portal, a
/// supplier portal — that OAuth clients link to ([#id] ⇒
/// `oauth_clients.portal_app_id`) and identities are granted per app
/// ([io.flowcatalyst.platform.portalidentity.PortalAppGrant], persisted by
/// the identity's own repository per spec §2.2).
///
/// Immutable record: each transition returns a copy. `code` is normalised
/// (trim + lower-case) at construction and never changes after creation
/// (spec §2.1, §3.5) — [#update] has no `code` parameter.
///
/// @param id          `pta_…` TSID
/// @param clientId    the owning tenant client; no FK (matches `oauth_clients`)
/// @param code        normalised (trim + lower-case), unique with `clientId`, immutable
/// @param name        required display name
/// @param description `null` ⇔ blank
/// @param active      inactive portal apps refuse new logins (spec §5)
/// @param createdAt   creation time
/// @param updatedAt   last change
public record PortalApp(
        String id,
        String clientId,
        String code,
        String name,
        String description,
        boolean active,
        Instant createdAt,
        Instant updatedAt) implements HasId {

    public PortalApp {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(clientId, "clientId");
        Objects.requireNonNull(code, "code");
        code = PortalAppCode.normalize(code);
        Objects.requireNonNull(name, "name");
        description = description == null || description.isBlank() ? null : description;
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }

    /// A fresh, active portal app (spec §3.3). `name` is trimmed; `code`
    /// carries its own normalisation and validation ([PortalAppCode#parse]).
    public static PortalApp create(String clientId, PortalAppCode code, String name, String description) {
        Objects.requireNonNull(clientId, "clientId");
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(name, "name");
        Instant now = Instant.now();
        return new PortalApp(EntityType.PORTAL_APP.generate(), clientId, code.value(), name.trim(), description,
                true, now, now);
    }

    /// A multi-field partial update (spec §3.5): each `null` argument leaves
    /// the field unchanged; `description` is trimmed and an empty result
    /// clears it (constructor-level normalisation); `code` never changes —
    /// there is no parameter for it.
    public PortalApp update(String name, String description, Boolean active) {
        return new PortalApp(id, clientId, code,
                name != null ? name.trim() : this.name,
                description != null ? description.trim() : this.description,
                active != null ? active : this.active,
                createdAt, Instant.now());
    }
}
