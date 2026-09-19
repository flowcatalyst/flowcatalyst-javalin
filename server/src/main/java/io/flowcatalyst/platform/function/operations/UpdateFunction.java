package io.flowcatalyst.platform.function.operations;

import io.flowcatalyst.platform.function.Function;
import io.flowcatalyst.platform.function.FunctionRepository;
import io.flowcatalyst.platform.function.operations.FunctionEvents.FunctionUpdated;
import io.flowcatalyst.platform.shared.auth.Auth;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.op.Operation;
import io.flowcatalyst.sdk.usecase.op.TxOperation;

import java.time.Instant;
import java.util.Objects;

/// Applies `description`/`status` (absent = untouched) and emits ONE
/// [FunctionUpdated] (spec `function-api.md` §4.2). `applicationId`,
/// `address`, `owner` and `runtime` are not on the command at all — the
/// immutable-field guard is the API's job (reading the raw body before
/// binding), never this operation's. A [TxOperation] (not the
/// single-aggregate [Operation] this package's other by-address operations
/// use) so a genuine `status` transition runs `TriggerSync.onStatusChange`
/// (pause/resume the function's linked subscriptions and jobs, spec
/// `function-invocation.md` §4) in the SAME transaction as the function's own
/// status flip — [Function#enable]/[Function#disable] already refuse a
/// no-op flip (`FUNCTION_ALREADY_ACTIVE`/`FUNCTION_ALREADY_DISABLED`), so
/// reaching that call always means a real transition happened. A
/// `description`-only update never calls it. `Authorize: Public` —
/// load-or-404 + reach is [Access#byAddress], run in `execute`
/// (`CONVENTIONS.md` §3).
public final class UpdateFunction {

    private UpdateFunction() {
    }

    public static TxOperation<UpdateCommand, FunctionUpdated> of(FunctionRepository repo, TriggerSync triggerSync) {
        Objects.requireNonNull(repo, "repo");
        Objects.requireNonNull(triggerSync, "triggerSync");
        return TxOperation.<UpdateCommand, FunctionUpdated>named("UpdateFunction")
                .authorize(Operation.Authorize.publicAccess()) // load-or-404 + reach is Access.byAddress, below
                .execute((scoped, cmd, ec) -> {
                    Function f = Access.byAddress(repo, cmd.address(), Auth.current());
                    Instant now = Instant.now();
                    boolean statusChanged = false;
                    if (cmd.description() != null) {
                        f = f.describe(cmd.description(), now);
                    }
                    if (cmd.status() != null) {
                        f = switch (cmd.status()) {
                            case "ACTIVE" -> f.enable(now);
                            case "DISABLED" -> f.disable(now);
                            default -> throw UseCaseException.validation("STATUS_INVALID",
                                    "status must be ACTIVE or DISABLED");
                        };
                        statusChanged = true;
                    }
                    FunctionUpdated event = FunctionUpdated.of(ec, f);
                    scoped.commit(f, repo, event, cmd);
                    if (statusChanged) {
                        triggerSync.onStatusChange(scoped, f, ec);
                    }
                    return event;
                });
    }
}
