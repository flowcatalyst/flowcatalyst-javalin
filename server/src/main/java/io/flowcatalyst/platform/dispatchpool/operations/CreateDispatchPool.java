package io.flowcatalyst.platform.dispatchpool.operations;

import io.flowcatalyst.platform.dispatchpool.DispatchPool;
import io.flowcatalyst.platform.dispatchpool.DispatchPoolCode;
import io.flowcatalyst.platform.dispatchpool.DispatchPoolRepository;
import io.flowcatalyst.platform.dispatchpool.operations.DispatchPoolEvents.DispatchPoolCreated;
import io.flowcatalyst.platform.shared.auth.Auth;
import io.flowcatalyst.platform.shared.auth.Checks;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.op.Operation;
import io.flowcatalyst.sdk.usecase.op.Plan;

/// Creates a dispatch pool (unique by `(code, clientId)`, spec §6) and emits
/// [DispatchPoolCreated]. The code is normalised (trim + lowercase) before
/// validation and storage.
public final class CreateDispatchPool {

    private CreateDispatchPool() {
    }

    public static Operation<CreateCommand, DispatchPoolCreated> of(DispatchPoolRepository repo) {
        return Operation.<CreateCommand, DispatchPoolCreated>named("CreateDispatchPool")
                .validate(cmd -> {
                    UseCaseException.requireNonBlank(cmd.code(), "CODE_REQUIRED", "code is required");
                    DispatchPoolCode.normalised(cmd.code());
                    UseCaseException.requireNonBlank(cmd.name(), "NAME_REQUIRED", "name is required");
                    Bounds.checkAdmin(cmd.concurrency(), cmd.rateLimit());
                })
                // The target client is a command field, so the per-resource check
                // can run before execute: a client-bound create needs access to that
                // client; a platform-wide (null clientId) create needs anchor.
                .authorize(cmd -> Checks.checkScopeAccess(Auth.current(), cmd.clientId()))
                .execute((cmd, ec) -> {
                    String code = DispatchPoolCode.normalised(cmd.code()).value();
                    if (repo.findByCode(code, cmd.clientId()).isPresent()) {
                        throw UseCaseException.conflict("CODE_EXISTS",
                                "Dispatch pool with code '" + code + "' already exists");
                    }
                    DispatchPool p = DispatchPool.create(code, cmd.name().trim())
                            .withDescription(cmd.description())
                            .withRateLimit(cmd.rateLimit())
                            .withClientId(cmd.clientId());
                    if (cmd.concurrency() != null) {
                        p = p.withConcurrency(cmd.concurrency());
                    }
                    return Plan.save(p, repo, DispatchPoolCreated.of(ec, p));
                });
    }
}
