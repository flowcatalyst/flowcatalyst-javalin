package io.flowcatalyst.platform.function.operations;

import io.flowcatalyst.platform.function.Function;
import io.flowcatalyst.platform.function.FunctionRepository;
import io.flowcatalyst.platform.function.operations.FunctionEvents.FunctionUpdated;
import io.flowcatalyst.platform.shared.auth.Auth;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.op.Operation;
import io.flowcatalyst.sdk.usecase.op.Plan;

import java.time.Instant;

/// Applies `description`/`status` (absent = untouched) and emits ONE
/// [FunctionUpdated] (spec `function-api.md` §4.2). `applicationId`,
/// `address`, `owner` and `runtime` are not on the command at all — the
/// immutable-field guard is the API's job (reading the raw body before
/// binding), never this operation's. `Authorize: Public` — load-or-404 +
/// reach is [Access#byAddress], run in `execute` (`CONVENTIONS.md` §3).
public final class UpdateFunction {

    private UpdateFunction() {
    }

    public static Operation<UpdateCommand, FunctionUpdated> of(FunctionRepository repo) {
        return Operation.<UpdateCommand, FunctionUpdated>named("UpdateFunction")
                .authorize(Operation.Authorize.publicAccess()) // load-or-404 + reach is Access.byAddress, below
                .execute((cmd, ec) -> {
                    Function f = Access.byAddress(repo, cmd.address(), Auth.current());
                    Instant now = Instant.now();
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
                    }
                    return Plan.save(f, repo, FunctionUpdated.of(ec, f));
                });
    }
}
