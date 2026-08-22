package io.flowcatalyst.platform.application;

import io.flowcatalyst.platform.shared.tsid.EntityType;
import io.flowcatalyst.sdk.usecase.HasId;
import io.flowcatalyst.sdk.usecase.UseCaseException;

import java.time.Instant;
import java.util.Objects;

/// The application aggregate root (spec: `docs/spec/application.md`). An
/// application is a registered first-party application or third-party
/// integration, identified by its normalised code. It is platform-level —
/// no client dimension — so it carries no scope of its own.
///
/// Immutable record: each transition returns a copy and throws
/// [UseCaseException] when an invariant is violated; the repository persists
/// whatever copy it is handed and stamps `updatedAt` itself.
///
/// @param id               `app_…` TSID
/// @param type             `APPLICATION` | `INTEGRATION`, immutable
/// @param code             normalised, unique, immutable (see [ApplicationCode])
/// @param name             human-readable name, trimmed
/// @param description      optional; `null` when absent
/// @param iconUrl          optional
/// @param website          optional
/// @param logo             optional inline logo content
/// @param logoMimeType     optional
/// @param defaultBaseUrl   optional
/// @param serviceAccountId the *principal* id (`sac_…`) of the attached service account; `null` until attached
/// @param active           whether the application is active (a flag, not a lifecycle — spec §2)
/// @param createdAt        creation time
/// @param updatedAt        last change
public record Application(
        String id,
        ApplicationType type,
        String code,
        String name,
        String description,
        String iconUrl,
        String website,
        String logo,
        String logoMimeType,
        String defaultBaseUrl,
        String serviceAccountId,
        boolean active,
        Instant createdAt,
        Instant updatedAt) implements HasId {

    public Application {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }

    /// A fresh, active application of `type` with the normalised code and
    /// trimmed name and no optional details.
    ///
    /// @throws UseCaseException validation `CODE_REQUIRED` | `INVALID_CODE_FORMAT` (see [ApplicationCode#parse])
    public static Application create(ApplicationType type, String code, String name) {
        Instant now = Instant.now();
        return new Application(EntityType.APPLICATION.generate(), type, ApplicationCode.parse(code).value(),
                name.trim(), null, null, null, null, null, null, null, true, now, now);
    }

    public boolean isIntegration() {
        return type == ApplicationType.INTEGRATION;
    }

    public boolean hasServiceAccount() {
        return serviceAccountId != null;
    }

    // ── Transitions (spec §2) ──────────────────────────────────────────────

    /// `active` → `true`. Idempotent: an already-active application is
    /// re-stamped, not refused (spec §2, open question 1).
    public Application activate() {
        return withActive(true);
    }

    /// `active` → `false`. Idempotent, like [#activate].
    public Application deactivate() {
        return withActive(false);
    }

    /// Records the service account's principal as this application's service
    /// account. Write-once.
    ///
    /// @throws UseCaseException business rule `APPLICATION_HAS_SERVICE_ACCOUNT`
    public Application attachServiceAccount(String servicePrincipalId) {
        Objects.requireNonNull(servicePrincipalId, "servicePrincipalId");
        if (hasServiceAccount()) {
            throw UseCaseException.businessRule("APPLICATION_HAS_SERVICE_ACCOUNT",
                    "Application already has a service account provisioned");
        }
        return new Application(id, type, code, name, description, iconUrl, website, logo, logoMimeType,
                defaultBaseUrl, servicePrincipalId, active, createdAt, Instant.now());
    }

    // ── Copies ─────────────────────────────────────────────────────────────

    /// Trimmed; the caller has already rejected a blank name.
    public Application withName(String newName) {
        return new Application(id, type, code, newName.trim(), description, iconUrl, website, logo, logoMimeType,
                defaultBaseUrl, serviceAccountId, active, createdAt, updatedAt);
    }

    public Application withDescription(String v) {
        return new Application(id, type, code, name, v, iconUrl, website, logo, logoMimeType,
                defaultBaseUrl, serviceAccountId, active, createdAt, updatedAt);
    }

    public Application withIconUrl(String v) {
        return new Application(id, type, code, name, description, v, website, logo, logoMimeType,
                defaultBaseUrl, serviceAccountId, active, createdAt, updatedAt);
    }

    public Application withWebsite(String v) {
        return new Application(id, type, code, name, description, iconUrl, v, logo, logoMimeType,
                defaultBaseUrl, serviceAccountId, active, createdAt, updatedAt);
    }

    public Application withLogo(String v) {
        return new Application(id, type, code, name, description, iconUrl, website, v, logoMimeType,
                defaultBaseUrl, serviceAccountId, active, createdAt, updatedAt);
    }

    public Application withLogoMimeType(String v) {
        return new Application(id, type, code, name, description, iconUrl, website, logo, v,
                defaultBaseUrl, serviceAccountId, active, createdAt, updatedAt);
    }

    public Application withDefaultBaseUrl(String v) {
        return new Application(id, type, code, name, description, iconUrl, website, logo, logoMimeType,
                v, serviceAccountId, active, createdAt, updatedAt);
    }

    private Application withActive(boolean v) {
        return new Application(id, type, code, name, description, iconUrl, website, logo, logoMimeType,
                defaultBaseUrl, serviceAccountId, v, createdAt, Instant.now());
    }
}
