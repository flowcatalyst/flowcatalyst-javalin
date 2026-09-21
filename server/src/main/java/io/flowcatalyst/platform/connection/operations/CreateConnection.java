package io.flowcatalyst.platform.connection.operations;

import io.flowcatalyst.platform.application.ApplicationRepository;
import io.flowcatalyst.platform.connection.Connection;
import io.flowcatalyst.platform.connection.ConnectionCode;
import io.flowcatalyst.platform.connection.ConnectionRepository;
import io.flowcatalyst.platform.connection.operations.ConnectionEvents.ConnectionCreated;
import io.flowcatalyst.platform.shared.auth.Auth;
import io.flowcatalyst.platform.shared.auth.Checks;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.op.Operation;
import io.flowcatalyst.sdk.usecase.op.Plan;

/// Creates a connection (unique by normalised code within its
/// `(applicationCode, clientId)` scope, spec `code-first-connections.md` §2)
/// and emits [ConnectionCreated].
public final class CreateConnection {

    private CreateConnection() {
    }

    public static Operation<CreateCommand, ConnectionCreated> of(ConnectionRepository repo, ApplicationRepository apps) {
        return Operation.<CreateCommand, ConnectionCreated>named("CreateConnection")
                .validate(cmd -> {
                    ConnectionCode.parse(cmd.code());
                    UseCaseException.requireNonBlank(cmd.name(), "NAME_REQUIRED", "Connection name is required");
                    UseCaseException.requireNonBlank(cmd.serviceAccountId(), "SERVICE_ACCOUNT_REQUIRED", "serviceAccountId is required");
                })
                // The target client is a command field, so the per-resource check
                // can run before execute: a client-bound create needs access to that
                // client; a platform-wide (null clientId) create needs anchor.
                .authorize(cmd -> Checks.checkScopeAccess(Auth.current(), cmd.clientId()))
                .execute((cmd, ec) -> {
                    // applicationCode (spec §3): must name an existing application the
                    // caller can access. Checked in execute (like the duplicate-code
                    // check below) because resolving it needs a repository read.
                    if (cmd.applicationCode() != null) {
                        var app = apps.findByCode(cmd.applicationCode())
                                .orElseThrow(() -> UseCaseException.resourceNotFound("Application", cmd.applicationCode()));
                        Checks.checkApplicationAccess(Auth.current(), app.id(), app.code());
                    }
                    ConnectionCode code = ConnectionCode.parse(cmd.code());
                    if (repo.findByCode(code.value(), cmd.applicationCode(), cmd.clientId()).isPresent()) {
                        throw UseCaseException.conflict("CODE_EXISTS",
                                "Connection with code '" + code.value() + "' already exists");
                    }
                    Connection c = Connection.create(code, cmd.name(), cmd.serviceAccountId())
                            .withDescription(cmd.description())
                            .withExternalId(cmd.externalId())
                            .withClientId(cmd.clientId())
                            .withApplicationCode(cmd.applicationCode());
                    return Plan.save(c, repo, ConnectionCreated.of(ec, c));
                });
    }
}
