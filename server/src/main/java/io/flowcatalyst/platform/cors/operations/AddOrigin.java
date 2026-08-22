package io.flowcatalyst.platform.cors.operations;

import io.flowcatalyst.platform.cors.CorsOrigin;
import io.flowcatalyst.platform.cors.CorsOriginRepository;
import io.flowcatalyst.platform.cors.Origin;
import io.flowcatalyst.platform.cors.operations.CorsOriginEvents.CorsOriginAdded;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.op.Operation;
import io.flowcatalyst.sdk.usecase.op.Plan;

/// Adds an origin to the allowlist (unique by trimmed origin) and emits
/// [CorsOriginAdded].
public final class AddOrigin {

    private AddOrigin() {
    }

    public static Operation<AddCommand, CorsOriginAdded> of(CorsOriginRepository repo) {
        return Operation.<AddCommand, CorsOriginAdded>named("AddOrigin")
                .validate(cmd -> Origin.parse(cmd.origin()))
                // CORS origins are anchor-only, platform-owned, with no per-resource dimension;
                // the handler's requireAnchor is the whole check (spec §5).
                .authorize(Operation.Authorize.publicAccess())
                .execute((cmd, ec) -> {
                    Origin origin = Origin.parse(cmd.origin());
                    if (repo.findByOrigin(origin.value()).isPresent()) {
                        throw UseCaseException.conflict("ORIGIN_ALREADY_EXISTS",
                                "CORS origin '" + origin.value() + "' already exists");
                    }
                    CorsOrigin o = CorsOrigin.create(origin, cmd.description(), ec.principalId());
                    return Plan.save(o, repo, CorsOriginAdded.of(ec, o));
                });
    }
}
