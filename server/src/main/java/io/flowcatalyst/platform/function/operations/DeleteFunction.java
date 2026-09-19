package io.flowcatalyst.platform.function.operations;

import io.flowcatalyst.platform.function.Function;
import io.flowcatalyst.platform.function.FunctionRepository;
import io.flowcatalyst.platform.function.operations.FunctionEvents.FunctionDeleted;
import io.flowcatalyst.platform.shared.auth.Auth;
import io.flowcatalyst.sdk.usecase.op.Operation;
import io.flowcatalyst.sdk.usecase.op.TxOperation;

import java.util.Objects;

/// Deletes a function; the database cascades to its versions, aliases and
/// routes (ruling R4, spec `function-api.md` §4.2 — `FunctionRepository#delete`
/// need not do anything extra). A [TxOperation] (not the single-aggregate
/// [Operation] this package's other by-address operations use) so
/// `TriggerSync.onDelete` removes the function's linked subscriptions, pool
/// and scheduled jobs in the SAME transaction as the function row itself
/// (spec `function-invocation.md` §4: "`DeleteFunction` deletes the linked
/// objects, then the function"). `Authorize: Public` — load-or-404 + reach
/// is [Access#byAddress], run in `execute` (`CONVENTIONS.md` §3).
public final class DeleteFunction {

    private DeleteFunction() {
    }

    public static TxOperation<DeleteCommand, FunctionDeleted> of(FunctionRepository repo, TriggerSync triggerSync) {
        Objects.requireNonNull(repo, "repo");
        Objects.requireNonNull(triggerSync, "triggerSync");
        return TxOperation.<DeleteCommand, FunctionDeleted>named("DeleteFunction")
                .authorize(Operation.Authorize.publicAccess()) // load-or-404 + reach is Access.byAddress, below
                .execute((scoped, cmd, ec) -> {
                    Function f = Access.byAddress(repo, cmd.address(), Auth.current());
                    triggerSync.onDelete(scoped, f, ec);
                    FunctionDeleted event = FunctionDeleted.of(ec, f);
                    scoped.commitDelete(f, repo, event, cmd);
                    return event;
                });
    }
}
