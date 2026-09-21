package io.flowcatalyst.platform.connection.operations;

import io.flowcatalyst.platform.application.ApplicationRepository;
import io.flowcatalyst.platform.connection.Connection;
import io.flowcatalyst.platform.connection.ConnectionRepository;
import io.flowcatalyst.platform.connection.ConnectionStatus;
import io.flowcatalyst.platform.connection.operations.ConnectionEvents.ConnectionUpdated;
import io.flowcatalyst.platform.shared.auth.Auth;
import io.flowcatalyst.platform.shared.auth.Checks;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.op.Operation;
import io.flowcatalyst.sdk.usecase.op.Plan;

import java.util.Objects;

/// Replaces name, description and external id — and optionally the status
/// and the owning application — on an existing connection and emits
/// [ConnectionUpdated]. The code is immutable.
public final class UpdateConnection {

    private UpdateConnection() {
    }

    public static Operation<UpdateCommand, ConnectionUpdated> of(ConnectionRepository repo, ApplicationRepository apps) {
        return Operation.<UpdateCommand, ConnectionUpdated>named("UpdateConnection")
                .validate(cmd -> {
                    UseCaseException.requireNonBlank(cmd.id(), "ID_REQUIRED", "id is required");
                    UseCaseException.requireNonBlank(cmd.name(), "NAME_REQUIRED", "Connection name is required");
                })
                .authorize(Operation.Authorize.publicAccess()) // per-resource check is in Access.loadScoped
                .execute((cmd, ec) -> {
                    Connection existing = Access.loadScoped(repo, cmd.id());
                    // applicationCode (spec §3): set-if-provided, never cleared. Must
                    // name an existing application the caller can access whenever
                    // provided; the duplicate check under the new key only runs when
                    // it actually changes the row's key — re-sending the current value
                    // must not 409 against the row itself.
                    if (cmd.applicationCode() != null) {
                        var app = apps.findByCode(cmd.applicationCode())
                                .orElseThrow(() -> UseCaseException.resourceNotFound("Application", cmd.applicationCode()));
                        Checks.checkApplicationAccess(Auth.current(), app.id(), app.code());
                        if (!Objects.equals(cmd.applicationCode(), existing.applicationCode())
                                && repo.findByCode(existing.code(), cmd.applicationCode(), existing.clientId()).isPresent()) {
                            throw UseCaseException.conflict("CODE_EXISTS",
                                    "Connection with code '" + existing.code() + "' already exists");
                        }
                    }
                    Connection c = existing
                            .withName(cmd.name())
                            .withDescription(cmd.description())
                            .withExternalId(cmd.externalId())
                            .withApplicationCode(cmd.applicationCode());
                    if (cmd.status() != null) {
                        // The status field selects one of the two transitions (spec §4, open question 4).
                        c = switch (ConnectionStatus.parseCommandStatus(cmd.status())) {
                            case PAUSED -> c.pause();
                            case ACTIVE -> c.activate();
                        };
                    }
                    return Plan.save(c, repo, ConnectionUpdated.of(ec, c));
                });
    }
}
