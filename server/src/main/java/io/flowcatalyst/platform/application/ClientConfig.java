package io.flowcatalyst.platform.application;

import io.flowcatalyst.platform.shared.tsid.EntityType;
import io.flowcatalyst.sdk.usecase.HasId;

import java.time.Instant;
import java.util.Objects;

/// The per-(application, client) enablement row (spec §1.2). A separate
/// aggregate from [Application]: enabling or disabling an application for a
/// client writes this row, never the application.
///
/// `enabled` flips both ways and idempotently — a disable on an already
/// disabled row is still a write (spec §2).
///
/// @param id            `apc_…` TSID
/// @param applicationId the application
/// @param clientId      the client
/// @param enabled       whether the application is enabled for the client
/// @param createdAt     creation time
/// @param updatedAt     last change
public record ClientConfig(
        String id,
        String applicationId,
        String clientId,
        boolean enabled,
        Instant createdAt,
        Instant updatedAt) implements HasId {

    public ClientConfig {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(applicationId, "applicationId");
        Objects.requireNonNull(clientId, "clientId");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }

    /// A fresh, enabled config for the pair.
    public static ClientConfig create(String applicationId, String clientId) {
        Instant now = Instant.now();
        return new ClientConfig(EntityType.APP_CLIENT_CONFIG.generate(), applicationId, clientId, true, now, now);
    }

    public ClientConfig enable() {
        return withEnabled(true);
    }

    public ClientConfig disable() {
        return withEnabled(false);
    }

    private ClientConfig withEnabled(boolean v) {
        return new ClientConfig(id, applicationId, clientId, v, createdAt, Instant.now());
    }
}
