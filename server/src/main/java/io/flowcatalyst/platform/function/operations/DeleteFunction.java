package io.flowcatalyst.platform.function.operations;

import io.flowcatalyst.platform.function.Function;
import io.flowcatalyst.platform.function.FunctionRepository;
import io.flowcatalyst.platform.function.operations.FunctionEvents.FunctionDeleted;
import io.flowcatalyst.platform.shared.auth.Auth;
import io.flowcatalyst.sdk.usecase.op.Operation;
import io.flowcatalyst.sdk.usecase.op.Plan;

/// Deletes a function; the database cascades to its versions, aliases and
/// routes (ruling R4, spec `function-api.md` §4.2 — `FunctionRepository#delete`
/// need not do anything extra). `Authorize: Public` — load-or-404 + reach is
/// [Access#byAddress], run in `execute` (`CONVENTIONS.md` §3).
public final class DeleteFunction {

    private DeleteFunction() {
    }

    public static Operation<DeleteCommand, FunctionDeleted> of(FunctionRepository repo) {
        return Operation.<DeleteCommand, FunctionDeleted>named("DeleteFunction")
                .authorize(Operation.Authorize.publicAccess()) // load-or-404 + reach is Access.byAddress, below
                .execute((cmd, ec) -> {
                    Function f = Access.byAddress(repo, cmd.address(), Auth.current());
                    return Plan.delete(f, repo, FunctionDeleted.of(ec, f));
                });
    }
}
