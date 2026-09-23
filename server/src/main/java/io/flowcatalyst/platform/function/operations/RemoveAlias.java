package io.flowcatalyst.platform.function.operations;

import io.flowcatalyst.platform.function.Function;
import io.flowcatalyst.platform.function.FunctionRepository;
import io.flowcatalyst.platform.function.FunctionVersion;
import io.flowcatalyst.platform.function.FunctionVersionRepository;
import io.flowcatalyst.platform.function.operations.FunctionEvents.AliasRemoved;
import io.flowcatalyst.platform.shared.auth.Auth;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.op.Operation;
import io.flowcatalyst.sdk.usecase.op.Plan;

import java.time.Instant;
import java.util.Objects;

/// Removes a named alias pointer (spec `function-zones-and-aliases.md` §2).
/// `Authorize: Public` — load-or-404 + reach is [Access#byAddress], run in
/// `execute`. A single-aggregate [Operation] (not a [io.flowcatalyst.sdk.usecase.op.TxOperation]):
/// removing a named alias is HTTP-only by ruling — it never touches
/// `TriggerSync`, unlike promoting/deleting/disabling the function, which
/// all reconcile `live`'s wiring.
public final class RemoveAlias {

    private RemoveAlias() {
    }

    public static Operation<RemoveAliasCommand, AliasRemoved> of(FunctionRepository functions, FunctionVersionRepository versions) {
        Objects.requireNonNull(functions, "functions");
        Objects.requireNonNull(versions, "versions");
        return Operation.<RemoveAliasCommand, AliasRemoved>named("RemoveAlias")
                .authorize(Operation.Authorize.publicAccess())
                .execute((cmd, ec) -> {
                    Function f = Access.byAddress(functions, cmd.address(), Auth.current());
                    Function.Removed removed = f.removeAlias(cmd.alias(), Instant.now());
                    // The removed pointer's target must still exist — an alias never outlives its
                    // version (fn_aliases' version_id FK cascades on delete, ruling R4).
                    FunctionVersion v = versions.findById(removed.versionId())
                            .orElseThrow(() -> UseCaseException.resourceNotFound("FunctionVersion", removed.versionId()));
                    AliasRemoved event = AliasRemoved.of(ec, removed.function(), cmd.alias(), v);
                    return Plan.save(removed.function(), functions, event);
                });
    }
}
