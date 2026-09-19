package io.flowcatalyst.platform.function.operations;

import io.flowcatalyst.platform.function.Function;
import io.flowcatalyst.platform.function.FunctionRepository;
import io.flowcatalyst.platform.function.FunctionVersion;
import io.flowcatalyst.platform.function.FunctionVersionRepository;
import io.flowcatalyst.platform.function.operations.FunctionEvents.VersionReady;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.op.Operation;
import io.flowcatalyst.sdk.usecase.op.TxOperation;

/// Marks a function version `READY` once a host reports it loaded or
/// registered (spec `function-api.md` §6.2, §3's `version:ready` event).
///
/// The operation guards ITSELF (review fix, slice B3): a [TxOperation], not
/// the single-aggregate [Operation] every other by-address operation in this
/// package uses, because the guard must be atomic with the write under a
/// real race. It takes `SELECT … FOR UPDATE` on the version row
/// ([FunctionVersionRepository#lockById]) INSIDE the transaction, then
/// guards `Published` on THAT locked, freshly-hydrated read — never on a
/// read taken before the transaction opened, which two simultaneous
/// heartbeats could both observe as `Published` and both pass. The loser of
/// the row lock sees the winner's committed `Ready` state and is refused
/// with conflict `VERSION_NOT_PUBLISHED`, so a `Ready` or `Retired` version
/// never gets a second `version:ready` event — `Plan`'s "no no-op variant"
/// rule (spec §8 P6: no event when nothing becomes ready) is enforced here,
/// not only by the caller. `FunctionControlApi`'s heartbeat handler keeps
/// its OWN `Published` pre-check too, so the routine case (a host
/// re-reporting an already-`Ready` version) never even calls this
/// operation; it catches exactly the `VERSION_NOT_PUBLISHED` conflict this
/// guard raises and treats it as ignorable — the residue of a race between
/// two heartbeats, not a failure.
public final class MarkVersionReady {

    /// The conflict [FunctionControlApi] catches and ignores — a race
    /// between its own `Published` pre-check and this operation's guard.
    public static final String VERSION_NOT_PUBLISHED = "VERSION_NOT_PUBLISHED";

    private MarkVersionReady() {
    }

    public static TxOperation<MarkVersionReadyCommand, VersionReady> of(FunctionVersionRepository versions,
            FunctionRepository functions) {
        return TxOperation.<MarkVersionReadyCommand, VersionReady>named("MarkVersionReady")
                // The caller is a host authenticated as `platform:function-host` (spec §2, §6.2),
                // not a reach-checked human/service principal — /control/functions/* gates on
                // FUNCTION_HOST_CONTROL instead. There is no per-resource reach to check here.
                .authorize(Operation.Authorize.publicAccess())
                .execute((scoped, cmd, ec) -> {
                    FunctionVersion v = versions.lockById(cmd.versionId(), scoped.dbTx())
                            .orElseThrow(() -> UseCaseException.resourceNotFound("FunctionVersion", cmd.versionId()));
                    if (!(v.state() instanceof FunctionVersion.VersionState.Published)) {
                        throw UseCaseException.conflict(VERSION_NOT_PUBLISHED,
                                "version " + v.version() + " is not PUBLISHED (already " + stateName(v.state()) + ")");
                    }
                    Function f = functions.findById(v.functionId())
                            .orElseThrow(() -> UseCaseException.resourceNotFound("Function", v.functionId()));
                    FunctionVersion ready = v.markReady(ec.initiatedAt());
                    VersionReady event = VersionReady.of(ec, f, ready, cmd.hostId());
                    return scoped.commit(ready, versions, event, cmd);
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
