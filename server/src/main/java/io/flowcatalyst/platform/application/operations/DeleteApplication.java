package io.flowcatalyst.platform.application.operations;

import io.flowcatalyst.platform.application.Application;
import io.flowcatalyst.platform.application.ApplicationRepository;
import io.flowcatalyst.platform.application.operations.ApplicationEvents.ApplicationDeleted;
import io.flowcatalyst.platform.function.FunctionRepository;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.op.Operation;
import io.flowcatalyst.sdk.usecase.op.Plan;

/// Hard-deletes an application and emits [ApplicationDeleted]. Client
/// configs are not cascaded (spec §3, open question 6).
///
/// Guarded by `functions` (spec `function-api.md` §4.2): there is no FK from
/// `fn_functions` to `app_applications` (`function-registry.md` §2), so an
/// application delete that did not check would orphan every function under
/// it, permanently — nobody could ever reuse that address again.
public final class DeleteApplication {

    private DeleteApplication() {
    }

    public static Operation<DeleteCommand, ApplicationDeleted> of(ApplicationRepository repo, FunctionRepository functions) {
        return Operation.<DeleteCommand, ApplicationDeleted>named("DeleteApplication")
                .validate(cmd -> UseCaseException.requireNonBlank(cmd.id(), "ID_REQUIRED", "id is required"))
                .authorize(Operation.Authorize.publicAccess()) // platform-level; load-or-404 is in Access.byId
                .execute((cmd, ec) -> {
                    Application a = Access.byId(repo, cmd.id());
                    long count = functions.countByApplication(a.id());
                    if (count > 0) {
                        throw UseCaseException.conflict("APPLICATION_HAS_FUNCTIONS",
                                "delete its " + count + " functions first");
                    }
                    return Plan.delete(a, repo, ApplicationDeleted.of(ec, a));
                });
    }
}
