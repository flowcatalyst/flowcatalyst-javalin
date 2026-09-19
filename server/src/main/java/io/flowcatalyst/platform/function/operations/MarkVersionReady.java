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
/// The operation guards ITSELF (review fix, slice B3): it loads the version
/// in `execute` and refuses with conflict `VERSION_NOT_PUBLISHED` unless it
/// is still `Published` — a `Ready` or `Retired` version never gets a second
/// `version:ready` event, so `Plan`'s "no no-op variant" rule (spec §8 P6:
/// no event when nothing becomes ready) is enforced here, not only by the
/// caller. `FunctionControlApi`'s heartbeat handler keeps its OWN `Published`
/// pre-check too, so the routine case (a host re-reporting an
/// already-`Ready` version) never even calls this operation; it catches
/// exactly the `VERSION_NOT_PUBLISHED` conflict this guard raises and treats
/// it as ignorable — the residue of a race between two heartbeats, not a
/// failure.
public final class MarkVersionReady {

    /// The conflict [FunctionControlApi] catches and ignores — a race
    /// between its own `Published` pre-check and this operation's guard.
    public static final String VERSION_NOT_PUBLISHED = "VERSION_NOT_PUBLISHED";

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
                    if (!(v.state() instanceof FunctionVersion.VersionState.Published)) {
                        throw UseCaseException.conflict(VERSION_NOT_PUBLISHED,
                                "version " + v.version() + " is not PUBLISHED (already " + stateName(v.state()) + ")");
                    }
                    Function f = functions.findById(v.functionId())
                            .orElseThrow(() -> UseCaseException.resourceNotFound("Function", v.functionId()));
                    FunctionVersion ready = v.markReady(ec.initiatedAt());
                    VersionReady event = VersionReady.of(ec, f, ready, cmd.hostId());
                    return Plan.save(ready, versions, event);
                });
    }

    private static String stateName(FunctionVersion.VersionState state) {
        return switch (state) {
            case FunctionVersion.VersionState.Published ignored -> "PUBLISHED";
            case FunctionVersion.VersionState.Ready ignored -> "READY";
            case FunctionVersion.VersionState.Retired ignored -> "RETIRED";
        };
    }
}
