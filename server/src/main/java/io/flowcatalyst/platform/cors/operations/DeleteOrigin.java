package io.flowcatalyst.platform.cors.operations;

import io.flowcatalyst.platform.cors.CorsOrigin;
import io.flowcatalyst.platform.cors.CorsOriginRepository;
import io.flowcatalyst.platform.cors.operations.CorsOriginEvents.CorsOriginDeleted;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.op.Operation;
import io.flowcatalyst.sdk.usecase.op.Plan;

/// Removes an origin from the allowlist (hard delete) and emits [CorsOriginDeleted].
public final class DeleteOrigin {

    private DeleteOrigin() {
    }

    public static Operation<DeleteCommand, CorsOriginDeleted> of(CorsOriginRepository repo) {
        return Operation.<DeleteCommand, CorsOriginDeleted>named("DeleteOrigin")
                // Spec open question 2: the template's blank-id rule, where Go fell through to 404.
                .validate(cmd -> UseCaseException.requireNonBlank(cmd.originId(), "ID_REQUIRED", "Origin id is required"))
                // CORS origins are anchor-only, platform-owned, with no per-resource dimension;
                // the handler's requireAnchor is the whole check (spec §5).
                .authorize(Operation.Authorize.publicAccess())
                .execute((cmd, ec) -> {
                    CorsOrigin o = Access.byId(repo, cmd.originId());
                    return Plan.delete(o, repo, CorsOriginDeleted.of(ec, o));
                });
    }
}
