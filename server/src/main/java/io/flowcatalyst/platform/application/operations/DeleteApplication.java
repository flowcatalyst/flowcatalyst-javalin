package io.flowcatalyst.platform.application.operations;

import io.flowcatalyst.platform.application.Application;
import io.flowcatalyst.platform.application.ApplicationRepository;
import io.flowcatalyst.platform.application.operations.ApplicationEvents.ApplicationDeleted;
import io.flowcatalyst.platform.function.FunctionRepository;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.op.Operation;
import io.flowcatalyst.sdk.usecase.op.Plan;

import java.util.ArrayList;
import java.util.List;

/// Hard-deletes an application and emits [ApplicationDeleted]. An active
/// application is refused first (409 `APPLICATION_ACTIVE`,
/// [Application#requireDeletable], bug 14).
///
/// The delete is also refused (409 `APPLICATION_HAS_REFERENCES`) while
/// anything still references the application: access grants, enabled
/// per-client configs, service accounts, application-scoped roles or
/// principals' application refs ([ApplicationRepository.References]). None of
/// those columns has a foreign key, so deleting would leave them dangling.
/// A disabled client config does not block the delete; the repository removes
/// it in the same transaction (spec §3; Go owner decisions #53, #55;
/// 2026-10-11 owner ruling for Java).
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
                    Application a = Access.byId(repo, cmd.id()).requireDeletable();
                    List<String> blockers = referenceBlockers(repo.countReferences(a.id()));
                    if (!blockers.isEmpty()) {
                        throw UseCaseException.conflict("APPLICATION_HAS_REFERENCES",
                                "Cannot delete application '" + a.code() + "' — " + String.join(", ", blockers)
                                        + " still reference it. Remove those before deleting.");
                    }
                    long count = functions.countByApplication(a.id());
                    if (count > 0) {
                        throw UseCaseException.conflict("APPLICATION_HAS_FUNCTIONS",
                                "delete its " + count + " functions first");
                    }
                    return Plan.delete(a, repo, ApplicationDeleted.of(ec, a));
                });
    }

    /// Each non-zero reference count named in a fixed order and wording
    /// ("2 access grants") for the `APPLICATION_HAS_REFERENCES` message.
    static List<String> referenceBlockers(ApplicationRepository.References refs) {
        var blockers = new ArrayList<String>(5);
        add(blockers, refs.accessGrants(), "access grants");
        add(blockers, refs.clientConfigs(), "client configs");
        add(blockers, refs.serviceAccounts(), "service accounts");
        add(blockers, refs.roles(), "application roles");
        add(blockers, refs.principalRefs(), "principal refs");
        return blockers;
    }

    private static void add(List<String> blockers, long n, String label) {
        if (n > 0) blockers.add(n + " " + label);
    }
}
