package io.flowcatalyst.platform.function.operations;

import io.flowcatalyst.platform.function.Function;
import io.flowcatalyst.platform.function.FunctionRepository;
import io.flowcatalyst.platform.function.FunctionVersion;
import io.flowcatalyst.platform.function.FunctionVersionRepository;
import io.flowcatalyst.platform.function.operations.FunctionEvents.AliasChanged;
import io.flowcatalyst.platform.shared.auth.Auth;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.op.Operation;
import io.flowcatalyst.sdk.usecase.op.TxOperation;

import java.time.Instant;
import java.util.Objects;

/// Promotes a version to an alias — today always `live` (spec
/// `function-api.md` §5.2, R3). `Authorize: Public` — load-or-404 + reach is
/// [Access#byAddress], run in `execute`.
///
/// R3's own guard lives HERE, not in [Function#promote]: only a `PUBLISHED`
/// (not yet ready) version is rejected with `VERSION_NOT_READY` — a
/// `RETIRED` version is NOT `Ready` either, but it must fall through to
/// [Function#promote]'s own `VERSION_RETIRED` check instead (spec §8 P12:
/// "promote back to retired v1 ⇒ 409 VERSION_RETIRED", not `VERSION_NOT_READY`).
/// `Function.promote` then owns its own errors: `VERSION_RETIRED`,
/// `FUNCTION_DISABLED`, `ALIAS_UNCHANGED`.
///
/// Review fix, slice B3: alias validity is checked in `validate` — BEFORE
/// authorize/execute ever load the function or its version — so `PUT
/// …/aliases/canary` on an unready version is 400 `ALIAS_UNSUPPORTED`, not
/// 409 `VERSION_NOT_READY`; [Function#requireSupportedAlias] is the rule's
/// one home, called again inside [Function#promote] itself so the two paths
/// can never disagree and the message is written once.
public final class PromoteVersion {

    private PromoteVersion() {
    }

    /// A [TxOperation] (not the single-aggregate [Operation] this package's
    /// other by-address operations use) so `TriggerSync.onPromote`'s
    /// reconciliation of the function's subscriptions/pool/scheduled jobs
    /// runs in the SAME transaction as the alias change (spec
    /// `function-invocation.md` §4: "reconcile ... inside the promote
    /// transaction") — a trigger write that cannot be honoured rolls the
    /// alias change back too.
    public static TxOperation<PromoteCommand, AliasChanged> of(FunctionRepository functions,
            FunctionVersionRepository versions, TriggerSync triggerSync) {
        Objects.requireNonNull(functions, "functions");
        Objects.requireNonNull(versions, "versions");
        Objects.requireNonNull(triggerSync, "triggerSync");
        return TxOperation.<PromoteCommand, AliasChanged>named("PromoteVersion")
                .validate(cmd -> Function.requireSupportedAlias(cmd.alias()))
                .authorize(Operation.Authorize.publicAccess())
                .execute((scoped, cmd, ec) -> {
                    Function f = Access.byAddress(functions, cmd.address(), Auth.current());
                    FunctionVersion v = versions.findByFunctionAndVersion(f.id(), cmd.version())
                            .orElseThrow(() -> UseCaseException.resourceNotFound("FunctionVersion",
                                    f.address().render() + "#" + cmd.version()));

                    // R3: only "not yet ready" is this operation's own guard — a RETIRED version
                    // is deliberately left to Function.promote's own VERSION_RETIRED check below.
                    if (v.state() instanceof FunctionVersion.VersionState.Published) {
                        throw UseCaseException.conflict("VERSION_NOT_READY",
                                "version " + v.version() + " has not been verified by any host in pool '"
                                        + v.manifest().pool().value() + "' yet");
                    }

                    Instant now = Instant.now();
                    Function.Promoted promoted = f.promote(cmd.alias(), v, ec.principalId(), now);
                    AliasChanged event = AliasChanged.of(ec, promoted.function(), cmd.alias(), v, promoted.previousVersionId());
                    scoped.commit(promoted.function(), functions, event, cmd);

                    FunctionVersion previousLive = promoted.previousVersionId() == null ? null
                            : versions.findById(promoted.previousVersionId()).orElse(null);
                    triggerSync.onPromote(scoped, promoted.function(), v, previousLive, ec);

                    return event;
                });
    }
}
