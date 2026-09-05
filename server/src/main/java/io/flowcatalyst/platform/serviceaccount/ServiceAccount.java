package io.flowcatalyst.platform.serviceaccount;

import io.flowcatalyst.platform.shared.tsid.EntityType;
import io.flowcatalyst.sdk.usecase.HasId;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/// The service-account aggregate root (spec: `docs/spec/serviceaccount.md`):
/// a machine-to-machine identity's credential-holding half. The
/// *authorisation* half is a linked `SERVICE` principal in the `principal`
/// aggregate, where roles actually live — [#roles] here is a **read
/// projection**, hydrated by [ServiceAccountRepository] on single-row reads
/// only (spec §9.1), never written by this aggregate.
///
/// Immutable record: each transition returns a copy and throws
/// [io.flowcatalyst.sdk.usecase.UseCaseException] when an invariant is
/// violated, so an operation is just load → transition → event. The
/// repository persists whatever copy it is handed and stamps `updatedAt`
/// itself for the write timestamp; `lastUsedAt` is stamped by a targeted
/// repository write ([ServiceAccountRepository#touchLastUsed]), not a
/// transition, because it is best-effort bookkeeping outside the mutating
/// transaction (spec §9.2).
///
/// @param id                 `sac_…` TSID
/// @param code               unique, lower-case (see [ServiceAccountCode])
/// @param name               human-readable name
/// @param description        optional
/// @param active             whether the account may authenticate
/// @param clientIds          reach: which clients this account may act for
/// @param scope              optional free-form scope tag (Go: `*string`, never validated)
/// @param applicationId      the owning application, when provisioned for one
/// @param webhookCredentials outbound-call credentials, always plaintext in memory (spec §2.1)
/// @param roles              read projection of the linked principal's roles (spec §9.1); `[]` unless hydrated
/// @param lastUsedAt         last time credentials were exercised, or `null` (spec §9.2)
/// @param createdAt          creation time
/// @param updatedAt          last change
public record ServiceAccount(
        String id,
        String code,
        String name,
        String description,
        boolean active,
        List<String> clientIds,
        String scope,
        String applicationId,
        WebhookCredentials webhookCredentials,
        List<RoleAssignment> roles,
        Instant lastUsedAt,
        Instant createdAt,
        Instant updatedAt) implements HasId {

    public ServiceAccount {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(name, "name");
        clientIds = clientIds == null ? List.of() : List.copyOf(clientIds);
        Objects.requireNonNull(webhookCredentials, "webhookCredentials");
        roles = roles == null ? List.of() : List.copyOf(roles);
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }

    /// A fresh, active account with no credentials, no clients, no roles.
    public static ServiceAccount create(ServiceAccountCode code, String name) {
        Instant now = Instant.now();
        return new ServiceAccount(EntityType.SERVICE_ACCOUNT.generate(), code.value(), name.trim(), null, true,
                List.of(), null, null, WebhookCredentials.none(), List.of(), null, now, now);
    }

    // ── Transitions (spec §4.2, §4.6) ───────────────────────────────────────

    /// `active = false` (idempotent — always bumps `updatedAt`, matching Go).
    /// There is no route back to `true` (spec §3 has no reactivate endpoint;
    /// Go has no `Activate` caller either) — `active` starts `true` at
    /// [#create] and this is the only transition that changes it.
    public ServiceAccount deactivate() {
        return withActive(false);
    }

    /// The fields an admin update may replace; `null` = leave untouched.
    /// `clientIds` distinguishes `null` (untouched) from `[]` (cleared) —
    /// callers pass `null` only when the field was absent on the wire.
    public record Changes(String name, String description, String scope, List<String> clientIds, WebhookCredentials webhookCredentials) {
    }

    /// Replaces the non-null fields and re-stamps `updatedAt` (spec §4.2).
    public ServiceAccount update(Changes changes) {
        return new ServiceAccount(id, code,
                changes.name() == null ? name : changes.name().trim(),
                changes.description() == null ? description : changes.description(),
                active,
                changes.clientIds() == null ? clientIds : List.copyOf(changes.clientIds()),
                changes.scope() == null ? scope : changes.scope(),
                applicationId,
                changes.webhookCredentials() == null ? webhookCredentials : changes.webhookCredentials(),
                roles, lastUsedAt, createdAt, Instant.now());
    }

    /// Rotates the bearer token (spec §4.6): forces `authType` to `BEARER_TOKEN`.
    public ServiceAccount withToken(String plaintext) {
        return new ServiceAccount(id, code, name, description, active, clientIds, scope, applicationId,
                webhookCredentials.withToken(Objects.requireNonNull(plaintext, "plaintext")), roles, lastUsedAt, createdAt, Instant.now());
    }

    /// Rotates the HMAC signing secret (spec §4.6): `authType` is left as-is.
    public ServiceAccount withSigningSecret(String plaintext) {
        return new ServiceAccount(id, code, name, description, active, clientIds, scope, applicationId,
                webhookCredentials.withSigningSecret(Objects.requireNonNull(plaintext, "plaintext")), roles, lastUsedAt, createdAt, Instant.now());
    }

    // ── Construction-time copies (spec §4.1) ────────────────────────────────

    public ServiceAccount withDescription(String newDescription) {
        return new ServiceAccount(id, code, name, newDescription, active, clientIds, scope, applicationId,
                webhookCredentials, roles, lastUsedAt, createdAt, updatedAt);
    }

    public ServiceAccount withScope(String newScope) {
        return new ServiceAccount(id, code, name, description, active, clientIds, newScope, applicationId,
                webhookCredentials, roles, lastUsedAt, createdAt, updatedAt);
    }

    public ServiceAccount withApplicationId(String newApplicationId) {
        return new ServiceAccount(id, code, name, description, active, clientIds, scope, newApplicationId,
                webhookCredentials, roles, lastUsedAt, createdAt, updatedAt);
    }

    /// `null` leaves the current (empty by default) list untouched.
    public ServiceAccount withClientIds(List<String> newClientIds) {
        if (newClientIds == null) {
            return this;
        }
        return new ServiceAccount(id, code, name, description, active, newClientIds, scope, applicationId,
                webhookCredentials, roles, lastUsedAt, createdAt, updatedAt);
    }

    public ServiceAccount withWebhookCredentials(WebhookCredentials newCredentials) {
        return new ServiceAccount(id, code, name, description, active, clientIds, scope, applicationId,
                Objects.requireNonNull(newCredentials, "newCredentials"), roles, lastUsedAt, createdAt, updatedAt);
    }

    private ServiceAccount withActive(boolean newActive) {
        return new ServiceAccount(id, code, name, description, newActive, clientIds, scope, applicationId,
                webhookCredentials, roles, lastUsedAt, createdAt, Instant.now());
    }

    // ── Repository-only read projection (spec §9.1) ─────────────────────────

    /// Overlays the roles hydrated from the linked principal. Package-private:
    /// only [ServiceAccountRepository] calls this, never an operation — roles
    /// are never written through this aggregate.
    ServiceAccount withRoles(List<RoleAssignment> hydratedRoles) {
        return new ServiceAccount(id, code, name, description, active, clientIds, scope, applicationId,
                webhookCredentials, hydratedRoles, lastUsedAt, createdAt, updatedAt);
    }
}
