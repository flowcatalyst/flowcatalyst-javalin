package io.flowcatalyst.platform.function.operations;

import io.flowcatalyst.platform.function.Function;
import io.flowcatalyst.platform.function.FunctionRepository;
import io.flowcatalyst.platform.function.FunctionVersion;
import io.flowcatalyst.platform.function.FunctionVersionRepository;
import io.flowcatalyst.platform.function.operations.FunctionEvents.VersionReady;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.op.Operation;
import io.flowcatalyst.sdk.usecase.op.Plan;

/// Marks a function version `READY` once a host reports it loaded or
/// registered (spec `function-api.md` §6.2, §3's `version:ready` event).
///
/// The ONLY guard on "does this actually become ready" — spec §8 P6: no
/// event when nothing becomes ready — is the caller's own check that the
/// version is still `Published` before it invokes this operation at all
/// (`FunctionControlApi`'s heartbeat handler); `Plan` has no "no-op" variant,
/// so an operation that runs at all always commits a version row and a
/// `version:ready` event. That is deliberate: it is the one place the guard
/// can live, so the mutant that drops it (spec §8 P6) is unambiguous.
public final class MarkVersionReady {

    private MarkVersionReady() {
    }

    public static Operation<MarkVersionReadyCommand, VersionReady> of(FunctionVersionRepository versions,
            FunctionRepository functions) {
        return Operation.<MarkVersionReadyCommand, VersionReady>named("MarkVersionReady")
                // The caller is a host authenticated as `platform:function-host` (spec §2, §6.2),
                // not a reach-checked human/service principal — /control/functions/* gates on
                // FUNCTION_HOST_CONTROL instead. There is no per-resource reach to check here.
                .authorize(Operation.Authorize.publicAccess())
                .execute((cmd, ec) -> {
                    FunctionVersion v = versions.findById(cmd.versionId())
                            .orElseThrow(() -> UseCaseException.resourceNotFound("FunctionVersion", cmd.versionId()));
                    Function f = functions.findById(v.functionId())
                            .orElseThrow(() -> UseCaseException.resourceNotFound("Function", v.functionId()));
                    FunctionVersion ready = v.markReady(ec.initiatedAt());
                    VersionReady event = VersionReady.of(ec, f, ready, cmd.hostId());
                    return Plan.save(ready, versions, event);
                });
    }
}
